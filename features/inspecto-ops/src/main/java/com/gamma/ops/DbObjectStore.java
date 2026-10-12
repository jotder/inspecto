package com.gamma.ops;

import com.gamma.workflow.ObjectType;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Database-backed {@link ObjectStore} — the durable Layer-2 store for mutable operational objects.
 * Deliberately the twin of {@link com.gamma.service.DbStatusStore}: plain JDBC over portable SQL, the
 * <b>primary engine is the already-bundled DuckDB</b> (so the object store adds <em>no</em> new
 * dependency), and a {@code jdbc:postgresql://…} URL with the PG driver on the classpath serves a
 * future distributed deployment. Pick the engine by JDBC URL via {@link #open(String, String, String)}.
 *
 * <p>Unlike the status store (a DELETE-then-INSERT projection of immutable audit), objects are the
 * source of truth and they <b>mutate</b>, so this store does a real {@code UPDATE} on a status change.
 * Each operation borrows a {@link Connection} from a {@link ConnectionSource} for its duration and
 * returns it (a JDBC connection is not thread-safe); {@link #close()} closes the source.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class DbObjectStore extends AbstractJdbcStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(DbObjectStore.class);

    private static final String TABLE = "inspecto_ops_objects";
    /**
     * Derived side table: one narrow row per entity-bearing attribute ({@code key.*} and {@code entityKey}) of every object,
     * the lower-cased value, plus one placeholder row (empty key) per object so "already indexed" is a single anti-join.
     */
    private static final String REF_TABLE = "inspecto_ops_object_entity_ref";
    /** {@code owner} is quoted — it is a reserved word in some SQL dialects. */
    private static final String COLS = "id, object_type, title, description, status, severity, priority, "
            + "\"owner\", assignee, correlation_id, attributes, created_at, updated_at, closed_at, version";

    /** Wrap an already-open JDBC connection (any engine); the schema is created if absent. */
    public DbObjectStore(Connection conn) {
        this(JdbcDrivers.source(conn));
    }

    /** Borrow from {@code src} per operation; the schema is created if absent. */
    public DbObjectStore(ConnectionSource src) {
        super(src, "objects", "Objects", TABLE, "object");
        initSchema();
    }

    /**
     * Open an object DB by JDBC URL via {@link JdbcDrivers#connect(String, String, String)}, which
     * registers the bundled driver matching the scheme ({@code jdbc:duckdb:} primary,
     * {@code jdbc:postgresql:} deployment-supplied).
     *
     * @param url  JDBC URL (e.g. {@code jdbc:duckdb:inspecto-ops.db})
     * @param user username, or {@code null}
     * @param pass password, or {@code null}
     */
    public static DbObjectStore open(String url, String user, String pass) throws SQLException {
        return new DbObjectStore(JdbcDrivers.source(url, user, pass, "objects"));
    }

    @Override
    public OperationalObject create(OperationalObject obj) {
        String sql = "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)";
        try {
            return withConn(conn0 -> tx(conn0, conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    bindAll(ps, obj);
                    ps.executeUpdate();
                    writeRefs(conn, obj);
                    return obj.withVersion(0);
                }
            }));
        } catch (SQLException e) {
            // A duplicate id trips the primary-key constraint — surface it as the SPI's contract type.
            if (get(obj.id()).isPresent())
                throw new IllegalStateException("object already exists: " + obj.id());
            throw new IllegalStateException("could not insert object " + obj.id() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<OperationalObject> get(String id) {
        try {
            return withConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT " + COLS + " FROM " + TABLE + " WHERE id = ?")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(mapRow(rs)) : Optional.<OperationalObject>empty();
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("object get failed for {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public OperationalObject update(OperationalObject obj) {
        if (obj.isInert()) throw new InertObjectException(obj.id(), obj.typeName());   // never rewrite a row of an unknown type
        String sql = "UPDATE " + TABLE + " SET object_type=?, title=?, description=?, status=?, "
                + "severity=?, priority=?, \"owner\"=?, assignee=?, correlation_id=?, attributes=?, "
                + "created_at=?, updated_at=?, closed_at=?, version=version+1 WHERE id=? AND version=?";
        try {
            return withConn(conn0 -> tx(conn0, conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    // Same column order as bindAll minus the leading id, then id as the WHERE param last.
                    ps.setString(1, obj.objectType().name());
                    ps.setString(2, obj.title());
                    ps.setString(3, obj.description());
                    ps.setString(4, obj.status());
                    ps.setString(5, obj.severity());
                    ps.setString(6, obj.priority());
                    ps.setString(7, obj.owner());
                    ps.setString(8, obj.assignee());
                    ps.setString(9, obj.correlationId());
                    ps.setString(10, JsonAttributes.toJson(obj.attributes()));
                    ps.setLong(11, obj.createdAt());
                    ps.setLong(12, obj.updatedAt());
                    ps.setLong(13, obj.closedAt());
                    ps.setString(14, obj.id());
                    ps.setLong(15, obj.version());
                    if (ps.executeUpdate() == 0) {
                        // Zero rows: the id is gone, or the row moved past the version the caller read.
                        try (PreparedStatement probe = conn.prepareStatement("SELECT 1 FROM " + TABLE + " WHERE id = ?")) {
                            probe.setString(1, obj.id());
                            try (ResultSet rs = probe.executeQuery()) {
                                if (!rs.next()) throw new NoSuchElementException("no object with id '" + obj.id() + "'");
                            }
                        }
                        throw new ObjectVersionConflictException(obj.id(), obj.version());
                    }
                    writeRefs(conn, obj);
                    return obj.withVersion(obj.version() + 1);
                }
            }));
        } catch (SQLException e) {
            throw new IllegalStateException("could not update object " + obj.id() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String id) {
        try {
            runConn(conn0 -> tx(conn0, conn -> {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
                    ps.setString(1, id);
                    if (ps.executeUpdate() == 0)
                        throw new NoSuchElementException("no object with id '" + id + "'");
                }
                deleteRefs(conn, id);
                return null;
            }));
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete object " + id + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<OperationalObject> query(ObjectQuery q) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (q.objectType() != null) { where.add("object_type = ?"); params.add(q.objectType().name()); }
        ciEquals(where, params, "status", q.status());
        ciEquals(where, params, "severity", q.severity());
        ciEquals(where, params, "assignee", q.assignee());
        ciEquals(where, params, "\"owner\"", q.owner());
        if (q.correlationId() != null) { where.add("correlation_id = ?"); params.add(q.correlationId()); }
        // closed_at = 0 means "not closed" — a reopened object must never satisfy a retention cutoff.
        if (q.closedBefore() > 0) { where.add("closed_at > 0 AND closed_at < ?"); params.add(q.closedBefore()); }
        if (q.openOnly()) where.add("closed_at = 0");
        if (q.textContains() != null) {
            where.add("(LOWER(title) LIKE ? OR LOWER(description) LIKE ?)");
            String like = "%" + q.textContains().toLowerCase(Locale.ROOT) + "%";
            params.add(like);
            params.add(like);
        }
        String sql = "SELECT " + COLS + " FROM " + TABLE
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                // id breaks created_at ties so consecutive OFFSET pages never overlap or skip (ObjectService#allMatching)
                + " ORDER BY created_at " + (q.oldestFirst() ? "ASC, id ASC" : "DESC, id DESC") + " LIMIT ? OFFSET ?";
        List<OperationalObject> out = new ArrayList<>();
        try {
            runConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int i = 1;
                    for (Object p : params) ps.setObject(i++, p);
                    ps.setInt(i++, q.limit());
                    ps.setInt(i, q.offset());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(mapRow(rs));
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("object query failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Portable across DuckDB and PostgreSQL, so no JSON operator: the attribute column is the compact
     * JSON {@link JsonAttributes} writes, and each wanted entry is prefiltered by {@code LIKE} on the exact
     * fragment that same encoder produces for it ({@code "key":"value"}), then re-checked exactly on the
     * decoded map — the {@code LIKE} only narrows, it never decides.
     */
    @Override
    public List<OperationalObject> findByAttributes(ObjectType type, Map<String, String> attributes, int limit) {
        List<String> where = new ArrayList<>(List.of("object_type = ?"));
        List<Object> params = new ArrayList<>(List.of(type.name()));
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            String pair = JsonAttributes.toJson(Map.of(e.getKey(), e.getValue()));
            String fragment = pair.substring(1, pair.length() - 1);
            where.add("attributes LIKE ? ESCAPE '\\'");
            params.add("%" + fragment.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String sql = "SELECT " + COLS + " FROM " + TABLE + " WHERE " + String.join(" AND ", where)
                + " ORDER BY created_at ASC";
        List<OperationalObject> out = new ArrayList<>();
        try {
            runConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int i = 1;
                    for (Object p : params) ps.setObject(i++, p);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next() && out.size() < limit) {
                            OperationalObject o = mapRow(rs);
                            if (attributes.entrySet().stream()
                                    .allMatch(w -> w.getValue().equals(o.attributes().get(w.getKey()))))
                                out.add(o);
                        }
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("object attribute lookup failed: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Index-assisted: the fragment is matched against the narrow {@value #REF_TABLE} side table (only the entity
     * reference values, lower-cased) instead of the whole attribute JSON of every object, and the object rows are then
     * fetched by id in the same newest-first order. Candidates are therefore objects whose {@code key.*} /
     * {@code entityKey} VALUE contains the fragment - a subset of the old JSON-text scan (which also matched keys and
     * unrelated attributes); the caller's exact re-check only ever accepted those, so results are unchanged, and the
     * {@code limit} window now counts only real candidates. What remains O(n): a substring {@code LIKE} over the side
     * table (rows = reference attributes, not objects x full attribute bag).
     */
    @Override
    public List<OperationalObject> findByAttributeFragment(java.util.Collection<ObjectType> types, String fragment, int limit) {
        if (types.isEmpty()) return List.of();
        String in = String.join(", ", java.util.Collections.nCopies(types.size(), "?"));
        String sql = "SELECT " + COLS + " FROM " + TABLE + " WHERE object_type IN (" + in + ")"
                + " AND id IN (SELECT object_id FROM " + REF_TABLE + " WHERE value_lc LIKE ? ESCAPE '\\')"
                + " ORDER BY created_at DESC, id DESC LIMIT ?";
        String like = "%" + fragment.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<OperationalObject> out = new ArrayList<>();
        try {
            runConn(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int i = 1;
                    for (ObjectType t : types) ps.setString(i++, t.name());
                    ps.setString(i++, like);
                    ps.setInt(i, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(mapRow(rs));
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("object attribute scan failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** Replace {@code o}'s side-table rows (idempotent): a placeholder row plus one per entity-bearing attribute. */
    private static void writeRefs(Connection conn, OperationalObject o) throws SQLException {
        deleteRefs(conn, o.id());
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + REF_TABLE + " (object_id, attr_key, value_lc) VALUES (?,?,?)")) {
            for (Map.Entry<String, String> a : o.attributes().entrySet()) {
                String k = a.getKey(), v = a.getValue();
                if (v == null || v.isBlank() || !(k.startsWith("key.") || k.equals(ObjectService.ATTR_ENTITY_KEY))) continue;
                ps.setString(1, o.id());
                ps.setString(2, k);
                ps.setString(3, v.toLowerCase(Locale.ROOT));
                ps.addBatch();
            }
            // The placeholder goes LAST: an object is "indexed" only once all its reference rows are in.
            ps.setString(1, o.id());
            ps.setString(2, "");
            ps.setString(3, "");
            ps.addBatch();
            ps.executeBatch();
        }
    }

    /** Run {@code body} in ONE transaction (commit, or roll back on any failure), restoring auto-commit. */
    private static <T> T tx(Connection conn, ConnectionSource.SqlFunction<T> body) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            T r = body.apply(conn);
            conn.commit();
            return r;
        } catch (SQLException | RuntimeException e) {
            try { conn.rollback(); } catch (SQLException ignored) { /* the original failure matters */ }
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    private static void deleteRefs(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + REF_TABLE + " WHERE object_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private static final int BACKFILL_PAGE = 1_000;

    /**
     * Index every object that has no placeholder row yet (existing databases, rows written by older builds, a partial
     * write). Paged - {@value #BACKFILL_PAGE} objects per transaction - so memory stays bounded; each indexed object gains
     * its placeholder, so the loop ends.
     */
    static void backfillRefs(Connection conn) throws SQLException {
        while (true) {
            int n = tx(conn, c -> {
                List<OperationalObject> page = new ArrayList<>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + COLS + " FROM " + TABLE
                             + " WHERE id NOT IN (SELECT object_id FROM " + REF_TABLE + " WHERE attr_key = '')"
                             + " ORDER BY id LIMIT " + BACKFILL_PAGE)) {
                    while (rs.next()) page.add(mapRow(rs));
                }
                for (OperationalObject o : page) writeRefs(c, o);
                return page.size();
            });
            if (n < BACKFILL_PAGE) return;
        }
    }

    // ── schema + helpers ─────────────────────────────────────────────────────────

    private void initSchema() {
        try {
            runConn(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                            + "id VARCHAR PRIMARY KEY, object_type VARCHAR, title VARCHAR, description VARCHAR, "
                            + "status VARCHAR, severity VARCHAR, priority VARCHAR, \"owner\" VARCHAR, "
                            + "assignee VARCHAR, correlation_id VARCHAR, attributes VARCHAR, "
                            + "created_at BIGINT, updated_at BIGINT, closed_at BIGINT, version BIGINT DEFAULT 0)");
                    // Optimistic lock counter — additive migration for tables created before it existed; their
                    // rows start at 0. (A DEFAULT, not NOT NULL: DuckDB cannot add a constrained column.)
                    st.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0");
                    st.execute("CREATE TABLE IF NOT EXISTS " + REF_TABLE
                            + " (object_id VARCHAR, attr_key VARCHAR, value_lc VARCHAR)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_" + REF_TABLE + "_object ON " + REF_TABLE + " (object_id)");
                }
                backfillRefs(conn);
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise object DB schema", e);
        }
    }

    private static void bindAll(PreparedStatement ps, OperationalObject o) throws SQLException {
        ps.setString(1, o.id());
        ps.setString(2, o.typeName());
        ps.setString(3, o.title());
        ps.setString(4, o.description());
        ps.setString(5, o.status());
        ps.setString(6, o.severity());
        ps.setString(7, o.priority());
        ps.setString(8, o.owner());
        ps.setString(9, o.assignee());
        ps.setString(10, o.correlationId());
        ps.setString(11, JsonAttributes.toJson(o.attributes()));
        ps.setLong(12, o.createdAt());
        ps.setLong(13, o.updatedAt());
        ps.setLong(14, o.closedAt());
    }

    private static OperationalObject mapRow(ResultSet rs) throws SQLException {
        String rawType = rs.getString("object_type");
        ObjectType type = ObjectType.tryOf(rawType);
        if (type == null) {
            // A stored type this build does not know (a legacy type, or a module that is not installed): load the
            // row INERT — listed and readable, never rewritten and never dropped.
            return OperationalObject.inert(rawType,
                    rs.getString("id"), rs.getString("title"), rs.getString("description"), rs.getString("status"),
                    rs.getString("severity"), rs.getString("priority"), rs.getString("owner"), rs.getString("assignee"),
                    rs.getString("correlation_id"), JsonAttributes.fromJson(rs.getString("attributes")),
                    rs.getLong("created_at"), rs.getLong("updated_at"), rs.getLong("closed_at"), rs.getLong("version"));
        }
        return new OperationalObject(
                rs.getString("id"),
                type,
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("severity"),
                rs.getString("priority"),
                rs.getString("owner"),
                rs.getString("assignee"),
                rs.getString("correlation_id"),
                JsonAttributes.fromJson(rs.getString("attributes")),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getLong("closed_at"),
                rs.getLong("version"));
    }

    private static void ciEquals(List<String> where, List<Object> params, String col, String val) {
        if (val != null) {
            where.add("LOWER(" + col + ") = ?");
            params.add(val.toLowerCase(Locale.ROOT));
        }
    }
}
