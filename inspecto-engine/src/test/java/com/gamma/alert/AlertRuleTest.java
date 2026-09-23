package com.gamma.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule parsing/validation + window/comparator semantics (v4.1, B5). */
class AlertRuleTest {

    private static Map<String, Object> valid() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "high-error-rate");
        m.put("metric", "error_rate");
        m.put("comparator", "gt");
        m.put("threshold", 0.05);
        m.put("window", "1h");
        m.put("severity", "WARNING");
        m.put("onPipeline", "EVENTS");
        return m;
    }

    @Test
    void parsesAValidRule() {
        AlertRule r = AlertRule.fromMap(valid());
        assertEquals("high-error-rate", r.name());
        assertEquals("error_rate", r.metric());
        assertEquals(0.05, r.threshold());
        assertEquals(Duration.ofHours(1), r.windowDuration());
        assertFalse(r.batchWindow());
        assertEquals("EVENTS", r.onPipeline());
    }

    @Test
    void rejectsBadFields() {
        for (var bad : Map.of(
                "metric", "row_count",
                "comparator", "between",
                "severity", "PANIC",
                "window", "soon").entrySet()) {
            Map<String, Object> m = valid();
            m.put(bad.getKey(), bad.getValue());
            assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(m),
                    "should reject " + bad);
        }
        Map<String, Object> m = valid();
        m.put("threshold", -1);
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(m));
    }

    @Test
    void batchWindowAndComparators() {
        Map<String, Object> m = valid();
        m.put("window", "20b");
        m.remove("onPipeline");
        AlertRule r = AlertRule.fromMap(m);
        assertTrue(r.batchWindow());
        assertEquals(20, r.windowBatches());
        assertNull(r.onPipeline(), "absent onPipeline means every pipeline");
        assertTrue(r.breached(0.06));
        assertFalse(r.breached(0.05), "gt is strict");
    }

    // ── row-scoping 'when' (Rules triad condition-tree promotion, 2026-07-18) ──────

    private static Map<String, Object> conditionTree() {
        return Map.of("kind", "group", "op", "AND", "items", List.of(
                Map.of("kind", "condition", "field", "status", "operator", "=", "value", "FAILED")));
    }

    @Test
    void whenIsAcceptedOnALedgerMetricRule() {
        Map<String, Object> m = valid();
        m.put("when", conditionTree());
        AlertRule r = AlertRule.fromMap(m);
        assertEquals(conditionTree(), r.when());
    }

    @Test
    void whenIsRejectedOnAMeasureRule() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "low-revenue");
        m.put("dataset", "sales_ds");
        m.put("measure", "sum(amount)");
        m.put("comparator", "lt");
        m.put("threshold", 1000);
        m.put("severity", "WARNING");
        m.put("when", conditionTree());
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(m),
                "a measure alert has no ledger rows to scope");
    }

    /**
     * 🔴 A bare condition as the root used to match EVERY ledger row, and a non-map {@code when} (a string)
     * was silently dropped to "no filter" — either way the rule fired on rows it was meant to exclude.
     * Both are refused at parse time, which is what the save route (422) and the boot loader (warn + not
     * armed) both call. The twin: the same condition inside a group is accepted.
     */
    @Test
    void aWhenWhoseRootIsNotAGroupIsRefused() {
        Map<String, Object> leaf = Map.of("kind", "condition", "field", "status", "operator", "=", "value", "FAILED");
        for (Object bad : List.of(leaf, "status = FAILED", List.of(leaf))) {
            Map<String, Object> m = valid();
            m.put("when", bad);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(m),
                    String.valueOf(bad));
            assertTrue(e.getMessage().startsWith("alert.when"), e.getMessage());
        }
        Map<String, Object> ok = valid();
        ok.put("when", Map.of("kind", "group", "op", "AND", "items", List.of(leaf)));
        assertEquals(ok.get("when"), AlertRule.fromMap(ok).when());
    }

    // ── LA-23: an Alert Rule over an Investigation's Working Set ─────────────────────────────────────

    private static Map<String, Object> investigationRule() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "big-ring");
        m.put("investigation", "inv-42");
        m.put("measure", "count");
        m.put("comparator", "gte");
        m.put("threshold", 10);
        m.put("severity", "CRITICAL");
        return m;
    }

    @Test
    void anInvestigationRuleParsesDefaultsToTheEntitiesRelationAndRoundTrips() {
        AlertRule r = AlertRule.fromMap(investigationRule());
        assertTrue(r.isInvestigationRule());
        assertFalse(r.isMeasureRule(), "disjoint from the Dataset measure kind");
        assertFalse(r.isFreshnessRule());
        assertEquals("inv-42", r.investigation());
        assertEquals("entities", r.relation(), "relation defaults to the entities relation");
        assertEquals(r, AlertRule.fromMap(r.toMap()), "what the registry stores is what re-arms at boot");

        Map<String, Object> links = investigationRule();
        links.put("relation", "LINKS");
        links.put("measure", "sum(count)");
        assertEquals("links", AlertRule.fromMap(links).relation());
    }

    @Test
    void anInvestigationRuleRefusesEveryOtherKindsFieldsAndItsOwnBadValues() {
        for (var bad : Map.<String, Object>of(
                "dataset", "sales_ds",
                "metric", "error_rate",
                "window", "1h",
                "maximumAge", "6h",
                "relation", "edges",
                "measure", "median(hop)",
                "when", Map.of("kind", "group", "op", "AND", "items", List.of())).entrySet()) {
            Map<String, Object> m = investigationRule();
            m.put(bad.getKey(), bad.getValue());
            if (bad.getKey().equals("when"))   // an empty group normalises to absent — give it a condition
                m.put("when", Map.of("kind", "group", "op", "AND", "items", List.of(
                        Map.of("kind", "condition", "field", "status", "operator", "=", "value", "x"))));
            assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(m), "should reject " + bad);
        }
        Map<String, Object> orphan = valid();
        orphan.put("relation", "links");
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(orphan),
                "a relation without an investigation names nothing");
    }

    @Test
    void emptyWhenNormalizesToNull() {
        Map<String, Object> m = valid();
        m.put("when", Map.of());
        assertNull(AlertRule.fromMap(m).when(), "an empty tree carries no constraint — stored as absent");
    }

    @Test
    void loadsTheDiagnoseAndAlertDraftShape(@TempDir Path dir) throws Exception {
        // Exactly what the agent's draftToon emits: ConfigCodec.toToon of a root 'alert' block.
        Path f = dir.resolve("high_errors_alert.toon");
        Files.writeString(f, com.gamma.config.io.ConfigCodec.toToon(Map.of("alert", valid())));
        AlertRule r = AlertRule.load(f);
        assertEquals("high-error-rate", r.name());
        assertEquals("WARNING", r.severity());
    }
}
