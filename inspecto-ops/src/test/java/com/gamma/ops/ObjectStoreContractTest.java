package com.gamma.ops;

import com.gamma.objects.ObjectType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@link ObjectStore} contract for a <b>Case</b> (the store a Case lives in — there is no separate
 * {@code CaseStore}: a Case is an {@code ObjectType.CASE} row), run against BOTH implementations: the
 * in-memory one always, the Postgres-backed {@link DbObjectStore} when a server is configured
 * ({@code INSPECTO_TEST_PG_URL} or {@code -Dinspecto.test.pg.url}; see {@code PostgresStateStoreTest}).
 *
 * <p>The Postgres variants skip PER TEST (an {@code assumeTrue} in {@code @BeforeAll} would drop the class
 * from surefire's totals). They use a throwaway schema per run, never the caller's default one.
 *
 * <p>The concurrency tests pin "no duplicate ids, no lost creates": a racing second {@code create} of the
 * same id fails with {@link IllegalStateException}, exactly one wins, whatever the number of writers.
 * Updates are last-writer-wins by design (no version column) and are not asserted here.
 */
class ObjectStoreContractTest {

    private static final String URL_PROPERTY = "inspecto.test.pg.url";
    private static String adminUrl;
    private static String schema;
    private static String url;

    @BeforeAll
    static void connect() throws Exception {
        adminUrl = System.getProperty(URL_PROPERTY);
        if (adminUrl == null || adminUrl.isBlank()) adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
        if (adminUrl == null || adminUrl.isBlank()) return;
        schema = "inspecto_test_cases_" + Long.toHexString(System.nanoTime());
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        url = adminUrl + (adminUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    @AfterAll
    static void drop() throws Exception {
        if (schema == null) return;
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static void requirePg() {
        assumeTrue(url != null, "needs PostgreSQL: set INSPECTO_TEST_PG_URL or -D" + URL_PROPERTY);
    }

    private static OperationalObject kase(String id) {
        return OperationalObject.builder(ObjectType.CASE).id(id).title("case " + id).status("OPEN")
                .owner("ops").attr("k", "v").build();
    }

    // ── contract ─────────────────────────────────────────────────────────────

    private static void contract(ObjectStore store, String p) {
        store.create(kase(p + "1"));
        assertEquals("OPEN", store.get(p + "1").orElseThrow().status());
        assertEquals("v", store.get(p + "1").orElseThrow().attributes().get("k"));
        assertThrows(IllegalStateException.class, () -> store.create(kase(p + "1")), "duplicate id refused");

        store.update(store.get(p + "1").orElseThrow().withStatus("CLOSED", 5L, true));
        assertEquals("CLOSED", store.get(p + "1").orElseThrow().status());
        assertThrows(NoSuchElementException.class, () -> store.update(kase(p + "missing")), "update of unknown id");
        assertTrue(store.get(p + "nope").isEmpty());

        assertEquals(1, store.findByAttributes(ObjectType.CASE, Map.of("k", "v"), 10).stream()
                .filter(o -> o.id().startsWith(p)).count());
    }

    @Test
    void contract_inMemory() throws Exception {
        try (ObjectStore s = new InMemoryObjectStore()) { contract(s, "M-"); }
    }

    @Test
    void contract_postgres() throws Exception {
        requirePg();
        try (ObjectStore s = DbObjectStore.open(url, null, null)) { contract(s, "P-"); }
    }

    // ── concurrency: several writers ─────────────────────────────────────────

    private static void concurrency(ObjectStore store, String p) throws Exception {
        // (1) the SAME id raced by 8 writers: exactly one wins, the rest see "already exists".
        int writers = 8;
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger(), refused = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < writers; i++) fs.add(pool.submit((Callable<Void>) () -> {
                go.await();
                try { store.create(kase(p + "same")); won.incrementAndGet(); }
                catch (IllegalStateException e) { refused.incrementAndGet(); }
                return null;
            }));
            go.countDown();
            for (Future<?> f : fs) f.get();
            assertEquals(1, won.get(), "exactly one racing create wins");
            assertEquals(writers - 1, refused.get());

            // (2) distinct ids from 4 writers x 25: none lost.
            CountDownLatch go2 = new CountDownLatch(1);
            List<Future<?>> fs2 = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                int wid = w;
                fs2.add(pool.submit((Callable<Void>) () -> {
                    go2.await();
                    for (int i = 0; i < 25; i++) store.create(kase(p + "d-" + wid + "-" + i));
                    return null;
                }));
            }
            go2.countDown();
            for (Future<?> f : fs2) f.get();
        } finally { pool.shutdownNow(); }
        long n = store.query(ObjectQuery.builder().objectType(ObjectType.CASE).limit(1000).build()).stream()
                .filter(o -> o.id().startsWith(p)).count();
        assertEquals(101, n, "1 raced id + 100 distinct ids, none lost, no duplicates");
    }

    @Test
    void concurrency_inMemory() throws Exception {
        try (ObjectStore s = new InMemoryObjectStore()) { concurrency(s, "CM-"); }
    }

    @Test
    void concurrency_postgres() throws Exception {
        requirePg();
        try (ObjectStore s = DbObjectStore.open(url, null, null)) { concurrency(s, "CP-"); }
    }
}
