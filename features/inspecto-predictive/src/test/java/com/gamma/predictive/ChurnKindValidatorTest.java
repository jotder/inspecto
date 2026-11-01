package com.gamma.predictive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code churn-model} save gates that need the Space, and the reserved {@code churn_scores_} prefix. Each refused
 * model differs from the storable corpus model ({@link #theCorpusModelIsStorable}, the probe) by one key.
 */
class ChurnKindValidatorTest {

    private final ChurnKindValidator v = new ChurnKindValidator();

    private static void refused(Runnable r, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::run);
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(ChurnCorpus.model());
        m.put(key, value);
        return m;
    }

    private static Map<String, Object> withInput(int i, String measure) {
        Map<String, Object> m = new LinkedHashMap<>(ChurnCorpus.model());
        List<Object> inputs = new ArrayList<>((List<?>) m.get("inputs"));
        @SuppressWarnings("unchecked")
        Map<String, Object> in = new LinkedHashMap<>((Map<String, Object>) inputs.get(i));
        in.put("measure", measure);
        inputs.set(i, in);
        m.put("inputs", inputs);
        return m;
    }

    @Test
    void theCorpusModelIsStorable(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ChurnCorpus.plant(cfg, data);
        v.validateInSpace(cfg, () -> data, ChurnCorpus.MODEL, ChurnCorpus.model());
    }

    @Test
    void aColumnMissingFromTheSchemaIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ChurnCorpus.plant(cfg, data);
        refused(() -> v.validateInSpace(cfg, () -> data, "churn", withInput(2, "sum(revenue)")),
                "column(s) [revenue] are not in the Schema of dataset 'orders'");
    }

    @Test
    void wrongColumnTypesAreRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ChurnCorpus.plant(cfg, data);
        refused(() -> v.validateInSpace(cfg, () -> data, "churn", with("time", "amount")),
                "churn-model time column 'amount' of dataset 'orders' must be a DATE or TIMESTAMP, is DECIMAL(4,1)");
        refused(() -> v.validateInSpace(cfg, () -> data, "churn", withInput(0, "recency(amount)")),
                "churn-model input 'quiet' column 'amount' of dataset 'orders' must be a DATE or TIMESTAMP");
        refused(() -> v.validateInSpace(cfg, () -> data, "churn", withInput(2, "sum(customer_id)")),
                "input 'spend' reads sum(customer_id), which must be a numeric column of dataset 'orders', is VARCHAR");
    }

    @Test
    void anUnknownDatasetIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ChurnCorpus.plant(cfg, data);
        refused(() -> v.validateInSpace(cfg, () -> data, "churn", with("dataset", "ghost")),
                "cannot be checked against the Schema of dataset 'ghost'");
    }

    @Test
    void anOutputNameThatExistsAndIsNotOursIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ChurnCorpus.plant(cfg, data);
        Files.createDirectories(data.resolve("churn_scores_churn_latest"));
        refused(() -> v.validateInSpace(cfg, () -> data, "churn", ChurnCorpus.model()), "not this model's output");
    }

    @Test
    void aDatasetOrSinkUnderTheReservedPrefixIsRefusedUnlessItReadsASavedModelsOwnStore(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        ChurnCorpus.plant(cfg, data);
        refused(() -> v.requireNotReserved(cfg, "dataset", "mine", Map.of("physicalRef", "churn_scores_churn")),
                "reserved prefix 'churn_scores_'");
        refused(() -> v.requireNotReserved(cfg, "sink", "out", Map.of("output_store", "churn_scores_x")),
                "reserved prefix 'churn_scores_'");
        refused(() -> v.requireNotReserved(cfg, "dataset", "churn_scores_ghost_latest",
                Map.of("physicalRef", "churn_scores_ghost_latest")), "over a saved model is allowed");
        v.requireNotReserved(cfg, "dataset", "churn_scores_churn_latest", Map.of("physicalRef", "churn_scores_churn_latest"));
        v.requireNotReserved(cfg, "dataset", "churn_scores_churn", Map.of("physicalRef", "churn_scores_churn"));
        v.requireNotReserved(cfg, "dataset", "orders2", Map.of("physicalRef", "orders"));
    }
}
