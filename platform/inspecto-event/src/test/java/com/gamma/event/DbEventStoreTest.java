package com.gamma.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbEventStore} — the shared event backend behind {@code -Devents.backend=db} (D6).
 *
 * <p>⚠ Driven over <b>DuckDB</b>, not Postgres, on purpose: this is the coverage that runs on every
 * developer machine and in CI. `PostgresStateStoreTest` adds the Postgres round-trip, and it
 * <b>skips entirely</b> without a server — so a class that only lived there would be a test nobody has
 * ever seen pass. Both engines go through the same `JdbcDrivers.connect`, and the DDL and SQL here are
 * deliberately dialect-neutral.
 */
class DbEventStoreTest {

    private static Event event(String type, String pipeline, long ts, EventLevel level, String message) {
        return new Event(null, ts, level, type, "com.gamma.Test", pipeline, "corr-" + ts, message,
                Map.of("k", "v"), Map.of());
    }

    private static String urlIn(Path dir) {
        return "jdbc:duckdb:" + dir.resolve("events.db").toString().replace('\\', '/');
    }

    @Test
    void appendsAndReadsBackEverythingTheEventCarries(@TempDir Path dir) throws Exception {
        try (DbEventStore store = DbEventStore.open(urlIn(dir), null, null)) {
            Event e = new Event("fixed-id", 1_700_000_000_000L, EventLevel.WARN, EventType.AUDIT,
                    "com.gamma.Src", "orders", "corr-1", "pipeline deleted",
                    Map.of("actor", "alice", "action", "pipeline.deleted"), Map.of());
            store.append(e);

            List<Event> back = store.recent(10);
            assertEquals(1, back.size());
            Event got = back.get(0);
            assertEquals("fixed-id", got.eventId());
            assertEquals(1_700_000_000_000L, got.ts());
            assertEquals(EventLevel.WARN, got.level());
            assertEquals(EventType.AUDIT, got.type());
            assertEquals("com.gamma.Src", got.source());
            assertEquals("orders", got.pipeline());
            assertEquals("corr-1", got.correlationId());
            assertEquals("pipeline deleted", got.message());
            assertEquals("alice", got.attributes().get("actor"),
                    "the attributes bag round-trips through JSON — an audit row without its actor answers nothing");
            assertEquals("pipeline.deleted", got.attributes().get("action"));
        }
    }

    /** Survives a restart — the property that separates this from the in-memory ring. */
    @Test
    void rowsSurviveAReopen(@TempDir Path dir) throws Exception {
        String url = urlIn(dir);
        try (DbEventStore store = DbEventStore.open(url, null, null)) {
            store.append(event(EventType.AUDIT, "orders", 1_000L, EventLevel.INFO, "one"));
        }
        try (DbEventStore reopened = DbEventStore.open(url, null, null)) {
            assertEquals(1, reopened.recent(10).size(), "an event must outlive the process that wrote it");
        }
    }

    /**
     * 🔴 The invariant that matters most, and the one a hand-written `WHERE` clause breaks silently: the
     * SQL filter must select exactly what {@link EventQuery#matches} selects, because that predicate is
     * what `InMemoryEventStore` applies. Two backends answering the same query differently is a defect an
     * operator would experience as "the events page shows different rows on different deployments".
     *
     * <p>Rather than assert the SQL, this runs every query through BOTH stores over identical data and
     * demands the same ids — so a future predicate added to one and not the other fails here.
     */
    @Test
    void theSqlFilterAgreesWithTheInMemoryPredicate(@TempDir Path dir) throws Exception {
        List<Event> corpus = List.of(
                event(EventType.AUDIT, "orders", 1_000L, EventLevel.INFO, "alpha happened"),
                event(EventType.AUDIT, "payments", 2_000L, EventLevel.ERROR, "beta failed"),
                event("BATCH_COMMITTED", "orders", 3_000L, EventLevel.WARN, "gamma warned"),
                event("SEQUENCE_GAP", "ORDERS", 4_000L, EventLevel.DEBUG, "delta gapped"),
                event("BATCH_FAILED", null, 5_000L, EventLevel.ERROR, "epsilon exploded"));

        InMemoryEventStore memory = new InMemoryEventStore();
        try (DbEventStore db = DbEventStore.open(urlIn(dir), null, null)) {
            for (Event e : corpus) {
                memory.append(e);
                db.append(e);
            }

            List<EventQuery> queries = List.of(
                    EventQuery.recent(100),
                    EventQuery.builder().type(EventType.AUDIT).build(),
                    EventQuery.builder().type("audit").build(),                 // case-insensitive, per matches()
                    EventQuery.builder().pipeline("orders").build(),
                    EventQuery.builder().pipeline("ORDERS").build(),            // case-insensitive
                    EventQuery.builder().correlationId("corr-2000").build(),    // EXACT, per matches()
                    EventQuery.builder().minLevel(EventLevel.WARN).build(),     // an enum ladder, not alphabetical
                    EventQuery.builder().minLevel(EventLevel.ERROR).build(),
                    EventQuery.builder().textContains("beta").build(),
                    EventQuery.builder().textContains("GAMMA").build(),         // case-insensitive
                    EventQuery.builder().textContains("gamma.Test").build(),    // matches on SOURCE too
                    EventQuery.builder().from(2_000L).build(),
                    EventQuery.builder().to(3_000L).build(),
                    EventQuery.builder().from(2_000L).to(4_000L).build());

            for (EventQuery q : queries) {
                assertEquals(ids(memory.query(q)), ids(db.query(q)),
                        "the DB filter and EventQuery.matches disagree for " + q);
            }
        }
    }

