package com.gamma.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-P7-INCIDENTS (retention of resolved Alert rows) - characterisation, written BEFORE the purge: on
 * both stores a resolved Alert is kept forever today, ack / resolve stamp {@code closedAt} / {@code closedBy} only on
 * the terminal move, and the active reads never see a resolved row.
 */
class AlertRetentionPinTest {

    private interface Body { void run(AlertStore s) throws Exception; }

    private static void onBothStores(Path dir, Body body) throws Exception {
        body.run(new InMemoryAlertStore());
        try (DbAlertStore db = new DbAlertStore("jdbc:duckdb:" + dir.resolve("pin.db"), null, null)) {
            body.run(db);
        }
    }

    @Test
    void aResolvedAlertIsKeptAndStampedAndNeverReadAsActive(@TempDir Path dir) throws Exception {
        onBothStores(dir, s -> {
            String id = s.open("t", "m", "warning", "sc", Map.of("rule", "r"), null);
            assertTrue(s.transition(id, "ack", "ann"));
            AlertStore.Row acked = s.get(id).orElseThrow();
            assertEquals(AlertLifecycle.ACKNOWLEDGED, acked.state());
            assertEquals(0L, acked.closedAt(), "only the terminal move stamps closedAt");
            assertNull(acked.closedBy());
            assertTrue(s.transition(id, "resolve", "bob"));
            AlertStore.Row done = s.get(id).orElseThrow();
            assertEquals(AlertLifecycle.RESOLVED, done.state());
            assertTrue(done.closedAt() > 0);
            assertEquals("bob", done.closedBy());
            assertTrue(s.active("sc").isEmpty());
            assertEquals(1, s.size(), "nothing ever deletes a resolved row today");
        });
    }

    @Test
    void anAncientResolvedRowIsStillThereAfterEverything(@TempDir Path dir) throws Exception {
        onBothStores(dir, s -> {
            s.insert(new AlertStore.Row("ALERT-OLD", "t", "m", "warning", "sc", Map.of(),
                    AlertLifecycle.RESOLVED, 1_000L, 2_000L, "x", null, null));
            s.open("t2", "m", "warning", "sc", Map.of(), null);
            assertEquals(2, s.size());
            assertTrue(s.get("ALERT-OLD").isPresent());
        });
    }
}
