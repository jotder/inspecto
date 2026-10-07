package com.gamma.la.core;

import com.gamma.control.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationTimeRecurringTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sealed(Object... exclude) {
        return (List<Map<String, Object>>) InvestigationTime.window(Map.of("exclude", List.of(exclude), "timezone", "UTC"), "w").get("exclude");
    }

    @Test
    void aWindowWithoutRulesSealsExactlyAsBefore() {
        assertEquals("[{from=2026-09-01, to=2026-09-01}, {from=2026-12-24, to=2026-12-26, name=Break}]",
                sealed(Map.of("from", "2026-12-24", "to", "2026-12-26", "name", "Break"), "2026-09-01").toString());
    }

    @Test
    void rulesSealAsRulesInACanonicalOrderAfterTheDates() {
        var a = sealed(Map.of("rule", "nthWeekday", "month", 11, "weekday", "THU", "nth", 4),
                Map.of("rule", "yearly", "month", 12, "day", 25, "name", "Xmas"), "2026-01-01");
        var b = sealed("2026-01-01", Map.of("rule", "yearly", "month", 12, "day", 25, "name", "Xmas"),
                Map.of("rule", "nthWeekday", "month", 11, "weekday", "THU", "nth", 4));
        assertEquals(a, b, "authoring order does not change the sealed bytes");
        assertEquals("{from=2026-01-01, to=2026-01-01}", a.get(0).toString());
        assertEquals("{rule=nthWeekday, month=11, weekday=THU, nth=4}", a.get(1).toString());
        assertEquals("{rule=yearly, month=12, day=25, name=Xmas}", a.get(2).toString());
        // Re-validating the sealed form is a fixed point (replay).
        assertEquals(a, InvestigationTime.window(Map.of("exclude", a, "timezone", "UTC"), "w").get("exclude"));
    }

    @Test
    void matchesIsTheRecurrenceOverEveryDayOfAYear() {
        var rules = sealed(Map.of("rule", "yearly", "month", 12, "day", 25),
                Map.of("rule", "nthWeekday", "month", 11, "weekday", "THU", "nth", 4));
        List<LocalDate> hit = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 1, 1); d.getYear() == 2026; d = d.plusDays(1)) {
            LocalDate day = d;
            if (rules.stream().anyMatch(r -> InvestigationTime.matches(r, day))) hit.add(d);
        }
        assertEquals(List.of(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 25)), hit);   // Thanksgiving 2026 = Nov 26
    }

    @Test
    void feb29MatchesLeapYearsOnlyAndAFifthWeekdayOnlyWhenTheMonthHasOne() {
        var leap = sealed(Map.of("rule", "yearly", "month", 2, "day", 29)).get(0);
        assertTrue(InvestigationTime.matches(leap, LocalDate.of(2028, 2, 29)));
        assertFalse(InvestigationTime.matches(leap, LocalDate.of(2027, 2, 28)));
        var fifth = sealed(Map.of("rule", "nthWeekday", "month", 2, "weekday", "MON", "nth", 5)).get(0);
        assertFalse(InvestigationTime.matches(fifth, LocalDate.of(2026, 2, 23)));   // Feb 2026 has four Mondays
        var march = sealed(Map.of("rule", "nthWeekday", "month", 3, "weekday", "MON", "nth", 5)).get(0);
        assertTrue(InvestigationTime.matches(march, LocalDate.of(2026, 3, 30)));   // March 2026 has five Mondays
    }

    @Test
    void describeNamesTheRule() {
        var w = InvestigationTime.window(Map.of("exclude", List.of(Map.of("rule", "yearly", "month", 12, "day", 25, "name", "Xmas")),
                "timezone", "UTC"), "w");
        assertTrue(InvestigationTime.describe(w).contains("Xmas (every 12-25)"), InvestigationTime.describe(w));
    }

    @Test
    void predicatesBindTheRuleWithoutInterpolation() {
        var w = InvestigationTime.window(Map.of("exclude", List.of(Map.of("rule", "nthWeekday", "month", 11, "weekday", "THU", "nth", 4)),
                "timezone", "UTC"), "w");
        StringBuilder where = new StringBuilder();
        List<String> binds = new ArrayList<>();
        InvestigationTime.predicates(w, where, binds);
        assertEquals(List.of("11", "4", "4"), binds);
        assertFalse(where.toString().contains("11"));
    }

    @Test
    void malformedRulesAreRefused() {
        for (Object bad : List.of(Map.of("rule", "daily"), Map.of("rule", "yearly", "month", 4, "day", 31),
                Map.of("rule", "yearly", "month", 4), Map.of("rule", "nthWeekday", "month", 4, "weekday", "XXX", "nth", 1),
                Map.of("rule", "nthWeekday", "month", 4, "weekday", "MON", "nth", 0),
                Map.of("rule", "yearly", "month", 4, "day", 1, "weekday", "MON")))
            assertThrows(ApiException.class, () -> sealed(bad), bad.toString());
        List<Object> many = new ArrayList<>();
        for (int i = 0; i <= InvestigationTime.MAX_RULES; i++) many.add(Map.of("rule", "yearly", "month", 1, "day", 1, "name", "n" + i));
        assertThrows(ApiException.class, () -> sealed(many.toArray()));
    }
}
