package com.gamma.ops;

import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two residuals of the "Postgres multi-user" row that need no second host, on <b>real PostgreSQL</b>:
 * <ul>
 *   <li><b>Two nodes</b> — a second JVM (its own driver, its own pool, as a second host would have) and this JVM
 *   run read-modify-write increments on ONE object in ONE Space at the same time. Nothing in-process is shared, so
 *   only the {@code WHERE version=?} guard in Postgres can keep an increment from being lost.</li>
 *   <li><b>Soak</b> — writers across two Spaces for a bounded time ({@code -Dinspecto.test.pg.soak.seconds},
 *   default 10): no increment lost, no unexpected error, and the server-side connection count stays inside the
 *   per-process budget for the whole run.</li>
 * </ul>
 * Skips PER TEST without {@code INSPECTO_TEST_PG_URL} / {@code -Dinspecto.test.pg.url}; throwaway schemas.
 */
class PostgresTwoNodeSoakTest {

    private static final int INCREMENTS_PER_NODE = 60;
    /** {@code -Ddb.pool.process.size} default; the soak's connection ceiling above the pre-run baseline. */
    private static final int PROCESS_POOL_BUDGET = 20;

    private static String adminUrl;
    private static final List<String> schemas = new ArrayList<>();

    @BeforeAll
    static void connect() {
        adminUrl = System.getProperty("inspecto.test.pg.url");
        if (adminUrl == null || adminUrl.isBlank()) adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
    }

