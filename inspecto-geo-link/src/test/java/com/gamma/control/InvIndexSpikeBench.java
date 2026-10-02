package com.gamma.control;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * MEASUREMENT harness, not a test: D-3 step 1 spike ({@code docs/superpower/la-separation-d3-design.md} §5 step 1 and its
 * "Step 1 results" section). TEST-ONLY — it measures the proposed edge/node index layout; nothing here is main code.
 *
 * <p>Never runs in the default suite: it needs BOTH {@code -Dinspecto.bench=true} and {@code -Dinspecto.bench.dir=<dir>}
 * (the dir holds {@code edges_<n>.parquet} from {@link InvTraversalBench}; the index is built under it as
 * {@code d3idx_<n>}). Keep the dir under {@code .claude/worktrees/}, never commit it. Example:
 * {@code mvn -o test -Pedition-enterprise -pl inspecto-geo-link -am -Dtest=InvIndexSpikeBench#d3Build
 * -Dsurefire.failIfNoSpecifiedTests=false -Dinspecto.bench=true -Dinspecto.bench.dir=... -Dinspecto.bench.sizes=1000000}.
 * Results append to {@code <dir>/results_d3s1.txt}.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench", matches = "true")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class InvIndexSpikeBench {

    private static final Path DIR = Path.of(System.getProperty("inspecto.bench.dir", "."));
    private static final int REPS = 21;      // first is dropped -> 20 samples

    // ---------------------------------------------------------------- layout / build (design §2.2, §3.2)

    /** Design §2.2: clamp(pow2(ceil(edges / 4e6)), 16, 1024). */
    static int bucketsFor(long edges) {
        long raw = Math.max(1, (edges + 3_999_999) / 4_000_000);
        long p = Long.highestOneBit(raw) == raw ? raw : Long.highestOneBit(raw) << 1;
        return (int) Math.max(16, Math.min(1024, p));
    }

    private static Path flat(long n) {
        Path f = DIR.resolve("edges_" + n + ".parquet");
        if (!Files.exists(f)) throw new IllegalStateException("missing " + f + " - run InvTraversalBench first (shared bench dir)");
        return f;
    }

    private static Path idx(long n, String tag) { return DIR.resolve("d3idx_" + n + tag); }

    private static Connection open() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = c.createStatement()) {
            Path tmp = DIR.resolve("duckdb_tmp");
            Files.createDirectories(tmp);
            s.execute("SET temp_directory='" + sqlPath(tmp) + "'");
        } catch (IOException e) { throw new SQLException(e); }
        return c;
    }

    /** Build out + in + nodes; returns the key=value manifest lines (cached on disk). */
    private static List<String> build(long n, String tag, String bucketFn) throws Exception {
        Path d = idx(n, tag), man = d.resolve("manifest.txt");
        if (Files.exists(man)) return Files.readAllLines(man);
        int N = bucketsFor(n);
        Files.createDirectories(d);
        String rel = "read_parquet('" + sqlPath(flat(n)) + "')";
        List<String> m = new ArrayList<>();
        m.add("edges=" + n); m.add("buckets=" + N); m.add("bucketFn=" + bucketFn);
        try (Connection c = open(); Statement s = c.createStatement(); Sampler smp = new Sampler(c)) {
            m.add("duckdb=" + one(s, "SELECT version()"));
            m.add("memory_limit=" + one(s, "SELECT current_setting('memory_limit')") + " threads=" + one(s, "SELECT current_setting('threads')"));
            long t0 = System.nanoTime();
            for (String[] dir : new String[][]{{"out", "src"}, {"in", "dst"}}) {
                long t = System.nanoTime();
                String k = dir[1];
                s.execute("COPY (SELECT CAST(" + bucketFn.replace("$k", k) + " AS INTEGER) AS bucket, src, dst, ts, amount AS w FROM " + rel
                        + " WHERE src IS NOT NULL AND dst IS NOT NULL ORDER BY bucket, " + k + ", ts) TO '" + sqlPath(d.resolve(dir[0]))
                        + "' (FORMAT parquet, PARTITION_BY (bucket), ROW_GROUP_SIZE 100000, COMPRESSION zstd)");
                m.add(String.format("build_%s_s=%.1f", dir[0], (System.nanoTime() - t) / 1e9));
            }
            long t = System.nanoTime();
            s.execute("COPY (SELECT CAST(" + bucketFn.replace("$k", "id") + " AS INTEGER) AS bucket, id, coalesce(o.oe,0) out_edges, coalesce(i.ie,0) in_edges,"
                    + " coalesce(o.ol,0) out_links, coalesce(i.il,0) in_links, least(o.f, i.f) first_ts, greatest(o.l, i.l) last_ts FROM"
                    + " (SELECT src id, count(*) oe, count(DISTINCT dst) ol, min(ts) f, max(ts) l FROM " + rel + " WHERE src IS NOT NULL AND dst IS NOT NULL GROUP BY src) o"
                    + " FULL JOIN (SELECT dst id, count(*) ie, count(DISTINCT src) il, min(ts) f, max(ts) l FROM " + rel + " WHERE src IS NOT NULL AND dst IS NOT NULL GROUP BY dst) i USING (id)"
                    + " ORDER BY bucket, id) TO '" + sqlPath(d.resolve("nodes")) + "' (FORMAT parquet, PARTITION_BY (bucket), ROW_GROUP_SIZE 100000, COMPRESSION zstd)");
            m.add(String.format("build_nodes_s=%.1f", (System.nanoTime() - t) / 1e9));
            m.add(String.format("build_total_s=%.1f", (System.nanoTime() - t0) / 1e9));
            m.add("peak_duckdb_memory_mb=" + smp.peakDuck / (1 << 20));
            m.add("peak_process_workingset_mb=" + smp.peakRss / 1024);
            // verification (design §3.2 step 4)
            long fr = Long.parseLong(one(s, "SELECT count(*) FROM " + rel + " WHERE src IS NOT NULL AND dst IS NOT NULL"));
            for (String tb : new String[]{"out", "in", "nodes"}) {
                String g = "'" + sqlPath(d.resolve(tb)) + "/**/*.parquet'";
                m.add(tb + "_rows=" + one(s, "SELECT sum(num_rows) FROM parquet_file_metadata(" + g + ")"));
                m.add(tb + "_files=" + one(s, "SELECT count(*) FROM parquet_file_metadata(" + g + ")"));
                m.add(tb + "_bytes=" + dirBytes(d.resolve(tb)));
                m.add(tb + "_rowgroups=" + one(s, "SELECT count(*) FROM parquet_metadata(" + g + ") WHERE path_in_schema='src' OR path_in_schema='id'"));
                if (!tb.equals("nodes")) {
                    try (ResultSet r = s.executeQuery("SELECT min(c), median(c), max(c) FROM (SELECT sum(num_rows) c FROM parquet_file_metadata(" + g + ") GROUP BY regexp_extract(file_name, 'bucket=([0-9]+)', 1))")) {
                        r.next();
                        m.add(tb + "_bucket_rows_min_median_max=" + r.getLong(1) + "," + (long) r.getDouble(2) + "," + r.getLong(3));
                    }
                }
            }
            m.add("flat_rows=" + fr + " flat_bytes=" + Files.size(flat(n)));
            m.add("verify_out_eq_in_eq_flat=" + (m.contains("out_rows=" + fr) && m.contains("in_rows=" + fr)));
            m.add("created_by=" + one(s, "SELECT any_value(created_by) FROM parquet_file_metadata('" + sqlPath(d.resolve("out")) + "/**/*.parquet')"));
            // probes from the node table: p50 / p99 / max out-degree node
            try (ResultSet r = s.executeQuery("WITH q AS (SELECT quantile_disc(out_edges,0.5) p50, quantile_disc(out_edges,0.99) p99, max(out_edges) mx FROM read_parquet('"
                    + sqlPath(d.resolve("nodes")) + "/**/*.parquet') WHERE out_edges>0), nd AS (SELECT id, out_edges FROM read_parquet('" + sqlPath(d.resolve("nodes")) + "/**/*.parquet'))"
                    + " SELECT (SELECT min(id) FROM nd, q WHERE out_edges=q.p50), (SELECT min(id) FROM nd, q WHERE out_edges=q.p99), (SELECT min(id) FROM nd, q WHERE out_edges=q.mx), q.p50, q.p99, q.mx FROM q")) {
                r.next();
                m.add("probe_p50=" + r.getString(1) + " probe_p99=" + r.getString(2) + " probe_max=" + r.getString(3) + " deg=" + r.getLong(4) + "/" + r.getLong(5) + "/" + r.getLong(6));
            }
        }
        Files.write(man, m);
        return m;
    }

    @Test
    void d3Build() throws Exception {
        for (long n : sizes()) {
            List<String> m = build(n, "", "hash($k) % " + bucketsFor(n));
            log("D3S1 build n=" + n + " (cached manifest below)\n  " + String.join("\n  ", m));
        }
    }

    // ---------------------------------------------------------------- sealed connection (SqlSandbox.sealAllowing, replicated)

    private static Connection sealed(Path index) throws SQLException {
        Connection c = open();
        try (Statement s = c.createStatement()) {
            s.execute("SET threads=4");                              // traversalPolicy() default
            s.execute("SET autoinstall_known_extensions=false");
            s.execute("SET autoload_known_extensions=false");
            for (String t : new String[]{"out", "in", "nodes"})
                s.execute("CREATE VIEW " + (t.equals("nodes") ? t : "e_" + t) + " AS SELECT * FROM read_parquet('" + sqlPath(index.resolve(t)) + "/**/*.parquet', hive_partitioning = true)");
            s.execute("SET allowed_directories=['" + sqlPath(index) + "/']");
            s.execute("SET enable_external_access=false");
            s.execute("SET lock_configuration=true");
        }
        return c;
    }

    private static String probe(List<String> m, String key) {
        for (String l : m) { Matcher x = Pattern.compile(key + "=(\\S+)").matcher(l); if (x.find()) return x.group(1); }
        throw new IllegalStateException(key);
    }

    // ---------------------------------------------------------------- Q1 pruning + (b) one hop + (c) frontier

    @Test
    void d3Lookups() throws Exception {
        List<Long> sz = sizes();
        for (long n : sz) {
            List<String> m = build(n, "", "hash($k) % " + bucketsFor(n));
            int N = bucketsFor(n);
            String node = probe(m, "probe_p50");
            Path index = idx(n, "");
            boolean last = n == sz.get(sz.size() - 1);
            try (Connection c = sealed(index)) {
                int b;
                try (PreparedStatement p = c.prepareStatement("SELECT CAST(hash(?) % " + N + " AS INTEGER)")) { p.setString(1, node); ResultSet r = p.executeQuery(); r.next(); b = r.getInt(1); }
                log("D3S1 lookups n=" + n + " N=" + N + " node=" + node + " bucket=" + b);
                String sel = "SELECT dst, ts FROM e_out WHERE src = ";
                Object[][] v = {
                        {"literal src + literal bucket", sel + "'" + node + "' AND bucket = " + b, new Object[0]},
                        {"bound src + bound bucket (setInt)", sel + "? AND bucket = ?", new Object[]{node, b}},
                        {"bound src + bound bucket (setLong)", sel + "? AND bucket = ?", new Object[]{node, (long) b}},
                        {"bound src + bucket = hash(?) in SQL", sel + "? AND bucket = CAST(hash(CAST(? AS VARCHAR)) % " + N + " AS INTEGER)", new Object[]{node, node}},
                        {"bound src, no bucket predicate", sel + "?", new Object[]{node}},
                        {"IN-list literal src (1) + literal bucket", sel.replace("= ", "IN (") + "'" + node + "') AND bucket IN (" + b + ")", new Object[0]},
                };
                for (Object[] q : v) {
                    List<Double> t = timed(c, (String) q[1], (Object[]) q[2], REPS);
                    log(String.format("D3S1 one-hop n=%d [sealed] %s: p50 %.2f ms p95 %.2f ms (n=%d)", n, q[0], pct(t, 50), pct(t, 95), t.size()));
                    String plan = explain(c, (String) q[1], (Object[]) q[2]);
                    log("D3S1 EXPLAIN n=" + n + " " + q[0] + ": " + filesRead(plan));
                    if (last) Files.writeString(DIR.resolve("explain_d3_" + n + "_" + q[0].toString().replaceAll("[^a-zA-Z]+", "_") + ".txt"), plan);
                }
            }
            // unsealed baseline for the seal's own cost
            try (Connection c = open(); Statement s = c.createStatement()) {
                s.execute("CREATE VIEW e_out AS SELECT * FROM read_parquet('" + sqlPath(index.resolve("out")) + "/**/*.parquet', hive_partitioning = true)");
                int b = Integer.parseInt(one(s, "SELECT CAST(hash('" + node + "') % " + N + " AS INTEGER)"));
                List<Double> t = timed(c, "SELECT dst, ts FROM e_out WHERE src = ? AND bucket = ?", new Object[]{node, b}, REPS);
                log(String.format("D3S1 one-hop n=%d [UNSEALED, default threads] bound src+bucket: p50 %.2f ms p95 %.2f ms", n, pct(t, 50), pct(t, 95)));
            }
            // (c) level statement at frontier 1 / 100 / 10000
            try (Connection c = sealed(index)) {
                for (int k : new int[]{1, 100, 10_000}) {
                    List<String> fr = new ArrayList<>();
                    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT id FROM nodes WHERE out_edges > 0 ORDER BY hash(id) LIMIT " + k)) {
                        while (r.next()) fr.add(r.getString(1));
                    }
                    String binds = String.join(",", Collections.nCopies(k, "?"));
                    String lits = fr.stream().map(x -> "'" + x + "'").collect(java.util.stream.Collectors.joining(","));
                    String bucketLits = bucketSet(c, fr, N);
                    long[] rows = new long[1];
                    Object[][] lv = {
                            {"bound ids + bucket IN literals", "SELECT src, dst, ts FROM e_out WHERE bucket IN (" + bucketLits + ") AND src IN (" + binds + ")", fr.toArray()},
                            {"literal ids + bucket IN literals", "SELECT src, dst, ts FROM e_out WHERE bucket IN (" + bucketLits + ") AND src IN (" + lits + ")", new Object[0]},
                            {"bound ids, no bucket predicate", "SELECT src, dst, ts FROM e_out WHERE src IN (" + binds + ")", fr.toArray()},
                    };
                    for (Object[] q : lv) {
                        List<Double> t = timed(c, (String) q[1], (Object[]) q[2], k >= 10_000 ? 9 : REPS, rows);
                        log(String.format("D3S1 level n=%d frontier=%d (%s buckets) [sealed] %s: p50 %.2f ms p95 %.2f ms rows=%d (n=%d)",
                                n, k, bucketLits.split(",").length, q[0], pct(t, 50), pct(t, 95), rows[0], t.size()));
                        if (last && k == 100) log("D3S1 EXPLAIN level n=" + n + " frontier=" + k + " " + q[0] + ": " + filesRead(explain(c, (String) q[1], (Object[]) q[2])));
                    }
                    List<Double> t = new ArrayList<>();
                    for (int i = 0; i < 9; i++) { long t0 = System.nanoTime(); bucketSet(c, fr, N); if (i > 0) t.add((System.nanoTime() - t0) / 1e6); }
                    log(String.format("D3S1 bucket-set round trip n=%d frontier=%d: p50 %.2f ms p95 %.2f ms", n, k, pct(t, 50), pct(t, 95)));
                }
            }
        }
    }

    /** The multi-key level statement: which shape keeps file AND row-group pruning as the frontier grows? */
    @Test
    void d3Frontier() throws Exception {
        List<Long> sz = sizes();
        long n = sz.get(sz.size() - 1);
        int N = bucketsFor(n);
        build(n, "", "hash($k) % " + N);
        try (Connection c = sealed(idx(n, ""))) {
            for (int k : new int[]{1, 2, 5, 10, 20, 50, 100, 1000}) {
                List<String> ids = new ArrayList<>(); List<Integer> bs = new ArrayList<>();
                try (PreparedStatement p = c.prepareStatement("SELECT id, CAST(hash(id) % " + N + " AS INTEGER) FROM nodes WHERE out_edges > 0 ORDER BY hash(id) LIMIT " + k);
                     ResultSet r = p.executeQuery()) { while (r.next()) { ids.add(r.getString(1)); bs.add(r.getInt(2)); } }
                String inBinds = String.join(",", Collections.nCopies(k, "?"));
                String bset = new java.util.TreeSet<>(bs).stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
                List<Object> pairs = new ArrayList<>();
                for (int i = 0; i < k; i++) { pairs.add(ids.get(i)); pairs.add(bs.get(i)); }
                StringBuilder or = new StringBuilder(), un = new StringBuilder();
                for (int i = 0; i < k; i++) {
                    or.append(i > 0 ? " OR " : "").append("(src = ? AND bucket = ?)");
                    un.append(i > 0 ? " UNION ALL " : "").append("SELECT src, dst, ts FROM e_out WHERE src = ? AND bucket = ?");
                }
                Object[][] v = {
                        {"A IN-list + bucket IN", "SELECT src, dst, ts FROM e_out WHERE bucket IN (" + bset + ") AND src IN (" + inBinds + ")", ids.toArray()},
                        {"B OR of (src=? AND bucket=?)", "SELECT src, dst, ts FROM e_out WHERE " + or, pairs.toArray()},
                        {"C UNION ALL of per-key equalities", un.toString(), pairs.toArray()},
                        {"D join VALUES(src,bucket)", "SELECT e.src, e.dst, e.ts FROM e_out e JOIN (VALUES " + String.join(",", Collections.nCopies(k, "(?,?)")) + ") f(s,b) ON e.src = f.s AND e.bucket = f.b", pairs.toArray()},
                        {"E join unnest(?) list", "SELECT e.src, e.dst, e.ts FROM e_out e JOIN (SELECT unnest(?) s) f ON e.src = f.s WHERE e.bucket IN (" + bset + ")", null},
                };
                for (Object[] q : v) {
                    if (n > 10_000_000 && k >= 100 && (q[0].toString().startsWith("B") || q[0].toString().startsWith("C"))) continue;   // 17-25 s per call at 10^7 already
                    long[] rows = new long[1];
                    List<Double> t;
                    Object[] binds = (Object[]) q[2];
                    if (binds == null) {            // E: bind a list
                        t = new ArrayList<>();
                        for (int i = 0; i < 9; i++) {
                            long t0 = System.nanoTime(); long cnt = 0;
                            try (PreparedStatement p = c.prepareStatement((String) q[1])) {
                                p.setObject(1, c.createArrayOf("VARCHAR", ids.toArray()));
                                try (ResultSet r = p.executeQuery()) { while (r.next()) cnt++; }
                            } catch (SQLException e) { log("D3S1 frontier n=" + n + " k=" + k + " " + q[0] + ": FAIL " + e.getMessage().lines().findFirst().orElse("")); t = null; break; }
                            if (i > 0) t.add((System.nanoTime() - t0) / 1e6);
                            rows[0] = cnt;
                        }
                        if (t == null) continue;
                        log(String.format("D3S1 frontier n=%d k=%d %s: p50 %.1f ms p95 %.1f ms rows=%d (n=%d)", n, k, q[0], pct(t, 50), pct(t, 95), rows[0], t.size()));
                        continue;
                    }
                    try {
                        t = timed(c, (String) q[1], binds, k >= 1000 ? 5 : 9, rows);
                        log(String.format("D3S1 frontier n=%d k=%d %s: p50 %.1f ms p95 %.1f ms rows=%d (n=%d) | %s", n, k, q[0], pct(t, 50), pct(t, 95), rows[0], t.size(),
                                filesRead(explain(c, (String) q[1], binds))));
                    } catch (SQLException e) { log("D3S1 frontier n=" + n + " k=" + k + " " + q[0] + ": FAIL " + e.getMessage().lines().findFirst().orElse("")); }
                }
            }
        }
    }

    /** (e) Java-driven BFS, one statement per level, per-level LIMIT = yield fence. */
    @Test
    void d3Walk() throws Exception {
        for (long n : sizes()) {
            List<String> m = build(n, "", "hash($k) % " + bucketsFor(n));
            int N = bucketsFor(n);
            try (Connection c = sealed(idx(n, ""))) {
                for (String which : new String[]{"probe_p99", "probe_p50"})
                    for (boolean useBucket : new boolean[]{true, false}) {
                        String start = probe(m, which);
                        List<Double> t = new ArrayList<>();
                        String shape = "";
                        for (int i = 0; i < 8; i++) {
                            long t0 = System.nanoTime();
                            shape = walk(c, start, 5, 10_000, useBucket, N);
                            if (i > 0) t.add((System.nanoTime() - t0) / 1e6);
                        }
                        log(String.format("D3S1 walk n=%d 5 levels %s(%s) yield=10000 bucketPredicate=%b [sealed, 4 threads]: p50 %.1f ms p95 %.1f ms (n=%d) %s",
                                n, which, start, useBucket, pct(t, 50), pct(t, 95), t.size(), shape));
                    }
            }
        }
    }

    private static String walk(Connection c, String start, int levels, int yield, boolean useBucket, int N) throws SQLException {
        Set<String> seen = new HashSet<>(Set.of(start));
        List<String> frontier = List.of(start);
        long edges = 0; StringBuilder lv = new StringBuilder("frontiers=");
        for (int l = 0; l < levels && !frontier.isEmpty(); l++) {
            lv.append(frontier.size()).append(l < levels - 1 ? "/" : "");
            String binds = String.join(",", Collections.nCopies(frontier.size(), "?"));
            String sql = "SELECT src, dst FROM e_out WHERE " + (useBucket ? "bucket IN (" + bucketSet(c, frontier, N) + ") AND " : "")
                    + "src IN (" + binds + ") LIMIT " + yield;
            List<String> next = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(sql)) {
                for (int i = 0; i < frontier.size(); i++) p.setString(i + 1, frontier.get(i));
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) { edges++; String d = r.getString(2); if (seen.add(d)) next.add(d); }
                }
            }
            frontier = next;
        }
        return "edgesRead=" + edges + " nodesSeen=" + seen.size() + " " + lv;
    }

    private static String bucketSet(Connection c, List<String> ids, int N) throws SQLException {
        String vals = String.join(",", Collections.nCopies(ids.size(), "(?)"));
        List<String> b = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT DISTINCT CAST(hash(v) % " + N + " AS INTEGER) FROM (VALUES " + vals + ") t(v)")) {
            for (int i = 0; i < ids.size(); i++) p.setString(i + 1, ids.get(i));
            try (ResultSet r = p.executeQuery()) { while (r.next()) b.add(String.valueOf(r.getInt(1))); }
        }
        return String.join(",", b);
    }

    // ---------------------------------------------------------------- Q2 hash stability + Java reproducibility

    @Test
    void d3Hash() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 1000; i++) ids.add("n" + (i * 7919L));
        ids.add("n0"); ids.add(""); ids.add("Zürich-ß-日本"); ids.add("7:550123456789");
        String fp1, fp2;
        try (Connection a = DriverManager.getConnection("jdbc:duckdb:"); Connection b = DriverManager.getConnection("jdbc:duckdb:")) {
            fp1 = hashFp(a, ids); fp2 = hashFp(b, ids);
        }
        log("D3S1 hash two separate in-memory DuckDB instances in one JVM equal=" + fp1.equals(fp2) + " fp=" + fp1
                + " | JVM pid=" + ProcessHandle.current().pid() + " (compare fp across runs/JVMs)");
        Files.writeString(DIR.resolve("hash_fp.txt"), "pid=" + ProcessHandle.current().pid() + " fp=" + fp1 + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        // Persisted data: the D-S1 b64 copies were written by an EARLIER JVM (2026-09-30); recompute and compare every row.
        for (long n : sizes()) {
            Path d = DIR.resolve("edges_" + n + "_b64");
            if (!Files.exists(d)) continue;
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
                String g = "'" + sqlPath(d) + "/**/*.parquet'";
                log("D3S1 hash persisted n=" + n + ": rows where stored bucket != hash(src)%64 recomputed now = "
                        + one(s, "SELECT count(*) FROM read_parquet(" + g + ", hive_partitioning=true) WHERE bucket != CAST(hash(src) % 64 AS INTEGER)")
                        + " of " + one(s, "SELECT count(*) FROM read_parquet(" + g + ", hive_partitioning=true)")
                        + "; file writer created_by=" + one(s, "SELECT any_value(created_by) FROM parquet_file_metadata(" + g + ")")
                        + "; runtime=" + one(s, "SELECT version()"));
            }
        }
        // Java reproducibility: md5_number_lower (documented, stable, in Java's JDK) as the alternative.
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            int[] ok = new int[4]; int N = 64;
            String[] names = {"bytes[0..8) BE", "bytes[0..8) LE", "bytes[8..16) BE", "bytes[8..16) LE"};
            for (String id : ids) {
                long sqlV; String sqlType;
                try (PreparedStatement p = c.prepareStatement("SELECT CAST(md5_number_lower(?) % " + N + " AS INTEGER), typeof(md5_number_lower(?))")) {
                    p.setString(1, id); p.setString(2, id);
                    ResultSet r = p.executeQuery(); r.next(); sqlV = r.getInt(1); sqlType = r.getString(2);
                    if (id.equals("n0")) log("D3S1 md5_number_lower type=" + sqlType);
                }
                byte[] h = md.digest(id.getBytes(StandardCharsets.UTF_8));
                long[] cand = {be(h, 0), le(h, 0), be(h, 8), le(h, 8)};
                for (int i = 0; i < 4; i++) if (Long.remainderUnsigned(cand[i], N) == sqlV) ok[i]++;
            }
            for (int i = 0; i < 4; i++) log("D3S1 md5 Java==SQL (bucket of N=64) via " + names[i] + ": " + ok[i] + "/" + ids.size());
            // Can Java reproduce hash()? No documented algorithm; probe the obvious JDK candidates.
            int hc = 0;
            for (String id : ids) {
                long sqlH; try (PreparedStatement p = c.prepareStatement("SELECT hash(?) % 64")) { p.setString(1, id); ResultSet r = p.executeQuery(); r.next(); sqlH = r.getLong(1); }
                if (Math.floorMod((long) id.hashCode(), 64) == sqlH) hc++;
            }
            log("D3S1 hash() vs Java String.hashCode()%64 agreement (chance = 1/64): " + hc + "/" + ids.size() + " - no JDK equivalent found; hash() algorithm is not documented, not reverse-engineered here");
        }
    }

    private static long be(byte[] h, int o) { return ByteBuffer.wrap(h, o, 8).getLong(); }
    private static long le(byte[] h, int o) { return ByteBuffer.wrap(h, o, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(); }

    private static String hashFp(Connection c, List<String> ids) throws Exception {
        StringBuilder b = new StringBuilder();
        try (PreparedStatement p = c.prepareStatement("SELECT hash(?)")) {
            for (String id : ids) { p.setString(1, id); ResultSet r = p.executeQuery(); r.next(); b.append(r.getObject(1)).append(','); }
        }
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** The md5_number_lower alternative bucket function, built and queried like the hash() index (n capped at 10^7). */
    @Test
    void d3Md5Variant() throws Exception {
        for (long n : sizes()) {
            if (n > 10_000_000) { log("D3S1 md5 variant skipped at n=" + n + " (cap 10^7)"); continue; }
            int N = bucketsFor(n);
            List<String> m = build(n, "_md5", "md5_number_lower($k) % " + N);
            log("D3S1 md5-variant build n=" + n + ": total " + probe(m, "build_total_s") + " s (out " + probe(m, "build_out_s") + " in " + probe(m, "build_in_s")
                    + " nodes " + probe(m, "build_nodes_s") + "); bucket rows min/median/max " + probe(m, "out_bucket_rows_min_median_max"));
            String node = probe(m, "probe_p50");
            try (Connection c = sealed(idx(n, "_md5"))) {
                int b;
                try (PreparedStatement p = c.prepareStatement("SELECT CAST(md5_number_lower(?) % " + N + " AS INTEGER)")) { p.setString(1, node); ResultSet r = p.executeQuery(); r.next(); b = r.getInt(1); }
                List<Double> t = timed(c, "SELECT dst, ts FROM e_out WHERE src = ? AND bucket = ?", new Object[]{node, b}, REPS);
                log(String.format("D3S1 md5-variant one-hop n=%d bound src+bound bucket [sealed]: p50 %.2f ms p95 %.2f ms; files read: %s", n, pct(t, 50), pct(t, 95),
                        filesRead(explain(c, "SELECT dst, ts FROM e_out WHERE src = ? AND bucket = ?", new Object[]{node, b}))));
            }
        }
    }

    // ---------------------------------------------------------------- Q4 capabilities of the pinned jar

    @Test
    void d3Caps() throws Exception {
        Path w = DIR.resolve("d3caps");
        if (Files.exists(w)) try (Stream<Path> x = Files.walk(w)) { for (Path p : (Iterable<Path>) x.sorted(Comparator.reverseOrder())::iterator) Files.delete(p); }
        Files.createDirectories(w);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            log("D3S1 caps runtime version=" + one(s, "SELECT version()"));
            log("D3S1 caps json extension: " + one(s, "SELECT string_agg(extension_name || ' loaded=' || loaded || ' installed=' || installed, '; ') FROM duckdb_extensions() WHERE extension_name IN ('json','parquet','core_functions')"));
            for (String q : new String[]{"SELECT json_extract('{\"a\":[1,2]}', '$.a[1]')", "SELECT json_extract_string('{\"a\":\"x\"}', '$.a')",
                    "SELECT json_group_array(i) FROM range(3) t(i)", "SELECT CAST('[\"a\",\"b\"]' AS JSON)", "SELECT unnest(from_json('[\"a\",\"b\"]', '[\"VARCHAR\"]'))",
                    "SELECT json_array('a','b')", "SELECT json_valid('{}')"})
                log("D3S1 caps json: " + q + " -> " + tryOne(s, q));
            // sealed (autoload off, external access off) - is json built in?
        }
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("SET autoinstall_known_extensions=false"); s.execute("SET autoload_known_extensions=false");
            s.execute("SET enable_external_access=false"); s.execute("SET lock_configuration=true");
            log("D3S1 caps json in a SEALED connection (autoload off): " + tryOne(s, "SELECT json_extract('{\"a\":1}', '$.a')"));
        }
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE e AS SELECT CAST(hash(i) % 4 AS INTEGER) bucket, 'k' || i src, i, TIMESTAMP '2026-01-01' + to_seconds(i) ts FROM range(250000) t(i)");
            String[][] opts = {
                    {"PARTITION_BY + ROW_GROUP_SIZE 100000 + COMPRESSION zstd", "(FORMAT parquet, PARTITION_BY (bucket), ROW_GROUP_SIZE 100000, COMPRESSION zstd)", "dir"},
                    {"COMPRESSION zstd + COMPRESSION_LEVEL 3", "(FORMAT parquet, COMPRESSION zstd, COMPRESSION_LEVEL 3)", "file"},
                    {"COMPRESSION snappy", "(FORMAT parquet, COMPRESSION snappy)", "file"},
                    {"ROW_GROUP_SIZE_BYTES 8MB", "(FORMAT parquet, ROW_GROUP_SIZE_BYTES '8MB')", "file"},
                    {"FILE_SIZE_BYTES 1MB", "(FORMAT parquet, FILE_SIZE_BYTES '1MB')", "dir"},
                    {"PER_THREAD_OUTPUT", "(FORMAT parquet, PER_THREAD_OUTPUT)", "dir"},
                    {"OVERWRITE_OR_IGNORE with PARTITION_BY", "(FORMAT parquet, PARTITION_BY (bucket), OVERWRITE_OR_IGNORE)", "dir"},
                    {"WRITE_BLOOM_FILTER / bloom_filter_false_positive_ratio", "(FORMAT parquet, bloom_filter_false_positive_ratio 0.01)", "file"},
                    {"PARQUET_VERSION V2", "(FORMAT parquet, PARQUET_VERSION V2)", "file"},
            };
            int i = 0;
            for (String[] o : opts) {
                Path target = w.resolve("t" + (i++) + ("dir".equals(o[2]) ? "" : ".parquet"));
                String res = tryExec(c, "COPY (SELECT * FROM e ORDER BY bucket, src, ts) TO '" + sqlPath(target) + "' " + o[1]);
                String meta = "";
                if (res.startsWith("OK")) {
                    String g = "dir".equals(o[2]) ? sqlPath(target) + "/**/*.parquet" : sqlPath(target);
                    meta = " | files=" + one(s, "SELECT count(*) FROM parquet_file_metadata('" + g + "')") + " rowGroups=" + one(s, "SELECT count(*) FROM parquet_metadata('" + g + "') WHERE path_in_schema='i'")
                            + " codec=" + one(s, "SELECT string_agg(DISTINCT compression, ',') FROM parquet_metadata('" + g + "')");
                }
                log("D3S1 caps parquet write [" + o[0] + "]: " + res + meta);
            }
            // manual per-bucket files
            for (int b = 0; b < 4; b++) {
                Path f = w.resolve("manual_b" + b + ".parquet");
                s.execute("COPY (SELECT * FROM e WHERE bucket = " + b + " ORDER BY src, ts) TO '" + sqlPath(f) + "' (FORMAT parquet, ROW_GROUP_SIZE 100000, COMPRESSION zstd)");
            }
            log("D3S1 caps manual per-bucket files (bucket=0..3 each its own COPY): OK; read back rows=" + one(s, "SELECT count(*) FROM read_parquet('" + sqlPath(w) + "/manual_b*.parquet')"));
            log("D3S1 caps bloom filter metadata present in zstd file: " + tryOne(s, "SELECT count(*) FROM parquet_bloom_probe('" + sqlPath(w.resolve("t2.parquet")) + "', 'src', 'k5')"));
            log("D3S1 caps parquet_metadata bloom columns: " + tryOne(s, "SELECT string_agg(DISTINCT CAST(bloom_filter_offset IS NOT NULL AS VARCHAR), ',') FROM parquet_metadata('" + sqlPath(w.resolve("t2.parquet")) + "')"));
        }
    }

    // ---------------------------------------------------------------- Q5 fingerprint cost

    @Test
    void d3Fingerprint() throws Exception {
        String relationSql = "SELECT * FROM read_parquet('C:/data/ds/**/*.parquet', hive_partitioning = true) WHERE x IS NOT NULL " + "pad".repeat(200);
        List<Double> t = new ArrayList<>();
        for (int i = 0; i < 31; i++) { long t0 = System.nanoTime(); sha(relationSql); if (i > 0) t.add((System.nanoTime() - t0) / 1e6); }
        log(String.format("D3S1 fingerprint SHA-256 of a %d-char relation SQL: p50 %.4f ms p95 %.4f ms", relationSql.length(), pct(t, 50), pct(t, 95)));
        Path synth = DIR.resolve("d3fp");
        for (int files : new int[]{1, 64, 1_000, 10_000}) {
            Path d = synth.resolve("f" + files);
            if (!Files.exists(d)) {
                Files.createDirectories(d);
                for (int i = 0; i < files; i++) Files.writeString(d.resolve("part-" + i + ".parquet"), "x".repeat(10 + i % 7));
            }
            List<Double> a = new ArrayList<>(), b = new ArrayList<>();
            String fp = "";
            for (int i = 0; i < 21; i++) {
                long t0 = System.nanoTime();
                fp = dirFingerprint(d);
                if (i > 0) a.add((System.nanoTime() - t0) / 1e6);
                if (files <= 10_000) {
                    try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
                        long t1 = System.nanoTime(); one(s, "SELECT count(*) FROM glob('" + sqlPath(d) + "/*.parquet')");
                        if (i > 0) b.add((System.nanoTime() - t1) / 1e6);
                    }
                }
            }
            log(String.format("D3S1 fingerprint %d files: Java list(name,size,mtime)+SHA-256 p50 %.2f ms p95 %.2f ms | DuckDB glob() count (incl. fresh instance open) p50 %.2f ms p95 %.2f ms | fp=%s",
                    files, pct(a, 50), pct(a, 95), pct(b, 50), pct(b, 95), fp.substring(0, 12)));
        }
        for (long n : sizes()) {
            Path d = DIR.resolve("edges_" + n + "_b64");
            if (!Files.exists(d)) continue;
            List<Double> a = new ArrayList<>();
            for (int i = 0; i < 21; i++) { long t0 = System.nanoTime(); dirFingerprint(d); if (i > 0) a.add((System.nanoTime() - t0) / 1e6); }
            log(String.format("D3S1 fingerprint real bench dir edges_%d_b64 (64 hive subdirs, recursive walk): p50 %.2f ms p95 %.2f ms", n, pct(a, 50), pct(a, 95)));
        }
    }

    private static String sha(String s) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String dirFingerprint(Path d) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> w = Files.walk(d)) {
            for (Path p : (Iterable<Path>) w.filter(Files::isRegularFile).sorted()::iterator) {
                java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                md.update((d.relativize(p) + "|" + a.size() + "|" + a.lastModifiedTime().toMillis() + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    // ---------------------------------------------------------------- helpers

    /** Prepare + bind + drain per iteration (as the route does), drop the first sample. */
    private static List<Double> timed(Connection c, String sql, Object[] binds, int reps) throws SQLException { return timed(c, sql, binds, reps, new long[1]); }

    private static List<Double> timed(Connection c, String sql, Object[] binds, int reps, long[] rows) throws SQLException {
        List<Double> t = new ArrayList<>();
        for (int i = 0; i < reps; i++) {
            long t0 = System.nanoTime();
            long cnt = 0;
            try (PreparedStatement p = c.prepareStatement(sql)) {
                bind(p, binds);
                try (ResultSet r = p.executeQuery()) { while (r.next()) cnt++; }
            }
            if (i > 0) t.add((System.nanoTime() - t0) / 1e6);
            rows[0] = cnt;
        }
        return t;
    }

    private static void bind(PreparedStatement p, Object[] binds) throws SQLException {
        for (int i = 0; i < binds.length; i++) {
            Object o = binds[i];
            if (o instanceof Integer x) p.setInt(i + 1, x);
            else if (o instanceof Long x) p.setLong(i + 1, x);
            else p.setString(i + 1, String.valueOf(o));
        }
    }

    private static String explain(Connection c, String sql, Object[] binds) throws SQLException {
        StringBuilder b = new StringBuilder();
        try (PreparedStatement p = c.prepareStatement("EXPLAIN ANALYZE " + sql)) {
            bind(p, binds);
            try (ResultSet r = p.executeQuery()) { while (r.next()) b.append(r.getString(2)).append('\n'); }
        }
        return b.toString();
    }

    /** What the plan says about files and rows: Total Files Read, rows scanned by the scan operator, and rows out. */
    private static String filesRead(String plan) {
        StringBuilder b = new StringBuilder();
        Matcher f = Pattern.compile("Total Files Read:\\s*(\\d+)").matcher(plan);
        while (f.find()) b.append("Total Files Read=").append(f.group(1)).append(' ');
        Matcher r = Pattern.compile("(?m)^\\s*│\\s*(\\d[\\d,]*) rows?\\s*│").matcher(plan);
        List<String> rows = new ArrayList<>();
        while (r.find()) rows.add(r.group(1));
        b.append("operator row counts=").append(rows);
        Matcher e = Pattern.compile("Scanning Row Groups:\\s*(\\S+)").matcher(plan);
        while (e.find()) b.append(" rowGroups=").append(e.group(1));
        return b.toString();
    }

    private static String tryOne(Statement s, String sql) {
        try (ResultSet r = s.executeQuery(sql)) {
            StringBuilder b = new StringBuilder("OK ");
            int k = 0;
            while (r.next() && k++ < 3) b.append(r.getObject(1)).append(' ');
            return b.toString().trim();
        } catch (SQLException e) {
            return "FAIL " + e.getMessage().lines().findFirst().orElse("");
        }
    }

    private static String tryExec(Connection c, String sql) {
        try (Statement s = c.createStatement()) { s.execute(sql); return "OK"; } catch (SQLException e) { return "FAIL " + e.getMessage().lines().findFirst().orElse(""); }
    }

    private static String one(Statement s, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) { r.next(); return String.valueOf(r.getObject(1)); }
    }

    private static long dirBytes(Path d) throws IOException {
        try (Stream<Path> w = Files.walk(d)) { return w.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } }).sum(); }
    }

    /** Polls DuckDB's own memory accounting and (Windows) the process working set; reports peaks. */
    private static final class Sampler implements AutoCloseable {
        volatile long peakDuck, peakRss;   // peakRss in KB
        private final Thread th;
        private volatile boolean run = true;
        Sampler(Connection c) throws SQLException {
            Connection dup = ((org.duckdb.DuckDBConnection) c).duplicate();
            String pid = String.valueOf(ProcessHandle.current().pid());
            th = new Thread(() -> {
                try (Statement s = dup.createStatement()) {
                    while (run) {
                        try (ResultSet r = s.executeQuery("SELECT coalesce(sum(memory_usage_bytes),0) FROM duckdb_memory()")) { r.next(); peakDuck = Math.max(peakDuck, r.getLong(1)); }
                        try {
                            Process p = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH").redirectErrorStream(true).start();
                            String line = new String(p.getInputStream().readAllBytes());
                            Matcher m = Pattern.compile("\"([\\d,.]+) K\"").matcher(line);
                            if (m.find()) peakRss = Math.max(peakRss, Long.parseLong(m.group(1).replaceAll("[,.]", "")));
                        } catch (IOException ignore) { /* not Windows: no RSS */ }
                        Thread.sleep(2000);
                    }
                } catch (Exception ignore) { /* sampler is best-effort */ }
                finally { try { dup.close(); } catch (SQLException ignore) { } }
            }, "d3-sampler");
            th.setDaemon(true);
            th.start();
        }
        @Override public void close() { run = false; th.interrupt(); }
    }

    private static List<Long> sizes() {
        List<Long> out = new ArrayList<>();
        for (String s : System.getProperty("inspecto.bench.sizes", "1000000,10000000").split(",")) out.add(Long.parseLong(s.trim()));
        return out;
    }

    private static String sqlPath(Path p) { return p.toAbsolutePath().toString().replace('\\', '/'); }

    private static double pct(List<Double> xs, int p) {
        if (xs.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        int i = (int) Math.ceil(p / 100.0 * s.size()) - 1;
        return s.get(Math.max(0, Math.min(s.size() - 1, i)));
    }

    private static void log(String line) {
        System.out.println("[bench] " + line);
        try (PrintStream out = new PrintStream(Files.newOutputStream(DIR.resolve("results_d3s1.txt"), StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            out.println(line);
        } catch (IOException ignore) {
            // stdout already has it
        }
    }
}
