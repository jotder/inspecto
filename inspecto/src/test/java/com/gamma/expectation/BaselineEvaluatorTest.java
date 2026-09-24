package com.gamma.expectation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DUCKLE-C8 — the {@code baseline} kind's decision logic (median of the accepted window, limits in either
 * direction, missing groups) and its record-level validation. Pure: no DuckDB, no store.
 */
class BaselineEvaluatorTest {

    // ── median ───────────────────────────────────────────────────────────────────

    @Test
    void medianIsTheMiddleValueAndAveragesAnEvenPair() {
        assertEquals(20, BaselineEvaluator.median(List.of(90.0, 10.0, 20.0)));
        assertEquals(15, BaselineEvaluator.median(List.of(20.0, 10.0)));
        assertEquals(25, BaselineEvaluator.median(List.of(40.0, 10.0, 30.0, 20.0)));
        assertEquals(7, BaselineEvaluator.median(List.of(7.0)));
    }

    // ── limits ───────────────────────────────────────────────────────────────────

    @Test
    void percentLimitsAreInclusiveInBothDirections() {
        assertNull(BaselineEvaluator.breach(100, 110, 10.0, 10.0, "percent"), "exactly +10% is in band");
        assertEquals("above", BaselineEvaluator.breach(100, 110.5, 10.0, 10.0, "percent"));
        assertNull(BaselineEvaluator.breach(100, 90, 10.0, 10.0, "percent"), "exactly -10% is in band");
        assertEquals("below", BaselineEvaluator.breach(100, 89, 10.0, 10.0, "percent"));
    }

    @Test
    void anUnsetDirectionIsUnbounded() {
        assertNull(BaselineEvaluator.breach(100, 1_000, null, 10.0, "percent"));
        assertNull(BaselineEvaluator.breach(100, 0, 10.0, null, "percent"));
    }

    @Test
    void absoluteLimitsCompareTheRawDifference() {
        assertNull(BaselineEvaluator.breach(100, 105, 5.0, 5.0, "absolute"));
        assertEquals("above", BaselineEvaluator.breach(100, 106, 5.0, 5.0, "absolute"));
        assertEquals("below", BaselineEvaluator.breach(100, 94, 5.0, 5.0, "absolute"));
        // absolute is not percent: +6 on 1000 is 0.6%, still a breach of an absolute 5
        assertEquals("above", BaselineEvaluator.breach(1000, 1006, 5.0, 5.0, "absolute"));
    }

    @Test
    void percentIsTakenAgainstTheMagnitudeOfANegativeBaseline() {
        // -100 → -80 is a RISE of 20 (20%), not a fall
        assertEquals("above", BaselineEvaluator.breach(-100, -80, 10.0, 10.0, "percent"));
        assertNull(BaselineEvaluator.breach(-100, -80, null, 10.0, "percent"), "a rise never breaches a fall limit");
    }

    @Test
    void anyMoveOffAZeroBaselineIsAnInfinitePercentage() {
        assertEquals("above", BaselineEvaluator.breach(0, 1, 1_000.0, null, "percent"));
        assertNull(BaselineEvaluator.breach(0, 0, 0.0, 0.0, "percent"));
        assertNull(BaselineEvaluator.breach(0, 1, null, 10.0, "percent"), "no upward limit, so no breach");
    }

    // ── compare ──────────────────────────────────────────────────────────────────

    private static Expectation.Baseline rows(Double up, Double down) {
        return new Expectation.Baseline(3, List.of("row_count"), List.of(), up, down, "percent", List.of(), false);
    }

