package com.gamma.la.storage;

import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.sql.SqlViews;
import com.gamma.util.SqlIdent;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads ONE pinned index version for ONE request (D-3 step 5, design 4.1): a {@link SqlSandbox} connection with views over
 * the version's {@code out} and {@code in} copies, SEALED with {@code allowed_directories} limited to that version directory,
 * so the connection can read the index and nothing else (not another version, not the Dataset, not a URL).
 *
 * <p><b>The statement shape is fixed by the spike (design 5.1):</b> only an equality on ONE key reaches the scan as a zone-map
 * filter, and a multi-key frontier is best served by a {@code UNION ALL} of per-key equality statements. Each key's bucket is
 * computed IN JAVA ({@link BucketFunction#bucketOf}) and written as a literal; the key itself is a BOUND parameter. Every
 * statement carries the policy's timeout ({@link SqlSandbox#preparedStatement}).
 *
 * <p>Not thread-safe; open one per request and close it.
 */
public final class IndexReader implements AutoCloseable {

    /** Which copy a key is looked up in: {@code OUT} = edges whose source is the key, {@code IN} = edges whose target is the key. */
    public enum Side { OUT, IN }

    /**
     * One edge seen from a key: {@code neighbour} is the other endpoint; {@code tsMicros} is the naive-UTC instant as
     * microseconds since the epoch (null when the edge has no time); {@code weight} is null when unmapped or NULL.
     */
    public record Edge(String key, String neighbour, Long tsMicros, Double weight) { }

    private final SqlSandbox sandbox;
    private final int buckets;
    /** The version has a {@code nodes} table (every build writes one) and whether delta files sit beside its main ones. */
    private final boolean hasNodes;
    private final boolean deltas;
    /** LA-INDEX-PAIRS-LOOPS-1: the derived tables hold at least one file (a table with no rows is an empty directory). */
    private final boolean hasPairsBack;
    private final boolean hasLoops;
    /** Non-null for a reader lent by {@link #borrow}: {@link #close()} then returns it to the pool instead of closing it. */
    private Path pooledKey;

    private IndexReader(SqlSandbox sandbox, int buckets, boolean hasNodes, boolean deltas, boolean hasPairsBack, boolean hasLoops) {
        this.sandbox = sandbox;
        this.buckets = buckets;
        this.hasNodes = hasNodes;
        this.deltas = deltas;
        this.hasPairsBack = hasPairsBack;
        this.hasLoops = hasLoops;
    }

    /** True when {@code tableDir} holds a parquet file in one of its bucket directories (a view over none fails to bind). */
    private static boolean hasFiles(Path tableDir) throws IOException {
        if (!java.nio.file.Files.isDirectory(tableDir)) return false;
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(tableDir, 2)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".parquet"));
        }
    }

    // ── the pool: idle sealed readers per version directory (LA-INDEX-READER-POOL-1) ─────────────────────────────

    private static final int MAX_IDLE_PER_VERSION = 4;
    private static final Map<Path, java.util.ArrayDeque<IndexReader>> IDLE = new LinkedHashMap<>();
    /** How many sealed connections were really opened (the pool test counts reuse by this). */
    static final java.util.concurrent.atomic.AtomicLong OPENS = new java.util.concurrent.atomic.AtomicLong();

    /**
     * As {@link #open}, but reuses an idle sealed reader of the same version directory when there is one; {@link #close()}
     * returns it. Borrowing a version closes the idle readers of every SIBLING version (same parent directory): CURRENT
     * moved on, so the older version's handles are dead weight.
     */
    public static IndexReader borrow(Path versionDir, IndexManifest manifest, SqlSandboxPolicy policy) throws SQLException, IOException {
        Path key = versionDir.toAbsolutePath().normalize();
        List<IndexReader> evicted = new ArrayList<>();
        IndexReader idle = null;
        synchronized (IDLE) {
            Path parent = key.getParent();
            // D7-2: a pinned version's idle readers are spared (a Draft reads it), and borrowing a PINNED version evicts nothing
            // (it is not "the newer one"). An unreadable pin set spares everything.
            java.util.Set<Path> pinned = IndexPins.pinnedDirectories(parent, java.time.Clock.systemUTC());
            boolean keepAll = pinned == null || pinned.contains(key);
            IDLE.entrySet().removeIf(e -> {
                boolean sibling = !keepAll && !e.getKey().equals(key) && java.util.Objects.equals(e.getKey().getParent(), parent)
                        && !pinned.contains(e.getKey());
                if (sibling) evicted.addAll(e.getValue());
                return sibling;
            });
            java.util.ArrayDeque<IndexReader> q = IDLE.get(key);
            if (q != null) idle = q.pollFirst();
        }
        evicted.forEach(IndexReader::closeNow);
        if (idle == null) {
            idle = open(key, manifest, policy);
            OPENS.incrementAndGet();
        }
        idle.pooledKey = key;
        return idle;
    }

    /** Closes every idle pooled reader (service shutdown, tests). */
    public static void evictAll() {
        List<IndexReader> all = new ArrayList<>();
        synchronized (IDLE) {
            IDLE.values().forEach(all::addAll);
            IDLE.clear();
        }
        all.forEach(IndexReader::closeNow);
    }

    /** Idle readers currently pooled for {@code versionDir}. */
    static int idleCount(Path versionDir) {
        synchronized (IDLE) {
            java.util.ArrayDeque<IndexReader> q = IDLE.get(versionDir.toAbsolutePath().normalize());
            return q == null ? 0 : q.size();
        }
    }

    /**
     * Opens the views, then seals the connection to {@code versionDir}. A delta (D-3 step 8) lives as extra files inside the same
     * bucket directories, so the {@code out/*&#47;*.parquet} glob IS the union of the main and every delta: no read path needs to know.
     */
    public static IndexReader open(Path versionDir, IndexManifest manifest, SqlSandboxPolicy policy) throws SQLException, IOException {
        if (!BucketFunction.NAME.equals(manifest.bucketFn()))
            throw new IllegalArgumentException("index bucket function '" + manifest.bucketFn() + "' is not '" + BucketFunction.NAME + "'");
        boolean pairsBack = hasFiles(versionDir.resolve("pairs_back")), loops = hasFiles(versionDir.resolve("loops"));
        SqlSandbox sandbox = SqlSandbox.open(policy);
        try {
            try (Statement st = sandbox.connection().createStatement()) {
                for (String table : new String[] {"out", "in"}) {
                    String glob = SqlIdent.pathStr(versionDir.toAbsolutePath().normalize(), "/" + table + "/*/*.parquet");
                    st.execute("CREATE VIEW e_" + table + " AS SELECT * FROM " + SqlViews.parquet(glob, false, SqlViews.Hive.TYPED));
                }
                if (java.nio.file.Files.isDirectory(versionDir.resolve("nodes"))) {
                    String glob = SqlIdent.pathStr(versionDir.toAbsolutePath().normalize(), "/nodes/*/*.parquet");
                    st.execute("CREATE VIEW n_nodes AS SELECT * FROM " + SqlViews.parquet(glob, false, SqlViews.Hive.TYPED));
                }
                for (String table : new String[] {"pairs_back", "loops"})
                    if (table.equals("pairs_back") ? pairsBack : loops) {
                        String glob = SqlIdent.pathStr(versionDir.toAbsolutePath().normalize(), "/" + table + "/*/*.parquet");
                        st.execute("CREATE VIEW d_" + table + " AS SELECT * FROM " + SqlViews.parquet(glob, false, SqlViews.Hive.TYPED));
                    }
            }
            SqlSandbox.sealAllowing(sandbox.connection(), List.of(versionDir));
        } catch (SQLException | RuntimeException e) {
            sandbox.close();
            throw e;
        }
        return new IndexReader(sandbox, manifest.buckets(), java.nio.file.Files.isDirectory(versionDir.resolve("nodes")),
                manifest.deltas() != null && !manifest.deltas().isEmpty(), pairsBack, loops);
    }

    /**
     * The edges of every key, one equality statement per key and side, {@code UNION ALL}ed into one round trip. {@code filterSql}
     * is an already-rendered predicate over the index columns ({@code src, dst, kind, ts, a0..}) or null; each key yields at most
     * {@code perKeyLimit} rows per side. Keys with no edge are absent from the map.
     */
    public Map<String, List<Edge>> edges(List<String> keys, List<Side> sides, String filterSql, int perKeyLimit) throws SQLException {
        return edges(keys, sides, filterSql, List.of(), perKeyLimit);
    }

    /** As above; {@code filterBinds} are the filter's positional values, repeated in every per-key, per-side arm (the filter text is). */
    public Map<String, List<Edge>> edges(List<String> keys, List<Side> sides, String filterSql, List<String> filterBinds, int perKeyLimit) throws SQLException {
        if (perKeyLimit < 1) throw new IllegalArgumentException("perKeyLimit must be >= 1");
        Map<String, List<Edge>> out = new LinkedHashMap<>();
        if (keys.isEmpty() || sides.isEmpty()) return out;
        StringBuilder sql = new StringBuilder();
        List<String> binds = new ArrayList<>();
        for (String key : keys) {
            int bucket = BucketFunction.bucketOf(key, buckets);
            for (Side side : sides) {
                String own = side == Side.OUT ? "src" : "dst", other = side == Side.OUT ? "dst" : "src";
                if (!sql.isEmpty()) sql.append(" UNION ALL ");
                sql.append("(SELECT ").append(own).append(" AS k, ").append(other).append(" AS n, epoch_us(ts) AS tus, w FROM e_")
                        .append(side == Side.OUT ? "out" : "in").append(" WHERE bucket = ").append(bucket).append(" AND ").append(own)
                        .append(" = ?");
                if (filterSql != null) sql.append(" AND (").append(filterSql).append(')');
                sql.append(" LIMIT ").append(perKeyLimit).append(')');
                binds.add(key);
                if (filterSql != null) binds.addAll(filterBinds);
            }
        }
        try (PreparedStatement ps = sandbox.preparedStatement(sql.toString())) {
            for (int i = 0; i < binds.size(); i++) ps.setString(i + 1, binds.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long tus = rs.getLong(3);
                    Long ts = rs.wasNull() ? null : tus;
                    double w = rs.getDouble(4);
                    Double weight = rs.wasNull() ? null : w;
                    out.computeIfAbsent(rs.getString(1), x -> new ArrayList<>()).add(new Edge(rs.getString(1), rs.getString(2), ts, weight));
                }
            }
        }
        return out;
    }

    /**
     * One folded link seen from a key (D-3 step 6): the distinct {@code (source, target, extras...)} with the number of edge rows
     * that fold into it, as the flat {@code GROUP BY} reports it. {@code extras} are in the order asked for, null where the row's
     * value is NULL.
     */
    public record Folded(String source, String target, List<String> extras, long count, Double total) {
        /** A fold that sums no column. */
        public Folded(String source, String target, List<String> extras, long count) {
            this(source, target, extras, count, null);
        }
    }

    /**
     * The folded links of ONE key on ONE side: one equality statement on the key's bucket, {@code GROUP BY src, dst, extras}.
     * Every row of the key is folded (a count is only exact over all of them), so the cost is linear in the key's own degree,
     * never in the Dataset. {@code extraCols} are index columns ({@code kind}, {@code a0..}); {@code kinds}, when non-null,
     * keeps only edges whose kind is in the list (bound); {@code filterSql} is an already-rendered predicate or null.
     */
    public List<Folded> fold(String key, Side side, List<String> extraCols, List<String> kinds, String filterSql) throws SQLException {
        return fold(key, side, extraCols, kinds, filterSql, List.of());
    }

    /** As above; {@code filterBinds} are the filter's positional values, bound after the key and the kinds. */
    public List<Folded> fold(String key, Side side, List<String> extraCols, List<String> kinds, String filterSql, List<String> filterBinds) throws SQLException {
        return fold(key, side, extraCols, kinds, filterSql, filterBinds, false);
    }

    /**
     * As above; {@code weighted} folds the SUM of the mapped weight column instead of the row count - each row weighs
     * {@code CAST(w AS BIGINT)}, a NULL weight one row's worth - exactly as an Investigation bound to an {@code eventsCol}
     * weighs a pair ({@code COALESCE(CAST(eventsCol AS BIGINT), 1)}).
     */
    public List<Folded> fold(String key, Side side, List<String> extraCols, List<String> kinds, String filterSql, List<String> filterBinds,
                             boolean weighted) throws SQLException {
        return fold(key, side, extraCols, kinds, filterSql, filterBinds, weighted, null);
    }

    /**
     * As above; {@code totalCol}, when not null, is one more index column ({@code a0..}) whose numbers each folded link also
     * SUMs ({@link Folded#total}; a value that is no number counts nothing, a link with none is null) - LA-COLUMN-ROLES-2:
     * the primary Measure that weighs a link on the canvas.
     */
    public List<Folded> fold(String key, Side side, List<String> extraCols, List<String> kinds, String filterSql, List<String> filterBinds,
                             boolean weighted, String totalCol) throws SQLException {
        if (totalCol != null && !totalCol.matches("a\\d{1,3}")) throw new IllegalArgumentException("not an index attribute column: " + totalCol);
        for (String c : extraCols)
            if (!c.matches("kind|a\\d{1,3}")) throw new IllegalArgumentException("not an index column: " + c);
        if (kinds != null && kinds.isEmpty()) throw new IllegalArgumentException("kinds must not be empty");
        String own = side == Side.OUT ? "src" : "dst";
        StringBuilder sel = new StringBuilder("src, dst");
        for (String c : extraCols) sel.append(", ").append(c);
        StringBuilder sql = new StringBuilder("SELECT ").append(sel)
                .append(totalCol != null ? ", SUM(TRY_CAST(" + totalCol + " AS DOUBLE)) AS total" : "")
                .append(weighted ? ", CAST(SUM(COALESCE(CAST(w AS BIGINT), 1)) AS BIGINT) AS cnt FROM e_" : ", COUNT(*) AS cnt FROM e_")
                .append(side == Side.OUT ? "out" : "in").append(" WHERE bucket = ").append(BucketFunction.bucketOf(key, buckets))
                .append(" AND ").append(own).append(" = ?");
        List<String> binds = new ArrayList<>();
        binds.add(key);
        if (kinds != null) {
            sql.append(" AND kind IN (").append(String.join(",", java.util.Collections.nCopies(kinds.size(), "?"))).append(')');
            binds.addAll(kinds);
        }
        if (filterSql != null) {
            sql.append(" AND (").append(filterSql).append(')');
            binds.addAll(filterBinds);
        }
        sql.append(" GROUP BY ").append(sel);
        List<Folded> out = new ArrayList<>();
        try (PreparedStatement ps = sandbox.preparedStatement(sql.toString())) {
            for (int i = 0; i < binds.size(); i++) ps.setString(i + 1, binds.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> extras = new ArrayList<>(extraCols.size());
                    for (int i = 0; i < extraCols.size(); i++) extras.add(rs.getString(3 + i));
                    int at = 3 + extraCols.size();
                    Double total = null;
                    if (totalCol != null) {
                        double t = rs.getDouble(at++);
                        total = rs.wasNull() ? null : t;
                    }
                    out.add(new Folded(rs.getString(1), rs.getString(2), extras, rs.getLong(at), total));
                }
            }
        }
        return out;
    }

    /**
     * The distinct-counterparty degree of each key over both sides (a self-loop counts the key itself once), counting only
     * edges whose kind is in {@code kinds} (when non-null) and whose counterparty is not in {@code excluded}. One round trip:
     * an equality arm per key and side on the key's bucket, so the cost is linear in the keys' own rows. Keys with no such
     * edge are absent.
     */
    public Map<String, Long> distinctCounterparties(java.util.Collection<String> keys, List<String> kinds, java.util.Collection<String> excluded)
            throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        if (keys.isEmpty()) return out;
        if (kinds != null && kinds.isEmpty()) throw new IllegalArgumentException("kinds must not be empty");
        // Bounded statement: the keys and exclusions travel as one bind each; the key buckets (at most {@code buckets}) prune.
        List<String> binds = new ArrayList<>();
        java.util.TreeSet<Integer> bucketSet = new java.util.TreeSet<>();
        for (String key : keys) bucketSet.add(BucketFunction.bucketOf(key, buckets));
        String bucketList = bucketSet.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        String keyRel = idRelation(new java.util.LinkedHashSet<>(keys), binds);
        StringBuilder sql = new StringBuilder("WITH ks AS ").append(keyRel);
        if (!excluded.isEmpty()) sql.append(", xs AS ").append(idRelation(new java.util.LinkedHashSet<>(excluded), binds));
        sql.append(" SELECT k, COUNT(DISTINCT n) FROM (");
        for (Side side : Side.values()) {
            String own = side == Side.OUT ? "src" : "dst", other = side == Side.OUT ? "dst" : "src";
            if (side != Side.OUT) sql.append(" UNION ALL ");
            sql.append("SELECT ").append(own).append(" AS k, ").append(other).append(" AS n FROM e_")
               .append(side == Side.OUT ? "out" : "in").append(" WHERE bucket IN (").append(bucketList).append(") AND ")
               .append(own).append(" IN (SELECT id FROM ks)");
            if (kinds != null) {
                sql.append(" AND kind IN (").append(String.join(",", java.util.Collections.nCopies(kinds.size(), "?"))).append(')');
                binds.addAll(kinds);
            }
        }
        sql.append(") u");
        if (!excluded.isEmpty()) sql.append(" WHERE n NOT IN (SELECT id FROM xs)");
        sql.append(" GROUP BY k");
        try (PreparedStatement ps = sandbox.preparedStatement(sql.toString())) {
            for (int i = 0; i < binds.size(); i++) ps.setString(i + 1, binds.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    /**
     * A one-column relation ({@code id}) over {@code values} with ONE bind however many there are - the values joined on
     * U+001F and split back by the engine - so a large candidate or exclusion set never grows the statement. Falls back to a
     * {@code VALUES} list only when a value itself carries U+001F.
     */
    public static String idRelation(java.util.Collection<String> values, List<String> binds) {
        for (String v : values)
            if (v.indexOf('') >= 0) {
                binds.addAll(values);
                return "(SELECT * FROM (VALUES " + String.join(",", java.util.Collections.nCopies(values.size(), "(?)")) + ") v(id))";
            }
        binds.add(String.join("", values));
        return "(SELECT unnest(string_split(?, chr(31))) AS id)";
    }

    /** One edge row of the {@code out} copy for a temporal scan: {@code ms} is the event time as epoch milliseconds, null when the row has none. */
    public record LinkTime(String source, String target, Long ms) { }

    /**
     * Every edge row of the index (the {@code out} copy holds each edge once), ordered {@code src, dst, ts} as the flat temporal
     * statement orders them (NULL times last), at most {@code maxRows}; {@code truncated} is not reported here - ask for
     * {@code maxRows + 1} and compare. A full scan: cost linear in the Dataset, bounded by {@code maxRows}. {@code filterSql} is an
     * already-rendered predicate over the index columns or null.
     */
    public List<LinkTime> scanLinkTimes(String filterSql, int maxRows) throws SQLException {
        return scanLinkTimes(filterSql, List.of(), maxRows);
    }

    /** As above; {@code filterBinds} are the filter's positional values. */
    public List<LinkTime> scanLinkTimes(String filterSql, List<String> filterBinds, int maxRows) throws SQLException {
        String sql = "SELECT src, dst, epoch_ms(ts) AS ms FROM e_out" + (filterSql == null ? "" : " WHERE (" + filterSql + ")")
                + " ORDER BY src, dst, ts LIMIT " + maxRows;
        List<LinkTime> out = new ArrayList<>();
        try (PreparedStatement ps = sandbox.preparedStatement(sql)) {
            if (filterSql != null) for (int i = 0; i < filterBinds.size(); i++) ps.setString(i + 1, filterBinds.get(i));
            try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long ms = rs.getLong(3);
                Long time = rs.wasNull() ? null : ms;                     // wasNull() speaks of the LAST column read: ask before the next getString
                out.add(new LinkTime(rs.getString(1), rs.getString(2), time));
            }
            }
        }
        return out;
    }

    /**
     * How many edge ROWS the key has on both sides (its raw degree: a self-loop row counts on each side, and parallel rows are not
     * folded) - one equality statement per side, cost linear in the key's own rows. An upper bound of its folded links, which is
     * what the pre-work size estimate of a graph run wants; zero means the key is not a node of the index.
     */
    public long degree(String key) throws SQLException {
        long total = 0;
        for (Side side : Side.values()) {
            String own = side == Side.OUT ? "src" : "dst";
            String sql = "SELECT COUNT(*) FROM e_" + (side == Side.OUT ? "out" : "in") + " WHERE bucket = "
                    + BucketFunction.bucketOf(key, buckets) + " AND " + own + " = ?";
            try (PreparedStatement ps = sandbox.preparedStatement(sql)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) total += rs.getLong(1);
                }
            }
        }
        return total;
    }

    /** One key's links as the manifest's link statistics count them: its distinct neighbours and its links per kind (a NULL kind as a null key). */
    public record KeyLinks(long degree, Map<String, Long> kinds) { }

    /**
     * The links of one Entity key (Entity profile D-EP4), folded exactly as {@link IndexBuilder} folds them for the manifest:
     * each edge's ends keyed by {@code srcKeySql} / {@code dstKeySql} (TRUSTED SQL over {@code src} / {@code dst}, the endpoint
     * types' normalised keys), distinct {@code (a, b, kind)}, self-links and empty keys left out. A key need not be a raw id, so
     * no bucket narrows the read: one scan of {@code out}'s three columns.
     */
    public KeyLinks keyLinks(String srcKeySql, String dstKeySql, String key) throws SQLException {
        String sql = "WITH l AS (SELECT DISTINCT a, b, kind FROM (SELECT " + srcKeySql + " AS a, " + dstKeySql + " AS b, kind FROM e_out) x"
                + " WHERE (a = ? OR b = ?) AND a <> '' AND b <> '' AND a <> b)"
                + " SELECT kind, count(*) AS n, (SELECT count(DISTINCT CASE WHEN a = ? THEN b ELSE a END) FROM l) AS deg FROM l"
                + " GROUP BY kind ORDER BY n DESC, kind";
        Map<String, Long> kinds = new java.util.LinkedHashMap<>();
        long degree = 0;
        try (PreparedStatement ps = sandbox.preparedStatement(sql)) {
            for (int i = 1; i <= 3; i++) ps.setString(i, key);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    kinds.put(rs.getString(1), rs.getLong(2));
                    degree = rs.getLong(3);
                }
            }
        }
        return new KeyLinks(degree, kinds);
    }

    /**
     * One entity's row of the {@code nodes} table: its edge rows and its distinct {@code (neighbour, kind)} links on each side,
     * and the first time it was seen. Over a version with deltas an entity's main and delta rows are SUMMED, so the link counts
     * are then an upper bound ({@link #exactLinks()} says which).
     */
    public record NodeStat(String id, long outEdges, long inEdges, long outLinks, long inLinks, java.time.LocalDateTime firstSeen) { }

    /** Which side's distinct links rank the entities. */
    public enum NodeRank { IN_LINKS, OUT_LINKS }

    /** False when the version has deltas: an entity's link counts are then summed across files, an upper bound. */
    public boolean exactLinks() {
        return !deltas;
    }

    /**
     * The entities with the most distinct links on one side, from the {@code nodes} table (no edge scan): at least {@code min},
     * highest first, then by id, at most {@code limit} rows. Refused when the version has no {@code nodes} table.
     */
    public List<NodeStat> rankNodes(NodeRank rank, long min, int limit) throws SQLException {
        String col = rank == NodeRank.IN_LINKS ? "in_links" : "out_links";
        return nodeStats("n." + col + " >= ?", "n." + col + " DESC, n.id", List.of(min), limit);
    }

    /**
     * The entities first seen within {@code days} days before the newest link of the index (its data, not the calendar) that
     * already have at least {@code minEdges} edge rows, the busiest first, at most {@code limit} rows.
     */
    public List<NodeStat> newcomers(int days, long minEdges, int limit) throws SQLException {
        return nodeStats("n.first_seen > (SELECT max(last_ts) FROM n_nodes) - to_days(CAST(? AS INTEGER)) AND n.out_edges + n.in_edges >= ?",
                "n.out_edges + n.in_edges DESC, n.id", List.of(days, minEdges), limit);
    }

    /** One pair linked both ways ({@code a < b}) and its links each way. */
    public record TwoWay(String a, String b, long linksAb, long linksBa) { }

    /** One directed loop {@code x -> y -> z -> x} ({@code x} its smallest id) and the links along its three steps. */
    public record Loop(String x, String y, String z, long links) { }

    /**
     * The pairs of {@code pairs_back} with at least {@code min} links each way, the busiest pair first, at most {@code limit}
     * rows. Empty when the table has no file; whether the version wrote it at all is the manifest's to say (the caller checks).
     */
    public List<TwoWay> twoWay(long min, int limit) throws SQLException {
        if (!hasPairsBack) return List.of();
        List<TwoWay> out = new ArrayList<>();
        try (PreparedStatement ps = sandbox.preparedStatement("SELECT a, b, n_ab, n_ba FROM d_pairs_back WHERE least(n_ab, n_ba) >= ?"
                + " ORDER BY n_ab + n_ba DESC, a, b LIMIT ?")) {
            ps.setLong(1, min);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new TwoWay(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)));
            }
        }
        return out;
    }

    /**
     * The loops of {@code loops}, the most links first, at most {@code limit} rows. {@code maxRows} null = as built; else only
     * loops whose three entities have at most that many link rows in and out ({@code nodes} edges summed, exact across deltas)
     * - meaningful only BELOW the build's cap, which the caller checks.
     */
    public List<Loop> loops(Long maxRows, int limit) throws SQLException {
        if (!hasLoops) return List.of();
        String quiet = maxRows == null ? "" : " WHERE x IN (SELECT id FROM q) AND y IN (SELECT id FROM q) AND z IN (SELECT id FROM q)";
        String with = maxRows == null ? "" : "WITH q AS (SELECT id FROM n_nodes WHERE id IS NOT NULL GROUP BY id"
                + " HAVING sum(out_edges + in_edges) <= ?) ";
        List<Loop> out = new ArrayList<>();
        try (PreparedStatement ps = sandbox.preparedStatement(with + "SELECT x, y, z, n FROM d_loops" + quiet
                + " ORDER BY n DESC, x, y, z LIMIT ?")) {
            int i = 1;
            if (maxRows != null) ps.setLong(i++, maxRows);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Loop(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
            }
        }
        return out;
    }

    /** Fixed SQL over {@code n_nodes}; {@code where} and {@code order} are this class's own text, every value a bind. */
    private List<NodeStat> nodeStats(String where, String order, List<Object> binds, int limit) throws SQLException {
        if (!hasNodes) throw new IllegalStateException("this index version has no nodes table");
        String rows = deltas
                ? "SELECT id, sum(out_edges) AS out_edges, sum(in_edges) AS in_edges, sum(out_links) AS out_links,"
                  + " sum(in_links) AS in_links, min(first_ts) AS first_seen FROM n_nodes WHERE id IS NOT NULL GROUP BY id"
                : "SELECT id, out_edges, in_edges, out_links, in_links, first_ts AS first_seen FROM n_nodes WHERE id IS NOT NULL";
        String sql = "SELECT n.id, n.out_edges, n.in_edges, n.out_links, n.in_links, n.first_seen FROM (" + rows + ") n WHERE " + where
                + " ORDER BY " + order + " LIMIT ?";
        List<NodeStat> out = new ArrayList<>();
        try (PreparedStatement ps = sandbox.preparedStatement(sql)) {
            int i = 1;
            for (Object b : binds) ps.setObject(i++, b);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Timestamp ts = rs.getTimestamp(6);
                    out.add(new NodeStat(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                            ts == null ? null : ts.toLocalDateTime()));
                }
            }
        }
        return out;
    }

    /** The sealed connection, for the test that proves it cannot read outside the version directory. */
    Connection connection() {
        return sandbox.connection();
    }

    @Override
    public void close() {
        Path key = pooledKey;
        if (key == null) {
            sandbox.close();
            return;
        }
        pooledKey = null;
        synchronized (IDLE) {
            // a superseded version's late return is re-pooled here and closed by the next borrow of a sibling version
            java.util.ArrayDeque<IndexReader> q = IDLE.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
            if (q.size() < MAX_IDLE_PER_VERSION) {
                q.addFirst(this);
                return;
            }
        }
        sandbox.close();
    }

    private void closeNow() {
        sandbox.close();
    }
}
