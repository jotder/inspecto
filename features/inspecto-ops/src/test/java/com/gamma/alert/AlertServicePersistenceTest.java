package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.workflow.ObjectType;
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

    /** Slice 2: Alerts in the Alert store, the Incident half over the real object engine. */
    private static AlertService over(InMemoryAlertStore alerts, ObjectService objects, AlertRule rule,
                                     ConfigSource configs, StatusStore status) {
        java.util.Optional<com.gamma.objects.ObjectAccess> ops = java.util.Optional.of(objects.access());
        return new AlertService(List.of(rule), configs, status, AlertRecords.of(alerts, ops), AlertRecords.incidentsOf(ops));
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
        InMemoryAlertStore alertStore = new InMemoryAlertStore();
        AlertService svc = over(alertStore, objects, errorRateRule(), configs(cfg), store(breachingLedger()));

        assertEquals(1, svc.evaluateAll().size(), "rule breaches and fires");
        assertTrue(objects.query(ObjectQuery.builder().objectType(ObjectType.ALERT).build()).isEmpty(),
                "an Alert is no longer an operational object");
        List<AlertStore.Row> alerts = alertStore.allActive();
        assertEquals(1, alerts.size(), "the fired alert is persisted in the Alert store");
        AlertStore.Row a = alerts.get(0);
        assertEquals("OPEN", a.state());
        assertEquals("WARNING", a.severity());
        assertEquals("MINI_ETL", a.scope());
        assertEquals("high-error-rate", a.attributes().get("rule"));
        assertEquals("error_rate", a.attributes().get("metric"));
        assertNotNull(a.attributes().get("causedByEvent"), "linked to the firing ALERT_FIRED event");
    }

    @Test
    void activeAlertNotDuplicated(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        InMemoryAlertStore alertStore = new InMemoryAlertStore();
        assertEquals(1, over(alertStore, objects, errorRateRule(), configs(cfg), store(breachingLedger())).evaluateAll().size());
        // Slice 2: a fresh AlertService over the SAME Alert store does not re-fire at all (its own cooldown is
        // empty, but the still-OPEN record for this rule+pipeline says the breach was already announced).
        assertEquals(0, over(alertStore, objects, errorRateRule(), configs(cfg), store(breachingLedger())).evaluateAll().size(),
                "an open Alert is not re-fired after a restart");
        assertEquals(1, alertStore.size(), "and is not duplicated");
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
        InMemoryAlertStore alertStore = new InMemoryAlertStore();
        over(alertStore, objects, critical, configs(cfg), store(ledger)).evaluateAll();

        AlertStore.Row alert = alertStore.allActive().get(0);
        OperationalObject incident =
                objects.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build()).get(0);

        // The correlation is a real edge in the object graph, not merely matching attributes: an operator
        // opening the Incident can pivot to the Alert that raised it (and back).
        List<ObjectLink> edges = objects.linksOf(incident.id());
        assertEquals(1, edges.size(), "exactly one correlation edge");
        ObjectLink edge = edges.get(0);
        assertEquals(incident.id(), edge.fromId());
        assertEquals("INCIDENT", edge.fromType());
        assertEquals(alert.id(), edge.toId(), "Incident ESCALATED_FROM the Alert that raised it (kind ALERT + id)");
        assertEquals("ALERT", edge.toType());
        assertTrue(objects.get(alert.id()).isEmpty(), "the Alert is not an object: the edge is a cross-store reference");
        assertEquals(incident.id(), alert.incidentId(), "and the Alert row names its Incident");
        assertEquals("ESCALATED_FROM", edge.relationship());
        assertEquals(edges, objects.linksOf(alert.id()), "traversable from the Alert end too");
        assertEquals(1, objects.graph(incident.id(), 1).get("edges") instanceof List<?> l ? l.size() : -1,
                "the incident's neighbourhood still lists the edge; the node for the Alert is simply absent");
    }

    /** R2-05: the Incident a measure breach raises, read by an operator — with and without a rule description. */
    private static OperationalObject incidentRaisedBy(AlertRule rule, double value, Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        AlertService svc = over(new InMemoryAlertStore(), objects, rule, configs(cfg), store(List.of()));
        svc.measureProbe((dataset, measure) -> java.util.OptionalDouble.of(value));
        assertEquals(1, svc.evaluateAll().size());
        List<OperationalObject> incidents =
                objects.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build());
        assertEquals(1, incidents.size());
        return incidents.get(0);
    }

    @Test
    void aMeasureIncidentWithoutADescriptionIsTitledByWhatItWatches(@TempDir Path dir) throws Exception {
        AlertRule rule = new AlertRule("fm_open_exposure", null, "gt", 298668, null, "CRITICAL", null,
                "fraud_cases_open", "sum(exposure_sar)");
        OperationalObject incident = incidentRaisedBy(rule, 373335.09, dir);

        assertEquals("Sum of exposure_sar on fraud_cases_open is above 298,668", incident.title());
        assertEquals("CRITICAL: Sum of exposure_sar on fraud_cases_open is 373,335.09, above the threshold of "
                + "298,668 (over current data)", incident.description());
        // The machine ids the old title carried stay where the API already exposes them.
        assertEquals("fm_open_exposure", incident.attributes().get("rule"));
        assertEquals("fraud_cases_open", incident.attributes().get("dataset"));
        assertEquals("sum(exposure_sar)", incident.attributes().get("measure"));
        assertEquals("gt", incident.attributes().get("comparator"));
        assertEquals("298668.0", incident.attributes().get("threshold"));
        assertEquals("373335.09", incident.attributes().get("value"));
    }

    @Test
    void aMeasureIncidentWithADescriptionIsTitledByIt(@TempDir Path dir) throws Exception {
        AlertRule rule = new AlertRule("ra_failed_controls_today", null, "gte", 1, null, "CRITICAL", null,
                "control_runs_today", "sum(failed)", null, null, null, null, "Revenue Assurance controls failing today");
        OperationalObject incident = incidentRaisedBy(rule, 1, dir);

        assertEquals("Revenue Assurance controls failing today — control_runs_today", incident.title());
        assertEquals("CRITICAL: Sum of failed on control_runs_today is 1, at or above the threshold of 1 "
                + "(over current data)", incident.description());
        assertEquals("ra_failed_controls_today", incident.attributes().get("rule"));
    }

    @Test
    void aMeasureIncidentNamesTheDatasetByItsResolvedLabelAndKeepsTheIdInItsAttributes(@TempDir Path dir)
            throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        InMemoryAlertStore alertStore = new InMemoryAlertStore();
        AlertService svc = over(alertStore, objects, new AlertRule("fm_open_exposure", null, "gt", 298668, null,
                "CRITICAL", null, "fraud_cases_open", "sum(exposure_sar)"), configs(cfg), store(List.of()));
        svc.measureProbe((dataset, measure) -> java.util.OptionalDouble.of(373335.09));
        svc.datasetLabel(id -> "fraud_cases_open".equals(id) ? "Open fraud cases" : null);
        assertEquals(1, svc.evaluateAll().size());

        String title = "Sum of exposure_sar on Open fraud cases is above 298,668";
        String message = "CRITICAL: Sum of exposure_sar on Open fraud cases is 373,335.09, above the threshold of "
                + "298,668 (over current data)";
        List<OperationalObject> incidents = objects.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build());
        assertEquals(1, incidents.size(), "INCIDENT");
        assertEquals(title, incidents.get(0).title(), "INCIDENT");
        assertEquals(message, incidents.get(0).description(), "INCIDENT");
        assertEquals("fraud_cases_open", incidents.get(0).attributes().get("dataset"), "the machine id stays in the attributes");
        assertEquals(1, alertStore.size(), "ALERT");
        AlertStore.Row alert = alertStore.allActive().get(0);
        assertEquals(title, alert.title(), "ALERT");
        assertEquals(message, alert.message(), "ALERT");
        assertEquals("fraud_cases_open", alert.attributes().get("dataset"), "the machine id stays in the attributes");
    }
}
