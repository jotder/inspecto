package com.gamma.predictive;

import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code segmentation-model} save gates that need the Space, and the reserved {@code customer_segments_} prefix. */
class SegmentationKindValidatorTest {

    private final SegmentationKindValidator v = new SegmentationKindValidator();

    private static void refused(Runnable r, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::run);
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(RfmCorpus.model());
        m.put(key, value);
        return m;
    }

    @Test
    void theCorpusModelIsStorable(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        RfmCorpus.plant(cfg, data);
        v.validateInSpace(cfg, () -> data, "rfm", RfmCorpus.model());   // probe: the gate passes a good model
    }

    @Test
    void aColumnMissingFromTheSchemaIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        RfmCorpus.plant(cfg, data);
        refused(() -> v.validateInSpace(cfg, () -> data, "rfm", with("monetary", "sum(revenue)")),
                "column(s) [revenue] are not in the Schema of dataset 'orders'");
        refused(() -> v.validateInSpace(cfg, () -> data, "rfm", with("key", "account_id")),
                "column(s) [account_id] are not in the Schema of dataset 'orders'");
    }

    @Test
    void aTimeColumnThatIsNotATimestampIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        RfmCorpus.plant(cfg, data);
        refused(() -> v.validateInSpace(cfg, () -> data, "rfm", with("time", "amount")),
                "segmentation-model.time column 'amount' of dataset 'orders' must be a DATE or TIMESTAMP, is DOUBLE");
    }

    @Test
    void anUnknownDatasetIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        RfmCorpus.plant(cfg, data);
        refused(() -> v.validateInSpace(cfg, () -> data, "rfm", with("dataset", "ghost")),
                "cannot be checked against the Schema of dataset 'ghost'");
    }

    @Test
    void anOutputNameThatExistsAndIsNotOursIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        RfmCorpus.plant(cfg, data);
        Files.createDirectories(data.resolve("customer_segments_rfm"));
        refused(() -> v.validateInSpace(cfg, () -> data, "rfm", RfmCorpus.model()), "not this model's output");
    }

    @Test
    void aDatasetOrSinkUnderTheReservedPrefixIsRefusedUnlessItReadsASavedModelsOwnStore(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        RfmCorpus.plant(cfg, data);
        refused(() -> v.requireNotReserved(cfg, "dataset", "mine", Map.of("physicalRef", "customer_segments_rfm")),
                "reserved prefix 'customer_segments_'");
        refused(() -> v.requireNotReserved(cfg, "sink", "out", Map.of("output_store", "customer_segments_x")),
                "reserved prefix 'customer_segments_'");
        refused(() -> v.requireNotReserved(cfg, "dataset", "customer_segments_ghost_latest",
                Map.of("physicalRef", "customer_segments_ghost_latest")), "over a saved model is allowed");
        v.requireNotReserved(cfg, "dataset", "customer_segments_rfm_latest",
                Map.of("physicalRef", "customer_segments_rfm_latest"));
        v.requireNotReserved(cfg, "dataset", "customer_segments_rfm", Map.of("physicalRef", "customer_segments_rfm"));
        v.requireNotReserved(cfg, "dataset", "orders2", Map.of("physicalRef", "orders"));
        assertTrue(new ComponentStore(cfg.resolve("registry")).exists(SegmentationModel.KIND, "rfm"));
    }
}
