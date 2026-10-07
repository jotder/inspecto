package com.gamma.entitylist;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * MEASUREMENT harness, not a test (SP4, roadmap Data preparation): the cost of dropping Entity List members before they count,
 * as a SQL anti-join over a skewed edge relation on Parquet. Never runs by default; run with
 * <pre>mvn -o test -pl :inspecto-entity-store -am -Dtest=ListPruningSp4Bench -Dsurefire.failIfNoSpecifiedTests=false
 *   -Dbench.run=true -DargLine="--enable-native-access=ALL-UNNAMED -Xmx6g"</pre>
 * Optional: {@code -Dbench.dir=<dir>} (default {@code target/sp4-bench}), {@code -Dbench.edges=10000000},
 * {@code -Dbench.lists=1x1000,1x10000,1x1000000,10x100000,50x100000,50x1000000} (LISTSxMEMBERS), {@code -Dbench.inMax=2000}
 * (largest literal IN list tried), {@code -Dbench.threads=4}. Output: stdout and {@code <dir>/sp4-results.txt}.
 *
 * <p>Corpus: {@code nodes = edges / 2}, string ids ({@code +44} + 9 digits, like a normalised MSISDN); src = {@code floor(nodes*u^3)},
 * dst = {@code floor(nodes*v^2)} with u, v deterministic hashes of the row number: heavy-tailed, hubs are the low ids.
 * List members are hash-spread over the node universe PLUS (list 1) the 1000 hubs, so the lists really remove hub traffic.
 * Hop A = 1000 uniform-random seeds; hop B = 1000 skewed seeds (hub-heavy); hop C = whole-relation degree count.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "bench.run", matches = "true")
class ListPruningSp4Bench {

    private static final String FMT = "('+44' || lpad((%s)::BIGINT::VARCHAR, 9, '0'))";