    private static Map<String, Object> profile(Map<String, Map<String, Double>> groups) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("accepted", true);
        p.put("groups", groups);
        return p;
    }

    private static Map<String, Map<String, Double>> all(double rowCount) {
        return Map.of(BaselineEvaluator.ALL, Map.of("row_count", rowCount));
    }

    @Test
    void comparesAgainstTheMedianNotTheMeanOrTheLatest() {
        // window 100, 100, 1000: median 100, mean 400, latest 1000. Current 105 at ±10%:
        // passes against the median, would fail against the mean (-74%) or the latest (-90%).
        List<Map<String, Object>> window = List.of(profile(all(100)), profile(all(100)), profile(all(1000)));
        var ok = BaselineEvaluator.compare(rows(10.0, 10.0), all(105), window);
        assertEquals(0, ok.violations());
        assertEquals(3, ok.baselineSize());

        var bad = BaselineEvaluator.compare(rows(10.0, 10.0), all(120), window);
        assertEquals(1, bad.violations());
        BaselineEvaluator.Finding f = bad.findings().getFirst();
        assertEquals(100, f.baseline());
        assertEquals(120, f.current());
        assertEquals("above", f.direction());
        assertEquals("row_count", f.measure());
    }

    @Test
    void noAcceptedProfileMeansNothingToCompare() {
        var cold = BaselineEvaluator.compare(rows(0.0, 0.0), all(123), List.of());
        assertEquals(0, cold.violations(), "cold start: the first run cannot breach a baseline it does not have");
        assertEquals(0, cold.baselineSize());
    }

    @Test
    void perColumnMeasuresAreComparedPerColumnAndNullCellsSkipped() {
        var b = new Expectation.Baseline(5, List.of("null_rate", "mean"), List.of("amount", "email"),
                0.5, 0.5, "absolute", List.of(), false);
        Map<String, Double> past = new LinkedHashMap<>();
        past.put("amount.null_rate", 0.0);
        past.put("amount.mean", 10.0);
        past.put("email.null_rate", 0.1);
        past.put("email.mean", null);            // a text column has no numeric mean
        Map<String, Double> now = new LinkedHashMap<>(past);
        now.put("amount.mean", 12.0);            // +2 > 0.5 absolute
        now.put("email.null_rate", 0.9);         // +0.8 > 0.5
        var cmp = BaselineEvaluator.compare(b, Map.of(BaselineEvaluator.ALL, now),
                List.of(profile(Map.of(BaselineEvaluator.ALL, past))));
        assertEquals(2, cmp.violations());
        assertEquals(List.of("email/null_rate", "amount/mean"), cmp.findings().stream()
                .map(f -> f.column() + "/" + f.measure()).sorted(java.util.Comparator.reverseOrder()).toList());
    }

    @Test
    void aMissingGroupIsAViolationOnlyWithRequireExistingGroups() {
        Map<String, Map<String, Double>> both = Map.of("[\"EU\"]", Map.of("row_count", 50.0),
                "[\"US\"]", Map.of("row_count", 50.0));
        Map<String, Map<String, Double>> euOnly = Map.of("[\"EU\"]", Map.of("row_count", 50.0));
        List<Map<String, Object>> window = List.of(profile(both), profile(both));

        var lax = new Expectation.Baseline(2, List.of("row_count"), List.of(), 10.0, 10.0, "percent",
                List.of("region"), false);
        assertEquals(0, BaselineEvaluator.compare(lax, euOnly, window).violations(),
                "totals-only view: the surviving group looks normal");

        var strict = new Expectation.Baseline(2, List.of("row_count"), List.of(), 10.0, 10.0, "percent",
                List.of("region"), true);
        var cmp = BaselineEvaluator.compare(strict, euOnly, window);
        assertEquals(1, cmp.violations());
        assertEquals("[\"US\"]", cmp.findings().getFirst().group());
        assertEquals("missing_group", cmp.findings().getFirst().direction());
    }

    @Test
    void aGroupSeenInAMinorityOfTheWindowIsNotAnExistingGroup() {
        Map<String, Map<String, Double>> withRare = Map.of("[\"EU\"]", Map.of("row_count", 50.0),
                "[\"XX\"]", Map.of("row_count", 1.0));
        Map<String, Map<String, Double>> euOnly = Map.of("[\"EU\"]", Map.of("row_count", 50.0));
        var strict = new Expectation.Baseline(3, List.of("row_count"), List.of(), 10.0, 10.0, "percent",
                List.of("region"), true);
        // XX in 1 of 3 profiles → median row count 0 → not expected
        assertEquals(0, BaselineEvaluator.compare(strict, euOnly,
                List.of(profile(withRare), profile(euOnly), profile(euOnly))).violations());
        // XX in 2 of 3 → median 1 → expected, and missing
        assertEquals(1, BaselineEvaluator.compare(strict, euOnly,
                List.of(profile(withRare), profile(withRare), profile(euOnly))).violations());
    }

    @Test
    void profileSqlIsServerBuiltWithQuotedIdentifiers() {
        var b = new Expectation.Baseline(3, List.of("mean"), List.of("amount"), 1.0, null, "absolute",
                List.of("region"), false);
        String sql = BaselineEvaluator.profileSql(b, "REL");
        assertTrue(sql.contains("GROUP BY CAST(\"region\" AS VARCHAR)"), sql);
        assertTrue(sql.contains("avg(TRY_CAST(\"amount\" AS DOUBLE))"), sql);
        assertTrue(sql.endsWith("LIMIT " + (BaselineEvaluator.MAX_GROUPS + 1)), sql);
    }

    // ── record validation (the constructor is the whole validator) ─────────────────

    private static Expectation fromBody(String extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "b1");
        m.put("target", "orders");
        m.put("kind", "baseline");
        m.put("maxIncrease", 10);
        if (extra != null) for (String kv : extra.split(";")) {
            String[] p = kv.split("=", 2);
            m.put(p[0], p[1].startsWith("[") ? List.of(p[1].substring(1, p[1].length() - 1).split(",")) : p[1]);
        }
        return Expectation.fromMap(m);
    }

    @Test
    void aValidBaselineNeedsNoColumnAndRoundTripsItsFlatKeys() {
        Expectation e = fromBody("measures=[row_count,mean];columns=[amount];groupBy=[region];requireExistingGroups=true");
        assertNull(e.column());
        assertEquals(7, e.baseline().window());
        assertEquals("percent", e.baseline().limitUnit());
        Map<String, Object> m = e.toMap();
        assertEquals(List.of("region"), m.get("groupBy"));
        assertEquals(e, Expectation.fromMap(m), "toMap → fromMap is lossless");
    }

    @Test
    void theRecordRefusesEveryMalformedBaseline() {
        List<String> bad = List.of(
                "baselineWindow=0",
                "baselineWindow=101",
                "baselineWindow=2.5",
                "measures=[p99]",
                "measures=[mean]",                       // per-column measure, no columns
                "columns=[amount]",                      // columns, but only row_count measured
                "measures=[mean];columns=[\"x\"]",       // not a plain identifier
                "limitUnit=ratio",
                "requireExistingGroups=true",            // no groupBy
                "maxIncrease=-1");
        for (String b : bad)
            assertThrows(IllegalArgumentException.class, () -> fromBody(b), b);

        Map<String, Object> noLimit = new LinkedHashMap<>(Map.of("name", "b", "target", "t", "kind", "baseline"));
        assertThrows(IllegalArgumentException.class, () -> Expectation.fromMap(noLimit),
                "a baseline with neither limit can never fail");
    }

    @Test
    void baselineKeysOnAnotherKindAreIgnored() {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("name", "n", "target", "t", "column", "c",
                "kind", "non_null", "maxIncrease", "nonsense"));
        Expectation e = Expectation.fromMap(m);
        assertNull(e.baseline());
        assertFalse(e.toMap().containsKey("maxIncrease"));
    }

    @Test
    void theViolationCountRefusesABaselineByName(@org.junit.jupiter.api.io.TempDir java.nio.file.Path data) {
        Expectation e = fromBody(null);
        IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
                () -> ExpectationEvaluator.countSql(e, data));
        assertTrue(x.getMessage().contains("BaselineEvaluator"), x.getMessage());
    }
}
