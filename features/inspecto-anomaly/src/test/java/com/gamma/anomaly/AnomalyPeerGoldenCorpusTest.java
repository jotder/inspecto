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
 * The peer golden corpus ({@link AnomalyPeerCorpus}) over real DuckDB/Parquet: U2 (SIM-box), U5 (peer outlier) and a
 * lone spike inside a promoted cohort are exactly the {@code high} set; the promoted cohort, a peer-only newcomer and
 * a cohort of one stay {@code normal}. Mutations that must turn a NAMED assertion red: drop the cohort shift (every
 * PROMO member goes high); drop the peer z from {@code dev} (simbox and adjuster go normal); let a cohort below
 * {@code minGroupSize} score (loner gets a peer score).
 */
class AnomalyPeerGoldenCorpusTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static AnomalyScoreEvaluator.Run run;
    private static AnomalyModel model;

    @BeforeAll
    static void score(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        Map<String, Object> content = AnomalyPeerCorpus.plant(cfg, data);
        model = AnomalyModel.fromMap(AnomalyPeerCorpus.MODEL, content);
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        ViewStore views = new ViewStore(cfg.resolve("views"));
        run = AnomalyScoreEvaluator.evaluate(model, AnomalyPeerCorpus.AS_OF, id ->
                DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, views));
    }

    private static Map<String, AnomalyScorer.Scored> byKey() {
        return run.scored().stream().collect(Collectors.toMap(AnomalyScorer.Scored::entityKey, s -> s));
    }

    private static AnomalyScorer.FeatureResult feature(AnomalyScorer.Scored s, String id) {
        return s.features().stream().filter(f -> f.feature().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Design §16.3 (exclusion before peers): excluded entities are dropped BEFORE the cohort medians, so they cannot
     * shape their cohort's baseline. Excluding the heavier half of PRE (60 members at 150..190) and the heavy
     * {@code adjuster} (POST, 10x) shrinks both cohorts by exactly those members and lowers PRE's peer median.
     */
    @Test
    void anExcludedHeavyEntityDoesNotShapeItsCohortMedian(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        AnomalyPeerCorpus.plant(cfg, data);
        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        ViewStore views = new ViewStore(cfg.resolve("views"));
        java.util.function.Predicate<String> heavy = k -> k.equals("adjuster")
                || (k.matches("p\\d{3}") && Integer.parseInt(k.substring(1)) % 10 >= 5);
        AnomalyScoreEvaluator.Run ex = AnomalyScoreEvaluator.evaluate(model, AnomalyPeerCorpus.AS_OF, id ->
                DatasetRelation.relationSql(store.get("dataset", id).orElseThrow().content(), data, views), heavy);
        assertEquals(61, ex.excluded());
        assertEquals(345 - 61, ex.scored().size());
        assertTrue(ex.scored().stream().noneMatch(s -> heavy.test(s.entityKey())), "excluded entities are not scored");
        Map<String, AnomalyScorer.Scored> exByKey = ex.scored().stream()
                .collect(Collectors.toMap(AnomalyScorer.Scored::entityKey, s -> s));
        var preBefore = feature(byKey().get("simbox"), "data_mb").peerBaseline();
        var preAfter = feature(exByKey.get("simbox"), "data_mb").peerBaseline();
        assertEquals(preBefore.points() - 60, preAfter.points(), "the excluded PRE members left the cohort");
        assertTrue(preAfter.median() < preBefore.median(),
                "PRE median " + preBefore.median() + " -> " + preAfter.median() + ": the excluded heavy half no longer lifts it");
        var postBefore = feature(byKey().get("q000"), "data_mb").peerBaseline();
        var postAfter = feature(exByKey.get("q000"), "data_mb").peerBaseline();
        assertEquals(postBefore.points() - 1, postAfter.points(), "adjuster left the POST cohort");
    }

    @Test
    void highIsExactlyThePlantedPeerAnomalies() {
        assertEquals(345, run.scored().size(), "340 background + 5 planted subscribers");
        List<String> high = run.scored().stream().filter(x -> "high".equals(x.band()))
                .map(AnomalyScorer.Scored::entityKey).sorted().toList();
        assertEquals(AnomalyPeerCorpus.PLANTED, high, "precision and recall: high = the planted set, nothing else");
    }

    @Test
    void simboxIsSeveralModeratelyUnusualFeaturesVsPeers() {
        AnomalyScorer.Scored s = byKey().get("simbox");
        for (String id : List.of("data_mb", "sessions")) {
            AnomalyScorer.FeatureResult f = feature(s, id);
            assertTrue(Math.abs(f.zSelf()) < 3, id + ": normal for itself, zSelf " + f.zSelf());
            assertTrue(f.zPeer() > 4 && f.zPeer() < model.zCap(), id + ": moderately above peers, zPeer " + f.zPeer());
            assertEquals("PRE", f.peerBaseline().basis());
        }
        assertEquals(6.0, feature(s, "sessions").zPeer(), 1e-12, "7 sessions vs a flat 1 (the 1-unit floor)");
    }

    @Test
    void adjusterIsAPersistentOutlierVsPeersOnly() {
        AnomalyScorer.Scored s = byKey().get("adjuster");
        AnomalyScorer.FeatureResult mb = feature(s, "data_mb");
        assertTrue(Math.abs(mb.zSelf()) < 3, "always this high: normal for itself, zSelf " + mb.zSelf());
        assertEquals(10.0, mb.deviation(), "ten times its peers: held to zCap by the peer z");
        assertEquals(100 * (1 - Math.exp(-Math.sqrt(100.0 / 2) / 3)), s.score(), 1e-9, "one feature at zCap");
        assertTrue(mb.reason().contains("vs peers'") && mb.reason().contains("peers POST, 121 entities"), mb.reason());
    }

    @Test
    void aWholeCohortShiftIsNotEveryMembersAnomaly() {
        Map<String, AnomalyScorer.Scored> s = byKey();
        for (int i = 0; i < 100; i++) {
            AnomalyScorer.Scored r = s.get("r%03d".formatted(i));
            assertEquals("normal", r.band(), r.entityKey() + " moved with its cohort (drop the cohort shift -> high)");
        }
        AnomalyScorer.FeatureResult mb = feature(s.get("r000"), "data_mb");
        assertEquals(2.0, mb.cohortShift(), 0.1, "the promotion doubled the cohort");
        assertTrue(mb.reason().contains("cohort shift"), mb.reason());
        assertEquals(1.0, feature(s.get("p000"), "data_mb").cohortShift(), 0.1, "PRE did not move");
        AnomalyScorer.Scored spike = s.get("promo_spike");
        assertEquals("high", spike.band(), "a member that moves ten-fold on a two-fold day is still found");
        assertTrue(feature(spike, "data_mb").zSelf() > model.zCap(), "self z past zCap even after the shift");
    }

    @Test
    void aNewcomerIsScoredOnPeersOnlyAndFlagged() {
        AnomalyScorer.Scored s = byKey().get("newpeer");
        assertEquals("normal", s.band());
        assertEquals(0, s.insufficientCount(), "D-AD12: peers score it");
        AnomalyScorer.FeatureResult mb = feature(s, "data_mb");
        assertTrue(mb.peersOnly(), "flagged in the explanation");
        assertTrue(mb.baseline().insufficient());
        assertTrue(mb.reason().contains("scored on peers only"), mb.reason());
        assertEquals(Boolean.TRUE, mb.toMap().get("peersOnly"));
    }

    @Test
    void aCohortBelowMinGroupSizeGivesNoPeerScore() {
        AnomalyScorer.Scored s = byKey().get("loner");
        assertEquals("normal", s.band());
        AnomalyScorer.FeatureResult mb = feature(s, "data_mb");
        assertTrue(mb.peerBaseline().insufficient(), "D-AD11 a: a cohort of 1 < 30");
        assertTrue(Double.isNaN(mb.zPeer()));
        assertEquals(1.0, mb.cohortShift(), "no cohort, no shift");
        assertTrue(mb.reason().contains("cohort SOLO has 1 < 30 entities"), mb.reason());
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
