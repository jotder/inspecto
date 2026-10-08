package com.gamma.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link AlertStore#purgeResolvedBefore} on the heap store and the durable DuckDB file (real file, reopened). */
class AlertPurgeStoreTest {

    private static final long CUT = 1_000_000L;
    private static final Instant CUTOFF = Instant.ofEpochMilli(CUT);

    private static AlertStore.Row row(String id, String state, long closedAt, String incident, Alert fired) {
        return new AlertStore.Row(id, "t", "m", "warning", "sc", Map.of("rule", id), state, 10L, closedAt,
                closedAt > 0 ? "x" : null, incident, fired);
    }

    private static Alert fired(String rule) {
        return new Alert(rule, "warning", "p", "failed_batches", 1, "gte", 1, "1h", 5L, "msg");
    }

    private static void seed(AlertStore s) {
        s.insert(row("OLD-RES", "RESOLVED", CUT - 1, "INC-1", fired("a")));
        s.insert(row("EDGE-RES", "RESOLVED", CUT, null, fired("b")));
        s.insert(row("NEW-RES", "RESOLVED", CUT + 1, null, fired("c")));
        s.insert(row("NOTIME-RES", "RESOLVED", 0, null, null));
        s.insert(row("OLD-OPEN", "OPEN", 0, null, fired("d")));
        s.insert(row("OLD-ACK", "ACKNOWLEDGED", 0, null, fired("e")));
    }

    private interface Body { void run(AlertStore s) throws Exception; }

    private static void onBothStores(Path dir, Body body) throws Exception {
        body.run(new InMemoryAlertStore());
        try (DbAlertStore db = new DbAlertStore("jdbc:duckdb:" + dir.resolve("purge.db"), null, null)) {
            body.run(db);
        }
    }

    @Test
    void onlyAResolvedRowClosedStrictlyBeforeTheCutoffIsDeleted(@TempDir Path dir) throws Exception {
        onBothStores(dir, s -> {
            seed(s);
            AlertStore.Purge p = s.purgeResolvedBefore(CUTOFF, false);
            assertEquals(1, p.purged());
            assertEquals(3, p.keptResolved(), "closed at the cutoff, after it, and with no close time are kept");
            assertEquals(2, p.keptActive());
            assertEquals(List.of(fired("a")), p.fired());
            assertTrue(s.get("OLD-RES").isEmpty());
            for (String kept : List.of("EDGE-RES", "NEW-RES", "NOTIME-RES", "OLD-OPEN", "OLD-ACK"))
                assertTrue(s.get(kept).isPresent(), kept + " must survive");
            assertEquals(5, s.size());
            assertEquals(2, s.allActive().size(), "the active alerts (and their de-duplication) are untouched");
            assertTrue(s.hasActive("sc", "OLD-OPEN"));
            assertEquals(0, s.purgeResolvedBefore(CUTOFF, false).purged(), "a second pass finds nothing");
        });
    }

    @Test
    void aDryRunCountsAndDeletesNothing(@TempDir Path dir) throws Exception {
        onBothStores(dir, s -> {
            seed(s);
            AlertStore.Purge p = s.purgeResolvedBefore(CUTOFF, true);
            assertEquals(1, p.purged());
            assertEquals(3, p.keptResolved());
            assertEquals(2, p.keptActive());
            assertEquals(6, s.size());
            assertTrue(s.get("OLD-RES").isPresent());
        });
    }

    @Test
    void aPurgedRowStaysGoneAfterTheDurableFileIsReopenedAndTheDanglingIncidentLinkIsHarmless(@TempDir Path dir)
            throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("reopen.db");
        try (DbAlertStore db = new DbAlertStore(url, null, null)) {
            seed(db);
            db.linkIncident("EDGE-RES", "INC-2");
            assertEquals(1, db.purgeResolvedBefore(CUTOFF, false).purged());
        }
        try (DbAlertStore db = new DbAlertStore(url, null, null)) {
            assertEquals(5, db.size());
            assertTrue(db.get("OLD-RES").isEmpty(), "no resurrected row after a restart");
            assertFalse(db.recentFired(10).contains(fired("a")), "history re-seeds without the purged Alert");
            assertEquals(4, db.recentFired(10).size());
            // the Incident link on a kept row is a reference into another store; nothing reads it back as a join
            assertEquals("INC-2", db.get("EDGE-RES").orElseThrow().incidentId());
            assertFalse(db.transition("OLD-RES", "ack", "u"), "acting on a purged id is the ordinary 'unknown alert'");
        }
    }
}
