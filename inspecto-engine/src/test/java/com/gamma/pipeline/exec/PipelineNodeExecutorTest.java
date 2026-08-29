package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.pipeline.PipelineRel;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The node-type plugin seam's EXECUTION half: a contributed type must actually run.
 *
 * <p>Before this, {@link com.gamma.pipeline.PipelineNodeType} gave a contributed type a palette entry, a
 * category and validated {@code accepts}/{@code emits} — and then {@link RowShaper#shape} threw, because
 * its dispatch was a closed {@code if}-chain over the built-ins. These tests run the real dispatch over a
 * real DuckDB with a provider registered through {@code META-INF/services}, exactly as a plugin would be.
 */
class PipelineNodeExecutorTest {

    private static final List<Map<String, Object>> SAMPLE =
            List.of(Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3"));

    /** 🔴 The gap this closes: a contributed type now shapes rows instead of throwing. */
    @Test
    void aContributedNodeTypeExecutes() throws Exception {
        File db = DuckDbUtil.tempDbFile("nodeexec_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, "src", List.of("id"), SAMPLE);
            // ⚠ The two halves are INDEPENDENT registrations: this provider contributes only the executor,
            // and the type shapes rows anyway. A real plugin registers a PipelineNodeType descriptor too —
            // without one the validator will not let anyone wire its output — but a test-scope descriptor
            // is deliberately NOT registered here: the served step catalog is a COMMITTED CONTRACT
            // (StepTypesContractTest vs step-types.contract.json), so a fixture type would either fail
            // that guard or get baked into the shipped client contract.
            assertFalse(PipelineNodeTypes.isKnown(FakeNodeExecutor.TYPE), "descriptor deliberately absent");
            assertTrue(PipelineNodeExecutors.get(FakeNodeExecutor.TYPE).isPresent(), "executor registered");

            PipelineNode node = PipelineNode.of("t", FakeNodeExecutor.TYPE, Map.of("count", "2"));

            List<RowShaper.Relation> out = RowShaper.shape(conn, node, "src", "take");

            // It emits TWO relations — a contributed type is not limited to a single passthrough.
            assertEquals(List.of(PipelineRel.DATA, PipelineRel.DROPPED),
                    out.stream().map(RowShaper.Relation::rel).toList());
            for (RowShaper.Relation r : out) {
                int rows = ScratchTables.count(conn, r.table());
                assertEquals(PipelineRel.DATA.equals(r.rel()) ? 2 : 1, rows, r.rel() + " row count");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * The falsification: an unregistered type still refuses, and the message must name the SEAM. A
     * contributed type reaches the shaper having already rendered, validated and lifted, so a bare
     * "cannot shape" reads as a core bug rather than a missing provider.
     */
    @Test
    void anUnregisteredTypeRefusesAndNamesTheSeam() throws Exception {
        File db = DuckDbUtil.tempDbFile("nodeexec_none_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, "src", List.of("id"), SAMPLE);
            PipelineNode node = PipelineNode.of("x", "transform.nope", Map.of());

            Exception e = assertThrows(IllegalArgumentException.class,
                    () -> RowShaper.shape(conn, node, "src", "none"));
            assertTrue(e.getMessage().contains("PipelineNodeExecutor"), e.getMessage());
            assertTrue(e.getMessage().contains("not a registered node type"), e.getMessage());
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * ⚠ The two halves of the seam are INDEPENDENT, and the message says which one is missing. A type can
     * be a registered descriptor (palette, validator) while having no executor — that combination was the
     * whole descriptor-only gap, and it must read differently from an unknown type.
     */
    @Test
    void aDescriptorWithoutAnExecutorSaysSo() throws Exception {
        // transform.dedup.marker is a registered descriptor that RowShaper does shape, so pick a type the
        // registry knows and the chain does not: `adapter` is a real node type with no shaping branch.
        assertTrue(PipelineNodeTypes.isKnown("adapter"), "precondition: adapter is a registered descriptor");
        assertTrue(PipelineNodeExecutors.get("adapter").isEmpty(), "precondition: and has no executor");

        File db = DuckDbUtil.tempDbFile("nodeexec_desc_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, "src", List.of("id"), SAMPLE);
            Exception e = assertThrows(IllegalArgumentException.class,
                    () -> RowShaper.shape(conn, PipelineNode.of("a", "adapter", Map.of()), "src", "desc"));
            assertTrue(e.getMessage().contains("only its executor is missing"), e.getMessage());
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** A stock build contributes nothing — the seam costs one map lookup and changes no shipped behaviour. */
    @Test
    void theRegistryHoldsOnlyWhatProvidersContribute() {
        assertEquals(List.of(FakeNodeExecutor.TYPE), List.copyOf(PipelineNodeExecutors.all()),
                "only the test provider is registered; a stock build has none");
    }
}
