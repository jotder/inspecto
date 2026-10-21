package com.gamma.control;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.SharedRefResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BI-QUERY-MASKING-1 (D-M6): a Dataset over another Space's shared snapshot is masked from the classification the snapshot
 * CARRIES, judged under the consumer's own entity types; the same Dataset is raw for a caller who may reveal; a classification
 * that is not known fails closed. The negative cases are paired with a clean snapshot, which must stay untouched.
 */
class BiResultMaskingSharedRefTest {

    private static final Map<String, Object> DS = Map.of("physicalRef", "shared/finance/calls", "columns", List.of());

    /** These tests are about masking by default, which is Standard: this module's own classpath is Basic, where it is off. */
    @org.junit.jupiter.api.BeforeEach
    void standardEdition() {
        com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.INVESTIGATIONS));
    }

    @AfterEach
    void restoreEdition() {
        com.gamma.etl.EditionFeatures.overrideForTest(null);
    }

    @AfterEach
    void reset() { SharedRefResolver.install(null); }

    private static BiResultMasking over(Path root, boolean reveal) {
        return BiResultMasking.of(root, new ComponentStore(root.resolve("registry")), new ViewStore(root.resolve("views")),
                "calls_shared", DS, reveal);
    }

    private static void carried(Map<String, String> classification) {
        SharedRefResolver.install(new SharedRefResolver() {
            @Override public Optional<java.nio.file.Path> resolveSnapshot(String owner, String item) { return Optional.empty(); }
            @Override public Optional<Map<String, String>> classification(String owner, String item) {
                return "finance".equals(owner) && "calls".equals(item) ? Optional.ofNullable(classification) : Optional.empty();
            }
        });
    }

    @Test
    void aCarriedClassifiedColumnIsMaskedForAViewerAndRawForARevealer(@TempDir Path root) {
        carried(Map.of("msisdn", "MSISDN", "amount", "NONE"));
        BiResultMasking viewer = over(root, false);
        assertTrue(viewer.engaged(), "the snapshot's classification reaches the consumer's masking");
        assertTrue(String.valueOf(viewer.describe().get("basis")).contains("[msisdn]"), "only the classified column: " + viewer.describe());
        BiResultMasking revealer = over(root, true);
        assertTrue(revealer.applies() && !revealer.engaged(), "the capability, not the carry, decides raw");
    }

    @Test
    void aCleanSnapshotIsUntouched(@TempDir Path root) {
        carried(Map.of("msisdn", "NONE", "amount", "NONE"));
        assertFalse(over(root, false).applies(), "the negative control: nothing classified, nothing masked");
    }

    @Test
    void anUnknownClassificationFailsClosed(@TempDir Path root) {
        carried(null);   // a live grant, an older snapshot, or no grant
        assertTrue(over(root, false).engaged());
        carried(Map.of("msisdn", "UNKNOWN"));   // the owner could not trace its lineage
        assertTrue(over(root, false).engaged());
    }
}
