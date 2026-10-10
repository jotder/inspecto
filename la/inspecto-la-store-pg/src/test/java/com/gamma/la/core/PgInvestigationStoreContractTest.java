package com.gamma.la.core;

import com.gamma.la.store.pg.PgInvestigationStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link InvestigationStoreContract} (la-core's test-jar: the SAME 29 cases the filesystem store runs) over
 * {@link PgInvestigationStore} on a real PostgreSQL, one throwaway schema per case, plus what only a database can show.
 *
 * <p>Lives in package {@code com.gamma.la.core} because the contract is package-private there.
 *
 * <p><b>It runs against an EXISTING server</b> (the harness the other Postgres tests use): set the environment variable
 * {@code INSPECTO_TEST_PG_URL} (or {@code -Dinspecto.test.pg.url}); with neither, every case SKIPS and says so. The skip is per
 * case (an {@code assumeTrue} in {@link #fresh()}), so the count stays visible: <b>read "Tests run" and "Skipped"</b>, an exit code
 * of 0 proves nothing here.
 *
 * <pre>{@code
 * MAVEN_OPTS="-Duser.timezone=Asia/Kolkata" \
 * INSPECTO_TEST_PG_URL='jdbc:postgresql://localhost:5432/postgres?user=postgres&password=postgres' \
 * mvn -o -B test -Pedition-enterprise -pl :inspecto-la-store-pg -am \
 *     -Dtest=PgInvestigationStoreContractTest -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0
 * }</pre>
 * The three traps are those of {@code PostgresStateStoreTest}: the env var (a {@code -D} URL loses its password to {@code &} on Windows),
 * {@code -DforkCount=0} with {@code MAVEN_OPTS} (an {@code argLine} never reaches the fork, and PG 18 refuses the {@code Asia/Calcutta}
 * alias a Windows JVM reports), and the driver, which this module declares itself because test scope is not transitive.
 */
class PgInvestigationStoreContractTest extends InvestigationStoreContract {

    private static String url() {
        String u = System.getenv("INSPECTO_TEST_PG_URL");
        return u != null && !u.isBlank() ? u : System.getProperty("inspecto.test.pg.url");
    }

    private final List<String> schemas = new ArrayList<>();

    /**
     * ONE small pool for the whole class, the production path: 4 connections for the contract's 16-writer races, so a store method that
     * held two connections at once (a nested borrow) would starve the pool and the case would time out instead of passing.
     */
    private static com.gamma.util.ConnectionSource pool;

    private static synchronized com.gamma.util.ConnectionSource pool(String url) throws Exception {
        if (pool == null) {
            String before = System.getProperty("db.pool.size");
            System.setProperty("db.pool.size", "4");
            try {
                pool = com.gamma.util.JdbcDrivers.source(url, null, null, "la-test");
            } finally {
                if (before == null) System.clearProperty("db.pool.size");
                else System.setProperty("db.pool.size", before);
            }
        }
        return pool;
    }

    @AfterAll
    static synchronized void closePool() {
        if (pool != null) pool.close();
        pool = null;
    }

    @Override
    InvestigationStore fresh() throws Exception {
        String url = url();
        assumeTrue(url != null, "no Postgres: set INSPECTO_TEST_PG_URL (jdbc:postgresql://host:5432/db?user=..&password=..) to run this");
        String schema = "la_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        schemas.add(schema);
        return new PgInvestigationStore(pool(url), schema);
    }

    @Override
    InvestigationStore freshWithSetLimit(long bytes) throws Exception {
        String url = url();
        assumeTrue(url != null, "no Postgres: set INSPECTO_TEST_PG_URL (jdbc:postgresql://host:5432/db?user=..&password=..) to run this");
        String schema = "la_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        schemas.add(schema);
        return new PgInvestigationStore(pool(url), schema, () -> bytes, com.gamma.la.core.InvestigationSetBudget.DEFAULT);
    }

    @Override
    InvestigationStore freshWithInvestigationBudget(long bytes) throws Exception {
        String url = url();
        assumeTrue(url != null, "no Postgres: set INSPECTO_TEST_PG_URL (jdbc:postgresql://host:5432/db?user=..&password=..) to run this");
        String schema = "la_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        schemas.add(schema);
        lastSchema = schema;
        return new PgInvestigationStore(pool(url), schema, com.gamma.la.core.WorkingSetSizeLimit.DEFAULT, () -> bytes);
    }

    private String lastSchema;

    @Test
    void aTableMadeBeforeTheCounterExistedIsCountedOnceLazilyAndStillEnforced() throws Exception {
        InvestigationStore s = freshWithInvestigationBudget(3000);
        s.create("a", "{}");
        for (int i = 1; i <= 3; i++) s.append(InvestigationStore.Scope.main("a"), i - 1, i, "{\"step\":" + i + "}", "x".repeat(1000));
        try (java.sql.Connection c = DriverManager.getConnection(url()); java.sql.Statement st = c.createStatement()) {
            st.execute("UPDATE " + lastSchema + ".la_investigation SET set_bytes = NULL");   // what an older table looks like
        }
        com.gamma.spi.auth.ApiException e = assertThrows(com.gamma.spi.auth.ApiException.class,
                () -> s.append(InvestigationStore.Scope.main("a"), 3, 4, "{\"step\":4}", "x"));
        assertEquals(413, e.status);
        assertTrue(e.getMessage().contains("holds 3000 bytes"), e.getMessage());
    }

    @Test
    void firstStartOfOneSpaceOnManyPodsAtOnceBootstrapsTheSchemaOnceAndAllSucceed() throws Exception {
        String url = url();
        assumeTrue(url != null, "no Postgres");
        String schema = "la_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        schemas.add(schema);
        int pods = 8;
        java.util.concurrent.CyclicBarrier go = new java.util.concurrent.CyclicBarrier(pods);
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(pods);
        try {
            List<java.util.concurrent.Future<InvestigationStore>> started = new ArrayList<>();
            for (int i = 0; i < pods; i++)   // each "pod" has its own connections, as two JVMs would
                started.add(ex.submit(() -> {
                    go.await();
                    return new PgInvestigationStore(() -> DriverManager.getConnection(url), schema);
                }));
            for (var f : started) assertNotNull(f.get(60, java.util.concurrent.TimeUnit.SECONDS), "every pod came up, none lost the DDL race");
        } finally {
            ex.shutdownNow();
        }
        InvestigationStore s = new PgInvestigationStore(() -> DriverManager.getConnection(url), schema);
        assertTrue(s.create("x", "{}"));
    }

    @Test
    void theSealedMainRecordsCanNeitherBeChangedNorRemovedButADraftsOwnRowsCanBe() throws Exception {
        InvestigationStore s = fresh();
        s.create("ao", "{}");
        s.append(main("ao"), 0, 1, "{\"step\":1}", "{\"set\":1}");
        s.appendMember("ao", 0, "{\"member\":1}");
        s.appendReference("ao", "k", "{\"ref\":1}", 5);
        String schema = schemas.get(schemas.size() - 1);
        try (Connection c = DriverManager.getConnection(url()); Statement st = c.createStatement()) {
            for (String sql : List.of(
                    "UPDATE " + schema + ".la_log SET line = 'x' WHERE draft = ''",
                    "DELETE FROM " + schema + ".la_log WHERE draft = ''",
                    "UPDATE " + schema + ".la_set SET body = 'x' WHERE draft = ''",
                    "DELETE FROM " + schema + ".la_set WHERE draft = ''",
                    "UPDATE " + schema + ".la_member SET line = 'x'",
                    "DELETE FROM " + schema + ".la_member",
                    "UPDATE " + schema + ".la_reference SET line = 'x'",
                    "DELETE FROM " + schema + ".la_reference",
                    "TRUNCATE " + schema + ".la_log",
                    "TRUNCATE " + schema + ".la_set",
                    "TRUNCATE " + schema + ".la_member",
                    "TRUNCATE " + schema + ".la_reference")) {
                java.sql.SQLException refused = assertThrows(java.sql.SQLException.class, () -> st.execute(sql), sql);
                assertEquals("23000", refused.getSQLState(), sql + ": " + refused.getMessage());
            }
        }
        assertEquals(List.of("{\"step\":1}"), s.log(main("ao")), "nothing changed");
        assertEquals("{\"set\":1}", s.set("ao", 1).orElseThrow());
        // the Draft's own rows are deleted by a close: the trigger must not bind them
        String d = DraftStore.newId();
        s.createDraft("ao", d, "{\"draftId\":\"" + d + "\",\"actor\":\"a\"}", "a", 9);
        s.append(InvestigationStore.Scope.draft("ao", d), 0, 1, "{\"step\":1}", "{\"set\":1}");
        assertEquals(java.util.Optional.of(true), s.closeDraft("ao", d, null, own -> "{}"));
        assertEquals(List.of("{\"step\":1}"), s.log(main("ao")));
    }

    @Test
    void aDatabaseThatGoesAwayIs503NotAnotherBackendAndTheStoreRecoversWhenItReturns() throws Exception {
        String url = url();
        assumeTrue(url != null, "no Postgres");
        String schema = "la_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        schemas.add(schema);
        boolean[] down = {false};
        InvestigationStore s = new PgInvestigationStore(() -> {
            if (down[0]) throw new java.sql.SQLTransientConnectionException("connection refused", "08001");
            return DriverManager.getConnection(url);
        }, schema);
        s.create("o", "{}");
        down[0] = true;
        com.gamma.spi.auth.ApiException e = assertThrows(com.gamma.spi.auth.ApiException.class, () -> s.header("o"));
        assertEquals(503, com.gamma.control.ApiExceptionPeek.status(e));
        assertEquals(com.gamma.spi.auth.ErrorCodes.CAPABILITY_UNAVAILABLE, com.gamma.control.ApiExceptionPeek.code(e));
        down[0] = false;
        assertEquals("{}", s.header("o").orElseThrow(), "the same store serves again: nothing was cached about the outage");
    }

    @Test
    void selectedAndUnreachableIs503WithNoFallbackAndNothingCachedSoTheNextRequestRetries() {
        String before = System.getProperty(InvestigationStores.BACKEND_PROPERTY);
        System.setProperty(InvestigationStores.BACKEND_PROPERTY, "db");
        System.setProperty(InvestigationStores.URL_PROPERTY, "jdbc:postgresql://127.0.0.1:1/none?connectTimeout=2");
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                com.gamma.spi.auth.ApiException e = assertThrows(com.gamma.spi.auth.ApiException.class,
                        () -> InvestigationStores.of(java.nio.file.Path.of("spaces", "s1", "config")));
                assertEquals(503, com.gamma.control.ApiExceptionPeek.status(e));
                assertTrue(e.getMessage().contains("cannot be used"), e.getMessage());
            }
        } finally {
            System.clearProperty(InvestigationStores.URL_PROPERTY);
            if (before == null) System.clearProperty(InvestigationStores.BACKEND_PROPERTY);
            else System.setProperty(InvestigationStores.BACKEND_PROPERTY, before);
        }
    }

    @Test
    void selectedAndReachableTheRegisteredProviderServesEachSpaceItsOwnSchemaAndCachesTheStore() throws Exception {
        String url = url();
        assumeTrue(url != null, "no Postgres");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        java.nio.file.Path a = java.nio.file.Path.of("spaces", "lasel-a-" + suffix, "config");
        java.nio.file.Path b = java.nio.file.Path.of("spaces", "lasel-b-" + suffix, "config");
        schemas.add("space_lasel_a_" + suffix);
        schemas.add("space_lasel_b_" + suffix);
        System.setProperty(InvestigationStores.BACKEND_PROPERTY, "db");
        System.setProperty(InvestigationStores.URL_PROPERTY, url);
        try {
            InvestigationStore sa = InvestigationStores.of(a);
            assertInstanceOf(PgInvestigationStore.class, sa);
            assertSame(sa, InvestigationStores.of(a), "one store (and one schema bootstrap) per Space, not one per request");
            sa.create("only-in-a", "{}");
            assertEquals(List.of("only-in-a"), InvestigationStores.of(a).ids());
            assertEquals(List.of(), InvestigationStores.of(b).ids(), "another Space is another schema");
        } finally {
            System.clearProperty(InvestigationStores.BACKEND_PROPERTY);
            System.clearProperty(InvestigationStores.URL_PROPERTY);
        }
    }

    @AfterEach
    void dropSchemas() throws Exception {
        String url = url();
        if (url == null) return;
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            for (String s : schemas) st.execute("DROP SCHEMA IF EXISTS " + s + " CASCADE");
        }
        schemas.clear();
    }

    private static InvestigationStore.Scope main(String id) {
        return InvestigationStore.Scope.main(id);
    }

    @Test
    void theColumnsHoldTextNotJsonb_soBytesSurviveEvenWhereJsonbWouldReorderThem() throws Exception {
        InvestigationStore s = fresh();
        s.create("t", "{\"b\":1,\"a\":[ 1,  2 ]}");
        s.append(main("t"), 0, 1, "{\"z\":1,\"a\":2,  \"dup\":1,\"dup\":2}", "{\"k\":1.0e0, \"k2\":1.50}");
        assertEquals("{\"z\":1,\"a\":2,  \"dup\":1,\"dup\":2}", s.log(main("t")).get(0), "duplicate keys and spacing: jsonb would collapse both");
        assertEquals("{\"k\":1.0e0, \"k2\":1.50}", s.set("t", 1).orElseThrow(), "number spelling is kept");
        int checked = 0;
        try (Connection c = DriverManager.getConnection(url()); var rs = c.getMetaData().getColumns(null, schemas.get(0), "la_%", null)) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                if (List.of("header", "line", "body", "marker", "case_link").contains(col)) {
                    assertEquals("text", rs.getString("TYPE_NAME"), rs.getString("TABLE_NAME") + "." + col);
                    checked++;
                }
            }
        }
        assertTrue(checked >= 8, "the type check really looked at the columns: " + checked);
    }

    @Test
    void aFailedAppendLeavesNoLineWithoutItsSet() throws Exception {
        InvestigationStore s = fresh();
        s.create("a", "{}");
        s.append(main("a"), 0, 1, "{\"step\":1}", "{\"set\":1}");
        // the set of step 1 is already taken: the second append's set insert fails AFTER its line insert, in the same transaction
        assertThrows(java.io.IOException.class, () -> s.append(main("a"), 1, 1, "{\"step\":2}", "{\"set\":\"clash\"}"));
        assertEquals(List.of("{\"step\":1}"), s.log(main("a")), "the line was rolled back with the set that could not be written");
        assertEquals(1, s.version(main("a")));
        s.append(main("a"), 1, 2, "{\"step\":2}", "{\"set\":2}");
        assertEquals(2, s.version(main("a")));
    }

    @Test
    void logTokenMovesWithEveryChangeToTheScopesLog() throws Exception {
        InvestigationStore s = fresh();
        s.create("k", "{}");
        String m0 = s.logToken(main("k"));
        s.append(main("k"), 0, 1, "{}", "{}");
        assertNotEquals(m0, s.logToken(main("k")));
        String d = DraftStore.newId();
        s.createDraft("k", d, "{\"draftId\":\"" + d + "\",\"actor\":\"a\"}", "a", 9);
        var sc = InvestigationStore.Scope.draft("k", d);
        String d0 = s.logToken(sc);
        s.append(sc, 0, 2, "{}", "{}");
        String d1 = s.logToken(sc);
        assertNotEquals(d0, d1);
        s.closeDraft("k", d, null, own -> "{}");
        assertNotEquals(d1, s.logToken(sc), "a close changes the token too");
        assertNotEquals(s.cacheKey(main("k")), s.cacheKey(sc));
    }

    @Test
    void aDraftIdlesHibernatesRehydratesAndExpiresOnTheClock() throws Exception {
        InvestigationStore s = fresh();
        s.create("i", "{}");
        java.time.Clock before = DraftLifecycle.clock;
        try {
            java.time.Instant t0 = java.time.Instant.parse("2026-10-04T10:00:00Z");
            DraftLifecycle.clock = java.time.Clock.fixed(t0, java.time.ZoneOffset.UTC);
            String d = DraftStore.newId();
            s.createDraft("i", d, "{\"draftId\":\"" + d + "\",\"actor\":\"a\"}", "a", 9);
            DraftLifecycle.clock = java.time.Clock.fixed(t0.plusSeconds(2 * 3600), java.time.ZoneOffset.UTC);
            assertEquals(java.time.Duration.ofHours(2), s.draftIdle("i", d));
            assertFalse(s.hibernateDraft("i", d, java.time.Duration.ofHours(3)), "not idle long enough");
            assertTrue(s.hibernateDraft("i", d, java.time.Duration.ofHours(1)));
            assertFalse(s.hibernateDraft("i", d, java.time.Duration.ofHours(1)), "already hibernated");
            assertEquals(InvestigationStore.DraftState.HIBERNATED, s.draftState("i", d));
            assertEquals(1, s.openDraftCount(), "a hibernated Draft still holds its seat");
            s.touchDraft("i", d);
            assertEquals(java.time.Duration.ZERO, s.draftIdle("i", d));
            assertTrue(s.rehydrateDraft("i", d));
            assertFalse(s.rehydrateDraft("i", d));
            assertEquals(InvestigationStore.DraftState.OPEN, s.draftState("i", d));
            DraftLifecycle.clock = java.time.Clock.fixed(t0.plusSeconds(2 * 3600 + 31L * 86400), java.time.ZoneOffset.UTC);
            assertEquals(java.util.Optional.of(true), s.closeDraft("i", d, java.time.Duration.ofDays(30), own -> "{\"expired\":true}"));
            assertEquals(InvestigationStore.DraftState.DISCARDED, s.draftState("i", d));
            assertTrue(s.draftExpired("i", d));
            assertEquals(0, s.openDraftCount());
        } finally {
            DraftLifecycle.clock = before;
        }
    }

    @Test
    void headersListWithoutTheGrowingFieldsAndAnUnknownDraftReadsAsOpenAndEmpty() throws Exception {
        InvestigationStore s = fresh();
        s.create("h", "{}");
        String d = DraftStore.newId();
        s.createDraft("h", d, "{\"draftId\":\"" + d + "\",\"actor\":\"a\",\"baseLogHash\":\"x\",\"rebases\":[1]}", "a", 9);
        var heads = s.draftHeaders("h");
        assertEquals(java.util.Set.of(d), heads.keySet());
        assertFalse(heads.get(d).containsKey("baseLogHash"));
        assertFalse(heads.get(d).containsKey("rebases"));
        assertEquals("a", heads.get(d).get("actor"));
        assertTrue(s.draftHeader("h", "draft-00000000-0000-0000-0000-000000000000").isEmpty());
        assertEquals(java.util.Optional.empty(), s.closeDraft("h", "draft-00000000-0000-0000-0000-000000000000", null, own -> "{}"));
        s.recoverDrafts("h");   // nothing to recover: a transaction leaves no partial promote
    }
}
