package com.gamma.insights;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.gamma.insights.InsightScanTest.profile;
import static com.gamma.insights.InsightScanTest.scan;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The I-9 concentration and I-10 distribution-shift Insight Types of the scan, over the same fake fetcher as
 * {@link InsightScanTest}: five regions A–E at about 100 a day for 56 days, then a last day that changes the mix.
 */
class InsightScanDimensionTest {

    static Map<String, double[]> five(double... last) {
        Map<String, double[]> m = new LinkedHashMap<>();
        String[] names = {"A", "B", "C", "D", "E"};
        for (int r = 0; r < names.length; r++) {
            double[] s = new double[56];
            for (int i = 0; i < s.length; i++) s[i] = 100 + (i * 7 + names[r].charAt(0)) % 5;
            if (last.length > 0) s[55] = last[r];
            m.put(names[r], s);
        }
        return m;
    }

    @Test
    void threeRegionsTakingTheBulkIsAConcentrationFinding() throws Exception {
        InsightProfile p = InsightProfile.parse("p", profile("types", List.of("concentration")));
        // top 3 held ~60 %; on the last day A, B, C hold 900 of 1000 = 90 %
        InsightScan.Result r = scan(p, five(300, 300, 300, 50, 50), Map.of(), k -> 0);

        assertEquals(1, r.insights().size());
        InsightScan.Insight in = r.insights().get(0);
        assertEquals("concentration", in.type());
        assertEquals("region", in.dimension());
        assertEquals(InsightScan.TOTAL, in.value());
        assertEquals(0.9, in.observed(), 1e-9);
        assertEquals(0.3, in.impact(), 0.01, "impact = the share points moved");
        assertEquals(List.of("A", "B", "C"), in.explanation().get("topValues"));
        assertEquals(3, in.explanation().get("paretoCount"), "30 % + 30 % = 60 %, + 30 % = 90 % ≥ 80 %");
    }

    @Test
    void aChangedMixIsADistributionShiftFinding() throws Exception {
        InsightProfile p = InsightProfile.parse("p", profile("types", List.of("distribution-shift")));
        // same total (~500) but E jumps from ~20 % to 60 %
        InsightScan.Result r = scan(p, five(50, 50, 50, 50, 300), Map.of(), k -> 0);

        assertEquals(1, r.insights().size());
        InsightScan.Insight in = r.insights().get(0);
        assertEquals("distribution-shift", in.type());
        assertEquals("E", in.explanation().get("moverValue"));
        assertEquals(0.6, (Double) in.explanation().get("moverShareNow"), 1e-9);
        assertEquals(0.4, in.impact(), 0.01, "impact = total variation: 40 % of the whole moved to E");
        assertTrue(in.q() <= p.alpha());
        assertTrue(String.valueOf(in.explanation().get("sentence")).contains("across region shifted"));
    }

    @Test
    void aSteadyMixFindsNeither() throws Exception {
        InsightProfile p = InsightProfile.parse("p", profile("types", List.of("concentration", "distribution-shift")));
        InsightScan.Result r = scan(p, five(), Map.of(), k -> 0);
        assertTrue(r.insights().isEmpty(), () -> String.valueOf(r.insights()));
        assertEquals(2, r.tests(), "one test of each type for the one dimension");
    }

    @Test
    void aStableLowVolumeMixHoldsItsFalsePositiveRate() {
        // null: 6 values, Poisson(5) each per bucket (~30 units), 56 buckets; PSI is skewed there, so a normal tail
        // of its z gave P(p < .05) well above .05 — the rank p must stay near nominal
        java.util.Random rnd = new java.util.Random(42);
        List<String> values = List.of("a", "b", "c", "d", "e", "f");
        int runs = 1000, hits = 0;
        for (int run = 0; run < runs; run++) {
            double[][] parts = new double[56][6];
            for (double[] row : parts) for (int j = 0; j < 6; j++) row[j] = poisson(rnd, 5);
            var f = InsightScan.distributionShift(parts, values, true);
            if (f != null && f.p() < 0.05) hits++;
        }
        assertTrue(hits / (double) runs <= 0.07, "false-positive rate " + hits / (double) runs);
    }

    @Test
    void anObviousShiftSurvivesTheFullProfileAndLowSensitivity() throws Exception {
        // E from ~20 % to 50 %: with the three series types on (17 tests) and at α .01, a rank-only p (≥ 1/56) lost
        List<String> all = List.of("anomaly", "trend-break", "drift", "concentration", "distribution-shift");
        for (String sens : List.of("normal", "low")) {
            InsightProfile p = InsightProfile.parse("p", profile("types", all, "sensitivity", sens));
            InsightScan.Result r = scan(p, five(50, 50, 50, 50, 200), Map.of(), k -> 0);
            assertTrue(r.insights().stream().anyMatch(i -> i.type().equals("distribution-shift")),
                    sens + ": " + r.insights());
        }
    }

    private static int poisson(java.util.Random r, double lambda) {
        double l = Math.exp(-lambda), p = 1;
        int k = 0;
        do { k++; p *= r.nextDouble(); } while (p > l);
        return k - 1;
    }

    @Test
    void theDimensionTypesAreOptInAndNeedADimension() {
        assertEquals(List.of("anomaly", "trend-break", "drift"), InsightProfile.parse("p", profile()).types());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> InsightProfile.parse("p",
                profile("types", List.of("concentration"), "dimensions", List.of())));
        assertTrue(e.getMessage().contains("dimensions"), e.getMessage());
    }
}
