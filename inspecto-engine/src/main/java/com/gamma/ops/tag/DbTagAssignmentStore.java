package com.gamma.ops.tag;

import com.gamma.ops.AnnotationKinds;
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
 * Database-backed {@link TagAssignmentStore} — the durable cross-entity tag graph (BACKLOG D7). The twin
 * of {@code DbNoteStore}: plain JDBC over the already-bundled DuckDB (no new dependency), or a
 * {@code jdbc:postgresql://…} URL for a distributed deployment. All access is serialised on a single
 * shared {@link Connection}; {@link #close()} closes it.
 *
 * <p>The composite primary key {@code (tag, target_kind, target_id)} is what makes {@link #add}
 * idempotent and {@link #rename} self-merging — the database enforces edge identity rather than the
 * application checking first and racing.
 *
 * @since 4.9.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class DbTagAssignmentStore extends com.gamma.ops.AbstractJdbcStore implements TagAssignmentStore {

    private static final Logger log = LoggerFactory.getLogger(DbTagAssignmentStore.class);

    private static final String TABLE = "inspecto_ops_tag_assignments";
    private static final String COLS = "tag, target_kind, target_id, actor, created_at";

    /** Wrap an already-open JDBC connection (any engine); the schema is created if absent. */
    public DbTagAssignmentStore(Connection conn) {
        super(conn, "tag-assignments", "Tag assignments", TABLE, "tag");
        initSchema();
    }

    /** Open by JDBC URL via {@link JdbcDrivers#connect(String, String, String)}. */
    public static DbTagAssignmentStore open(String url, String user, String pass) throws SQLException {
        return new DbTagAssignmentStore(JdbcDrivers.connect(url, user, pass));
    }

    @Override
    public synchronized TagAssignment add(TagAssignment a) {
        // Idempotent by the primary key. Checked-then-inserted rather than relying on a dialect-specific
        // upsert: DuckDB and Postgres spell ON CONFLICT compatibly today, but the read is needed anyway
        // to return the ALREADY-STORED edge (with its original createdAt and actor) rather than the
        // caller's newer one — re-tagging must not silently rewrite who applied it and when.
        TagAssignment existing = find(a.tag(), a.targetKind(), a.targetId());
        if (existing != null) return existing;
        String sql = "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a.tag());
            ps.setString(2, a.targetKind());
            ps.setString(3, a.targetId());
            ps.setString(4, a.actor());
            ps.setLong(5, a.createdAt());
            ps.executeUpdate();
            return a;
        } catch (SQLException e) {
            // A concurrent insert of the same edge loses the race on the primary key. That is the
            // idempotent outcome, not a failure — return whatever is stored.
            TagAssignment raced = find(a.tag(), a.targetKind(), a.targetId());
            if (raced != null) return raced;
            throw new IllegalStateException("could not apply tag " + a.tag() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean remove(String tag, String targetKind, String targetId) {
        String tk = AnnotationKinds.require(targetKind);
        String sql = "DELETE FROM " + TABLE + " WHERE tag = ? AND target_kind = ? AND target_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tag == null ? null : tag.trim());
            ps.setString(2, tk);
            ps.setString(3, targetId == null ? null : targetId.trim());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("could not remove tag " + tag + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<String> tagsOf(String targetKind, String targetId) {
        String tk = AnnotationKinds.require(targetKind);
        String sql = "SELECT DISTINCT tag FROM " + TABLE
                + " WHERE target_kind = ? AND target_id = ? ORDER BY tag";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tk);
            ps.setString(2, targetId == null ? null : targetId.trim());
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            log.warn("tag lookup failed for {}/{}: {}", tk, targetId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public synchronized List<TagAssignment> forTag(String tag) {
        String sql = "SELECT " + COLS + " FROM " + TABLE + " WHERE tag = ? ORDER BY created_at DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tag == null ? null : tag.trim());
            return query(ps);
        } catch (SQLException e) {
            log.warn("tag member query failed for {}: {}", tag, e.getMessage());
            return List.of();
        }
    }

    @Override
    public synchronized List<TagAssignment> all() {
        String sql = "SELECT " + COLS + " FROM " + TABLE + " ORDER BY created_at DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            return query(ps);
        } catch (SQLException e) {
            log.warn("tag assignment scan failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public synchronized int rename(String from, String to) {
        String f = from == null ? null : from.trim();
        String t = to == null ? null : to.trim();
        if (f == null || t == null || f.isBlank() || t.isBlank() || f.equals(t)) return 0;
        List<TagAssignment> moving = forTag(f);
        if (moving.isEmpty()) return 0;
        // Delete-then-insert rather than a bulk UPDATE: an UPDATE would violate the primary key for any
        // target already carrying `to`, failing the whole rename. Merging is the correct outcome there
        // (the triple is the edge identity), and add() already merges.
        // One transaction: the delete and the re-inserts are a single rename. Run as separate
        // auto-commit statements, a failure between them (connection loss, disk) leaves the
        // assignments deleted and never re-added — the rename would silently destroy every edge
        // carrying the old tag rather than moving it.
        try {
            conn.setAutoCommit(false);
            try {
                removeTag(f);
                for (TagAssignment e : moving)
                    add(new TagAssignment(t, e.targetKind(), e.targetId(), e.actor(), e.createdAt()));
                conn.commit();
            } catch (RuntimeException | SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not rename tag " + f + " to " + t + ": " + e.getMessage(), e);
        }
        return moving.size();
    }

    @Override
    public synchronized int removeTag(String tag) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + TABLE + " WHERE tag = ?")) {
            ps.setString(1, tag == null ? null : tag.trim());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not clear tag " + tag + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized int removeAllForTarget(String targetKind, String targetId) {
        String tk = AnnotationKinds.require(targetKind);
        String sql = "DELETE FROM " + TABLE + " WHERE target_kind = ? AND target_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tk);
            ps.setString(2, targetId == null ? null : targetId.trim());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not clear tags on " + tk + "/" + targetId
                    + ": " + e.getMessage(), e);
        }
    }

    // ── schema + helpers ─────────────────────────────────────────────────────────

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "tag VARCHAR, target_kind VARCHAR, target_id VARCHAR, actor VARCHAR, "
                    + "created_at BIGINT, PRIMARY KEY (tag, target_kind, target_id))");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise tag assignment DB schema", e);
        }
    }

    private TagAssignment find(String tag, String targetKind, String targetId) {
        String sql = "SELECT " + COLS + " FROM " + TABLE
                + " WHERE tag = ? AND target_kind = ? AND target_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tag);
            ps.setString(2, targetKind);
            ps.setString(3, targetId);
            List<TagAssignment> hit = query(ps);
            return hit.isEmpty() ? null : hit.getFirst();
        } catch (SQLException e) {
            return null;
        }
    }

    private static List<TagAssignment> query(PreparedStatement ps) throws SQLException {
        List<TagAssignment> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapRow(rs));
        }
        return out;
    }

    private static TagAssignment mapRow(ResultSet rs) throws SQLException {
        return new TagAssignment(
                rs.getString("tag"),
                rs.getString("target_kind"),
                rs.getString("target_id"),
                rs.getString("actor"),
                rs.getLong("created_at"));
    }
}
