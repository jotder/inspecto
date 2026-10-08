package com.gamma.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one operational-database selection: DuckDB for Personal, PostgreSQL for Standard.
 *
 * <p>⛔ Every test here restores the properties it touched rather than clearing them — surefire reuses
 * one JVM per module, and a cleared shared property poisons every later test in it.
 */
class OperationalDbTest {

    /** Run {@code body} with the given properties set, restoring whatever was there before. */
    private static void withProps(Map<String, String> props, Runnable body) {
        List<Map.Entry<String, String>> prior = new ArrayList<>();
        props.forEach((k, v) -> {
            prior.add(Map.entry(k, String.valueOf(System.getProperty(k))));
            System.setProperty(k, v);
        });
        try {
            body.run();
        } finally {
            for (Map.Entry<String, String> e : prior) {
                if ("null".equals(e.getValue())) System.clearProperty(e.getKey());
                else System.setProperty(e.getKey(), e.getValue());
            }
        }
    }

    @Test
    void duckdbIsTheDefault_soAPersonalDeploymentNeedsNoDatabaseSettingsAtAll() {
        assertFalse(OperationalDb.postgres());
        assertNull(OperationalDb.url(), "no shared URL — each store keeps its own per-space DuckDB file");
        assertEquals("jdbc:duckdb:/spaces/a/duckdb/jobs.db",
                OperationalDb.urlFor(OperationalDb.Family.JOB_RUNS, "jdbc:duckdb:/spaces/a/duckdb/jobs.db"));
    }

    @Test
    void oneSelectionMovesEveryOperationalStore_soAStandardDeploymentCannotHalfMigrate() {
        withProps(Map.of("inspecto.db", "postgres",
                        "inspecto.db.url", "jdbc:postgresql://db:5432/inspecto"),
                () -> {
                    assertTrue(OperationalDb.postgres());
                    // Named once, honoured by every family — the space's DuckDB default is not consulted.
                    // ⚠ Iterates the ROSTER rather than a hand-listed copy (2026-08-15): a family added
                    // to Family without honouring the shared URL now fails here instead of going unnoticed.
                    // The roster is OPEN (MODULE-REORG-P1-FAMILY, 2026-10-08): this module sees the CORE families
                    // only - 12 - and `inspecto-ops` contributes the four Operational Object families (so an
                    // Enterprise classpath lists 16; the ops module's own test pins its four and the manifest).
                    // History of the whole roster: 11 -> 12 on 2026-09-07 DELIVERY_RECEIPTS; 12 -> 13 EVENTS (D6);
                    // 13 -> 14 RUN_LEASE (B1); 14 -> 15 INBOX_REGISTRY; 15 -> 16 on 2026-10-07 ALERTS.
                    // ⚠ The count has mirrors outside this file - `tools/check-family-count.mjs` fails the build
                    // when they drift (core from this enum + contributed from the module manifests).
                    // The loop below is the assertion that matters: a family honours the one shared URL too.
                    assertEquals(12, OperationalDb.Family.values().length, "the core roster is twelve families");
                    assertEquals(12, OperationalDb.all().size(), "no module contributes on the processor's own classpath");
                    for (StoreFamily family : OperationalDb.all()) {
                        assertEquals("jdbc:postgresql://db:5432/inspecto",
                                OperationalDb.urlFor(family, "jdbc:duckdb:/spaces/a/duckdb/x.db"),
                                family.name());
                    }
                });
    }

    @Test
    void aPerFamilyUrlStillWins_whichIsWhatKeepsExistingDeploymentsWorking() {
        withProps(Map.of("inspecto.db", "postgres",
                        "inspecto.db.url", "jdbc:postgresql://db:5432/inspecto",
                        "jobs.db.url", "jdbc:duckdb:/keep/me/here.db"),
                () -> assertEquals("jdbc:duckdb:/keep/me/here.db",
                        OperationalDb.urlFor(OperationalDb.Family.JOB_RUNS, "jdbc:duckdb:/spaces/a/duckdb/jobs.db")));
    }

