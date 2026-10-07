package com.gamma.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A {@link ConnectionSource} over exactly ONE connection, serialised on this object's monitor.
 *
 * <p>⛔ <b>This is the DuckDB case, and it is not a degraded pool — it is the only correct shape.</b>
 * Every operational store owns its own single-writer-locked DuckDB file; a second concurrent connection
 * to that file fails to take the lock. So DuckDB gets exactly one connection and callers queue on it,
 * which is precisely what the stores did before this seam existed (every {@code Db*Store} method was
 * {@code synchronized} on the store). Personal and Standard single-node behaviour is therefore
 * byte-identical to the pre-pool code.
 *
 * <p>Reentrancy comes free from {@code synchronized} being reentrant: a nested {@link #with} on the same
 * thread re-enters the monitor and reuses the same connection.
 *
 * <p>⚠ Also used to wrap a caller-supplied connection (tests, and a store constructed from an already-open
 * connection), which is why {@link #close} closes it: this source owns what it was handed.
 *
 * @since 4.0.0
 */
final class SingleConnectionSource implements ConnectionSource {

    private static final Logger log = LoggerFactory.getLogger(SingleConnectionSource.class);

    private final Connection conn;
    private final boolean postgres;

    SingleConnectionSource(Connection conn) {
        this.conn = conn;
        // Probed once, here, rather than per borrow (ConnectionSource#isPostgres).
        this.postgres = JdbcDrivers.isPostgres(conn);
    }

    @Override
    public synchronized <T> T with(SqlFunction<T> body) throws SQLException {
        return body.apply(conn);
    }

    @Override
    public boolean isPostgres() {
        return postgres;
    }

    /** Close the one connection. A close failure is logged, never thrown — shutdown must not fail. */
    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing JDBC connection: {}", e.getMessage());
        }
    }
}
