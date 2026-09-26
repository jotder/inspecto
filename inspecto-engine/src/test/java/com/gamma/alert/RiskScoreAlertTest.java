package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.objects.ObjectType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.query.DatasetRelation;
import com.gamma.risk.RiskCorpus;
import com.gamma.risk.RiskScoreEvaluator;
import com.gamma.risk.RiskScoreModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-RISK-SCORE-1 step 3: Incident priority through the EXISTING Alert Rule machinery — a per-entity rule
 * over the scores Dataset's {@code _latest} relation, {@code by} the model and entity key, threshold = the
 * model's {@code highThreshold}, raises one Incident per high entity naming it; a score that falls back heals
 * its Alert. No new alerting path: the production {@link DatasetMeasureProbe} and {@link AlertService}.
 */
class RiskScoreAlertTest {

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

    /** Score the corpus exactly as the Job does, writing the scores Datasets. */
    private static RiskScoreModel score(Path cfg, Path data, boolean m1Healed, String runId) throws Exception {
        Map<String, Object> content = RiskCorpus.plant(cfg, data, m1Healed);
        RiskScoreModel model = RiskScoreModel.fromMap(RiskCorpus.MODEL, content);
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        var run = RiskScoreEvaluator.evaluate(model, id -> DatasetRelation.relationSql(
                store.get("dataset", id).map(ComponentRegistry.Component::content).orElseThrow(), data, null));
        RiskScoreEvaluator.write(data, model, RiskScoreEvaluator.version(content), runId, Instant.now(), run.scored());
        return model;
    }

    @Test
    void aHighScoreRaisesAPerEntityIncidentAndAFallingScoreHeals(@TempDir Path root) throws Exception {
        Path cfg = root.resolve("config");
        Path data = root.resolve("data");
        RiskScoreModel model = score(cfg, data, false, "r1");
        // The documented example: the _latest relation declared as a Dataset, one rule over it.
        new ComponentStore(cfg.resolve("registry")).write("dataset", "risk_scores_subs_latest",
                Map.of("physicalRef", "risk_scores_subs_latest"));
        AlertRule rule = AlertRule.fromMap(Map.of("name", "high-risk-subscriber",
                "dataset", "risk_scores_subs_latest", "measure", "max(score)",
                "by", List.of("model", "entity_key"), "comparator", "gte", "threshold", model.highThreshold(),
                "severity", "CRITICAL", "description", "High Risk Score"));

        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = new AlertService(List.of(rule), noPipelines(), emptyStore(), objects);
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> cfg, () -> data);
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));

        assertEquals(1, svc.evaluateRules().size(), "only m1 (65.6) is at or above 50");
        List<FakeObjectAccess.Opened> incidents = objects.opened.stream()
                .filter(o -> o.kind() == ObjectType.INCIDENT).toList();
        assertEquals(1, incidents.size());
        FakeObjectAccess.Opened m1 = incidents.get(0);
        assertEquals("m1", m1.attributes().get("key.entity_key"), "the Incident names the entity");
        assertEquals("subs", m1.attributes().get("key.model"), "and the model the UI reads the factors from");
        assertEquals("high-risk-subscriber", m1.attributes().get("rule"));
        assertEquals("65.6", m1.attributes().get("value"));

        // m1's SIM swaps disappear: 25 + 0 + 0.6 = 25.6 — the next sweep heals its Alert, raising nothing new.
        score(cfg, data, true, "r2");
        assertEquals(0, svc.evaluateRules().size(), "no new Alert");
        assertEquals(1, objects.opened.stream().filter(o -> o.kind() == ObjectType.INCIDENT).count());
        assertFalse(objects.transitioned.isEmpty(), "the healed key resolves its Alert");
    }
}
