package com.gamma.anomaly;

import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code anomaly-model} save gates that need the Space, and the reserved {@code anomaly_scores_} prefix. */
class AnomalyKindValidatorTest {

    private final AnomalyKindValidator v = new AnomalyKindValidator();

    private static void refused(Runnable r, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::run);
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    @Test
    void theCorpusModelIsStorable(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        AnomalyCorpus.plant(cfg, data);
        v.validateInSpace(cfg, () -> data, "usage", AnomalyCorpus.model());   // probe: the gate passes a good model
    }

    @Test
    void aColumnMissingFromTheSchemaIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        AnomalyCorpus.plant(cfg, data);
        Map<String, Object> m = new LinkedHashMap<>(AnomalyCorpus.model());
        Map<String, Object> f = new LinkedHashMap<>(((List<Map<String, Object>>) m.get("features")).get(0));
        f.put("measure", "sum(bytes)");
        m.put("features", List.of(f));
        refused(() -> v.validateInSpace(cfg, () -> data, "usage", m), "[bytes] are not in the Schema of dataset 'usage'");
    }

    @Test
    void aTimeColumnThatIsNotATimestampIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        AnomalyCorpus.plant(cfg, data);
        Map<String, Object> m = new LinkedHashMap<>(AnomalyCorpus.model());
        Map<String, Object> f = new LinkedHashMap<>(((List<Map<String, Object>>) m.get("features")).get(0));
        f.put("time", "mb");
        m.put("features", List.of(f));
        refused(() -> v.validateInSpace(cfg, () -> data, "usage", m), "must be a DATE or TIMESTAMP, is DOUBLE");
    }

    @Test
    void anUnknownDatasetIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        AnomalyCorpus.plant(cfg, data);
        Map<String, Object> m = new LinkedHashMap<>(AnomalyCorpus.model());
        Map<String, Object> f = new LinkedHashMap<>(((List<Map<String, Object>>) m.get("features")).get(0));
        f.put("dataset", "ghost");
        m.put("features", List.of(f));
        refused(() -> v.validateInSpace(cfg, () -> data, "usage", m), "cannot be checked against the Schema of dataset 'ghost'");
    }

    @Test
    void anOutputNameThatExistsAndIsNotOursIsRefused(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        AnomalyCorpus.plant(cfg, data);
        Files.createDirectories(data.resolve("anomaly_scores_usage"));
        refused(() -> v.validateInSpace(cfg, () -> data, "usage", AnomalyCorpus.model()),
                "already exists under the data root and is not this model's output");
        Files.writeString(data.resolve("anomaly_scores_usage").resolve(AnomalyModel.OWNER_MARKER), "usage");
        v.validateInSpace(cfg, () -> data, "usage", AnomalyCorpus.model());   // probe: once owned, it is ours
    }

    @Test
    void theReservedPrefixIsRefusedForDatasetsAndSinksCaseInsensitivelyOnTheFirstSegment(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config");
        Files.createDirectories(cfg.resolve("registry"));
        refused(() -> v.requireNotReserved(cfg, "dataset", "Anomaly_Scores_x", Map.of()), "reserved prefix 'anomaly_scores_'");
        refused(() -> v.requireNotReserved(cfg, "sink", "out", Map.of("output_store", "./a/../anomaly_scores_x/y")),
                "reserved prefix");
        refused(() -> v.requireNotReserved(cfg, "dataset", "anomaly_scores_usage_latest",
                Map.of("physicalRef", "anomaly_scores_usage_latest")), "over a saved model");
        v.requireNotReserved(cfg, "dataset", "usage", Map.of("physicalRef", "usage"));   // probe: an ordinary name passes
        new ComponentStore(cfg.resolve("registry")).write(AnomalyModel.KIND, "usage", AnomalyCorpus.model());
        v.requireNotReserved(cfg, "dataset", "anomaly_scores_usage_latest",
                Map.of("physicalRef", "anomaly_scores_usage_latest"));   // the documented Alert Rule Dataset
        refused(() -> v.requireNotReserved(cfg, "dataset", "anomaly_scores_usage_latest",
                Map.of("physicalRef", "anomaly_scores_usage_latest", "path", "elsewhere")), "reserved prefix");
    }
}
