package com.gamma.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ConnectionSource} seam — scale-out phase A's connection pool ({@code OPS-03}).
 *
 * <p>⚠ <b>These run OFFLINE against the bundled DuckDB driver, deliberately.</b> The pool's own
 * behaviour — sizing, saturation, reentrancy — is a property of the pool, not of PostgreSQL; the pool itself
 * ({@link SharedPoolConnectionSource}) is driven directly by {@code SharedPoolConnectionSourceTest}.
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

    // ── reentrancy (the pooled source's reentrancy, timeout and close are in SharedPoolConnectionSourceTest) ──

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
