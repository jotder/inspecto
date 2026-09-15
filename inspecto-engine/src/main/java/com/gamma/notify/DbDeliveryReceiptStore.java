package com.gamma.notify;

import com.gamma.util.AbstractJdbcStore;

import com.gamma.util.ConnectionSource;
import com.gamma.util.JdbcDrivers;
import com.gamma.util.JsonAttributes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Database-backed {@link DeliveryReceiptStore} — the durable twin of {@link InMemoryDeliveryReceiptStore},
 * built on the same {@link AbstractJdbcStore} idiom as {@link com.gamma.ops.note.DbNoteStore}
 * (plain JDBC over the bundled DuckDB, or a {@code jdbc:postgresql://…} URL; every access borrows a
 * connection from the store's {@link ConnectionSource} for the duration of that one operation).
 *
 * <p><b>Why it exists.</b> `D8-SUPPRESS-1` — per-recipient suppression (a TTL for hard bounces, permanent
 * for complaints) — has been gated on "a DB-backed `DeliveryReceiptStore`" since it was filed, and on
 * 2026-09-07 that gate was still holding: {@link InMemoryDeliveryReceiptStore} was the only implementor.
 * Suppression cannot be built on a bounded in-memory map, because the map evicts oldest-first: the bounce
 * that should suppress an address is exactly the record most likely to have been dropped by the time the
 * next send asks. This is also EDITIONS `CP-15`'s remaining gap, which is Standard-and-above.
 *
 * <p>⛔ <b>It does not become the default.</b> {@code -Ddelivery.receipts.backend} defaults to {@code none},
 * so a Personal install keeps the lean in-memory store and writes no new database file. That is deliberate
 * twice over: this codebase's most repeated trap is a default-ON DB family creating files in the working
 * directory under {@code SpaceRoot.legacy()}, and CP-15 is a "not for Personal" cell.
 *
 * <p>⚠ <b>The status history is a JSON column, not a row per status.</b> {@link DeliveryReceipt#statusAt}
 * is a {@code Map<DeliveryStatus,Long>} precisely because a spam-button click produces {@code delivered}
 * <i>then</i> {@code complaint} for the same message and a single enum would erase the earlier one. A
 * child table would model that faithfully too, but every read here wants the whole map and never one
 * status, so it would buy a join on every query and no expressiveness the map lacks.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class DbDeliveryReceiptStore extends AbstractJdbcStore
        implements DeliveryReceiptStore {

    private static final String TABLE = "inspecto_delivery_receipts";
    /** Operator overrides (D8-SUPPRESS-1). Separate table: an override is a DECISION about history, not
     *  part of it, and mixing the two would make "was this address ever bad" unanswerable. */
    private static final String OVERRIDES = "inspecto_delivery_suppression_overrides";
    private static final String COLS =
            "delivery_id, notification_id, channel_config_id, target, sent_at, status_at, provider_raw, digest, "
            + "attempt_count, last_attempt_at";

    /** Wrap an already-open JDBC connection (any engine); the schema is created if absent. */
    public DbDeliveryReceiptStore(Connection conn) {
        this(JdbcDrivers.source(conn));
    }

    /** Borrow from {@code src} per operation; the schema is created if absent. */
    public DbDeliveryReceiptStore(ConnectionSource src) {
        super(src, "delivery_receipts", "Delivery receipts", TABLE, "delivery-receipt");
        initSchema();
    }

    /**
     * Open a receipt DB by JDBC URL via {@link JdbcDrivers#source(String, String, String, String)}, which
     * registers the bundled driver matching the scheme ({@code jdbc:duckdb:}, {@code jdbc:postgresql:}).
     */
    public static DbDeliveryReceiptStore open(String url, String user, String pass) throws SQLException {
        return new DbDeliveryReceiptStore(JdbcDrivers.source(url, user, pass, "delivery-receipts"));
    }

    private void initSchema() {
        try {
            runConn(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                            + "delivery_id VARCHAR PRIMARY KEY, notification_id VARCHAR, channel_config_id VARCHAR, "
                            + "target VARCHAR, sent_at BIGINT, status_at VARCHAR, provider_raw VARCHAR, "
                            + "digest BOOLEAN)");
                    // forNotification() and prune()/countPrunable() are the only non-PK access paths; both scan
                    // without these. Harmless if the engine ignores the hint.
                    // Soft-bounce retry (D8) needs a retry clock the status map cannot carry: withStatus keeps
                    // the FIRST observation of each status, so statusAt[BOUNCED_SOFT] never advances and a
                    // backoff measured from it would fire every remaining attempt at once. Additive and
                    // existing-install safe - the same ADD COLUMN IF NOT EXISTS idiom DbConsignmentOutputStore
                    // uses; rows written before this read back NULL and map to 0.
                    st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS attempt_count INTEGER");
                    st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS last_attempt_at BIGINT");
                    st.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_notification ON " + TABLE + " (notification_id)");
                    st.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_sent_at ON " + TABLE + " (sent_at)");
                    // latestWithStatus() — the per-recipient suppression lookup, run once per external delivery.
                    st.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_target ON " + TABLE + " (target)");
                    st.execute("CREATE TABLE IF NOT EXISTS " + OVERRIDES + " ("
                            + "target VARCHAR PRIMARY KEY, cleared_at BIGINT, actor VARCHAR)");
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not initialise " + TABLE + ": " + e.getMessage(), e);
        }
    }

    @Override
    public DeliveryReceipt add(DeliveryReceipt receipt) {
        // A resend reusing a delivery id would violate the PK; deleting first makes add() idempotent, which
        // is what the in-memory `put` already is. ⚠ Not an UPSERT: DuckDB and Postgres spell it differently
        // and this store must work unchanged on both.
        String delete = "DELETE FROM " + TABLE + " WHERE delivery_id = ?";
        String insert = "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?)";
        try {
            // ⚠ Delete and insert share ONE borrow — the pair IS the idempotent write, and on a pool two
            // borrows would be two connections with another writer free to slip between them.
            runConn(conn -> {
                try (PreparedStatement del = conn.prepareStatement(delete);
                     PreparedStatement ps = conn.prepareStatement(insert)) {
                    del.setString(1, receipt.deliveryId());
                    del.executeUpdate();
                    ps.setString(1, receipt.deliveryId());
                    ps.setString(2, receipt.notificationId());
                    ps.setString(3, receipt.channelConfigId());
                    ps.setString(4, receipt.target());
                    ps.setLong(5, receipt.sentAt());
                    ps.setString(6, statusJson(receipt.statusAt()));
                    ps.setString(7, receipt.providerRaw());
                    ps.setBoolean(8, receipt.digest());
                    ps.setInt(9, receipt.attemptCount());
                    ps.setLong(10, receipt.lastAttemptAt());
                    ps.executeUpdate();
                }
            });
            return receipt;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "could not insert delivery receipt " + receipt.deliveryId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<DeliveryReceipt> get(String deliveryId) {
        String sql = "SELECT " + COLS + " FROM " + TABLE + " WHERE delivery_id = ?";
        try {
            return withConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, deliveryId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(read(rs)) : Optional.<DeliveryReceipt>empty();
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not read delivery receipt " + deliveryId + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read-merge-write on ONE borrowed connection, mirroring the in-memory implementation exactly — the
     * merge lives in {@link DeliveryReceipt#withStatus}, so "first observation of a status wins" is one
     * rule in one place rather than a second copy in SQL.
     *
     * <p>⚠ An unknown delivery id returns empty and writes nothing. That is the contract, not a miss to
     * fix: receipts are prunable and providers retry a non-2xx forever, so a callback for a receipt this
     * store has already pruned must be accepted.
     */
    @Override
    public Optional<DeliveryReceipt> stamp(String deliveryId, DeliveryStatus status, long ts,
                                           String providerRaw) {
        String sql = "UPDATE " + TABLE + " SET status_at = ?, provider_raw = ? WHERE delivery_id = ?";
        try {
            // ⚠ The read and the write share ONE borrow — withConn is reentrant, so the nested get()
            // reuses this connection rather than taking a second one from the pool.
            return withConn(conn -> {
                Optional<DeliveryReceipt> existing = get(deliveryId);
                if (existing.isEmpty()) return Optional.<DeliveryReceipt>empty();
                DeliveryReceipt updated = existing.get().withStatus(status, ts, providerRaw);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, statusJson(updated.statusAt()));
                    ps.setString(2, updated.providerRaw());
                    ps.setString(3, deliveryId);
                    ps.executeUpdate();
                    return Optional.of(updated);
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not stamp delivery receipt " + deliveryId + ": "
                    + e.getMessage(), e);
        }
    }

    @Override
    public List<DeliveryReceipt> forNotification(String notificationId) {
        if (notificationId == null) return List.of();
        return query("SELECT " + COLS + " FROM " + TABLE
                + " WHERE notification_id = ? ORDER BY sent_at DESC", ps -> ps.setString(1, notificationId));
    }

    @Override
    public List<DeliveryReceipt> recent(int limit) {
        if (limit <= 0) return List.of();
        return query("SELECT " + COLS + " FROM " + TABLE + " ORDER BY sent_at DESC LIMIT ?",
                ps -> ps.setInt(1, limit));
    }

    /** The one definition of "prunable" — shared by the preview and the sweep (PRUNE-PREVIEW-DRIFT-1). */
    private static final String PRUNABLE = "sent_at < ?";

    @Override
    public int countPrunable(long cutoffMs) {
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE " + PRUNABLE;
        try {
            return withConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, cutoffMs);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not count prunable receipts: " + e.getMessage(), e);
        }
    }

    @Override
    public int prune(long cutoffMs) {
        try {
            return withConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + TABLE + " WHERE " + PRUNABLE)) {
                    ps.setLong(1, cutoffMs);
                    return ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not prune receipts: " + e.getMessage(), e);
        }
    }

    /** Receipts live in a database and are removed only by an explicit {@link #prune}. */
    @Override
    public boolean durable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Overridden because the interface default scans a bounded window of recent receipts, which is the
     * one thing a durable store must not do — the whole point of persisting receipts is that the bounce
     * being looked up may be older than any window.
     *
     * <p>⚠ The {@code LIKE} is a <b>pre-filter, not the decision</b>. {@code status_at} is our own JSON
     * ({@code {"BOUNCED_HARD":"1700000000000"}}), so matching the quoted key name narrows the scan in
     * SQL; the row is then parsed and re-checked against the real map, so a substring that happened to
     * appear inside {@code provider_raw}-shaped data could never produce a false suppression.
     */
    @Override
    public Optional<DeliveryReceipt> latestWithStatus(String target, DeliveryStatus status) {
        if (target == null || target.isBlank() || status == null) return Optional.empty();
        String sql = "SELECT " + COLS + " FROM " + TABLE
                + " WHERE target = ? AND status_at LIKE ? ORDER BY sent_at DESC LIMIT 1";
        try {
            return withConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, target);
                    ps.setString(2, "%\"" + status.name() + "\"%");
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return Optional.<DeliveryReceipt>empty();
                        DeliveryReceipt r = read(rs);
                        return r.statusAt().containsKey(status) ? Optional.of(r) : Optional.<DeliveryReceipt>empty();
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not look up " + status + " for " + target + ": "
                    + e.getMessage(), e);
        }
    }

    /** Both tables, so the raw-table browser shows the overrides beside the receipts they qualify. */
    @Override
    public List<String> browseTables() {
        return List.of(TABLE, OVERRIDES);
    }

    @Override
    public List<String> targetsWithStatus(DeliveryStatus status) {
        if (status == null) return List.of();
        String sql = "SELECT DISTINCT target FROM " + TABLE
                + " WHERE target IS NOT NULL AND target <> '' AND status_at LIKE ? ORDER BY target";
        try {
            return withConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, "%\"" + status.name() + "\"%");
                    try (ResultSet rs = ps.executeQuery()) {
                        List<String> out = new ArrayList<>();
                        while (rs.next()) out.add(rs.getString(1));
                        return out;
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not list targets with " + status + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Last write wins on a re-forgive: the newest {@code cleared_at} is the one that matters, and an
     * older row would only ever forgive less.
     */
    @Override
    public boolean unsuppress(String target, long at, String actor) {
        if (target == null || target.isBlank()) return false;
        try {
            // ⚠ Delete and insert share ONE borrow: "last write wins" only holds if nothing can land
            // between them, which two separate pool borrows would allow.
            runConn(conn -> {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM " + OVERRIDES + " WHERE target = ?");
                     PreparedStatement ins = conn.prepareStatement(
                             "INSERT INTO " + OVERRIDES + " (target, cleared_at, actor) VALUES (?,?,?)")) {
                    del.setString(1, target);
                    del.executeUpdate();
                    ins.setString(1, target);
                    ins.setLong(2, at);
                    ins.setString(3, actor);
                    ins.executeUpdate();
                }
            });
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("could not record suppression override for " + target + ": "
                    + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Long> unsuppressedAt(String target) {
        if (target == null || target.isBlank()) return Optional.empty();
        try {
            return withConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT cleared_at FROM " + OVERRIDES + " WHERE target = ?")) {
                    ps.setString(1, target);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(rs.getLong(1)) : Optional.<Long>empty();
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not read suppression override for " + target + ": "
                    + e.getMessage(), e);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<DeliveryReceipt> query(String sql, Binder binder) {
        try {
            return withConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    binder.bind(ps);
                    try (ResultSet rs = ps.executeQuery()) {
                        List<DeliveryReceipt> out = new ArrayList<>();
                        while (rs.next()) out.add(read(rs));
                        return out;
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not read delivery receipts: " + e.getMessage(), e);
        }
    }

    private static DeliveryReceipt read(ResultSet rs) throws SQLException {
        // ⚠ getInt/getLong return 0 for a SQL NULL, which is exactly the right reading for a row written
        // before these columns existed: never retried.
        return new DeliveryReceipt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), statusMap(rs.getString(6)), rs.getString(7), rs.getBoolean(8),
                rs.getInt(9), rs.getLong(10));
    }

    /** The status history as a flat {@code {"BOUNCED_HARD":"171…"}} object — one column, whole-map reads. */
    private static String statusJson(Map<DeliveryStatus, Long> statusAt) {
        if (statusAt == null || statusAt.isEmpty()) return "{}";
        Map<String, String> flat = new LinkedHashMap<>();
        statusAt.forEach((k, v) -> flat.put(k.name(), String.valueOf(v)));
        return JsonAttributes.toJson(flat);
    }

    /**
     * The inverse of {@link #statusJson}. ⚠ An entry this build does not recognise is DROPPED, not thrown
     * on: a receipt written by a newer build that added a {@link DeliveryStatus} constant must still be
     * readable here, and losing one status slot is recoverable where refusing the whole row is not.
     */
    private static Map<DeliveryStatus, Long> statusMap(String json) {
        Map<String, String> flat = JsonAttributes.fromJson(json);
        if (flat.isEmpty()) return Map.of();
        Map<DeliveryStatus, Long> out = new EnumMap<>(DeliveryStatus.class);
        for (Map.Entry<String, String> e : flat.entrySet()) {
            try {
                out.put(DeliveryStatus.valueOf(e.getKey().trim().toUpperCase(Locale.ROOT)),
                        Long.parseLong(e.getValue().trim()));
            } catch (IllegalArgumentException ignored) {
                // unknown status name, or a non-numeric timestamp — see the note above
            }
        }
        return out;
    }
}
