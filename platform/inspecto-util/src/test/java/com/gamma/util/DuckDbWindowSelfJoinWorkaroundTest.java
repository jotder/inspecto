package com.gamma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DuckDB 2.0.0's {@code window_self_join} rewrite loses hive-partition pruning in the scan it copies, so a
 * {@code count(*) OVER (PARTITION BY …)} over a day-filtered {@code read_parquet} counts the pruned day's rows too
 * ({@code DUCKDB-2-MIGRATION-1}; found as a {@code TelcoRerateGoldenTest} confidence drift, 5/6 read as 5/7).
 * {@link DuckDbUtil#applyCorrectnessSettings} disables the rewrite on every analytics connection.
 */
class DuckDbWindowSelfJoinWorkaroundTest {

    /** Rows a, b (group x, day 1), c (group x, day 2), d (group y, day 1): on day 1, group x holds TWO rows. */
    private static String corpus(Path dir) throws SQLException {
        String out = dir.resolve("p").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES ('a', 'x', DATE '2026-07-01'), ('b', 'x', DATE '2026-07-01'), "
                    + "('c', 'x', DATE '2026-07-02'), ('d', 'y', DATE '2026-07-01')) t(k, grp, d)) TO '" + out
                    + "' (FORMAT PARQUET, PARTITION_BY (d))");
        }
        // the row pick is OUTSIDE the window's input: inside, it would shrink the partition the window counts
        return "SELECT n FROM (SELECT k, count(*) OVER (PARTITION BY grp) AS n FROM read_parquet('" + out
                + "/**/*.parquet', hive_partitioning = true) WHERE d = DATE '2026-07-01') WHERE k = 'a'";
    }

    private static long n(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next(), sql);
            return r.getLong(1);
        }
    }

    @Test
    void everyDuckDbUtilConnectionCountsOnlyTheFilteredDay(@TempDir Path tmp) throws Exception {
        DuckDbUtil.loadDriver();
        String sql = corpus(tmp);
        try (Connection c = DuckDbUtil.openConnection(tmp.resolve("w.duckdb").toFile())) {
            assertEquals(2, n(c, sql), "openConnection (the Job / ingest path)");
        }
        try (Connection c = DuckDbUtil.openInMemory(tmp.resolve("spill"), List.of(tmp))) {
            assertEquals(2, n(c, sql), "openInMemory (the sealed analytics path)");
        }
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            DuckDbUtil.applyGlobalDuckDbSettings(c);
            assertEquals(2, n(c, sql), "applyGlobalDuckDbSettings");
        }
    }

    /**
     * The canary: a RAW connection still gets the wrong answer on the pinned DuckDB. When this fails (an upstream
     * fix, or a version where the rewrite is gone), drop {@code window_self_join} from
     * {@link DuckDbUtil#applyCorrectnessSettings} and this test.
     */
    @Test
    void theRawDriverStillMiscountsSoTheWorkaroundIsStillNeeded(@TempDir Path tmp) throws Exception {
        DuckDbUtil.loadDriver();
        String sql = corpus(tmp);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement(); ResultSet v = s.executeQuery("SELECT version()")) {
            v.next();
            if (!v.getString(1).startsWith("v2.")) return;   // 1.5.x never had the defect
            assertEquals(3, n(c, sql), "DuckDB " + v.getString(1) + " no longer miscounts: remove the workaround");
        }
    }
}
