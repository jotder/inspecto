package com.gamma.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Opens JDBC connections for DuckDB- or Postgres-backed stores. It registers the bundled
 * driver matching the URL's scheme, then hands back a {@link Connection} — centralising the
 * "which drivers ship + how a store opens its connection" logic that every store's
 * {@code open(...)} factory used to copy-paste.
 */
public final class JdbcDrivers {

    private static final Logger log = LoggerFactory.getLogger(JdbcDrivers.class);

    private JdbcDrivers() {}

    /**
     * Register the driver for {@code url}'s scheme, then open a connection with
     * URL-embedded / no credentials.
     *
     * @throws SQLException if the matched driver class is not on the classpath, or the connection fails
     */
    public static Connection connect(String url) throws SQLException {
        register(url);
        return DriverManager.getConnection(url);
    }

    /**
     * Register the driver for {@code url}'s scheme, then open a connection. When both
     * {@code user} and {@code pass} are {@code null}, any credentials embedded in the URL are used.
     *
     * @throws SQLException if the matched driver class is not on the classpath, or the connection fails
     */
    public static Connection connect(String url, String user, String pass) throws SQLException {
        register(url);
        return (user == null && pass == null)
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, pass);
    }

    /**
     * Best-effort dialect probe: {@code true} when {@code conn} speaks PostgreSQL, {@code false}
     * otherwise (including on any metadata error — callers default to the bundled DuckDB dialect).
     * Continuous percentiles are the one non-portable bit of SQL across our stores
     * ({@code quantile_cont(col, p)} vs {@code percentile_cont(p) WITHIN GROUP (ORDER BY col)}); this
     * centralises the probe that the dialect switch and the DB-browser engine label both need.
     */
    public static boolean isPostgres(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            return false;
        }
    }

    /** {@code -Ddb.pool.size} — per-family cap on concurrent borrows from the shared Postgres pool (across Spaces). */
    public static final String POOL_SIZE_PROPERTY = "db.pool.size";
    /** {@code -Ddb.pool.timeoutMs} — how long a borrow waits before failing. */
    public static final String POOL_TIMEOUT_PROPERTY = "db.pool.timeoutMs";

    /** {@code -Ddb.pool.process.size} — connections in the ONE shared PostgreSQL pool per process per server. */
    public static final String PROCESS_POOL_SIZE_PROPERTY = "db.pool.process.size";

    private static final int DEFAULT_POOL_SIZE = 10;
    private static final int DEFAULT_PROCESS_POOL_SIZE = 20;
    private static final long DEFAULT_BORROW_TIMEOUT_MS = 30_000L;

    /**
     * Open a {@link ConnectionSource} for {@code url} — the seam every DB-backed store should use
     * (scale-out phase A / {@code OPS-03}).
     *
     * <p><b>Sizing is derived from the URL scheme, not configured per store</b>, because it is a property
     * of the engine rather than of the operator's taste:
     * <ul>
     *   <li>⛔ {@code jdbc:duckdb:} → <b>exactly one</b> connection ({@link SingleConnectionSource}). Each
     *       store owns a single-writer-locked DuckDB file; a second concurrent connection to it fails to
     *       take the lock. This is not a tuning choice and {@code -Ddb.pool.size} does NOT apply.</li>
     *   <li>{@code jdbc:postgresql:} → a view over ONE shared HikariCP pool per process per server
     *       ({@code -Ddb.pool.process.size}, default {@value #DEFAULT_PROCESS_POOL_SIZE}), each family label
     *       capped at {@code -Ddb.pool.size} (default {@value #DEFAULT_POOL_SIZE}) concurrent borrows. A pod is
     *       bounded by the process pool, not by how many stores and Spaces it happens to open.</li>
     *   <li>anything else → one connection, the conservative shape.</li>
     * </ul>
     *
     * @param label a short name for the pool, so a stuck borrow names the store in a thread dump
     * @throws SQLException if the driver is missing or the first connection fails
     */
    public static ConnectionSource source(String url, String user, String pass, String label)
            throws SQLException {
        if (url != null && url.startsWith("jdbc:postgresql:")) {
            // ONE shared pool per process per server+user, a per-family cap of -Ddb.pool.size (operator
            // decision 2026-10-06). currentSchema is lifted off the URL and applied per borrow instead.
            String[] split = splitCurrentSchema(url);
            return SharedPoolConnectionSource.open(split[0], user, pass, split[1], label,
                    processPoolSize(), poolSize(), poolBorrowTimeoutMs(), true);
        }
        return new SingleConnectionSource(connect(url, user, pass));
    }

    /** Wrap an already-open connection as a source — for callers handed a connection they own. */
    public static ConnectionSource source(Connection conn) {
        return new SingleConnectionSource(conn);
    }

    /**
     * Splits a {@code currentSchema=} parameter off a URL: {@code [url without it, schema or null]}. The other
     * parameters stay, so a server reached with different TLS or options still gets its own pool.
     */
    static String[] splitCurrentSchema(String url) {
        int q = url.indexOf('?');
        if (q < 0) return new String[] {url, null};
        String schema = null;
        StringBuilder rest = new StringBuilder();
        for (String kv : url.substring(q + 1).split("&")) {
            if (kv.toLowerCase(java.util.Locale.ROOT).startsWith("currentschema=")) {
                schema = kv.substring("currentschema=".length());
                continue;
            }
            if (kv.isEmpty()) continue;
            rest.append(rest.length() == 0 ? "" : "&").append(kv);
        }
        return new String[] {url.substring(0, q) + (rest.length() == 0 ? "" : "?" + rest), schema};
    }

    /** {@code -Ddb.pool.process.size} — the ONE shared pool's size per process per server, clamped to at least 1. */
    static int processPoolSize() {
        return Math.max(1, intProperty(PROCESS_POOL_SIZE_PROPERTY, DEFAULT_PROCESS_POOL_SIZE));
    }

    /** {@code -Ddb.pool.size}, clamped to at least 1; invalid values fall back to the default. */
    static int poolSize() {
        return Math.max(1, intProperty(POOL_SIZE_PROPERTY, DEFAULT_POOL_SIZE));
    }

    /** {@code -Ddb.pool.timeoutMs}; a borrow that cannot be satisfied fails rather than hanging forever. */
    static long poolBorrowTimeoutMs() {
        return Math.max(250L, intProperty(POOL_TIMEOUT_PROPERTY, (int) DEFAULT_BORROW_TIMEOUT_MS));
    }

    private static int intProperty(String key, int fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("-D{}={} is not a number — using {}", key, raw, fallback);
            return fallback;
        }
    }

    /** Load the bundled JDBC driver matching the URL scheme; an unrecognised scheme self-registers (no-op). */
    private static void register(String url) throws SQLException {
        try {
            if (url.startsWith("jdbc:duckdb:")) Class.forName("org.duckdb.DuckDBDriver");
            else if (url.startsWith("jdbc:postgresql:")) Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("No JDBC driver on the classpath for " + url, e);
        }
    }
}
