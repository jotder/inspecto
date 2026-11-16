package com.gamma.query;

import com.gamma.job.PlatformServices;
import com.gamma.job.SnapshotSwapLock;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One Job Run's Dataset reads (Platform Services Stage 2, MoSCoW S6, 2026-11-17): the FIRST {@link DatasetAccess#read} of
 * a Dataset in a Run pins its files, and every later read of it in the same Run returns that pin. Two reads in one Run see
 * one file list, whatever a snapshot swap, a compaction or {@code retire_superseded} did to the store in between; the next
 * Run pins afresh.
 *
 * <p><b>The pin outlives a swap without holding the swap lock.</b> A swap renames the old {@code *.parquet} away, so a
 * remembered file list alone would name paths that are gone ({@link SnapshotSwapLock}). Holding the store's read lock for
 * the whole Run would keep them, but a publish would then wait for the slowest Run reading its store: up to the Job
 * deadline (30 minutes by default, 24 hours at most). So the pin keeps the FILES instead: under the store's read lock each
 * pinned file gets a hard link in this Run's own directory, {@code <dataRoot>/.run-snapshots/<uuid>/<n>/<its path under
 * the data root>} (the {@code key=value} folders kept, so a Hive-partitioned store reads the same columns), and the
 * relation reads the links. A swap or a retirement then removes the store's name for the file and the Run's name keeps its
 * bytes. A publish waits for a pin under way (list and link one Dataset), never for a Run.
 *
 * <p><b>Where hard links fail</b> (a file system without them, a store on another volume) the file is COPIED, but never
 * under the lock: it is opened under the lock and copied from that handle after the lock is released. A rename or delete
 * does not reach an open handle, so the copy holds exactly the pinned bytes. A pin that would copy more than
 * {@link #copyCap} bytes is not made: that Dataset is read per call, as before Stage 2, with a WARN; a Run that worked
 * without snapshots never fails because the host cannot link.
 *
 * <p><b>Released when the Run ends</b> — {@link #close()}, from the {@code finally} of the Run whether it succeeded, failed,
 * was cancelled ({@code interruptRuns}) or hit its deadline: the Run's directory is deleted, and a read after that is
 * refused. While the Run lives it holds an OS lock on {@code <its directory>/.lock}; the next Run that pins in the data
 * root deletes every Run directory whose lock it can take (a crash, or a close that could not delete), in any process,
 * and never one younger than {@link #SWEEP_AGE_FLOOR}, whose owner may not have locked it yet.
 *
 * <p>⚠ Assumes a published Parquet file is never rewritten in place (every writer publishes by rename), since a link shares
 * the file's bytes. Not pinned per Run: a {@code view}-backed Dataset (no file list; read afresh each time) and a
 * {@link DatasetAccess} that is not the Space's own (a pack-contributed one), which keep their per-call behaviour.
 */
public final class RunSnapshot implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunSnapshot.class);

    /** The directory under a data root that holds each live Run's links; every data-root walk skips it (DuckDbUtil). */
    static final String DIR = DuckDbUtil.RUN_SNAPSHOTS_DIR_NAME;

    /** The file in a Run directory whose OS lock says the Run is live. */
    static final String LOCK = ".lock";

    /** A Run directory younger than this is never swept: it may be between its creation and its owner's lock. */
    static final Duration SWEEP_AGE_FLOOR = Duration.ofMinutes(1);

    /** The most bytes one pin may copy where hard links fail; above it the Dataset is read per call (a test lowers it). */
    static volatile long copyCap = 1L << 30;

    /** Test seams: {@code false} forces the copy fallback; {@code beforeCopy} runs after the lock is released, before copying. */
    static volatile boolean hardLinks = true;
    static volatile Runnable beforeCopy = () -> { };

    private final PlatformServices granted;
    private final SpaceDatasetAccess space;
    private final Map<String, DatasetAccess.Read> pins = new HashMap<>();
    /** The Datasets this Run could not pin (no file list, or over the copy cap): read per call, decided once. */
    private final Set<String> perCall = new java.util.HashSet<>();
    private int seq;
    private Path dir;
    private FileChannel lockChannel;
    private boolean closed;

    private RunSnapshot(PlatformServices granted) {
        this.granted = granted;
        this.space = granted.find(DatasetAccess.class).orElse(null) instanceof SpaceDatasetAccess s ? s : null;
    }

    /** A snapshot for one Run over {@code granted}. Holds nothing until the Run's first read, and nothing after {@link #close()}. */
    public static RunSnapshot open(PlatformServices granted) {
        return new RunSnapshot(granted);
    }

    /** {@code granted}, with the Space's {@link DatasetAccess} replaced by this Run's. */
    public PlatformServices services() {
        if (space == null) return granted;
        DatasetAccess perRun = this::read;
        return new PlatformServices() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> find(Class<T> type) {
                return type == DatasetAccess.class ? Optional.of((T) perRun) : granted.find(type);
            }

            @Override public Set<Class<?>> granted() { return granted.granted(); }
        };
    }

    private synchronized DatasetAccess.Read read(String spaceId, String datasetId) {
        if (closed) throw new IllegalStateException("Dataset read refused: the Run has ended, dataset '" + datasetId + "'");
        Map<String, Object> cfg = space.config(spaceId, datasetId);
        DatasetAccess.Read pinned = pins.get(datasetId);
        if (pinned != null) return pinned;
        DatasetAccess.Read now = space.read(spaceId, datasetId);   // the per-call read refuses what it refuses
        if (now.files().isEmpty() || perCall.contains(datasetId)) return now;   // a view, or decided below: per call
        try {
            pinned = pin(datasetId, cfg);
        } catch (IllegalArgumentException bad) {
            throw SpaceDatasetAccess.unusable(datasetId, bad);
        }
        if (pinned == null) {
            perCall.add(datasetId);
            return now;
        }
        pins.put(datasetId, pinned);
        return pinned;
    }

    /** A file the pin opened under the lock, to copy into {@code to} once the lock is released. */
    private record Copy(FileChannel from, Path to) {}

    /** Enumerate the Dataset's store and link its files into this Run's directory; {@code null}: read it per call. */
    private DatasetAccess.Read pin(String datasetId, Map<String, Object> cfg) {
        Path root = space.dataRoot();
        if (root == null) return null;
        Path into = null;
        List<Copy> copies = new ArrayList<>();
        try {
            List<String> names = new ArrayList<>();
            List<String> links = new ArrayList<>();
            long toCopy = 0;
            try (SnapshotSwapLock.Held block = SnapshotSwapLock.hold()) {
                DatasetRelation.StoreFiles files = DatasetRelation.storeFiles(cfg, root);
                if (files == null) return null;
                into = runDir(root).resolve(String.valueOf(seq++));
                for (int i = 0; i < files.paths().size(); i++) {
                    Path file = files.paths().get(i);
                    Path link = into.resolve(files.names().get(i));   // its folders kept: key=value is a Hive column
                    Files.createDirectories(link.getParent());
                    try {
                        if (!link(file, link)) {
                            FileChannel from = FileChannel.open(file, StandardOpenOption.READ);
                            copies.add(new Copy(from, link));
                            toCopy += from.size();
                        }
                    } catch (NoSuchFileException gone) {
                        continue;   // gone since the walk: not an input any more
                    }
                    names.add(files.names().get(i));
                    links.add(link.toString());
                }
            }   // the store's lock ends here; a copy below reads through a handle opened under it
            if (!copies.isEmpty()) {
                if (toCopy > copyCap) {
                    log.warn("Dataset '{}' is read per call in this Run, not pinned: this file system cannot hard-link it "
                            + "and a copy would be {} bytes, over the {}-byte cap", datasetId, toCopy, copyCap);
                    delete(into);
                    return null;
                }
                log.warn("Dataset '{}': {} file(s), {} bytes, copied for this Run's snapshot; this file system cannot "
                        + "hard-link them", datasetId, copies.size(), toCopy);
                beforeCopy.run();
                for (Copy c : copies) copy(c);
            }
            return new DatasetAccess.Read(datasetId, DatasetRelation.relationSqlOverPinned(cfg, links),
                    Optional.of(List.copyOf(names)));
        } catch (IOException | RuntimeException e) {
            if (into != null) {
                try {
                    delete(into);   // a partial pin: the Run's later pins start in a folder of their own anyway
                } catch (IOException left) {
                    e.addSuppressed(left);
                }
            }
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Dataset read failed: could not pin dataset '" + datasetId
                    + "' for the Run: " + e.getMessage(), e);
        } finally {
            for (Copy c : copies) {
                try {
                    c.from().close();
                } catch (IOException ignored) {
                    // a read handle; nothing was written through it
                }
            }
        }
    }

    /** Hard-link {@code file} at {@code link}; false when this file system cannot (copy it instead). */
    private static boolean link(Path file, Path link) throws IOException {
        if (!hardLinks) return false;
        try {
            Files.createLink(link, file);
            return true;
        } catch (NoSuchFileException gone) {
            throw gone;
        } catch (UnsupportedOperationException | FileSystemException noLink) {
            return false;
        }
    }

    private static void copy(Copy c) throws IOException {
        try (FileChannel out = FileChannel.open(c.to(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            long size = c.from().size();
            for (long pos = 0; pos < size; ) pos += c.from().transferTo(pos, size - pos, out);
        }
    }

    private Path runDir(Path dataRoot) throws IOException {
        if (dir != null) return dir;
        Path base = dataRoot.toAbsolutePath().normalize().resolve(DIR);
        Path mine = base.resolve(UUID.randomUUID().toString());
        Files.createDirectories(mine);
        FileChannel ch = FileChannel.open(mine.resolve(LOCK), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            ch.lock();   // held for the Run's life: no sweep, in this process or another, takes this directory
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
        lockChannel = ch;
        dir = mine;
        sweep(base, mine);
        return dir;
    }

    /** Delete the Run directories under {@code base} no live Run owns: left by a crash, or by a close that failed. */
    private static void sweep(Path base, Path mine) {
        Instant floor = Instant.now().minus(SWEEP_AGE_FLOOR);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
            for (Path d : ds) {
                try {
                    if (d.equals(mine) || !Files.isDirectory(d)) continue;
                    if (Files.getLastModifiedTime(d).toInstant().isAfter(floor)) continue;   // its owner may lock it yet
                    if (!unowned(d)) continue;
                    delete(d);
                } catch (IOException e) {
                    log.debug("Run snapshot directory {} not swept: {}", d, e.toString());
                }
            }
        } catch (IOException e) {
            log.debug("Run snapshot sweep of {} skipped: {}", base, e.toString());
        }
    }

    /** Whether no live Run, in this process or another, holds {@code d}'s lock. */
    private static boolean unowned(Path d) {
        try (FileChannel ch = FileChannel.open(d.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock taken = ch.tryLock();
            if (taken == null) return false;
            taken.release();
            return true;
        } catch (OverlappingFileLockException | IOException heldOrUnreadable) {
            return false;   // this process holds it, or another process does (Windows refuses the open or the lock)
        }
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (NoSuchFileException gone) {
            // already gone
        }
    }

    /** Release every pin of this Run: delete its links; later reads are refused. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        pins.clear();
        perCall.clear();
        if (dir == null) return;
        try {
            lockChannel.close();   // releases the lock; the directory goes next
            delete(dir);
        } catch (IOException e) {
            log.warn("Run snapshot {} not deleted ({}); a later Run that pins in this data root deletes it", dir, e.toString());
        }
    }
}
