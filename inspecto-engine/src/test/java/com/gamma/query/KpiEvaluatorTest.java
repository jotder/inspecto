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
    void theWindowIsCutByTypedTimestamptzLiteralsInTheKpisZone() {
        KpiDefinition k = KpiDefinition.fromMap("k", Map.of("dataset", "orders", "measure", "sum(amount)",
                "timeField", "order_date", "grain", "month", "timezone", "Asia/Kolkata"));
        String sql = KpiEvaluator.sql(k, k.current(AS_OF));
        assertEquals("WITH \"__kpi_window\" AS (SELECT * FROM \"orders\" WHERE \"order_date\" >= TIMESTAMPTZ"
                + " '2026-08-01 00:00:00+05:30' AND \"order_date\" < TIMESTAMPTZ '2026-08-14 00:00:00+05:30')"
                + " SELECT SUM(\"amount\") AS \"sum_amount\" FROM \"__kpi_window\" LIMIT 1", sql);
        assertTrue(com.gamma.sql.SqlGuard.check(sql).isEmpty(), () -> com.gamma.sql.SqlGuard.check(sql).toString());
    }

    /** One order at 2026-02-28 20:00Z — 1 March 01:30 in Kolkata, still 28 February in UTC. */
    private static final String TZ_RELATION = """
            SELECT * FROM (VALUES (TIMESTAMPTZ '2026-02-28 20:00:00+00', 5.0)) AS t(order_ts, amount)""";

    private static Double dayValue(String zone, String asOf) throws Exception {
        return KpiEvaluator.evaluate(KpiDefinition.fromMap("k", Map.of("dataset", "orders", "measure", "sum(amount)",
                "timeField", "order_ts", "grain", "day", "timezone", zone)), TZ_RELATION, LocalDate.parse(asOf)).value();
    }

    @Test
    void aTimestamptzRowFallsInTheDayOfTheKpisZoneNotTheHosts() throws Exception {
        assertEquals(5.0, dayValue("Asia/Kolkata", "2026-03-01"));
        assertNull(dayValue("Asia/Kolkata", "2026-02-28"));
        assertEquals(5.0, dayValue("UTC", "2026-02-28"));
        assertNull(dayValue("UTC", "2026-03-01"));
    }

    @Test
    void aDateColumnIsCutOnTheZonesLocalDays() throws Exception {
        String rel = "SELECT * FROM (VALUES (DATE '2026-03-01', 5.0)) AS t(d, amount)";
        KpiDefinition k = KpiDefinition.fromMap("k", Map.of("dataset", "orders", "measure", "sum(amount)",
                "timeField", "d", "grain", "day", "timezone", "Asia/Kolkata"));
        assertEquals(5.0, KpiEvaluator.evaluate(k, rel, LocalDate.parse("2026-03-01")).value());
        assertNull(KpiEvaluator.evaluate(k, rel, LocalDate.parse("2026-02-28")).value());
    }

    @Test
    void theKpisZoneDoesNotLeakIntoALaterQuery() throws Exception {
        assertEquals(5.0, dayValue("Asia/Kolkata", "2026-03-01"));
        Object tz = QueryExecutor.run(new QueryExecutor.Request(null, null,
                "SELECT current_setting('TimeZone') AS tz", 1, 0, java.util.List.of(), java.util.List.of())).rows().get(0).get("tz");
        assertNotEquals("Asia/Kolkata", tz, "a plain run must not inherit a KPI's session TimeZone");
    }

    /** New York's 23-hour day (2026-03-08) and 25-hour day (2026-11-01): each column type is cut on local midnight. */
    @Test
    void dstDaysInNewYorkAreCutOnLocalMidnightForEveryColumnType() throws Exception {
        String rel = """
                SELECT * FROM (VALUES
                  (DATE '2026-03-08', TIMESTAMP '2026-03-08 00:30:00', TIMESTAMPTZ '2026-03-08 05:30:00+00', 1.0),
                  (DATE '2026-03-08', TIMESTAMP '2026-03-08 23:30:00', TIMESTAMPTZ '2026-03-09 03:30:00+00', 2.0),
                  (DATE '2026-03-09', TIMESTAMP '2026-03-09 00:30:00', TIMESTAMPTZ '2026-03-09 04:30:00+00', 4.0),
                  (DATE '2026-11-01', TIMESTAMP '2026-11-01 00:30:00', TIMESTAMPTZ '2026-11-01 04:30:00+00', 8.0),
                  (DATE '2026-11-01', TIMESTAMP '2026-11-01 23:30:00', TIMESTAMPTZ '2026-11-02 04:30:00+00', 16.0),
                  (DATE '2026-11-02', TIMESTAMP '2026-11-02 00:30:00', TIMESTAMPTZ '2026-11-02 05:30:00+00', 32.0)
                ) AS t(d, ts, tstz, amount)""";
        for (String col : java.util.List.of("d", "ts", "tstz")) {
            KpiDefinition k = KpiDefinition.fromMap("k", Map.of("dataset", "orders", "measure", "sum(amount)",
                    "timeField", col, "grain", "day", "comparison", "none", "timezone", "America/New_York"));
            assertEquals(3.0, KpiEvaluator.evaluate(k, rel, LocalDate.parse("2026-03-08")).value(), col + " 23-hour day");
            assertEquals(24.0, KpiEvaluator.evaluate(k, rel, LocalDate.parse("2026-11-01")).value(), col + " 25-hour day");
        }
    }

    @Test
    void anOffsetOrUnknownZoneIsRefused() {
        for (String z : java.util.List.of("+05:30", "Mars/Olympus", "UTC+1"))
            assertThrows(IllegalArgumentException.class, () -> KpiDefinition.fromMap("k", Map.of("dataset", "o",
                    "measure", "count", "timeField", "d", "grain", "day", "timezone", z)), z);
        assertEquals(java.time.ZoneId.of("UTC"), KpiDefinition.fromMap("k", Map.of("dataset", "o", "measure", "count",
                "timeField", "d", "grain", "day")).zone(), "absent ⇒ UTC");
    }

    @Test
    void aColumnTheRelationLacksFailsInDuckDbRatherThanReadingZero() {
        assertThrows(java.sql.SQLException.class, () -> eval(Map.of("measure", "sum(missing)")));
    }
}