    /** `minLevel` is an ORDER over the enum; a string comparison would put ERROR below INFO. */
    @Test
    void minLevelSelectsBySeverityNotAlphabetically(@TempDir Path dir) throws Exception {
        try (DbEventStore db = DbEventStore.open(urlIn(dir), null, null)) {
            for (EventLevel l : EventLevel.values()) {
                db.append(event(EventType.LOG, "p", 1_000L + l.ordinal(), l, l.name()));
            }
            assertEquals(2, db.query(EventQuery.builder().minLevel(EventLevel.WARN).build()).size(),
                    "WARN and ERROR only");
            assertEquals(5, db.query(EventQuery.builder().minLevel(EventLevel.TRACE).build()).size(),
                    "TRACE admits everything");
        }
    }

    @Test
    void pagesByKeysetNewestFirstAndBreaksTiesOnId(@TempDir Path dir) throws Exception {
        try (DbEventStore db = DbEventStore.open(urlIn(dir), null, null)) {
            // Two events share a timestamp — the tie-break is what makes a cursor resume unambiguously.
            db.append(new Event("id-a", 5_000L, EventLevel.INFO, EventType.LOG, "s", "p", null, "a", Map.of(), Map.of()));
            db.append(new Event("id-b", 5_000L, EventLevel.INFO, EventType.LOG, "s", "p", null, "b", Map.of(), Map.of()));
            db.append(new Event("id-c", 9_000L, EventLevel.INFO, EventType.LOG, "s", "p", null, "c", Map.of(), Map.of()));

            List<Event> first = db.page(2, null, null);
            // ⚠ orderedIds, not ids: this assertion is about ORDER, and a sorting helper would pass
            // regardless of what the store returned.
            assertEquals(List.of("id-c", "id-b"), orderedIds(first), "newest first, then the higher id of the tie");

            Event last = first.get(first.size() - 1);
            assertEquals(List.of("id-a"), orderedIds(db.page(2, last.ts(), last.eventId())),
                    "the cursor must not re-serve id-b, which shares its timestamp");
            assertEquals(3L, db.count());
        }
    }

    /**
     * Retention drops whole UTC days and reports DAYS, matching {@link ParquetEventStore}, whose unit is a
     * deleted partition. ⛔ The boundary is exclusive — an event ON the cutoff day is retained.
     */
    @Test
    void prunesWholeDaysExclusiveOfTheCutoffAndCountsWithoutDeletingOnADryRun(@TempDir Path dir) throws Exception {
        try (DbEventStore db = DbEventStore.open(urlIn(dir), null, null)) {
            long day1 = day(LocalDate.of(2026, 1, 1));
            long day2 = day(LocalDate.of(2026, 1, 2));
            long day3 = day(LocalDate.of(2026, 1, 3));
            db.append(event(EventType.LOG, "p", day1, EventLevel.INFO, "old"));
            db.append(event(EventType.LOG, "p", day1 + 60_000L, EventLevel.INFO, "also day one"));
            db.append(event(EventType.LOG, "p", day2, EventLevel.INFO, "middle"));
            db.append(event(EventType.LOG, "p", day3, EventLevel.INFO, "keep"));

            assertEquals(2, db.prune(LocalDate.of(2026, 1, 3), true), "two DAYS before the cutoff, not three rows");
            assertEquals(4L, db.count(), "a dry run deletes nothing");

            assertEquals(2, db.prune(LocalDate.of(2026, 1, 3), false));
            assertEquals(1L, db.count(), "only the cutoff day survives — the boundary is exclusive");
            assertEquals("keep", db.recent(10).get(0).message());
        }
    }

    @Test
    void pruningNothingIsZeroNotAnError(@TempDir Path dir) throws Exception {
        try (DbEventStore db = DbEventStore.open(urlIn(dir), null, null)) {
            db.append(event(EventType.LOG, "p", day(LocalDate.of(2026, 5, 5)), EventLevel.INFO, "recent"));
            assertEquals(0, db.prune(LocalDate.of(2026, 1, 1), false));
            assertEquals(1L, db.count());
        }
    }

    /** ⚠ Observability must never break the path it observes: a failed append warns, it does not throw. */
    @Test
    void appendAfterCloseDoesNotThrow(@TempDir Path dir) throws Exception {
        DbEventStore db = DbEventStore.open(urlIn(dir), null, null);
        db.close();
        assertDoesNotThrow(() -> db.append(event(EventType.LOG, "p", 1L, EventLevel.INFO, "after close")));
    }

    @Test
    void aNullEventIsIgnoredRatherThanStored(@TempDir Path dir) throws Exception {
        try (DbEventStore db = DbEventStore.open(urlIn(dir), null, null)) {
            db.append(null);
            assertEquals(0L, db.count());
        }
    }

    private static long day(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** Ids as a SET (sorted) — for "which rows", never for "in what order". */
    private static List<String> ids(List<Event> events) {
        return events.stream().map(Event::eventId).sorted().toList();
    }

    /** Ids in the order the store returned them — for the keyset assertions. */
    private static List<String> orderedIds(List<Event> events) {
        return events.stream().map(Event::eventId).toList();
    }

    @Test
    void browseSurfaceNamesItsOwnTable(@TempDir Path dir) throws Exception {
        try (DbEventStore db = DbEventStore.open(urlIn(dir), null, null)) {
            assertEquals("events", db.browseId());
            assertEquals(List.of("inspecto_events"), db.browseTables());
            assertNotNull(Instant.now());
        }
    }
}
