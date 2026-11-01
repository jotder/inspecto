package com.gamma.predictive;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code forecast-model} structural gates. Each refused model differs from an accepted corpus model by one key,
 * and the boundaries (horizon 90, maxSeries 1 000 000, history two seasons) are accepted exactly at the limit.
 */
class ForecastModelTest {

    private static void refused(Map<String, Object> m, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ForecastModel.fromMap("f", m));
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        if (value == null) m.remove(key); else m.put(key, value);
        return m;
    }

    @Test
    void theCorpusModelsParseWithTheirDefaults() {
        ForecastModel hw = ForecastModel.fromMap("demand", ForecastCorpus.demand());
        assertEquals(ForecastModel.Method.HOLT_WINTERS, hw.method());
        assertEquals(7, hw.season());
        assertEquals(14, hw.minHistory());
        assertEquals(95, hw.interval(), "default interval");
        assertEquals("forecasts_demand", hw.forecastsDataset());
        assertEquals("forecasts_demand_latest", hw.latestDataset());
        assertNull(hw.maxSeries(), "absent = the system default at run time");
        ForecastModel weekly = ForecastModel.fromMap("weekly", ForecastCorpus.weekly());
        assertEquals(52, weekly.season());
        assertEquals(104, weekly.minHistory());
        assertEquals(156, ForecastModel.fromMap("w", with(ForecastCorpus.weekly(), "history", null)).history(),
                "default week history");
        assertEquals(364, ForecastModel.fromMap("d", with(ForecastCorpus.naive(), "history", null)).history(),
                "default day history");
    }

    @Test
    void theHorizonIsOneToNinetyBuckets() {
        refused(with(ForecastCorpus.naive(), "horizon", 91), "forecast-model.horizon must be a whole number in 1..90");
        refused(with(ForecastCorpus.naive(), "horizon", 0), "horizon must be a whole number in 1..90");
        refused(with(ForecastCorpus.naive(), "horizon", null), "forecast-model.horizon is required");
        assertEquals(90, ForecastModel.fromMap("f", with(ForecastCorpus.naive(), "horizon", 90)).horizon());
    }

    @Test
    void maxSeriesIsCappedAtTenTimesTheDefault() {
        refused(with(ForecastCorpus.naive(), "maxSeries", 1_000_001), "maxSeries must be a whole number in 1..1000000");
        refused(with(ForecastCorpus.naive(), "maxSeries", 0), "maxSeries must be a whole number in 1..1000000");
        assertEquals(1_000_000, ForecastModel.fromMap("f", with(ForecastCorpus.naive(), "maxSeries", 1_000_000)).maxSeries());
    }

    @Test
    void historyMustHoldTwoSeasonsAndStayWithinTheGrainsLimit() {
        refused(with(ForecastCorpus.naive(), "history", 13), "history must be a whole number in 14..1095");
        refused(with(ForecastCorpus.naive(), "history", 1096), "history must be a whole number in 14..1095");
        refused(with(ForecastCorpus.weekly(), "history", 103), "history must be a whole number in 104..520");
        refused(with(ForecastCorpus.weekly(), "history", 521), "history must be a whole number in 104..520");
        assertEquals(14, ForecastModel.fromMap("f", with(ForecastCorpus.naive(), "history", 14)).history());
    }

    @Test
    void smoothingParametersAreRequiredByHoltWintersAndRefusedOnSeasonalNaive() {
        refused(with(ForecastCorpus.demand(), "gamma", null), "forecast-model.gamma is required by method holt-winters");
        refused(with(ForecastCorpus.demand(), "alpha", 1.5), "forecast-model.alpha must be in 0..1");
        refused(with(ForecastCorpus.demand(), "beta", "fast"), "forecast-model.beta must be a number");
        refused(with(ForecastCorpus.naive(), "alpha", 0.5), "forecast-model.alpha is refused on method seasonal-naive");
    }

    @Test
    void grainMethodMeasureAndIntervalAreClosedLists() {
        refused(with(ForecastCorpus.naive(), "grain", "month"), "grain must be day or week");
        refused(with(ForecastCorpus.naive(), "method", "arima"), "method must be seasonal-naive or holt-winters");
        refused(with(ForecastCorpus.naive(), "measure", "avg(qty)"), "measure must be count, countDistinct(field) or sum(field)");
        refused(with(ForecastCorpus.naive(), "interval", 85), "interval must be one of [80, 90, 95, 99]");
        refused(with(ForecastCorpus.naive(), "seasonality", 12), "unknown key(s) [seasonality]");
        refused(with(ForecastCorpus.naive(), "key", "sku; DROP"), "forecast-model.key 'sku; DROP' must match");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ForecastModel.fromMap("bad-id", ForecastCorpus.naive()));
        assertTrue(e.getMessage().contains("names the derived output Datasets"), e.getMessage());
    }
}
