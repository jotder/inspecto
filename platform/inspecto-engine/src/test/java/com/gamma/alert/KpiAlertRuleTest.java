package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `BI-INSIGHTS-1` I-17 — an Alert Rule on a KPI ({@code kpi:}) or on its projection ({@code projection:}): the shapes it
 * accepts and refuses, and the sweep that fires, refreshes and heals it through the KPI probe.
 */
class KpiAlertRuleTest {

    private static AlertRule rule(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("name", "revenue-off-track", "kpi", "monthly_revenue",
                "severity", "CRITICAL"));
        m.putAll(extra);
        return AlertRule.fromMap(m);
    }

    @Test
    void theFourShapesParseAndRoundTrip() {
        AlertRule value = rule(Map.of("comparator", "lt", "threshold", 1000));
        assertTrue(value.isKpiRule());
        assertFalse(value.isMeasureRule());
        assertEquals("kpi:monthly_revenue", value.scalarScope());
        assertEquals("value to date of KPI monthly_revenue", value.kpiSignal());

        AlertRule status = rule(Map.of("rag", "Amber"));
        assertEquals("amber", status.rag());
        assertEquals("gte", status.comparator(), "a status rule's comparison is fixed");
        assertEquals(1.0, status.threshold());
        assertTrue(status.breached(2) && status.breached(1) && !status.breached(0), "amber or worse");

        AlertRule landing = rule(Map.of("projection", "value", "comparator", "lt", "threshold", 5000));
        assertEquals("projected landing of KPI monthly_revenue", landing.kpiSignal());

        AlertRule chance = rule(Map.of("projection", "probability", "comparator", "lt", "threshold", 0.5));
        assertEquals("P(target met) of KPI monthly_revenue", chance.kpiSignal());

        AlertRule projectedRed = rule(Map.of("projection", "value", "rag", "red"));
        assertEquals(2.0, projectedRed.threshold());

        for (AlertRule r : List.of(value, status, landing, chance, projectedRed))
            assertEquals(r, AlertRule.fromMap(r.toMap()), "the stored shape reads back the same rule");
    }

    @Test
    void refusesWhatAKpiRuleCannotMean() {
        assertThrows(IllegalArgumentException.class, () -> rule(Map.of("dataset", "sales", "threshold", 1)),
                "the KPI names its Dataset");
        assertThrows(IllegalArgumentException.class, () -> rule(Map.of("measure", "count", "threshold", 1)));
        assertThrows(IllegalArgumentException.class, () -> rule(Map.of("window", "1h", "threshold", 1)));
        assertThrows(IllegalArgumentException.class, () -> rule(Map.of("onPipeline", "EVENTS", "threshold", 1)),
                "onPipeline would be silently ignored on a KPI rule");
        assertThrows(IllegalArgumentException.class, () -> rule(Map.of("projection", "landing", "threshold", 1)));
        assertThrows(IllegalArgumentException.class, () -> rule(Map.of("rag", "green")), "green is never a breach");
        assertThrows(IllegalArgumentException.class, () -> rule(Map.of("projection", "probability", "rag", "red")));
        assertThrows(IllegalArgumentException.class, () -> rule(Map.of("projection", "probability", "threshold", 50)),
                "a probability is at most 1");
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(Map.of("name", "x", "dataset", "sales",
                "measure", "count", "threshold", 1, "projection", "value")), "projection belongs to a KPI rule");
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(Map.of("name", "x", "metric", "error_rate",
                "window", "1h", "threshold", 0.1, "rag", "red")), "rag belongs to a KPI rule");
    }

    @Test
    void theSweepFiresOnceRefreshesAndHealsThroughTheKpiProbe(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        InMemoryAlertStore store = new InMemoryAlertStore();
        AlertRule chance = rule(Map.of("projection", "probability", "comparator", "lt", "threshold", 0.5));
        AlertService svc = new AlertService(List.of(chance), configs(cfg), emptyStore(),
                AlertRecords.of(store, java.util.Optional.empty()), AlertRecords.incidentsOf(java.util.Optional.empty()));
        AtomicReference<OptionalDouble> reading = new AtomicReference<>(OptionalDouble.empty());
        svc.kpiProbe(r -> {
            assertSame(chance, r);
            return reading.get();
        });

        assertEquals(0, svc.evaluateAll().size(), "unknown (no projection) neither fires nor heals");

        reading.set(OptionalDouble.of(0.25));
        List<Map<String, Object>> fired = svc.evaluateAll();
        assertEquals(1, fired.size());
        assertEquals("kpi:monthly_revenue", fired.get(0).get("pipeline"));
        assertEquals("CRITICAL: the P(target met) of KPI monthly_revenue is 0.25, below the threshold of 0.5 "
                + "(over the period in progress, projected)", fired.get(0).get("message"));
        assertEquals(1, store.active("kpi:monthly_revenue").size());

        reading.set(OptionalDouble.of(0.3));
        assertEquals(0, svc.evaluateAll().size(), "an open breach is refreshed, not raised again");

        reading.set(OptionalDouble.of(0.9));
        assertEquals(0, svc.evaluateAll().size());
        assertEquals(0, store.active("kpi:monthly_revenue").size(), "a healthy sweep heals it");

        reading.set(OptionalDouble.of(0.1));
        assertEquals(1, svc.evaluateAll().size(), "a relapse after the heal fires at once");
    }

    @Test
    void aStatusRuleSaysTheStatusInWords(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        AlertService svc = new AlertService(List.of(rule(Map.of("rag", "amber"))), configs(cfg), emptyStore());
        svc.kpiProbe(r -> OptionalDouble.of(2));   // RED
        List<Map<String, Object>> fired = svc.evaluateAll();
        assertEquals(1, fired.size());
        assertEquals("CRITICAL: the RAG of the value to date of KPI monthly_revenue is RED, at or worse than AMBER "
                + "(over the period to date)", fired.get(0).get("message"));
    }

    @Test
    void withNoKpiProbeWiredAKpiRuleIsInertAndNeverReachesTheLedgerPass(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        AlertService svc = new AlertService(List.of(rule(Map.of("comparator", "lt", "threshold", 10))), configs(cfg),
                emptyStore());
        assertEquals(0, svc.evaluateAll().size(), "no window: the ledger pass must skip it, not throw");
    }

    private static ConfigSource configs(PipelineConfig cfg) {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(cfg); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    /** One committed batch, so the ledger pass has rows and would read a KPI rule's (absent) window if it reached it. */
    private static StatusStore emptyStore() {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) {
                return List.of(Map.of("status", "COMMITTED", "batch_id", "b1"));
            }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String batchId) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }
}
