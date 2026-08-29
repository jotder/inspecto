package com.gamma.pipeline;

import com.gamma.pipeline.exec.FakeNodeExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 5 — a CONTRIBUTED node type is authorable, not merely executable.
 *
 * <p>Before this, {@code PipelineNodeExecutor} let a plugin type run but {@code LOWERABLE} was a closed
 * set of built-ins, so the palette greyed it out and a graph carrying one refused to save with
 * <i>"the flat pipeline config has no home for a '…' node"</i>. It has one: the chain is a LIST, so a
 * {@code steps:} entry can carry a kind the flat file has never seen, with its config verbatim.
 */
class PluginStepAuthoringTest {

    /** The test provider registers an EXECUTOR for this type; the descriptor half is asserted absent. */
    private static final String PLUGIN_TYPE = FakeNodeExecutor.TYPE;   // transform.take

    /** A CONTRIBUTED descriptor, supplied directly — see {@code stepKindOf(String, PipelineNodeType)}. */
    private static final PipelineNodeType CONTRIBUTED = new PipelineNodeType() {
        @Override public String type() { return PLUGIN_TYPE; }
        @Override public NodeCategory category() { return NodeCategory.TRANSFORM; }
    };

    private static PipelineNode node(String id, String type, Map<String, Object> cfg) {
        return new PipelineNode(id, type, null, null, cfg, null);
    }

    /**
     * 🔴 The gate that moved. A contributed type is lowerable — and therefore authorable — because the
     * `steps:` spelling can hold it.
     */
    @Test
    void aContributedTypeGetsAStepsHome() {
        assertEquals("take", PipelineEditable.stepKindOf(PLUGIN_TYPE, CONTRIBUTED),
                "the kind is the type's suffix, so it lifts back to the type the executor is under");
    }

    /**
     * 🔴 The restriction that keeps this change from reversing decisions it was not asked to. Several
     * built-ins are registered and executable and DELIBERATELY unauthorable; admitting every registered
     * transform.* would have silently made all of them authorable.
     */
    @Test
    void deliberatelyUnauthorableBuiltInsAreUntouched() {
        for (String builtIn : List.of("transform.split", "transform.select", "transform.derive",
                "transform.validate", "transform.merge")) {
            assertTrue(PipelineNodeTypes.isKnown(builtIn), builtIn + " is a registered built-in");
            assertNull(PipelineEditable.stepKindOf(builtIn), builtIn + " must gain no steps: home");
            assertFalse(PipelineEditable.isLowerable(builtIn), builtIn + " stays unlowerable");
            assertFalse(PipelineEditable.isAuthorable(builtIn), builtIn + " stays unauthorable");
        }
        // …and the five that always were chain kinds still are
        assertEquals("dedup", PipelineEditable.stepKindOf("transform.dedup"));
        assertTrue(PipelineEditable.isAuthorable("transform.dedup"));
    }

    /**
     * ⚠ …and an UNREGISTERED type still refuses. The author is present at save, which is where a graph
     * that would never run should be stopped — not at run time, and not never.
     */
    @Test
    void anUnregisteredTypeStillHasNoHome() {
        assertFalse(PipelineNodeTypes.isKnown("transform.not_a_plugin"));
        assertNull(PipelineEditable.stepKindOf("transform.not_a_plugin"));
        // an EXECUTOR alone is not enough: the palette and the validator need the descriptor half
        assertFalse(PipelineNodeTypes.isKnown(PLUGIN_TYPE), "this fixture registers no descriptor");
        assertNull(PipelineEditable.stepKindOf(PLUGIN_TYPE), "so it is not authorable either");
        assertFalse(PipelineEditable.isLowerable("transform.not_a_plugin"));
        assertFalse(PipelineEditable.isAuthorable("transform.not_a_plugin"));
        // and something that is not a transform at all is still not a chain step
        assertNull(PipelineEditable.stepKindOf("sink.persistent"));
    }

    /**
     * The round-trip property, asserted on the piece that decides it: a contributed kind is what the
     * {@code steps:} entry is keyed by, and {@code PipelineLift} rebuilds {@code transform.<kind>} from
     * exactly that — so the type the executor is registered under is the type that comes back.
     */
    @Test
    void theKindRoundTripsToTheTypeTheExecutorIsRegisteredUnder() {
        String kind = PipelineEditable.stepKindOf(PLUGIN_TYPE, CONTRIBUTED);
        assertEquals(PLUGIN_TYPE, "transform." + kind,
                "lift rebuilds transform.<kind>; if these disagree the step saves and then cannot run");
    }
}
