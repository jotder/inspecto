package com.gamma.la.storage;

import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.PatternQueryCompiler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DUCKDB-2-LOOP-QUERIES-1 C4 gate: the {@code afterPrevious} test of a branching-pattern stage - "this leg is later than the
 * earliest leg the previous stage reached its source by" - written three ways over the same legs statement
 * ({@link PatternQueryCompiler#legs}), at {@code inspecto.bench.sizes} rows (default 10^6 and 10^7):
 * <ul>
 *   <li><b>correlated</b> - today's {@code e.ts > (SELECT min(p.ts) FROM prev p WHERE p.t = e.s)};</li>
 *   <li><b>asof</b> - {@code __e ASOF JOIN prev p ON p.t = e.s AND e.ts > p.ts} (the research's candidate);</li>
 *   <li><b>grouped</b> - a join to {@code (SELECT t, min(ts) FROM prev GROUP BY t)}.</li>
 * </ul>
 * The three answers must be the same rows (count and an order-free checksum). Prints timings; runs only with
 * {@code -Dinspecto.bench.dir=...}.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class BranchingLegsBench {

    /** {@code SELECT * FROM __eI e WHERE e.s IN (SELECT t FROM __sJ) AND e.ts > (SELECT min(p.ts) FROM __sJ p WHERE p.t = e.s)}. */
    private static final Pattern AFTER = Pattern.compile(
            "SELECT \\* FROM (__e\\d+) e WHERE e\\.s IN \\(SELECT t FROM (__s\\d+)\\) AND e\\.ts > \\(SELECT min\\(p\\.ts\\) FROM \\2 p WHERE p\\.t = e\\.s\\)");

    static String asof(String sql) {
        return rewrite(sql, "SELECT e.* FROM $1 e ASOF JOIN $2 p ON p.t = e.s AND e.ts > p.ts WHERE true");
    }

    static String grouped(String sql) {
        return rewrite(sql, "SELECT e.* FROM $1 e JOIN (SELECT t, min(ts) AS mt FROM $2 GROUP BY t) p ON p.t = e.s AND e.ts > p.mt WHERE true");
    }

    private static String rewrite(String sql, String replacement) {
        Matcher m = AFTER.matcher(sql);
        if (!m.find()) throw new AssertionError("no afterPrevious test in: " + sql);
        return m.replaceAll(replacement);
    }

    @Test
    void correlatedVersusAsofVersusGrouped() throws Exception {
        int runs = Integer.parseInt(System.getProperty("inspecto.bench.runs", "3"));
        List<PatternQueryCompiler.Stage> stages = PatternQueryCompiler.parseStages(List.of(
                Map.of("shape", "fan-out", "minBranches", 3),
                Map.of("shape", "fan-in", "minBranches", 2, "afterPrevious", true),
                Map.of("shape", "fan-out", "minBranches", 2, "afterPrevious", true)));
        PatternQueryCompiler.Compiled legs = PatternQueryCompiler.legs("ds", "src", "dst", null, "ts", stages,
                DatasetProvider.BoundFilter.TRUE);
        for (String size : System.getProperty("inspecto.bench.sizes", "1000000,10000000").split(",")) {
            long rows = Long.parseLong(size.trim()), nodes = Math.max(1000, rows / 5);
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE ds AS SELECT 'n' || CAST(floor(" + nodes + " * pow((hash(i * 2) % 1000000) / 1e6, 3)) AS BIGINT) AS src,"
                        + " 'n' || CAST(floor(" + nodes + " * pow((hash(i * 2 + 1) % 1000000) / 1e6, 2)) AS BIGINT) AS dst,"
                        + " TIMESTAMP '2026-01-01' + to_seconds(CAST(hash(i * 3) % 2592000 AS BIGINT)) AS ts FROM range(" + rows + ") r(i)");
                String[] names = {"correlated", "asof", "grouped"};
                String[] sqls = {legs.sql(), asof(legs.sql()), grouped(legs.sql())};
                long[] best = new long[3];
                long[][] answer = new long[3][];
                for (int v = 0; v < 3; v++) {
                    best[v] = Long.MAX_VALUE;
                    for (int r = 0; r < runs; r++) {
                        long t = System.nanoTime();
                        answer[v] = run(c, sqls[v], legs.binds());
                        best[v] = Math.min(best[v], (System.nanoTime() - t) / 1_000_000);
                    }
                }
                System.out.printf("C4 rows=%d  correlated %d ms  asof %d ms  grouped %d ms  legs %d / %d / %d%n",
                        rows, best[0], best[1], best[2], answer[0][0], answer[1][0], answer[2][0]);
                for (int v = 1; v < 3; v++)
                    if (answer[v][0] != answer[0][0] || answer[v][1] != answer[0][1])
                        throw new AssertionError(names[v] + " answers differently at " + rows + " rows");
            }
        }
    }

    /** {count, order-free checksum} of the legs. */
    private static long[] run(Connection c, String sql, List<String> binds) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*), coalesce(sum(hash(stage, s, t, ts)), 0) FROM (" + sql + ")")) {
            for (int i = 0; i < binds.size(); i++) ps.setString(i + 1, binds.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[] {rs.getLong(1), rs.getLong(2)};
            }
        }
    }
}
