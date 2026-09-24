package com.gamma.job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The ONE reader of a Space's {@code maintenance_storage} sample series (the Parquet rows
 * {@code storage_report} appends): per-sample totals and each axis's latest bytes + two-point bytes/day
 * slope over a window. Shared by {@link StorageTrendTask} and {@link SpaceComparisonJob} — extracted
 * rather than copied because two copies of the slope calculation disagree the first time either is
 * touched, and a slope that differs between two reports is worse than no report
 * (space-comparison design §3).
 *
 * <p>Takes a data root, never a Space id: resolving WHICH Space's root may be read is the caller's
 * (authorized) decision, not this reader's.
 */
final class StorageSeries {

    static final String STORAGE_CATALOG = "maintenance_storage";
    private static final long DAY_MS = 86_400_000L;

    private StorageSeries() {}

    /** One axis's growth over the window: its latest observed size and its bytes/day slope. */
    record AxisTrend(String axis, long currentBytes, double bytesPerDay) {}

    /**
     * The in-window series: {@code totals} = {@code {createdMs, totalBytes}} per sample, ascending;
     * {@code axes} is empty when fewer than two samples fall in the window (no slope can be fitted).
     */
    record Window(List<long[]> totals, List<AxisTrend> axes) {
        int samples() { return totals.size(); }

        /** Window span in days between the earliest and latest sample (floored just above zero). */
        double spanDays() {
            long span = totals.get(totals.size() - 1)[0] - totals.get(0)[0];
            return Math.max(span, 1L) / (double) DAY_MS;
        }
    }

    /** {@code <dataRoot>/maintenance_storage}. */
    static Path storeDir(Path dataRoot) {
        return dataRoot.resolve(STORAGE_CATALOG);
    }

    /** True when the catalog dir exists and holds at least one Parquet sample (a glob over an empty dir throws). */
    static boolean hasHistory(Path storeDir) throws IOException {
        if (!Files.isDirectory(storeDir)) return false;
        try (var s = Files.list(storeDir)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".parquet"));
        }
    }

    /** Read the last {@code windowDays} of the series under {@code storeDir} (caller checks {@link #hasHistory}). */
    static Window read(Path storeDir, int windowDays) throws Exception {
        long cutoffMs = Instant.now().minus(Duration.ofDays(windowDays)).toEpochMilli();
        String glob = "'" + storeDir.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                + "/*.parquet'";
        List<long[]> totals = new ArrayList<>();
        List<AxisTrend> axes = new ArrayList<>();
        com.gamma.util.DuckDbUtil.loadDriver();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT created_ms, CAST(sum(bytes) AS BIGINT) FROM read_parquet("
                    + glob + ") WHERE created_ms >= " + cutoffMs + " GROUP BY created_ms ORDER BY created_ms")) {
                while (rs.next()) totals.add(new long[]{rs.getLong(1), rs.getLong(2)});
            }
            if (totals.size() < 2) return new Window(totals, List.of());
            double spanDays = new Window(totals, List.of()).spanDays();
            try (ResultSet rs = st.executeQuery("SELECT axis, arg_min(bytes, created_ms), "
                    + "arg_max(bytes, created_ms) FROM read_parquet(" + glob + ") WHERE created_ms >= "
                    + cutoffMs + " GROUP BY axis")) {
                while (rs.next()) {
                    long first = rs.getLong(2), last = rs.getLong(3);
                    axes.add(new AxisTrend(rs.getString(1), last, (last - first) / spanDays));
                }
            }
        }
        return new Window(totals, axes);
    }
}
