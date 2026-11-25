package com.gamma.query;

import com.gamma.analytics.forecast.ForecastMethod;
import com.gamma.analytics.forecast.Forecaster;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BI-INSIGHTS-1 I-11 / I-7: {@link ChartOverlays} over a Widget's result rows — the forecast band is the kernel's
 * Holt-Winters with its backtest, and every honesty rule (two seasons, no gap, no empty bucket, no cut result, no masked
 * value) gives a reason instead of a band; the anomaly score is the kernel's seasonal robust z.
 */
class ChartOverlaysTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 5);   // a Monday

    private static MeasureCompiler.Spec spec(List<String> groupBy) {
        return new MeasureCompiler.Spec("ds", List.of(new MeasureCompiler.Measure("sum", "amount")), groupBy,
                Map.of("day", "day"), List.of(), List.of(), 1000);
    }

    private static final MeasureCompiler.Spec DAILY = spec(List.of("day"));

    /** A weekly pattern: weekdays 100 + 10·weekday, weekends 40, plus a small deterministic wobble. */
    private static double weekly(int i) {
        int dow = i % 7;
        return (dow >= 5 ? 40 : 100 + 10 * dow) + ((i * 7919) % 5) - 2;
    }

    private static QueryExecutor.Result result(List<Map<String, Object>> rows, String measureType, boolean truncated) {
        List<ResultSetDescriptor.Column> cols = new ArrayList<>(List.of(new ResultSetDescriptor.Column("day", "string", "dimension", null)));
        if (rows.stream().anyMatch(r -> r.containsKey("region")))
            cols.add(new ResultSetDescriptor.Column("region", "string", "dimension", null));
        cols.add(new ResultSetDescriptor.Column("sum_amount", measureType, "measure", null));
        return new QueryExecutor.Result(cols, rows, rows.size(), truncated, 1);
    }

    private static List<Map<String, Object>> days(int n, java.util.function.IntFunction<Double> value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("day", START.plusDays(i).toString());
            r.put("sum_amount", value.apply(i));
            rows.add(r);
        }
        return rows;
    }

    private static ChartOverlays.Request req(Object overlay, MeasureCompiler.Spec spec) {
        return ChartOverlays.parse(overlay, spec);
    }

    private static final Map<String, Object> FORECAST = Map.of("forecast", Map.of());

    @Test
    void aForecastBandIsTheKernelsHoltWintersWithItsBacktest() {
        List<Map<String, Object>> rows = days(42, i -> weekly(i));
        ChartOverlays.Outcome o = ChartOverlays.apply(req(FORECAST, DAILY), DAILY, result(rows, "number", false));

        Map<String, Object> s = o.summary().get(0);
        assertNull(s.get("reason"), s.toString());
        assertEquals(7, s.get("period"));
        assertEquals("detected", s.get("periodSource"), "six weeks of a weekly pattern: the detector finds 7");
        assertEquals(7, s.get("horizon"));
        assertEquals(0.8, s.get("level"));

        List<Map<String, Object>> out = o.result().rows();
        assertEquals(42 + 7, out.size());
        assertEquals(START.plusDays(42).toString(), out.get(42).get("day"), "the first forecast bucket follows the last");
        assertNull(out.get(42).get("sum_amount"), "a forecast row has no actual");
        assertNull(out.get(41).get("sum_amount__fc"), "history rows carry no forecast");

        // The same numbers as the kernel called directly.
        double[] y = new double[42];
        for (int i = 0; i < 42; i++) y[i] = weekly(i);
        Forecaster.Result fit = Forecaster.fit(y, new Forecaster.Spec(ForecastMethod.HOLT_WINTERS, 7, Forecaster.DEFAULT_ALPHA,
                Forecaster.DEFAULT_BETA, Forecaster.DEFAULT_GAMMA, 7, List.of(0.8)));
        for (int h = 0; h < 7; h++) {
            Map<String, Object> r = out.get(42 + h);
            assertEquals(fit.ahead()[h], (double) r.get("sum_amount__fc"), 1e-12);
            assertEquals(fit.lowerAhead(0.8)[h], (double) r.get("sum_amount__fc_lo"), 1e-12);
            assertEquals(fit.upperAhead(0.8)[h], (double) r.get("sum_amount__fc_hi"), 1e-12);
            assertTrue((double) r.get("sum_amount__fc_lo") < (double) r.get("sum_amount__fc_hi"));
        }
        assertTrue((double) out.get(42 + 5).get("sum_amount__fc") < 60, "the forecast keeps the weekend dip");

        @SuppressWarnings("unchecked") Map<String, Object> bt = (Map<String, Object>) s.get("backtest");
        assertEquals(42 - 14 - 7 + 1, bt.get("origins"), "every origin from two seasons on whose horizon is observed");
        assertEquals(7, bt.get("horizon"));
        assertTrue((double) bt.get("mape") < 10, bt.toString());
        assertNotNull(bt.get("coverage"));

        ResultSetDescriptor.Column fc = o.result().columns().get(2);
        assertEquals("sum_amount__fc", fc.name());
        assertEquals(new ResultSetDescriptor.Overlay("forecast", "holt_winters", "sum_amount", "point"), fc.overlay());
    }

    @Test
    void fewerThanTwoSeasonsIsNoForecastNeverAGuessedBand() {
        List<Map<String, Object>> rows = days(13, i -> weekly(i));
        ChartOverlays.Outcome o = ChartOverlays.apply(req(FORECAST, DAILY), DAILY, result(rows, "number", false));
        assertTrue(String.valueOf(o.summary().get(0).get("reason")).contains("two seasons"), o.summary().toString());
        assertEquals(13, o.result().rows().size(), "no forecast rows");
        assertTrue(o.result().rows().stream().allMatch(r -> r.get("sum_amount__fc") == null));

        // falsified: one more bucket makes two seasons, and the band appears
        ChartOverlays.Outcome two = ChartOverlays.apply(req(FORECAST, DAILY), DAILY, result(days(14, i -> weekly(i)), "number", false));
        assertNull(two.summary().get(0).get("reason"), two.summary().toString());
        assertEquals(21, two.result().rows().size());
        @SuppressWarnings("unchecked") Map<String, Object> bt = (Map<String, Object>) two.summary().get(0).get("backtest");
        assertNotNull(bt.get("reason"), "exactly two seasons leave nothing to backtest against: " + bt);
    }

    @Test
    void aMissingBucketOrAnEmptyValueIsNeverReadAsZero() {
        List<Map<String, Object>> gap = days(42, i -> weekly(i));
        gap.remove(20);
        ChartOverlays.Outcome g = ChartOverlays.apply(req(FORECAST, DAILY), DAILY, result(gap, "number", false));
        assertTrue(String.valueOf(g.summary().get(0).get("reason")).contains("gap"), g.summary().toString());
        assertEquals(41, g.result().rows().size());

        ChartOverlays.Outcome e = ChartOverlays.apply(req(FORECAST, DAILY), DAILY,
                result(days(42, i -> i == 20 ? null : weekly(i)), "number", false));
        assertTrue(String.valueOf(e.summary().get(0).get("reason")).contains("no value"), e.summary().toString());
        assertEquals(42, e.result().rows().size());
    }

    @Test
    void aCutResultOrAMaskedMeasureGetsNoOverlay() {
        ChartOverlays.Request both = req(Map.of("forecast", Map.of(), "anomaly", Map.of()), DAILY);
        ChartOverlays.Outcome cut = ChartOverlays.apply(both, DAILY, result(days(42, i -> weekly(i)), "number", true));
        assertEquals(2, cut.summary().size());
        cut.summary().forEach(s -> assertTrue(String.valueOf(s.get("reason")).contains("cut"), s.toString()));
        assertTrue(cut.result().rows().stream().allMatch(r -> r.get("sum_amount__fc") == null && r.get("sum_amount__z") == null));

        // a masked Measure comes back as text: no number reaches the overlay
        List<Map<String, Object>> masked = days(42, i -> null);
        for (Map<String, Object> r : masked) r.put("sum_amount", "MSISDN-ab12");
        ChartOverlays.Outcome m = ChartOverlays.apply(both, DAILY, result(masked, "string", false));
        m.summary().forEach(s -> assertTrue(String.valueOf(s.get("reason")).contains("not a number"), s.toString()));
        assertEquals(42, m.result().rows().size());
    }

    @Test
    void anAnomalyIsTheRobustZAgainstTheWeekdayBaseline() {
        // nine weeks: eight other Wednesdays, past the baseline's minimum of 7 (six weeks would fall back to every day)
        List<Map<String, Object>> rows = days(63, i -> i == 30 ? 400.0 : weekly(i));   // day 30 is a Wednesday spike
        ChartOverlays.Outcome o = ChartOverlays.apply(req(Map.of("anomaly", Map.of()), DAILY), DAILY, result(rows, "number", false));
        Map<String, Object> s = o.summary().get(0);
        assertEquals("weekday", s.get("seasonality"), s.toString());
        assertEquals(63, s.get("scored"));

        Map<String, Object> spike = o.result().rows().get(30);
        assertTrue((double) spike.get("sum_amount__z") > 8, spike.toString());
        assertEquals(weekly(2), (double) spike.get("sum_amount__expected"), 2.5, "Wednesdays' median, not the whole week's");
        // A Saturday (40) is ordinary against Saturdays — against all days it would look like a dip.
        assertTrue(Math.abs((double) o.result().rows().get(33).get("sum_amount__z")) < 3, o.result().rows().get(33).toString());
        assertEquals(63, o.result().rows().size(), "anomaly adds no rows");
        ResultSetDescriptor.Column z = o.result().columns().get(o.result().columns().size() - 1);
        assertEquals(new ResultSetDescriptor.Overlay("anomaly", "robust_z", "sum_amount", "z"), z.overlay());
    }

    @Test
    void tooLittleHistoryScoresNothing() {
        ChartOverlays.Outcome o = ChartOverlays.apply(req(Map.of("anomaly", Map.of()), DAILY), DAILY,
                result(days(6, i -> weekly(i)), "number", false));
        assertEquals(0, o.summary().get(0).get("scored"));
        assertNotNull(o.summary().get(0).get("reason"));
        assertTrue(o.result().rows().stream().allMatch(r -> r.get("sum_amount__z") == null));
    }

    @Test
    void eachSeriesIsForecastOnItsOwn() {
        MeasureCompiler.Spec bySeries = spec(List.of("day", "region"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 28; i++)
            for (String region : List.of("north", "south")) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("day", START.plusDays(i).toString());
                r.put("region", region);
                r.put("sum_amount", "north".equals(region) ? weekly(i) : 1000 + weekly(i));
                rows.add(r);
            }
        ChartOverlays.Outcome o = ChartOverlays.apply(req(FORECAST, bySeries), bySeries, result(rows, "number", false));
        assertEquals(2, o.summary().size());
        assertEquals(Map.of("region", "south"), o.summary().get(1).get("series"));
        List<Map<String, Object>> future = o.result().rows().subList(56, o.result().rows().size());
        assertEquals(14, future.size());
        assertEquals("north", future.get(0).get("region"));
        assertEquals("south", future.get(1).get("region"));
        assertEquals(future.get(0).get("day"), future.get(1).get("day"), "every series at each step");
        assertTrue((double) future.get(1).get("sum_amount__fc") > 900);
    }

    @Test
    void aMonthGrainFallsBackToTheCalendarYear() {
        MeasureCompiler.Spec monthly = new MeasureCompiler.Spec("ds", List.of(new MeasureCompiler.Measure("sum", "amount")),
                List.of("day"), Map.of("day", "month"), List.of(), List.of(), 1000);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("day", LocalDate.of(2024, 1, 1).plusMonths(i).toString().substring(0, 7));
            r.put("sum_amount", 100.0 + i + (i % 12 == 11 ? 50 : 0));
            rows.add(r);
        }
        ChartOverlays.Outcome o = ChartOverlays.apply(req(FORECAST, monthly), monthly, result(rows, "number", false));
        Map<String, Object> s = o.summary().get(0);
        assertEquals(12, s.get("period"));
        assertEquals("calendar", s.get("periodSource"), "two years cannot show three cycles: the calendar year is used");
        assertEquals("2026-01", o.result().rows().get(24).get("day"));
    }

    @Test
    void theRequestIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> req(Map.of("soon", Map.of()), DAILY));
        assertThrows(IllegalArgumentException.class, () -> req(Map.of(), DAILY));
        assertThrows(IllegalArgumentException.class, () -> req(Map.of("forecast", Map.of("level", 0.2)), DAILY));
        assertThrows(IllegalArgumentException.class, () -> req(Map.of("forecast", Map.of("horizon", 61)), DAILY));
        assertThrows(IllegalArgumentException.class, () -> req(Map.of("anomaly", Map.of("threshold", 3)), DAILY));
        MeasureCompiler.Spec noGrain = new MeasureCompiler.Spec("ds", List.of(new MeasureCompiler.Measure("count", null)),
                List.of("region"), Map.of(), List.of(), List.of(), 10);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> req(FORECAST, noGrain));
        assertTrue(e.getMessage().contains("time dimension"), e.getMessage());
        MeasureCompiler.Spec clash = new MeasureCompiler.Spec("ds", List.of(new MeasureCompiler.Measure("sum", "amount")),
                List.of("day", "sum_amount__z"), Map.of("day", "day"), List.of(), List.of(), 10);
        assertThrows(IllegalArgumentException.class, () -> req(Map.of("anomaly", Map.of()), clash));
        assertEquals(new ChartOverlays.Request("day", new ChartOverlays.Forecast(12, 0.95), true),
                req(Map.of("forecast", Map.of("horizon", 12, "level", 0.95), "anomaly", Map.of()), DAILY));
    }
}
