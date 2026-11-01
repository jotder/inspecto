package com.gamma.predictive;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The pure Churn Score maths ({@link ChurnScorer}) against hand-computed figures ({@link ChurnCorpus}'s header). */
class ChurnScorerTest {

    private static final ChurnModel MODEL = ChurnModel.fromMap(ChurnCorpus.MODEL, ChurnCorpus.model());
    private static final LocalDate AS_OF = ChurnCorpus.AS_OF;

    private static ChurnScorer.Scored score(ChurnModel model, String firstSeen, Double... values) {
        return ChurnScorer.score(model, AS_OF, new ChurnScorer.Observed("e", LocalDate.parse(firstSeen),
                Arrays.asList(values)));
    }

    private static double sigmoid100(double logit) { return 100.0 / (1.0 + Math.exp(-logit)); }

    @Test
    void contributionsAreCoefficientTimesTransformAndSumToTheLogit() {
        ChurnScorer.Scored s = score(MODEL, "2025-03-01", 86.0, 1.0, 40.0);   // fading
        assertEquals(List.of(0.03 * 86, -1 * Math.log(2), -0.5 * ((40 - 100) / 50.0)),
                s.parts().stream().map(ChurnScorer.Part::contribution).toList());
        double sum = MODEL.intercept() + s.parts().stream().mapToDouble(ChurnScorer.Part::contribution).sum();
        assertEquals(sum, s.logit(), 1e-12, "the logit is the intercept plus the contributions");
        assertEquals(ChurnCorpus.LOGIT.get("fading"), s.logit(), 1e-12);
        assertEquals(81.5605, s.score(), 1e-4, "hand-computed 100·σ(1.486853)");
        assertEquals(sigmoid100(s.logit()), s.score(), 1e-12);
        assertEquals("high", s.band());
        assertEquals(486, s.historyDays());
    }

    @Test
    void theCorpusFiguresAndBands() {
        assertEquals(1.3044, score(MODEL, "2025-01-05", 2.0, 3.0, 300.0).score(), 1e-4);
        ChurnScorer.Scored lapsing = score(MODEL, "2025-09-01", 60.0, 1.0, 60.0);
        assertEquals(62.4068, lapsing.score(), 1e-4);
        assertEquals("elevated", lapsing.band());
        ChurnScorer.Scored steady = score(MODEL, "2025-01-05", 2.0, 3.0, 300.0);
        assertEquals("normal", steady.band());
    }

    @Test
    void anEntityWithNoRowInTheWindowTakesZeroForCountsAndSumsAndTheDeclaredMissingElsewhere() {
        ChurnScorer.Scored gone = score(MODEL, "2025-06-01", null, null, null);
        assertFalse(gone.insufficient());
        assertEquals(List.of(120.0, 0.0, 0.0), gone.parts().stream().map(ChurnScorer.Part::value).toList());
        assertTrue(gone.parts().stream().allMatch(ChurnScorer.Part::imputed));
        assertEquals(3.6, gone.logit(), 1e-12);
        assertEquals(97.3403, gone.score(), 1e-4);
    }

    @Test
    void historyShorterThanTheMinimumIsInsufficientNotZero() {
        ChurnScorer.Scored newbie = score(MODEL, "2026-06-01", 0.0, 1.0, 25.0);   // 29 days before as_of
        assertTrue(newbie.insufficient());
        assertNull(newbie.score(), "no score, never 0");
        assertNull(newbie.logit());
        assertEquals("insufficient", newbie.band());
        assertEquals(Map.of("insufficient", "history", "historyDays", 29L, "minHistoryDays", 30), newbie.explanation());
        ChurnScorer.Scored boundary = score(MODEL, "2026-05-31", 0.0, 1.0, 25.0);   // exactly 30 days
        assertFalse(boundary.insufficient(), "a history equal to the minimum is enough");
    }

    @Test
    void anInputWithNoValueAndNoDeclaredMissingIsInsufficient() {
        Map<String, Object> m = new LinkedHashMap<>(ChurnCorpus.model());
        List<Object> inputs = new ArrayList<>((List<?>) m.get("inputs"));
        inputs.add(ChurnCorpus.input("basket", "avg(amount)", "none", Map.of(), 0.1));
        m.put("inputs", inputs);
        ChurnModel model = ChurnModel.fromMap("c", m);
        ChurnScorer.Scored s = score(model, "2025-01-01", 5.0, 1.0, 50.0, null);
        assertTrue(s.insufficient());
        assertEquals(Map.of("insufficient", "missing", "inputs", List.of("basket")), s.explanation());
        assertFalse(score(model, "2025-01-01", 5.0, 1.0, 50.0, 50.0).insufficient());
    }

    @Test
    void transformsLogCapAndZ() {
        ChurnModel.Input log = MODEL.inputs().get(1), cap = MODEL.inputs().get(0), z = MODEL.inputs().get(2);
        assertEquals(Math.log(4), log.transform(3), 1e-12);
        assertEquals(0.0, log.transform(-5), 0.0, "log holds a negative value at 0");
        assertEquals(120.0, cap.transform(400), 0.0);
        assertEquals(7.0, cap.transform(7), 0.0);
        assertEquals(-1.2, z.transform(40), 1e-12);
    }

    @Test
    void aStoredRowRecomputesFromItsExplanationAndATamperedOneDoesNot() {
        ChurnScorer.Scored s = score(MODEL, "2025-03-01", 86.0, 1.0, 40.0);
        ChurnScorer.Recomputed r = ChurnScorer.recompute(MODEL, s.explanation());
        assertEquals(s.score(), r.score(), 1e-12);
        assertEquals(s.band(), r.band());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = new ArrayList<>((List<Map<String, Object>>) s.explanation().get("inputs"));
        Map<String, Object> quiet = new LinkedHashMap<>(parts.get(0));
        quiet.put("value", 2.0);
        parts.set(0, quiet);
        Map<String, Object> tampered = new LinkedHashMap<>(s.explanation());
        tampered.put("inputs", parts);
        assertNotEquals(s.score(), ChurnScorer.recompute(MODEL, tampered).score(), 1e-6);

        ChurnScorer.Scored newbie = score(MODEL, "2026-06-20", 0.0, 1.0, 25.0);
        assertNull(ChurnScorer.recompute(MODEL, newbie.explanation()).score());
    }
}
