package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Retention for the raw SOURCE copies an ingest keeps ({@code processing.raw_copy_retention_days}, optional,
 * INGEST-RAW-SOURCE-COPIES-RETENTION-1): regular files under {@code dirs.backup()} and {@code dirs.quarantine()}
 * older than the window are deleted. Never touched: {@code backup/parked/} (unprocessed files awaiting
 * {@code drain}) and the {@code .restricted} store (its own {@code refusal_retention_days}). The window is also
 * how long a reject stays replayable. Only a count is logged, never a name (names may be values).
 */
final class RawCopyRetention {

    private static final Logger log = LoggerFactory.getLogger(RawCopyRetention.class);
    private static final long THROTTLE_MS = 60_000L;
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

    private RawCopyRetention() {}

    /** Sweep at most once a minute per Pipeline; a no-op when the key is unset. Never throws. */
    static void sweep(PipelineConfig cfg) {
        if (cfg.rawCopyRetentionDays() == null) return;
        long now = System.currentTimeMillis();
        Long prev = LAST.put(cfg.dirs().backup() + "|" + cfg.dirs().quarantine(), now);
        if (prev != null && now - prev < THROTTLE_MS) return;
        sweepNow(cfg);
    }

    /** Unthrottled sweep; returns the number of files deleted. */
    static int sweepNow(PipelineConfig cfg) {
        Integer days = cfg.rawCopyRetentionDays();
        if (days == null) return 0;
        FileTime cutoff = FileTime.from(Instant.now().minus(days, ChronoUnit.DAYS));
        int n = 0;
        String b = cfg.dirs().backup(), q = cfg.dirs().quarantine();
        if (b != null && !b.isBlank()) {
            Path root = Paths.get(b).toAbsolutePath().normalize();
            n += sweep(root, root.resolve("parked"), cutoff);
        }
        if (q != null && !q.isBlank()) {
            Path root = Paths.get(q).toAbsolutePath().normalize();
            n += sweep(root, root.resolve(".restricted"), cutoff);
        }
        if (n > 0) log.info("[INGEST] [{}] raw-copy retention deleted {} file(s) older than {}d",
                cfg.identity().pipelineName(), n, days);
        return n;
    }

    private static int sweep(Path root, Path skip, FileTime cutoff) {
        if (!Files.isDirectory(root)) return 0;
        int n = 0;
        try (Stream<Path> w = Files.walk(root)) {
            for (Path p : (Iterable<Path>) w.filter(Files::isRegularFile)::iterator) {
                if (p.startsWith(skip)) continue;
                try {
                    if (Files.getLastModifiedTime(p).compareTo(cutoff) < 0 && Files.deleteIfExists(p)) n++;
                } catch (IOException e) {
                    log.warn("[INGEST] raw-copy retention could not delete a file: {}", e.getClass().getSimpleName());
                }
            }
        } catch (IOException e) {
            log.warn("[INGEST] raw-copy retention sweep failed: {}", e.getClass().getSimpleName());
        }
        return n;
    }
}
