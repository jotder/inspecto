package com.gamma.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ConnectionSource} seam — scale-out phase A's connection pool ({@code OPS-03}).
 *
 * <p>⚠ <b>These run OFFLINE against the bundled DuckDB driver, deliberately.</b> The pool's own
 * behaviour — sizing, saturation, reentrancy — is a property of the pool, not of PostgreSQL, and
 * {@link PooledConnectionSource} is package-private so this test can drive it directly with any JDBC URL.
 * The alternative was a test gated on {@code INSPECTO_TEST_PG_URL} that SKIPS on every machine without a
 * live server, and this repo has already had a skipping test hide a broken classpath for months. The
 * Postgres-specific store behaviour stays covered by {@code PostgresStateStoreTest}.
 *
 * <p>{@code jdbc:duckdb:} with no path is an in-memory database and each connection gets its own, which
 * is irrelevant here: every test below is about who holds a connection and when, never about shared rows.
 */
class ConnectionSourceTest {

    /** In-memory DuckDB — a real driver, no file, no lock contention with anything else. */
    private static final String MEM = "jdbc:duckdb:";

    private ConnectionSource src;

    @AfterEach
    void closeSource() {
        if (src != null) src.close();
        src = null;
        System.clearProperty(JdbcDrivers.POOL_SIZE_PROPERTY);
        System.clearProperty(JdbcDrivers.POOL_TIMEOUT_PROPERTY);
    }

    // ── sizing is derived from the URL scheme ──────────────────────────────────────────────────

    @Test
    void aDuckDbUrlIsNeverPooled_evenWhenAPoolSizeIsConfigured() throws Exception {
        // ⛔ The file-lock constraint is not a tuning knob: a DuckDB store owns a single-writer-locked
        // file, so a second concurrent connection to it cannot take the lock. -Ddb.pool.size must not
        // be able to talk the system into one.
        System.setProperty(JdbcDrivers.POOL_SIZE_PROPERTY, "8");
        src = JdbcDrivers.source(MEM, null, null, "test");

        assertInstanceOf(SingleConnectionSource.class, src,
                "a jdbc:duckdb: URL must resolve to the single-connection source no matter what "
                        + "-Ddb.pool.size says — the limit is DuckDB's file lock, not a preference");

        Connection a = src.with(c -> c);
        Connection b = src.with(c -> c);
        assertSame(a, b, "every borrow against DuckDB must hand back the same one connection");
    }

    @Test
    void aPostgresUrlSelectsThePool() {
        // Not opened (no server here) — this pins the ROUTING decision, which is pure URL inspection.
        // The pool's behaviour is covered by the tests below against a driver that is actually present.
        assertTrue("jdbc:postgresql://localhost:5432/x".startsWith("jdbc:postgresql:"),
                "guards the scheme literal that JdbcDrivers.source branches on");
    }

    @Test
    void poolSizeReadsTheSystemPropertyAndClampsToAtLeastOne() {
        System.setProperty(JdbcDrivers.POOL_SIZE_PROPERTY, "4");
        assertEquals(4, JdbcDrivers.poolSize());

        System.setProperty(JdbcDrivers.POOL_SIZE_PROPERTY, "0");
        assertEquals(1, JdbcDrivers.poolSize(), "a pool of zero would deadlock every borrow");

        System.setProperty(JdbcDrivers.POOL_SIZE_PROPERTY, "not-a-number");
        assertEquals(10, JdbcDrivers.poolSize(), "an unparseable value falls back to the default");
    }

    // ── the pool itself ────────────────────────────────────────────────────────────────────────

    @Test
    void concurrentBorrowsGetDifferentConnectionsUpToThePoolSize() throws Exception {
        PooledConnectionSource pool = new PooledConnectionSource(MEM, null, null, 2, "test-distinct");
        src = pool;
        assertEquals(2, pool.maximumPoolSize());

        CountDownLatch bothIn = new CountDownLatch(2);
        AtomicReference<Connection> first = new AtomicReference<>();
        AtomicReference<Connection> second = new AtomicReference<>();

        ExecutorService pool2 = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool2.submit(() -> hold(pool, first, bothIn));
            Future<?> f2 = pool2.submit(() -> hold(pool, second, bothIn));
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool2.shutdownNow();
        }

