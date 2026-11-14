package com.gamma.la.storage;

import com.gamma.la.core.TemporalCut;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * DUCKDB-2-LOOP-QUERIES-1 C3 bench: the {@link TemporalCut} statement against the read it replaces (every capped row hauled
 * to the JVM), at {@code inspecto.bench.events} events (default 10^7) over {@code inspecto.bench.links} links, both modes.
 * Sizes the {@code pattern.temporal_max_rows} default (plan D-LQ3). Runs only with {@code -Dinspecto.bench.dir=...};
 * prints, asserts nothing, so it never gates a build.
 */
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class TemporalCutBench {

    private static long prop(String k, long d) {
        return Long.parseLong(System.getProperty("inspecto.bench." + k, Long.toString(d)));
    }

    @Test
    void cutVersusHaul() throws Exception {
        long events = prop("events", 10_000_000), links = prop("links", 200_000);
        int runs = (int) prop("runs", 3);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            // Noise: every link gets events spread over 30 days; 1 % of links get a dense burst and 1 % a regular series.
            st.execute("CREATE TABLE ev AS SELECT 'n' || (i % " + links + ") AS s, 'm' || (i % 97) AS t,"
                    + " CAST(hash(i) % 2592000000 AS BIGINT) AS ts FROM range(" + events + ") r(i)");
            st.execute("INSERT INTO ev SELECT 'n' || (i % " + (links / 100) + "), 'burst', 1000000 + (i % 50) * 1000"
                    + " FROM range(" + (links / 100 * 20) + ") r(i)");
            st.execute("INSERT INTO ev SELECT 'n' || (i % " + (links / 100) + "), 'tick', (i / " + (links / 100) + ") * 3600000"
                    + " FROM range(" + (links / 100 * 24) + ") r(i)");
            long total = scalar(st, "SELECT count(*) FROM ev");
            System.out.printf("BENCH events=%d links=%d threads=%s%n", total, links, scalar(st, "SELECT current_setting('threads')"));
            for (long cap : new long[] {400_000, 2_000_000, 4_000_000, 10_000_000}) {
                for (boolean burst : new boolean[] {true, false}) {
                    TemporalCut.Spec spec = new TemporalCut.Spec(burst, false, 60_000, 5, 0.1);
                    long haul = Long.MAX_VALUE, cut = Long.MAX_VALUE, kept = 0, hauled = 0;
                    for (int r = 0; r < runs; r++) {
                        long t0 = System.nanoTime();
                        hauled = 0;
                        try (ResultSet rs = st.executeQuery("SELECT s, t, ts FROM ev ORDER BY s, t, ts NULLS LAST LIMIT " + cap)) {
                            while (rs.next()) { rs.getString(1); rs.getString(2); rs.getLong(3); hauled++; }
                        }
                        haul = Math.min(haul, (System.nanoTime() - t0) / 1_000_000);
                        t0 = System.nanoTime();
                        try (ResultSet rs = st.executeQuery(TemporalCut.sql("SELECT s, t, ts FROM ev", (int) cap, spec))) {
                            kept = TemporalCut.read(rs).rows().size();
                        }
                        cut = Math.min(cut, (System.nanoTime() - t0) / 1_000_000);
                    }
                    System.out.printf("BENCH cap=%d mode=%s haul=%d ms (%d rows) cut=%d ms (%d rows kept)%n",
                            cap, burst ? "burst" : "periodicity", haul, hauled, cut, kept);
                }
            }
        }
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
