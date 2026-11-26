package com.gamma.insights;

import com.gamma.control.BiSpecRunner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Depth 2 (BI-EXPLAIN-DEPTH2-1) contributions by pairs of dimensions, computed by hand.
 *
 * <p>Jan → Feb 2026, region × plan: N × p1 100 → 150, S × p1 200 → 0 (absent in Feb), S × p2 0 → 190, E × p2
 * 100 → 160. Total 400 → 500, Δ = 100. Contributions: N × p1 +50 (share 0.5), S × p1 −200 (−2), S × p2 +190 (1.9),
 * E × p2 +60 (0.6); they add up to Δ. Largest share first, the cover of 0.8 × 100 = 80 is S × p2 alone (190 ≥ 80).
 */
class PairExplainGoldenTest {

    private static final double EPS = 1e-9;
    static final LocalDate AS_OF = LocalDate.of(2026, 3, 10);

    static Map<String, Object> profile(int maxPairValues) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dataset", "sales_ds");
        p.put("measure", Map.of("agg", "sum", "field", "amount"));
        p.put("field", "ts");
        p.put("grain", "month");
        p.put("dimensions", List.of("region", "plan"));
        p.put("pairs", true);
        p.put("maxPairValues", maxPairValues);
        return p;
    }

    static Map<String, Object> row(String region, String plan, String month, double amount) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("region", region);
        r.put("plan", plan);
        r.put("ts", month);
        r.put("sum_amount", amount);
        return r;
    }

    static List<Map<String, Object>> janFeb() {
        return List.of(row("N", "p1", "2026-01", 100), row("S", "p1", "2026-01", 200), row("E", "p2", "2026-01", 100),
                row("N", "p1", "2026-02", 150), row("S", "p2", "2026-02", 190), row("E", "p2", "2026-02", 160));
    }

    @Test
    void contributionsOfValuePairsAreHandComputed() throws Exception {
        InsightProfile p = InsightProfile.parse("g", profile(200));
        InsightScan.Buckets b = InsightScan.buckets("month", AS_OF, p.lookback());
        List<Map<String, Object>> bodies = new ArrayList<>();
        Map<String, Object> e = PairExplain.compute(p, b, body -> {
            bodies.add(body);
            return new BiSpecRunner.Run(janFeb(), false, null);
        }, Instant.parse("2026-03-02T06:00:00Z"));

        assertEquals(1, bodies.size(), "one statement per pair of dimensions");
        assertEquals(List.of("region", "plan", "ts"), bodies.get(0).get("groupBy"));
        assertEquals(400, bodies.get(0).get("limit"), "2 buckets × maxPairValues");
        assertEquals(Map.of("field", "ts", "asOf", "2026-02-28", "days", 59), bodies.get(0).get("window"),
                "only the two compared buckets are read");
        assertEquals("2026-01", e.get("previous"));
        assertEquals("2026-02", e.get("current"));
        assertEquals(400.0, (double) e.get("before"), EPS);
        assertEquals(500.0, (double) e.get("after"), EPS);

        @SuppressWarnings("unchecked") Map<String, Object> pair = ((List<Map<String, Object>>) e.get("drivers")).get(0);
        assertEquals("region × plan", pair.get("dimension"));
        assertEquals(List.of("S × p2"), pair.get("cover"));
        Map<String, double[]> want = Map.of("N × p1", new double[]{50, 0.5}, "S × p1", new double[]{-200, -2},
                "S × p2", new double[]{190, 1.9}, "E × p2", new double[]{60, 0.6});
        @SuppressWarnings("unchecked") List<Map<String, Object>> els = (List<Map<String, Object>>) pair.get("elements");
        assertEquals(4, els.size());
        double sum = 0;
        for (Map<String, Object> el : els) {
            double[] w = want.get(String.valueOf(el.get("value")));
            assertNotNull(w, "unexpected value pair " + el.get("value"));
            assertEquals(w[0], (double) el.get("contribution"), EPS, () -> "contribution of " + el.get("value"));
            assertEquals(w[1], (double) el.get("share"), EPS, () -> "share of " + el.get("value"));
            sum += (double) el.get("contribution");
        }
        assertEquals(100, sum, EPS, "the contributions add up to the change");
    }

    @Test
    void aPairOverTheCapIsRefusedInWordsNeverCut() throws Exception {
        InsightProfile p = InsightProfile.parse("g", profile(10));
        InsightScan.Buckets b = InsightScan.buckets("month", AS_OF, p.lookback());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 11; i++) rows.add(row("R" + i, "p1", "2026-02", 1));   // 11 pairs, 11 rows ≤ limit 20
        Map<String, Object> e = PairExplain.compute(p, b, body -> new BiSpecRunner.Run(rows, false, null), Instant.now());
        assertEquals(List.of(), e.get("drivers"));
        @SuppressWarnings("unchecked") Map<String, Object> r = ((List<Map<String, Object>>) e.get("refused")).get(0);
        assertEquals("region × plan", r.get("dimension"));
        assertTrue(String.valueOf(r.get("reason")).contains("more than 10 value pairs"), String.valueOf(r));

        Map<String, Object> cut = PairExplain.compute(p, b, body -> new BiSpecRunner.Run(janFeb(), true, null), Instant.now());
        assertEquals(List.of(), cut.get("drivers"), "a truncated result is refused, not explained from what was cut");
        assertEquals(1, ((List<?>) cut.get("refused")).size());
    }

    @Test
    void pairsNeedTwoDimensionsAndABoundedCap() {
        Map<String, Object> one = profile(200);
        one.put("dimensions", List.of("region"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> InsightProfile.parse("g", one))
                .getMessage().contains("at least two"));
        assertThrows(IllegalArgumentException.class, () -> InsightProfile.parse("g", profile(5000)));
        Map<String, Object> off = profile(200);
        off.remove("pairs");
        assertFalse(InsightProfile.parse("g", off).pairs(), "pairs are opt-in");
    }
}
