package com.gamma.anomaly;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ANOMALY-DETECTION-1 S5: the example Anomaly Model each fraud / AML pack ships passes the {@code anomaly-model} save
 * gates against the pack's own registry and zero-row seed snapshots (every column in its Dataset's Schema, {@code time} a DATE/TIMESTAMP), and is
 * wired the D-AD7 way — an {@code anomaly.score} Job naming it and a pending Alert Rule over its {@code _latest} output.
 */
class AnomalyTemplateModelsTest {

    private static final Path TEMPLATES = Path.of("..", "..", "spaces", "_templates").toAbsolutePath().normalize();
    private final AnomalyKindValidator v = new AnomalyKindValidator();

    @ParameterizedTest
    @CsvSource({
            "telco-fraud, tf_subscriber_usage, tf_anomaly_score_job, tf_unusual_subscriber_usage",
            "mobile-money, mm_wallet_activity, mm_anomaly_score_job, mm_unusual_wallet_activity",
            "aml, aml_account_transfers, aml_anomaly_score_job, aml_unusual_account_transfers"})
    void thePackModelIsStorableAndWired(String pack, String model, String job, String pending)
            throws Exception {
        Path config = TEMPLATES.resolve(pack).resolve("config");
        ComponentRegistry.Component c = new ComponentStore(config.resolve("registry"))
                .get(AnomalyModel.KIND, model).orElseThrow(() -> new AssertionError(pack + ": no model " + model));
        Map<String, Object> content = c.content();
        v.validate(model, content);
        // A fresh Space has no ingested data: the pack ships a zero-row schema-seed.parquet in each feature store.
        Path data = TEMPLATES.resolve(pack).resolve("data");
        v.validateInSpace(config, () -> data, model, content);

        Map<String, Object> j = ToonHelper.load(config.resolve("jobs").resolve(job + ".toon").toString());
        @SuppressWarnings("unchecked") Map<String, Object> jb = (Map<String, Object>) j.get("job");
        assertNotNull(jb, job);
        assertEquals("anomaly.score", jb.get("type"));
        assertEquals(model, jb.get("model"));

        Map<String, Object> p = ToonHelper.load(config.resolve("pending/alert-rules").resolve(pending + ".toon").toString());
        assertEquals(Map.of("kind", "anomaly-model", "model", model), p.get("afterScore"));
        assertEquals("anomaly_scores_" + model + "_latest", p.get("dataset"));
        AnomalyModel parsed = AnomalyModel.fromMap(model, content);
        assertEquals(parsed.highThreshold(), ((Number) p.get("threshold")).doubleValue(), 0,
                "the pending rule fires at the model's high threshold");
    }
}
