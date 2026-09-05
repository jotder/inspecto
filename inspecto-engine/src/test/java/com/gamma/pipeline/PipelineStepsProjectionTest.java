package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan slice A2's real gate: <b>{@link PipelineConfig#steps()} must agree with what {@link PipelineLift}
 * actually builds</b>, for every combination of the legacy singular blocks.
 *
 * <p>⚠ <b>Why this test and not a constant.</b> Before {@code steps:} existed, the order of a pipeline's
 * transform chain was not in the file at all — {@code PipelineLift.branch} wires a hard-coded
 * {@code filter → join → dedup → summarize → route}, and with at most one node per kind a constant order
 * is indistinguishable from a stored one. The projection now has to reproduce that hard-coded order from
 * the outside. If it drifts by one position, every existing config silently reorders on its next save —
 * which is the data loss this plan exists to remove, reintroduced by the fix for it.
 *
 * <p>So the expectation is not written down twice. It is <b>read off the lift</b>, which is the only thing
 * that knows the truth today. A future reordering of either side fails here rather than in production.
 *
 * <p>⚠ This lives in {@code inspecto-engine} because it is the module that can see both {@code PipelineConfig}
 * (inspecto-etl) and {@code PipelineLift} — the dependency runs one way only.
 */
class PipelineStepsProjectionTest {

    private static final Map<String, Object> FILTER    = Map.of("where", "duration > 0");
    private static final Map<String, Object> JOIN      = Map.of("reference", "sites", "on", List.of("cell_id"));
    private static final Map<String, Object> DEDUP     = Map.of("keys", List.of("msisdn"));
    private static final Map<String, Object> SUMMARIZE = Map.of("group_by", List.of("day"),
                                                                "measures", List.of("count"));
    private static final Map<String, Object> ROUTE     = Map.of("on", "table");

    /**
     * A config carrying exactly the blocks named in {@code present}. {@code processing.csv_settings.where}
     * is how a filter is spelled today — it is a field inside the parse-settings block, not a block of its
     * own, which is why it needs its own case here.
     */
    private static PipelineConfig configWith(String... present) throws Exception {
        List<String> want = List.of(present);
        Map<String, Object> processing = new LinkedHashMap<>();
        processing.put("threads", 1);
        // Deliberately inserted in an order that is NOT the chain order, so a projection that merely
        // echoes declaration order cannot pass.
        if (want.contains("summarize")) processing.put("summarize", SUMMARIZE);
        if (want.contains("dedup"))     processing.put("dedup", DEDUP);
        if (want.contains("join"))      processing.put("join", JOIN);
        if (want.contains("filter"))    processing.put("csv_settings", FILTER);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "PROJ_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", processing);
        if (want.contains("route")) m.put("route", ROUTE);
        return PipelineConfig.fromMap(m);
    }

    /** The transform chain the lift actually builds, as step kinds — the {@code transform.} prefix dropped. */
    private static List<String> liftedChain(PipelineConfig cfg) {
        return PipelineLift.lift(cfg).nodes().stream()
                // the projection slot (a transform.sql since 2026-09-05) is not an authorable step
                .filter(n -> !PipelineEditable.isProjectionSlot(n))
                .map(PipelineNode::type)
                .filter(t -> t.startsWith("transform."))
                .map(t -> t.substring("transform.".length()))
                .filter(PipelineConfig.Step.KINDS::contains)
                .toList();
    }

    private static List<String> projectedChain(PipelineConfig cfg) {
        return cfg.steps().stream().map(PipelineConfig.Step::kind).toList();
    }

    /**
     * The property, over every subset that matters: what {@code steps()} projects is what the lift wires.
     *
     * <p>Enumerated rather than exhaustive over all 32 subsets on purpose — the full power set adds
     * combinations that no config can hold (a `route:` with no schema selector lifts differently) without
     * adding a distinct ordering question. These are the ones where an order can be wrong.
     */
    @Test
    void everyLegacyCombinationProjectsTheOrderTheLiftBuilds() throws Exception {
        List<String[]> cases = List.of(
                new String[]{"dedup"},
                new String[]{"filter"},
                new String[]{"join"},
                new String[]{"summarize"},
                new String[]{"dedup", "summarize"},
                new String[]{"join", "dedup"},
                new String[]{"filter", "dedup"},
                new String[]{"filter", "join", "dedup", "summarize"});

        for (String[] present : cases) {
            PipelineConfig cfg = configWith(present);
            assertEquals(liftedChain(cfg), projectedChain(cfg),
                    "steps() must match the lift's chain for " + List.of(present));
            assertFalse(projectedChain(cfg).isEmpty(), "the case should carry a chain: " + List.of(present));
        }
    }

    /**
     * The full chain, spelled out once. The loop above proves agreement; this pins <em>which</em> order
     * they agree on, so a change that reorders BOTH sides in step still trips a test.
     */
    @Test
    void theFullChainIsFilterJoinDedupSummarizeRoute() throws Exception {
        PipelineConfig cfg = configWith("filter", "join", "dedup", "summarize", "route");
        assertEquals(List.of("filter", "join", "dedup", "summarize", "route"), projectedChain(cfg));
    }

    /** An explicit chain is taken as authored — the projection does not re-sort it into the lift's order. */
    @Test
    void anExplicitChainIsNotReorderedIntoTheLegacyOrder() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "PROJ_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        m.put("steps", List.of(
                Map.of("summarize", SUMMARIZE),
                Map.of("dedup", DEDUP),
                Map.of("summarize", SUMMARIZE)));

        assertEquals(List.of("summarize", "dedup", "summarize"),
                projectedChain(PipelineConfig.fromMap(m)),
                "the legacy order is a projection rule, not a normalisation applied to authored chains");
    }
}
