package com.gamma.alert;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-P7 ALERT residue retirement: SEQUENCE_GAP and PIPELINE_CONSERVATION_IMBALANCE domain events are each
 * raised as a stored Alert (with de-duplication). Parity with the retired {@code EventObjectBridgeTest}, which
 * pinned the same outcomes as ALERT objects; here the Alert store is the substrate and there is no ops module.
 */
class EventAlertBridgeTest {

    private final AlertStore store = new InMemoryAlertStore();

    /** Personal-shaped: Alert records over the store, NO operational-object module. */
    private AlertService service(AlertStore s) {
        return new AlertService(List.of(), null, null, AlertRecords.of(s, Optional.empty()), AlertRecords.NO_INCIDENTS);
    }

    private static Event gap(String pipeline, String expected) {
        return Event.builder(EventType.SEQUENCE_GAP)
                .pipeline(pipeline)
                .message("Missing expected file in sequence: " + expected)
                .attr("expected", expected)
                .attr("sequence", "cdr_{yyyyMMddHH}.csv")
                .attr("unit", "HOURS")
                .build();
    }

    private static Event imbalance(String pipeline, String node, String kind, long in, long out) {
        return Event.builder(EventType.PIPELINE_CONSERVATION_IMBALANCE)
                .pipeline(pipeline)
                .message("pipeline '" + pipeline + "' node '" + node + "': " + in + " in, " + out + " out (" + kind + ")")
                .attr("node", node)
                .attr("kind", kind)
                .attr("recordsIn", in)
                .attr("recordsOut", out)
                .build();
    }

    @Test
    void raisesAGapAsAStoredAlertCarryingTheExpectedKey() {
        AlertService svc = service(store);
        new EventAlertBridge(svc).onEvent(gap("gap_src", "cdr_2026061402.csv"));

        List<AlertStore.Row> alerts = store.active("gap_src");
        assertEquals(1, alerts.size());
        AlertStore.Row a = alerts.get(0);
        assertEquals("OPEN", a.state());
        assertEquals("high", a.severity());
        assertEquals("cdr_2026061402.csv", a.attributes().get("expected"));
        assertEquals(EventAlertBridge.GAP_RULE, a.attributes().get("rule"));
        assertEquals("cdr_{yyyyMMddHH}.csv", a.attributes().get("sequence"));
        assertTrue(a.title().contains("cdr_2026061402.csv"));
        assertNotNull(a.attributes().get("occurredAt"), "the event's own time is kept (the MTTD numerator)");
        assertTrue(Long.parseLong(a.attributes().get("occurredAt")) <= a.openedAt(),
                "the condition occurred no later than the Alert was opened");
    }

    @Test
    void theGapIsVisibleThroughTheAlertListWithItsHandle() {
        AlertService svc = service(store);
        new EventAlertBridge(svc).onEvent(gap("gap_src", "cdr_2026061402.csv"));

        List<Map<String, Object>> listed = svc.recent(10);
        assertEquals(1, listed.size(), "GET /alerts lists the gap");
        assertEquals(EventAlertBridge.GAP_RULE, listed.get(0).get("rule"));
        assertEquals("OPEN", listed.get(0).get("state"));
        String id = String.valueOf(listed.get(0).get("id"));

        assertEquals("ACKNOWLEDGED", svc.acknowledge(id, "operator").get("state"), "POST /alerts/{id}/ack reaches it");
        assertEquals("RESOLVED", svc.resolve(id, "operator").get("state"));
    }

    @Test
    void anActiveGapForTheSameKeyIsNotDuplicated() {
        AlertService svc = service(store);
        EventAlertBridge bridge = new EventAlertBridge(svc);

        bridge.onEvent(gap("gap_src", "cdr_2026061402.csv"));
        bridge.onEvent(gap("gap_src", "cdr_2026061402.csv"));   // re-reported
        assertEquals(1, store.active("gap_src").size(), "same key => no clone");
        assertEquals(1, svc.recent(10).size());

        bridge.onEvent(gap("gap_src", "cdr_2026061405.csv"));   // a different hole => its own Alert
        assertEquals(2, store.active("gap_src").size());
    }

    @Test
    void aRestartedServiceOverTheSameStoreDoesNotCloneAnOpenGap() {
        new EventAlertBridge(service(store)).onEvent(gap("gap_src", "cdr_2026061402.csv"));

        AlertService afterRestart = service(store);   // a new instance: nothing in memory, the store remembers
        new EventAlertBridge(afterRestart).onEvent(gap("gap_src", "cdr_2026061402.csv"));
        assertEquals(1, store.active("gap_src").size(), "the cross-restart guard");
        assertEquals(1, afterRestart.recent(10).size(), "and the re-seeded history still lists it once");
    }

