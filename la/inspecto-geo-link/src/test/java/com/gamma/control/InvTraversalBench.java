package com.gamma.control;

import com.gamma.la.api.InvRoutes;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MEASUREMENT harness, not a test: LA-11 gate G-R4 (5 hops over 1 000 000 edges in under 350 ms through
 * {@code POST /inv/traversal/recursive-paths}) and the option-D spikes D-S1 / D-S3 (D-S2, DuckPGQ, was dropped 2026-10-01)
 * ({@code docs/archived-documents/plans-archive/la-separation-feasibility-plan.md} §7.10). Results are recorded there.
 *
 * <p>Never runs in the default suite: it needs {@code -Dinspecto.bench.dir=<dir>} (generated Parquet goes there —
 * keep it under {@code .claude/worktrees/}, never commit it). Example:
 * {@code mvn -o test -pl :inspecto-geo-link -Dtest=InvTraversalBench -Dsurefire.failIfNoSpecifiedTests=false
 * -Dinspecto.bench.dir=C:/sandbox/inspecto-clean/.claude/worktrees/bench-data}
 * (optional {@code -Dinspecto.bench.sizes=1000000,10000000,100000000}).
 *
 * <p>Corpus: {@code nodes = edges / 5}; source {@code = floor(nodes · u³)}, target {@code = floor(nodes · u²)} with
 * {@code u} a deterministic hash of the row number — a heavy-tailed (Zipf-like) out-degree, mean 5, a few hubs
 * with ~1–2 % of all edges. The D-S3 statement mirrors the route's DIRECTED, untimed recursive CTE (InvRoutes).
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class InvTraversalBench {

    private static final Path DIR = Path.of(System.getProperty("inspecto.bench.dir", "."));
    private final HttpClient client = HttpClient.newHttpClient();

    // ---------------------------------------------------------------- corpus

    private static Path flat(long edges) throws SQLException {
        Path f = DIR.resolve("edges_" + edges + ".parquet");
        if (Files.exists(f)) return f;
        long nodes = edges / 5;
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT 'n' || CAST(floor(" + nodes + " * pow(hash(i) / 1.8446744073709552e19, 3)) AS BIGINT) AS src,"
                    + " 'n' || CAST(floor(" + nodes + " * pow(hash(i + 7777777777) / 1.8446744073709552e19, 2)) AS BIGINT) AS dst,"
                    + " CAST(hash(i + 3) % 100000 AS DOUBLE) / 100 AS amount,"
                    + " TIMESTAMP '2026-01-01' + to_seconds(CAST(hash(i + 5) % 31536000 AS BIGINT)) AS ts"
                    + " FROM range(" + edges + ") t(i)) TO '" + sqlPath(f) + "' (FORMAT parquet)");
        }
        return f;
    }

    /** The §7.4 proposal: hash bucket of the from-entity, sorted by (entity, time), small row groups. */
    private static Path partitioned(long edges, int buckets) throws SQLException {
        Path d = DIR.resolve("edges_" + edges + "_b" + buckets);
        if (Files.exists(d)) return d;
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT *, CAST(hash(src) % " + buckets + " AS INTEGER) AS bucket FROM read_parquet('"
                    + sqlPath(flat(edges)) + "') ORDER BY bucket, src, ts) TO '" + sqlPath(d)
                    + "' (FORMAT parquet, PARTITION_BY (bucket), ROW_GROUP_SIZE 100000)");
        }
        return d;
    }

    private static String sqlPath(Path p) { return p.toAbsolutePath().toString().replace('\\', '/'); }

    /** {p50-degree node, p99-degree node, the top hub} by out-degree. */
    private static List<String> probes(Connection c, Path f) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "WITH d AS (SELECT src, count(*) n FROM read_parquet('" + sqlPath(f) + "') GROUP BY src),"
                        + " q AS (SELECT quantile_disc(n, 0.5) p50, quantile_disc(n, 0.99) p99, max(n) mx FROM d)"
                        + " SELECT (SELECT min(src) FROM d, q WHERE n = q.p50), (SELECT min(src) FROM d, q WHERE n = q.p99),"
                        + " (SELECT min(src) FROM d, q WHERE n = q.mx), q.p50, q.p99, q.mx FROM q")) {
            r.next();
            out.add(r.getString(1)); out.add(r.getString(2)); out.add(r.getString(3));
            log("probes " + f.getFileName() + ": p50 deg " + r.getLong(4) + " (" + r.getString(1) + "), p99 deg " + r.getLong(5)
                    + " (" + r.getString(2) + "), max deg " + r.getLong(6) + " (" + r.getString(3) + ")");
        }
        return out;
    }

    // ---------------------------------------------------------------- G-R4 through the real route

    @Test
    void gateR4_fiveHopsOverOneMillionEdges() throws Exception {
        Path f = flat(1_000_000);
        List<String> probes;
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) { probes = probes(c, f); }
        String[] labels = {"p50-degree start", "p99-degree start", "top hub start"};
        for (int p = 0; p < probes.size(); p++) {
            String body = "{\"dataset\":\"edges_ds\",\"sourceCol\":\"src\",\"targetCol\":\"dst\",\"startNode\":\""
                    + probes.get(p) + "\",\"maxDepth\":5}";
            List<Double> cold = new ArrayList<>(), warm = new ArrayList<>();
            String shape = "";
            for (int run = 0; run < 5; run++) {          // cold = first request on a freshly started ControlApi
                Path cfg = Files.createTempDirectory(DIR, "cfg"), root = Files.createTempDirectory(DIR, "root");
                Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
                System.setProperty("assist.write.root", root.toString());
                CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
                ControlApi api = new ControlApi(svc, 0);
                try {
                    api.start();
                    new ViewStore(root.resolve("views")).write(new ViewDefinition("edges_view", "flow-x", List.of(),
                            "SELECT * FROM read_parquet('" + sqlPath(f) + "')", "2026-09-30T00:00:00Z"));
                    new ComponentStore(root.resolve("registry")).write("dataset", "edges_ds", Map.of("view", "edges_view"));
                    cold.add(time(api.port(), body)[0]);
                    for (int w = 0; w < (run == 0 ? 20 : 0); w++) {
                        double[] t = time(api.port(), body);
                        warm.add(t[0]);
                        shape = lastShape;
                    }
                } finally {
                    api.close(); svc.close();
                    System.clearProperty("assist.write.root");
                }
            }
            log(String.format("G-R4 %s (%s): cold p50 %.1f ms p95 %.1f ms | warm p50 %.1f ms p95 %.1f ms (n=%d) | %s",
                    labels[p], probes.get(p), pct(cold, 50), pct(cold, 95), pct(warm, 50), pct(warm, 95), warm.size(), shape));
        }
    }

    private String lastShape = "";

    private double[] time(int port, String body) throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/inv/traversal/recursive-paths"))
                .method("POST", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        double ms = (System.nanoTime() - t0) / 1e6;
        assertEquals(200, r.statusCode(), r.body());
        String b = r.body();
        int paths = b.split("\"hops\"", -1).length - 1;
        lastShape = "paths=" + paths + " truncated=" + b.contains("\"truncated\":true")
                + " edgeYieldCapped=" + b.contains("\"edgeYieldCapped\":true");
        return new double[]{ms};
    }

    // ---------------------------------------------------------------- D-S1 pruning + one-hop latency

    @Test
    void spikeDS1_pruningAndOneHop() throws Exception {
        for (long n : sizes()) {
            Path f = flat(n), d = partitioned(n, 64);
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
                String node = probes(c, f).get(0);
                String flatQ = "SELECT dst, ts FROM read_parquet('" + sqlPath(f) + "') WHERE src = '" + node + "'";
                String partNoKey = "SELECT dst, ts FROM read_parquet('" + sqlPath(d) + "/**/*.parquet', hive_partitioning = true)"
                        + " WHERE src = '" + node + "'";
                String partKey = partNoKey + " AND bucket = CAST(hash('" + node + "') % 64 AS INTEGER)";
                for (String[] q : new String[][]{{"flat file", flatQ}, {"partitioned, no bucket predicate", partNoKey},
                        {"partitioned + bucket predicate", partKey}}) {
                    List<Double> t = new ArrayList<>();
                    for (int i = 0; i < 11; i++) t.add(ms(s, q[1]));
                    t.remove(0);
                    log(String.format("D-S1 n=%d %s: one-hop p50 %.2f ms p95 %.2f ms", n, q[0], pct(t, 50), pct(t, 95)));
                    if (n == sizes().get(sizes().size() - 1)) log("EXPLAIN ANALYZE (" + q[0] + ")\n" + explain(s, q[1]));
                }
            }
        }
    }

    // ---------------------------------------------------------------- D-S3 depth x volume curve

    @Test
    void spikeDS3_depthByVolume() throws Exception {
        for (long n : sizes()) {
            Path f = flat(n);
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
                String start = probes(c, f).get(1);  // p99-degree start: busy, not the pathological hub
                s.execute("CREATE VIEW edges AS SELECT * FROM read_parquet('" + sqlPath(f) + "')");
                for (boolean mat : new boolean[]{true, false})
                for (int yield : new int[]{10_000, 100_000}) {
                    for (int depth = 2; depth <= 8; depth += 2) {
                        List<Double> t = new ArrayList<>();
                        long rows = 0;
                        for (int i = 0; i < 4; i++) {
                            long t0 = System.nanoTime();
                            try (ResultSet r = s.executeQuery(walk(start, depth, yield, mat))) { r.next(); rows = r.getLong(1); }
                            if (i > 0) t.add((System.nanoTime() - t0) / 1e6);
                        }
                        log(String.format("D-S3 n=%d materialized=%b yield=%d depth=%d: p50 %.1f ms max %.1f ms, rows walked %d",
                                n, mat, yield, depth, pct(t, 50), pct(t, 100), rows));
                    }
                }
                if (n == sizes().get(0)) log("EXPLAIN ANALYZE (depth 5, yield 10000)\n" + explain(s, walk(start, 5, 10_000, true)));
            }
        }
    }

    /** The route's DIRECTED, untimed recursive member (InvRoutes.recursivePaths) with its fences inlined; {@code materialized} = the route as of G-R4. */
    private static String walk(String start, int depth, int yield, boolean materialized) {
        return "WITH RECURSIVE __e AS " + (materialized ? "MATERIALIZED " : "") + "(SELECT CAST(src AS VARCHAR) s, CAST(dst AS VARCHAR) t FROM edges"
                + " WHERE src IS NOT NULL AND dst IS NOT NULL),"
                + " __walk(node, path, depth) AS " + (materialized ? "MATERIALIZED " : "") + "(SELECT '" + start + "', list_value('" + start + "'), 0"
                + " UNION ALL SELECT * FROM (SELECT e.t, list_append(w.path, e.t), w.depth + 1 FROM __walk w"
                + " JOIN __e e ON e.s = w.node WHERE w.depth < " + depth + " AND NOT list_contains(w.path, e.t)"
                + " LIMIT " + yield + ")) SELECT count(*) FROM __walk";
    }

    // ---------------------------------------------------------------- SP2: CDR-shaped skew, daily vs window, ladder, plans

    /**
     * Spike SP2 (link-analysis-roadmap.md, Data preparation): a CDR-shaped SKEWED corpus (5 hubs holding 2 % of the rows as caller
     * and about 1 % as callee, a heavy-tailed rest, 7 days) laid out two ways in the D-3 index shape (both directions,
     * bucket = {@code md5_number_lower(entity) % 32} computed in Java, sorted by (entity, ts), 100k-row groups): 7 DAILY tables vs
     * ONE rolling WINDOW table, plus the FLAT unsorted Dataset. Measures AC-03 (1 hop), AC-04 (4 hops, 2 filters, 20-key frontier
     * cap, one server query per hop), the supernode barrier, and the plans.
     * Extra gate: {@code -Dbench.run=true}; size {@code -Dinspecto.bench.sp2.edges=N} (default 10 000 000); seeds
     * {@code -Dinspecto.bench.sp2.seeds} / {@code .flatSeeds}.
     */
    @Test
    @EnabledIfSystemProperty(named = "bench.run", matches = "true")
    void spikeSP2_skewedDailyVsWindow() throws Exception {
        long edges = Long.getLong("inspecto.bench.sp2.edges", 10_000_000L);
        int seedsN = Integer.getInteger("inspecto.bench.sp2.seeds", 12), flatSeedsN = Integer.getInteger("inspecto.bench.sp2.flatSeeds", 3);
        Path root = DIR.resolve("sp2_" + edges);
        sp2Build(root, edges);
        log("SP2 corpus " + edges + " edges, 7 days, 5 hubs, hubShare 2% as caller + 1% as callee; " + loadNote());
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("SET threads = 4");
            s.execute("SET memory_limit = '6GB'");
            s.execute("CREATE VIEW f_edges AS SELECT * FROM read_parquet('" + sqlPath(root.resolve("flat.parquet")) + "')");
            for (String lay : new String[]{"win", "day"})
                for (String side : new String[]{"out", "in"})
                    s.execute("CREATE VIEW " + lay + "_" + side + " AS SELECT * FROM read_parquet('" + sqlPath(root)
                            + (lay.equals("win") ? "/win/" : "/day*/") + side + "/bucket=*/*.parquet', hive_partitioning = true)");
            for (String lay : new String[]{"win", "day"}) {
                try (ResultSet r = s.executeQuery("SELECT count(*) FROM " + lay + "_out")) { r.next(); log("SP2 rows in " + lay + "_out = " + r.getLong(1)); }
                try (ResultSet r = s.executeQuery("SELECT count(DISTINCT file_name) FROM parquet_metadata('" + sqlPath(root)
                        + (lay.equals("win") ? "/win/" : "/day*/") + "out/bucket=*/*.parquet')")) { r.next(); log("SP2 layout " + lay + ": out files " + r.getLong(1)); }
            }
            try (ResultSet r = s.executeQuery("SELECT src, count(*) n FROM f_edges GROUP BY src ORDER BY n DESC LIMIT 3")) {
                while (r.next()) log("SP2 top caller " + r.getString(1) + " deg " + r.getLong(2));
            }
            // the Java bucket must equal the SQL bucket or every index read below would be a silent miss
            try (ResultSet r = s.executeQuery("SELECT md5_number_lower('n123') % " + SP2_BUCKETS)) { r.next(); assertEquals(r.getLong(1), bucket("n123")); }

            List<String> seeds = col(s, "SELECT src FROM (SELECT DISTINCT src FROM f_edges WHERE src LIKE 'n%' LIMIT 2000000) USING SAMPLE reservoir("
                    + seedsN + ") REPEATABLE (42)");
            List<String> hubSeeds = col(s, "SELECT src FROM (SELECT DISTINCT src FROM f_edges WHERE dst LIKE 'h%' AND src LIKE 'n%' LIMIT 2000000)"
                    + " USING SAMPLE reservoir(" + seedsN + ") REPEATABLE (43)");

            // AC-03 one hop, AC-04 four hops. Each series 3 runs; per run p50/p95; min/median across the 3 runs.
            for (String v : new String[]{"win", "day", "flat"}) {
                List<String> sd = v.equals("flat") ? seeds.subList(0, Math.min(flatSeedsN, seeds.size())) : seeds;
                for (int hops : new int[]{1, 4}) {
                    List<Double> p50s = new ArrayList<>(), p95s = new ArrayList<>();
                    String shape = "";
                    for (int run = 0; run < 3; run++) {
                        List<Double> t = new ArrayList<>();
                        int capped = 0;
                        double keysSum = 0;
                        sp2Ladder(s, v, sd.get(0), hops, false, new int[3]);   // warm-up
                        for (String seed : sd) {
                            int[] st = new int[3];
                            long t0 = System.nanoTime();
                            sp2Ladder(s, v, seed, hops, false, st);
                            t.add((System.nanoTime() - t0) / 1e6);
                            if (st[1] > 0) capped++;
                            keysSum += st[0];
                        }
                        p50s.add(pct(t, 50));
                        p95s.add(pct(t, 95));
                        log(String.format("SP2 progress %s %d-hop run %d: p50 %.1f p95 %.1f max %.1f ms", v, hops, run, pct(t, 50), pct(t, 95), pct(t, 100)));
                        shape = String.format("seeds=%d, ladders cut at the 20-key cap (product: FrontierOverCap refusal) = %d, mean keys looked up = %.1f",
                                sd.size(), capped, keysSum / sd.size());
                    }
                    log(String.format("SP2 %s %d-hop: p95 per run %s ms, p50 per run %s ms | p95 min %.1f median %.1f | %s",
                            v, hops, fmt(p95s), fmt(p50s), Collections.min(p95s), pct(p95s, 50), shape));
                }
            }
            // supernode barrier: hub-adjacent seeds, 4 hops, hub allowed in the frontier vs excluded (window layout)
            for (boolean barrier : new boolean[]{false, true}) {
                List<Double> p50s = new ArrayList<>(), p95s = new ArrayList<>();
                int hubHits = 0;
                for (int run = 0; run < 3; run++) {
                    List<Double> t = new ArrayList<>();
                    hubHits = 0;
                    sp2Ladder(s, "win", hubSeeds.get(0), 4, barrier, new int[3]);
                    for (String seed : hubSeeds) {
                        int[] st = new int[3];
                        long t0 = System.nanoTime();
                        sp2Ladder(s, "win", seed, 4, barrier, st);
                        t.add((System.nanoTime() - t0) / 1e6);
                        hubHits += st[2];
                    }
                    p50s.add(pct(t, 50));
                    p95s.add(pct(t, 95));
                }
                log(String.format("SP2 barrier=%b hub-adjacent seeds win 4-hop: p95 per run %s ms, p50 per run %s ms | p95 min %.1f median %.1f | ladders whose frontier held a hub = %d/%d",
                        barrier, fmt(p95s), fmt(p50s), Collections.min(p95s), pct(p95s, 50), hubHits, hubSeeds.size()));
            }
            // direct hub-key cost per layout (filters on, both directions, full fold)
            for (String v : new String[]{"win", "day", "flat"}) {
                List<Double> t = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    long t0 = System.nanoTime();
                    sp2Hop(s, v, List.of("h0"));
                    if (i > 0) t.add((System.nanoTime() - t0) / 1e6);
                }
                log(String.format("SP2 hub key h0 one hop (full fold, both directions) %s: min %.1f median %.1f ms", v, Collections.min(t), pct(t, 50)));
            }
            // plans: is the frontier join pushed BELOW the pair aggregation? (window layout, 20 keys, forward side)
            List<String> k20 = seeds.subList(0, Math.min(20, seeds.size()));
            String in = String.join(",", k20.stream().map(k -> "'" + k + "'").toList());
            String bks = String.join(",", k20.stream().map(k -> String.valueOf(bucket(k))).toList());
            String pairs = "(SELECT src, dst, count(*) c FROM win_out WHERE " + SP2_FILTER + " GROUP BY src, dst)";
            for (String[] q : new String[][]{
                    {"A. VALUES frontier JOIN pair-aggregate subquery (no bucket predicate)",
                            "SELECT p.* FROM " + pairs + " p JOIN (VALUES " + String.join(",", k20.stream().map(k -> "('" + k + "')").toList()) + ") f(k) ON p.src = f.k"},
                    {"B. IN-list on pair-aggregate subquery (no bucket predicate)", "SELECT * FROM " + pairs + " p WHERE src IN (" + in + ")"},
                    {"C. IN-list + bucket IN-list on the window table",
                            "SELECT src, dst, count(*) c FROM win_out WHERE bucket IN (" + bks + ") AND src IN (" + in + ") AND " + SP2_FILTER + " GROUP BY src, dst"},
                    {"D. per-key UNION ALL equality + bucket literal (IndexReader.edges shape)", sp2HopSql("win", k20)}}) {
                List<Double> t = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    double m = ms(s, q[1]);
                    if (i > 0) t.add(m);
                }
                log(String.format("SP2 PLAN %s: min %.1f median %.1f ms", q[0], Collections.min(t), pct(t, 50)));
                log("SP2 EXPLAIN ANALYZE " + q[0] + "\n" + explain(s, q[1]));
            }
        }
    }

    private static String fmt(List<Double> xs) { return xs.stream().map(x -> String.format("%.1f", x)).toList().toString(); }

    private static String loadNote() {
        return "cores " + Runtime.getRuntime().availableProcessors() + ", shared machine with other lanes running (operator-stated ~5)";
    }

    private static List<String> col(Statement s, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet r = s.executeQuery(sql)) { while (r.next()) out.add(r.getString(1)); }
        return out;
    }

    private static final int SP2_BUCKETS = 32;
    private static final String SP2_FILTER = "kind = 'voice' AND dur >= 30";

    /** Java side of the D-3 bucket function: MD5 digest bytes 8-15 little-endian, unsigned remainder (= md5_number_lower % N). */
    private static int bucket(String key) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 15; i >= 8; i--) v = (v << 8) | (d[i] & 0xffL);
            return (int) Long.remainderUnsigned(v, SP2_BUCKETS);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** One hop for a frontier: layouts {@code win} / {@code day} = per-key UNION ALL, both directions; {@code flat} = one IN-list scan. */
    private static String sp2HopSql(String layout, List<String> keys) {
        if (layout.equals("flat")) {
            String in = String.join(",", keys.stream().map(k -> "'" + k.replace("'", "''") + "'").toList());
            return "SELECT src AS k, dst AS n, count(*) c FROM f_edges WHERE src IN (" + in + ") AND " + SP2_FILTER + " GROUP BY src, dst"
                    + " UNION ALL SELECT dst AS k, src AS n, count(*) c FROM f_edges WHERE dst IN (" + in + ") AND " + SP2_FILTER + " GROUP BY dst, src";
        }
        StringBuilder b = new StringBuilder();
        for (String k : keys) {
            String q = k.replace("'", "''");
            if (b.length() > 0) b.append(" UNION ALL ");
            b.append("(SELECT src AS k, dst AS n, count(*) c FROM ").append(layout).append("_out WHERE bucket = ").append(bucket(k)).append(" AND src = '").append(q)
                    .append("' AND ").append(SP2_FILTER).append(" GROUP BY src, dst) UNION ALL (SELECT dst AS k, src AS n, count(*) c FROM ").append(layout)
                    .append("_in WHERE bucket = ").append(bucket(k)).append(" AND dst = '").append(q).append("' AND ").append(SP2_FILTER).append(" GROUP BY dst, src)");
        }
        return b.toString();
    }

    private static Map<String, Long> sp2Hop(Statement s, String layout, List<String> keys) throws SQLException {
        Map<String, Long> out = new java.util.HashMap<>();
        try (ResultSet r = s.executeQuery(sp2HopSql(layout, keys))) { while (r.next()) out.merge(r.getString(2), r.getLong(3), Long::sum); }
        return out;
    }

    /**
     * The hop ladder, one server query per hop. {@code stats} = {keys looked up in total, 1 if a frontier was cut at the 20-key cap,
     * 1 if a hub entered a frontier}. Strongest first (count desc, then key asc: hubs {@code h..} sort before {@code n..}, i.e. the worst
     * tie-break for them); with {@code barrier} a hub never enters the frontier.
     */
    private static void sp2Ladder(Statement s, String layout, String seed, int hops, boolean barrier, int[] stats) throws SQLException {
        java.util.Set<String> seen = new java.util.HashSet<>(List.of(seed));
        List<String> frontier = List.of(seed);
        for (int h = 0; h < hops && !frontier.isEmpty(); h++) {
            stats[0] += frontier.size();
            Map<String, Long> nb = sp2Hop(s, layout, frontier);
            List<String> next = new ArrayList<>();
            nb.entrySet().stream().filter(e -> !seen.contains(e.getKey()) && !(barrier && e.getKey().startsWith("h")))
                    .sorted((a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().compareTo(b.getKey()) : Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> next.add(e.getKey()));
            if (next.size() > 20) { stats[1] = 1; next.subList(20, next.size()).clear(); }
            for (String k : next) if (k.startsWith("h")) stats[2] = 1;
            seen.addAll(next);
            frontier = next;
        }
    }

    /** Generates the flat Dataset, the WINDOW index and the 7 DAILY indexes (idempotent: delete the directory to regenerate). */
    private static void sp2Build(Path root, long edges) throws Exception {
        if (Files.exists(root.resolve("DONE"))) return;
        Files.createDirectories(root);
        for (String side : new String[]{"out", "in"}) {
            Files.createDirectories(root.resolve("win").resolve(side));
            for (int d = 0; d < 7; d++) Files.createDirectories(root.resolve("day" + d).resolve(side));
        }
        long nodes = edges / 5;
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("SET threads = 4");
            s.execute("SET memory_limit = '8GB'");
            s.execute("SET temp_directory = '" + sqlPath(root.resolve("tmp")) + "'");
            String h = "hash(i + %d) / 1.8446744073709552e19";
            s.execute("COPY (SELECT CASE WHEN " + String.format(h, 11) + " < 0.02 THEN 'h' || CAST(hash(i + 12) % 5 AS BIGINT)"
                    + " ELSE 'n' || CAST(floor(" + nodes + " * pow(" + String.format(h, 21) + ", 3)) AS BIGINT) END AS src,"
                    + " CASE WHEN " + String.format(h, 31) + " < 0.01 THEN 'h' || CAST(hash(i + 32) % 5 AS BIGINT)"
                    + " ELSE 'n' || CAST(floor(" + nodes + " * pow(" + String.format(h, 41) + ", 2)) AS BIGINT) END AS dst,"
                    + " CASE WHEN hash(i + 51) % 10 < 6 THEN 'voice' WHEN hash(i + 51) % 10 < 9 THEN 'sms' ELSE 'data' END AS kind,"
                    + " 3600 * pow(" + String.format(h, 61) + ", 2) AS dur,"
                    + " CAST(i % 7 AS INTEGER) AS day,"
                    + " TIMESTAMP '2026-01-01' + to_days(CAST(i % 7 AS INTEGER)) + to_seconds(CAST(hash(i + 71) % 86400 AS BIGINT)) AS ts"
                    + " FROM range(" + edges + ") t(i)) TO '" + sqlPath(root.resolve("flat.parquet")) + "' (FORMAT parquet)");
            String src = "read_parquet('" + sqlPath(root.resolve("flat.parquet")) + "')";
            String opt = " (FORMAT parquet, PARTITION_BY (bucket), ROW_GROUP_SIZE 100000, OVERWRITE_OR_IGNORE)";
            for (String side : new String[]{"out", "in"}) {
                String own = side.equals("out") ? "src" : "dst";
                String sel = "SELECT src, dst, kind, dur, ts, CAST(md5_number_lower(" + own + ") % " + SP2_BUCKETS + " AS INTEGER) AS bucket FROM " + src;
                s.execute("COPY (" + sel + " ORDER BY bucket, " + own + ", ts) TO '" + sqlPath(root.resolve("win").resolve(side)) + "'" + opt);
                for (int d = 0; d < 7; d++)
                    s.execute("COPY (" + sel + " WHERE day = " + d + " ORDER BY bucket, " + own + ", ts) TO '"
                            + sqlPath(root.resolve("day" + d).resolve(side)) + "'" + opt);
            }
        }
        Files.writeString(root.resolve("DONE"), "ok");
    }

    // ---------------------------------------------------------------- helpers

    private static List<Long> sizes() {
        List<Long> out = new ArrayList<>();
        for (String s : System.getProperty("inspecto.bench.sizes", "1000000,10000000").split(",")) out.add(Long.parseLong(s.trim()));
        return out;
    }

    private static double ms(Statement s, String sql) throws SQLException {
        long t0 = System.nanoTime();
        try (ResultSet r = s.executeQuery(sql)) { while (r.next()) { /* drain */ } }
        return (System.nanoTime() - t0) / 1e6;
    }

    private static String explain(Statement s, String sql) throws SQLException {
        StringBuilder b = new StringBuilder();
        try (ResultSet r = s.executeQuery("EXPLAIN ANALYZE " + sql)) {
            while (r.next()) b.append(r.getString(2)).append('\n');
        }
        return b.toString();
    }

    private static double pct(List<Double> xs, int p) {
        if (xs.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) Math.ceil(p / 100.0 * s.size()) - 1 < 0 ? 0 : (int) Math.ceil(p / 100.0 * s.size()) - 1));
    }

    private static void log(String line) {
        System.out.println("[bench] " + line);
        try (PrintStream out = new PrintStream(Files.newOutputStream(DIR.resolve("results.txt"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND))) {
            out.println(line);
        } catch (IOException ignore) {
            // stdout already has it
        }
    }
}
