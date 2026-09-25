package com.gamma.control;

import com.gamma.config.spec.ConfigSpecs;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UIE-5 (d): the server's copy of the SPA's date-range maths ({@code dashboard-date-range.spec.ts} pins the same cases). */
class DashboardDateRangeTest {

    private static String span(Object selection, String anchor) {
        LocalDate[] r = DashboardDateRange.resolve(selection, LocalDate.parse(anchor));
        return r == null ? null : r[0] + ".." + r[1];
    }

    @Test
    void presetsCountBackFromTheAnchorInclusive() {
        assertEquals("2026-09-18..2026-09-24", span("last-7-days", "2026-09-24"));
        assertEquals("2026-08-26..2026-09-24", span("last-30-days", "2026-09-24"));
        assertEquals("2026-06-27..2026-09-24", span("last-90-days", "2026-09-24"));
        assertEquals("2024-02-01..2024-03-01", span("last-30-days", "2024-03-01"));
        assertEquals("2026-09-01..2026-09-24", span("month-to-date", "2026-09-24"));
        assertEquals("2026-01-01..2026-09-24", span("year-to-date", "2026-09-24"));
        assertEquals("2025-09-25..2026-09-24", span("last-12-months", "2026-09-24"));
        assertEquals("2023-03-01..2024-02-29", span("last-12-months", "2024-02-29"));
    }

    @Test
    void quarterToDateIsCalendarCorrectAtEveryBoundary() {
        Map<String, String> firstDay = Map.of(
                "2026-01-01", "2026-01-01", "2026-03-31", "2026-01-01",
                "2026-04-01", "2026-04-01", "2026-06-30", "2026-04-01",
                "2026-07-01", "2026-07-01", "2026-09-30", "2026-07-01",
                "2026-10-01", "2026-10-01", "2026-12-31", "2026-10-01");
        firstDay.forEach((anchor, from) -> assertEquals(from + ".." + anchor, span("quarter-to-date", anchor), anchor));
    }

    @Test
    void everyDeclaredPresetResolves_andAnythingElseDoesNot() {
        for (String preset : ConfigSpecs.DASHBOARD_RANGE_PRESETS)
            assertNotNull(span(preset, "2026-09-24"), preset + " is declared in ConfigSpecs but not resolved");
        assertNull(span("last-week", "2026-09-24"));
        assertNull(span(Map.of("from", "2026-02-01", "to", "2026-01-01"), "2026-09-24"));
        assertEquals("2026-01-05..2026-02-10", span(Map.of("from", "2026-01-05", "to", "2026-02-10"), "2026-09-24"));
    }

    @Test
    void termsAreHalfOpen_scopedToADatasetWithTheColumn_andAnUnreadableRangeIsRefused() {
        Map<String, Object> board = Map.of("dateField", "sold_on", "asOf", "2026-09-24", "defaultRange", "month-to-date");
        Map<String, Object> dated = Map.of("columns", List.of(Map.of("name", "sold_on", "type", "date")));
        Map<String, Object> calc = Map.of("calculated", List.of(Map.of("name", "sold_on", "expr", "x")));
        LocalDate today = LocalDate.parse("2030-01-01");   // ignored: asOf wins
        assertEquals(List.of(Map.of("field", "sold_on", "op", ">=", "value", "2026-09-01"),
                        Map.of("field", "sold_on", "op", "<", "value", "2026-09-25")),
                DashboardDateRange.terms(board, dated, today));
        assertEquals(2, DashboardDateRange.terms(board, calc, today).size());
        assertTrue(DashboardDateRange.terms(board, Map.of("columns", List.of()), today).isEmpty(), "no column → no range");
        assertTrue(DashboardDateRange.terms(Map.of("defaultRange", "month-to-date"), dated, today).isEmpty(), "no dateField");

        // No asOf → counted back from today.
        assertEquals("2029-12-26", DashboardDateRange.terms(
                Map.of("dateField", "sold_on", "defaultRange", "last-7-days"), dated, today).get(0).get("value"));

        assertThrows(IllegalArgumentException.class, () -> DashboardDateRange.terms(
                Map.of("dateField", "sold_on", "defaultRange", "last-week"), dated, today));
    }
}
