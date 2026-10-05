import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * DuckDB like-for-like workloads (scan, csv, store, readers). Single-file launch, driven by tools/bench-duckdb.ps1:
 * {@code java --enable-native-access=ALL-UNNAMED -cp <duckdb_jdbc.jar> DuckDbBench.java --workload scan --out r.json}.
 * Writes {"duckdbVersion","java","workload","metrics":{name:[sample per measured rep]}}. Metric names end _ms (lower is
 * better) or _rps/_qps (higher is better); the compare script reads the suffix.
 */
public class DuckDbBench {
    interface Body { Map<String, Double> run() throws Exception; }

    static String workload = "scan", out = "bench.json";
    static int warmup = 1, reps = 3;
    static double scale = 1.0;
    static Path corpus = Path.of(System.getProperty("java.io.tmpdir"), "inspecto-bench-corpus");
    static String version;

    static String sql(Path p) { return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''"); }
    static int n(long base) { return (int) Math.max(100, Math.round(base * scale)); }
    static double ms(long t0) { return (System.nanoTime() - t0) / 1e6; }
    static double rate(long count, long t0) { return count / (ms(t0) / 1000); }

    static long drain(Statement st, String q) throws SQLException {
        long rows = 0;
        try (ResultSet rs = st.executeQuery(q)) { while (rs.next()) rows++; }
        return rows;
    }

    static void deleteTree(Path d) throws IOException {
        if (!Files.exists(d)) return;
        try (Stream<Path> w = Files.walk(d)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
    }

    /** (a) parquet scan + filter + aggregate over a generated Dataset. The corpus file is shared across versions. */
    static Body scan() throws Exception {
        int rows = n(5_000_000);
        Files.createDirectories(corpus);
        Path f = corpus.resolve("scan-" + rows + ".parquet");
        if (!Files.exists(f)) try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT i AS id, 'c' || (hash(i) % 1000) AS cat, (hash(i * 7) % 100000) / 100.0 AS v,"
                    + " TIMESTAMP '2026-01-01' + to_seconds(i % 31536000) AS ts, 'k' || (hash(i * 3) % 500000) AS key"
                    + " FROM range(" + rows + ") r(i)) TO '" + sql(f) + "' (FORMAT parquet)");
        }
        String src = "read_parquet('" + sql(f) + "')";
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        return () -> {
            Map<String, Double> m = new LinkedHashMap<>();
            try (Statement st = c.createStatement()) {
                long t = System.nanoTime();
                drain(st, "SELECT cat, count(*), sum(v), avg(v) FROM " + src + " WHERE ts >= TIMESTAMP '2026-03-01' AND v > 250 GROUP BY cat");
                m.put("filter_agg_ms", ms(t));
                m.put("filter_agg_rps", rate(rows, t));
                t = System.nanoTime();
                drain(st, "SELECT key, count(*) AS n FROM " + src + " GROUP BY key");
                m.put("groupby_highcard_ms", ms(t));
                t = System.nanoTime();
                drain(st, "SELECT count(DISTINCT key), count(DISTINCT cat) FROM " + src);
                m.put("count_distinct_ms", ms(t));
                t = System.nanoTime();
                drain(st, "SELECT * FROM " + src + " ORDER BY v DESC, id LIMIT 100");
                m.put("topn_ms", ms(t));
            }
            return m;
        };
    }

