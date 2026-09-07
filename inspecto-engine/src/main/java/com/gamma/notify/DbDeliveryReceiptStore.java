package com.gamma.notify;

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
 * built on the same {@link com.gamma.ops.AbstractJdbcStore} idiom as {@link com.gamma.ops.note.DbNoteStore}
 * (plain JDBC over the bundled DuckDB, or a {@code jdbc:postgresql://…} URL; one shared connection, every
 * access serialised on the store's monitor).
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
public final class DbDeliveryReceiptStore extends com.gamma.ops.AbstractJdbcStore
        implements DeliveryReceiptStore {

    private static final String TABLE = "inspecto_delivery_receipts";
    private static final String COLS =
            "delivery_id, notification_id, channel_config_id, target, sent_at, status_at, provider_raw, digest";

    /** Wrap an already-open JDBC connection (any engine); the schema is created if absent. */
    public DbDeliveryReceiptStore(Connection conn) {
        super(conn, "delivery_receipts", "Delivery receipts", TABLE, "delivery-receipt");
        initSchema();
    }

    /**
     * Open a receipt DB by JDBC URL via {@link JdbcDrivers#connect(String, String, String)}, which
     * registers the bundled driver matching the scheme ({@code jdbc:duckdb:}, {@code jdbc:postgresql:}).
     */
    public static DbDeliveryReceiptStore open(String url, String user, String pass) throws SQLException {
        return new DbDeliveryReceiptStore(JdbcDrivers.connect(url, user, pass));
    }

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "delivery_id VARCHAR PRIMARY KEY, notification_id VARCHAR, channel_config_id VARCHAR, "
                    + "target VARCHAR, sent_at BIGINT, status_at VARCHAR, provider_raw VARCHAR, "
                    + "digest BOOLEAN)");
            // forNotification() and prune()/countPrunable() are the only non-PK access paths; both scan
            // without these. Harmless if the engine ignores the hint.
            st.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_notification ON " + TABLE + " (notification_id)");
            st.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_sent_at ON " + TABLE + " (sent_at)");
        } catch (SQLException e) {
            throw new IllegalStateException("could not initialise " + TABLE + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized DeliveryReceipt add(DeliveryReceipt receipt) {
        // A resend reusing a delivery id would violate the PK; deleting first makes add() idempotent, which
        // is what the in-memory `put` already is. ⚠ Not an UPSERT: DuckDB and Postgres spell it differently
        // and this store must work unchanged on both.
        String delete = "DELETE FROM " + TABLE + " WHERE delivery_id = ?";
        String insert = "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?)";
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
            ps.executeUpdate();
            return receipt;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "could not insert delivery receipt " + receipt.deliveryId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Optional<DeliveryReceipt> get(String deliveryId) {
        String sql = "SELECT " + COLS + " FROM " + TABLE + " WHERE delivery_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deliveryId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read delivery receipt " + deliveryId + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read-merge-write on the store's monitor, mirroring the in-memory implementation exactly — the
     * merge lives in {@link DeliveryReceipt#withStatus}, so "first observation of a status wins" is one
     * rule in one place rather than a second copy in SQL.
     *
     * <p>⚠ An unknown delivery id returns empty and writes nothing. That is the contract, not a miss to
     * fix: receipts are prunable and providers retry a non-2xx forever, so a callback for a receipt this
     * store has already pruned must be accepted.
     */
    @Override
    public synchronized Optional<DeliveryReceipt> stamp(String deliveryId, DeliveryStatus status, long ts,
                                                        String providerRaw) {
        Optional<DeliveryReceipt> existing = get(deliveryId);
        if (existing.isEmpty()) return Optional.empty();
        DeliveryReceipt updated = existing.get().withStatus(status, ts, providerRaw);
        String sql = "UPDATE " + TABLE + " SET status_at = ?, provider_raw = ? WHERE delivery_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, statusJson(updated.statusAt()));
            ps.setString(2, updated.providerRaw());
            ps.setString(3, deliveryId);
            ps.executeUpdate();
            return Optional.of(updated);
        } catch (SQLException e) {
            throw new IllegalStateException("could not stamp delivery receipt " + deliveryId + ": "
                    + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<DeliveryReceipt> forNotification(String notificationId) {
        if (notificationId == null) return List.of();
        return query("SELECT " + COLS + " FROM " + TABLE
                + " WHERE notification_id = ? ORDER BY sent_at DESC", ps -> ps.setString(1, notificationId));
    }

    @Override
    public synchronized List<DeliveryReceipt> recent(int limit) {
        if (limit <= 0) return List.of();
        return query("SELECT " + COLS + " FROM " + TABLE + " ORDER BY sent_at DESC LIMIT ?",
                ps -> ps.setInt(1, limit));
    }

    @Override
    public synchronized int countPrunable(long cutoffMs) {
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE sent_at < ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoffMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count prunable receipts: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized int prune(long cutoffMs) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + TABLE + " WHERE sent_at < ?")) {
            ps.setLong(1, cutoffMs);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not prune receipts: " + e.getMessage(), e);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<DeliveryReceipt> query(String sql, Binder binder) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<DeliveryReceipt> out = new ArrayList<>();
                while (rs.next()) out.add(read(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read delivery receipts: " + e.getMessage(), e);
        }
    }

    private static DeliveryReceipt read(ResultSet rs) throws SQLException {
        return new DeliveryReceipt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), statusMap(rs.getString(6)), rs.getString(7), rs.getBoolean(8));
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
