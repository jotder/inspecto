package com.gamma.alert;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.signal.FileProblemSignal;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalType;
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
    /** The {@code rule} tag of a {@code collector.file.problem}-raised Alert (LA-DATA-TRUST-1 S6). */
    public static final String FILE_PROBLEM_RULE = "file_problem";
    /** The {@code rule} tag of a conservation-imbalance-raised Alert (T22, §11.4). */
    public static final String IMBALANCE_RULE = "conservation_imbalance";

    /** The {@code rule} tag of an Alert raised by a refused or failed Link Analysis index build (LA-DATA-TRUST-1 S4). */
    public static final String INDEX_BUILD_RULE = "la_index_build";

    private final AlertService alerts;

    public EventAlertBridge(AlertService alerts) {
        this.alerts = alerts;
    }

    /** The de-duplication key of a gap Alert; {@link AlertMigration} stamps the same key on an adopted legacy one. */
    static String gapKey(String expected) { return GAP_RULE + "|" + expected; }

    /** The de-duplication key of an index-build Alert: one per index-build Job. */
    static String indexBuildKey(String job) { return INDEX_BUILD_RULE + "|" + job; }

    /** The de-duplication key of an imbalance Alert. */
    static String imbalanceKey(String node) { return IMBALANCE_RULE + "|" + node; }

    /** {@code EventLog} subscriber entry point: raise the Alert if the event is one we manage. Never throws. */
    public void onEvent(Event e) {
        if (e == null) return;
        try {
            if (EventType.SEQUENCE_GAP.equals(e.type())) raiseGap(e);
            else if (EventType.SIGNAL.equals(e.type())) raiseFromSignal(e);
            else if (EventType.PIPELINE_CONSERVATION_IMBALANCE.equals(e.type())
                    || EventType.FLOW_CONSERVATION_IMBALANCE_LEGACY.equals(e.type())) raiseImbalance(e);   // vocab-allow: the Tier-2 legacy read-alias, matches events persisted under the pre-rename type
        } catch (RuntimeException ex) {
            log.warn("could not raise an Alert for {}: {}", e.type(), ex.getMessage());
        }
    }

    /**
     * {@code la.index.build.completed} (LA-DATA-TRUST-1 S4): a build that ended {@code REFUSED} or {@code FAILED} raises one
     * Alert per index-build Job, naming the Job, the mode and the stable refusal code (BUILD_IN_PROGRESS, OVER_BUDGET,
     * FULL_NOT_ALLOWED, BUSY, ...); a later build that leaves the index current ({@code BUILT} / {@code UP_TO_DATE}) resolves it.
     * ⚠ The Signal payload is aggregate-only (job, result, mode, code, counts), so the text can carry no entity key or value.
     * {@code RUNNING} / {@code DRY_RUN} neither raise nor resolve: the index state is not decided yet.
     */
    private void indexBuild(Event e) {
        Map<String, Object> p = e.payload();
        String job = String.valueOf(p.get("job"));
        if (p.get("job") == null || job.isBlank()) return;
        Object result = p.get("result");
        if ("BUILT".equals(result) || "UP_TO_DATE".equals(result)) {
            alerts.resolveFromEvent(job, indexBuildKey(job), "index-build:" + job);
            return;
        }
        if (!"REFUSED".equals(result) && !"FAILED".equals(result)) return;
        String mode = p.get("mode") == null ? null : String.valueOf(p.get("mode"));
        String code = p.get("code") == null ? null : String.valueOf(p.get("code"));
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("rule", INDEX_BUILD_RULE);
        attrs.put("job", job);
        attrs.put("result", String.valueOf(result));
        putIfPresent(attrs, "mode", mode);
        putIfPresent(attrs, "code", code);
        if (p.get("dataset") != null) attrs.put("dataset", String.valueOf(p.get("dataset")));
        stamp(attrs, e);
        String title = "Link Analysis index build " + String.valueOf(result).toLowerCase(java.util.Locale.ROOT) + ": " + job
                + (code == null ? "" : " (" + code + ")");
        String message = title + (mode == null ? "" : ", mode " + mode) + " - the index may be behind the data";
        alerts.raiseFromEvent(new Alert(INDEX_BUILD_RULE, "high", job, INDEX_BUILD_RULE, 1, "gt", 0, null,
                e.ts() > 0 ? e.ts() : System.currentTimeMillis(), message), title, job, indexBuildKey(job), attrs);
    }

    /**
     * LA-DATA-TRUST-1 S6: a {@code collector.file.problem} Signal becomes ONE Alert per (pipeline, File, day) - the
     * de-duplication key carries no row value and no entity key, and neither does the title. A quarantined or failed
     * File is {@code high}; a partial one (rows lost, File landed) is {@code medium}.
     */
    private void raiseFromSignal(Event e) {
        if (SignalType.LA_INDEX_BUILD_COMPLETED.equals(e.attributes().get(Signal.ATTR_TYPE))) {
            indexBuild(e);
            return;
        }
        Signal s = Signal.fromEvent(e);
        if (!FileProblemSignal.TYPE.equals(s.type()) || s.payload() == null) return;
        Map<String, Object> p = s.payload();
        String file = String.valueOf(p.get("file"));
        String day = String.valueOf(p.get("day"));
        String pipeline = String.valueOf(p.get("pipeline"));
        String kind = String.valueOf(p.get("kind"));
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("rule", FILE_PROBLEM_RULE);
        attrs.put("kind", kind);
        attrs.put("file", file);
        attrs.put("day", day);
        attrs.put("errorRows", String.valueOf(p.get("errorRows")));
        stamp(attrs, e);
        String what = switch (kind) {
            case FileProblemSignal.QUARANTINED -> "File quarantined";
            case FileProblemSignal.FAILED -> "File not loaded (consignment failed)";
            default -> "File loaded with rejected rows";
        };
        String title = what + ": " + file + " (day " + day + ") on " + pipeline;
        String severity = FileProblemSignal.PARTIAL.equals(kind) ? "medium" : "high";
        long at = e.ts() > 0 ? e.ts() : System.currentTimeMillis();
        Alert alert = new Alert(FILE_PROBLEM_RULE, severity, pipeline, FILE_PROBLEM_RULE, 1, "gt", 0, null, at, title);
        alerts.raiseFromEvent(alert, title, pipeline, FILE_PROBLEM_RULE + "|" + pipeline + "|" + file + "|" + day, attrs);
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
