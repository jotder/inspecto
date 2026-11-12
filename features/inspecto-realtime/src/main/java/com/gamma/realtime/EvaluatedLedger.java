package com.gamma.realtime;

import com.gamma.util.DuckDbUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which committed files a window group has evaluated (PA-9d; owed by PA-9b's verification): {@code load()} at boot only
 * buffers, so with {@code distinctOn} a Consignment committed but never evaluated - the server stopped between its
 * commit and its evaluation, or the Add-on was not entitled then - would be buffered and never judged. The boot
 * rebuild therefore replays every committed file this ledger does not hold, committed after the group was armed.
 *
 * <p>Two tables in one DuckDB file: {@code armed(grp, at_ms)}, the first time a group (Dataset, event-time column,
 * {@code distinctOn}) was built - files committed before it are history, loaded and never replayed; and
 * {@code evaluated(grp, path, at_ms)}, one row per evaluated file, pruned after {@link #KEEP_MS}. A file is evaluated
 * after its commit, so a pruned row's file is older than {@link RealtimeSession#REPLAY_HORIZON_MS} and is never a
 * replay candidate.
 */
final class EvaluatedLedger implements AutoCloseable {

    /** How long an evaluated row is kept: twice the replay horizon. */
    static final long KEEP_MS = 2 * RealtimeSession.REPLAY_HORIZON_MS;

    private final Connection conn;

    /** The ledger in {@code dbFile}, or in memory when {@code dbFile} is {@code null}. */
    EvaluatedLedger(Path dbFile) throws SQLException {
        try {
            if (dbFile != null && dbFile.getParent() != null) Files.createDirectories(dbFile.getParent());
        } catch (java.io.IOException e) {
            throw new SQLException("cannot create the evaluated ledger's folder: " + e.getMessage(), e);
        }
        conn = dbFile == null ? DuckDbUtil.openInMemory(null) : DuckDbUtil.openConnection(dbFile.toFile());
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS armed (grp VARCHAR PRIMARY KEY, at_ms BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS evaluated (grp VARCHAR NOT NULL, path VARCHAR NOT NULL, "
                    + "at_ms BIGINT NOT NULL, PRIMARY KEY (grp, path))");
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
    }

    /** When {@code group} was first armed; arms it at {@code nowMs} when it never was. */
    synchronized long armedAt(String group, long nowMs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO armed VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, group);
            ps.setLong(2, nowMs);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT at_ms FROM armed WHERE grp = ?")) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Every file {@code group} has evaluated (absolute, normalised paths). */
    synchronized Set<String> paths(String group) throws SQLException {
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT path FROM evaluated WHERE grp = ?")) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** Record {@code files} as evaluated by {@code group}. */
    synchronized void record(String group, List<Path> files, long nowMs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO evaluated VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            for (Path f : files) {
                ps.setString(1, group);
                ps.setString(2, key(f));
                ps.setLong(3, nowMs);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Drop {@code group}'s rows evaluated before {@code beforeMs}; returns how many. */
    synchronized int prune(String group, long beforeMs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM evaluated WHERE grp = ? AND at_ms < ?")) {
            ps.setString(1, group);
            ps.setLong(2, beforeMs);
            return ps.executeUpdate();
        }
    }

    /** The ledger's spelling of a file: absolute and normalised. */
    static String key(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    @Override
    public synchronized void close() throws SQLException {
        conn.close();
    }
}