    /** The probe that would otherwise succeed: a RESOLVED Alert no longer suppresses, so the guard is on ACTIVE only. */
    @Test
    void aResolvedGapIsRaisedAgainWhenItRecurs() {
        AlertService svc = service(store);
        EventAlertBridge bridge = new EventAlertBridge(svc);
        bridge.onEvent(gap("gap_src", "cdr_2026061402.csv"));
        String id = store.active("gap_src").get(0).id();
        svc.resolve(id, "operator");
        assertTrue(store.active("gap_src").isEmpty());

        bridge.onEvent(gap("gap_src", "cdr_2026061402.csv"));
        assertEquals(1, store.active("gap_src").size(), "resolved => a recurrence is a new Alert");
    }

    @Test
    void raisesAConservationImbalanceAsAStoredAlert() {
        AlertService svc = service(store);
        new EventAlertBridge(svc).onEvent(imbalance("evt_rollup", "flt", "LOSS", 3, 2));

        List<AlertStore.Row> alerts = store.active("evt_rollup");
        assertEquals(1, alerts.size());
        AlertStore.Row a = alerts.get(0);
        assertEquals(EventAlertBridge.IMBALANCE_RULE, a.attributes().get("rule"));
        assertEquals("flt", a.attributes().get("node"));
        assertEquals("LOSS", a.attributes().get("kind"));
        assertEquals("3", a.attributes().get("recordsIn"));
        assertEquals("2", a.attributes().get("recordsOut"));
        assertTrue(a.title().startsWith("Data loss"), a.title());
        assertTrue(a.title().contains("flt"), a.title());
    }

    @Test
    void anImbalanceUnderTheLegacyPreRenameTypeStillRaises() {
        // FLOW_CONSERVATION_IMBALANCE is persisted in existing event-ledger rows (Tier 2 rename); the bridge must
        // keep resolving it on read even though nothing is emitted under it anymore.
        new EventAlertBridge(service(store)).onEvent(Event.builder(EventType.FLOW_CONSERVATION_IMBALANCE_LEGACY)
                .pipeline("evt_rollup")
                .message("pipeline 'evt_rollup' node 'flt': 3 in, 2 out (LOSS)")
                .attr("node", "flt")
                .attr("kind", "LOSS")
                .attr("recordsIn", 3L)
                .attr("recordsOut", 2L)
                .build());

        List<AlertStore.Row> alerts = store.active("evt_rollup");
        assertEquals(1, alerts.size());
        assertEquals(EventAlertBridge.IMBALANCE_RULE, alerts.get(0).attributes().get("rule"));
    }

    @Test
    void anActiveImbalanceForTheSameNodeIsNotDuplicated() {
        EventAlertBridge bridge = new EventAlertBridge(service(store));

        bridge.onEvent(imbalance("evt_rollup", "flt", "LOSS", 3, 2));
        bridge.onEvent(imbalance("evt_rollup", "flt", "LOSS", 5, 4));   // same node re-reported
        assertEquals(1, store.active("evt_rollup").size(), "same node => no clone");

        bridge.onEvent(imbalance("evt_rollup", "agg", "AMPLIFICATION", 2, 6));   // a different node => its own Alert
        assertEquals(2, store.active("evt_rollup").size());
    }

    @Test
    void anImbalanceWithoutANodeIsIgnored() {
        new EventAlertBridge(service(store)).onEvent(
                Event.builder(EventType.PIPELINE_CONSERVATION_IMBALANCE).pipeline("p").message("no node attr").build());
        assertTrue(store.active("p").isEmpty());
    }

    @Test
    void ignoresNonGapEventsAndEventsWithoutAnExpectedKey() {
        EventAlertBridge bridge = new EventAlertBridge(service(store));
        bridge.onEvent(Event.builder(EventType.FILE_RECEIVED).pipeline("p").message("x").build());
        bridge.onEvent(Event.builder(EventType.SEQUENCE_GAP).pipeline("p").message("no attrs").build());
        bridge.onEvent(null);
        assertTrue(store.active("p").isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    void wiresThroughEventLogAsASubscriber() {
        AlertService svc = service(store);
        Consumer<Event> sub = new EventAlertBridge(svc)::onEvent;
        EventLog.global().addSubscriber(sub);
        try {
            EventLog.global().emit(gap("bus_src", "cdr_2026061407.csv"));
            List<AlertStore.Row> alerts = store.active("bus_src");
            assertEquals(1, alerts.size(), "emitting a SEQUENCE_GAP raises the Alert via the subscriber");
            assertEquals("cdr_2026061407.csv", alerts.get(0).attributes().get("expected"));
        } finally {
            EventLog.global().removeSubscriber(sub);
        }
    }

    @Test
    void openAlertsListsOnlyTheUnacknowledged() {
        AlertService svc = service(store);
        EventAlertBridge bridge = new EventAlertBridge(svc);
        bridge.onEvent(gap("p", "a.csv"));
        bridge.onEvent(gap("p", "b.csv"));
        assertEquals(2, svc.openAlerts().size());

        String first = svc.openAlerts().get(0).id();
        svc.acknowledge(first, "agent:ops-monitor");
        assertEquals(1, svc.openAlerts().size(), "an acknowledged Alert is no longer OPEN");
        assertNotEquals(first, svc.openAlerts().get(0).id());
    }
}
