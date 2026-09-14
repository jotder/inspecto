package com.gamma.service;

import com.gamma.util.ConnectionSource;
import com.gamma.util.JdbcDrivers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>The fleet-wide roster of declared inboxes — scale-out plan §5.3 (`INBOX-REGISTRY-CROSS-POD-1`).</b>
 * Every pod publishes the {@code dirs.poll} its own Spaces declare; every pod then reads the whole table, so
 * {@link SpaceInboxAudit} can compare Spaces it does <b>not</b> host against the ones it does.
 *
 * <p>🔴 <b>Why a shared table is the only way to see this.</b> Two Spaces on one inbox is silent
 * double-ingestion — {@code MarkerManager} marks a file only <em>after</em> a batch commits, so nothing
 * claims it before it is read. {@link SpaceInboxAudit} already catches the same-pod case, but once Spaces are
 * partitioned ({@link SpacePartition}) the dangerous pairing is two Spaces on <b>different</b> pods, and no
 * pod can see another's config. This table is the only place that knowledge can meet.
 *
 * <p>⚠ <b>Detection, never prevention</b> (operator decision 2026-09-12). It turns an undetectable collision
 * into a loud one. Prevention would mean requiring {@code dirs.poll} to resolve under its declaring Space's
 * root, which was <b>refused</b>: an external vendor drop directory outside the Space tree is legitimate,
 * common, and already in use.
 *
 * <p>⚠ <b>Keyed by Space, not by pod, and that is what makes it self-healing.</b> {@link #publish} replaces
 * every row a Space owns, so moving a Space to another pod rewrites its rows on that pod's next boot rather
 * than leaving a ghost that reports a collision with itself. ⛔ Keying on the pod instead would do exactly
 * that — the detector's worst failure is a false finding, because an operator who learns to ignore it has
 * lost the real one too.
 *
 * <p>🔴 <b>Known gap: a Space DELETED outright leaves its rows behind</b>, because deletion happens where
 * nothing publishes. Its stale rows can raise a finding against a Space that no longer exists. The rows carry
 * {@code pod} and {@code declared_at} so such a finding is diagnosable; clearing them is manual
 * (`DELETE FROM inbox_registry WHERE space = …`). ⛔ Do not "fix" this with a TTL — an inbox declaration has
 * no natural lifetime, and a pod that is merely down would then disappear from the roster it exists to fill.
 *
 * <p><b>Fail-open, like every other audit seam.</b> A write or read failure is logged and swallowed: an audit
 * must never be the thing that fails a boot which otherwise succeeded. A failed read degrades to the
 * this-pod-only roster — that is, to the behaviour before this class existed.
 */
final class DbInboxRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DbInboxRegistry.class);
    private static final String TABLE = "inbox_registry";

    private final ConnectionSource src;
    private final String pod;

    DbInboxRegistry(Connection conn, String pod) {
        this(JdbcDrivers.source(conn), pod);
    }

    DbInboxRegistry(ConnectionSource src, String pod) {
        this.src = src;
        this.pod = pod;
        initSchema();
    }

    /** Open the registry over {@code url}. {@code pod} names the publisher, for diagnosis only. */
    static DbInboxRegistry open(String url, String user, String pass, String pod) throws SQLException {
        return new DbInboxRegistry(JdbcDrivers.source(url, user, pass, "inbox-registry"),
                pod == null || pod.isBlank() ? defaultPod() : pod);
    }

    /** The host's pod name when the orchestrator sets one, else a stable placeholder. ⚠ Not an identity to
     *  key anything on — it is a label on a row, so an operator reading a finding knows who wrote it. */
    static String defaultPod() {
        String host = System.getenv("HOSTNAME");
        return host == null || host.isBlank() ? "pod" : host;
    }

    /**
     * Replace every declaration this Space owns with {@code declarations}.
     *
     * <p>⚠ Must be called for a hosted Space even when it declares <b>nothing</b> — that is precisely when
     * the delete matters, because a pipeline whose {@code dirs.poll} was removed must stop being reported.
     */
    void publish(String space, List<SpaceInboxAudit.InboxDecl> declarations) {
        try {
            // ⚠ ONE borrow: the DELETE, the INSERTs and the commit are the replace-in-place this method
            // promises — on a pool, split across borrows they would be separate transactions and a failed
            // INSERT half would leave the Space with NO rows at all.
            src.run(conn -> {
                boolean auto = true;
                try {
                    auto = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try (PreparedStatement del = conn.prepareStatement(
                            "DELETE FROM " + TABLE + " WHERE space = ?")) {
                        del.setString(1, space);
                        del.executeUpdate();
                    }
                    try (PreparedStatement ins = conn.prepareStatement("INSERT INTO " + TABLE
                            + " (space, pipeline, poll_dir, pod, declared_at) VALUES (?,?,?,?,?)")) {
                        long now = System.currentTimeMillis();
                        for (SpaceInboxAudit.InboxDecl d : declarations) {
                            if (d.pollDir() == null || d.pollDir().isBlank()) continue;
                            ins.setString(1, space);
                            ins.setString(2, d.pipeline());
                            ins.setString(3, d.pollDir());
                            ins.setString(4, pod);
                            ins.setLong(5, now);
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    rollbackQuietly(conn);
                    log.warn("Could not publish space '{}' to the shared inbox registry — other pods will not see "
                            + "its inboxes: {}", space, e.getMessage());
                } finally {
                    try {
                        conn.setAutoCommit(auto);
                    } catch (SQLException ignored) {
                        // the connection is already unusable; the next call logs it
                    }
                }
            });
        } catch (SQLException e) {
            // Only a failed BORROW reaches here — there is no connection to roll back on.
            log.warn("Could not publish space '{}' to the shared inbox registry — other pods will not see "
                    + "its inboxes: {}", space, e.getMessage());
        }
    }

    /**
     * Every inbox declared by <b>any</b> pod, this one included.
     *
     * @return the roster, or an empty list if it cannot be read — the caller then audits what it hosts, which
     *         is the pre-registry behaviour and never a claim that the fleet is healthy
     */
    List<SpaceInboxAudit.InboxDecl> declarations() {
        List<SpaceInboxAudit.InboxDecl> out = new ArrayList<>();
        try {
            src.run(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT space, pipeline, poll_dir FROM " + TABLE + " ORDER BY space, pipeline");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        out.add(new SpaceInboxAudit.InboxDecl(
                                rs.getString("space"), rs.getString("pipeline"), rs.getString("poll_dir")));
                }
            });
        } catch (SQLException e) {
            log.warn("Could not read the shared inbox registry — auditing only the Spaces this pod hosts: {}",
                    e.getMessage());
            return List.of();
        }
        return out;
    }

    private void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // nothing to salvage; publish() has already logged the cause
        }
    }

    private void initSchema() {
        try {
            src.run(conn -> {
                try (Statement st = conn.createStatement()) {
                    // ⚠ PRIMARY KEY (space, pipeline): a pipeline declares exactly one inbox, and a pipeline name is
                    // unique only WITHIN a Space. ⛔ NOT keyed on poll_dir — two Spaces sharing a directory is the
                    // very thing this table exists to record, so the key must let that row pair exist.
                    st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                            + "space VARCHAR, pipeline VARCHAR, poll_dir VARCHAR, pod VARCHAR, declared_at BIGINT, "
                            + "PRIMARY KEY (space, pipeline))");
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise the inbox-registry schema", e);
        }
    }

    @Override
    public void close() {
        try {
            src.close();
        } catch (RuntimeException e) {
            log.warn("Error closing the inbox-registry connection: {}", e.getMessage());
        }
    }
}
