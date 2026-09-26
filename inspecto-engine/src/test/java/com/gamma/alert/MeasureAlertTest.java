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
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** BI-5 measure alerts: dataset+measure rules evaluated via the wired probe, cooldown, validation. */
class MeasureAlertTest {

    private static ConfigSource configs(PipelineConfig cfg) {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(cfg); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static StatusStore emptyStore() {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String batchId) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    private static AlertRule measureRule(String dataset, String measure, String comparator, double threshold) {
        return new AlertRule("low-revenue", null, comparator, threshold, null, "WARNING", null, dataset, measure);
    }

    @Test
    void measureRuleFiresThroughTheProbeAndCoolsDown(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        AlertService svc = new AlertService(
                List.of(measureRule("sales_ds", "sum(amount)", "lt", 1000)), configs(cfg), emptyStore());
        AtomicInteger probed = new AtomicInteger();
        svc.measureProbe((dataset, measure) -> {
            assertEquals("sales_ds", dataset);
            assertEquals("sum(amount)", measure);
            probed.incrementAndGet();
            return OptionalDouble.of(750.0);   // below the 1000 threshold → breach
        });

        List<Map<String, Object>> fired = svc.evaluateAll();
        assertEquals(1, fired.size());
        assertEquals("sales_ds", fired.get(0).get("pipeline"), "measure alerts scope to their dataset");
        assertEquals(750.0, (double) fired.get(0).get("value"), 1e-9);
        assertEquals("sum(amount)", fired.get(0).get("metric"), "labelled by its measure");

        // Still breached moments later — the cooldown suppresses a duplicate but the probe still runs.
        assertEquals(0, svc.evaluateAll().size(), "cooldown suppresses an immediate re-fire");
        assertEquals(2, probed.get());
    }

    @Test
    void aFiredMeasureRuleIsWordedWithTheResolvedDatasetNameAndKeepsTheId(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        AlertService svc = new AlertService(List.of(measureRule("sales_ds", "sum(amount)", "lt", 1000),
                measureRule2("orders_ds")), configs(cfg), emptyStore());
        svc.measureProbe((dataset, measure) -> OptionalDouble.of(750.0));
        svc.datasetLabel(id -> "sales_ds".equals(id) ? "Daily sales" : null);   // orders_ds has no label

        Map<String, Map<String, Object>> byRule = new java.util.HashMap<>();
        for (Map<String, Object> a : svc.evaluateAll()) byRule.put((String) a.get("rule"), a);
        Map<String, Object> sales = byRule.get("low-revenue");
        assertEquals("WARNING: Sum of amount on Daily sales is 750, below the threshold of 1,000 (over current data)",
                sales.get("message"));
        assertEquals("sales_ds", sales.get("pipeline"), "the scope field keeps the Dataset id");
        assertEquals("WARNING: Row count on orders_ds is 750, below the threshold of 1,000 (over current data)",
                byRule.get("few-orders").get("message"), "no label → the id");
    }

    private static AlertRule measureRule2(String dataset) {
        return new AlertRule("few-orders", null, "lt", 1000, null, "WARNING", null, dataset, "count");
    }

    @Test
    void unresolvableMeasureNeverFires(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        AlertService svc = new AlertService(
                List.of(measureRule("ghost_ds", "sum(amount)", "lt", 1000)), configs(cfg), emptyStore());

        assertEquals(0, svc.evaluateAll().size(), "no probe wired → measure rules are inert");
        svc.measureProbe((d, m) -> OptionalDouble.empty());
        assertEquals(0, svc.evaluateAll().size(), "probe cannot compute → degrade silently, never fire");
    }

    @Test
    void ruleValidationSeparatesTheTwoShapes() {
        // A measure rule must not carry ledger-metric fields, and vice versa.
        assertThrows(IllegalArgumentException.class, () ->
                new AlertRule("x", "error_rate", "lt", 1, null, "WARNING", null, "ds", "sum(a)"));
        assertThrows(IllegalArgumentException.class, () ->
                new AlertRule("x", null, "lt", 1, "1h", "WARNING", null, "ds", "sum(a)"),
                "a measure rule takes no window");
        assertThrows(IllegalArgumentException.class, () ->
                new AlertRule("x", null, "lt", 1, null, "WARNING", null, "ds", "median(a)"),
                "unknown aggregation");
        assertThrows(IllegalArgumentException.class, () ->
                new AlertRule("x", null, "lt", 1, null, "WARNING", null, null, "sum(a)"),
                "measure requires dataset");

        AlertRule ok = AlertRule.fromMap(Map.of("name", "ok", "dataset", "ds", "measure", "count",
                "comparator", "gte", "threshold", 5, "severity", "INFO"));
        assertTrue(ok.isMeasureRule());
        assertEquals("ds", ok.toMap().get("dataset"));
        assertNull(ok.toMap().get("window"));
    }
}
