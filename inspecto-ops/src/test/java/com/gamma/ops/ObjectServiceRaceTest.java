package com.gamma.ops;

import com.gamma.objects.ObjectType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ObjectService} read-modify-write is race-free on the optimistic version: writers that each change a
 * DIFFERENT part of one object at once must all land (the service re-reads and re-applies on a lost race), and
 * a writer that keeps losing surfaces {@link ObjectVersionConflictException} after a bounded number of attempts.
 * Against {@link InMemoryObjectStore} always, and {@link DbObjectStore} on Postgres when
 * {@code INSPECTO_TEST_PG_URL} / {@code -Dinspecto.test.pg.url} is set (skips PER TEST, like
 * {@link ObjectStoreContractTest}).
 */
class ObjectServiceRaceTest {

    private static String adminUrl;
    private static String schema;
    private static String url;

    @BeforeAll
    static void connect() throws Exception {
        adminUrl = System.getProperty("inspecto.test.pg.url");
        if (adminUrl == null || adminUrl.isBlank()) adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
        if (adminUrl == null || adminUrl.isBlank()) return;
        schema = "inspecto_test_svc_" + Long.toHexString(System.nanoTime());
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

    /** 6 writers, each patching its own attribute key on the same Case at once: every key must survive. */
    private static void patchesAreNotLost(ObjectStore store) throws Exception {
        ObjectService svc = new ObjectService(store);
        OperationalObject c = svc.open(ObjectType.CASE, "hot case", "d", "HIGH", "LOW", "ops", null, null, Map.of());
        int writers = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String key = "k" + i;
                fs.add(pool.submit((Callable<Void>) () -> {
                    go.await();
                    svc.patch(c.id(), null, null, null, Map.of(key, "v"));
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
        } finally { pool.shutdownNow(); }
        OperationalObject after = store.get(c.id()).orElseThrow();
        for (int i = 0; i < writers; i++)
            assertEquals("v", after.attributes().get("k" + i), "patch k" + i + " was lost to a racing writer");
        assertEquals(writers, after.version(), "one version bump per landed patch");
    }

    @Test
    void racingPatches_inMemory() throws Exception {
        try (ObjectStore s = new InMemoryObjectStore()) { patchesAreNotLost(s); }
    }

    @Test
    void racingPatches_postgres() throws Exception {
        assumeTrue(url != null, "needs PostgreSQL: set INSPECTO_TEST_PG_URL or -Dinspecto.test.pg.url");
        try (ObjectStore s = DbObjectStore.open(url, null, null)) { patchesAreNotLost(s); }
    }

    /** A transition raced against a patch: the transition re-reads, so the patch's attribute is not clobbered. */
    @Test
    void transitionAgainstPatch_keepsBothChanges() throws Exception {
        try (ObjectStore store = new InMemoryObjectStore()) {
            ObjectService svc = new ObjectService(store);
            OperationalObject c = svc.open(ObjectType.CASE, "t", "d", "HIGH", "LOW", "ops", null, null, Map.of());
            ObjectStore racy = interleaving(store, () -> svc.patch(c.id(), null, null, null, Map.of("late", "yes")));
            ObjectService racing = new ObjectService(racy);
            String next = svc.workflow(ObjectType.CASE).transitions().stream()
                    .filter(t -> t.from().equalsIgnoreCase(c.status())).findFirst().orElseThrow().to();
            OperationalObject moved = racing.transitionTo(c.id(), next, "tester");
            assertEquals(next, moved.status());
            assertEquals("yes", moved.attributes().get("late"), "the interleaved patch survived the transition");
        }
    }

    /** The bound: a writer that always loses is retried MAX_RMW_ATTEMPTS times, then the conflict surfaces. */
    @Test
    void persistentConflict_surfacesAfterBoundedRetries() throws Exception {
        try (InMemoryObjectStore inner = new InMemoryObjectStore()) {
            OperationalObject c = new ObjectService(inner)
                    .open(ObjectType.CASE, "t", "d", "HIGH", "LOW", "ops", null, null, Map.of());
            AtomicInteger attempts = new AtomicInteger();
            ObjectStore alwaysLoses = (ObjectStore) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ObjectStore.class}, (proxy, method, args) -> {
                        if (method.getName().equals("update")) {
                            attempts.incrementAndGet();
                            throw new ObjectVersionConflictException(c.id(), 0);
                        }
                        try { return method.invoke(inner, args); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    });
            ObjectService svc = new ObjectService(alwaysLoses);
            assertThrows(ObjectVersionConflictException.class,
                    () -> svc.patch(c.id(), "HIGH", null, null, null));
            assertEquals(ObjectService.MAX_RMW_ATTEMPTS, attempts.get());
        }
    }

    /** Store view that runs {@code rival} exactly once, just before the FIRST update - i.e. between read and write. */
    private static ObjectStore interleaving(ObjectStore inner, Runnable rival) {
        AtomicInteger fired = new AtomicInteger();
        return (ObjectStore) Proxy.newProxyInstance(ObjectServiceRaceTest.class.getClassLoader(),
                new Class<?>[]{ObjectStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("update") && fired.getAndIncrement() == 0) rival.run();
                    try { return method.invoke(inner, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
    }
}
