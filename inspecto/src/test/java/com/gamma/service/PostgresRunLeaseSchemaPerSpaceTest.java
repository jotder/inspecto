package com.gamma.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The run lease (cross-process exclusion) on <b>real PostgreSQL</b>, schema-per-space, with many racing "pods"
 * (one {@link DbRunLease} each, distinct owners) in TWO Spaces (BACKLOG "Postgres multi-user"). Exactly one racer
 * may win a pipeline per Space; the same pipeline name in the other Space is an independent lease. Skips PER TEST
 * without {@code INSPECTO_TEST_PG_URL} / {@code -Dinspecto.test.pg.url}; throwaway schemas only.
 */
class PostgresRunLeaseSchemaPerSpaceTest {

    private static final int PODS = 10;

    private static String adminUrl;
    private static final List<String> schemas = new ArrayList<>();
    private static SpaceRoot a, b;
    private static String urlA, urlB;

    @BeforeAll
    static void connect() {
        adminUrl = System.getProperty("inspecto.test.pg.url");
        if (adminUrl == null || adminUrl.isBlank()) adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
        if (adminUrl == null || adminUrl.isBlank()) return;
        String tag = Long.toHexString(System.nanoTime());
        a = SpaceRoot.under(Path.of("target", "spaces-x", "pglease-a-" + tag));
        b = SpaceRoot.under(Path.of("target", "spaces-x", "pglease-b-" + tag));
        schemas.add(OperationalDb.schemaFor(a.id()));
        schemas.add(OperationalDb.schemaFor(b.id()));
        String[] keys = {"inspecto.db", "inspecto.db.url", "run.lease.backend"};
        String[] old = {System.getProperty(keys[0]), System.getProperty(keys[1]), System.getProperty(keys[2])};
        System.setProperty("inspecto.db", "postgres");
        System.setProperty("inspecto.db.url", adminUrl);
        System.setProperty("run.lease.backend", "postgres");
        try {
            OperationalDb.ensureSpaceSchemas(a);
            OperationalDb.ensureSpaceSchemas(b);
            urlA = OperationalDb.urlFor(OperationalDb.Family.RUN_LEASE, a, "jdbc:duckdb:unused");
            urlB = OperationalDb.urlFor(OperationalDb.Family.RUN_LEASE, b, "jdbc:duckdb:unused");
        } finally {
            for (int i = 0; i < keys.length; i++)
                if (old[i] == null) System.clearProperty(keys[i]); else System.setProperty(keys[i], old[i]);
        }
    }

    @AfterAll
    static void drop() throws Exception {
        if (adminUrl == null || adminUrl.isBlank()) return;
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            for (String sc : schemas) s.execute("DROP SCHEMA IF EXISTS " + sc + " CASCADE");
        }
    }

    @BeforeEach
    void requireServer() {
        assumeTrue(adminUrl != null && !adminUrl.isBlank(),
                "needs PostgreSQL: set INSPECTO_TEST_PG_URL or -Dinspecto.test.pg.url");
    }

    private static DbRunLease pod(String url, int i) throws Exception {
        return DbRunLease.open(url, null, null, "default", DbRunLease.SCOPE_RUN, "pod-" + i, Duration.ofSeconds(60));
    }

    @Test
    void exactlyOnePodWinsAPipelinePerSpace_andTheOtherSpaceHasAnIndependentLease() throws Exception {
        List<DbRunLease> pods = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(PODS * 2);
        List<RunLease.Claim> claims = new ArrayList<>();
        try {
            List<Future<RunLease.Claim>> fa = new ArrayList<>(), fb = new ArrayList<>();
            for (int i = 0; i < PODS; i++) {
                DbRunLease la = pod(urlA, i), lb = pod(urlB, i);
                pods.add(la);
                pods.add(lb);
                fa.add(pool.submit((Callable<RunLease.Claim>) () -> { go.await(); return la.tryAcquire("hot-pipeline"); }));
                fb.add(pool.submit((Callable<RunLease.Claim>) () -> { go.await(); return lb.tryAcquire("hot-pipeline"); }));
            }
            go.countDown();
            int winsA = 0, winsB = 0;
            for (Future<RunLease.Claim> f : fa) { RunLease.Claim c = f.get(); if (c != null) { winsA++; claims.add(c); } }
            for (Future<RunLease.Claim> f : fb) { RunLease.Claim c = f.get(); if (c != null) { winsB++; claims.add(c); } }
            assertEquals(1, winsA, "exactly one pod holds the pipeline in Space A");
            assertEquals(1, winsB, "exactly one pod holds the same pipeline name in Space B (independent lease)");
            assertTrue(claims.stream().allMatch(RunLease.Claim::isValid));
        } finally {
            pool.shutdownNow();
            claims.forEach(RunLease.Claim::close);
            for (DbRunLease p : pods) p.close();
        }
    }

    @Test
    void releasedLeaseIsReacquirableByAnotherPod_withAHigherEpoch() throws Exception {
        try (DbRunLease p1 = pod(urlA, 100); DbRunLease p2 = pod(urlA, 101)) {
            RunLease.Claim c1 = p1.tryAcquire("handover");
            assertNotNull(c1);
            assertNull(p2.tryAcquire("handover"), "held by pod-100, so pod-101 is refused");
            c1.close();
            RunLease.Claim c2 = p2.tryAcquire("handover");
            assertNotNull(c2, "free after release");
            assertFalse(c1.isValid(), "the released claim stays dead");
            assertTrue(c2.isValid());
            c2.close();
        }
    }
}
