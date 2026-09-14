package com.gamma.util;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Where a DB-backed store gets a JDBC {@link Connection} for the duration of ONE operation.
 *
 * <p>⚠ <b>Borrow-scoped by design (scale-out phase A / {@code OPS-03}).</b> Before this seam every
 * {@code Db*Store} held one {@link Connection} in a final field for its whole life and serialised access
 * on its own monitor. That is correct on one node and a connection storm on twenty: fifteen stores times
 * N pods, each holding a connection open forever. A pool only helps once stores <em>return</em> what they
 * borrow, so the connection is handed to a callback rather than to the caller.
 *
 * <p>⛔ <b>Never let the {@link Connection} escape the callback.</b> It is valid only for the duration of
 * {@link #with}; after that it may have been returned to a pool and handed to another thread. Materialise
 * results (read the {@code ResultSet} into objects) before returning.
 *
 * <p>🔴 <b>Reentrancy is required, not a nicety.</b> The stores this replaced used {@code synchronized}
 * instance methods, which are reentrant, so one store method may call another that also touches the
 * database. A naive pooled implementation would take a SECOND connection for the nested call and can
 * deadlock a small pool against itself. Every implementation here reuses the connection already borrowed
 * by the current thread.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface ConnectionSource extends AutoCloseable {

    /** A body that runs against a borrowed connection and may fail the way JDBC fails. */
    @FunctionalInterface
    interface SqlFunction<T> {
        T apply(Connection conn) throws SQLException;
    }

    /** A body that runs against a borrowed connection and returns nothing. */
    @FunctionalInterface
    interface SqlAction {
        void accept(Connection conn) throws SQLException;
    }

    /**
     * Borrow a connection, run {@code body}, return it. Reentrant: a nested call on the same thread
     * reuses the connection already borrowed rather than taking another.
     */
    <T> T with(SqlFunction<T> body) throws SQLException;

    /** {@link #with} for a body with no result. */
    default void run(SqlAction body) throws SQLException {
        with(conn -> {
            body.accept(conn);
            return null;
        });
    }

    /**
     * {@code true} when this source speaks PostgreSQL. Resolved ONCE when the source is created, never
     * per borrow — the dialect is a property of the URL, and probing it on every borrow would add a
     * metadata round trip to every query.
     */
    boolean isPostgres();

    /** Release everything this source holds. ⛔ Must not throw: shutdown must not fail. */
    @Override
    void close();
}
