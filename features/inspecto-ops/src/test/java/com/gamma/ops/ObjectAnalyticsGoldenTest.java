package com.gamma.ops;

import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Golden pins for {@link ObjectService#analytics(ObjectType)} (ANALYTICS-CORE-1 S8): the rollup's exact output —
 * values, BigDecimal scales AND map key order (first-seen in the oldest-first walk) — captured from the JVM-side
 * implementation before its aggregation moved into the store's SQL, and run against every backend: the in-memory
 * store always, DuckDB always, PostgreSQL when {@code INSPECTO_TEST_PG_URL} / {@code -Dinspecto.test.pg.url} names
 * a server (skipped per test otherwise, with the reason).
 *
 * <p>The fixture covers: an empty corpus, a single object, createdAt ties (id breaks them), null / blank /
 * mixed-case / Unicode-folding statuses and priorities, the category L1 split and trim, cycle-time / MTTR / MTTD
 * inclusion edges (equal, reversed, absent, non-numeric, padded, negative, an ISO timestamp with a zone offset —
 * excluded, never re-zoned), impact in two currencies with an unreadable and an invalid entry, and an object of
 * another type that must not count.
 */
class ObjectAnalyticsGoldenTest {

    private static final String URL_PROPERTY = "inspecto.test.pg.url";
    private static String adminUrl;
    private static String schema;
    private static String pgUrl;

    @BeforeAll
    static void connect() throws Exception {
        adminUrl = System.getProperty(URL_PROPERTY);
        if (adminUrl == null || adminUrl.isBlank()) adminUrl = System.getenv("INSPECTO_TEST_PG_URL");
        if (adminUrl == null || adminUrl.isBlank()) return;
        schema = "inspecto_test_analytics_" + Long.toHexString(System.nanoTime());
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        pgUrl = adminUrl + (adminUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    @AfterAll
    static void drop() throws Exception {
        if (schema == null) return;
        try (Connection c = DriverManager.getConnection(adminUrl); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    // ── the goldens (captured from the pre-S8 JVM implementation, 2026-10-17) ──────────

    static final String EMPTY = "{type=INCIDENT, total=0, backlog=0, byStatus={}, byCategory={}, byPriority={}, "
            + "cycleTime={count=0, avgMs=0, definition=created \u2192 closed (the terminal state; for an Incident that is ARCHIVED, not RESOLVED)}, "
            + "mttr={count=0, avgMs=0, definition=created \u2192 most recent RESOLVED transition; a reopened object measures to the resolution that stuck. Objects with no recorded resolution are excluded from the mean}, "
            + "mttd={count=0, avgMs=0, definition=occurredAt (the triggering event's own time) \u2192 created (the object was opened). Objects with no recorded occurrence time are excluded from the mean}, "
            + "impact={byCurrency={}, recordsAffected=0}}";

    static final String SINGLE = "{type=INCIDENT, total=1, backlog=0, byStatus={ARCHIVED=1}, byCategory={Network=1}, byPriority={P1=1}, "
            + "cycleTime={count=1, avgMs=3000, definition=created \u2192 closed (the terminal state; for an Incident that is ARCHIVED, not RESOLVED)}, "
            + "mttr={count=1, avgMs=2000, definition=created \u2192 most recent RESOLVED transition; a reopened object measures to the resolution that stuck. Objects with no recorded resolution are excluded from the mean}, "
            + "mttd={count=1, avgMs=750, definition=occurredAt (the triggering event's own time) \u2192 created (the object was opened). Objects with no recorded occurrence time are excluded from the mean}, "
            + "impact={byCurrency={GBP={count=1, suspected=0, confirmed=10.5, recovered=0.5, prevented=0, outstanding=10.0}}, recordsAffected=12}}";

    static final String MIXED = "{type=INCIDENT, total=9, backlog=7, byStatus={RESOLVED=1, IDENTIFIED=3, ARCHIVED=2,  RESOLVED =1, DIAGNOSING=1, STRASSE=1}, "
            + "byCategory={UNCATEGORIZED=3, Network=2, Fraud=2, fraud=1, Billing=1}, byPriority={NONE=3, P1=3, P2=1, P3=1, \u212AEY=1}, "
            + "cycleTime={count=3, avgMs=3333, definition=created \u2192 closed (the terminal state; for an Incident that is ARCHIVED, not RESOLVED)}, "
            + "mttr={count=4, avgMs=150775, definition=created \u2192 most recent RESOLVED transition; a reopened object measures to the resolution that stuck. Objects with no recorded resolution are excluded from the mean}, "
            + "mttd={count=3, avgMs=200667, definition=occurredAt (the triggering event's own time) \u2192 created (the object was opened). Objects with no recorded occurrence time are excluded from the mean}, "
            + "impact={byCurrency={EUR={count=2, suspected=10, confirmed=0, recovered=0, prevented=0.0001, outstanding=0}, "
            + "USD={count=2, suspected=0, confirmed=101.5, recovered=20, prevented=0, outstanding=81.5}}, recordsAffected=9}}";

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static OperationalObject obj(String id, ObjectType type, String status, String priority,
                                         long createdAt, long closedAt, Map<String, String> attrs) {
        return new OperationalObject(id, type, "t " + id, "d", status, "HIGH", priority, null, null, null,
                attrs, createdAt, createdAt, closedAt, 0);
    }

    private static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static void seedSingle(ObjectStore s) {
        s.create(obj("s1", ObjectType.INCIDENT, "ARCHIVED", "P1", 1_000, 4_000,
                attrs("category", "Network / Core", "resolvedAt", "3000", "occurredAt", "250", "recordsAffected", "12",
                        Impact.ATTR, "{\"confirmed\":\"10.50\",\"recovered\":\"0.5\",\"currency\":\"GBP\"}")));
    }

    static void seedMixed(ObjectStore s) {
        long day = 1_760_000_000_000L;   // a real-sized epoch (2025-10-09), so a sum over the fixture is not toy-small
        // createdAt tie with i02: id order (i01 < i02) decides who is first-seen
        s.create(obj("i01", ObjectType.INCIDENT, "IDENTIFIED", "P1", day, 0,
                attrs("category", "Fraud / SIM box", "occurredAt", String.valueOf(day - 600_000), "recordsAffected", "5",
                        Impact.ATTR, "{\"confirmed\":\"100.50\",\"recovered\":\"20\",\"currency\":\"USD\"}")));
        s.create(obj("i02", ObjectType.INCIDENT, "identified", "p1", day, 0,
                attrs("category", "fraud/x", "resolvedAt", String.valueOf(day + 600_000), "recordsAffected", " 7 ")));
        s.create(obj("i03", ObjectType.INCIDENT, "ARCHIVED", null, day + 1_000, day + 5_000,
                attrs("category", " Billing ", "resolvedAt", String.valueOf(day + 4_000),
                        "occurredAt", String.valueOf(day + 2_500),   // after createdAt: excluded from MTTD
                        Impact.ATTR, "{\"suspected\":\"10\",\"currency\":\"EUR\"}")));
        s.create(obj("i04", ObjectType.INCIDENT, "Archived", " ", day + 2_000, day + 1_500,   // closed before created
                attrs("category", "", "resolvedAt", "abc", Impact.ATTR, "{oops", "recordsAffected", "x")));
        s.create(obj("i05", ObjectType.INCIDENT, " Resolved ", "P2", day + 3_000, day + 9_000,
                attrs("resolvedAt", "2026-01-01T00:00:00+02:00",   // an ISO time with a zone: not an epoch, excluded
                        "occurredAt", " " + (day + 1_000) + " ",
                        Impact.ATTR, "{\"confirmed\":\"1\",\"currency\":\"usd\"}", "recordsAffected", "-3")));
        s.create(obj("i06", ObjectType.INCIDENT, "RESOLVED", "", 0, 0,   // createdAt 0
                attrs("resolvedAt", "100", "occurredAt", "-5")));
        s.create(obj("i07", ObjectType.INCIDENT, "diagnosing", "P3", day + 4_000, 0,
                attrs("resolvedAt", String.valueOf(day + 4_000),     // equal: a zero-length resolution counts
                        Impact.ATTR, "{\"confirmed\":\"-1\",\"currency\":\"USD\"}",   // invalid: no impact
                        "category", "Fraud")));
        s.create(obj("i08", ObjectType.INCIDENT, "stra\u00dfe", "\u212Aey", day + 4_000, day + 4_000,   // folds to STRASSE / KEY
                attrs("category", "Network/Core/Edge", Impact.ATTR, "{\"prevented\":\"0.000100\",\"currency\":\"EUR\"}")));
        s.create(obj("i09", ObjectType.INCIDENT, "IDENTIFIED", "P1", day - 86_400_000L, 0,   // oldest: first-seen
                attrs("category", "Network / Access", "occurredAt", String.valueOf(day - 86_400_000L - 1))));
        s.create(obj("c01", ObjectType.CASE, "OPEN", "P1", day, 0,   // another type: never counted
                attrs("category", "Case cat", "recordsAffected", "1000",
                        Impact.ATTR, "{\"confirmed\":\"999\",\"currency\":\"USD\"}")));
    }

    // ── backends ─────────────────────────────────────────────────────────────

    private static ObjectStore duck() {
        try {
            return DbObjectStore.open("jdbc:duckdb:", null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectStore pg() {
        assumeTrue(pgUrl != null, "needs PostgreSQL: set INSPECTO_TEST_PG_URL or -D" + URL_PROPERTY);
        try {
            // a fresh table per test: drop what the previous test left in the throwaway schema
            try (Connection c = DriverManager.getConnection(pgUrl); Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS inspecto_ops_objects");
                s.execute("DROP TABLE IF EXISTS inspecto_ops_object_entity_ref");
            }
            return DbObjectStore.open(pgUrl, null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rollup(Supplier<ObjectStore> backend, java.util.function.Consumer<ObjectStore> seed) {
        try (ObjectStore store = backend.get()) {
            seed.accept(store);
            return String.valueOf(new ObjectService(store).analytics(ObjectType.INCIDENT));
        }
    }

    private static void pin(String golden, Supplier<ObjectStore> backend, java.util.function.Consumer<ObjectStore> seed) {
        assertEquals(golden, rollup(backend, seed));
    }

    @Test void emptyInMemory() { pin(EMPTY, InMemoryObjectStore::new, s -> { }); }
    @Test void emptyDuckDb() { pin(EMPTY, ObjectAnalyticsGoldenTest::duck, s -> { }); }
    @Test void emptyPostgres() { pin(EMPTY, ObjectAnalyticsGoldenTest::pg, s -> { }); }

    @Test void singleInMemory() { pin(SINGLE, InMemoryObjectStore::new, ObjectAnalyticsGoldenTest::seedSingle); }
    @Test void singleDuckDb() { pin(SINGLE, ObjectAnalyticsGoldenTest::duck, ObjectAnalyticsGoldenTest::seedSingle); }
    @Test void singlePostgres() { pin(SINGLE, ObjectAnalyticsGoldenTest::pg, ObjectAnalyticsGoldenTest::seedSingle); }

    @Test void mixedInMemory() { pin(MIXED, InMemoryObjectStore::new, ObjectAnalyticsGoldenTest::seedMixed); }
    @Test void mixedDuckDb() { pin(MIXED, ObjectAnalyticsGoldenTest::duck, ObjectAnalyticsGoldenTest::seedMixed); }
    @Test void mixedPostgres() { pin(MIXED, ObjectAnalyticsGoldenTest::pg, ObjectAnalyticsGoldenTest::seedMixed); }
}
