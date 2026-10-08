package com.gamma.alert;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns two append-only domain {@link Event}s into stored {@link Alert}s: {@link EventType#SEQUENCE_GAP} (a missing
 * file in a configured sequence) and {@link EventType#PIPELINE_CONSERVATION_IMBALANCE} (data lost or amplified at a
 * non-amplifying node, §11.4). Registered as an {@code EventLog} subscriber by the host.
 *
 * <p>MODULE-REORG-P7 ALERT residue retirement (2026-10-08): this was {@code com.gamma.ops.EventObjectBridge}, which
 * wrote ALERT <em>objects</em> and so existed only where the optional operational-object module did. It now writes
 * the Alert-owned store through {@link AlertService#raiseFromEvent}, so a gap or imbalance is an Alert on EVERY
 * edition (Personal included) and is read from {@code GET /alerts}.
 *
 * <p>De-duplication: a still-active Alert for the same {@code (pipeline, expected key)} / {@code (pipeline, node)}
 * suppresses a duplicate, so a re-report after a restart does not hand an operator a clone. Never throws.
 */
public final class EventAlertBridge {

    private static final Logger log = LoggerFactory.getLogger(EventAlertBridge.class);

    /** The {@code rule} tag of a gap-raised Alert. */
    public static final String GAP_RULE = "sequence_gap";
    /** The {@code rule} tag of a conservation-imbalance-raised Alert (T22, §11.4). */
    public static final String IMBALANCE_RULE = "conservation_imbalance";

    private final AlertService alerts;

    public EventAlertBridge(AlertService alerts) {
        this.alerts = alerts;
    }

    /** The de-duplication key of a gap Alert; {@link AlertMigration} stamps the same key on an adopted legacy one. */
    static String gapKey(String expected) { return GAP_RULE + "|" + expected; }

    /** The de-duplication key of an imbalance Alert. */
    static String imbalanceKey(String node) { return IMBALANCE_RULE + "|" + node; }

    /** {@code EventLog} subscriber entry point: raise the Alert if the event is one we manage. Never throws. */
    public void onEvent(Event e) {
        if (e == null) return;
        try {
            if (EventType.SEQUENCE_GAP.equals(e.type())) raiseGap(e);
            else if (EventType.PIPELINE_CONSERVATION_IMBALANCE.equals(e.type())
                    || EventType.FLOW_CONSERVATION_IMBALANCE_LEGACY.equals(e.type())) raiseImbalance(e);   // vocab-allow: the Tier-2 legacy read-alias, matches events persisted under the pre-rename type
        } catch (RuntimeException ex) {
            log.warn("could not raise an Alert for {}: {}", e.type(), ex.getMessage());
        }
    }

    private void raiseImbalance(Event e) {
        String node = e.attributes().get("node");
        if (node == null || node.isBlank()) return;
        String pipeline = e.pipeline();
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("rule", IMBALANCE_RULE);
        attrs.put("node", node);
        putIfPresent(attrs, "kind", e.attributes().get("kind"));
        putIfPresent(attrs, "recordsIn", e.attributes().get("recordsIn"));
        putIfPresent(attrs, "recordsOut", e.attributes().get("recordsOut"));
        stamp(attrs, e);
        String kind = e.attributes().getOrDefault("kind", "imbalance");
        String title = ("LOSS".equals(kind) ? "Data loss" : "Record amplification")
                + " at node " + node + (pipeline != null ? " in pipeline " + pipeline : "");
        alerts.raiseFromEvent(alert(IMBALANCE_RULE, pipeline, e), title, pipeline, imbalanceKey(node), attrs);
    }

    private void raiseGap(Event e) {
        String expected = e.attributes().get("expected");
        if (expected == null || expected.isBlank()) return;
        String pipeline = e.pipeline();
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("rule", GAP_RULE);
        attrs.put("expected", expected);
        putIfPresent(attrs, "sequence", e.attributes().get("sequence"));
        putIfPresent(attrs, "unit", e.attributes().get("unit"));
        stamp(attrs, e);
        String title = "Missing file in sequence: " + expected + (pipeline != null ? " on " + pipeline : "");
        alerts.raiseFromEvent(alert(GAP_RULE, pipeline, e), title, pipeline, gapKey(expected), attrs);
    }

    /** The fired {@link Alert} behind the record (so it shows in {@code GET /alerts}); severity {@code high} as before. */
    private static Alert alert(String rule, String pipeline, Event e) {
        long at = e.ts() > 0 ? e.ts() : System.currentTimeMillis();
        return new Alert(rule, "high", pipeline == null ? "" : pipeline, rule, 1, "gt", 0, null, at, e.message());
    }

    private static void stamp(Map<String, String> attrs, Event e) {
        if (e.eventId() != null) attrs.put("causedByEvent", e.eventId());
        if (e.ts() > 0) attrs.put("occurredAt", Long.toString(e.ts()));
    }

    private static void putIfPresent(Map<String, String> m, String key, String value) {
        if (value != null && !value.isBlank()) m.put(key, value);
    }
}
