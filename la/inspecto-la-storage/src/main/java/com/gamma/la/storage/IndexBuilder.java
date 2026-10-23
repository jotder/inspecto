package com.gamma.la.storage;

import com.gamma.la.storage.IndexManifest.TableStats;

import com.gamma.sql.SqlViews;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
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
import java.util.TreeSet;
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
 * unmapped. The bucket is {@link BucketFunction}. {@code nodes} folds {@code *_links} as distinct (neighbour, kind); it is folded one
 * bucket at a time from the written {@code out} and {@code in}, so its memory is that of a bucket, not of the node count.
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
     * @param sizing      the bucket sizing a FULL build applies and records (an append or compact keeps its parent's); null =
     *                    {@link BucketFunction.Sizing#DEFAULT}
     */
    public record Options(Integer buckets, String memoryLimit, Integer threads, CancelToken cancel, Consumer<Progress> progress,
                          BucketFunction.Sizing sizing) {
        public Options {
            if (sizing == null) sizing = BucketFunction.Sizing.DEFAULT;
        }

        /** The shipped bucket sizing. */
        public Options(Integer buckets, String memoryLimit, Integer threads, CancelToken cancel, Consumer<Progress> progress) {
            this(buckets, memoryLimit, threads, cancel, progress, null);
        }

        public static Options defaults() { return new Options(null, null, null, null, null); }
    }

    /**
     * An endpoint column's Entity Type: its id, its normaliser and {@code keySql}, the TRUSTED SQL of the normalised key of a
     * VARCHAR column expression (the caller renders it from the type's normaliser; the builder applies it to {@code src} /
     * {@code dst} of the written edges).
     */
    public record EndpointKey(String type, String normaliser, java.util.function.UnaryOperator<String> keySql) {
        public EndpointKey {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(keySql, "keySql");
        }

        IndexManifest.EndpointType asManifest() {
            return new IndexManifest.EndpointType(type, normaliser);
        }
    }

    /** What a build does: FULL rebuilds from the relation; APPEND adds a delta of new files to the live version; COMPACT merges deltas into one sorted main. */
    public enum Mode { FULL, APPEND, COMPACT }

    /**
     * @param datasetId       recorded in the manifest (the store's directory name is derived from it separately)
     * @param relationSql     TRUSTED relation SQL (a SELECT) - see the class doc
     * @param baseFingerprint the caller's fingerprint of the base data; the builder does not list files
     * @param inputFiles      the caller's listing of those files, recorded in the manifest as given; null = not recorded
     * @param mode            what to do; APPEND and COMPACT build on the store's CURRENT version
     * @param deltaSql        APPEND only: the relation SQL over just the given (added) input files, null = the Dataset cannot be
     *                        appended to (its relation is not row-wise over its files)
     * @param readRoots       the directories the relation may read; the connection is sealed to them plus the store directory
     * @param maxDeltas       APPEND only: the Space's {@code index.max_deltas}, the gate {@link IndexPlan#classify} re-checks
     * @param srcType         the Entity Type the source column is typed as, null = untyped: the manifest's link statistics key
     *                        the column's values by it
     * @param dstType         the Entity Type the target column is typed as, null = untyped
     */
    public record Request(String datasetId, IndexMapping mapping, String relationSql, IndexStore store, String baseFingerprint,
                          Options options, List<IndexManifest.InputFile> inputFiles, Mode mode,
                          java.util.function.Function<List<String>, String> deltaSql, List<Path> readRoots, int maxDeltas,
                          EndpointKey srcType, EndpointKey dstType) {
        /** Endpoint columns not typed as an Entity Type. */
        public Request(String datasetId, IndexMapping mapping, String relationSql, IndexStore store, String baseFingerprint,
                       Options options, List<IndexManifest.InputFile> inputFiles, Mode mode,
                       java.util.function.Function<List<String>, String> deltaSql, List<Path> readRoots, int maxDeltas) {
            this(datasetId, mapping, relationSql, store, baseFingerprint, options, inputFiles, mode, deltaSql, readRoots, maxDeltas,
                    null, null);
        }

        /** Under the default delta cap ({@link IndexPlan#DEFAULT_MAX_DELTAS}). */
        public Request(String datasetId, IndexMapping mapping, String relationSql, IndexStore store, String baseFingerprint,
                       Options options, List<IndexManifest.InputFile> inputFiles, Mode mode,
                       java.util.function.Function<List<String>, String> deltaSql, List<Path> readRoots) {
            this(datasetId, mapping, relationSql, store, baseFingerprint, options, inputFiles, mode, deltaSql, readRoots,
                    IndexPlan.DEFAULT_MAX_DELTAS);
        }

        public Request(String datasetId, IndexMapping mapping, String relationSql, IndexStore store, String baseFingerprint,
                       Options options, List<IndexManifest.InputFile> inputFiles) {
            this(datasetId, mapping, relationSql, store, baseFingerprint, options, inputFiles, Mode.FULL, null, null);
        }

        public Request(String datasetId, IndexMapping mapping, String relationSql, IndexStore store, String baseFingerprint,
                       Options options) {
            this(datasetId, mapping, relationSql, store, baseFingerprint, options, null);
        }


        public Request {
            readRoots = readRoots == null ? List.of() : List.copyOf(readRoots);
            Objects.requireNonNull(datasetId, "datasetId");
            Objects.requireNonNull(mapping, "mapping");
            Objects.requireNonNull(relationSql, "relationSql");
            Objects.requireNonNull(store, "store");
            if (options == null) options = Options.defaults();
            if (mode == null) mode = Mode.FULL;
        }
    }

    /** @param rowsInRelation rows of the relation; {@code edges} = indexed edges (per direction) = rows - droppedNull */
    public record Result(long version, Path directory, IndexManifest manifest, long rowsInRelation, long edges, long droppedNull,
                         long nodes, int buckets, Map<String, Long> timingsMs, long totalMs) { }

    public static class IndexBuildException extends RuntimeException {
        public IndexBuildException(String message) { super(message); }

        public IndexBuildException(String message, Throwable cause) { super(message, cause); }
    }

    /** An APPEND or COMPACT that is not sound or not possible now; the message says why and that a FULL rebuild is the way forward. */
    public static final class NotApplicableException extends IndexBuildException {
        public NotApplicableException(String message) { super(message); }
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
            return switch (req.mode()) {
                case FULL -> run.execute(t0);
                case APPEND -> run.executeAppend(t0);
                case COMPACT -> run.executeCompact(t0);
            };
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

    /**
     * The number of rows of {@code relationSql} (a trusted SELECT), on a short-lived connection of its own - the disk-budget
     * estimate of {@link IndexBuildService} needs it BEFORE a build is queued.
     */
    public static long countRows(String relationSql, List<Path> readRoots) {
        return countRows(relationSql, 0L, readRoots);
    }

    private static final ScheduledExecutorService ESTIMATE_TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "la-index-estimate-timer");
        t.setDaemon(true);
        return t;
    });

    /** The count ran past its statement timeout and was cancelled; nothing about the relation is known. */
    public static final class EstimateTimeoutException extends IndexBuildException {
        public EstimateTimeoutException(long timeoutMs) {
            super("counting the relation's rows did not finish within " + timeoutMs + " ms");
        }
    }

    /**
     * {@link #countRows(String)} bounded by a statement timeout: the running statement is cancelled from a timer after
     * {@code timeoutMs} (0 = unbounded) and {@link EstimateTimeoutException} is thrown.
     */
    public static long countRows(String relationSql, long timeoutMs, List<Path> readRoots) {
        String inner = relationSql.strip().replaceAll(";+\\s*$", "");
        java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        try (Connection c = open(null, null, null, readRoots); Statement s = c.createStatement()) {
            java.util.concurrent.ScheduledFuture<?> guard = timeoutMs <= 0 ? null : ESTIMATE_TIMER.schedule(() -> {
                timedOut.set(true);
                try {
                    s.cancel();
                } catch (SQLException ignored) {
                    // finished in the same instant
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM (" + inner + ") __la_count")) {
                rs.next();
                return rs.getLong(1);
            } finally {
                if (guard != null) guard.cancel(false);
            }
        } catch (SQLException e) {
            if (timedOut.get()) throw new EstimateTimeoutException(timeoutMs);
            throw new IndexBuildException("cannot count the rows of the relation: " + e.getMessage(), e);
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
        try (Connection c = open(null, null, null, List.of(versionDir))) {
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

    /**
     * The distinct nodes a PUBLISHED version holds. Its {@code nodes} table is folded per build, so after an APPEND it holds the
     * parent's rows plus the delta's, and an entity in both is two rows: {@link TableStats#rows()} then over-counts (LA-ONBOARD-DEFECTS-1).
     * A version with no delta needs no query: its rows are already distinct.
     */
    public static long distinctNodes(Path versionDir, IndexManifest man) {
        TableStats nodes = man.tables().get("nodes");
        if (man.deltas().isEmpty()) return nodes == null ? 0L : nodes.rows();
        List<Path> files = parquetFiles(versionDir.resolve("nodes"), Integer.MAX_VALUE);
        if (files.isEmpty()) return 0L;
        try (Connection c = open(null, null, null, List.of(versionDir));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(DISTINCT id) FROM " + SqlViews.parquet(fileList(files), false, SqlViews.Hive.OFF))) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IndexBuildException("cannot count the nodes of " + versionDir.getFileName() + ": " + e.getMessage(), e);
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

        /** The relation's columns and the quoted mapped endpoint columns, once the relation and edge views exist. */
        record Edges(String srcQ, String dstQ, Map<String, String> types) { }

        /** Defines the temp views over {@code relationSql}: the relation, then the edges (ids cast to VARCHAR, NULL endpoints dropped). */
        Edges defineEdges(String relationSql) throws SQLException {
            IndexMapping m = req.mapping();
            exec("CREATE TEMP VIEW " + REL + " AS " + relationSql.strip().replaceAll(";+\\s*$", ""));
            Map<String, String> types = columnTypes();
            String ts = timeExpr(m, types);
            String srcQ = column(m.srcColumn(), types), dstQ = column(m.dstColumn(), types);
            exec("CREATE TEMP VIEW " + EDGES + " AS SELECT CAST(" + srcQ + " AS VARCHAR) AS src, CAST(" + dstQ + " AS VARCHAR) AS dst, "
                    + (m.kindColumn() == null ? "CAST(NULL AS VARCHAR)" : "CAST(" + column(m.kindColumn(), types) + " AS VARCHAR)") + " AS kind, "
                    + ts + " AS ts, "
                    + (m.weightColumn() == null ? "CAST(NULL AS DOUBLE)" : "CAST(" + column(m.weightColumn(), types) + " AS DOUBLE)") + " AS w"
                    + attrSelect(m, types)
                    + " FROM " + REL + " WHERE " + srcQ + " IS NOT NULL AND " + dstQ + " IS NOT NULL");
            return new Edges(srcQ, dstQ, types);
        }

        /** {@code {rows in the relation, rows with both endpoints}} of the relation view. */
        long[] countEdges(Edges ed) throws SQLException {
            Object[] counts = rows("SELECT count(*), count(*) FILTER (WHERE " + ed.srcQ() + " IS NOT NULL AND " + ed.dstQ() + " IS NOT NULL) FROM " + REL).get(0);
            return new long[] {((Number) counts[0]).longValue(), ((Number) counts[1]).longValue()};
        }

        /**
         * COPYs out and in of the EDGES view into {@code root}, sorted and bucketed, then folds nodes from those two tables
         * ({@link #copyNodes}); {@code step} is the first progress step.
         */
        void copyTables(Path root, int n, int step) throws SQLException, IOException {
            String edgeCols = "src, dst, kind, ts, w" + attrAliases(req.mapping());
            long t;
            for (String[] d : new String[][] {{"out", "src"}, {"in", "dst"}}) {
                t = System.nanoTime();
                progress(d[0], step++);
                exec("COPY (SELECT " + BucketFunction.sql(d[1], n) + " AS bucket, " + edgeCols + " FROM " + EDGES
                        + " ORDER BY bucket, " + d[1] + ", ts) TO " + SqlIdent.pathStr(root.resolve(d[0])) + " " + COPY_OPTIONS);
                timings.put(d[0], ms(t));
            }
            t = System.nanoTime();
            progress("nodes", step);
            copyNodes(root);
            timings.put("nodes", ms(t));
        }

        /**
         * The node table of the edges {@code root} holds, ONE BUCKET AT A TIME, read from {@code root}'s own {@code out} and
         * {@code in} (so it is the fold of exactly the rows just written, and the relation is not read again).
         *
         * <p>The earlier statement folded the whole EDGES view in one pass - two global {@code GROUP BY}s with
         * {@code count(DISTINCT (neighbour, kind))}, a {@code FULL JOIN} of them and a global {@code ORDER BY} - whose working set
         * grows with the number of distinct (node, neighbour, kind) links, that is with the edge count, and not with the node
         * count. It spilled but did not stay inside its cap: 2x10^8 and 3x10^8 edges died with DuckDB's {@code Out of Memory
         * Error} at 6 GB and 5x10^8 at 12 GB (LA-INDEX-SCALE-MEASURE-1). Here the working set is the edges of ONE bucket (the
         * sizing aims at {@link BucketFunction#EDGES_PER_BUCKET}), independent of the total.
         *
         * <p>Same rows as before: a node's out and in edges share its bucket (the bucket is a function of the id alone), so a
         * bucket's nodes are the {@code FULL JOIN} of its {@code out} and {@code in} folds. One {@code data_0.parquet} per
         * non-empty bucket, sorted by id, same row-group size and codec; a bucket with no edge on either side has no directory.
         */
        void copyNodes(Path root) throws SQLException, IOException {
            TreeSet<Integer> buckets = new TreeSet<>(bucketDirs(root.resolve("out")));
            buckets.addAll(bucketDirs(root.resolve("in")));
            for (int b : buckets) {
                Path file = Files.createDirectories(root.resolve("nodes").resolve("bucket=" + b)).resolve("data_0.parquet");
                exec("COPY (SELECT id, coalesce(o.oe, 0) AS out_edges, coalesce(i.ie, 0) AS in_edges, coalesce(o.ol, 0) AS out_links,"
                        + " coalesce(i.il, 0) AS in_links, least(o.f, i.f) AS first_ts, greatest(o.l, i.l) AS last_ts FROM ("
                        + nodeSide(root.resolve("out"), b, "src", "dst", "oe", "ol") + ") o FULL JOIN ("
                        + nodeSide(root.resolve("in"), b, "dst", "src", "ie", "il") + ") i USING (id) ORDER BY id) TO "
                        + SqlIdent.pathStr(file) + " " + NODES_COPY_OPTIONS);
            }
        }

        /**
         * One side of a bucket's nodes: per {@code key} the edge count, the distinct (neighbour, kind) count and the time span of
         * {@code table}'s bucket {@code b}; an empty relation of the same columns when that side has no edge in the bucket.
         */
        static String nodeSide(Path table, int b, String key, String neighbour, String edges, String links) {
            Path dir = table.resolve("bucket=" + b);
            if (!Files.isDirectory(dir))
                return "SELECT CAST(NULL AS VARCHAR) AS id, CAST(NULL AS BIGINT) AS " + edges + ", CAST(NULL AS BIGINT) AS " + links
                        + ", CAST(NULL AS TIMESTAMP) AS f, CAST(NULL AS TIMESTAMP) AS l WHERE false";
            return "SELECT " + key + " AS id, count(*) AS " + edges + ", count(DISTINCT struct_pack(n := " + neighbour + ", k := kind)) AS " + links
                    + ", min(ts) AS f, max(ts) AS l FROM " + SqlViews.parquet(SqlIdent.pathStr(dir, "/*.parquet"), false, SqlViews.Hive.OFF)
                    + " GROUP BY " + key;
        }

        /** What the sealed connection may touch: the relation's declared roots plus the store directory (stage, .spill, parent version, .delta). */
        List<Path> sealDirs() {
            List<Path> d = new ArrayList<>(req.readRoots());
            d.add(req.store().directory());
            return d;
        }

        /**
         * What a FULL build reads: the relation PINNED to the input listing the caller recorded, when the Dataset can render the
         * relation over a file list ({@code deltaSql}). The relation itself is usually a glob that every statement of the build
         * re-expands, so a file that lands while a long FULL runs is read by some statements and not others (the verification then
         * fails the build) or by all of them while the manifest, which records the listing taken at submit, does not list it (the
         * next plan appends it again and counts its rows twice). Pinned, the index holds exactly the files the manifest lists.
         * The manifest's relation hash stays that of {@link Request#relationSql()}. No listing, no list renderer, or a path the
         * renderer refuses: the relation as given.
         */
        String fullReadSql() {
            if (req.deltaSql() == null || req.inputFiles() == null || req.inputFiles().isEmpty()) return req.relationSql();
            try {
                String pinned = req.deltaSql().apply(req.inputFiles().stream().map(IndexManifest.InputFile::path).toList());
                return pinned == null ? req.relationSql() : pinned;
            } catch (IllegalArgumentException notAnInput) {
                return req.relationSql();
            }
        }

        Result execute(long t0) throws Exception {
            IndexMapping m = req.mapping();
            Path spill = stage.resolve(".spill");
            conn = open(opt.memoryLimit(), opt.threads(), spill, sealDirs());

            // -- estimate -------------------------------------------------------------------------------------
            long t = System.nanoTime();
            progress("estimate", 1);
            Edges ed = defineEdges(fullReadSql());
            Map<String, String> types = ed.types();
            long[] counts = countEdges(ed);
            long total = counts[0];
            long kept = counts[1];
            long dropped = total - kept;
            if (kept == 0) throw new IndexBuildException("the relation has no edge with both endpoints set (" + total + " rows, " + dropped + " with a NULL endpoint)");
            int n = opt.buckets() != null ? opt.buckets() : opt.sizing().bucketsFor(total);
            timings.put("estimate", ms(t));

            // -- out / in / nodes ---------------------------------------------------------------------------------
            copyTables(stage, n, 2);

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
                    ROW_GROUP_SIZE, m, m.hash(), req.datasetId(), relationSqlHash(req.relationSql()), req.baseFingerprint(), zone, stats, dropped, null, null, req.inputFiles(),
                    opt.sizing().signature());
            man = linkStats(man);
            return publish(man, spill, number, total, kept, dropped, stats, n, t0);
        }

        /**
         * {@code man} with its link statistics (see {@link IndexManifest}), read from the tables this build just wrote into the
         * stage - never the relation again. The edges of {@code out} are keyed by their endpoint types ({@link EndpointKey#keySql},
         * the raw id for an untyped end) and folded to distinct {@code (a, b, kind)} links without self-links, exactly as the
         * Entity profile's Dataset fallback folds them, so an index answer and a Dataset answer agree. Two temp tables (the links,
         * then each key's distinct-neighbour degree) feed the kind counts, the per-type key counts, the quantiles and the
         * histogram; {@code nodes} gives the time span.
         *
         * <p>Every mode RECOMPUTES them over the whole version (an APPEND included: distinct counts do not add up across a delta);
         * the cost is one more aggregation over {@code out}, of the size of the {@code nodes} step.
         */
        IndexManifest linkStats(IndexManifest man) throws SQLException {
            long t = System.nanoTime();
            IndexMapping m = req.mapping();
            EndpointKey st = req.srcType(), dt = req.dstType();
            exec("CREATE TEMP TABLE __la_links AS SELECT DISTINCT a, b, kind FROM (SELECT " + (st == null ? "src" : st.keySql().apply("src"))
                    + " AS a, " + (dt == null ? "dst" : dt.keySql().apply("dst")) + " AS b, kind FROM "
                    + SqlViews.parquet(SqlIdent.pathStr(stage.resolve("out"), "/*/*.parquet"), false, SqlViews.Hive.OFF) + ") l WHERE a <> '' AND b <> '' AND a <> b");
            // Distinct neighbours per key = distinct (key, neighbour) pairs counted per key (o is never NULL: a and b are non-empty above).
            // Spelled as DISTINCT then count(*) because the grouped count(DISTINCT o) ran out of memory at a 2 GB cap over 10^8 edges
            // (LA-INDEX-SCALE-MEASURE-1) where this form completed.
            exec("CREATE TEMP TABLE __la_degree AS SELECT x, count(*) AS d FROM (SELECT DISTINCT x, o FROM (SELECT a AS x, b AS o FROM __la_links"
                    + " UNION ALL SELECT b AS x, a AS o FROM __la_links) e) GROUP BY x");
            Map<String, Long> kinds = new LinkedHashMap<>();
            if (m.kindColumn() != null)
                for (Object[] r : rows("SELECT kind, count(*) AS n FROM __la_links WHERE kind IS NOT NULL GROUP BY kind ORDER BY n DESC, kind"))
                    kinds.put(r[0].toString(), ((Number) r[1]).longValue());
            Object[] q = rows("SELECT coalesce(quantile_disc(d, 0.5), 0), coalesce(quantile_disc(d, 0.9), 0), coalesce(quantile_disc(d, 0.99), 0),"
                    + " coalesce(max(d), 0), count(*), (SELECT count(DISTINCT a) FROM __la_links), (SELECT count(DISTINCT b) FROM __la_links)"
                    + " FROM __la_degree").get(0);
            Map<Long, Long> histogram = new LinkedHashMap<>();
            for (Object[] r : rows("SELECT d, count(*) FROM __la_degree GROUP BY d ORDER BY d"))
                histogram.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
            Object[] span = rows("SELECT CAST(min(first_ts) AS VARCHAR), CAST(max(last_ts) AS VARCHAR) FROM "
                    + SqlViews.parquet(SqlIdent.pathStr(stage.resolve("nodes"), "/*/*.parquet"), false, SqlViews.Hive.OFF)).get(0);
            exec("DROP TABLE __la_degree");
            exec("DROP TABLE __la_links");
            Map<String, Long> types = new LinkedHashMap<>();
            if (st != null && dt != null && st.type().equals(dt.type())) types.put(st.type(), num(q[4]));
            else {
                if (st != null) types.put(st.type(), num(q[5]));
                if (dt != null) types.put(dt.type(), num(q[6]));
            }
            timings.put("stats", ms(t));
            return man.withLinkStats(kinds, types, new IndexManifest.Degree(num(q[0]), num(q[1]), num(q[2]), num(q[3])),
                    m.timeColumn() == null || span[0] == null ? null : new IndexManifest.TimeSpan(instant(span[0].toString()), instant(span[1].toString())),
                    histogram, new IndexManifest.EndpointTypes(st == null ? null : st.asManifest(), dt == null ? null : dt.asManifest()));
        }

        /** Writes the manifest, releases the files, deletes the scratch directories and publishes the stage. */
        Result publish(IndexManifest man, Path spill, long number, long total, long kept, long dropped, Map<String, TableStats> stats,
                       int n, long t0) throws Exception {
            man.write(stage);
            check();
            close();   // release the files (Windows) and the spill directory before the stage is renamed
            deleteSpill(spill);
            deleteSpill(stage.resolve(".delta"));
            long t = System.nanoTime();
            if (opt.progress() != null) opt.progress().accept(new Progress("publish", STEPS, STEPS));
            Path published = req.store().publish(stage);
            stage = null;
            timings.put("publish", ms(t));
            return new Result(number, published, man, total, kept, dropped, stats.get("nodes").rows(), n, timings, ms(t0));
        }

        /** The live version and its manifest, for an APPEND or COMPACT; refused when there is none or it is another mapping's. */
        IndexManifest parentManifest(Path[] dirOut) throws IOException {
            Path dir = req.store().current().orElseThrow(() -> new NotApplicableException("the index has no published version to build on - run a full build first"));
            IndexManifest parent;
            try {
                parent = IndexManifest.read(dir);
            } catch (IllegalArgumentException e) {
                throw new NotApplicableException("the live version's manifest cannot be read - run a full build");
            }
            if (!parent.mappingHash().equals(req.mapping().hash()) || !parent.dataset().equals(req.datasetId()))
                throw new NotApplicableException("the live version belongs to another Dataset or mapping - run a full build");
            dirOut[0] = dir;
            return parent;
        }

        /**
         * APPEND (design 3.3): the new files only, as a delta sorted within itself, layered onto a hard-linked copy of the live
         * version, published as the NEXT immutable version. The gate is {@link IndexPlan#classify}; a version is complete before it
         * is named, exactly as for a full build, and a cancel or failure deletes the stage (the links, not the parent's files).
         */
        Result executeAppend(long t0) throws Exception {
            IndexMapping m = req.mapping();
            Path[] pd = new Path[1];
            IndexManifest parent = parentManifest(pd);
            Path parentDir = pd[0];
            String duck = duckdbVersion();
            IndexPlan.Plan plan = IndexPlan.classify(parent, req.inputFiles(), relationSqlHash(req.relationSql()), BucketFunction.NAME,
                    opt.sizing(), duck, req.maxDeltas());
            if (!plan.appendable())
                throw new NotApplicableException("an append is not possible now (" + (plan.reasons().isEmpty() ? "no input file was added" : String.join(", ", plan.reasons()))
                        + ") - " + (plan.recommended() == IndexPlan.Action.COMPACT ? "compact first" : "run a full build"));
            String deltaSql = req.deltaSql() == null ? null : req.deltaSql().apply(plan.added());
            if (deltaSql == null)
                throw new NotApplicableException("this Dataset's relation is not row-wise over its input files, so its index cannot be appended to - run a full build");
            Path spill = stage.resolve(".spill");
            conn = open(opt.memoryLimit(), opt.threads(), spill, sealDirs());

            long t = System.nanoTime();
            progress("estimate", 1);
            Edges ed = defineEdges(deltaSql);
            String zone = effectiveZone(m, ed.types());
            if (!Objects.equals(zone, parent.timeColZone()))
                throw new NotApplicableException("the new files read the time column differently from the index (zone " + zone + ", index " + parent.timeColZone() + ") - run a full build");
            long[] counts = countEdges(ed);
            long total = counts[0], kept = counts[1], dropped = total - kept;
            int n = parent.buckets();
            timings.put("estimate", ms(t));

            String prefix = String.format("d%03d", parent.deltas().size() + 1);
            long deltaBytes = 0;
            if (kept > 0) {
                Path delta = Files.createDirectories(stage.resolve(".delta"));
                copyTables(delta, n, 2);
                for (String table : new String[] {"out", "in", "nodes"}) linkTree(parentDir.resolve(table), stage.resolve(table));
                deltaBytes = mergeDelta(delta, prefix);
            } else {
                for (String table : new String[] {"out", "in", "nodes"}) linkTree(parentDir.resolve(table), stage.resolve(table));
            }

            t = System.nanoTime();
            progress("verify", 6);
            Map<String, TableStats> stats = scan(conn, stage, n, token);
            long wantEdges = parent.tables().get("out").rows() + kept;
            if (stats.get("out").rows() != wantEdges || stats.get("in").rows() != wantEdges)
                throw new IndexBuildException("verification failed: the index and the delta hold " + wantEdges + " edges, but out holds "
                        + stats.get("out").rows() + " rows and in holds " + stats.get("in").rows());
            invariants(conn, stage, stats, token);
            timings.put("verify", ms(t));

            long number = Long.parseLong(stage.getFileName().toString().replaceAll("\\D", ""));
            List<IndexManifest.Delta> deltas = new ArrayList<>(parent.deltas());
            deltas.add(new IndexManifest.Delta(prefix, kept, deltaBytes));
            IndexManifest man = new IndexManifest(number, Instant.now().toString(), IndexManifest.Builder.APPEND, duck, BucketFunction.NAME, n,
                    ROW_GROUP_SIZE, m, m.hash(), req.datasetId(), parent.relationSqlHash(), req.baseFingerprint(), parent.timeColZone(), stats,
                    parent.droppedNull() + dropped, deltas, parentDir.getFileName().toString(), req.inputFiles(), parent.bucketSizing());
            man = linkStats(man);
            return publish(man, spill, number, total, kept, dropped, stats, n, t0);
        }

        /**
         * COMPACT (design 3.3): merges the live version's main and deltas into ONE sorted main, reading the INDEX's own files (never
         * the Dataset), so it neither needs nor changes what the index covers - the fingerprint, file list and null count carry over,
         * and the version is published as the next one. Nodes are recomputed, which also folds the per-delta node rows.
         */
        Result executeCompact(long t0) throws Exception {
            IndexMapping m = req.mapping();
            Path[] pd = new Path[1];
            IndexManifest parent = parentManifest(pd);
            Path parentDir = pd[0];
            if (parent.deltas().isEmpty()) throw new NotApplicableException("the live version has no deltas to compact");
            Path spill = stage.resolve(".spill");
            conn = open(opt.memoryLimit(), opt.threads(), spill, sealDirs());

            long t = System.nanoTime();
            progress("estimate", 1);
            exec("CREATE TEMP VIEW " + EDGES + " AS SELECT src, dst, kind, ts, w" + attrAliases(m) + " FROM "
                    + SqlViews.parquet(SqlIdent.pathStr(parentDir, "/out/*/*.parquet"), false, SqlViews.Hive.TYPED));
            long kept = ((Number) rows("SELECT count(*) FROM " + EDGES).get(0)[0]).longValue();
            if (kept != parent.tables().get("out").rows())
                throw new IndexBuildException("the live version's out table holds " + kept + " rows, its manifest says " + parent.tables().get("out").rows());
            int n = parent.buckets();
            timings.put("estimate", ms(t));

            copyTables(stage, n, 2);

            t = System.nanoTime();
            progress("verify", 6);
            Map<String, TableStats> stats = scan(conn, stage, n, token);
            if (stats.get("out").rows() != kept || stats.get("in").rows() != kept)
                throw new IndexBuildException("verification failed: compaction read " + kept + " edges, but out holds "
                        + stats.get("out").rows() + " rows and in holds " + stats.get("in").rows());
            invariants(conn, stage, stats, token);
            timings.put("verify", ms(t));
            String duck = rows("SELECT version()").get(0)[0].toString();

            long number = Long.parseLong(stage.getFileName().toString().replaceAll("\\D", ""));
            IndexManifest man = new IndexManifest(number, Instant.now().toString(), IndexManifest.Builder.COMPACT, duck, BucketFunction.NAME, n,
                    ROW_GROUP_SIZE, m, m.hash(), req.datasetId(), parent.relationSqlHash(), parent.baseFingerprint(), parent.timeColZone(), stats,
                    parent.droppedNull(), null, parentDir.getFileName().toString(), parent.inputFiles(), parent.bucketSizing());
            man = linkStats(man);
            return publish(man, spill, number, kept, kept, 0, stats, n, t0);
        }

        /** Moves the delta's bucket files into the stage's bucket directories as {@code <prefix>-<n>.parquet}; returns their bytes. */
        long mergeDelta(Path delta, String prefix) throws IOException {
            long bytes = 0;
            for (String table : new String[] {"out", "in", "nodes"}) {
                Path from = delta.resolve(table);
                if (!Files.isDirectory(from)) continue;
                try (Stream<Path> dirs = Files.list(from)) {
                    for (Path d : (Iterable<Path>) dirs.sorted()::iterator) {
                        Path to = stage.resolve(table).resolve(d.getFileName().toString());
                        Files.createDirectories(to);
                        int i = 0;
                        try (Stream<Path> fs = Files.list(d)) {
                            for (Path f : (Iterable<Path>) fs.filter(x -> x.getFileName().toString().endsWith(".parquet")).sorted()::iterator) {
                                bytes += Files.size(f);
                                Files.move(f, to.resolve(prefix + "-" + i++ + ".parquet"));
                            }
                        }
                    }
                }
            }
            return bytes;
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
            return SqlIdent.q(name);
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
    /** One bucket's node file: the options of {@link #COPY_OPTIONS} without the partitioning (the bucket directory is made by the caller). */
    private static final String NODES_COPY_OPTIONS = "(FORMAT parquet, ROW_GROUP_SIZE " + ROW_GROUP_SIZE + ", COMPRESSION zstd)";

    private static Connection open(String memoryLimit, Integer threads, Path spill, List<Path> dirs) throws SQLException {
        // DuckDbUtil.openInMemory caps memory and points the spill at a real directory (a raw in-memory open spills to .tmp
        // in the process CWD, outside the Space - see NoRawInMemoryDuckDbOpenContractTest); the caller-given limits then override it.
        // Sealed to the relation's declared read roots plus the store directory (ENGINE-INMEMORY-UNSEALED-1).
        Connection c = DuckDbUtil.openInMemory(spill, dirs);
        try (Statement s = c.createStatement()) {
            s.execute("SET TimeZone = 'UTC'");   // never the host zone
            if (memoryLimit != null) s.execute("SET memory_limit = '" + memoryLimit + "'");
            if (threads != null) s.execute("SET threads = " + threads);
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

    /** The bucket numbers of the {@code bucket=<n>} directories of a table directory (none when it does not exist). */
    private static List<Integer> bucketDirs(Path tableDir) throws IOException {
        List<Integer> ids = new ArrayList<>();
        if (!Files.isDirectory(tableDir)) return ids;
        try (Stream<Path> dirs = Files.list(tableDir)) {
            for (Path d : (Iterable<Path>) dirs::iterator) {
                String name = d.getFileName().toString();
                try {
                    if (!name.startsWith("bucket=")) throw new NumberFormatException();
                    ids.add(Integer.parseInt(name.substring("bucket=".length())));
                } catch (NumberFormatException e) {
                    throw new IndexBuildException("unexpected entry '" + name + "' in " + tableDir.getFileName() + " (expected bucket=<n>)");
                }
            }
        }
        return ids;
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
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT sum(out_edges), sum(in_edges) FROM " + SqlViews.parquet(fileList(files), false, SqlViews.Hive.AUTO))) {
            rs.next();
            if (rs.getLong(1) != out || rs.getLong(2) != in)
                throw new IndexBuildException("verification failed: node degrees sum to " + rs.getLong(1) + " out / " + rs.getLong(2) + " in, edge rows are " + out + " / " + in);
        }
    }

    /** A non-empty file list as a SQL list literal (absolute, forward-slashed paths). */
    private static String fileList(List<Path> files) {
        return SqlViews.pathList(files.stream().map(f -> f.toAbsolutePath().toString()).toList());
    }

    /** A copy of the tree {@code from} at {@code to} made of hard links (the files are immutable), falling back to a byte copy where links are not available. */
    private static void linkTree(Path from, Path to) throws IOException {
        try (Stream<Path> w = Files.walk(from)) {
            for (Path p : (Iterable<Path>) w::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    try {
                        Files.createLink(t, p);
                    } catch (IOException | UnsupportedOperationException | SecurityException noLinks) {
                        Files.copy(p, t);
                    }
                }
            }
        }
    }

    private static void deleteSpill(Path spill) throws IOException {
        if (!Files.exists(spill)) return;
        try (Stream<Path> w = Files.walk(spill)) {
            for (Path x : (Iterable<Path>) w.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(x);
        }
    }

    /** The pinned file list a store read renders ({@code read_parquet(['a', 'b'], ...)}): the Dataset's INPUT, tracked by the input fingerprint, not part of its definition. */
    private static final java.util.regex.Pattern PINNED_FILE_LIST = java.util.regex.Pattern.compile(
            "(read_(?:parquet|csv)\\(\\s*)\\[\\s*'(?:[^']++|'')*+'(?:\\s*,\\s*'(?:[^']++|'')*+')*+\\s*\\]");

    /**
     * What the manifest records as {@code relationSqlHash}: the SHA-256 of the relation SQL text with the pinned file list of
     * a store read blanked out - the DEFINITION of the Dataset. An added or removed input file changes the list but not the
     * definition (the input fingerprint tracks the files), so adding a file does not read as 'the relation SQL changed'.
     * Only a list directly inside {@code read_parquet(} / {@code read_csv(} is blanked: a literal list elsewhere in the SQL
     * (a filter) is part of the definition.
     */
    public static String relationSqlHash(String relationSql) {
        return sha256(PINNED_FILE_LIST.matcher(relationSql).replaceAll("$1[<pinned files>]"));
    }

    private static volatile String duckdbVersion;

    /** The DuckDB version this JVM runs (what a build would record in its manifest); read once, on a short-lived connection. */
    public static String duckdbVersion() {
        String v = duckdbVersion;
        if (v == null) {
            try (Connection c = open(null, null, null, List.of()); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT version()")) {
                rs.next();
                v = rs.getString(1);
            } catch (SQLException e) {
                throw new IndexBuildException("cannot read the DuckDB version: " + e.getMessage(), e);
            }
            duckdbVersion = v;
        }
        return v;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** DuckDB's text of a naive UTC TIMESTAMP ({@code 2026-10-09 10:36:47[.123]}) as ISO-8601 instant text. */
    private static String instant(String naiveUtc) {
        return naiveUtc.replace(' ', 'T') + "Z";
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static long ms(long fromNanos) { return (System.nanoTime() - fromNanos) / 1_000_000L; }
}
