package com.gamma.alert;

import com.gamma.ops.DbObjectStore;
import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.DbLinkStore;
import com.gamma.ops.link.ObjectLink;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one-shot ALERT adoption (MODULE-REORG-P7-INCIDENTS slice 2) against a REAL seeded {@link ObjectService}, and the
 * persisted-data guarantee of the ALERT enum retirement: the still-active ALERT objects written by the pre-slice-2
 * {@code AlertService} carry the de-duplication state and move into the Alert store under their own ids; every legacy
 * row (active, acknowledged, resolved) survives, is listed INERT - no {@link ObjectType} names {@code ALERT} any more -
 * and none is rewritten or dropped; a second run adopts nothing; an {@code ESCALATED_FROM} edge whose far end is kind
 * {@code ALERT} still loads.
 */
class AlertMigrationOpsTest {

    private static OperationalObject legacy(String id, String title, String status, String severity, String scope,
                                            long created, long closedAt, Map<String, String> attrs) {
        return OperationalObject.inert(LegacyAlertObjects.TYPE, id, title, "msg", status, severity, null, null, null,
                scope, attrs, created, created, closedAt, 4);
    }

    @Test
    void activeAlertObjectsMoveOnceAndEveryLegacyRowStaysInertAndUntouched() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        ObjectService objects = new ObjectService(store);
        store.create(legacy("A-open", "Failed batches on P", "OPEN", "warning", "P", 10, 0,
                Map.of("rule", "r-failed", "metric", "failed_batches")));
        store.create(legacy("A-acked", "Another", "ACKNOWLEDGED", "critical", "Q", 20, 0, Map.of("rule", "r-other")));
        store.create(legacy("A-done", "Old", "RESOLVED", "warning", "P", 30, 31, Map.of("rule", "r-old")));
        store.create(legacy("A-gap", "Missing file", "OPEN", "high", "P", 40, 0, Map.of("rule", "sequence_gap", "expected", "f1")));
        store.create(legacy("A-imb", "Data loss at node n1", "OPEN", "high", "P", 50, 0,
                Map.of("rule", "conservation_imbalance", "node", "n1")));
        objects.open(ObjectType.INCIDENT, "An incident", "msg", "critical", "P", Map.of("rule", "r-failed"));
        List<OperationalObject> before = objects.query(ObjectQuery.builder().limit(ObjectQuery.MAX_LIMIT).oldestFirst(true).build());

        AlertStore alerts = new InMemoryAlertStore();
        assertEquals(4, AlertMigration.adopt(objects.access(), alerts),
                "the open and the acknowledged ALERT and the bridge's gap and imbalance - not the resolved one, not the INCIDENT");

        assertEquals("A-open", alerts.activeIndex("P", "rule").get("r-failed"), "adopted under its own id");
        assertTrue(alerts.hasActive("P", "r-failed"), "so the breach is not announced again after the upgrade");
        AlertStore.Row ackedRow = alerts.active("Q").get(0);
        assertEquals("A-acked", ackedRow.id());
        assertEquals("ACKNOWLEDGED", ackedRow.state(), "state preserved");
        assertEquals("critical", ackedRow.severity());
        assertFalse(alerts.hasActive("P", "r-old"), "history is not state");
        assertTrue(alerts.hasActive("P", "sequence_gap"), "the bridge's gap ALERT moved too");
        assertTrue(alerts.activeIndex("P", "eventKey").containsKey("sequence_gap|f1"), "stamped with EventAlertBridge's key");
        assertTrue(alerts.activeIndex("P", "eventKey").containsKey("conservation_imbalance|n1"));

        List<OperationalObject> after = objects.query(ObjectQuery.builder().limit(ObjectQuery.MAX_LIMIT).oldestFirst(true).build());
        assertEquals(6, after.size(), "every object row is still there - nothing was deleted");
        assertEquals(before, after, "and not one field of any row moved (the legacy rows were not rewritten)");
        assertEquals(5, after.stream().filter(o -> o.isInert() && LegacyAlertObjects.TYPE.equals(o.typeName())).count(),
                "all five legacy rows load inert under the type text ALERT");

        assertEquals(0, AlertMigration.adopt(objects.access(), alerts), "idempotent: a non-empty store is left alone");
        assertEquals(4, alerts.size());
    }

    @Test
    void aLegacyAlertRowInADurableStoreSurvivesBootIsListedInertAdoptedAndItsEscalationEdgeStillLoads() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            DbObjectStore objectStore = new DbObjectStore(c);
            DbLinkStore linkStore = new DbLinkStore(c);
            try (Statement st = c.createStatement()) {
                for (String[] r : new String[][]{{"A-1", "OPEN", "0"}, {"A-2", "RESOLVED", "99"}}) {
                    st.execute("INSERT INTO inspecto_ops_objects (id, object_type, title, description, status, severity, priority, "
                            + "\"owner\", assignee, correlation_id, attributes, created_at, updated_at, closed_at, version) VALUES ('"
                            + r[0] + "','ALERT','legacy','d','" + r[1] + "','high',null,null,null,'P','{\"rule\":\"r-" + r[0] + "\"}',7,7," + r[2] + ",2)");
                }
            }
            linkStore.add(new ObjectLink("INC-1", "INCIDENT", "A-1", "ALERT", "ESCALATED_FROM", 8));

            // "boot": a service over the same durable stores reads everything without throwing
            ObjectService objects = new ObjectService(objectStore, Map.of(), linkStore);
            List<OperationalObject> all = objects.query(ObjectQuery.recent(10));
            assertEquals(2, all.size(), "neither legacy row was dropped");
            assertTrue(all.stream().allMatch(o -> o.isInert() && "ALERT".equals(o.typeName())));
            assertEquals("ALERT", objects.linksOf("A-1").get(0).toType(), "the ESCALATED_FROM edge naming kind ALERT still loads");

            AlertStore alerts = new InMemoryAlertStore();
            assertEquals(1, AlertMigration.adopt(objects.access(), alerts), "only the active one is adopted");
            assertEquals("A-1", alerts.activeIndex("P", "rule").get("r-A-1"));

            assertEquals("OPEN", objectStore.get("A-1").orElseThrow().status(), "adoption left the object row untouched");
            assertEquals(2L, objectStore.get("A-1").orElseThrow().version(), "not even its version moved");
            assertEquals(99L, objectStore.get("A-2").orElseThrow().closedAt());
        }
    }

    @Test
    void anEmptyObjectStoreAdoptsNothing() {
        AlertStore store = new InMemoryAlertStore();
        assertEquals(0, AlertMigration.adopt(new ObjectService(new InMemoryObjectStore()).access(), store));
        assertEquals(0, store.size());
        assertEquals(List.of(), store.allActive());
    }
}
