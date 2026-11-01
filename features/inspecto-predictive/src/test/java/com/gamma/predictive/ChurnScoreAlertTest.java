package com.gamma.predictive;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Churn Scores through the EXISTING Alert Rule machinery: a per-entity rule over {@code churn_scores_<id>_latest},
 * {@code by} model and entity key, {@code gte highThreshold}, at CRITICAL raises one Incident per high entity and none for the
 * elevated, normal or insufficient ones (the NULL insufficient score itself is pinned by {@code ChurnJobTest}). And the deferred template seed: a pending rule with
 * {@code afterScore: {kind: churn-model, model}} is created by that model's first run. The production
 * {@link DatasetMeasureProbe}, {@link AlertService} and {@link PendingAlertRules}.
 */
class ChurnScoreAlertTest {

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

    /** Score the corpus exactly as the Job does, writing history + {@code _latest}. */
    private static ChurnModel score(Path cfg, Path data, String runId) throws Exception {
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        Map<String, Object> content = store.get(ChurnModel.KIND, ChurnCorpus.MODEL).orElseThrow().content();
        ChurnModel model = ChurnModel.fromMap(ChurnCorpus.MODEL, content);
        String relation = DatasetRelation.relationSql(
                store.get("dataset", "orders").map(ComponentRegistry.Component::content).orElseThrow(), data, null);
        ChurnEvaluator.write(data, model, ChurnEvaluator.version(content), runId, Instant.now(), ChurnCorpus.AS_OF,
                ChurnEvaluator.evaluate(model, ChurnCorpus.AS_OF, relation));
        return model;
    }

    @Test
    void aHighChurnScoreRaisesAPerEntityIncidentAndAnInsufficientOneNever(@TempDir Path root) throws Exception {
        Path cfg = root.resolve("config"), data = root.resolve("data");
        ChurnCorpus.plant(cfg, data);
        ChurnModel model = score(cfg, data, "r1");
        new ComponentStore(cfg.resolve("registry")).write("dataset", "churn_scores_churn_latest",
                Map.of("physicalRef", "churn_scores_churn_latest"));
        AlertRule rule = AlertRule.fromMap(Map.of("name", "likely-churn", "dataset", "churn_scores_churn_latest",
                "measure", "max(score)", "by", List.of("model", "entity_key"), "comparator", "gte",
                "threshold", model.highThreshold(), "severity", "CRITICAL", "description", "Likely to churn"));

        FakeObjectAccess objects = new FakeObjectAccess();
        InMemoryAlertStore alerts = new InMemoryAlertStore();
        java.util.Optional<com.gamma.objects.ObjectAccess> ops = java.util.Optional.of(objects);
        AlertService svc = new AlertService(List.of(rule), noPipelines(), emptyStore(), AlertRecords.of(alerts, ops),
                AlertRecords.incidentsOf(ops));
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> cfg, () -> data);
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));

        List<com.gamma.alert.Alert> raised = svc.evaluateRules();
        assertEquals(1, raised.size(), "ONE Alert for the rule (ALERT-PER-RULE-1)");
        assertEquals(2.0, raised.get(0).value(), 1e-9, "the two high entities, not the elevated or insufficient ones");
        List<FakeObjectAccess.Opened> incidents = objects.opened.stream()
                .filter(o -> o.kind() == ObjectType.INCIDENT).toList();
        assertEquals(List.of("fading", "gone"), incidents.stream()
                .map(o -> o.attributes().get("key.entity_key")).sorted().toList(), "one Incident per entity, naming it");
        for (FakeObjectAccess.Opened o : incidents) assertEquals("churn", o.attributes().get("key.model"));
    }

    @Test
    void aPendingTemplateRuleIsCreatedByTheModelsFirstRun(@TempDir Path root) throws Exception {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Path cfg = root.resolve("config"), data = root.resolve("data");
        ChurnCorpus.plant(cfg, data);
        Path pending = Files.createDirectories(cfg.resolve(PendingAlertRules.DIR));
        Files.writeString(pending.resolve("likely_churn.toon"), """
                afterScore:
                  kind: churn-model
                  model: churn
                dataset: churn_scores_churn_latest
                measure: "max(score)"
                by[2]: model, entity_key
                comparator: gte
                threshold: 80
                severity: WARNING
                """);
        List<Event> audit = new ArrayList<>();
        EventLog events = EventLog.create();
        events.addSubscriber(e -> { if (EventType.AUDIT.equals(e.type())) audit.add(e); });

        assertEquals(List.of(), PendingAlertRules.onScoreProduced(cfg, () -> data, "churn-model", "churn", null, events),
                "before the first run there is no output: it stays pending");
        assertEquals(Map.of("kind", "churn-model", "model", "churn"), PendingAlertRules.list(cfg).get(0).get("afterScore"));
        assertEquals(List.of(), PendingAlertRules.onScoreProduced(cfg, () -> data, "anomaly-model", "churn", null, events),
                "an Anomaly Model of the same id is another score kind");

        score(cfg, data, "r1");
        assertEquals(List.of("likely_churn"),
                PendingAlertRules.onScoreProduced(cfg, () -> data, "churn-model", "churn", null, events));
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        Map<String, Object> created = store.get("alert-rule", "likely_churn").orElseThrow().content();
        assertEquals("churn_scores_churn_latest", created.get("dataset"));
        assertNull(created.get(PendingAlertRules.AFTER), "the trigger is not part of the rule");
        assertTrue(store.exists("dataset", "churn_scores_churn_latest"), "the Dataset over _latest is registered with it");
        assertEquals(List.of(), PendingAlertRules.list(cfg));
        assertEquals("churn-model", audit.get(audit.size() - 1).attributes().get("scoreKind"));
    }
}
