package com.gamma.insights;

import com.gamma.pipeline.ComponentStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code insight-profile} save gates and the reserved {@code insights_} store prefix. */
class InsightProfileValidatorTest {

    private final InsightProfileValidator v = new InsightProfileValidator();

    private static Map<String, Object> profile() {
        return Map.of("dataset", "sales", "measure", Map.of("agg", "sum", "field", "amount"), "field", "ts", "grain", "day");
    }

    @Test
    void anUnknownDatasetIsRefusedAndAKnownOneAccepted(@TempDir Path root) throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> v.validateInSpace(root, () -> null, "p", profile()));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        new ComponentStore(root.resolve("registry")).write("dataset", "sales", Map.of("physicalRef", "sales"));
        v.validateInSpace(root, () -> null, "p", profile());
        assertEquals("insight-profile", v.type());
    }

    @Test
    void onlyTheProfilesOwnInsightsDatasetMayNameTheReservedPrefix(@TempDir Path root) throws Exception {
        ComponentStore store = new ComponentStore(root.resolve("registry"));
        assertThrows(IllegalArgumentException.class, () -> v.requireNotReserved(root, "dataset", "insights_p",
                Map.of("physicalRef", "insights_p")), "no saved Profile 'p' yet");
        store.write("dataset", "sales", Map.of("physicalRef", "sales"));
        store.write(InsightProfile.KIND, "p", profile());
        v.requireNotReserved(root, "dataset", "insights_p", Map.of("physicalRef", "insights_p"));
        assertThrows(IllegalArgumentException.class, () -> v.requireNotReserved(root, "dataset", "mine",
                Map.of("physicalRef", "insights_p")), "another Dataset over the store");
        assertThrows(IllegalArgumentException.class, () -> v.requireNotReserved(root, "sink", "out",
                Map.of("output_store", "insights_p")), "a sink writing into it");
        assertThrows(IllegalArgumentException.class, () -> v.requireNotReserved(root, "dataset", "insights_p",
                Map.of("physicalRef", "insights_p", "path", "elsewhere")), "a path override");
        v.requireNotReserved(root, "dataset", "sales2", Map.of("physicalRef", "sales"));
    }

    @Test
    void aBadProfileIsRefusedAtSave() {
        assertThrows(IllegalArgumentException.class, () -> v.validate("p", Map.of("dataset", "sales")));
        assertThrows(IllegalArgumentException.class, () -> v.validate("p",
                Map.of("dataset", "sales", "measure", Map.of("agg", "median", "field", "a"), "field", "ts", "grain", "day")));
        v.validate("p", profile());
        assertEquals(List.of("anomaly", "trend-break", "drift"), InsightProfile.parse("p", profile()).types());
    }
}
