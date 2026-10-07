package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.audit.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** BI-5 measure alerts: dataset+measure rules evaluated via the wired probe, cooldown, validation. */
class MeasureAlertTest {

    /** Subscribers this class adds to the event log — removed after each test so none leak into the fork. */
    private final java.util.List<java.util.function.Consumer<com.gamma.audit.Event>> subscribed = new java.util.ArrayList<>();

    private void subscribe(java.util.function.Consumer<com.gamma.audit.Event> s) {
        subscribed.add(s);
        EventLog.current().addSubscriber(s);
    }

    @org.junit.jupiter.api.AfterEach
    void unsubscribe() {
        subscribed.forEach(EventLog.current()::removeSubscriber);
        subscribed.clear();
    }

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

    // ── heal — ASSURE-PER-ENTITY-ALERTS-RESIDUALS-1 (2) ───────────────────────────────

    private static List<Event> cleared(List<Event> seen, String dataset) {
        return seen.stream().filter(e -> EventType.ALERT_CLEARED.equals(e.type())
                && dataset.equals(e.attributes().get("dataset"))).toList();
    }

    @Test
    void aScalarMeasureRuleHealsOnTheEdgeAndARelapseFiresAtOnce(@TempDir Path dir) throws Exception {
        List<Event> seen = new CopyOnWriteArrayList<>();
        subscribe(seen::add);
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        AlertService svc = new AlertService(
                List.of(measureRule("heal_ds", "sum(amount)", "lt", 1000)), configs(cfg), emptyStore());
        AtomicReference<OptionalDouble> value = new AtomicReference<>(OptionalDouble.of(750.0));
        svc.measureProbe((d, m) -> value.get());

        assertEquals(1, svc.evaluate(null, 0).size(), "breach");
        value.set(OptionalDouble.of(1500.0));                           // recovers, inside the cooldown
        assertTrue(svc.evaluate(null, 1000).isEmpty(), "an all-clear is not a fired Alert");
        List<Event> clears = cleared(seen, "heal_ds");
        assertEquals(1, clears.size(), "the recovery is announced even while the breach's cooldown runs");
        assertEquals(EventLevel.INFO, clears.get(0).level());
        assertEquals("low-revenue", clears.get(0).attributes().get("rule"));
        assertTrue(seen.stream().anyMatch(e -> EventType.SIGNAL.equals(e.type())
                && "alert-rule.cleared".equals(e.attributes().get(com.gamma.signal.Signal.ATTR_TYPE))
                && "alert:low-revenue|heal_ds".equals(e.correlationId())), "the correlated all-clear Signal");

        assertTrue(svc.evaluate(null, 2000).isEmpty());
        assertEquals(1, cleared(seen, "heal_ds").size(), "healthy again: no second all-clear (edge only)");

        value.set(OptionalDouble.of(10.0));                             // relapse 3s after the first fire
        assertEquals(1, svc.evaluate(null, 3000).size(),
                "the heal reset the cooldown — the relapse is not swallowed by a breach that is over");
    }

    @Test
    void aDeletedAndRecreatedRuleThatStillBreachesFiresAtOnceAndClearsOnlyWhatItRaised(@TempDir Path dir)
            throws Exception {
        List<Event> seen = new CopyOnWriteArrayList<>();
        subscribe(seen::add);
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        AlertRule rule = measureRule("recreate_ds", "sum(amount)", "lt", 1000);
        AlertService svc = new AlertService(List.of(rule), configs(cfg), emptyStore());
        AtomicReference<OptionalDouble> value = new AtomicReference<>(OptionalDouble.of(10.0));
        svc.measureProbe((d, m) -> value.get());

        assertEquals(1, svc.evaluate(null, 0).size());
        assertTrue(svc.remove("low-revenue"));
        svc.upsert(rule);                                               // re-created, still breaching
        assertEquals(1, svc.evaluate(null, 1000).size(),
                "the dead rule's cooldown does not hold the new rule's first Alert");
        value.set(OptionalDouble.of(5000.0));
        svc.evaluate(null, 2000);
        assertEquals(1, cleared(seen, "recreate_ds").size(), "one all-clear, for the Alert that was raised");
    }

