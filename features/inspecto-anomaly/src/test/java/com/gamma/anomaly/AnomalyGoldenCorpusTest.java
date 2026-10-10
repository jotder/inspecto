package com.gamma.anomaly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The golden corpus ({@link AnomalyCorpus}) over real DuckDB/Parquet: every planted anomaly is {@code high} and in
 * the top N, no look-alike is {@code high}, and the planted scores are pinned exactly. Each mutation the design
 * lists (§14: drop the MAD floor, mean/σ instead of median/MAD, the scored day inside its own baseline) turns a
 * NAMED assertion here red — see the class's assertion messages.
 */
class AnomalyGoldenCorpusTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static AnomalyScoreEvaluator.Run run;
    private static AnomalyModel model;

    @BeforeAll
    static void score(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        Map<String, Object> content = AnomalyCorpus.plant(cfg, data);
        model = AnomalyModel.fromMap(AnomalyCorpus.MODEL, content);
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        ViewStore views = new ViewStore(cfg.resolve("views"));
        run = AnomalyScoreEvaluator.evaluate(model, AnomalyCorpus.AS_OF, id ->
                DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, views));
    }

    private static Map<String, AnomalyScorer.Scored> byKey() {
        return run.scored().stream().collect(Collectors.toMap(AnomalyScorer.Scored::entityKey, s -> s));
    }

    @Test
    void everyEntityIsScoredForTheDayBeforeAsOf() {
        assertEquals("2026-09-30", run.period().toString());
        assertEquals(306, run.scored().size(), "300 background + 6 planted subscribers");
    }

    @Test
    void plantedAnomaliesAreHighAndTopRanked() {
        List<String> top = run.scored().stream().sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(AnomalyCorpus.PLANTED.size()).map(AnomalyScorer.Scored::entityKey).sorted().toList();
        assertEquals(AnomalyCorpus.PLANTED, top, "the planted anomalies are exactly the top 2");
        Map<String, AnomalyScorer.Scored> s = byKey();
        assertEquals("high", s.get("spike").band(), "the 10x spike");
        assertEquals("high", s.get("masked").band(),
                "a second spike behind three earlier ones (mean/sigma baseline would mask it)");
        List<String> high = run.scored().stream().filter(x -> "high".equals(x.band()))
                .map(AnomalyScorer.Scored::entityKey).sorted().toList();
        assertEquals(AnomalyCorpus.PLANTED, high, "precision and recall: high = the planted set, nothing else");
    }

    @Test
    void lookAlikesStayNormal() {
        Map<String, AnomalyScorer.Scored> s = byKey();
        for (String k : AnomalyCorpus.LOOK_ALIKES) assertEquals("normal", s.get(k).band(), k);
        assertTrue(s.get("flat").score() < 30, "flat history +1: the MAD floor keeps z = 1, got " + s.get("flat").score());
        assertEquals(0.0, s.get("drop").score(), "a fall on an 'up' feature deviates 0");
        AnomalyScorer.Scored newbie = s.get("newbie");
        assertEquals(2, newbie.insufficientCount(), "2 days of history: every feature insufficient");
        assertEquals(0.0, newbie.score());
        assertTrue(newbie.features().get(0).reason().contains("too few to score"), newbie.features().get(0).reason());
    }

    @Test
    void pinnedScoresAndExplanations() {
        Map<String, AnomalyScorer.Scored> s = byKey();
        AnomalyScorer.Scored spike = s.get("spike");
        AnomalyScorer.FeatureResult mb = spike.features().stream().filter(f -> f.feature().equals("data_mb")).findFirst().orElseThrow();
        assertEquals(3000.0, mb.observed());
        assertEquals("self", mb.baseline().kind());
        assertEquals(28, mb.baseline().points(), "28-day window, scored day excluded");
        assertEquals(10.0, mb.deviation(), "held to zCap");
        assertEquals(100 * (1 - Math.exp(-Math.sqrt((100.0 + 81.0) / 2) / 3)), spike.score(), 1e-9,
                "data at zCap 10; sessions 10 vs a flat 1 is z 9 (the 1-unit floor)");
        assertEquals(100.0 / 181.0, mb.share(), 1e-12, "data explains 100 of the 181 weighted dev^2");
        assertTrue(mb.reason().startsWith("Data volume (MB) 3000 vs a usual 299 (28 days)"), mb.reason());
        assertTrue(mb.reason().endsWith("MADs above"), mb.reason());

        AnomalyScorer.Scored masked = s.get("masked");
        AnomalyScorer.FeatureResult mmb = masked.features().stream().filter(f -> f.feature().equals("data_mb")).findFirst().orElseThrow();
        assertEquals(199.0, mmb.baseline().median(), 0.0, "the three 5 000 days do not move the median (a mean would be ~713)");
        assertEquals(10.0, mmb.deviation(), "900 vs ~200 is far past zCap");
        assertEquals(100 * (1 - Math.exp(-Math.sqrt(100.0 / 2) / 3)), masked.score(), 1e-9,
                "one feature at zCap, sessions at 0");

        AnomalyScorer.FeatureResult flat = s.get("flat").features().stream().filter(f -> f.feature().equals("data_mb")).findFirst().orElseThrow();
        assertEquals(0.0, flat.baseline().scaledMad(), "a perfectly flat history");
        assertEquals(1.0, flat.zSelf(), 1e-12, "the floor (1 unit) is the divisor");
    }

    @Test
    void everyScoreIsReproducibleFromItsStoredExplanation() throws Exception {
        for (AnomalyScorer.Scored s : run.scored()) {
            List<Map<String, Object>> stored = JSON.readValue(
                    AnomalyScoreEvaluator.featuresJson(s.features()), new TypeReference<>() {});
            double[] r = AnomalyScorer.recompute(stored, model.zCap(), model.scale());
            assertEquals(s.raw(), r[0], 1e-9, s.entityKey() + " raw");
            assertEquals(s.score(), r[1], 1e-9, s.entityKey() + " score");
        }
    }
}
