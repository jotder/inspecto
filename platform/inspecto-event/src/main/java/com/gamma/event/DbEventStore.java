package com.gamma.event;

import com.gamma.util.AbstractJdbcStore;
import com.gamma.util.ConnectionSource;
import com.gamma.util.JdbcDrivers;
import com.gamma.util.JsonAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import com.gamma.audit.AuditChain;
import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.audit.EventQuery;
import com.gamma.audit.EventStore;
import com.gamma.audit.EventType;

/**
 * Durable, <b>shared</b> event store on JDBC — {@code -Devents.backend=db}, decision <b>D6</b> of the
 * enterprise scale-out plan.
 *
 * <h3>Why this exists when {@link ParquetEventStore} already persists</h3>
 * 🔴 Parquet makes events survive a restart; it does not make them <b>visible to another process</b>.
 * {@code EventLog} is a per-process, per-Space static registry, and a Parquet directory is written by
 * exactly one pod. On N pods the Signal ledger — the spine of Ops — therefore shows a different world per
 * replica. This store is the one backend that two processes can share, which is the whole of D6.
 *
 * <p>⚠ <b>D6 does NOT make SSE cross-pod</b>, contrary to a reading of the plan's §5.5. {@code
 * /signals/stream} subscribes to {@code EventLog}'s <b>in-heap</b> subscriber list and never consults a
 * store, and {@code EventObjectBridge} promotes {@code SEQUENCE_GAP} from that same in-heap stream — so a
 * gap observed on pod B still never becomes an ALERT if the bridge runs on pod A. This store closes the
 * <em>query</em> half only. ⛔ Do not record D6 as closing the live-tail half.
 *
 * <h3>Contract notes</h3>
 * Append-only, like every other {@code EventStore}: no update, no row delete. {@link #prune} removes whole
 * UTC days, mirroring the Parquet store's partition delete, and returns the number of <b>days</b> removed
 * so the two backends report the same unit.
 *
 * <p>⚠ Every method is {@code synchronized} on the store, because {@link AbstractJdbcStore} holds a single
 * shared {@link Connection} and a JDBC connection is not thread-safe. Events arrive from many threads.
 *
 * <p>⚠ Column names mirror {@link ParquetEventStore}'s exactly ({@code event_id, ts_ms, …}) so an operator
 * reading one backend's raw table reads the other's unchanged, and so the row→{@link Event} mapping is the
 * same code shape in both.
 *
 * @since 5.x
 */