    @Test
    void listAntiJoinCost() throws Exception {
        Path dir = Path.of(System.getProperty("bench.dir", "target/sp4-bench")).toAbsolutePath();
        long edgesN = Long.getLong("bench.edges", 10_000_000L);
        long nodes = edgesN / 2;
        int threads = Integer.getInteger("bench.threads", 4);
        int inMax = Integer.getInteger("bench.inMax", 2_000);
        Files.createDirectories(dir);
        Path edgeFile = dir.resolve("edges_" + edgesN + ".parquet");
        List<String> out = new ArrayList<>();
        try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dir), List.of(dir));
             Statement st = c.createStatement()) {
            st.execute("SET threads=" + threads);
            if (!Files.isRegularFile(edgeFile)) {
                long t = System.nanoTime();
                st.execute("COPY (SELECT " + fmt("floor(" + nodes + " * pow((hash(i) % 1000003) / 1000003.0, 3))") + " AS src, "
                        + fmt("floor(" + nodes + " * pow((hash(i * 7 + 13) % 1000003) / 1000003.0, 2))") + " AS dst "
                        + "FROM range(" + edgesN + ") r(i)) TO '" + p(edgeFile) + "' (FORMAT PARQUET, ROW_GROUP_SIZE 122880)");
                say(out, "# generated %d edges in %.1f s".formatted(edgesN, (System.nanoTime() - t) / 1e9));
            }
            Path sortedFile = dir.resolve("edges_" + edgesN + "_sorted.parquet");
            if (!Files.isRegularFile(sortedFile))
                st.execute("COPY (SELECT * FROM read_parquet('" + p(edgeFile) + "') ORDER BY src) TO '" + p(sortedFile)
                        + "' (FORMAT PARQUET, ROW_GROUP_SIZE 122880)");
            String edgesScan = "read_parquet('" + p(edgeFile) + "')";
            String edgesSorted = "read_parquet('" + p(sortedFile) + "')";
            String edges = edgesScan;
            st.execute("CREATE TABLE fr_rand AS SELECT DISTINCT " + fmt("hash(i * 31 + 5) % " + nodes) + " AS k FROM range(1000) r(i)");
            st.execute("CREATE TABLE fr_skew AS SELECT DISTINCT " + fmt("floor(" + nodes + " * pow((hash(i * 17 + 3) % 1000003) / 1000003.0, 3))")
                    + " AS k FROM range(1000) r(i)");
            say(out, "# edges=%d nodes=%d threads=%d  load: see roadmap findings".formatted(edgesN, nodes, threads));
            for (String t : new String[]{"fr_rand", "fr_skew"}) {
                long[] r = Sp4Hop.consume(c, "SELECT e.dst, count(*) c FROM " + edges + " e JOIN " + t + " f ON e.src = f.k GROUP BY e.dst");
                say(out, "# baseline %s: groups=%d budget(edges expanded)=%d".formatted(t, r[0], r[1]));
            }
            say(out, "lists x members | form | hop | min ms | median ms | base median ms | added % | groups | budget");

            for (String cfg : System.getProperty("bench.lists", "1x1000,1x10000,1x1000000,10x100000,50x100000,50x1000000").split(",")) {
                String[] lm = cfg.trim().split("x");
                int lists = Integer.parseInt(lm[0]);
                long members = Long.parseLong(lm[1]);
                Path root = dir.resolve("lists_" + cfg.trim());
                if (Files.isDirectory(root)) try (var w = Files.walk(root)) {
                    w.sorted(Collections.reverseOrder()).forEach(q -> q.toFile().delete());
                }
                StringBuilder ids = new StringBuilder();
                for (int l = 1; l <= lists; l++) {
                    Path ld = Files.createDirectories(root.resolve("entity_list_excl" + l));
                    // same schema as EntityListSidecar; 1% of entries expire (half already in the past)
                    st.execute("COPY (SELECT 'excl" + l + "' AS list_id, 'exclude' AS purpose, 'msisdn' AS entity_type, 'key' AS match, entry, "
                            + "entry AS lo, entry AS hi, CASE WHEN h % 100 = 0 THEN (now() + INTERVAL 1 DAY * ((h % 3)::INTEGER - 1))::TIMESTAMP END AS expires_at, "
                            + "now()::TIMESTAMP AS added_at, 'bench' AS added_by, 'bench' AS reason FROM (SELECT DISTINCT entry, "
                            + "hash(entry) AS h FROM (SELECT " + fmt("hash(i * 1000003 + " + l + ") % " + nodes) + " AS entry FROM range(" + members + ") r(i)"
                            + (l == 1 ? " UNION ALL SELECT " + fmt("i") + " FROM range(1000) r(i)" : "") + "))) TO '" + p(ld.resolve("entries.parquet")) + "' (FORMAT PARQUET)");
                    if (ids.length() > 0) ids.append(',');
                    ids.append("'excl").append(l).append('\'');
                }
                String rel = "read_parquet('" + p(root) + "/entity_list_*/entries.parquet')";
                String listIds = ids.toString();
                st.execute("DROP TABLE IF EXISTS lt");
                st.execute("DROP TABLE IF EXISTS list_member");
                long t0 = System.nanoTime();
                st.execute("CREATE TABLE lt AS SELECT * FROM " + rel);
                double loadMs = (System.nanoTime() - t0) / 1e6;
                t0 = System.nanoTime();
                st.execute("CREATE TABLE list_member AS SELECT DISTINCT entry FROM (" + Sp4Hop.liveKeys("lt", listIds) + ")");
                double deriveMs = (System.nanoTime() - t0) / 1e6;
                long distinct = scalar(st, "SELECT count(*) FROM list_member");
                say(out, "# cfg %s: sidecar rows=%d distinct live members=%d; one-off load-to-table %.0f ms, derive list_member %.0f ms"
                        .formatted(cfg.trim(), scalar(st, "SELECT count(*) FROM lt"), distinct, loadMs, deriveMs));

                StringBuilder lits = new StringBuilder();
                boolean inOk = distinct <= inMax;
                if (inOk) {
                    try (ResultSet rs = st.executeQuery("SELECT entry FROM list_member")) {
                        while (rs.next()) lits.append(lits.length() > 0 ? "," : "").append('\'').append(rs.getString(1)).append('\'');
                    }
                }
                for (String hopName : new String[]{"A-rand1000", "B-skew1000", "C-degree-all"})
                  for (String layout : new String[]{"unsorted", "sorted-by-src"}) {
                    String hop = hopName + "/" + layout;
                    if (layout.startsWith("sorted") && hopName.startsWith("C")) continue;   // degree-all scans everything either way
                    edges = layout.startsWith("sorted") ? edgesSorted : edgesScan;
                    String fr = hopName.startsWith("A") ? "fr_rand" : "fr_skew";
                    boolean degree = hopName.startsWith("C");
                    for (Sp4Hop.Form f : Sp4Hop.Form.values()) {
                        if (f == Sp4Hop.Form.NONE || (f == Sp4Hop.Form.IN_LIST && !inOk)) continue;
                        String rr = f == Sp4Hop.Form.LIST_TABLE ? "lt" : f == Sp4Hop.Form.LIST_MEMBER ? "list_member" : rel;
                        String sql = degree ? Sp4Hop.degreeSql(f, edges, rr, listIds, lits.toString())
                                : Sp4Hop.sql(f, edges, fr, rr, listIds, lits.toString());
                        long[] res = new long[2];
                        double[] r = times(c, sql, res);
                        double[] b = times(c, degree ? Sp4Hop.degreeSql(Sp4Hop.Form.NONE, edges, null, null, null)
                                : Sp4Hop.sql(Sp4Hop.Form.NONE, edges, fr, null, null, null), null);
                        double baseMed = b[1];
                        say(out, "%s | %s | %s | %.0f | %.0f | %.0f | %+.0f%% | %d | %d".formatted(cfg.trim(), f, hop, r[0], r[1], baseMed,
                                (r[1] / baseMed - 1) * 100, res[0], res[1]));
                    }
                }
            }
        }
        Files.write(dir.resolve("sp4-results.txt"), out);
        try (PrintWriter pw = new PrintWriter(System.out)) { pw.flush(); }
    }

    /** {min, median} ms of 3 timed runs after 1 warm-up; the result of the last run goes to {@code res}. */
    private static double[] times(Connection c, String sql, long[] res) throws Exception {
        Sp4Hop.consume(c, sql);
        double[] t = new double[3];
        for (int i = 0; i < 3; i++) {
            long s = System.nanoTime();
            long[] r = Sp4Hop.consume(c, sql);
            t[i] = (System.nanoTime() - s) / 1e6;
            if (res != null) { res[0] = r[0]; res[1] = r[1]; }
        }
        java.util.Arrays.sort(t);
        return new double[]{t[0], t[1]};
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private static String fmt(String expr) {
        return FMT.formatted(expr);
    }

    private static String p(Path path) {
        return path.toString().replace('\\', '/').replace("'", "''");
    }

    private static void say(List<String> out, String line) {
        out.add(line);
        System.out.println("[SP4] " + line);
    }
}
