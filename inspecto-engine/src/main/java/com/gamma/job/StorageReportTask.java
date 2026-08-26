package com.gamma.job;

import com.gamma.config.safety.PathJail;
import com.gamma.signal.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The {@code storage_report} maintenance task (MNT-3): read-only storage observation over {@code dir}
 * (typically the space root). Reports per-axis (immediate subdirectory) file counts and bytes, records
 * each axis as a Run Artifact so the series accumulates queryably run over run, logs the {@code top}
 * largest files, and — when {@code warn_bytes} is set and breached — emits a
 * {@code maintenance.storage.threshold} WARNING signal an Alert Rule can subscribe to.
 */
final class StorageReportTask {

    private static final Logger log = LoggerFactory.getLogger(StorageReportTask.class);

    /** The Dataset {@code storage_report} appends its per-axis samples to (one file per run, glob-union)
     *  and {@code storage_trend} reads (MNT-3 → COULD-tier growth-trend analysis). */
    private static final String STORAGE_CATALOG = "maintenance_storage";

    private StorageReportTask() {}

    static JobResult run(JobConfig cfg, String dataDir, JobContext ctx) {
        // Read-only, but still jailed: the walk logs the largest files by full path, so an unjailed
        // `dir` is directory enumeration of anywhere the server can read.
        Path dir = PathJail.requireUnderAny(PathJail.allowedRoots(), cfg.require("dir"), "dir");
        long warnBytes = Long.parseLong(cfg.opt("warn_bytes", "0"));   // 0 = no threshold
        int top = Integer.parseInt(cfg.opt("top", "5"));
        long t0 = System.nanoTime();
        if (!Files.isDirectory(dir)) {
            return JobResult.ok("storage_report: directory not present, nothing to report (" + dir + ")", 0L);
        }
        Map<String, long[]> axes = new TreeMap<>();          // axis -> {files, bytes}
        List<Map.Entry<Path, Long>> files = new ArrayList<>();
        long totalBytes = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                long size;
                try {
                    size = Files.size(p);
                } catch (IOException e) {
                    log.warn("storage_report: could not size {}: {}", p, e.getMessage());
                    continue;
                }
                Path rel = dir.relativize(p);
                String axis = rel.getNameCount() > 1 ? rel.getName(0).toString() : ".";
                long[] acc = axes.computeIfAbsent(axis, k -> new long[2]);
                acc[0]++;
                acc[1] += size;
                files.add(Map.entry(p, size));
                totalBytes += size;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("storage_report walk failed under " + dir, e);
        }
        boolean breached = warnBytes > 0 && totalBytes > warnBytes;
        if (ctx != null) {
            axes.forEach((axis, acc) -> ctx.artifacts().file("axis:" + axis,
                    ".".equals(axis) ? dir : dir.resolve(axis), acc[1]));
            files.sort(Map.Entry.<Path, Long>comparingByValue().reversed());
            for (Map.Entry<Path, Long> f : files.subList(0, Math.min(top, files.size())))
                ctx.log().info("largest consumer", "path", f.getKey().toString(), "bytes", f.getValue());
            if (breached)
                ctx.signals().emit("maintenance.storage.threshold", Severity.WARN,
                        Map.of("dir", dir.toString(), "totalBytes", totalBytes, "warnBytes", warnBytes));
            // Persist this run's per-axis sample so the series accumulates queryably for storage_trend —
            // real runs only (a dry-run preview must never add a data point to the trend).
            if (!ctx.dryRun()) storageCatalog(ctx, dataDir, axes);
        }
        StringBuilder perAxis = new StringBuilder();
        axes.forEach((axis, acc) -> perAxis.append(perAxis.isEmpty() ? "" : ", ")
                .append(axis).append('=').append(acc[1]).append('b'));
        return JobResult.ok("storage_report: " + files.size() + " file(s), " + totalBytes + " byte(s) under "
                + dir + " [" + perAxis + "]"
                + (breached ? " — OVER warn_bytes=" + warnBytes : ""),
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /**
     * Append this {@code storage_report} run's per-axis sample as one Parquet (a row per axis) in
     * {@code <dataDir>/maintenance_storage/} (readers glob {@code *.parquet} — rows union across runs)
     * and idempotently register the {@code maintenance_storage} Dataset, the {@link BackupTask} /
     * {@link MaterializeTask} catalog idiom. {@code created_ms} is the sortable/filterable sample key
     * (epoch millis — ISO-string comparison is not reliably chronological across variable precision).
     * Best-effort: a missing data/write root or a Parquet failure is noted in the Run Log, never fails
     * the report.
     */
    private static void storageCatalog(JobContext ctx, String dataDir, Map<String, long[]> axes) {
        String writeRoot = System.getProperty("assist.write.root");
        if (dataDir == null || dataDir.isBlank() || writeRoot == null || writeRoot.isBlank()) {
            ctx.log().info("storage_report catalog skipped (no data root / write root configured)");
            return;
        }
        try {
            Instant now = Instant.now();
            Path storeDir = Path.of(dataDir).resolve(STORAGE_CATALOG);
            Files.createDirectories(storeDir);
            Path parquet = storeDir.resolve("storage_" + now.toEpochMilli() + "_out.parquet");
            com.gamma.util.DuckDbUtil.loadDriver();
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE storage_sample (created VARCHAR, created_ms BIGINT, "
                            + "axis VARCHAR, files BIGINT, bytes BIGINT)");
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO storage_sample VALUES (?,?,?,?,?)")) {
                    for (Map.Entry<String, long[]> e : axes.entrySet()) {
                        ps.setString(1, now.toString());
                        ps.setLong(2, now.toEpochMilli());
                        ps.setString(3, e.getKey());
                        ps.setLong(4, e.getValue()[0]);
                        ps.setLong(5, e.getValue()[1]);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (Statement st = conn.createStatement()) {
                    st.execute("COPY storage_sample TO '"
                            + parquet.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                            + "' (FORMAT PARQUET)");
                }
            }
            com.gamma.pipeline.ComponentStore store =
                    new com.gamma.pipeline.ComponentStore(Path.of(writeRoot).resolve("registry"));
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("name", STORAGE_CATALOG);
            content.put("physicalRef", STORAGE_CATALOG);
            content.put("description", "System Maintenance storage-usage samples (one row per axis per run, MNT-3)");
            store.write("dataset", STORAGE_CATALOG, content, false);   // result-stamp write, no version churn
            ctx.artifacts().dataset(STORAGE_CATALOG, STORAGE_CATALOG, null, (long) axes.size(), null);
        } catch (Exception e) {
            log.warn("storage_report catalog append failed (report itself succeeded): {}", e.getMessage());
            ctx.log().warn("storage_report catalog append failed: " + e.getMessage());
        }
    }
}