    @AfterAll
    static void drop() throws Exception {
        if (schemas.isEmpty()) return;
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            for (String sc : schemas) s.execute("DROP SCHEMA IF EXISTS " + sc + " CASCADE");
        }
    }

    @BeforeEach
    void requireServer() {
        assumeTrue(adminUrl != null && !adminUrl.isBlank(),
                "needs PostgreSQL: set INSPECTO_TEST_PG_URL or -Dinspecto.test.pg.url");
    }

    private static String newSchemaUrl(String prefix) throws Exception {
        String schema = prefix + Long.toHexString(System.nanoTime());
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        schemas.add(schema);
        return adminUrl + (adminUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private static OperationalObject counter(String id) {
        return OperationalObject.builder(ObjectType.CASE).id(id).title("counter " + id).status("OPEN")
                .owner("ops").attr("n", "0").build();
    }

    /** One read-modify-write increment of attribute {@code n}, retried on a lost version race. Returns the retries. */
    static int increment(ObjectStore store, String id) {
        int retries = 0;
        while (true) {
            OperationalObject cur = store.get(id).orElseThrow();
            int n = Integer.parseInt(cur.attributes().get("n"));
            try {
                store.update(cur.withAttributes(Map.of("n", Integer.toString(n + 1)), System.currentTimeMillis()));
                return retries;
            } catch (ObjectVersionConflictException lost) {
                retries++;
            }
        }
    }

    /** The second node: {@code <url> <objectId> <count>}. Waits for a {@code go} line on stdin so both start together. */
    public static void main(String[] args) throws Exception {
        try (ObjectStore store = DbObjectStore.open(args[0], null, null)) {
            System.out.println("ready");
            System.out.flush();
            if (System.in.read() < 0) return;
            int retries = 0;
            for (int i = 0; i < Integer.parseInt(args[2]); i++) retries += increment(store, args[1]);
            System.out.println("retries=" + retries);
        }
    }

    @Test
    void twoNodes_racingIncrementsOnOneObject_noneLost() throws Exception {
        String url = newSchemaUrl("inspecto_test_2node_");
        try (ObjectStore store = DbObjectStore.open(url, null, null)) {
            store.create(counter("shared"));
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process node = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    PostgresTwoNodeSoakTest.class.getName(), url, "shared", Integer.toString(INCREMENTS_PER_NODE))
                    .redirectErrorStream(true).start();
            try {
                var out = new java.io.BufferedReader(new java.io.InputStreamReader(node.getInputStream()));
                String line;
                while ((line = out.readLine()) != null && !line.equals("ready")) { /* driver/log noise */ }
                assertEquals("ready", line, "the second node never came up");
                node.getOutputStream().write('\n');
                node.getOutputStream().flush();
                int localRetries = 0;
                for (int i = 0; i < INCREMENTS_PER_NODE; i++) localRetries += increment(store, "shared");
                assertTrue(node.waitFor(60, TimeUnit.SECONDS), "the second node did not finish");
                String rest = new String(node.getInputStream().readAllBytes());
                assertEquals(0, node.exitValue(), "second node failed: " + rest);
                OperationalObject after = store.get("shared").orElseThrow();
                assertEquals(Integer.toString(2 * INCREMENTS_PER_NODE), after.attributes().get("n"),
                        "an increment was lost between nodes (local retries " + localRetries + ", node " + rest.trim() + ")");
                assertEquals(2 * INCREMENTS_PER_NODE, after.version(), "one version bump per landed write");
            } finally {
                node.destroyForcibly();
            }
        }
    }

    @Test
    void soak_twoSpaces_noLostWrite_connectionsInsideBudget() throws Exception {
        long seconds = Long.getLong("inspecto.test.pg.soak.seconds", 10L);
        String[] urls = {newSchemaUrl("inspecto_test_soak_a_"), newSchemaUrl("inspecto_test_soak_b_")};
        int objectsPerSpace = 3, writersPerSpace = 4;
        int baseline = backends();
        AtomicInteger peak = new AtomicInteger(baseline);
        AtomicLong landed = new AtomicLong();
        List<ObjectStore> stores = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(urls.length * writersPerSpace + 1);
        try {
            for (String u : urls) {
                ObjectStore s = DbObjectStore.open(u, null, null);
                stores.add(s);
                for (int o = 0; o < objectsPerSpace; o++) s.create(counter("c" + o));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Long>> writers = new ArrayList<>();
            for (ObjectStore s : stores)
                for (int w = 0; w < writersPerSpace; w++) {
                    int seed = w;
                    writers.add(pool.submit((Callable<Long>) () -> {
                        go.await();
                        long mine = 0;
                        for (int i = seed; System.nanoTime() < deadline; i++) {
                            increment(s, "c" + (i % objectsPerSpace));
                            mine++;
                        }
                        return mine;
                    }));
                }
            Future<?> sampler = pool.submit((Callable<Void>) () -> {
                while (System.nanoTime() < deadline) {
                    peak.accumulateAndGet(backends(), Math::max);
                    Thread.sleep(200);
                }
                return null;
            });
            go.countDown();
            long perSpace0 = 0;
            for (int i = 0; i < writers.size(); i++) {
                long n = writers.get(i).get();
                landed.addAndGet(n);
                if (i < writersPerSpace) perSpace0 += n;
            }
            sampler.get();

            long[] expected = {perSpace0, landed.get() - perSpace0};
            for (int sp = 0; sp < stores.size(); sp++) {
                long sum = 0, versions = 0;
                for (int o = 0; o < objectsPerSpace; o++) {
                    OperationalObject c = stores.get(sp).get("c" + o).orElseThrow();
                    sum += Long.parseLong(c.attributes().get("n"));
                    versions += c.version();
                }
                assertEquals(expected[sp], sum, "Space " + sp + " lost an increment during the soak");
                assertEquals(expected[sp], versions, "Space " + sp + ": one version bump per landed write");
            }
            assertTrue(landed.get() > 0, "the soak landed no writes");
            // the sampler's own connection is counted too, hence + 1
            assertTrue(peak.get() - baseline <= PROCESS_POOL_BUDGET + 1,
                    "connections grew past the per-process budget: baseline " + baseline + ", peak " + peak.get());
        } finally {
            pool.shutdownNow();
            for (ObjectStore s : stores) s.close();
        }
    }

    /** Server-side backends on this database, counted through a fresh connection. */
    private static int backends() throws Exception {
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