    @Test
    void anUnknownValueNeitherFiresNorHealsAndANeverBreachedRuleIsNeverCleared(@TempDir Path dir) throws Exception {
        List<Event> seen = new CopyOnWriteArrayList<>();
        subscribe(seen::add);
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        AlertService svc = new AlertService(
                List.of(measureRule("unknown_ds", "sum(amount)", "lt", 1000)), configs(cfg), emptyStore());
        AtomicReference<OptionalDouble> value = new AtomicReference<>(OptionalDouble.of(5000.0));
        svc.measureProbe((d, m) -> value.get());

        svc.evaluate(null, 0);
        assertTrue(cleared(seen, "unknown_ds").isEmpty(), "never breached ⇒ nothing to clear");

        value.set(OptionalDouble.of(1.0));
        assertEquals(1, svc.evaluate(null, 1000).size());
        value.set(OptionalDouble.empty());                              // the Dataset cannot be read
        svc.evaluate(null, 2000);
        assertTrue(cleared(seen, "unknown_ds").isEmpty(), "unknown is not healed");
        value.set(OptionalDouble.of(5000.0));
        svc.evaluate(null, 3000);
        assertEquals(1, cleared(seen, "unknown_ds").size(), "readable and healthy ⇒ healed");
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

    // ── heal hysteresis — ALERT-HEAL-FLAP-1 ───────────────────────────────────────────────

    private static AlertRule dampedRule(Integer healAfterSweeps) {
        Map<String, Object> m = new java.util.HashMap<>(Map.of("name", "low-revenue", "dataset", "flap_ds",
                "measure", "sum(amount)", "comparator", "lt", "threshold", 1000, "severity", "WARNING"));
        if (healAfterSweeps != null) m.put("healAfterSweeps", healAfterSweeps);
        return AlertRule.fromMap(m);
    }

    @Test
    void aScalarRuleOscillatingAroundItsThresholdStaysOneOpenAlertUntilNHealthySweeps(@TempDir Path dir)
            throws Exception {
        List<Event> seen = new CopyOnWriteArrayList<>();
        subscribe(seen::add);
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        AlertService svc = new AlertService(List.of(dampedRule(3)), configs(cfg), emptyStore());
        AtomicReference<OptionalDouble> value = new AtomicReference<>(OptionalDouble.of(750.0));
        svc.measureProbe((d, m) -> value.get());

        assertEquals(1, svc.evaluate(null, 0).size(), "breach");
        int fired = 0;
        for (int sweep = 1; sweep <= 6; sweep++) {              // flap: healthy, breach, healthy, breach, ...
            value.set(OptionalDouble.of(sweep % 2 == 1 ? 1500.0 : 750.0));
            fired += svc.evaluate(null, sweep * 1000L).size();
        }
        assertEquals(0, fired, "a relapse inside the streak raises no fresh Alert");
        assertTrue(cleared(seen, "flap_ds").isEmpty(), "and no all-clear/Alert pair per sweep");

        value.set(OptionalDouble.of(1500.0));                   // three healthy sweeps in a row
        svc.evaluate(null, 7000);
        svc.evaluate(null, 8000);
        assertTrue(cleared(seen, "flap_ds").isEmpty(), "two of three: still open");
        svc.evaluate(null, 9000);
        assertEquals(1, cleared(seen, "flap_ds").size(), "the third consecutive healthy sweep clears, once");

        value.set(OptionalDouble.of(10.0));
        assertEquals(1, svc.evaluate(null, 10000).size(), "after the heal a relapse fires at once, as before");
    }

    @Test
    void theDefaultHealsOnTheFirstHealthySweepExactlyAsBefore(@TempDir Path dir) throws Exception {
        List<Event> seen = new CopyOnWriteArrayList<>();
        subscribe(seen::add);
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        AlertRule rule = dampedRule(null);
        assertEquals(1, rule.healAfterSweeps());
        assertFalse(rule.toMap().containsKey("healAfterSweeps"), "the default is not written");
        AlertService svc = new AlertService(List.of(rule), configs(cfg), emptyStore());
        AtomicReference<OptionalDouble> value = new AtomicReference<>(OptionalDouble.of(750.0));
        svc.measureProbe((d, m) -> value.get());

        assertEquals(1, svc.evaluate(null, 0).size());
        value.set(OptionalDouble.of(1500.0));
        svc.evaluate(null, 1000);
        assertEquals(1, cleared(seen, "flap_ds").size(), "cleared on the first healthy sweep");
        value.set(OptionalDouble.of(10.0));
        assertEquals(1, svc.evaluate(null, 2000).size(), "and the relapse fires at once");
    }

    @Test
    void healAfterSweepsFailsClosed() {
        for (Object bad : new Object[]{0, -1, 1.5, "x", 1001})
            assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(Map.of("name", "x", "dataset", "ds",
                    "measure", "count", "comparator", "gt", "threshold", 5, "severity", "INFO",
                    "healAfterSweeps", bad)), "healAfterSweeps " + bad);
        assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(Map.of("name", "x",
                "metric", "error_rate", "window", "1h", "comparator", "gt", "threshold", 5, "severity", "INFO",
                "healAfterSweeps", 3)), "a ledger rule has no Measure to heal");
        assertEquals(1000, AlertRule.fromMap(Map.of("name", "x", "dataset", "ds", "measure", "count",
                "comparator", "gt", "threshold", 5, "severity", "INFO", "healAfterSweeps", 1000)).healAfterSweeps());
    }
}
