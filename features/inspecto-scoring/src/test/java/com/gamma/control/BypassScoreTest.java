package com.gamma.control;

import com.gamma.risk.RiskScoreModel;
import com.gamma.risk.RiskScorer;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TELCO-BYPASS-SIMBOX-1} slice B2: the shipped {@code tb_sim} Risk Score model, factor by factor. Pure arithmetic
 * over the model the template ships (no Job, no data): the weights, the protective sign, the cap, the clamp, the
 * high threshold and the recomputation from stored factors. {@link TelcoBypassGoldenTest} holds the same model to
 * the corpus end to end.
 */
class BypassScoreTest {

    /** Factor id to (weight, the tb_factors column its Measure reads): the plan's illustrative weights, pinned. */
    private static final Map<String, Object[]> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("b_diversity", new Object[]{14.0, "b_diversity_excess"});
        EXPECTED.put("short_calls", new Object[]{12.0, "short_uniform_index"});
        EXPECTED.put("no_inbound", new Object[]{14.0, "no_inbound_flag"});
        EXPECTED.put("voice_only", new Object[]{10.0, "voice_only_flag"});
        EXPECTED.put("static_cell", new Object[]{8.0, "static_cell_flag"});
        EXPECTED.put("seq_dial", new Object[]{8.0, "seq_dial_share"});
        EXPECTED.put("night", new Object[]{8.0, "night_share"});
        EXPECTED.put("onnet", new Object[]{6.0, "onnet_target_share"});
        EXPECTED.put("inbound_excess", new Object[]{-20.0, "inbound_excess"});
        EXPECTED.put("service_mix", new Object[]{-15.0, "normal_service_mix"});
        EXPECTED.put("mobility", new Object[]{-12.0, "mobility_flag"});
    }

    private static RiskScoreModel model() {
        Path file = BypassPack.TEMPLATE.resolve("config/registry/risk-scores/tb_sim.toon");
        try {
            return RiskScoreModel.fromMap("tb_sim", ToonHelper.load(file.toString()));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Map<String, Double> only(String id, double value) {
        return Map.of(id, value);
    }

    @Test
    void theModelShipsTheElevenFactorsWithTheirPinnedWeightsAndColumns() {
        RiskScoreModel m = model();
        assertEquals("sim", m.entityType());
        assertEquals(60.0, m.highThreshold());
        assertEquals("risk_scores_tb_sim", m.scoresDataset());
        assertNull(m.watchList(), "a template cannot ship the Entity List a watchList names; the admin guide adds it");
        assertEquals(new ArrayList<>(EXPECTED.keySet()), m.factors().stream().map(RiskScoreModel.Factor::id).toList());
        double positive = 0, protective = 0;
        for (RiskScoreModel.Factor f : m.factors()) {
            Object[] want = EXPECTED.get(f.id());
            assertEquals((Double) want[0], f.weight(), f.id() + " weight");
            assertEquals("tb_factors", f.dataset(), f.id());
            assertEquals("msisdn", f.key(), f.id());
            assertEquals("max(" + want[1] + ")", f.measure(), f.id() + " reads one column of the factor Dataset");
            assertEquals(Math.abs((Double) want[0]), f.cap(), f.id() + ": the cap is the factor's own weight, so no factor can exceed it");
            assertFalse(f.evidence().isEmpty(), f.id() + " shows the signal it weighs");
            if (f.weight() > 0) positive += f.weight(); else protective += f.weight();
        }
        assertEquals(80.0, positive, "the eight CDR signals; the device-cluster (12) and anomaly (8) factors arrive in B3 and B4");
        assertEquals(-47.0, protective);
        assertEquals(8, m.factors().stream().filter(f -> f.weight() > 0).count(), "eight positive factors");
    }

    @Test
    void eachPositiveFactorAloneContributesItsWeightTimesItsValue() {
        RiskScoreModel m = model();
        for (RiskScoreModel.Factor f : m.factors()) {
            if (f.weight() < 0) continue;
            RiskScorer.Scored full = RiskScorer.score(m, "s", only(f.id(), 1.0), null);
            assertEquals(f.weight(), full.score(), 1e-12, f.id() + " at 1.0");
            RiskScorer.Scored half = RiskScorer.score(m, "s", only(f.id(), 0.5), null);
            assertEquals(f.weight() / 2, half.score(), 1e-12, f.id() + " at 0.5");
            RiskScorer.FactorResult r = full.factors().stream().filter(x -> x.indicator().equals(f.id())).findFirst().orElseThrow();
            assertEquals(f.weight(), r.contribution(), 1e-12);
            assertFalse(r.capped(), f.id() + " at its natural maximum is not 'capped'");
            for (RiskScorer.FactorResult other : full.factors())
                if (!other.indicator().equals(f.id())) {
                    assertTrue(other.missing(), other.indicator() + " has no value: missing, flagged, not silently dropped");
                    assertEquals(0.0, other.contribution(), 0.0);
                }
        }
    }

    @Test
    void aProtectiveFactorSubtractsAndItsContributionIsNegative() {
        RiskScoreModel m = model();
        Map<String, Double> positive = new LinkedHashMap<>(Map.of("b_diversity", 1.0, "no_inbound", 1.0));   // 28
        assertEquals(28.0, RiskScorer.score(m, "s", positive, null).score(), 1e-12);
        for (Map.Entry<String, Double> p : Map.of("inbound_excess", -20.0, "service_mix", -15.0, "mobility", -12.0).entrySet()) {
            Map<String, Double> values = new LinkedHashMap<>(positive);
            values.put(p.getKey(), 1.0);
            RiskScorer.Scored s = RiskScorer.score(m, "s", values, null);
            assertEquals(28.0 + p.getValue(), s.score(), 1e-12, p.getKey() + " subtracts its weight");
            RiskScorer.FactorResult r = s.factors().stream().filter(x -> x.indicator().equals(p.getKey())).findFirst().orElseThrow();
            assertEquals(p.getValue(), r.contribution(), 1e-12, p.getKey() + " contribution is negative");
        }
        // a protective factor alone cannot push the score below zero: the sum is clamped to [0, 100]
        RiskScorer.Scored alone = RiskScorer.score(m, "s", Map.of("inbound_excess", 1.0, "service_mix", 1.0, "mobility", 1.0), null);
        assertEquals(0.0, alone.score(), 0.0);
        assertEquals(-47.0, alone.factors().stream().mapToDouble(RiskScorer.FactorResult::contribution).sum(), 1e-12);
    }

    @Test
    void theCapHoldsTheAbsoluteContributionInBothDirections() {
        RiskScoreModel m = model();
        RiskScorer.Scored s = RiskScorer.score(m, "s", Map.of("b_diversity", 3.0, "inbound_excess", 3.0), null);
        RiskScorer.FactorResult pos = s.factors().stream().filter(x -> x.indicator().equals("b_diversity")).findFirst().orElseThrow();
        RiskScorer.FactorResult neg = s.factors().stream().filter(x -> x.indicator().equals("inbound_excess")).findFirst().orElseThrow();
        assertTrue(pos.capped());
        assertEquals(14.0, pos.contribution(), 1e-12, "3 x 14 held to the cap of 14");
        assertTrue(neg.capped());
        assertEquals(-20.0, neg.contribution(), 1e-12, "a protective factor is held to minus its cap");
    }

    @Test
    void theHighThresholdIsInclusiveAndTheMaximumIsEighty() {
        RiskScoreModel m = model();
        Map<String, Double> flags = new LinkedHashMap<>();
        for (String id : List.of("b_diversity", "short_calls", "no_inbound", "voice_only", "static_cell")) flags.put(id, 1.0);   // 58
        Map<String, Double> atSixty = new LinkedHashMap<>(flags);
        atSixty.put("seq_dial", 0.25);   // 58 + 2
        RiskScorer.Scored at = RiskScorer.score(m, "s", atSixty, null);
        assertEquals(60.0, at.score(), 1e-12);
        assertTrue(at.high(), "60 is high: the threshold is inclusive");
        Map<String, Double> below = new LinkedHashMap<>(flags);
        below.put("seq_dial", 0.2499);
        assertFalse(RiskScorer.score(m, "s", below, null).high(), "just under 60 is not high");
        Map<String, Double> all = new LinkedHashMap<>(flags);
        for (String id : List.of("seq_dial", "night", "onnet")) all.put(id, 1.0);
        assertEquals(80.0, RiskScorer.score(m, "s", all, null).score(), 1e-12, "every positive factor at 1.0");
    }

    @Test
    void aScoreRecomputesFromItsStoredFactors() {
        RiskScoreModel m = model();
        Map<String, Double> v = new LinkedHashMap<>();
        v.put("b_diversity", 1.0);
        v.put("no_inbound", 1.0);
        v.put("seq_dial", 0.4);
        v.put("onnet", 1.0);
        v.put("mobility", 1.0);
        v.put("inbound_excess", 5.0);   // over its cap
        RiskScorer.Scored s = RiskScorer.score(m, "s", v, null);
        List<Map<String, Object>> stored = new ArrayList<>();
        for (RiskScorer.FactorResult f : s.factors()) stored.add(f.toMap());
        assertEquals(s.score(), RiskScorer.recompute(stored), 1e-12, "the stored factors are the whole explanation");
        assertEquals(5.2, s.score(), 1e-9, "14 + 14 + 3.2 + 6 - 12 - 20 (the protective factor held to its cap)");
        // a stored weight that was changed afterwards recomputes to a different score: the explanation cannot be edited quietly
        List<Map<String, Object>> tampered = new ArrayList<>();
        for (Map<String, Object> f : stored) {
            Map<String, Object> c = new LinkedHashMap<>(f);
            if ("b_diversity".equals(c.get("indicator"))) c.put("weight", 1.0);
            tampered.add(c);
        }
        assertNotEquals(s.score(), RiskScorer.recompute(tampered), 1e-12);
    }
}