    @Test
    void credentialsFallBackToTheSharedOnes_butAFamilyMayOverrideThem() {
        withProps(Map.of("inspecto.db", "postgres",
                        "inspecto.db.url", "jdbc:postgresql://db:5432/inspecto",
                        "inspecto.db.user", "svc",
                        "inspecto.db.password", "s3cret",
                        "status.db.user", "status_reader"),
                () -> {
                    assertEquals("svc", OperationalDb.userFor(OperationalDb.Family.ALERTS));
                    assertEquals("s3cret", OperationalDb.passwordFor(OperationalDb.Family.ALERTS));
                    assertEquals("status_reader", OperationalDb.userFor(OperationalDb.Family.STATUS));
                    assertEquals("s3cret", OperationalDb.passwordFor(OperationalDb.Family.STATUS),
                            "an overridden user does not drag the password with it");
                });
    }

    /**
     * PG-1's second decision: {@code -Dinspecto.db.password} takes a {@code SecretResolver} reference —
     * the {@code auth.oidc.clientSecret} precedent — so the value never has to sit on the command line.
     * {@code ${SYS:…}} is the scope a test can control; a literal must keep passing through unchanged,
     * and the per-family override must resolve too (a family pointed at a second reference gets that
     * secret, not the shared one's).
     */
    @Test
    void aPasswordReferenceIsResolvedAtUse_andALiteralPassesThrough() {
        withProps(Map.of("inspecto.db.password", "${SYS:test.pg.secret}",
                        "test.pg.secret", "resolved-secret",
                        "status.db.password", "${SYS:test.status.secret}",
                        "test.status.secret", "status-secret"),
                () -> {
                    assertEquals("resolved-secret", OperationalDb.passwordFor(OperationalDb.Family.ALERTS));
                    assertEquals("status-secret", OperationalDb.passwordFor(OperationalDb.Family.STATUS),
                            "a per-family reference resolves to ITS secret, not the shared one");
                });
        withProps(Map.of("inspecto.db.password", "plain-literal"),
                () -> assertEquals("plain-literal", OperationalDb.passwordFor(OperationalDb.Family.ALERTS)));
    }

    @Test
    void postgresWithoutAUrlFailsAtBoot_namingTheSettingToFix() {
        withProps(Map.of("inspecto.db", "postgres"), () -> {
            IllegalStateException boom = assertThrows(IllegalStateException.class, OperationalDb::verifySelectable);
            assertTrue(boom.getMessage().contains("inspecto.db.url"), boom.getMessage());
        });
    }

    @Test
    void duckdbNeverFailsTheBootCheck() {
        withProps(Map.of("inspecto.db", "duckdb"), OperationalDb::verifySelectable);
    }

    /**
     * ⚠ The driver IS on the test classpath (inspecto-engine pulls it at test scope), so this asserts the
     * reachable half: a fully-specified Postgres selection passes the gate. The refusal path — no driver,
     * i.e. the shipped Personal bundle — cannot be exercised from a JVM that has the driver, and is
     * covered by the message assertion above plus the missing-URL case.
     */
    @Test
    void aFullySpecifiedPostgresSelectionPassesTheBootCheckWhenTheDriverIsPresent() {
        assumeDriverPresent();
        withProps(Map.of("inspecto.db", "postgres",
                        "inspecto.db.url", "jdbc:postgresql://db:5432/inspecto"),
                OperationalDb::verifySelectable);
    }

    // ── OBJECTS-BACKEND-DEFAULT-MEMORY-1 (operator decision 2026-09-25) ────────────────────────────────
    // The four object families moved to inspecto-ops (ObjectFamiliesOperationalDbTest there). What the
    // PROCESSOR owns is the value check and the absent-module behaviour below.

    /** Without the ops module no family consumes {@code objects.backend}, so nothing is refused beyond the value. */
    @Test
    void withoutTheOpsModule_postgresObjectsBackendHasNothingToRefuse() {
        assertTrue(OperationalDb.loaded().isEmpty());
        withProps(Map.of(OperationalDb.OBJECTS_BACKEND, "postgres"), OperationalDb::verifyObjectsBackend);
    }

    @Test
    void anUnknownObjectsBackendFailsAtBoot_ratherThanSilentlyMeaningMemory() {
        withProps(Map.of(OperationalDb.OBJECTS_BACKEND, "duckdb"), () -> {
            IllegalStateException boom = assertThrows(IllegalStateException.class, OperationalDb::verifySelectable);
            assertTrue(boom.getMessage().contains("-Dobjects.backend=duckdb"), boom.getMessage());
        });
    }

    private static void assumeDriverPresent() {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "no PG driver on this test classpath");
        }
    }
}