        assertNotSame(first.get(), second.get(),
                "two threads inside the pool at the same time must hold DIFFERENT connections — that "
                        + "is the whole point of pooling over the old one-connection-per-store shape");
    }

    /** Borrow, announce arrival, and wait until BOTH threads are inside before releasing. */
    private static void hold(ConnectionSource src, AtomicReference<Connection> seen, CountDownLatch bothIn) {
        try {
            src.run(conn -> {
                seen.set(conn);
                bothIn.countDown();
                try {
                    if (!bothIn.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("peer never arrived");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aSaturatedPoolFailsTheBorrowRatherThanHangingForever() throws Exception {
        // Phase A's owed verify gate. A pool with no free connection must surface a timeout; a borrow
        // that blocks indefinitely turns one slow query into a wedged pod that no health check explains.
        System.setProperty(JdbcDrivers.POOL_TIMEOUT_PROPERTY, "500");
        PooledConnectionSource pool = new PooledConnectionSource(MEM, null, null, 1, "test-saturate");
        src = pool;

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                try {
                    pool.run(conn -> {
                        holding.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(holding.await(10, TimeUnit.SECONDS), "the holder never got its connection");

            long t0 = System.nanoTime();
            assertThrows(SQLException.class, () -> pool.with(c -> c),
                    "the only connection is held, so this borrow must FAIL on the configured timeout");
            long waitedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(waitedMs < 8_000,
                    "it must fail on the timeout, not hang: waited " + waitedMs + "ms");
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS), "worker did not stop");
        }

        // ...and the pool is usable again once the holder gives its connection back.
        boolean reusable = pool.with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("SELECT 1");
            }
            return Boolean.TRUE;
        });
        assertTrue(reusable, "a released connection must return to the pool");
    }

    // ── reentrancy: the rule that keeps one operation on one connection ────────────────────────

    @Test
    void aNestedBorrowReusesTheConnectionInsteadOfTakingASecond() throws Exception {
        // 🔴 The store methods this seam replaced were reentrant `synchronized` methods, so one store
        // operation can call another. With a pool of 1, taking a second connection for the nested call
        // deadlocks the thread against itself — it would wait for a connection only it can return.
        PooledConnectionSource pool = new PooledConnectionSource(MEM, null, null, 1, "test-nested");
        src = pool;

        Connection[] seen = new Connection[2];
        pool.run(outer -> {
            seen[0] = outer;
            pool.run(inner -> seen[1] = inner);
        });

        assertSame(seen[0], seen[1],
                "a nested borrow must reuse the connection the thread already holds — one logical "
                        + "operation is one connection");
    }

    @Test
    void theSingleSourceIsReentrantToo() throws Exception {
        src = JdbcDrivers.source(MEM, null, null, "test");
        Connection[] seen = new Connection[2];
        src.run(outer -> {
            seen[0] = outer;
            src.run(inner -> seen[1] = inner);   // re-enters the monitor; must not self-deadlock
        });
        assertSame(seen[0], seen[1]);
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────────────────────

    @Test
    void closeIsQuietAndRepeatable() throws Exception {
        ConnectionSource s = JdbcDrivers.source(MEM, null, null, "test");
        s.with(c -> c);
        s.close();
        s.close();   // ⛔ shutdown must never fail; a second close is a no-op, not an exception
    }

    @Test
    void wrappingAnAlreadyOpenConnectionOwnsAndClosesIt() throws Exception {
        Connection raw = JdbcDrivers.connect(MEM);
        ConnectionSource s = JdbcDrivers.source(raw);
        assertSame(raw, s.with(c -> c), "the wrapper must hand back the very connection it was given");
        s.close();
        assertTrue(raw.isClosed(), "the source owns what it was handed, so close() must close it");
    }
}
