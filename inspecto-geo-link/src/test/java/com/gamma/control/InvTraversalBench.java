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
 * {@code POST /inv/traversal/recursive-paths}) and the option-D spikes D-S1 / D-S2 / D-S3
 * ({@code docs/superpower/la-separation-feasibility-plan.md} §7.10). Results are recorded there.
 *
 * <p>Never runs in the default suite: it needs {@code -Dinspecto.bench.dir=<dir>} (generated Parquet goes there —
 * keep it under {@code .claude/worktrees/}, never commit it). Example:
 * {@code mvn -o test -pl inspecto-geo-link -Dtest=InvTraversalBench -Dsurefire.failIfNoSpecifiedTests=false
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

    // ---------------------------------------------------------------- D-S2 DuckPGQ offline load

    @Test
    void spikeDS2_duckpgqLoadsOffline() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("SET autoinstall_known_extensions = false");
            s.execute("SET autoload_known_extensions = false");
            try (ResultSet r = s.executeQuery("SELECT version()")) { r.next(); log("DuckDB " + r.getString(1)); }
            try {
                s.execute("LOAD duckpgq");
                log("D-S2 duckpgq LOADED offline");
            } catch (SQLException e) {
                log("D-S2 duckpgq NOT loadable offline: " + e.getMessage().lines().findFirst().orElse(""));
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
