package com.gamma.geolink;

import com.gamma.la.core.InvestigationTime;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code lastWeekday} recurring rule's SQL ({@link InvestigationTime#predicates}) must remove EXACTLY the days the Java
 * {@link InvestigationTime#matches} does, over a whole leap year and a whole common year, for every weekday.
 */
class InvestigationTimeLastWeekdaySqlTest {

    @Test
    void sqlAndJavaAgreeOnEveryDayOfALeapAndACommonYear() throws Exception {
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("lastwd_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db)) {
            for (int year : List.of(2028, 2027))
                for (String weekday : InvestigationTime.DAYS)
                    for (int month : List.of(2, 4, 5, 12)) {
                        var w = InvestigationTime.window(Map.of("exclude", List.of(Map.of("rule", "lastWeekday", "month", month, "weekday", weekday)),
                                "timezone", "UTC"), "w");
                        StringBuilder where = new StringBuilder("SELECT CAST(lt AS DATE) AS d FROM (SELECT g AS lt FROM generate_series(TIMESTAMP '"
                                + year + "-01-01', TIMESTAMP '" + year + "-12-31', INTERVAL 1 DAY) t(g)) WHERE 1=1");
                        List<String> binds = new ArrayList<>();
                        InvestigationTime.predicates(w, where, binds);
                        Set<LocalDate> kept = new TreeSet<>();
                        try (var ps = conn.prepareStatement(where.toString())) {
                            for (int i = 0; i < binds.size(); i++) ps.setString(i + 1, binds.get(i));
                            try (var rs = ps.executeQuery()) {
                                while (rs.next()) kept.add(rs.getDate(1).toLocalDate());
                            }
                        }
                        Set<LocalDate> expected = new TreeSet<>();
                        @SuppressWarnings("unchecked") Map<String, Object> rule = ((List<Map<String, Object>>) w.get("exclude")).get(0);
                        for (LocalDate d = LocalDate.of(year, 1, 1); d.getYear() == year; d = d.plusDays(1))
                            if (!InvestigationTime.matches(rule, d)) expected.add(d);
                        assertEquals(expected, kept, year + " " + weekday + " month " + month);
                        assertEquals(365 + (year == 2028 ? 1 : 0) - 1, kept.size(), "exactly one day removed: " + year + " " + weekday + " " + month);
                    }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
