package com.gamma.la.storage;

import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;

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
    /** Non-null for a reader lent by {@link #borrow}: {@link #close()} then returns it to the pool instead of closing it. */
    private Path pooledKey;

    private IndexReader(SqlSandbox sandbox, int buckets) {
        this.sandbox = sandbox;
        this.buckets = buckets;
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
            IDLE.entrySet().removeIf(e -> {
                boolean sibling = !e.getKey().equals(key) && java.util.Objects.equals(e.getKey().getParent(), parent);
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

    /** Opens the views, then seals the connection to {@code versionDir}. */
    public static IndexReader open(Path versionDir, IndexManifest manifest, SqlSandboxPolicy policy) throws SQLException, IOException {
        if (!BucketFunction.NAME.equals(manifest.bucketFn()))
            throw new IllegalArgumentException("index bucket function '" + manifest.bucketFn() + "' is not '" + BucketFunction.NAME + "'");
        if (!manifest.deltas().isEmpty()) throw new IllegalArgumentException("an index with delta files cannot be read yet");
        SqlSandbox sandbox = SqlSandbox.open(policy);
        try {
            try (Statement st = sandbox.connection().createStatement()) {
                for (String table : new String[] {"out", "in"}) {
                    String glob = versionDir.toAbsolutePath().normalize().toString().replace('\\', '/').replace("'", "''")
                            + "/" + table + "/*/*.parquet";
                    st.execute("CREATE VIEW e_" + table + " AS SELECT * FROM read_parquet('" + glob + "', hive_partitioning = true)");
                }
            }
            SqlSandbox.sealAllowing(sandbox.connection(), List.of(versionDir));
        } catch (SQLException | RuntimeException e) {
            sandbox.close();
            throw e;
        }
        return new IndexReader(sandbox, manifest.buckets());
    }

    /**
     * The edges of every key, one equality statement per key and side, {@code UNION ALL}ed into one round trip. {@code filterSql}
     * is an already-rendered predicate over the index columns ({@code src, dst, kind, ts, a0..}) or null; each key yields at most
     * {@code perKeyLimit} rows per side. Keys with no edge are absent from the map.
     */
    public Map<String, List<Edge>> edges(List<String> keys, List<Side> sides, String filterSql, int perKeyLimit) throws SQLException {
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
