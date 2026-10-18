package com.gamma.anomaly;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.sql.SqlSandboxPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The isolation-forest option of an Anomaly Model (ANALYTICS-CORE-1 C4) over the golden corpus ({@link AnomalyCorpus}):
 * the robust-statistical score is untouched when the option is off, the forest run is deterministic, keeps the
 * per-feature explanation, and is recomputable from it.
 */
class AnomalyForestScorerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static Function<String, String> relation;
    private static AnomalyModel robustModel, forestModel;
    private static Map<String, AnomalyScorer.Scored> robust, robustExplicit, forest, forestAgain;

    @BeforeAll
    static void run(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        AnomalyCorpus.plant(cfg, data);
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        ViewStore views = new ViewStore(cfg.resolve("views"));
        relation = id -> {
            try {
                return DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, views);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        robustModel = AnomalyModel.fromMap("c4", AnomalyCorpus.model());
        Map<String, Object> explicit = new LinkedHashMap<>(AnomalyCorpus.model());
        explicit.put("method", "robust_z");
        Map<String, Object> f = new LinkedHashMap<>(AnomalyCorpus.model());
        f.put("method", "isolation_forest");
        f.put("forest", Map.of("trees", 100, "sampleSize", 128, "seed", 7));
        forestModel = AnomalyModel.fromMap("c4", f);
        robust = score(robustModel);
        robustExplicit = score(AnomalyModel.fromMap("c4", explicit));
        forest = score(forestModel);
        forestAgain = score(forestModel);
    }

    private static Map<String, AnomalyScorer.Scored> score(AnomalyModel model) throws Exception {
        return AnomalyScoreEvaluator.evaluate(model, AnomalyCorpus.AS_OF, relation).scored().stream()
                .collect(Collectors.toMap(AnomalyScorer.Scored::entityKey, s -> s));
    }

    @Test
    void methodRobustZIsTheDefaultAndChangesNothing() {
        assertNull(robustModel.forest());
        assertEquals("robust_z", AnomalyForestScorer.method(robustModel));
        assertEquals("isolation_forest", AnomalyForestScorer.method(forestModel));
        assertEquals(robust.keySet(), robustExplicit.keySet());
        for (String k : robust.keySet()) {
            assertEquals(robust.get(k).score(), robustExplicit.get(k).score(), 0, k);
            assertEquals(robust.get(k).raw(), robustExplicit.get(k).raw(), 0, k);
        }
        // and the shipped numbers of the golden corpus are still what its own test pins
        assertEquals(100 * (1 - Math.exp(-Math.sqrt(100.0 / 2) / 3)), robust.get("masked").score(), 1e-9);
    }

    @Test
    void theForestRanksThePlantedAnomaliesAtTheTopAndKeepsTheLookAlikesQuiet() {
        List<String> ranked = new ArrayList<>(forest.keySet());
        ranked.sort((a, b) -> Double.compare(forest.get(b).score(), forest.get(a).score()));
        assertTrue(ranked.subList(0, 6).containsAll(AnomalyCorpus.PLANTED), "top 6: " + ranked.subList(0, 6));
        assertTrue(forest.get("spike").score() > 50, "spike " + forest.get("spike").score());
        for (AnomalyScorer.Scored s : forest.values()) assertTrue(s.score() >= 0 && s.score() <= 100);
        assertTrue(forest.get("n150").score() < 30, "a background subscriber stays low: " + forest.get("n150").score());
    }

    @Test
    void theSameModelAndDataGiveTheSameScoresBitForBit() {
        assertEquals(forest.keySet(), forestAgain.keySet());
        for (String k : forest.keySet()) {
            assertEquals(forest.get(k).score(), forestAgain.get(k).score(), 0, k);
            assertEquals(forest.get(k).features().get(0).feature(), forestAgain.get(k).features().get(0).feature(), k);
        }
    }

    @Test
    void thePerFeatureExplanationIsKeptAndTheScoreIsRecomputableFromIt() throws Exception {
        AnomalyScorer.Scored spike = forest.get("spike");
        assertEquals(2, spike.features().size());
        AnomalyScorer.FeatureResult top = spike.features().get(0);
        assertTrue(top.contribution() >= spike.features().get(1).contribution(), "largest contribution first");
        assertEquals(robust.get("spike").features().stream().filter(f -> f.feature().equals(top.feature())).findFirst().orElseThrow()
                .deviation(), top.deviation(), 0, "the robust per-feature evidence is carried over");
        assertTrue(top.reason().contains("isolation share"), top.reason());
        for (AnomalyScorer.Scored s : forest.values()) {
            List<Map<String, Object>> stored = JSON.readValue(AnomalyScoreEvaluator.featuresJson(s.features()), new TypeReference<>() {});
            double[] r = AnomalyForestScorer.recompute(stored);
            assertEquals(s.raw(), r[0], 1e-9, s.entityKey() + " raw");
            assertEquals(s.score(), r[1], 1e-7, s.entityKey() + " score");
            assertEquals(s.band(), forestModel.band(r[1]), s.entityKey() + " band");
        }
    }

    @Test
    void moreEntitiesThanTheCapFailsTheRunAndThePreviewIsRefused() {
        IllegalStateException cap = assertThrows(IllegalStateException.class, () -> AnomalyScoreEvaluator.evaluate(forestModel,
                AnomalyCorpus.AS_OF, relation, 100, AnomalyScoreEvaluator.MAX_BUCKET_ROWS, SqlSandboxPolicy.defaultPolicy()));
        assertTrue(cap.getMessage().contains("refusing to score a subset"), cap.getMessage());
        IllegalArgumentException preview = assertThrows(IllegalArgumentException.class, () -> AnomalyScoreEvaluator.preview(forestModel,
                AnomalyCorpus.AS_OF, relation, "spike", SqlSandboxPolicy.defaultPolicy()));
        assertTrue(preview.getMessage().contains("Job only"), preview.getMessage());
    }

    @Test
    void theModelGrammarRefusesWhatItCannotRun() {
        Map<String, Object> base = AnomalyCorpus.model();
        assertThrows(IllegalArgumentException.class, () -> AnomalyModel.fromMap("m", with(base, "method", "xgboost")));
        assertThrows(IllegalArgumentException.class, () -> AnomalyModel.fromMap("m", with(base, "forest", Map.of("trees", 10))),
                "forest needs method: isolation_forest");
        Map<String, Object> bad = with(with(base, "method", "isolation_forest"), "forest", Map.of("trees", 0));
        assertThrows(IllegalArgumentException.class, () -> AnomalyModel.fromMap("m", bad));
        assertThrows(IllegalArgumentException.class,
                () -> AnomalyModel.fromMap("m", with(with(base, "method", "isolation_forest"), "forest", Map.of("depth", 3))));
        AnomalyModel defaults = AnomalyModel.fromMap("m", with(base, "method", "isolation_forest"));
        assertEquals(100, defaults.forest().trees());
        assertEquals(256, defaults.forest().sampleSize());
        assertEquals(1L, defaults.forest().seed());
    }

    private static Map<String, Object> with(Map<String, Object> m, String k, Object v) {
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put(k, v);
        return out;
    }
}
