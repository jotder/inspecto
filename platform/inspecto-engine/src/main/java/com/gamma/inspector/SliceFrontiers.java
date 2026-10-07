package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.CollectorConnectors;
import com.gamma.etl.PipelineConfig;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * The durable half of a remote slice's frontier (STREAM-CONSUMER-1, operator decision Q3 2026-09-24: a restart
 * must never re-ingest). {@code kafka} and {@code db} stash their reached offset / row watermark in memory
 * ({@link AcquisitionLedgers#stashDbWatermark}); a restart between land and commit used to lose it, so the slice
 * committed without advancing the frontier and the next cycle re-read it.
 *
 * <p><b>The record.</b> One small JSON sidecar {@code {"key","value"}} per landed slice, under
 * {@code <staging>/.frontier/}, mirrored by poll-relative path — the {@link CommitRetry} idiom. It is written
 * (temp file + atomic rename) <em>before</em> the slice's own atomic land, so a slice is never visible in the
 * inbox without its frontier; the commit reads it when the in-memory stash is gone and deletes it only after the
 * ledger holds the value. {@link #restore} re-stashes the records of landed-but-uncommitted slices before a
 * discovery, which is what lets the in-flight fence ({@link AcquisitionLedgers#hasPendingDbWatermark}) survive a
 * restart too. Durable exactly as far as the ledger is: with the default in-memory ledger the committed frontier
 * itself is lost on restart, whatever this record says.
 */
final class SliceFrontiers {

    private static final Logger log = LoggerFactory.getLogger(SliceFrontiers.class);
    private static final Gson GSON = new Gson();

    /** The reserved subdirectory of the staging tree; a remote listing may never stage into it. */
    static final String DIR = ".frontier";
    private static final String EXT = ".json";

    private record Rec(String key, String value) {}

    private SliceFrontiers() {}

    /** {@code <staging>/.frontier}, or {@code null} for a local collector (no staging tree, no slices). */
    static Path root(PipelineConfig cfg) {
        if (!CollectorConnectors.isRemote(cfg)) return null;
        try {
            return RemoteAcquisitionHandler.stagingRoot(cfg, cfg.collector().fetch()).resolve(DIR);
        } catch (IllegalStateException e) {
            return null;   // no staging tree resolvable ⇒ nothing was ever landed through one
        }
    }

    private static Path pollRoot(PipelineConfig cfg) {
        return Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
    }

    private static Path sidecar(Path root, PipelineConfig cfg, Path inboxFile) {
        return root.resolve(pollRoot(cfg).relativize(inboxFile.toAbsolutePath().normalize()) + EXT);
    }

    /**
     * Before the land: make {@code target}'s record say {@code wm}, or remove a stale one when the slice carries
     * no frontier. Throws when it cannot, and the caller then does not land — a slice without its durable
     * frontier is exactly the re-ingest this class exists to prevent.
     */
    static void write(PipelineConfig cfg, Path target, Optional<AcquisitionLedgers.DbWatermark> wm) throws IOException {
        Path root = root(cfg);
        if (root == null) return;
        Path side = sidecar(root, cfg, target);
        if (wm.isEmpty()) {
            Files.deleteIfExists(side);
            return;
        }
        Files.createDirectories(side.getParent());
        Path tmp = side.resolveSibling(side.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(new Rec(wm.get().key(), wm.get().value())), StandardCharsets.UTF_8);
        Files.move(tmp, side, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The durable frontier recorded for landed slice {@code inboxFile}, or empty (none, or unreadable — logged). */
    static Optional<AcquisitionLedgers.DbWatermark> read(PipelineConfig cfg, Path inboxFile) {
        Path root = root(cfg);
        return root == null ? Optional.empty() : read(sidecar(root, cfg, inboxFile));
    }

    private static Optional<AcquisitionLedgers.DbWatermark> read(Path side) {
        if (!Files.isRegularFile(side)) return Optional.empty();
        try {
            Rec r = GSON.fromJson(Files.readString(side, StandardCharsets.UTF_8), Rec.class);
            if (r == null || r.key() == null || r.value() == null) return Optional.empty();
            return Optional.of(new AcquisitionLedgers.DbWatermark(r.key(), r.value()));
        } catch (IOException | RuntimeException e) {
            log.warn("Unreadable slice frontier record {}: {}", side, e.getMessage());
            return Optional.empty();
        }
    }

    /** The slice committed (its frontier is in the ledger) or never landed: its record is spent. Best-effort. */
    static void delete(PipelineConfig cfg, Path inboxFile) {
        Path root = root(cfg);
        if (root == null) return;
        try {
            Files.deleteIfExists(sidecar(root, cfg, inboxFile));
        } catch (IOException e) {
            log.warn("Could not delete slice frontier record for {}: {}", inboxFile, e.getMessage());
        }
    }

    /**
     * Before a discovery: re-stash the frontier of every landed-but-uncommitted slice, so a restart neither loses
     * it nor opens the fence. Records whose slice is gone (never landed, quarantined, backed up) or whose value
     * the ledger already holds (committed; only the delete was lost) are removed instead.
     */
    static void restore(PipelineConfig cfg) {
        Path root = root(cfg);
        if (root == null || !Files.isDirectory(root)) return;
        List<Path> records;
        try (var s = Files.walk(root)) {
            records = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(EXT)).toList();
        } catch (IOException e) {
            log.warn("Could not scan slice frontier records under {}: {}", root, e.getMessage());
            return;
        }
        Path poll = pollRoot(cfg);
        for (Path side : records) {
            String rel = root.relativize(side).toString();
            Path inbox = poll.resolve(rel.substring(0, rel.length() - EXT.length())).normalize();
            Optional<AcquisitionLedgers.DbWatermark> wm = read(side);
            boolean spent = wm.isEmpty() || !Files.exists(inbox)
                    || AcquisitionLedgers.shared().dbWatermark(wm.get().key()).map(wm.get().value()::equals).orElse(false);
            if (spent) {
                try { Files.deleteIfExists(side); } catch (IOException ignore) { /* retried next cycle */ }
            } else {
                AcquisitionLedgers.restoreDbWatermark(inbox, wm.get().key(), wm.get().value());
            }
        }
    }
}
