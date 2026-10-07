package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ONE-shared-pool-per-process connection budget (operator decision 2026-10-06), driven OFFLINE over in-memory
 * DuckDB like {@link ConnectionSourceTest}: sharing, the per-family cap and reference-counted close are properties of
 * the pool, not of PostgreSQL. Schema-per-space over the shared pool is proven on a real server by
 * {@code PostgresSchemaPerSpaceStoresTest}.
 */
class SharedPoolConnectionSourceTest {

    private static SharedPoolConnectionSource view(String url, String family, int familyCap) {
        return SharedPoolConnectionSource.open(url, null, null, null, family, 4, familyCap, 500, false);
    }

    @Test
    void storesOnOneServerShareOnePool_andItClosesWithTheLastView() {
        int before = SharedPoolConnectionSource.sharedPoolCount();
        SharedPoolConnectionSource a = view("jdbc:duckdb:", "events", 2);
        SharedPoolConnectionSource b = view("jdbc:duckdb:", "status", 2);
        assertSame(a.poolIdentity(), b.poolIdentity(), "two families on one server share ONE pool");
        assertEquals(4, a.processPoolSize());
        assertEquals(before + 1, SharedPoolConnectionSource.sharedPoolCount());
        a.close();
        a.close();   // repeatable: must not decrement twice
        assertEquals(before + 1, SharedPoolConnectionSource.sharedPoolCount(), "b still holds the pool open");
        b.close();
        assertEquals(before, SharedPoolConnectionSource.sharedPoolCount(), "the last view closes the pool");
    }

    @Test
    void aSaturatedFamilyFailsOnTheTimeout_whileAnotherFamilyStillBorrows() throws Exception {
        SharedPoolConnectionSource hot = view("jdbc:duckdb:", "hot", 1);
        SharedPoolConnectionSource hot2 = view("jdbc:duckdb:", "hot", 1);
        SharedPoolConnectionSource other = view("jdbc:duckdb:", "other", 1);
        CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                hot.run(c -> { holding.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
                return null;
            });
            assertTrue(holding.await(10, TimeUnit.SECONDS));
            SQLException ex = assertThrows(SQLException.class, () -> hot2.with(c -> c),
                    "the family cap is shared across views (Spaces): the second 'hot' borrow must time out");
            assertTrue(ex.getMessage().contains("family connection cap"), ex.getMessage());
            assertNotNull(other.with(c -> c), "a different family is not starved by the hot one");
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            hot.close(); hot2.close(); other.close();
        }
        // permit released: the family borrows again
        SharedPoolConnectionSource again = view("jdbc:duckdb:", "hot", 1);
        try { assertNotNull(again.with(c -> c)); } finally { again.close(); }
    }

    @Test
    void concurrentBorrowsGetDifferentConnectionsUpToTheFamilyCap() throws Exception {
        SharedPoolConnectionSource v = view("jdbc:duckdb:", "distinct", 2);
        CountDownLatch bothIn = new CountDownLatch(2);
        java.sql.Connection[] seen = new java.sql.Connection[2];
        ExecutorService two = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                int slot = i;
                two.submit(() -> {
                    v.run(c -> {
                        seen[slot] = c;
                        bothIn.countDown();
                        try { bothIn.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    });
                    return null;
                });
            }
            two.shutdown();
            assertTrue(two.awaitTermination(10, TimeUnit.SECONDS));
            assertNotNull(seen[0]);
            assertNotSame(seen[0], seen[1], "two threads inside the pool at once must hold DIFFERENT connections");
        } finally {
            two.shutdownNow();
            v.close();
        }
    }

    @Test
    void aNestedBorrowReusesTheConnection_soAFamilyCapOfOneCannotSelfDeadlock() throws Exception {
        SharedPoolConnectionSource v = view("jdbc:duckdb:", "nested", 1);
        try {
            java.sql.Connection[] seen = new java.sql.Connection[2];
            v.run(outer -> { seen[0] = outer; v.run(inner -> seen[1] = inner); });
            assertSame(seen[0], seen[1]);
        } finally {
            v.close();
        }
    }

    @Test
    void currentSchemaIsLiftedOffTheUrl_otherParametersStay() {
        assertArrayEquals(new String[] {"jdbc:postgresql://h/db?user=u&sslmode=require", "space_a"},
                JdbcDrivers.splitCurrentSchema("jdbc:postgresql://h/db?user=u&currentSchema=space_a&sslmode=require"));
        assertArrayEquals(new String[] {"jdbc:postgresql://h/db", "space_b"},
                JdbcDrivers.splitCurrentSchema("jdbc:postgresql://h/db?currentschema=space_b"));
        assertArrayEquals(new String[] {"jdbc:postgresql://h/db", null},
                JdbcDrivers.splitCurrentSchema("jdbc:postgresql://h/db"));
    }
}
