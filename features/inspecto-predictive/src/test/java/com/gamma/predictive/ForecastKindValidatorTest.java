package com.gamma.predictive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code forecast-model} save gates that need the Space, and the reserved {@code forecasts_} prefix. Each refused
 * model differs from the storable corpus model ({@link #theCorpusModelsAreStorable}, the probe) by one key.
 */
class ForecastKindValidatorTest {

    private final ForecastKindValidator v = new ForecastKindValidator();

    private static void refused(Runnable r, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::run);
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(ForecastCorpus.demand());
        m.put(key, value);
        return m;
    }

    @Test
    void theCorpusModelsAreStorable(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ForecastCorpus.plant(cfg, data);
        v.validateInSpace(cfg, () -> data, "demand", ForecastCorpus.demand());
        v.validateInSpace(cfg, () -> data, "weekly", ForecastCorpus.weekly());
        v.validateInSpace(cfg, () -> data, "counted", with("measure", "countDistinct(sku)"));
    }

    @Test
    void aColumnMissingFromTheSchemaIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ForecastCorpus.plant(cfg, data);
        refused(() -> v.validateInSpace(cfg, () -> data, "demand", with("measure", "sum(revenue)")),
                "column(s) [revenue] are not in the Schema of dataset 'sales'");
    }

    @Test
    void wrongColumnTypesAreRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ForecastCorpus.plant(cfg, data);
        refused(() -> v.validateInSpace(cfg, () -> data, "demand", with("time", "qty")),
                "forecast-model time column 'qty' of dataset 'sales' must be a DATE or TIMESTAMP, is DOUBLE");
        refused(() -> v.validateInSpace(cfg, () -> data, "demand", with("measure", "sum(sku)")),
                "measure sum(sku) must read a numeric column of dataset 'sales', is VARCHAR");
    }

    @Test
    void anUnknownDatasetIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ForecastCorpus.plant(cfg, data);
        refused(() -> v.validateInSpace(cfg, () -> data, "demand", with("dataset", "ghost")),
                "cannot be checked against the Schema of dataset 'ghost'");
    }

    @Test
    void anOutputNameThatExistsAndIsNotOursIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ForecastCorpus.plant(cfg, data);
        Files.createDirectories(data.resolve("forecasts_demand_latest"));
        refused(() -> v.validateInSpace(cfg, () -> data, "demand", ForecastCorpus.demand()), "not this model's output");
    }

    @Test
    void aDatasetOrSinkUnderTheReservedPrefixIsRefusedUnlessItReadsASavedModelsOwnStore(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ForecastCorpus.plant(cfg, data);
        refused(() -> v.requireNotReserved(cfg, "dataset", "mine", Map.of("physicalRef", "forecasts_demand")),
                "reserved prefix 'forecasts_'");
        refused(() -> v.requireNotReserved(cfg, "sink", "out", Map.of("output_store", "forecasts_x")),
                "reserved prefix 'forecasts_'");
        refused(() -> v.requireNotReserved(cfg, "dataset", "forecasts_ghost_latest",
                Map.of("physicalRef", "forecasts_ghost_latest")), "over a saved model is allowed");
        v.requireNotReserved(cfg, "dataset", "forecasts_demand_latest", Map.of("physicalRef", "forecasts_demand_latest"));
        v.requireNotReserved(cfg, "dataset", "forecasts_demand", Map.of("physicalRef", "forecasts_demand"));
        v.requireNotReserved(cfg, "dataset", "sales2", Map.of("physicalRef", "sales"));
    }
}
