package com.gamma.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A pooled {@link ConnectionSource} over HikariCP — the PostgreSQL case.
 *
 * <p>This is the point of scale-out phase A's pool item ({@code OPS-03}). Fifteen stores each holding one
 * connection open for the process's life is fine for one node and a connection storm for twenty pods;
 * borrowing per operation means a pod's concurrent connections are bounded by the pool, not by the number
 * of stores.
 *
 * <p>🔴 <b>Reentrancy is implemented here deliberately.</b> The stores this replaced used reentrant
 * {@code synchronized} methods, so a store operation may call another that also touches the database.
 * Taking a second pooled connection for the nested call would mean one logical operation holds two —
 * and with a small pool, enough concurrent operations each holding two deadlock against each other while
 * every thread waits for a connection that only another waiting thread can return. The thread-local below
 * makes a nested borrow reuse the connection the thread already holds, so one operation is always one
 * connection. ⛔ Do not remove it in favour of "Hikari handles that" — it does not; the pool has no idea
 * two borrows belong to one logical unit of work.
 *
 * @since 4.0.0
 */
final class PooledConnectionSource implements ConnectionSource {

    private static final Logger log = LoggerFactory.getLogger(PooledConnectionSource.class);

    /** The connection the current thread has already borrowed, if it is inside a {@link #with}. */
    private final ThreadLocal<Connection> borrowed = new ThreadLocal<>();

    private final HikariDataSource pool;

    PooledConnectionSource(String url, String user, String pass, int maxSize, String poolName) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        if (user != null) cfg.setUsername(user);
        if (pass != null) cfg.setPassword(pass);
        cfg.setMaximumPoolSize(maxSize);
        cfg.setPoolName(poolName);
        // A borrow that cannot be satisfied must fail loudly rather than hang a request forever.
        cfg.setConnectionTimeout(JdbcDrivers.poolBorrowTimeoutMs());
        this.pool = new HikariDataSource(cfg);
    }

    @Override
    public <T> T with(SqlFunction<T> body) throws SQLException {
        Connection already = borrowed.get();
        if (already != null) return body.apply(already);   // nested: one operation, one connection

        try (Connection conn = pool.getConnection()) {
            borrowed.set(conn);
            try {
                return body.apply(conn);
            } finally {
                // ⛔ Remove, not set(null): a lingering empty entry keeps the thread-local map alive on
                // pooled request threads that outlive the call.
                borrowed.remove();
            }
        }
    }

    @Override
    public boolean isPostgres() {
        // This implementation is only ever chosen for a jdbc:postgresql: URL (JdbcDrivers#source), so the
        // answer is structural. Probing a borrowed connection would add a round trip per call.
        return true;
    }

    @Override
    public void close() {
        try {
            pool.close();
        } catch (RuntimeException e) {
            log.warn("Error closing connection pool {}: {}", pool.getPoolName(), e.getMessage());
        }
    }

    /** Live pool size — for the saturation test and operational assertions. */
    int maximumPoolSize() {
        return pool.getMaximumPoolSize();
    }
}
