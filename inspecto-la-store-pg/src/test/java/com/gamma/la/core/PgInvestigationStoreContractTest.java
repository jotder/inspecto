package com.gamma.la.core;

import com.gamma.la.store.pg.PgInvestigationStore;
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
 * mvn -o -B test -Pedition-enterprise -pl inspecto-la-store-pg -am \
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

    @Override
    InvestigationStore fresh() throws Exception {
        String url = url();
        assumeTrue(url != null, "no Postgres: set INSPECTO_TEST_PG_URL (jdbc:postgresql://host:5432/db?user=..&password=..) to run this");
        String schema = "la_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        schemas.add(schema);
        return new PgInvestigationStore(() -> DriverManager.getConnection(url), schema);
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
