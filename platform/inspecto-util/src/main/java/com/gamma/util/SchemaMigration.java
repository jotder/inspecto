package com.gamma.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The catalog probes and the lock an open-time schema migration needs, scoped to the connection's OWN schema.
 *
 * <p>⚠ <b>Every probe filters on {@code table_catalog = current_database() AND table_schema = current_schema()}.</b>
 * On Postgres every Space is a schema of one database ({@code OperationalDb.urlFor} sets {@code currentSchema}),
 * and {@code information_schema} lists every schema the role can see. An unscoped probe therefore sees another
 * Space's table: a store already migrated (or new) in this schema would run a rename against a column that is not
 * here, and fail to open. The same holds on DuckDB with a second schema or an attached database. The DDL a
 * migration runs uses unqualified names, so it resolves to {@code current_schema()} too — probe and act agree.
 * Both engines provide both functions.
 *
 * <p><b>Concurrency.</b> {@link #lock} serialises the first open of one schema across nodes on Postgres with a
 * transaction-scoped advisory lock; the migration then re-probes under the lock, so the node that waited finds the
 * work done and does nothing. DuckDB needs no lock: its database file is held by one process.
 */
public final class SchemaMigration {

    private static final String HERE = " AND table_catalog = current_database() AND table_schema = current_schema()";

    private SchemaMigration() {}

    /** {@code true} when {@code table} exists in the connection's current schema. */
    public static boolean tableExists(Connection c, String table) throws SQLException {
        return any(c, "SELECT 1 FROM information_schema.tables WHERE table_name = ?" + HERE, table);
    }

    /** {@code true} when {@code table} in the connection's current schema has a column named {@code column}. */
    public static boolean columnExists(Connection c, String table, String column) throws SQLException {
        return any(c, "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?" + HERE,
                table, column);
    }

    /** {@code true} when {@code table} in the connection's current schema has a constraint of {@code type} (e.g. {@code UNIQUE}). */
    public static boolean constraintExists(Connection c, String table, String type) throws SQLException {
        return any(c, "SELECT 1 FROM information_schema.table_constraints WHERE table_name = ? AND constraint_type = ?"
                + HERE, table, type);
    }

    /**
     * On Postgres, block until this transaction holds the advisory lock for {@code name} in the current schema; it
     * is released at commit or rollback. A no-op on any other engine. ⚠ Call it inside a transaction
     * ({@code autoCommit = false}): in autocommit mode the lock is released as soon as the statement ends.
     */
    public static void lock(Connection c, boolean postgres, String name) throws SQLException {
        if (!postgres) return;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtext(? || ':' || current_schema()))")) {
            ps.setString(1, name);
            ps.execute();
        }
    }

    private static boolean any(Connection c, String sql, String... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setString(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
