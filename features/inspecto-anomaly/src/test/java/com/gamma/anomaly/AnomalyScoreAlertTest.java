package com.gamma.anomaly;

import com.gamma.alert.AlertRecords;
import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.alert.InMemoryAlertStore;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.control.PendingAlertRules;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.event.EventLog;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.query.DatasetRelation;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Design §9: Incidents through the EXISTING Alert Rule machinery — a per-entity rule over
 * {@code anomaly_scores_<id>_latest}, {@code by} model and entity key, {@code gte highThreshold}, raises one Incident
 * per high entity naming it and heals when a later run scores it normal. And the deferred template seed (D-AD7): a
 * pending rule with {@code afterScore: {kind: anomaly-model, model}} is created by that model's first run.
 * The production {@link DatasetMeasureProbe}, {@link AlertService} and {@link PendingAlertRules}.
 */
class AnomalyScoreAlertTest {

    @AfterEach
    void tearDown() { com.gamma.etl.EditionFeatures.overrideForTest(null); }

    private static ConfigSource noPipelines() {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static StatusStore emptyStore() {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    /** Score the corpus for the day before {@code asOf} exactly as the Job does, writing history + {@code _latest}. */
    private static AnomalyModel score(Path cfg, Path data, LocalDate asOf, String runId) throws Exception {
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        Map<String, Object> content = store.get(AnomalyModel.KIND, AnomalyCorpus.MODEL).orElseThrow().content();
        AnomalyModel model = AnomalyModel.fromMap(AnomalyCorpus.MODEL, content);
        AnomalyScoreEvaluator.Run run = AnomalyScoreEvaluator.evaluate(model, asOf, id -> DatasetRelation.relationSql(
                store.get("dataset", id).map(ComponentRegistry.Component::content).orElseThrow(), data, null));
        AnomalyScoreEvaluator.write(data, model, AnomalyScoreEvaluator.version(content), runId, Instant.now(), run);
        return model;
    }

    @Test
    void aHighScoreRaisesAPerEntityIncidentAndANormalRunHeals(@TempDir Path root) throws Exception {
        Path cfg = root.resolve("config"), data = root.resolve("data");
        AnomalyCorpus.plant(cfg, data);
        AnomalyModel model = score(cfg, data, AnomalyCorpus.AS_OF, "r1");
        new ComponentStore(cfg.resolve("registry")).write("dataset", "anomaly_scores_usage_latest",
                Map.of("physicalRef", "anomaly_scores_usage_latest"));
        AlertRule rule = AlertRule.fromMap(Map.of("name", "usage-anomaly", "dataset", "anomaly_scores_usage_latest",
                "measure", "max(score)", "by", List.of("model", "entity_key"), "comparator", "gte",
                "threshold", model.highThreshold(), "severity", "CRITICAL", "description", "Unusual usage"));

        FakeObjectAccess objects = new FakeObjectAccess();
        InMemoryAlertStore alerts = new InMemoryAlertStore();
        java.util.Optional<com.gamma.objects.ObjectAccess> ops = java.util.Optional.of(objects);
        AlertService svc = new AlertService(List.of(rule), noPipelines(), emptyStore(), AlertRecords.of(alerts, ops),
                AlertRecords.incidentsOf(ops));
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> cfg, () -> data);
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));

        assertEquals(2, svc.evaluateRules().size(), "the two planted anomalies, and no look-alike");
        List<FakeObjectAccess.Opened> incidents = objects.opened.stream()
                .filter(o -> o.kind() == ObjectType.INCIDENT).toList();
        assertEquals(List.of("masked", "spike"), incidents.stream()
                .map(o -> o.attributes().get("key.entity_key")).sorted().toList(), "one Incident per entity, naming it");
        for (FakeObjectAccess.Opened o : incidents) {
            assertEquals("usage", o.attributes().get("key.model"), "and the model the entity panel reads");
            assertEquals("usage-anomaly", o.attributes().get("rule"));
        }

        // The next run (as_of 2026-09-30) scores 2026-09-29, an ordinary day for both: _latest swaps and both heal.
        score(cfg, data, LocalDate.parse("2026-09-30"), "r2");
        assertEquals(0, svc.evaluateRules().size(), "no new Alert");
        assertEquals(2, objects.opened.stream().filter(o -> o.kind() == ObjectType.INCIDENT).count());
        assertTrue(alerts.allActive().isEmpty(), "each healed key resolves its Alert");
        assertEquals(2, alerts.size(), "the Alert records are kept, resolved");
    }

    @Test
    void aPendingTemplateRuleIsCreatedByTheModelsFirstRun(@TempDir Path root) throws Exception {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Path cfg = root.resolve("config"), data = root.resolve("data");
        AnomalyCorpus.plant(cfg, data);
        Path pending = Files.createDirectories(cfg.resolve(PendingAlertRules.DIR));
        Files.writeString(pending.resolve("usage_anomaly.toon"), """
                afterScore:
                  kind: anomaly-model
                  model: usage
                dataset: anomaly_scores_usage_latest
                measure: "max(score)"
                by[2]: model, entity_key
                comparator: gte
                threshold: 80
                severity: CRITICAL
                """);
        List<Event> audit = new ArrayList<>();
        EventLog events = EventLog.create();
        events.addSubscriber(e -> { if (EventType.AUDIT.equals(e.type())) audit.add(e); });

        assertEquals(List.of(), PendingAlertRules.onRiskScoreProduced(cfg, () -> data, "anomaly-model", "usage", null, events),
                "before the first run there is no output: it stays pending");
        assertEquals(1, PendingAlertRules.list(cfg).size());
        assertEquals(Map.of("kind", "anomaly-model", "model", "usage"), PendingAlertRules.list(cfg).get(0).get("afterScore"));
        assertEquals(List.of(), PendingAlertRules.onRiskScoreProduced(cfg, () -> data, "risk-score", "usage", null, events),
                "a Risk Score of the same id is another score kind");

        score(cfg, data, AnomalyCorpus.AS_OF, "r1");
        assertEquals(List.of("usage_anomaly"),
                PendingAlertRules.onRiskScoreProduced(cfg, () -> data, "anomaly-model", "usage", null, events));
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        Map<String, Object> created = store.get("alert-rule", "usage_anomaly").orElseThrow().content();
        assertEquals("anomaly_scores_usage_latest", created.get("dataset"));
        assertNull(created.get(PendingAlertRules.AFTER), "the trigger is not part of the rule");
        assertTrue(store.exists("dataset", "anomaly_scores_usage_latest"), "the Dataset over _latest is registered with it");
        assertEquals(List.of(), PendingAlertRules.list(cfg));
        assertEquals("anomaly-model", audit.get(audit.size() - 1).attributes().get("scoreKind"));
    }
}
