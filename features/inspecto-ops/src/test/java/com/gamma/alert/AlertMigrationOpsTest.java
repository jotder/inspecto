package com.gamma.alert;

import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one-shot ALERT adoption (MODULE-REORG-P7-INCIDENTS slice 2) against a REAL seeded {@link ObjectService}: the
 * still-active ALERT objects written by the pre-slice-2 {@code AlertService} carry the de-duplication state and move
 * into the Alert store under their own ids; nothing is deleted, resolved ALERTs are left alone (history, not
 * state), the Event bridge's gap / imbalance ALERTs are adopted like any other (they now live in the store), and a
 * second run adopts nothing.
 */
class AlertMigrationOpsTest {

    @Test
    void activeAlertObjectsMoveOnceAndTheObjectRowsStayUntouched() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        String open = objects.open(ObjectType.ALERT, "Failed batches on P", "msg", "warning", "P",
                Map.of("rule", "r-failed", "metric", "failed_batches")).id();
        String acked = objects.open(ObjectType.ALERT, "Another", "msg", "critical", "Q", Map.of("rule", "r-other")).id();
        objects.transition(acked, "ack", "dana");
        String resolved = objects.open(ObjectType.ALERT, "Old", "msg", "warning", "P", Map.of("rule", "r-old")).id();
        objects.transition(resolved, "resolve", "dana");
        objects.open(ObjectType.ALERT, "Missing file", "msg", "high", "P", Map.of("rule", "sequence_gap", "expected", "f1"));
        objects.open(ObjectType.ALERT, "Data loss at node n1", "msg", "high", "P",
                Map.of("rule", "conservation_imbalance", "node", "n1"));
        objects.open(ObjectType.INCIDENT, "An incident", "msg", "critical", "P", Map.of("rule", "r-failed"));

        AlertStore store = new InMemoryAlertStore();
        assertEquals(4, AlertMigration.adopt(objects.access(), store),
                "the open and the acknowledged ALERT and the bridge's gap and imbalance - not the resolved one, not the INCIDENT");

        assertEquals(open, store.activeIndex("P", "rule").get("r-failed"), "adopted under its own id");
        assertTrue(store.hasActive("P", "r-failed"), "so the breach is not announced again after the upgrade");
        AlertStore.Row ackedRow = store.active("Q").get(0);
        assertEquals(acked, ackedRow.id());
        assertEquals("ACKNOWLEDGED", ackedRow.state(), "state preserved");
        assertEquals("critical", ackedRow.severity());
        assertFalse(store.hasActive("P", "r-old"), "history is not state");
        assertTrue(store.hasActive("P", "sequence_gap"), "the bridge's gap ALERT moved too");
        assertEquals("sequence_gap|f1", store.activeIndex("P", "eventKey").keySet().stream().filter(k -> k.startsWith("sequence")).findFirst().orElse(null),
                "stamped with the de-duplication key EventAlertBridge uses");
        assertTrue(store.activeIndex("P", "eventKey").containsKey("conservation_imbalance|n1"));

        assertEquals(6, objects.query(ObjectQuery.builder().limit(ObjectQuery.MAX_LIMIT).build()).size(),
                "every object row is still there - nothing was deleted");
        assertEquals(5, objects.query(ObjectQuery.builder().objectType(ObjectType.ALERT).build()).size());

        assertEquals(0, AlertMigration.adopt(objects.access(), store), "idempotent: a non-empty store is left alone");
        assertEquals(4, store.size());
    }

    @Test
    void anEmptyObjectStoreAdoptsNothing() {
        AlertStore store = new InMemoryAlertStore();
        assertEquals(0, AlertMigration.adopt(new ObjectService(new InMemoryObjectStore()).access(), store));
        assertEquals(0, store.size());
        assertEquals(List.of(), store.allActive());
    }
}
