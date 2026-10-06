package com.gamma.ops;

import com.gamma.workflow.ObjectType;
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
 *
 * <p>Updates are <b>optimistic</b>: an object carries a {@code version}, {@code update} writes only if the
 * stored row is still at the version the caller read, and a stale writer fails with
 * {@link ObjectVersionConflictException}. The version tests below race writers on ONE object and pin "exactly
 * one wins per version, none lost" - they go red on a full-row last-writer-wins UPDATE.
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

        // optimistic versioning: create = v0, each update bumps by one and returns the new version
        store.create(kase(p + "v"));
        OperationalObject v0 = store.get(p + "v").orElseThrow();
        assertEquals(0, v0.version());
        OperationalObject v1 = store.update(v0.withAssignee("alice", 6L));
        assertEquals(1, v1.version(), "update returns the object at its new version");
        assertEquals(1, store.get(p + "v").orElseThrow().version());
        // a stale copy (still v0) is refused and writes nothing
        assertThrows(ObjectVersionConflictException.class, () -> store.update(v0.withAssignee("mallory", 7L)));
        assertEquals("alice", store.get(p + "v").orElseThrow().assignee());
        assertEquals(1, store.get(p + "v").orElseThrow().version());
        // the returned copy is current, so it can be updated again
        assertEquals(2, store.update(v1.withAssignee("bob", 8L)).version());
        // an unknown id is still NoSuchElement, never a conflict
        assertThrows(NoSuchElementException.class, () -> store.update(kase(p + "ghost").withVersion(3)));
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

    // ── optimistic versioning: racing writers on ONE object ───────────────────

    private static void versionRace(ObjectStore store, String p) throws Exception {
        store.create(kase(p + "hot"));
        int writers = 8;
        // (1) every writer read the SAME version-0 copy and updates it at once: exactly one wins, no write is lost.
        OperationalObject read = store.get(p + "hot").orElseThrow();
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger(), stale = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String who = "w" + i;
                fs.add(pool.submit((Callable<Void>) () -> {
                    go.await();
                    try { store.update(read.withAssignee(who, 10L)); won.incrementAndGet(); }
                    catch (ObjectVersionConflictException e) { stale.incrementAndGet(); }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get();
            assertEquals(1, won.get(), "exactly one racing update wins per version");
            assertEquals(writers - 1, stale.get());
            assertEquals(1, store.get(p + "hot").orElseThrow().version(), "one write landed, so one bump");

            // (2) 4 writers x 25 read-modify-write increments with retry: none lost, the version counts each write.
            CountDownLatch go2 = new CountDownLatch(1);
            store.create(kase(p + "ctr").withAttributes(Map.of("n", "0"), 1L));
            List<Future<?>> fs2 = new ArrayList<>();
            for (int w = 0; w < 4; w++) fs2.add(pool.submit((Callable<Void>) () -> {
                go2.await();
                for (int i = 0; i < 25; i++) {
                    while (true) {
                        OperationalObject cur = store.get(p + "ctr").orElseThrow();
                        int n = Integer.parseInt(cur.attributes().get("n"));
                        try { store.update(cur.withAttributes(Map.of("n", Integer.toString(n + 1)), 2L)); break; }
                        catch (ObjectVersionConflictException retry) { /* lost the race: re-read */ }
                    }
                }
                return null;
            }));
            go2.countDown();
            for (Future<?> f : fs2) f.get();
        } finally { pool.shutdownNow(); }
        OperationalObject ctr = store.get(p + "ctr").orElseThrow();
        assertEquals("100", ctr.attributes().get("n"), "no increment lost");
        assertEquals(100, ctr.version(), "one version bump per landed write");
    }

    @Test
    void versionRace_inMemory() throws Exception {
        try (ObjectStore s = new InMemoryObjectStore()) { versionRace(s, "VM-"); }
    }

    @Test
    void versionRace_postgres() throws Exception {
        requirePg();
        try (ObjectStore s = DbObjectStore.open(url, null, null)) { versionRace(s, "VP-"); }
    }

    // ── migration: a table created before the version column existed ─────────

    private static final String LEGACY_DDL = "CREATE TABLE inspecto_ops_objects (id VARCHAR PRIMARY KEY, "
            + "object_type VARCHAR, title VARCHAR, description VARCHAR, status VARCHAR, severity VARCHAR, "
            + "priority VARCHAR, \"owner\" VARCHAR, assignee VARCHAR, correlation_id VARCHAR, attributes VARCHAR, "
            + "created_at BIGINT, updated_at BIGINT, closed_at BIGINT)";

    private static void migration(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(LEGACY_DDL);
            st.execute("INSERT INTO inspecto_ops_objects VALUES ('old-1','CASE','legacy','d','OPEN',NULL,NULL,'ops',"
                    + "NULL,NULL,'{}',1,1,0)");
        }
        ObjectStore store = new DbObjectStore(conn);                             // initSchema ALTERs the table
        OperationalObject legacy = store.get("old-1").orElseThrow();
        assertEquals(0, legacy.version(), "a pre-migration row reads as version 0");
        assertEquals(1, store.update(legacy.withAssignee("alice", 2L)).version());
        assertThrows(ObjectVersionConflictException.class, () -> store.update(legacy.withAssignee("bob", 3L)));
        // (store.close() would close the shared connection, so it is left to the caller's try-with-resources)
        try (ObjectStore again = new DbObjectStore(conn)) {                      // idempotent: a second boot is a no-op
            assertEquals(1, again.get("old-1").orElseThrow().version());
        }
    }

    @Test
    void migration_existingTable_duckdb() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) { migration(conn); }
    }

    @Test
    void migration_existingTable_postgres() throws Exception {
        requirePg();
        String legacySchema = "inspecto_test_legacy_" + Long.toHexString(System.nanoTime());
        try (Connection admin = DriverManager.getConnection(adminUrl); Statement s = admin.createStatement()) {
            s.execute("CREATE SCHEMA " + legacySchema);
        }
        try (Connection conn = DriverManager.getConnection(adminUrl + (adminUrl.contains("?") ? "&" : "?")
                + "currentSchema=" + legacySchema)) {
            migration(conn);
        } finally {
            try (Connection admin = DriverManager.getConnection(adminUrl); Statement s = admin.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + legacySchema + " CASCADE");
            }
        }
    }
}
