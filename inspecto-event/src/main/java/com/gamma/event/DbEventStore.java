package com.gamma.event;

import com.gamma.util.AbstractJdbcStore;
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

    /** Wrap an already-open JDBC connection (any engine); the schema is created if absent. */
    public DbEventStore(Connection conn) {
        super(conn, "events", "Operational Events", TABLE, "event");
        initSchema();
    }

    /**
     * Open an event DB by JDBC URL via {@link JdbcDrivers#connect(String, String, String)}, which
     * registers the bundled driver matching the scheme ({@code jdbc:duckdb:} primary,
     * {@code jdbc:postgresql:}).
     */
    public static DbEventStore open(String url, String user, String pass) throws SQLException {
        return new DbEventStore(JdbcDrivers.connect(url, user, pass));
    }

    // ── append ──────────────────────────────────────────────────────────────────────

    @Override
    public synchronized void append(Event event) {
        if (event == null) return;
        String sql = "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?)";
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
            ps.executeUpdate();
        } catch (SQLException e) {
            // ⚠ Logged, never thrown. Observability must not break the path it observes — the same
            // stance ParquetEventStore takes on a failed flush. An emitter is usually mid-ingest.
            log.warn("Could not append event {} ({}): {}", event.eventId(), event.type(), e.getMessage());
        }
    }

    // ── query ───────────────────────────────────────────────────────────────────────

    @Override
    public synchronized List<Event> query(EventQuery q) {
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
    public synchronized List<Event> recent(int limit) {
        return query(EventQuery.recent(limit));
    }

    /**
     * Exact keyset page — the {@code (ts DESC, eventId DESC)} total order the interface specifies, pushed
     * into SQL rather than derived from {@link #query} (the interface default would cap at
     * {@code MAX_LIMIT} and sort in memory).
     */
    @Override
    public synchronized List<Event> page(int limit, Long afterTs, String afterId) {
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

    /** Exact count — the {@code metadata.pagination.total} companion of {@link #page}. */
    @Override
    public synchronized long count() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            return rs.next() ? rs.getLong(1) : 0L;
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
    public synchronized int prune(LocalDate before, boolean dryRun) {
        long cutoff = before.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        try {
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
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "event_id VARCHAR, ts_ms BIGINT, level VARCHAR, type VARCHAR, source VARCHAR, "
                    + "pipeline VARCHAR, correlation_id VARCHAR, message VARCHAR, "
                    + "attributes VARCHAR, payload VARCHAR)");
            // The one access path that is not a full scan. Both engines accept this form.
            st.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_ts ON " + TABLE + " (ts_ms)");
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
        } catch (SQLException e) {
            log.warn("Event DB query failed: {}", e.getMessage());
        }
        return out;
    }
}
