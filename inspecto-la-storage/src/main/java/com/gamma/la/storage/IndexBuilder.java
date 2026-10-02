package com.gamma.la.storage;

import com.gamma.la.storage.IndexManifest.TableStats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The full build of one edge index (D-3 design 3.2): both edge directions, the node table, verification, the manifest,
 * and the atomic publish through {@link IndexStore}.
 *
 * <h2>Trust boundary</h2>
 * {@link Request#relationSql()} is TRUSTED SQL: the caller got it from {@code DatasetProvider.relationSql} AFTER its own
 * view gate (R3: may this Subject view the Dataset?). The builder never calls that gate, never sees a Subject, and
 * executes the text as-is on its OWN, non-sandboxed in-memory DuckDB connection (the sandbox forbids {@code COPY ... TO}).
 * Everything else that reaches SQL - column names (double-quoted), the time zone (validated), the memory limit
 * (validated), paths (single-quote escaped) - is validated or escaped here.
 *
 * <h2>Output</h2>
 * Into the stage {@code vNNNNNN.tmp}: {@code out/bucket=B/*.parquet} (edges keyed by source), {@code in/...} (the same
 * edges keyed by target) and {@code nodes/...}, each {@code PARTITION_BY (bucket)}, {@code ROW_GROUP_SIZE 100000}, zstd,
 * sorted by {@code (bucket, key, ts)}; columns {@code src, dst, kind, ts, w, a0..ak}. Ids are the raw column values CAST to
 * VARCHAR; rows with a NULL endpoint are dropped and counted ({@code droppedNull}). {@code ts} is the instant of the time
 * column stored as a NAIVE UTC timestamp (a naive source column is read in the mapping's explicit zone, UTC when none;
 * the session TimeZone is pinned to UTC and never decides anything). {@code kind} / {@code ts} / {@code w} are NULL when
 * unmapped. The bucket is {@link BucketFunction}. {@code nodes} folds {@code *_links} as distinct (neighbour, kind).
 *
 * <h2>Failure and cancel</h2>
 * ANY failure or cancel closes the connection, deletes the stage and leaves CURRENT untouched. Cancel is
 * {@link CancelToken#cancel()}: a volatile flag checked between statements AND {@code Statement.cancel()} on the running
 * statement, issued from the cancelling thread. A background heartbeat keeps the stage "in flight" for
 * {@link IndexStore#gc}.
 */
public final class IndexBuilder {

    public static final int ROW_GROUP_SIZE = 100_000;
    private static final String REL = "__la_rel";
    private static final String EDGES = "__la_edges";
    private static final Pattern ZONE = Pattern.compile("[A-Za-z0-9_+\\-/]+");
    private static final Pattern MEMORY = Pattern.compile("[0-9]+(\\.[0-9]+)?\\s*[A-Za-z]{0,3}");
    private static final int STEPS = 6;

    private IndexBuilder() { }

    /** Cooperative + hard cancel of a running build; share one token between the builder thread and the canceller. */
    public static final class CancelToken {
        private volatile boolean cancelled;
        private volatile Statement running;

        public void cancel() {
            cancelled = true;
            Statement s = running;
            if (s != null) {
                try {
                    s.cancel();
                } catch (SQLException ignored) {
                    // the statement finished (or the connection closed) between the read and the cancel: the flag still stops the build
                }
            }
        }

        public boolean isCancelled() { return cancelled; }
    }

    /** {@code phase} is one of estimate, out, in, nodes, verify, publish; {@code step} counts from 1 of {@code steps}. */
    public record Progress(String phase, int step, int steps) { }

    /**
     * @param buckets     override of the signed bucket-count formula (tests); null = formula
     * @param memoryLimit DuckDB {@code memory_limit} (e.g. {@code 512MB}); null = DuckDB default
     * @param threads     DuckDB {@code threads}; null = default
     * @param cancel      null = not cancellable
     * @param progress    null = none; called on the builder thread
     */
    public record Options(Integer buckets, String memoryLimit, Integer threads, CancelToken cancel, Consumer<Progress> progress) {
        public static Options defaults() { return new Options(null, null, null, null, null); }
    }

    /**
     * @param datasetId       recorded in the manifest (the store's directory name is derived from it separately)
     * @param relationSql     TRUSTED relation SQL (a SELECT) - see the class doc
     * @param baseFingerprint the caller's fingerprint of the base data; the builder does not list files
     */
    public record Request(String datasetId, IndexMapping mapping, String relationSql, IndexStore store, String baseFingerprint,
                          Options options) {
        public Request {
            Objects.requireNonNull(datasetId, "datasetId");
            Objects.requireNonNull(mapping, "mapping");
            Objects.requireNonNull(relationSql, "relationSql");
            Objects.requireNonNull(store, "store");
            if (options == null) options = Options.defaults();
        }
    }

    /** @param rowsInRelation rows of the relation; {@code edges} = indexed edges (per direction) = rows - droppedNull */
    public record Result(long version, Path directory, IndexManifest manifest, long rowsInRelation, long edges, long droppedNull,
                         long nodes, int buckets, Map<String, Long> timingsMs, long totalMs) { }

    public static class IndexBuildException extends RuntimeException {
        public IndexBuildException(String message) { super(message); }

        public IndexBuildException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class CancelledException extends IndexBuildException {
        public CancelledException() { super("index build cancelled"); }
    }

    // ------------------------------------------------------------------------------------------------------ build

    public static Result build(Request req) {
        Options opt = req.options();
        IndexMapping m = req.mapping();
        if (opt.memoryLimit() != null && !MEMORY.matcher(opt.memoryLimit()).matches())
            throw new IllegalArgumentException("not a memory limit: '" + opt.memoryLimit() + "'");
        if (m.timeColZone() != null && !ZONE.matcher(m.timeColZone()).matches())
            throw new IllegalArgumentException("not a time zone id: '" + m.timeColZone() + "'");
        if (opt.buckets() != null && opt.buckets() < 1) throw new IllegalArgumentException("buckets must be >= 1");
        if (opt.threads() != null && opt.threads() < 1) throw new IllegalArgumentException("threads must be >= 1");

        long t0 = System.nanoTime();
        Run run = new Run(req);
        Path stage = null;
        ScheduledExecutorService beat = null;
        try {
            run.check();
            stage = req.store().stage();
            run.stage = stage;
            final Path st = stage;
            beat = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "la-index-heartbeat");
                t.setDaemon(true);
                return t;
            });
            beat.scheduleWithFixedDelay(() -> {
                try {
                    req.store().heartbeat(st);
                } catch (IOException | RuntimeException ignored) {
                    // the stage is gone (build ended); nothing to keep alive
                }
            }, 30, 30, TimeUnit.SECONDS);
            Result r = run.execute(t0);
            return r;
        } catch (CancelledException | IllegalArgumentException e) {
            run.abandon(stage);
            throw e;
        } catch (Throwable e) {
            run.abandon(stage);
            if (e instanceof IndexBuildException ibe) throw ibe;
            throw new IndexBuildException("index build failed: " + e.getMessage(), e);
        } finally {
            if (beat != null) beat.shutdownNow();
            run.close();
        }
    }

    /** Re-verifies a PUBLISHED (or staged) version directory against its own manifest; throws {@link IndexBuildException}. */
    public static IndexManifest verify(Path versionDir) {
        IndexManifest man;
        try {
            man = IndexManifest.read(versionDir);
        } catch (IOException | IllegalArgumentException e) {
            throw new IndexBuildException("cannot read the manifest of " + versionDir.getFileName() + ": " + e.getMessage(), e);
        }
        try (Connection c = open(null, null, null)) {
            Map<String, TableStats> actual = scan(c, versionDir, man.buckets(), null);
            invariants(c, versionDir, actual, null);
            for (var e : man.tables().entrySet()) {
                TableStats a = actual.get(e.getKey());
                if (a == null || a.rows() != e.getValue().rows() || a.files() != e.getValue().files())
                    throw new IndexBuildException("table " + e.getKey() + " differs from its manifest: manifest "
                            + e.getValue().rows() + " rows / " + e.getValue().files() + " files, found "
                            + (a == null ? "none" : a.rows() + " rows / " + a.files() + " files"));
            }
            return man;
        } catch (SQLException e) {
            throw new IndexBuildException("verification could not read " + versionDir.getFileName() + ": " + e.getMessage(), e);
        }
    }

    // --------------------------------------------------------------------------------------------- one build run

    private static final class Run {
        final Request req;
        final Options opt;
        final CancelToken token;
        final Map<String, Long> timings = new LinkedHashMap<>();
        Connection conn;
        Path stage;

        Run(Request req) {
            this.req = req;
            this.opt = req.options();
            this.token = opt.cancel();
        }

        void check() {
            if (token != null && token.isCancelled()) throw new CancelledException();
        }

        void progress(String phase, int step) {
            check();
            if (stage != null) {
                try {
                    req.store().heartbeat(stage);
                } catch (IOException ignored) {
                    // best effort; the background heartbeat also runs
                }
            }
            if (opt.progress() != null) opt.progress().accept(new Progress(phase, step, STEPS));
        }

        void exec(String sql) throws SQLException {
            check();
            try (Statement s = conn.createStatement()) {
                if (token != null) {
                    token.running = s;
                    check();   // a cancel that raced the registration
                }
                try {
                    s.execute(sql);
                } finally {
                    if (token != null) token.running = null;
                }
            } catch (SQLException e) {
                if (token != null && token.isCancelled()) throw new CancelledException();
                throw e;
            }
        }

        List<Object[]> rows(String sql) throws SQLException {
            check();
            try (Statement s = conn.createStatement()) {
                if (token != null) {
                    token.running = s;
                    check();
                }
                try (ResultSet rs = s.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<Object[]> out = new ArrayList<>();
                    while (rs.next()) {
                        Object[] row = new Object[md.getColumnCount()];
                        for (int i = 0; i < row.length; i++) row[i] = rs.getObject(i + 1);
                        out.add(row);
                    }
                    return out;
                } finally {
                    if (token != null) token.running = null;
                }
            } catch (SQLException e) {
                if (token != null && token.isCancelled()) throw new CancelledException();
                throw e;
            }
        }

        Result execute(long t0) throws Exception {
            IndexMapping m = req.mapping();
            Path spill = stage.resolve(".spill");
            conn = open(opt.memoryLimit(), opt.threads(), spill);

            // -- estimate -------------------------------------------------------------------------------------
            long t = System.nanoTime();
            progress("estimate", 1);
            exec("CREATE TEMP VIEW " + REL + " AS " + req.relationSql().strip().replaceAll(";+\\s*$", ""));
            Map<String, String> types = columnTypes();
            String ts = timeExpr(m, types);
            String edgeCols = "src, dst, kind, ts, w" + attrAliases(m);
            String srcQ = column(m.srcColumn(), types), dstQ = column(m.dstColumn(), types);
            exec("CREATE TEMP VIEW " + EDGES + " AS SELECT CAST(" + srcQ + " AS VARCHAR) AS src, CAST(" + dstQ + " AS VARCHAR) AS dst, "
                    + (m.kindColumn() == null ? "CAST(NULL AS VARCHAR)" : "CAST(" + column(m.kindColumn(), types) + " AS VARCHAR)") + " AS kind, "
                    + ts + " AS ts, "
                    + (m.weightColumn() == null ? "CAST(NULL AS DOUBLE)" : "CAST(" + column(m.weightColumn(), types) + " AS DOUBLE)") + " AS w"
                    + attrSelect(m, types)
                    + " FROM " + REL + " WHERE " + srcQ + " IS NOT NULL AND " + dstQ + " IS NOT NULL");
            Object[] counts = rows("SELECT count(*), count(*) FILTER (WHERE " + srcQ + " IS NOT NULL AND " + dstQ + " IS NOT NULL) FROM " + REL).get(0);
            long total = ((Number) counts[0]).longValue();
            long kept = ((Number) counts[1]).longValue();
            long dropped = total - kept;
            if (kept == 0) throw new IndexBuildException("the relation has no edge with both endpoints set (" + total + " rows, " + dropped + " with a NULL endpoint)");
            int n = opt.buckets() != null ? opt.buckets() : BucketFunction.bucketsFor(total);
            timings.put("estimate", ms(t));

            // -- out / in / nodes ---------------------------------------------------------------------------------
            int step = 2;
            for (String[] d : new String[][] {{"out", "src"}, {"in", "dst"}}) {
                t = System.nanoTime();
                progress(d[0], step++);
                exec("COPY (SELECT " + BucketFunction.sql(d[1], n) + " AS bucket, " + edgeCols + " FROM " + EDGES
                        + " ORDER BY bucket, " + d[1] + ", ts) TO '" + sqlPath(stage.resolve(d[0])) + "' " + COPY_OPTIONS);
                timings.put(d[0], ms(t));
            }
            t = System.nanoTime();
            progress("nodes", step);
            exec("COPY (SELECT " + BucketFunction.sql("id", n) + " AS bucket, id, coalesce(o.oe, 0) AS out_edges, coalesce(i.ie, 0) AS in_edges,"
                    + " coalesce(o.ol, 0) AS out_links, coalesce(i.il, 0) AS in_links, least(o.f, i.f) AS first_ts, greatest(o.l, i.l) AS last_ts FROM"
                    + " (SELECT src AS id, count(*) AS oe, count(DISTINCT struct_pack(n := dst, k := kind)) AS ol, min(ts) AS f, max(ts) AS l FROM " + EDGES + " GROUP BY src) o"
                    + " FULL JOIN (SELECT dst AS id, count(*) AS ie, count(DISTINCT struct_pack(n := src, k := kind)) AS il, min(ts) AS f, max(ts) AS l FROM " + EDGES + " GROUP BY dst) i USING (id)"
                    + " ORDER BY bucket, id) TO '" + sqlPath(stage.resolve("nodes")) + "' " + COPY_OPTIONS);
            timings.put("nodes", ms(t));

            // -- verify ---------------------------------------------------------------------------------------------
            t = System.nanoTime();
            progress("verify", 6);
            Map<String, TableStats> stats = scan(conn, stage, n, token);
            if (stats.get("out").rows() != kept || stats.get("in").rows() != kept)
                throw new IndexBuildException("verification failed: the relation has " + kept + " edges with both endpoints, but out holds "
                        + stats.get("out").rows() + " rows and in holds " + stats.get("in").rows());
            invariants(conn, stage, stats, token);
            timings.put("verify", ms(t));
            String duck = rows("SELECT version()").get(0)[0].toString();

            // -- manifest + publish -----------------------------------------------------------------------------------
            long number = Long.parseLong(stage.getFileName().toString().replaceAll("\\D", ""));
            String zone = effectiveZone(m, types);
            IndexManifest man = new IndexManifest(number, Instant.now().toString(), IndexManifest.Builder.FULL, duck, BucketFunction.NAME, n,
                    ROW_GROUP_SIZE, m, m.hash(), req.datasetId(), sha256(req.relationSql()), req.baseFingerprint(), zone, stats, dropped, null, null);
            man.write(stage);
            check();
            close();   // release the files (Windows) and the spill directory before the stage is renamed
            deleteSpill(spill);
            t = System.nanoTime();
            if (opt.progress() != null) opt.progress().accept(new Progress("publish", STEPS, STEPS));
            Path published = req.store().publish(stage);
            stage = null;
            timings.put("publish", ms(t));
            return new Result(number, published, man, total, kept, dropped, stats.get("nodes").rows(), n, timings, ms(t0));
        }

        Map<String, String> columnTypes() throws SQLException {
            Map<String, String> types = new LinkedHashMap<>();
            for (Object[] r : rows("DESCRIBE SELECT * FROM " + REL)) types.put(r[0].toString().toLowerCase(Locale.ROOT), r[1].toString());
            return types;
        }

        /** The double-quoted column, after checking the relation has it. */
        String column(String name, Map<String, String> types) {
            if (!types.containsKey(name.toLowerCase(Locale.ROOT)))
                throw new IndexBuildException("the relation has no column '" + name + "' (it has " + types.keySet() + ")");
            return "\"" + name.replace("\"", "\"\"") + "\"";
        }

        String effectiveZone(IndexMapping m, Map<String, String> types) {
            if (m.timeColumn() == null) return null;
            return isTimestampTz(types.get(m.timeColumn().toLowerCase(Locale.ROOT))) ? null : (m.timeColZone() == null ? "UTC" : m.timeColZone());
        }

        String timeExpr(IndexMapping m, Map<String, String> types) {
            if (m.timeColumn() == null) {
                if (m.timeColZone() != null) throw new IllegalArgumentException("a time zone needs a time column");
                return "CAST(NULL AS TIMESTAMP)";
            }
            String col = column(m.timeColumn(), types);
            String type = types.get(m.timeColumn().toLowerCase(Locale.ROOT)).toUpperCase(Locale.ROOT);
            if (isTimestampTz(type)) {
                if (m.timeColZone() != null)
                    throw new IllegalArgumentException("time column '" + m.timeColumn() + "' is a TIMESTAMP WITH TIME ZONE (already an instant); a timeColZone would be ignored");
                return "CAST(timezone('UTC', " + col + ") AS TIMESTAMP)";
            }
            if (type.equals("TIMESTAMP") || type.equals("TIMESTAMP_S") || type.equals("TIMESTAMP_MS") || type.equals("TIMESTAMP_NS") || type.equals("DATE")) {
                String zone = m.timeColZone() == null ? "UTC" : m.timeColZone();
                return "CAST(timezone('UTC', timezone('" + zone + "', CAST(" + col + " AS TIMESTAMP))) AS TIMESTAMP)";
            }
            throw new IndexBuildException("time column '" + m.timeColumn() + "' has type " + type + "; expected TIMESTAMP, DATE or TIMESTAMP WITH TIME ZONE");
        }

        static boolean isTimestampTz(String type) {
            String t = type.toUpperCase(Locale.ROOT);
            return t.equals("TIMESTAMP WITH TIME ZONE") || t.equals("TIMESTAMPTZ");
        }

        static String attrAliases(IndexMapping m) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < m.attributeColumns().size(); i++) sb.append(", a").append(i);
            return sb.toString();
        }

        String attrSelect(IndexMapping m, Map<String, String> types) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < m.attributeColumns().size(); i++)
                sb.append(", CAST(").append(column(m.attributeColumns().get(i), types)).append(" AS VARCHAR) AS a").append(i);
            return sb.toString();
        }

        void abandon(Path st) {
            close();
            if (st != null) {
                try {
                    req.store().discard(st);
                } catch (IOException | RuntimeException ignored) {
                    // an unremovable stage is cleaned by the next stage() / gc()
                }
            }
        }

        void close() {
            Connection c = conn;
            conn = null;
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // closing a cancelled connection may report; nothing left to release
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------- shared helpers

    private static final String COPY_OPTIONS = "(FORMAT parquet, PARTITION_BY (bucket), ROW_GROUP_SIZE " + ROW_GROUP_SIZE + ", COMPRESSION zstd)";

    private static Connection open(String memoryLimit, Integer threads, Path spill) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = c.createStatement()) {
            s.execute("SET TimeZone = 'UTC'");   // never the host zone
            if (memoryLimit != null) s.execute("SET memory_limit = '" + memoryLimit + "'");
            if (threads != null) s.execute("SET threads = " + threads);
            if (spill != null) s.execute("SET temp_directory = '" + sqlPath(spill) + "'");
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    /**
     * Per-table rows (parquet footers), files and bytes, with the LAYOUT checks: every bucket directory is
     * {@code bucket=<int in [0,buckets)>} and holds a parquet file. A bucket directory that is absent is provably empty
     * because the row totals are checked against the relation / manifest by the caller.
     */
    private static Map<String, TableStats> scan(Connection c, Path root, int buckets, CancelToken token) throws SQLException {
        Map<String, TableStats> out = new LinkedHashMap<>();
        for (String table : new String[] {"out", "in", "nodes"}) {
            if (token != null && token.isCancelled()) throw new CancelledException();
            List<Path> files = parquetFiles(root.resolve(table), buckets);
            long bytes = 0;
            for (Path f : files) {
                try {
                    bytes += Files.size(f);
                } catch (IOException e) {
                    throw new IndexBuildException("cannot stat " + f + ": " + e.getMessage(), e);
                }
            }
            long rows = 0;
            if (!files.isEmpty()) {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT sum(num_rows) FROM parquet_file_metadata(" + fileList(files) + ")")) {
                    rs.next();
                    rows = rs.getLong(1);
                }
            }
            out.put(table, new TableStats(rows, files.size(), bytes));
        }
        return out;
    }

    private static List<Path> parquetFiles(Path tableDir, int buckets) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(tableDir)) return files;
        try (Stream<Path> dirs = Files.list(tableDir)) {
            for (Path d : (Iterable<Path>) dirs.sorted()::iterator) {
                String name = d.getFileName().toString();
                int b;
                try {
                    if (!name.startsWith("bucket=")) throw new NumberFormatException();
                    b = Integer.parseInt(name.substring("bucket=".length()));
                } catch (NumberFormatException e) {
                    throw new IndexBuildException("unexpected entry '" + name + "' in " + tableDir.getFileName() + " (expected bucket=<n>)");
                }
                if (b < 0 || b >= buckets)
                    throw new IndexBuildException("bucket directory " + name + " in " + tableDir.getFileName() + " is outside [0," + buckets + ")");
                int before = files.size();
                try (Stream<Path> fs = Files.list(d)) {
                    fs.filter(f -> f.getFileName().toString().endsWith(".parquet")).sorted().forEach(files::add);
                }
                if (files.size() == before) throw new IndexBuildException("bucket directory " + name + " in " + tableDir.getFileName() + " holds no parquet file");
            }
        } catch (IOException e) {
            throw new IndexBuildException("cannot list " + tableDir + ": " + e.getMessage(), e);
        }
        return files;
    }

    /** rows(out) = rows(in); the node table is non-empty and its folded totals equal the edge rows. */
    private static void invariants(Connection c, Path root, Map<String, TableStats> stats, CancelToken token) throws SQLException {
        long out = stats.get("out").rows(), in = stats.get("in").rows(), nodes = stats.get("nodes").rows();
        if (out != in) throw new IndexBuildException("verification failed: out holds " + out + " rows but in holds " + in);
        if (nodes <= 0) throw new IndexBuildException("verification failed: the node table is empty");
        if (token != null && token.isCancelled()) throw new CancelledException();
        List<Path> files = parquetFiles(root.resolve("nodes"), Integer.MAX_VALUE);
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT sum(out_edges), sum(in_edges) FROM read_parquet(" + fileList(files) + ")")) {
            rs.next();
            if (rs.getLong(1) != out || rs.getLong(2) != in)
                throw new IndexBuildException("verification failed: node degrees sum to " + rs.getLong(1) + " out / " + rs.getLong(2) + " in, edge rows are " + out + " / " + in);
        }
    }

    private static String fileList(List<Path> files) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) sb.append(i == 0 ? "'" : ", '").append(sqlPath(files.get(i))).append('\'');
        return sb.append(']').toString();
    }

    /** Forward slashes (valid on Windows too) and single quotes doubled, for use inside a SQL string literal. */
    private static String sqlPath(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    private static void deleteSpill(Path spill) throws IOException {
        if (!Files.exists(spill)) return;
        try (Stream<Path> w = Files.walk(spill)) {
            for (Path x : (Iterable<Path>) w.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(x);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long ms(long fromNanos) { return (System.nanoTime() - fromNanos) / 1_000_000L; }
}
