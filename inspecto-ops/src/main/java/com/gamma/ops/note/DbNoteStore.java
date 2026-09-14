package com.gamma.ops.note;

import com.gamma.util.AbstractJdbcStore;

import com.gamma.objects.AnnotationKinds;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Database-backed {@link NoteStore} — the durable evidence/notes store. The twin of
 * {@link com.gamma.ops.DbObjectStore} (it reuses the same attribute-JSON idiom) but append-only like
 * {@link com.gamma.ops.link.DbLinkStore}: plain JDBC over the already-bundled DuckDB (no new
 * dependency), or a {@code jdbc:postgresql://…} URL for a distributed deployment. Each operation
 * borrows a {@link Connection} from a {@link ConnectionSource} for its duration and returns it;
 * {@link #close()} closes the source.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class DbNoteStore extends AbstractJdbcStore implements NoteStore {

    private static final Logger log = LoggerFactory.getLogger(DbNoteStore.class);

    private static final String TABLE = "inspecto_ops_notes";
    private static final String COLS =
            "id, object_id, target_kind, kind, author, body, attributes, created_at";

    /** Wrap an already-open JDBC connection (any engine); the schema is created if absent. */
    public DbNoteStore(Connection conn) {
        this(JdbcDrivers.source(conn));
    }

    /** Borrow from {@code src} per operation; the schema is created if absent. */
    public DbNoteStore(ConnectionSource src) {
        super(src, "notes", "Notes", TABLE, "note");
        initSchema();
    }

    /**
     * Open a note DB by JDBC URL via {@link JdbcDrivers#connect(String, String, String)}, which
     * registers the bundled driver matching the scheme ({@code jdbc:duckdb:} primary, {@code jdbc:postgresql:}).
     */
    public static DbNoteStore open(String url, String user, String pass) throws SQLException {
        return new DbNoteStore(JdbcDrivers.source(url, user, pass, "notes"));
    }

    @Override
    public ObjectNote add(ObjectNote note) {
        String sql = "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?)";
        try {
            return withConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, note.id());
                    ps.setString(2, note.objectId());
                    ps.setString(3, note.targetKind());
                    ps.setString(4, note.kind().name());
                    ps.setString(5, note.author());
                    ps.setString(6, note.body());
                    ps.setString(7, JsonAttributes.toJson(note.attributes()));
                    ps.setLong(8, note.createdAt());
                    ps.executeUpdate();
                    return note;
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("could not insert note " + note.id() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<ObjectNote> forTarget(String targetKind, String targetId, NoteKind kind) {
        String tk = targetKind == null || targetKind.isBlank()
                ? AnnotationKinds.OBJECT : targetKind.trim().toLowerCase(java.util.Locale.ROOT);
        String sql = "SELECT " + COLS + " FROM " + TABLE + " WHERE object_id = ? AND target_kind = ?"
                + (kind == null ? "" : " AND kind = ?") + " ORDER BY created_at DESC";
        try {
            return withConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, targetId);
                    ps.setString(2, tk);
                    if (kind != null) ps.setString(3, kind.name());
                    List<ObjectNote> out = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(mapRow(rs));
                    }
                    return out;
                }
            });
        } catch (SQLException e) {
            log.warn("note query failed for {}/{}: {}", tk, targetId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public int deleteForTarget(String targetKind, String targetId) {
        String tk = targetKind == null || targetKind.isBlank()
                ? AnnotationKinds.OBJECT : targetKind.trim().toLowerCase(java.util.Locale.ROOT);
        String sql = "DELETE FROM " + TABLE + " WHERE object_id = ? AND target_kind = ?";
        try {
            return withConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, targetId);
                    ps.setString(2, tk);
                    return ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            // Throws, unlike the read above which logs and degrades to an empty list: a cascade that
            // quietly "deleted 0" would orphan the rows the caller is purging an object to remove.
            throw new IllegalStateException("could not delete notes for " + tk + "/" + targetId
                    + ": " + e.getMessage(), e);
        }
    }

    // ── schema + helpers ─────────────────────────────────────────────────────────

    private void initSchema() {
        try {
            runConn(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                            + "id VARCHAR PRIMARY KEY, object_id VARCHAR, kind VARCHAR, author VARCHAR, "
                            + "body VARCHAR, attributes VARCHAR, created_at BIGINT)");
                    // D10 migration: a notes table created before the target-kind dimension gains the column in
                    // place (the ACQ-7 idiom in DbAcquisitionLedger — supported by bundled DuckDB and Postgres).
                    // Every pre-D10 note targets an OperationalObject, so existing rows backfill to 'object';
                    // the same UPDATE also normalises a NULL written by any older writer.
                    st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS target_kind VARCHAR");
                    st.execute("UPDATE " + TABLE + " SET target_kind = '" + AnnotationKinds.OBJECT + "' "
                            + "WHERE target_kind IS NULL OR target_kind = ''");
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise note DB schema", e);
        }
    }

    private static ObjectNote mapRow(ResultSet rs) throws SQLException {
        return new ObjectNote(
                rs.getString("id"),
                rs.getString("object_id"),
                rs.getString("target_kind"),
                NoteKind.valueOf(rs.getString("kind")),
                rs.getString("author"),
                rs.getString("body"),
                JsonAttributes.fromJson(rs.getString("attributes")),
                rs.getLong("created_at"));
    }
}
