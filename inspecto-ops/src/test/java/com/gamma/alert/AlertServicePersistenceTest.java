package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.objects.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.ObjectLink;
import com.gamma.etl.StatusStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase-2 promotion: a fired alert (the runtime half of {@code diagnose-and-alert}) is persisted as a
 * managed {@link ObjectType#ALERT} {@link OperationalObject}, linked to the firing event, deduplicated
 * while still active, and the events-only path (no object store) is unchanged.
 */
class AlertServicePersistenceTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static Map<String, String> row(String status, long in, long out, LocalDateTime end) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("total_input_rows", String.valueOf(in));
        m.put("total_output_rows", String.valueOf(out));
        m.put("rejected_count", "0");
        m.put("duration_ms", "100");
        m.put("end_time", TS.format(end));
        return m;
    }

    private static StatusStore store(List<Map<String, String>> batches) {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return batches; }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    private static ConfigSource configs(PipelineConfig cfg) {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(cfg); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static AlertRule errorRateRule() {
        return new AlertRule("high-error-rate", "error_rate", "gt", 0.05, "1h", "WARNING", null);
    }

    private static List<Map<String, String>> breachingLedger() {
        return List.of(row("SUCCESS", 1000, 900, LocalDateTime.now().minusMinutes(5)));   // 10% error
    }

    @Test
    void firedAlertBecomesManagedObject(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        AlertService svc = new AlertService(List.of(errorRateRule()), configs(cfg), store(breachingLedger()), objects.access());

        assertEquals(1, svc.evaluateAll().size(), "rule breaches and fires");
        List<OperationalObject> alerts = objects.query(ObjectQuery.builder().objectType(ObjectType.ALERT).build());
        assertEquals(1, alerts.size(), "the fired alert is persisted as an ALERT object");
        OperationalObject a = alerts.get(0);
        assertEquals("OPEN", a.status());
        assertEquals("WARNING", a.severity());
        assertEquals("MINI_ETL", a.correlationId());
        assertEquals("high-error-rate", a.attributes().get("rule"));
        assertEquals("error_rate", a.attributes().get("metric"));
        assertNotNull(a.attributes().get("causedByEvent"), "linked to the firing ALERT_FIRED event");
    }

    @Test
    void activeAlertNotDuplicated(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        new AlertService(List.of(errorRateRule()), configs(cfg), store(breachingLedger()), objects.access()).evaluateAll();
        // A fresh AlertService over the SAME object store fires again (its own cooldown is empty) — but
        // the still-OPEN object for this rule+pipeline suppresses the duplicate.
        new AlertService(List.of(errorRateRule()), configs(cfg), store(breachingLedger()), objects.access()).evaluateAll();
        assertEquals(1, objects.query(ObjectQuery.builder().objectType(ObjectType.ALERT).build()).size(),
                "an active alert object isn't duplicated");
    }

    @Test
    void noObjectStoreIsEventsOnly(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        // 3-arg ctor: no ObjectService — must fire exactly as before, with no persistence and no NPE.
        AlertService svc = new AlertService(List.of(errorRateRule()), configs(cfg), store(breachingLedger()));
        assertEquals(1, svc.evaluateAll().size());
        assertEquals(1, svc.recent(10).size());
    }

    /**
     * <b>Moved here from {@code AlertServiceTest} in EDG-01 cell 7</b> (2026-09-08). It asserts the real
     * object GRAPH — edge direction, both endpoint types, traversability from either end — which needs a
     * live engine. Core kept the two tests that only assert AlertService's own decision (ALERT always,
     * INCIDENT above a severity threshold) against a seam double; re-pointing this one at a fake would
     * have made it a test of the fake.
     */
    @Test
    void thePromotedIncidentIsLinkedEscalatedFromItsAlert(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        // ⚠ This class's own row() helper (4 args) — the test came from AlertServiceTest, whose helper
        // takes six. One FAILED batch is all the failed_batches>=1 rule needs.
        List<Map<String, String>> ledger = List.of(
                row("FAILED", 10, 0, LocalDateTime.now().minusMinutes(5)));
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        AlertRule critical = new AlertRule("r-crit", "failed_batches", "gte", 1, "1h", "critical", "MINI_ETL");
        new AlertService(List.of(critical), configs(cfg), store(ledger), objects.access()).evaluateAll();

        OperationalObject alert = objects.query(ObjectQuery.builder().objectType(ObjectType.ALERT).build()).get(0);
        OperationalObject incident =
                objects.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build()).get(0);

        // The correlation is a real edge in the object graph, not merely matching attributes: an operator
        // opening the Incident can pivot to the Alert that raised it (and back).
        List<ObjectLink> edges = objects.linksOf(incident.id());
        assertEquals(1, edges.size(), "exactly one correlation edge");
        ObjectLink edge = edges.get(0);
        assertEquals(incident.id(), edge.fromId());
        assertEquals(ObjectType.INCIDENT, edge.fromType());
        assertEquals(alert.id(), edge.toId(), "Incident ESCALATED_FROM the ALERT that raised it");
        assertEquals(ObjectType.ALERT, edge.toType());
        assertEquals("ESCALATED_FROM", edge.relationship());
        assertEquals(edges, objects.linksOf(alert.id()), "traversable from the Alert end too");
    }
}
