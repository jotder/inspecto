package com.gamma.query;

import com.gamma.query.KpiDefinition.Rag;
import com.gamma.query.KpiDefinition.Window;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `ASSURE-KPI-DEFINITIONS-1` — the pure half of a KPI: structural validation (fail closed), the RAG band at its
 * edges for each direction, and the current / comparison windows per grain.
 */
class KpiDefinitionTest {

    private static Map<String, Object> base() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataset", "sales");
        m.put("measure", "sum(amount)");
        m.put("timeField", "order_date");
        m.put("grain", "month");
        return m;
    }

    private static KpiDefinition kpi(Map<String, Object> overrides) {
        Map<String, Object> m = base();
        m.putAll(overrides);
        return KpiDefinition.fromMap("k", m);
    }

    private static String refusal(Map<String, Object> overrides) {
        Map<String, Object> m = base();
        m.putAll(overrides);
        return assertThrows(IllegalArgumentException.class, () -> KpiDefinition.fromMap("k", m)).getMessage();
    }

    // ── validation ───────────────────────────────────────────────────────────────

    @Test
    void defaultsAreUpAndPreviousAndTheMeasureIsSplit() {
        KpiDefinition k = kpi(Map.of());
        assertEquals(KpiDefinition.Direction.UP, k.direction());
        assertEquals(KpiDefinition.Comparison.PREVIOUS, k.comparison());
        assertEquals(new MeasureCompiler.Measure("sum", "amount"), k.measure());
        assertNull(k.target());
        assertNull(k.bands());
    }

    @Test
    void anUnknownGrainOrAMissingOneIsRefused() {
        assertTrue(refusal(Map.of("grain", "fortnight")).contains("unknown kpi grain 'fortnight'"));
        Map<String, Object> m = base();
        m.remove("grain");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> KpiDefinition.fromMap("k", m))
                .getMessage().contains("'grain' is required"));
    }

    @Test
    void everyGrainTheBriefNamesIsAccepted() {
        for (String g : List.of("day", "week", "month", "quarter", "year"))
            assertEquals(g, kpi(Map.of("grain", g)).grain().wire());
    }

    @Test
    void aBadMeasureTimeFieldComparisonOrDirectionIsRefused() {
        assertTrue(refusal(Map.of("measure", "median(amount)")).contains("'measure'"));
        assertTrue(refusal(Map.of("measure", "sum(a; drop)")).contains("'measure'"));
        assertTrue(refusal(Map.of("timeField", "order date")).contains("'timeField'"));
        assertTrue(refusal(Map.of("comparison", "yesterday")).contains("unknown kpi comparison"));
        assertTrue(refusal(Map.of("direction", "sideways")).contains("unknown kpi direction"));
        assertTrue(refusal(Map.of("target", "high")).contains("'target'"));
    }

    @Test
    void anUnknownKeyIsRefusedNotSilentlyDropped() {
        assertTrue(refusal(Map.of("thresholdz", 3)).contains("unknown key(s) [thresholdz]"));
        assertDoesNotThrow(() -> kpi(Map.of("x-note", "kept")));
    }

    @Test
    void unorderedBandsAreRefusedForEachDirection() {
        assertTrue(refusal(Map.of("bands", Map.of("green", 80, "amber", 90))).contains("amber must be <= green"));
        assertTrue(refusal(Map.of("direction", "down", "bands", Map.of("green", 90, "amber", 80)))
                .contains("green must be <= amber"));
        assertTrue(refusal(Map.of("direction", "band",
                "bands", Map.of("green", List.of(90, 110), "amber", List.of(95, 105)))).contains("inside the amber"));
        assertTrue(refusal(Map.of("direction", "band",
                "bands", Map.of("green", List.of(110, 90), "amber", List.of(80, 120)))).contains("inside the amber"));
        assertTrue(refusal(Map.of("direction", "band")).contains("needs 'bands'"));
        assertTrue(refusal(Map.of("bands", Map.of("green", 80))).contains("'green' and 'amber'"));
    }

    // ── RAG at the band edges ────────────────────────────────────────────────────

    @Test
    void upIsGreenAtTheGreenEdgeAmberAtTheAmberEdgeAndRedBelow() {
        KpiDefinition k = kpi(Map.of("bands", Map.of("green", 90, "amber", 80)));
        assertEquals(Rag.GREEN, k.rag(90.0));
        assertEquals(Rag.GREEN, k.rag(1e9));
        assertEquals(Rag.AMBER, k.rag(89.999));
        assertEquals(Rag.AMBER, k.rag(80.0));
        assertEquals(Rag.RED, k.rag(79.999));
    }

    @Test
    void downMirrorsUp() {
        KpiDefinition k = kpi(Map.of("direction", "down", "bands", Map.of("green", 5, "amber", 8)));
        assertEquals(Rag.GREEN, k.rag(5.0));
        assertEquals(Rag.GREEN, k.rag(-3.0));
        assertEquals(Rag.AMBER, k.rag(5.001));
        assertEquals(Rag.AMBER, k.rag(8.0));
        assertEquals(Rag.RED, k.rag(8.001));
    }

    @Test
    void bandIsGreenInsideAmberAroundItAndRedOutsideOnBothSides() {
        KpiDefinition k = kpi(Map.of("direction", "band",
                "bands", Map.of("green", List.of(95, 105), "amber", List.of(90, 110))));
        assertEquals(Rag.GREEN, k.rag(95.0));
        assertEquals(Rag.GREEN, k.rag(105.0));
        assertEquals(Rag.AMBER, k.rag(94.9));
        assertEquals(Rag.AMBER, k.rag(110.0));
        assertEquals(Rag.RED, k.rag(89.9));
        assertEquals(Rag.RED, k.rag(110.1));
    }

    @Test
    void withoutBandsTheTargetAloneDecidesGreenOrRedInItsDirection() {
        assertEquals(Rag.GREEN, kpi(Map.of("target", 10)).rag(10.0));
        assertEquals(Rag.RED, kpi(Map.of("target", 10)).rag(9.99));
        assertEquals(Rag.GREEN, kpi(Map.of("target", 10, "direction", "down")).rag(10.0));
        assertEquals(Rag.RED, kpi(Map.of("target", 10, "direction", "down")).rag(10.01));
        assertNull(kpi(Map.of()).rag(10.0), "no target and no bands ⇒ no status");
        assertNull(kpi(Map.of("target", 10)).rag(null), "no value ⇒ no status");
    }

    @Test
    void theToneIsTheStatusBadgeMappingOfTheBand() {
        assertEquals("success", KpiDefinition.tone(Rag.GREEN));
        assertEquals("warning", KpiDefinition.tone(Rag.AMBER));
        assertEquals("error", KpiDefinition.tone(Rag.RED));
        assertEquals("neutral", KpiDefinition.tone(null));
    }

    @Test
    void deltaPctFollowsTheReconBoardRule() {
        assertEquals(25.0, KpiDefinition.deltaPct(80.0, 100.0), 1e-9);
        assertEquals(-50.0, KpiDefinition.deltaPct(-10.0, -15.0), 1e-9, "a negative base divides by its magnitude");
        assertEquals(0.0, KpiDefinition.deltaPct(0.0, 0.0));
        assertNull(KpiDefinition.deltaPct(0.0, 5.0));
        assertNull(KpiDefinition.deltaPct(null, 5.0));
    }

    // ── windows per grain and comparison ─────────────────────────────────────────

    private static Window w(String from, String to) {
        return new Window(LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void theCurrentWindowIsThePeriodToDateForEveryGrain() {
        LocalDate asOf = LocalDate.parse("2026-08-13");   // a Thursday in Q3
        assertEquals(w("2026-08-13", "2026-08-14"), kpi(Map.of("grain", "day")).current(asOf));
        assertEquals(w("2026-08-10", "2026-08-14"), kpi(Map.of("grain", "week")).current(asOf));
        assertEquals(w("2026-08-01", "2026-08-14"), kpi(Map.of("grain", "month")).current(asOf));
        assertEquals(w("2026-07-01", "2026-08-14"), kpi(Map.of("grain", "quarter")).current(asOf));
        assertEquals(w("2026-01-01", "2026-08-14"), kpi(Map.of("grain", "year")).current(asOf));
    }

    @Test
    void thePreviousPeriodIsCutToTheSameElapsedLength() {
        LocalDate asOf = LocalDate.parse("2026-08-13");
        assertEquals(w("2026-08-12", "2026-08-13"), kpi(Map.of("grain", "day")).comparison(asOf));
        assertEquals(w("2026-08-03", "2026-08-07"), kpi(Map.of("grain", "week")).comparison(asOf));
        assertEquals(w("2026-07-01", "2026-07-14"), kpi(Map.of("grain", "month")).comparison(asOf));
        assertEquals(w("2026-04-01", "2026-05-15"), kpi(Map.of("grain", "quarter")).comparison(asOf));
        assertEquals(w("2025-01-01", "2025-08-14"), kpi(Map.of("grain", "year")).comparison(asOf));
    }

    @Test
    void aLongerCurrentMonthNeverRunsPastAShorterPreviousOne() {
        // 31 March to date is 31 days; February holds 28 — the comparison stops at February's end.
        assertEquals(w("2026-02-01", "2026-03-01"), kpi(Map.of()).comparison(LocalDate.parse("2026-03-31")));
    }

    @Test
    void lastYearIsTheSamePeriodAYearBackAndAWeekStaysAMonday() {
        LocalDate asOf = LocalDate.parse("2026-08-13");
        assertEquals(w("2025-08-01", "2025-08-14"), kpi(Map.of("comparison", "last-year")).comparison(asOf));
        Window week = kpi(Map.of("grain", "week", "comparison", "last-year")).comparison(asOf);
        assertEquals(w("2025-08-11", "2025-08-15"), week);
        assertEquals(java.time.DayOfWeek.MONDAY, week.from().getDayOfWeek());
    }

    // ── the calendar edges (the day-count comparison, pinned) ────────────────────

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void leapDayMonthToDateAgainstThePreviousMonthsSameDayCount() {
        KpiDefinition k = kpi(Map.of());
        assertEquals(w("2028-02-01", "2028-03-01"), k.current(d("2028-02-29")));
        assertEquals(w("2028-01-01", "2028-01-30"), k.comparison(d("2028-02-29")), "29 days of January");
    }

    @Test
    void leapDayLastYearFallsOnThe28thAndAMonthIsClippedToTheShorterFebruary() {
        assertEquals(w("2027-02-28", "2027-03-01"),
                kpi(Map.of("grain", "day", "comparison", "last-year")).comparison(d("2028-02-29")));
        assertEquals(w("2027-02-01", "2027-03-01"),
                kpi(Map.of("comparison", "last-year")).comparison(d("2028-02-29")), "29 days asked, 28 exist");
        assertEquals(w("2027-01-01", "2027-03-02"),
                kpi(Map.of("grain", "year", "comparison", "last-year")).comparison(d("2028-02-29")), "60 days each");
    }

    @Test
    void quarterBoundaries() {
        KpiDefinition q = kpi(Map.of("grain", "quarter"));
        assertEquals(w("2026-04-01", "2026-04-02"), q.current(d("2026-04-01")));
        assertEquals(w("2026-01-01", "2026-01-02"), q.comparison(d("2026-04-01")));
        assertEquals(w("2026-01-01", "2026-04-01"), q.current(d("2026-03-31")));
        assertEquals(w("2025-10-01", "2025-12-30"), q.comparison(d("2026-03-31")), "90 days of Q4");
        assertEquals(w("2026-10-01", "2027-01-01"), q.current(d("2026-12-31")));
    }

    @Test
    void theWeekStartsOnMonday() {
        KpiDefinition wk = kpi(Map.of("grain", "week"));
        assertEquals(w("2026-08-10", "2026-08-11"), wk.current(d("2026-08-10")), "a Monday opens its own week");
        assertEquals(w("2026-08-10", "2026-08-17"), wk.current(d("2026-08-16")), "a Sunday closes it");
        assertEquals(w("2026-08-03", "2026-08-10"), wk.comparison(d("2026-08-16")));
    }

    @Test
    void aYearBoundary() {
        assertEquals(w("2025-12-31", "2026-01-01"), kpi(Map.of("grain", "day")).comparison(d("2026-01-01")));
        assertEquals(w("2025-12-29", "2026-01-02"), kpi(Map.of("grain", "week")).current(d("2026-01-01")),
                "the week of New Year's Day starts in the old year");
        assertEquals(w("2025-12-01", "2025-12-02"), kpi(Map.of()).comparison(d("2026-01-01")));
        KpiDefinition y = kpi(Map.of("grain", "year"));
        assertEquals(w("2026-01-01", "2027-01-01"), y.current(d("2026-12-31")));
        assertEquals(w("2025-01-01", "2026-01-01"), y.comparison(d("2026-12-31")));
    }

    @Test
    void noneHasNoComparisonWindow() {
        assertNull(kpi(Map.of("comparison", "none")).comparison(LocalDate.parse("2026-08-13")));
    }
}