@com.gamma.api.PublicApi(since = "5.0.0")
public final class DbEventStore extends AbstractJdbcStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(DbEventStore.class);

    private static final String TABLE = "inspecto_events";
    private static final String COLS = "event_id, ts_ms, level, type, source, pipeline, "
            + "correlation_id, message, attributes, payload";

    /** Wrap an already-open JDBC connection (any engine); the store owns and closes it. */
    public DbEventStore(Connection conn) {
        this(JdbcDrivers.source(conn));
    }

    /** Borrow from {@code src} per operation; the schema is created if absent. */
    public DbEventStore(ConnectionSource src) {
        this(src, null);
    }

    /** {@code lockConn}: the dedicated connection that holds the PostgreSQL chain-writer advisory lock for this
     *  store's life (see {@link #claimChainWriter}); the store owns and closes it. */
    private DbEventStore(ConnectionSource src, Connection lockConn) {
        super(src, "events", "Operational Events", TABLE, "event");
        this.lockConn = lockConn;
        initSchema();
    }

    /**
     * Open an event DB by JDBC URL via {@link JdbcDrivers#connect(String, String, String)}, which
     * registers the bundled driver matching the scheme ({@code jdbc:duckdb:} primary,
     * {@code jdbc:postgresql:}).
     */
    public static DbEventStore open(String url, String user, String pass) throws SQLException {
        // PostgreSQL is the one engine several pods can share, so it gets a connection of its own that is never
        // pooled or returned: a session-level advisory lock lives exactly as long as that session.
        Connection lock = url != null && url.startsWith("jdbc:postgresql:") ? JdbcDrivers.connect(url, user, pass) : null;
        try {
            return new DbEventStore(JdbcDrivers.source(url, user, pass, "events"), lock);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(lock);
            throw e;
        }
    }

    // ── the single chain writer (ASSURE-AUDIT-CHAIN-RESIDUALS-1 (2)) ───────────────────────────────

    private final Connection lockConn;
    private boolean writerHeld;

    /**
     * Claim the right to LINK onto this store's audit chain, or throw — a store two pods link onto forks the chain
     * (each recovers the same head).
     *
     * <p>🔴 There is no honest way to COUNT the pods that share a database (no replica count, no pod id, no
     * partition map reaches this store), so the signal is the only reliable one: a try-lock this process takes
     * and holds for its whole life, on a dedicated connection. On PostgreSQL that is a session-level
     * {@code pg_try_advisory_lock}, which the server releases the instant the process or its connection dies, so a
     * crash never wedges the next start. ⚠ This is a fail-closed single-WRITER guard, not a pod detector: a
     * second process that links onto the same database is refused whether or not it is a "pod". A store that
     * cannot hold the lock (no dedicated connection, or the connection has died since) refuses too.
     *
     * <p>A single-connection file engine (DuckDB) needs no claim: the engine itself holds an exclusive lock on
     * its file, so the file cannot be shared by two processes. Any other engine is refused.
     */
    @Override
    public synchronized void claimChainWriter() {
        if (!src.isPostgres()) {
            try {
                String product = withConn(c -> c.getMetaData().getDatabaseProductName());
                if (product != null && product.toLowerCase().contains("duckdb")) return;
                throw new IllegalStateException("the event database (" + product + ") has no single-writer guarantee "
                        + "for the audit chain");
            } catch (SQLException e) {
                throw new IllegalStateException("could not identify the event database engine: " + e.getMessage(), e);
            }
        }
        try {
            if (lockConn == null || lockConn.isClosed() || !lockConn.isValid(5)) {
                writerHeld = false;
                throw new IllegalStateException("the audit chain writer lock connection of the shared event database "
                        + (lockConn == null ? "was never opened (open the store with DbEventStore.open)" : "was lost"));
            }
            if (writerHeld) return;
            String schema;
            try (Statement st = lockConn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT current_schema()")) {
                schema = rs.next() ? rs.getString(1) : "";
            }
            long key = ("inspecto.audit-chain-writer:" + schema).hashCode();
            try (PreparedStatement ps = lockConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean(1))
                        throw new IllegalStateException("another process holds the audit chain writer lock on this "
                                + "shared event database");
                }
            }
            writerHeld = true;
        } catch (SQLException e) {
            throw new IllegalStateException("could not claim the audit chain writer lock: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            writerHeld = false;
            closeQuietly(lockConn);
        }
        super.close();
    }

    private static void closeQuietly(Connection c) {
        if (c == null) return;
        try {
            c.close();
        } catch (SQLException ignore) {
            // the server drops the session (and its advisory lock) with the connection anyway
        }
    }

    // ── append ──────────────────────────────────────────────────────────────────────

    @Override
    public void append(Event event) {
        if (event == null) return;
        String sql = "INSERT INTO " + TABLE + " (" + COLS + ", audit_seq) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        long seq = AuditChain.chained(event) ? AuditChain.seq(event) : -1;
        try {
            runConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, event.eventId());
                    ps.setLong(2, event.ts());
                    ps.setString(3, event.level().name());
                    ps.setString(4, event.type());
                    ps.setString(5, event.source());
                    ps.setString(6, event.pipeline());
                    ps.setString(7, event.correlationId());
                    ps.setString(8, event.message());
                    ps.setString(9, JsonAttributes.toJson(event.attributes()));
                    ps.setString(10, JsonAttributes.toPayloadJson(event.payload()));
                    if (seq > 0) ps.setLong(11, seq);
                    else ps.setNull(11, java.sql.Types.BIGINT);
                    ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            // ⚠ Logged, never thrown. Observability must not break the path it observes — the same
            // stance ParquetEventStore takes on a failed flush. An emitter is usually mid-ingest.
            log.warn("Could not append event {} ({}): {}", event.eventId(), event.type(), e.getMessage());
        }
    }

    // ── query ───────────────────────────────────────────────────────────────────────

    @Override
    public List<Event> query(EventQuery q) {
        List<Object> params = new ArrayList<>();
        String where = whereFor(q, params);
        // OFFSET is applied in SQL here (the Parquet store cannot — it merges an unflushed buffer first).
        String sql = "SELECT " + COLS + " FROM " + TABLE + where
                + " ORDER BY ts_ms DESC, event_id DESC LIMIT ? OFFSET ?";
        params.add((long) q.limit());
        params.add((long) q.offset());
        return read(sql, params);
    }

    @Override
    public List<Event> recent(int limit) {
        return query(EventQuery.recent(limit));
    }

    /**
     * Exact keyset page — the {@code (ts DESC, eventId DESC)} total order the interface specifies, pushed
     * into SQL rather than derived from {@link #query} (the interface default would cap at
     * {@code MAX_LIMIT} and sort in memory).
     */
    @Override
    public List<Event> page(int limit, Long afterTs, String afterId) {
        List<Object> params = new ArrayList<>();
        String where = "";
        if (afterTs != null) {
            // Strictly "older than" the cursor, with event_id breaking a timestamp tie — so a cursor
            // resumes unambiguously even when several events share a millisecond.
            where = " WHERE (ts_ms < ? OR (ts_ms = ? AND event_id < ?))";
            params.add(afterTs);
            params.add(afterTs);
            params.add(afterId == null ? "" : afterId);
        }
        String sql = "SELECT " + COLS + " FROM " + TABLE + where
                + " ORDER BY ts_ms DESC, event_id DESC LIMIT ?";
        params.add((long) Math.max(0, limit));
        return read(sql, params);
    }

    // ── the audit chain (ASSURE-AUDIT-CHAIN-1): indexed reads on audit_seq ─────────────────────

    @Override
    public Event chainHead() {
        List<Event> r = readOrThrow("SELECT " + COLS + " FROM " + TABLE
                + " WHERE audit_seq IS NOT NULL ORDER BY audit_seq DESC LIMIT 1", List.of());
        return r.isEmpty() ? null : r.get(0);
    }

    @Override
    public List<Event> chainPage(long fromSeq, int limit) {
        return readOrThrow("SELECT " + COLS + " FROM " + TABLE
                + " WHERE audit_seq >= ? ORDER BY audit_seq, event_id LIMIT ?", List.of(fromSeq, (long) Math.max(0, limit)));
    }

    @Override
    public long unlinkedSince(long fromTs) {
        return readOrThrow("SELECT " + COLS + " FROM " + TABLE + " WHERE type IN ('" + EventType.AUDIT + "', '"
                + EventType.ACCESS_DENIED + "') AND ts_ms >= ? AND audit_seq IS NULL", List.of(fromTs)).size();
    }

    @Override
    public java.util.Set<String> presentIds(java.util.Collection<String> ids) {
        java.util.Set<String> found = new java.util.HashSet<>();
        List<String> all = new ArrayList<>(ids);
        for (int from = 0; from < all.size(); from += 500) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + 500));
            String in = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            for (Event e : readOrThrow("SELECT " + COLS + " FROM " + TABLE + " WHERE event_id IN (" + in + ")",
                    new ArrayList<>(chunk))) found.add(e.eventId());
        }
        return found;
    }

    /** As {@link #read}, but a failure THROWS: a chain read must never answer "empty" for "unreadable". */
    private List<Event> readOrThrow(String sql, List<Object> params) {
        List<Event> out = new ArrayList<>();
        try {
            runConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < params.size(); i++) {
                        Object p = params.get(i);
                        if (p instanceof Long l) ps.setLong(i + 1, l);
                        else ps.setString(i + 1, String.valueOf(p));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new Event(rs.getString("event_id"), rs.getLong("ts_ms"),
                                    EventLevel.parse(rs.getString("level")), rs.getString("type"),
                                    rs.getString("source"), rs.getString("pipeline"),
                                    rs.getString("correlation_id"), rs.getString("message"),
                                    JsonAttributes.fromJson(rs.getString("attributes")),
                                    JsonAttributes.fromPayloadJson(rs.getString("payload"))));
                        }
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("audit chain read failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** Exact count — the {@code metadata.pagination.total} companion of {@link #page}. */
    @Override
    public long count() {
        try {
            return withConn(conn -> {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            });
        } catch (SQLException e) {
            log.warn("Event count failed: {}", e.getMessage());
            return 0L;
        }
    }

    /**
     * Audit retention (COMPLY-3): drop every event whose UTC day is strictly before {@code before}, and
     * return the number of <b>days</b> removed — not rows — so this reports the same unit as
     * {@link ParquetEventStore}, which deletes whole day partitions.
     *
     * <p>⚠ A {@code dryRun} counts without deleting. ⛔ The boundary is exclusive: an event ON
     * {@code before} is retained, matching the Parquet store and the MNT-14 G3 stance that nothing inside
     * the window is touched.
     */
    @Override
    public int prune(LocalDate before, boolean dryRun) {
        long cutoff = before.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        try {
            // ⚠ Count and delete share ONE borrow: on a pool they would otherwise land on different
            // connections, so a concurrent append between them could be counted and not deleted.
            return withConn(conn -> {
                int days;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(DISTINCT CAST(ts_ms / 86400000 AS BIGINT)) FROM " + TABLE + " WHERE ts_ms < ?")) {
                    ps.setLong(1, cutoff);
                    try (ResultSet rs = ps.executeQuery()) {
                        days = rs.next() ? rs.getInt(1) : 0;
                    }
                }
                if (!dryRun && days > 0) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM " + TABLE + " WHERE ts_ms < ?")) {
                        ps.setLong(1, cutoff);
                        ps.executeUpdate();
                    }
                }
                return days;
            });
        } catch (SQLException e) {
            log.warn("Event prune failed: {}", e.getMessage());
            return -1;
        }
    }

    /** Nothing is buffered — every {@link #append} is already committed, so this is a genuine no-op. */
    @Override
    public void flush() {
        // intentionally empty: see the method contract above
    }

    // ── schema + helpers ────────────────────────────────────────────────────────────

    private void initSchema() {
        try {
            runConn(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                            + "event_id VARCHAR, ts_ms BIGINT, level VARCHAR, type VARCHAR, source VARCHAR, "
                            + "pipeline VARCHAR, correlation_id VARCHAR, message VARCHAR, "
                            + "attributes VARCHAR, payload VARCHAR)");
                    // The one access path that is not a full scan. Both engines accept this form.
                    st.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_ts ON " + TABLE + " (ts_ms)");
                    // ASSURE-AUDIT-CHAIN-1: the chain seq as a real, indexed column, so the chain reads are an
                    // index range and not a scan of every event's attributes JSON.
                    st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS audit_seq BIGINT");
                    st.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_seq ON " + TABLE + " (audit_seq)");
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise event DB schema", e);
        }
    }

    /**
     * The {@code WHERE} clause for {@code q}, appending its bind values to {@code params}.
     *
     * <p>⚠ Every predicate here must agree with {@link EventQuery#matches}, which is what the in-memory
     * store applies — two backends answering the same query differently is the defect this mirrors away.
     * Two deliberate details: {@code type}/{@code pipeline} compare case-INsensitively because
     * {@code matches} uses {@code equalsIgnoreCase}, while {@code correlationId} is exact because it uses
     * {@code equals}; and {@code minLevel} is expanded to the set of names at or above it rather than a
     * string comparison, since the ladder is an enum ORDER, not alphabetical.
     */
    private static String whereFor(EventQuery q, List<Object> params) {
        StringBuilder w = new StringBuilder();
        if (q.fromMs() != null) {
            w.append(w.isEmpty() ? " WHERE " : " AND ").append("ts_ms >= ?");
            params.add(q.fromMs());
        }
        if (q.toMs() != null) {
            w.append(w.isEmpty() ? " WHERE " : " AND ").append("ts_ms <= ?");
            params.add(q.toMs());
        }
        if (q.type() != null) {
            w.append(w.isEmpty() ? " WHERE " : " AND ").append("UPPER(type) = UPPER(?)");
            params.add(q.type());
        }
        if (q.pipeline() != null) {
            w.append(w.isEmpty() ? " WHERE " : " AND ").append("UPPER(pipeline) = UPPER(?)");
            params.add(q.pipeline());
        }
        if (q.correlationId() != null) {
            w.append(w.isEmpty() ? " WHERE " : " AND ").append("correlation_id = ?");
            params.add(q.correlationId());
        }
        if (q.minLevel() != null) {
            List<String> atLeast = new ArrayList<>();
            for (EventLevel l : EventLevel.values()) if (l.atLeast(q.minLevel())) atLeast.add(l.name());
            w.append(w.isEmpty() ? " WHERE " : " AND ").append("level IN (");
            for (int i = 0; i < atLeast.size(); i++) {
                w.append(i == 0 ? "?" : ",?");
                params.add(atLeast.get(i));
            }
            w.append(')');
        }
        if (q.textContains() != null && !q.textContains().isBlank()) {
            // matches() searches message OR source, case-insensitively.
            w.append(w.isEmpty() ? " WHERE " : " AND ")
                    .append("(UPPER(message) LIKE UPPER(?) OR UPPER(source) LIKE UPPER(?))");
            String like = "%" + q.textContains() + "%";
            params.add(like);
            params.add(like);
        }
        return w.toString();
    }

    /** Run {@code sql} with {@code params} bound in order and map every row to an {@link Event}. */
    private List<Event> read(String sql, List<Object> params) {
        List<Event> out = new ArrayList<>();
        try {
            runConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < params.size(); i++) {
                        Object p = params.get(i);
                        if (p instanceof Long l) ps.setLong(i + 1, l);
                        else ps.setString(i + 1, String.valueOf(p));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new Event(rs.getString("event_id"), rs.getLong("ts_ms"),
                                    EventLevel.parse(rs.getString("level")), rs.getString("type"),
                                    rs.getString("source"), rs.getString("pipeline"),
                                    rs.getString("correlation_id"), rs.getString("message"),
                                    JsonAttributes.fromJson(rs.getString("attributes")),
                                    JsonAttributes.fromPayloadJson(rs.getString("payload"))));
                        }
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("Event DB query failed: {}", e.getMessage());
        }
        return out;
    }
}
