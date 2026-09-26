package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `ASSURE-KPI-DEFINITIONS-1` — a KPI evaluated through the real DuckDB sandbox: the window filters on the time field
 * select exactly the rows of each period (per grain, per comparison), over both a DATE and a TIMESTAMP column, and
 * the delta / band come out of the numbers that ran.
 */
class KpiEvaluatorTest {

    /** Orders on a DATE and a TIMESTAMP column, spread across 2025 and 2026. */
    private static final String RELATION = """
            SELECT * FROM (VALUES
              (DATE '2025-08-05', TIMESTAMP '2025-08-05 10:00:00', 7.0),
              (DATE '2026-07-02', TIMESTAMP '2026-07-02 00:00:00', 10.0),
              (DATE '2026-07-20', TIMESTAMP '2026-07-20 23:59:59', 50.0),
              (DATE '2026-08-01', TIMESTAMP '2026-08-01 00:00:00', 20.0),
              (DATE '2026-08-10', TIMESTAMP '2026-08-10 12:00:00', 30.0),
              (DATE '2026-08-13', TIMESTAMP '2026-08-13 23:59:59', 5.0),
              (DATE '2026-08-14', TIMESTAMP '2026-08-14 00:00:00', 1000.0)
            ) AS t(order_date, order_ts, amount)""";

    private static final LocalDate AS_OF = LocalDate.parse("2026-08-13");

    private static KpiEvaluator.Result eval(Map<String, Object> overrides) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("dataset", "orders", "measure", "sum(amount)",
                "timeField", "order_date", "grain", "month"));
        m.putAll(overrides);
        return KpiEvaluator.evaluate(KpiDefinition.fromMap("k", m), RELATION, AS_OF);
    }

    @Test
    void monthToDateAgainstTheSameLengthOfThePreviousMonth() throws Exception {
        KpiEvaluator.Result r = eval(Map.of("target", 60, "bands", Map.of("green", 60, "amber", 50)));
        // Aug 1–13: 20 + 30 + 5 (the 14th is after asOf); Jul 1–13: 10 (the 20th is past the same elapsed length)
        assertEquals(55.0, r.value());
        assertEquals(10.0, r.comparisonValue());
        assertEquals(45.0, r.delta());
        assertEquals(450.0, r.deltaPct(), 1e-9);
        assertEquals(KpiDefinition.Rag.AMBER, r.rag());
        assertEquals("warning", r.tone());
    }

    @Test
    void aTimestampColumnIsCutOnTheSameDayBoundaries() throws Exception {
        KpiEvaluator.Result r = eval(Map.of("timeField", "order_ts"));
        assertEquals(55.0, r.value(), "23:59:59 on asOf is in; 00:00 the next day is out");
        assertEquals(10.0, r.comparisonValue());
    }

    @Test
    void eachGrainSelectsItsOwnPeriod() throws Exception {
        assertEquals(5.0, eval(Map.of("grain", "day")).value());
        assertEquals(35.0, eval(Map.of("grain", "week")).value(), "Mon 10 Aug to date");
        assertEquals(115.0, eval(Map.of("grain", "quarter")).value(), "1 Jul to date");
        assertEquals(115.0, eval(Map.of("grain", "year")).value());
        assertEquals(7.0, eval(Map.of("grain", "year", "comparison", "last-year")).comparisonValue());
    }

    @Test
    void lastYearAndNoneComparisons() throws Exception {
        KpiEvaluator.Result ly = eval(Map.of("comparison", "last-year"));
        assertEquals(7.0, ly.comparisonValue(), "Aug 1–13 2025");
        KpiEvaluator.Result none = eval(Map.of("comparison", "none"));
        assertNull(none.comparisonWindow());
        assertNull(none.comparisonValue());
        assertNull(none.delta());
        assertNull(none.deltaPct());
    }

    @Test
    void anEmptyPeriodHasNoValueAndNoStatus() throws Exception {
        KpiEvaluator.Result r = eval(Map.of("grain", "day", "comparison", "previous", "target", 1));
        assertNull(r.comparisonValue(), "12 Aug holds no order — SUM over nothing is NULL, not 0");
        assertNull(r.delta());
        KpiEvaluator.Result count = eval(Map.of("measure", "count", "grain", "day"));
        assertEquals(1.0, count.value());
        assertEquals(0.0, count.comparisonValue(), "COUNT over nothing is 0");
    }

    @Test
    void theCompiledStatementIsTheMeasureCompilersWithTypedDateLiterals() {
        KpiDefinition k = KpiDefinition.fromMap("k", Map.of("dataset", "orders", "measure", "sum(amount)",
                "timeField", "order_date", "grain", "month"));
        String sql = KpiEvaluator.sql(k, k.current(AS_OF));
        assertEquals("SELECT SUM(\"amount\") AS \"sum_amount\" FROM \"orders\" WHERE \"order_date\" >= '2026-08-01'"
                + " AND \"order_date\" < '2026-08-14' LIMIT 1", sql);
        assertTrue(com.gamma.sql.SqlGuard.check(sql).isEmpty());
    }

    @Test
    void aColumnTheRelationLacksFailsInDuckDbRatherThanReadingZero() {
        assertThrows(java.sql.SQLException.class, () -> eval(Map.of("measure", "sum(missing)")));
    }
}
