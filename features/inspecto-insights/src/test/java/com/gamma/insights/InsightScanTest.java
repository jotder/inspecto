package com.gamma.insights;

import com.gamma.control.BiSpecRunner;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The I-8 scan over a fake {@link InsightScan.Fetcher} (no DuckDB): what it finds, what false-discovery control keeps
 * out, and how the calendar, feedback, novelty, support and the value cap act. Daily data, 56 buckets ending
 * 2026-03-01 (as of 2026-03-02): regions N, S, E at 100 + (i·7 mod 5) a day; N jumps to 400 on the last day.
 */
class InsightScanTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 3, 2);

    static Map<String, Object> profile(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataset", "sales");
        m.put("measure", Map.of("agg", "sum", "field", "amount"));
        m.put("field", "ts");
        m.put("grain", "day");
        m.put("dimensions", List.of("region"));
        m.put("season", "none");
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** A fetcher answering the total and the per-region statement from {@code region → series}. */
    static InsightScan.Fetcher fake(InsightScan.Buckets b, Map<String, double[]> regions) {
        return body -> {
            @SuppressWarnings("unchecked") List<String> groupBy = (List<String>) body.get("groupBy");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < b.size(); i++) {
                double total = 0;
                for (Map.Entry<String, double[]> e : regions.entrySet()) {
                    total += e.getValue()[i];
                    if (groupBy.size() == 2) rows.add(Map.of("region", e.getKey(), "ts", b.label(i), "sum_amount", e.getValue()[i]));
                }
                if (groupBy.size() == 1) rows.add(Map.of("ts", b.label(i), "sum_amount", total));
            }
            int limit = ((Number) body.get("limit")).intValue();
            boolean cut = rows.size() > limit;
            return new BiSpecRunner.Run(cut ? rows.subList(0, limit) : rows, cut, null);
        };
    }

    static Map<String, double[]> regions(int n, double lastN) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (String r : List.of("N", "S", "E")) {
            double[] s = new double[n];
            for (int i = 0; i < n; i++) s[i] = 100 + (i * 7 + r.charAt(0)) % 5;
            m.put(r, s);
        }
        m.get("N")[n - 1] = lastN;
        return m;
    }

    static InsightScan.Result scan(InsightProfile p, Map<String, double[]> data, Map<String, String> feedback,
                                   java.util.function.ToIntFunction<String> before) throws Exception {
        InsightScan.Buckets b = InsightScan.buckets(p.grain(), AS_OF, p.lookback());
        InsightScan.Fetched f = InsightScan.fetch(p, b, fake(b, data));
        return InsightScan.scan(p, f, 0, b.size(), before, feedback);
    }

    @Test
    void bucketsEndWithTheLastCompleteOne() {
        InsightScan.Buckets d = InsightScan.buckets("day", AS_OF, 3);
        assertEquals(List.of(LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 1)), d.starts());
        InsightScan.Buckets m = InsightScan.buckets("month", AS_OF, 2);
        assertEquals("2026-02", m.label(1));
        InsightScan.Buckets w = InsightScan.buckets("week", AS_OF, 1);   // 2026-03-02 is a Monday
        assertEquals(LocalDate.of(2026, 2, 23), w.start(0));
    }

    @Test
    void theSpikeInNorthIsFoundAndRankedFirst() throws Exception {
        InsightProfile p = InsightProfile.parse("p", profile());
        InsightScan.Result r = scan(p, regions(56, 400), Map.of(), k -> 0);

        assertFalse(r.insights().isEmpty());
        InsightScan.Insight top = r.insights().get(0);
        assertEquals("anomaly", top.type());
        assertTrue(top.dimension().equals("region") && top.value().equals("N") || top.dimension().equals("*"),
                "the North slice or the total it lifts: " + top.key());
        assertTrue(r.insights().stream().anyMatch(i -> i.key().equals("anomaly|region|N")));
        assertEquals(LocalDate.of(2026, 3, 1), r.insights().stream().filter(i -> i.key().equals("anomaly|region|N"))
                .findFirst().orElseThrow().period());
        assertTrue(r.insights().stream().noneMatch(i -> "S".equals(i.value()) || "E".equals(i.value())),
                "the quiet regions are not reported");
        assertTrue(r.tests() > 3, "every slice × type that could run was tested");
        assertTrue(top.q() <= 0.05);
        assertTrue(String.valueOf(top.explanation().get("sentence")).contains("2026-03-01"));
    }

    @Test
    void quietDataReportsNothing() throws Exception {
        InsightProfile p = InsightProfile.parse("p", profile("types", List.of("anomaly")));
        assertTrue(scan(p, regions(56, 100 + (55 * 7 + 'N') % 5), Map.of(), k -> 0).insights().isEmpty());
    }

    @Test
    void anExcludedDayIsNeverReportedAndAnExpectedOneIsDroppedAndCounted() throws Exception {
        InsightProfile ex = InsightProfile.parse("p", profile("types", List.of("anomaly"),
                "calendar", List.of(Map.of("from", "2026-03-01", "label", "outage", "mode", "exclude"))));
        assertTrue(scan(ex, regions(56, 400), Map.of(), k -> 0).insights().isEmpty());

        InsightProfile exp = InsightProfile.parse("p", profile("types", List.of("anomaly"),
                "calendar", List.of(Map.of("from", "2026-03-01", "label", "campaign", "mode", "expected"))));
        InsightScan.Result r = scan(exp, regions(56, 400), Map.of(), k -> 0);
        assertTrue(r.insights().isEmpty());
        assertTrue(r.expectedDropped() >= 1);
    }

    @Test
    void feedbackSuppressesAndNoveltyLowersTheScore() throws Exception {
        InsightProfile p = InsightProfile.parse("p", profile("types", List.of("anomaly")));
        InsightScan.Insight fresh = scan(p, regions(56, 400), Map.of(), k -> 0).insights().stream()
                .filter(i -> i.key().equals("anomaly|region|N")).findFirst().orElseThrow();
        InsightScan.Insight seen = scan(p, regions(56, 400), Map.of(), k -> 1).insights().stream()
                .filter(i -> i.key().equals("anomaly|region|N")).findFirst().orElseThrow();
        assertEquals(fresh.score() / 2, seen.score(), 1e-9, "reported once before → novelty ½");

        InsightScan.Result r = scan(p, regions(56, 400), Map.of("anomaly|region|N", "not_useful"), k -> 0);
        assertTrue(r.insights().stream().noneMatch(i -> i.key().equals("anomaly|region|N")));
        assertTrue(r.suppressed() >= 1);
        assertEquals("useful", scan(p, regions(56, 400), Map.of("anomaly|region|N", "useful"), k -> 0).insights().stream()
                .filter(i -> i.key().equals("anomaly|region|N")).findFirst().orElseThrow().status());
    }

    @Test
    void aSliceBelowTheSupportIsNotTested() throws Exception {
        Map<String, double[]> d = regions(56, 400);
        double[] tiny = new double[56];
        for (int i = 0; i < 56; i++) tiny[i] = 0.1;
        tiny[55] = 5;   // a 50× spike in a slice holding ~0.03 % of the Measure
        d.put("X", tiny);
        InsightProfile p = InsightProfile.parse("p", profile("types", List.of("anomaly")));
        InsightScan.Result r = scan(p, d, Map.of(), k -> 0);
        assertTrue(r.insights().stream().noneMatch(i -> "X".equals(i.value())));
        assertEquals(1, r.belowSupport());
    }

    @Test
    void aDimensionWithTooManyValuesIsSkippedInWords() throws Exception {
        InsightProfile p = InsightProfile.parse("p", profile("maxValues", 2));
        InsightScan.Buckets b = InsightScan.buckets("day", AS_OF, p.lookback());
        InsightScan.Fetched f = InsightScan.fetch(p, b, fake(b, regions(56, 400)));
        assertEquals(1, f.skipped().size());
        assertTrue(f.slices().isEmpty());
        assertTrue(String.valueOf(f.skipped().get(0).get("reason")).contains("more than 2 values"));
    }

    @Test
    void aLevelShiftIsAChangepointAndASteadyClimbIsADrift() throws Exception {
        Map<String, double[]> shift = new HashMap<>();
        double[] s = new double[56];
        for (int i = 0; i < 56; i++) s[i] = (i < 48 ? 100 : 160) + (i * 7) % 5;
        shift.put("N", s);
        InsightProfile cp = InsightProfile.parse("p", profile("types", List.of("trend-break")));
        InsightScan.Insight c = scan(cp, shift, Map.of(), k -> 0).insights().get(0);
        assertEquals("trend-break", c.type());
        assertEquals(LocalDate.of(2026, 3, 1).minusDays(7), c.period(), "the change dates from its first bucket");

        Map<String, double[]> climb = new HashMap<>();
        double[] t = new double[56];
        for (int i = 0; i < 56; i++) t[i] = 100 + 2 * i + (i * 7) % 5;
        climb.put("N", t);
        InsightProfile dr = InsightProfile.parse("p", profile("types", List.of("drift")));
        InsightScan.Insight d = scan(dr, climb, Map.of(), k -> 0).insights().get(0);
        assertEquals("drift", d.type());
        assertTrue(d.delta() > 90, "about 2 a day over 55 days");
    }

    @Test
    void theProfileIsValidatedInWords() {
        assertThrows(IllegalArgumentException.class, () -> InsightProfile.parse("p",
                profile("measure", Map.of("agg", "avg", "field", "amount"))));
        assertThrows(IllegalArgumentException.class, () -> InsightProfile.parse("p", profile("grain", "hour")));
        assertThrows(IllegalArgumentException.class, () -> InsightProfile.parse("p", profile("types", List.of("magic"))));
        assertThrows(IllegalArgumentException.class, () -> InsightProfile.parse("p", profile("dimensions", List.of("ts"))));
        assertThrows(IllegalArgumentException.class, () -> InsightProfile.parse("p", profile("lookback", 120, "maxValues", 200)));
        assertThrows(IllegalArgumentException.class, () -> InsightProfile.parse("p",
                profile("calendar", List.of(Map.of("from", "2026-03-01", "mode", "exclude")))), "a calendar day needs a label");
        assertThrows(IllegalArgumentException.class, () -> InsightProfile.parse("bad id", profile()));
        InsightProfile ok = InsightProfile.parse("p", profile("sensitivity", "high"));
        assertEquals(0.10, ok.alpha());
        assertEquals(56, ok.lookback());
    }
}
