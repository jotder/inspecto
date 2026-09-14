package com.gamma.acquire;

import com.gamma.util.ConnectionSource;
import com.gamma.util.JdbcDrivers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Database-backed {@link AcquisitionLedger} — durable fingerprint repository over the already-bundled DuckDB
 * (no new dependency), or a {@code jdbc:postgresql://…} URL for a distributed deployment. Mirrors the OI store
 * pattern ({@link com.gamma.ops.note.DbNoteStore}): plain JDBC, a {@link Connection} borrowed from a
 * {@link ConnectionSource} for the duration of each operation, schema created on open. Lives in its <b>own</b> DB file (default
 * {@code inspecto-acquisition.db}) because a file DuckDB holds a single-writer lock and the status/object/link/
 * note stores already own theirs.
 *
 * <p><b>Upsert semantics:</b> the {@code (source_id, relative_path)} primary key holds one row per file; a new
 * fingerprint replaces the prior one (DELETE-then-INSERT in one transaction, on ONE borrowed connection) so a
 * re-uploaded/changed file's latest state is what later cycles compare against. The two statements are
 * committed together — a half-applied replace would drop the fingerprint and re-ingest the file. See
 * {@link #record}.
 */
public final class DbAcquisitionLedger implements AcquisitionLedger, com.gamma.util.BrowsableStore {

    private static final Logger log = LoggerFactory.getLogger(DbAcquisitionLedger.class);

    private static final String TABLE = "inspecto_acquisition_ledger";
    // "object_version" not "version" — the latter is dialect-risky (cf. the reserved-word bites: day/trigger).
    private static final String COLS = "source_id, relative_path, name, size, checksum, etag, object_version, last_modified, processed_at, status";
    private static final String WM_TABLE = "inspecto_acquisition_db_watermark";

    /** Where each operation borrows its connection; ⛔ never stash the borrowed {@link Connection}. */
    private final ConnectionSource src;

    // ── raw table browser seam (BrowsableStore) — read-only, one borrow per read ──
    @Override public String browseId() { return "acquire"; }
    @Override public String browseLabel() { return "Acquisition Ledger"; }
    @Override public java.util.List<String> browseTables() { return java.util.List.of(TABLE, WM_TABLE); }
    @Override public ConnectionSource browseSource() { return src; }

    /** Wrap an already-open JDBC connection (any engine); the schema is created if absent. */
    public DbAcquisitionLedger(Connection conn) {
        this(JdbcDrivers.source(conn));
    }

    /** Borrow from {@code src} per operation; the schema is created if absent. */
    public DbAcquisitionLedger(ConnectionSource src) {
        this.src = src;
        initSchema();
    }

    /** Open a ledger DB by JDBC URL, registering the matching driver (bundled DuckDB, or Postgres). */
    public static DbAcquisitionLedger open(String url, String user, String pass) throws SQLException {
        return new DbAcquisitionLedger(JdbcDrivers.source(url, user, pass, "acquire"));
    }

    @Override
    public Optional<LedgerEntry> find(String sourceId, String relativePath) {
        String sql = "SELECT " + COLS + " FROM " + TABLE + " WHERE source_id = ? AND relative_path = ?";
        try {
            return src.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, sourceId);
                    ps.setString(2, relativePath);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(mapRow(rs)) : Optional.<LedgerEntry>empty();
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("ledger lookup failed for {}/{}: {}", sourceId, relativePath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Replace this file's fingerprint, atomically.
     *
     * <p>The DELETE and the INSERT are one fact — "this file's fingerprint is now X" — so they run in one
     * transaction. Under autocommit they committed separately, and anything landing between them (a driver
     * error, a full disk, a JVM kill) left the row deleted and never re-inserted: the fingerprint was gone,
     * so the next acquisition cycle saw the file as NEW and re-ingested it, duplicating its records
     * downstream. Losing a fingerprint is worse than failing to update one, which is why this rolls back.
     *
     * <p>⚠ This is <b>not</b> a concurrency fix. The DELETE, the INSERT and the commit share ONE borrowed
     * connection, so they are one transaction; the window this closes is the crash/exception one, which is
     * live on DuckDB right now. Two concurrent writers CAN now interleave on a pooled source — the
     * primary key is what keeps the row single — see
     * {@code docs/superpower/postgres-multi-user-plan.md} P1.
     *
     * <p>⚠ Autocommit is restored in a {@code finally}: every other method borrowing this connection assumes
     * it is on.
     */
    @Override
    public void record(LedgerEntry e) {
        try {
            // ⚠ DELETE, INSERT and commit share ONE borrow: on a pool separate borrows are separate
            // connections, so the two statements would no longer be one transaction.
            src.run(conn -> {
                boolean priorAutoCommit = true;
                boolean autoCommitChanged = false;
                try {
                    priorAutoCommit = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    autoCommitChanged = true;
                    try (PreparedStatement del = conn.prepareStatement(
                            "DELETE FROM " + TABLE + " WHERE source_id = ? AND relative_path = ?")) {
                        del.setString(1, e.sourceId());
                        del.setString(2, e.relativePath());
                        del.executeUpdate();
                    }
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                        ins.setString(1, e.sourceId());
                        ins.setString(2, e.relativePath());
                        ins.setString(3, e.name());
                        ins.setLong(4, e.size());
                        ins.setString(5, e.checksum());
                        ins.setString(6, e.etag());
                        ins.setString(7, e.version());
                        ins.setLong(8, e.lastModified());
                        ins.setLong(9, e.processedAt());
                        ins.setString(10, e.status());
                        ins.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException ex) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackFailed) {
                        // The original cause is the useful one; a failed rollback must not mask it.
                        ex.addSuppressed(rollbackFailed);
                    }
                    throw ex;
                } finally {
                    if (autoCommitChanged) {
                        try {
                            conn.setAutoCommit(priorAutoCommit);
                        } catch (SQLException restoreFailed) {
                            log.warn("could not restore autocommit on the ledger connection: {}", restoreFailed.getMessage());
                        }
                    }
                }
            });
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "could not record ledger entry " + e.sourceId() + "/" + e.relativePath() + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public OptionalLong highWatermark(String sourceId) {
        String sql = "SELECT MAX(last_modified) FROM " + TABLE + " WHERE source_id = ?";
        try {
            return src.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, sourceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long v = rs.getLong(1);
                            if (!rs.wasNull()) return OptionalLong.of(v);   // MAX over an empty set is SQL NULL
                        }
                        return OptionalLong.empty();
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("watermark lookup failed for {}: {}", sourceId, e.getMessage());
            return OptionalLong.empty();   // degrade safely: an unavailable watermark just means no skipping
        }
    }

    @Override
    public Optional<String> dbWatermark(String sourceKey) {
        String sql = "SELECT watermark_value FROM " + WM_TABLE + " WHERE source_key = ?";
        try {
            return src.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, sourceKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.<String>empty();
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("db-watermark lookup failed for {}: {}", sourceKey, e.getMessage());
            return Optional.empty();   // degrade safely: an unavailable watermark just re-exports from the floor
        }
    }

    @Override
    public void recordDbWatermark(String sourceKey, String value) {
        if (value == null) return;
        try {
            // ⚠ The DELETE and the INSERT replace one watermark row — ONE borrow, or on a pool they land
            // on different connections and a concurrent reader can see the key missing entirely.
            src.run(conn -> {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM " + WM_TABLE + " WHERE source_key = ?")) {
                    del.setString(1, sourceKey);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO " + WM_TABLE + " (source_key, watermark_value, advanced_at) VALUES (?,?,?)")) {
                    ins.setString(1, sourceKey);
                    ins.setString(2, value);
                    ins.setLong(3, System.currentTimeMillis());
                    ins.executeUpdate();
                }
            });
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "could not record db watermark for " + sourceKey + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public int prune(long processedBefore, String sourceId) {
        String sql = "DELETE FROM " + TABLE + " WHERE processed_at < ?"
                + (sourceId != null ? " AND source_id = ?" : "");
        try {
            return src.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, processedBefore);
                    if (sourceId != null) ps.setString(2, sourceId);
                    return ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("ledger prune failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int countPrunable(long processedBefore, String sourceId) {
        String sql = "SELECT COUNT(*) FROM " + TABLE + " WHERE processed_at < ?"
                + (sourceId != null ? " AND source_id = ?" : "");
        try {
            return src.with(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, processedBefore);
                    if (sourceId != null) ps.setString(2, sourceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("ledger prune count failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int renameSource(String oldSourceId, String newSourceId) {
        try {
            // ⚠ Both UPDATEs move the same source and share ONE borrow: on a pool they would otherwise
            // land on different connections, so a reader could see the two tables disagree.
            return src.with(conn -> {
                int moved;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + TABLE + " SET source_id = ? WHERE source_id = ?")) {
                    ps.setString(1, newSourceId);
                    ps.setString(2, oldSourceId);
                    moved = ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + WM_TABLE + " SET source_key = ? WHERE source_key = ?")) {
                    ps.setString(1, newSourceId);
                    ps.setString(2, oldSourceId);
                    ps.executeUpdate();
                }
                return moved;
            });
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "could not rename ledger source " + oldSourceId + " -> " + newSourceId + ": " + ex.getMessage(), ex);
        }
    }

    /** CHECKPOINT + VACUUM, each best-effort — Postgres restricts CHECKPOINT to superusers, DuckDB allows both. */
    @Override
    public void maintenance() {
        for (String stmt : new String[]{"CHECKPOINT", "VACUUM"}) {
            try {
                src.run(conn -> {
                    try (Statement st = conn.createStatement()) {
                        st.execute(stmt);
                    }
                });
            } catch (SQLException e) {
                log.warn("ledger maintenance: {} failed (continuing): {}", stmt, e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        try {
            src.close();
        } catch (RuntimeException e) {
            log.warn("Error closing acquisition-ledger DB connection source: {}", e.getMessage());
        }
    }

    // ── schema + helpers ─────────────────────────────────────────────────────────

    private void initSchema() {
        try {
            src.run(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                            + "source_id VARCHAR, relative_path VARCHAR, name VARCHAR, size BIGINT, "
                            + "checksum VARCHAR, etag VARCHAR, object_version VARCHAR, "
                            + "last_modified BIGINT, processed_at BIGINT, status VARCHAR, "
                            + "PRIMARY KEY (source_id, relative_path))");
                    // ACQ-7 migration: a ledger created before the etag/version dimensions gains the columns in place
                    // (supported by both bundled DuckDB and Postgres; existing rows read back NULL = "listing carried none").
                    st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS etag VARCHAR");
                    st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS object_version VARCHAR");
                    // Row-level DB-export watermark (resumable incremental export): one opaque value per source key,
                    // advanced only after a batch commits. Its own table, not a fake row in the fingerprint table.
                    st.execute("CREATE TABLE IF NOT EXISTS " + WM_TABLE + " ("
                            + "source_key VARCHAR, watermark_value VARCHAR, advanced_at BIGINT, "
                            + "PRIMARY KEY (source_key))");
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise acquisition-ledger DB schema", e);
        }
    }

    private static LedgerEntry mapRow(ResultSet rs) throws SQLException {
        return new LedgerEntry(
                rs.getString("source_id"),
                rs.getString("relative_path"),
                rs.getString("name"),
                rs.getLong("size"),
                rs.getString("checksum"),
                rs.getString("etag"),
                rs.getString("object_version"),
                rs.getLong("last_modified"),
                rs.getLong("processed_at"),
                rs.getString("status"));
    }
}
