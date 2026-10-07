package com.gamma.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A store's view of the <b>one shared PostgreSQL pool per process</b> (operator decision 2026-10-06, BACKLOG
 * "Postgres multi-user"). Every store that opens the same server (URL without {@code currentSchema}, same user)
 * borrows from one HikariCP pool of {@code -Ddb.pool.process.size} connections, and each family label (events,
 * status, …) is capped across all Spaces at {@code -Ddb.pool.size} concurrent borrows, so one busy family cannot
 * starve the rest. Schema-per-space survives the sharing: every borrow sets the view's schema (or resets the
 * {@code search_path} for a view with none) before the body runs.
 *
 * <p>⚠ Before this, each store opened its own pool — one per family per Space — so connection use grew with the
 * number of Spaces (~20 pools exhausted {@code max_connections=100} before {@code minimumIdle=1}).
 */
final class SharedPoolConnectionSource implements ConnectionSource {

    private static final Logger log = LoggerFactory.getLogger(SharedPoolConnectionSource.class);

    /** One per server+user: the pool, its per-family caps, and how many open views use it. */
    static final class Shared {
        final String key;
        final HikariDataSource pool;
        final Map<String, Semaphore> familyCaps = new HashMap<>();
        int views;

        Shared(String key, HikariDataSource pool) {
            this.key = key;
            this.pool = pool;
        }
    }

    private static final Map<String, Shared> SHARED = new HashMap<>();

    private final ThreadLocal<Connection> borrowed = new ThreadLocal<>();
    private final Shared shared;
    private final Semaphore cap;
    private final String schema;
    private final boolean resetSearchPath;
    private final long timeoutMs;
    private boolean closed;

    private SharedPoolConnectionSource(Shared shared, Semaphore cap, String schema, boolean resetSearchPath,
                                       long timeoutMs) {
        this.shared = shared;
        this.cap = cap;
        this.schema = schema;
        this.resetSearchPath = resetSearchPath;
        this.timeoutMs = timeoutMs;
    }

    /**
     * A view over the process pool for {@code baseUrl}+{@code user} (created on first use), capped for
     * {@code family}. {@code schema} is the view's {@code currentSchema}, or {@code null} for the server default.
     */
    static synchronized SharedPoolConnectionSource open(String baseUrl, String user, String pass, String schema,
                                                       String family, int processSize, int familyCap,
                                                       long timeoutMs, boolean resetSearchPath) {
        String key = baseUrl + "\n" + (user == null ? "" : user);
        Shared s = SHARED.get(key);
        if (s == null) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(baseUrl);
            if (user != null) cfg.setUsername(user);
            if (pass != null) cfg.setPassword(pass);
            cfg.setMaximumPoolSize(processSize);
            cfg.setMinimumIdle(1);   // never pin the whole budget while idle
            cfg.setPoolName("inspecto-shared-" + SHARED.size());
            cfg.setConnectionTimeout(timeoutMs);
            s = new Shared(key, new HikariDataSource(cfg));
            SHARED.put(key, s);
        }
        s.views++;
        Semaphore cap = s.familyCaps.computeIfAbsent(family, f -> new Semaphore(familyCap, true));
        return new SharedPoolConnectionSource(s, cap, schema, resetSearchPath, timeoutMs);
    }

    @Override
    public <T> T with(SqlFunction<T> body) throws SQLException {
        Connection already = borrowed.get();
        if (already != null) return body.apply(already);   // nested: one operation, one connection

        try {
            if (!cap.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS))
                throw new SQLException("family connection cap reached on " + shared.pool.getPoolName()
                        + " — no permit within " + timeoutMs + "ms (-Ddb.pool.size)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted waiting for a connection", e);
        }
        try (Connection conn = shared.pool.getConnection()) {
            if (schema != null) conn.setSchema(schema);
            else if (resetSearchPath) try (Statement st = conn.createStatement()) { st.execute("RESET search_path"); }
            borrowed.set(conn);
            try {
                return body.apply(conn);
            } finally {
                borrowed.remove();
            }
        } finally {
            cap.release();
        }
    }

    @Override
    public boolean isPostgres() {
        return true;   // only chosen for a jdbc:postgresql: URL (JdbcDrivers#source)
    }

    /** Releases this view; the shared pool closes when its last view does. Quiet and repeatable. */
    @Override
    public void close() {
        synchronized (SharedPoolConnectionSource.class) {
            if (closed) return;
            closed = true;
            if (--shared.views > 0) return;
            SHARED.remove(shared.key);
        }
        try {
            shared.pool.close();
        } catch (RuntimeException e) {
            log.warn("Error closing connection pool {}: {}", shared.pool.getPoolName(), e.getMessage());
        }
    }

    /** Test seam: the live shared pool's maximum size. */
    int processPoolSize() {
        return shared.pool.getMaximumPoolSize();
    }

    /** Test seam: the pool object, to prove two views share it. */
    Object poolIdentity() {
        return shared.pool;
    }

    /** Test seam: number of live shared pools in this process. */
    static synchronized int sharedPoolCount() {
        return SHARED.size();
    }
}
