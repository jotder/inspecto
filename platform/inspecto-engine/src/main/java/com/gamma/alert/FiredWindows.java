package com.gamma.alert;

import com.gamma.util.DuckDbUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The fired table of windowed Alert Rules (PA-9b, D-PA40/D-PA41): one row per (rule, key, window start) that has
 * raised. It is what makes a windowed Alert <b>effectively once</b>: a replayed slice, a re-read after a restart or a
 * later commit into the same window finds the row and raises nothing. Unlike the window buffer it is durable — a
 * DuckDB file the buffer's rebuild at boot never touches. Rows of windows that can never be evaluated again (closed
 * past their lateness) are purged, so it stays small.
 */
public final class FiredWindows implements AutoCloseable {

    private final Connection conn;

    /** A fired table in {@code dbFile} (created when absent), or in memory when {@code dbFile} is {@code null}. */
    public FiredWindows(Path dbFile) throws SQLException {
        try {
            if (dbFile != null && dbFile.getParent() != null) Files.createDirectories(dbFile.getParent());
        } catch (java.io.IOException e) {
            throw new SQLException("cannot create the fired table's folder: " + e.getMessage(), e);
        }
        conn = dbFile == null ? DuckDbUtil.openInMemory(null) : DuckDbUtil.openConnection(dbFile.toFile());
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS fired (rule VARCHAR NOT NULL, key_id VARCHAR NOT NULL, "
                    + "window_start_ms BIGINT NOT NULL, fired_at_ms BIGINT NOT NULL, PRIMARY KEY (rule, key_id, window_start_ms))");
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
    }

    /** Whether (rule, key, window start) has already fired. */
    public synchronized boolean contains(String rule, String keyId, long windowStartMs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM fired WHERE rule = ? AND key_id = ? AND window_start_ms = ?")) {
            ps.setString(1, rule);
            ps.setString(2, keyId);
            ps.setLong(3, windowStartMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Record (rule, key, window start) as fired; {@code false} when it already was. */
    public synchronized boolean record(String rule, String keyId, long windowStartMs, long nowMs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO fired VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, rule);
            ps.setString(2, keyId);
            ps.setLong(3, windowStartMs);
            ps.setLong(4, nowMs);
            return ps.executeUpdate() == 1;
        }
    }

    /** Drop the rule's rows whose window started before {@code windowStartMs}; returns how many. */
    public synchronized int purgeBefore(String rule, long windowStartMs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM fired WHERE rule = ? AND window_start_ms < ?")) {
            ps.setString(1, rule);
            ps.setLong(2, windowStartMs);
            return ps.executeUpdate();
        }
    }

    /**
     * The keys (their {@link AlertService#keyId}) of {@code rule} that still hold a fired row: a Window they fired in has
     * not closed yet ({@link #purgeBefore} drops closed ones). PA-9d: what keeps a key open on the rule's one Alert.
     */
    public synchronized java.util.Set<String> keyIds(String rule) throws SQLException {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT key_id FROM fired WHERE rule = ? ORDER BY key_id")) {
            ps.setString(1, rule);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** How many rows the table holds. */
    public synchronized long size() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM fired")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        conn.close();
    }
}