    /** (c) ingest-shaped: read_csv -> transform SQL -> partitioned parquet write (the DuckDbCsvIngester path, in SQL). */
    static Body csv() throws Exception {
        int rows = n(2_000_000);
        Files.createDirectories(corpus);
        Path f = corpus.resolve("ingest-" + rows + ".csv");
        if (!Files.exists(f)) try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT i AS id, 'name_' || (hash(i) % 5000) AS name, (hash(i * 7) % 100000) / 100.0 AS amount,"
                    + " strftime(DATE '2026-01-01' + CAST(i % 30 AS INTEGER), '%Y-%m-%d') AS day, 'x' || (hash(i * 3) % 100) AS c1,"
                    + " 'y' || (hash(i * 5) % 100) AS c2, (hash(i * 11) % 1000) AS n1, (hash(i * 13) % 1000) AS n2, 'z' || i AS c3,"
                    + " (hash(i * 17) % 10) AS n3, 'w' || (hash(i * 19) % 7) AS c4, i % 2 = 0 AS flag FROM range(" + rows + ") r(i))"
                    + " TO '" + sql(f) + "' (FORMAT csv, HEADER)");
        }
        Path outDir = Files.createTempDirectory("dbbench-csv");
        return () -> {
            Map<String, Double> m = new LinkedHashMap<>();
            Path o = outDir.resolve("o");
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
                long t0 = System.nanoTime(), t = t0;
                st.execute("CREATE TABLE raw AS SELECT * FROM read_csv('" + sql(f) + "')");
                m.put("read_csv_ms", ms(t));
                t = System.nanoTime();
                st.execute("CREATE TABLE tf AS SELECT id, upper(name) AS name, try_cast(amount AS DECIMAL(12,2)) AS amount,"
                        + " CAST(day AS DATE) AS day, c1 || '-' || c2 AS c12, n1 + n2 AS n, length(c3) AS l3, n3, c4, flag FROM raw");
                m.put("transform_ms", ms(t));
                t = System.nanoTime();
                st.execute("COPY tf TO '" + sql(o) + "' (FORMAT parquet, PARTITION_BY (day), OVERWRITE_OR_IGNORE)");
                m.put("write_parquet_ms", ms(t));
                m.put("total_rps", rate(rows, t0));
            } finally { deleteTree(o); }
            return m;
        };
    }

    /** (d) INSERT / UPSERT throughput on file-backed operational-store shapes (dedup ledger, status, events). */
    static Body store() throws Exception {
        int big = n(50_000), single = n(2_000), resync = n(100);
        Path dir = Files.createTempDirectory("dbbench-store");
        return () -> {
            Map<String, Double> m = new LinkedHashMap<>();
            Path db = dir.resolve("s.duckdb");
            db.toFile().delete();
            dir.resolve("s.duckdb.wal").toFile().delete();
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db.toAbsolutePath()); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE ledger (k VARCHAR, a VARCHAR, b VARCHAR, c VARCHAR, claimed_at TIMESTAMP, PRIMARY KEY (k, a))");
                st.execute("CREATE TABLE status (k VARCHAR PRIMARY KEY, payload VARCHAR, n BIGINT)");
                st.execute("CREATE TABLE events (id VARCHAR, ts TIMESTAMP, kind VARCHAR, sev VARCHAR, src VARCHAR, subj VARCHAR,"
                        + " msg VARCHAR, attrs VARCHAR, actor VARCHAR, space VARCHAR, audit_seq BIGINT)");
                st.execute("CREATE TABLE rows_t (pipeline VARCHAR, seq INTEGER, payload VARCHAR)");
                String ins = "INSERT INTO ledger VALUES (?,?,?,?,now()) ON CONFLICT DO NOTHING";
                m.put("ledger_batch_rps", ledger(c, ins, big, 0, true));
                m.put("ledger_dupe_rps", ledger(c, ins, big, 0, true));       // every key conflicts
                m.put("ledger_single_rps", ledger(c, ins, single, 10_000_000, false));   // autocommit, one row per statement
                c.setAutoCommit(false);
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO status VALUES (?,?,?) ON CONFLICT (k) DO UPDATE SET payload = excluded.payload, n = excluded.n")) {
                    for (int pass = 0; pass < 2; pass++) {   // pass 0 seeds; pass 1 upserts half existing / half new keys
                        long t = System.nanoTime();
                        for (int i = 0; i < big; i++) {
                            p.setString(1, "k" + (i + pass * big / 2)); p.setString(2, "payload-" + i + "-" + pass); p.setLong(3, i); p.addBatch();
                        }
                        p.executeBatch(); c.commit();
                        if (pass == 1) m.put("upsert_rps", rate(big, t));
                    }
                }
                long t = System.nanoTime();
                try (PreparedStatement p = c.prepareStatement("INSERT INTO events VALUES (?,now(),?,?,?,?,?,?,?,?,?)")) {
                    for (int i = 0; i < big; i++) {
                        p.setString(1, "e" + i); p.setString(2, "k" + i % 20); p.setString(3, "INFO"); p.setString(4, "src");
                        p.setString(5, "subj" + i % 1000); p.setString(6, "message number " + i); p.setString(7, "{}");
                        p.setString(8, "u"); p.setString(9, "demo"); p.setLong(10, i); p.addBatch();
                    }
                    p.executeBatch(); c.commit();
                }
                m.put("events_batch_rps", rate(big, t));
                // DbStatusStore re-sync shape: DELETE the pipeline's rows, then INSERT its current rows, one commit each
                t = System.nanoTime();
                try (PreparedStatement d = c.prepareStatement("DELETE FROM rows_t WHERE pipeline = ?");
                     PreparedStatement p = c.prepareStatement("INSERT INTO rows_t VALUES (?,?,?)")) {
                    for (int r = 0; r < resync; r++) {
                        d.setString(1, "p" + r % 5); d.executeUpdate();
                        for (int i = 0; i < 200; i++) { p.setString(1, "p" + r % 5); p.setInt(2, i); p.setString(3, "payload " + i); p.addBatch(); }
                        p.executeBatch(); c.commit();
                    }
                }
                m.put("resync_rps", rate(resync * 200L, t));
            }
            return m;
        };
    }

    static double ledger(Connection c, String ins, int rows, int off, boolean batch) throws SQLException {
        c.setAutoCommit(!batch);
        long t = System.nanoTime();
        try (PreparedStatement p = c.prepareStatement(ins)) {
            for (int i = 0; i < rows; i++) {
                p.setString(1, "file-" + (i + off)); p.setString(2, "pipe"); p.setString(3, "md5-" + i); p.setString(4, "claimed");
                if (batch) p.addBatch(); else p.executeUpdate();
            }
            if (batch) { p.executeBatch(); c.commit(); }
        }
        return rate(rows, t);
    }

    /** (e) concurrent readers on one file: T threads, each its own duplicate() connection of one database. */
    static Body readers() throws Exception {
        int rows = n(2_000_000), perThread = n(20);
        Path dir = Files.createTempDirectory("dbbench-readers");
        Connection main = DriverManager.getConnection("jdbc:duckdb:" + dir.resolve("r.duckdb").toAbsolutePath());
        try (Statement st = main.createStatement()) {
            st.execute("CREATE TABLE t AS SELECT i AS id, hash(i) % 1000 AS grp, (hash(i * 7) % 100000) / 100.0 AS v,"
                    + " 'k' || (hash(i * 3) % 50000) AS key FROM range(" + rows + ") r(i)");
        }
        return () -> {
            Map<String, Double> m = new LinkedHashMap<>();
            for (int threads : new int[]{1, 4, 8}) {
                ExecutorService ex = Executors.newFixedThreadPool(threads);
                List<Connection> conns = new ArrayList<>();
                for (int i = 0; i < threads; i++) conns.add(((org.duckdb.DuckDBConnection) main).duplicate());
                long t = System.nanoTime();
                List<Future<?>> fs = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    final int id = i;
                    fs.add(ex.submit(() -> {
                        try (Statement st = conns.get(id).createStatement()) {
                            for (int q = 0; q < perThread; q++) {
                                drain(st, "SELECT count(*), sum(v) FROM t WHERE grp = " + ((id * 131 + q * 17) % 1000));
                                drain(st, "SELECT key, count(*) FROM t WHERE id % 97 = " + (q % 97) + " GROUP BY key ORDER BY 2 DESC LIMIT 10");
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> f : fs) f.get();
                m.put("readers_t" + threads + "_qps", rate((long) threads * perThread * 2, t));
                ex.shutdown();
                for (Connection x : conns) x.close();
            }
            return m;
        };
    }

    static String json(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    public static void main(String[] a) throws Exception {
        for (int i = 0; i + 1 < a.length; i += 2) switch (a[i]) {
            case "--workload" -> workload = a[i + 1];
            case "--out" -> out = a[i + 1];
            case "--warmup" -> warmup = Integer.parseInt(a[i + 1]);
            case "--reps" -> reps = Integer.parseInt(a[i + 1]);
            case "--scale" -> scale = Double.parseDouble(a[i + 1]);
            case "--corpus" -> corpus = Path.of(a[i + 1]);
            default -> throw new IllegalArgumentException("unknown arg " + a[i]);
        }
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT version()")) {
            rs.next();
            version = rs.getString(1);
        }
        Body body = switch (workload) {
            case "scan" -> scan();
            case "csv" -> csv();
            case "store" -> store();
            case "readers" -> readers();
            default -> throw new IllegalArgumentException("unknown workload " + workload);
        };
        Map<String, List<Double>> samples = new LinkedHashMap<>();
        for (int i = 0; i < warmup + reps; i++) {
            Map<String, Double> m = body.run();
            if (i >= warmup) m.forEach((k, v) -> samples.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
            System.out.println("rep " + (i < warmup ? "warm " : "measured ") + i + " " + m);
        }
        StringBuilder sb = new StringBuilder("{\"duckdbVersion\":" + json(version) + ",\"java\":" + json(System.getProperty("java.version"))
                + ",\"workload\":" + json(workload) + ",\"scale\":" + scale + ",\"metrics\":{");
        boolean first = true;
        for (var e : samples.entrySet()) {
            sb.append(first ? "" : ",").append(json(e.getKey())).append(":").append(e.getValue());
            first = false;
        }
        Files.writeString(Path.of(out), sb.append("}}").toString());
        System.exit(0);   // DuckDB native threads / executors must not hold the launcher open
    }
}
