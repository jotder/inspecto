package com.gamma.service;

import com.gamma.job.DbJobRunStore;
import com.gamma.job.JobRun;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Schema-per-space wiring (BACKLOG "Postgres multi-user" P3): the URL derivation and fail-closed rules always run;
 * the isolation proof needs a real PostgreSQL ({@code INSPECTO_TEST_PG_URL} / {@code -Dinspecto.test.pg.url}) and is
 * skipped PER TEST — never in {@code @BeforeAll}, which would drop the class from the totals.
 */
class PostgresSchemaPerSpaceTest {

    private static final String PG = "jdbc:postgresql://db:5432/inspecto";

    private static String adminUrl;
    private static final List<String> created = new ArrayList<>();

    @BeforeAll
    static void readServer() {
        adminUrl = System.getProperty("inspecto.test.pg.url");
        if (adminUrl == null || adminUrl.isBlank()) adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
    }

    @AfterAll
    static void dropThrowawaySchemas() throws Exception {
        if (adminUrl == null || adminUrl.isBlank()) return;
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            for (String schema : created) s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static SpaceRoot space(String id) { return SpaceRoot.under(Path.of("target", "spaces-x", id)); }

    private static void withShared(String url, Runnable body) {
        String oldJobs = System.getProperty("jobs.backend"), oldDb = System.getProperty("inspecto.db"),
                oldUrl = System.getProperty("inspecto.db.url");
        System.setProperty("jobs.backend", "postgres");
        System.setProperty("inspecto.db", "postgres");
        System.setProperty("inspecto.db.url", url);
        try {
            body.run();
        } finally {
            restore("jobs.backend", oldJobs);
            restore("inspecto.db", oldDb);
            restore("inspecto.db.url", oldUrl);
        }
    }

    private static void restore(String k, String v) {
        if (v == null) System.clearProperty(k); else System.setProperty(k, v);
    }

    @Test
    void postgresUrlGainsTheSpacesSchema_withAnExistingQueryOrWithout() {
        withShared(PG, () -> assertEquals(PG + "?currentSchema=space_north_east",
                OperationalDb.urlFor(OperationalDb.Family.OBJECTS, space("north-east"), "jdbc:duckdb:x")));
        withShared(PG + "?user=u", () -> assertEquals(PG + "?user=u&currentSchema=space_a",
                OperationalDb.urlFor(OperationalDb.Family.OBJECTS, space("a"), "jdbc:duckdb:x")));
    }

    @Test
    void duckdbLegacyAndExplicitSchemaAreUntouched() {
        assertEquals("jdbc:duckdb:x", OperationalDb.urlFor(OperationalDb.Family.OBJECTS, space("a"), "jdbc:duckdb:x"));
        withShared(PG, () -> assertEquals(PG,
                OperationalDb.urlFor(OperationalDb.Family.OBJECTS, SpaceRoot.legacy(), "jdbc:duckdb:x"),
                "the single-tenant legacy root keeps its historical unscoped URL"));
        withShared(PG + "?currentSchema=ops", () -> assertEquals(PG + "?currentSchema=ops",
                OperationalDb.urlFor(OperationalDb.Family.OBJECTS, space("a"), "jdbc:duckdb:x")));
    }

    @Test
    void unsafeOrOverlongIdsAndNonPostgresUrlsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> OperationalDb.schemaFor("a;drop schema public"));
        assertThrows(IllegalArgumentException.class, () -> OperationalDb.schemaFor("Upper"));
        assertThrows(IllegalArgumentException.class, () -> OperationalDb.schemaFor(null));
        assertThrows(IllegalArgumentException.class, () -> OperationalDb.schemaFor("a".repeat(63)),
                "space_ + 63 chars exceeds PostgreSQL's 63-byte identifier limit");
        assertThrows(IllegalArgumentException.class, () -> OperationalDb.withSchema("jdbc:duckdb:x", "space_a"));
        assertEquals("space_a_b", OperationalDb.schemaFor("a-b"));
        assertNotEquals(OperationalDb.schemaFor("a-b"), OperationalDb.schemaFor("a"), "distinct ids stay distinct");
    }

    @Test
    void twoSpacesOnOnePostgresCannotSeeEachOthersRows() throws Exception {
        assumeTrue(adminUrl != null && !adminUrl.isBlank(),
                "needs a PostgreSQL server: set INSPECTO_TEST_PG_URL or -Dinspecto.test.pg.url");
        String tag = Long.toHexString(System.nanoTime());
        SpaceRoot a = space("pgt-a-" + tag), b = space("pgt-b-" + tag);
        created.add(OperationalDb.schemaFor(a.id()));
        created.add(OperationalDb.schemaFor(b.id()));
        withShared(adminUrl, () -> {
            OperationalDb.ensureSpaceSchemas(a);
            OperationalDb.ensureSpaceSchemas(b);
            OperationalDb.ensureSpaceSchemas(a);   // idempotent
            String urlA = OperationalDb.urlFor(OperationalDb.Family.JOB_RUNS, a, "jdbc:duckdb:x");
            String urlB = OperationalDb.urlFor(OperationalDb.Family.JOB_RUNS, b, "jdbc:duckdb:x");
            try (DbJobRunStore sa = DbJobRunStore.open(urlA); DbJobRunStore sb = DbJobRunStore.open(urlB)) {
                sa.record(new JobRun("run-a", "only-in-a", "PIPELINE", "SCHEDULE",
                        "2026-07-07T00:00:00Z", "2026-07-07T00:01:00Z", "SUCCESS", 100, "ok"));
                // The probe WOULD succeed against a shared schema: both stores would read the same table.
                assertEquals(1, sa.recentRuns(10, "only-in-a").size(), "A sees its own row");
                assertTrue(sb.recentRuns(10, "only-in-a").isEmpty(), "B must not see A's row");
                assertTrue(sb.recentRuns(10, null).isEmpty(), "B's table is empty");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('"
                     + OperationalDb.schemaFor(a.id()) + "','" + OperationalDb.schemaFor(b.id()) + "')")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1), "both Space schemas were created");
        }
    }
}
