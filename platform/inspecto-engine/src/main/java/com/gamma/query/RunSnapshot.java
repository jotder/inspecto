package com.gamma.query;

import com.gamma.job.PlatformServices;
import com.gamma.job.SnapshotSwapLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * deadline (30 minutes by default, 24 hours at most). So the pin keeps the FILES instead: under the store's read lock, for
 * as long as it takes to enumerate and link, each pinned file gets a hard link in this Run's own directory
 * ({@code <dataRoot>/.run-snapshots/<uuid>/}), and the relation reads the links. A swap or a retirement then removes the
 * store's name for the file and the Run's name keeps its bytes. A file system without hard links (or a store on another
 * volume) gets a copy. A publish waits only for a pin under way, never for a Run.
 *
 * <p><b>Released when the Run ends</b> — {@link #close()}, from the {@code finally} of the Run whether it succeeded, failed,
 * was cancelled ({@code interruptRuns}) or hit its deadline: the Run's directory is deleted, and a read after that is
 * refused. A directory a crash or a still-open file left behind is deleted by the next Run that pins in that data root.
 *
 * <p>⚠ Assumes a published Parquet file is never rewritten in place (every writer publishes by rename), since a link shares
 * the file's bytes. Not pinned per Run: a {@code view}-backed Dataset (no file list; read afresh each time) and a
 * {@link DatasetAccess} that is not the Space's own (a pack-contributed one), which keep their per-call behaviour.
 */
public final class RunSnapshot implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunSnapshot.class);

    /** The directory under a data root that holds each live Run's links; hidden, so no store walk sees it. */
    static final String DIR = ".run-snapshots";

    /** Every Run directory a live snapshot owns, JVM-wide: the sweep leaves these alone. */
    private static final Set<Path> LIVE = ConcurrentHashMap.newKeySet();

    private final PlatformServices granted;
    private final SpaceDatasetAccess space;
    private final Map<String, DatasetAccess.Read> pins = new HashMap<>();
    private Path dir;
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
        if (now.files().isEmpty()) return now;                       // no file list (a view): read afresh each time
        try {
            pinned = pin(datasetId, cfg);
        } catch (IllegalArgumentException bad) {
            throw SpaceDatasetAccess.unusable(datasetId, bad);
        }
        if (pinned == null) return now;
        pins.put(datasetId, pinned);
        return pinned;
    }

    /** Enumerate the Dataset's store and link its files into this Run's directory, under the store's read lock. */
    private DatasetAccess.Read pin(String datasetId, Map<String, Object> cfg) {
        Path root = space.dataRoot();
        if (root == null) return null;
        try (SnapshotSwapLock.Held block = SnapshotSwapLock.hold()) {
            DatasetRelation.StoreFiles files = DatasetRelation.storeFiles(cfg, root);
            if (files == null) return null;
            Path into = runDir(root).resolve(String.valueOf(pins.size()));
            Files.createDirectories(into);
            List<String> names = new ArrayList<>();
            List<String> links = new ArrayList<>();
            for (int i = 0; i < files.paths().size(); i++) {
                Path link = into.resolve(i + ".parquet");
                if (!keep(files.paths().get(i), link)) continue;   // gone since the walk: not an input any more
                names.add(files.names().get(i));
                links.add(link.toString());
            }
            return new DatasetAccess.Read(datasetId, DatasetRelation.relationSqlOverPinned(cfg, links),
                    Optional.of(List.copyOf(names)));
        } catch (IOException e) {
            throw new IllegalStateException("Dataset read failed: could not pin dataset '" + datasetId
                    + "' for the Run: " + e.getMessage(), e);
        }
    }

    /** {@code file}'s bytes under {@code link}: a hard link, else a copy. False when the file is gone. */
    private static boolean keep(Path file, Path link) throws IOException {
        try {
            Files.createLink(link, file);
        } catch (NoSuchFileException gone) {
            return false;
        } catch (UnsupportedOperationException | FileSystemException noLink) {
            try {
                Files.copy(file, link);
            } catch (NoSuchFileException gone) {
                return false;
            }
        }
        return true;
    }

    private Path runDir(Path dataRoot) throws IOException {
        if (dir != null) return dir;
        Path base = dataRoot.toAbsolutePath().normalize().resolve(DIR);
        Path mine = base.resolve(UUID.randomUUID().toString());
        LIVE.add(mine);   // before it exists, so a concurrent sweep never takes it
        sweep(base);
        Files.createDirectories(mine);
        return dir = mine;
    }

    /** Delete the Run directories under {@code base} no live snapshot owns: left by a crash, or by a close that failed. */
    private static void sweep(Path base) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
            for (Path d : ds) {
                if (LIVE.contains(d)) continue;
                try {
                    delete(d);
                } catch (IOException e) {
                    log.debug("Run snapshot directory {} not deleted yet: {}", d, e.toString());
                }
            }
        } catch (NoSuchFileException none) {
            // no Run has pinned in this data root yet
        } catch (IOException e) {
            log.debug("Run snapshot sweep of {} skipped: {}", base, e.toString());
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
        if (dir == null) return;
        try {
            delete(dir);
        } catch (IOException e) {
            log.warn("Run snapshot {} not deleted ({}); the next Run that pins in this data root deletes it", dir, e.toString());
        } finally {
            LIVE.remove(dir);
        }
    }
}
