package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>A5-at-rest slice 1</b> — {@link PipelineLift#stageTwo}: a flat config's Stage-2 remainder lifts
 * into {@code source_store seed → ordered chain → sink.persistent(output_store)}, and every chain this
 * route would run <em>wrongly</em> refuses instead (no chain / no {@code output_store:} / route step /
 * pre-map legacy filter / pre-parse filter keys).
 *
 * <p>Lives here (not inspecto-etl) for the same reason as {@code PipelineStepsProjectionTest}: this is
 * the module that can see both {@code PipelineConfig} and {@code PipelineLift}.
 */
class PipelineStageTwoLiftTest {

    /** A minimal flat config; explicit {@code steps:} entries are single-key maps of kind → config. */
    private static PipelineConfig config(String outputStore, List<Map<String, Object>> steps) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "SHAPE_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        if (outputStore != null) m.put("output_store", outputStore);
        if (steps != null) m.put("steps", steps);
        return PipelineConfig.fromMap(m);
    }

    @Test
    void liftsSeedChainAndAuthoredSink() throws Exception {
        PipelineConfig cfg = config("orders_shaped", List.of(
                Map.of("dedup", Map.of("keys", List.of("msisdn"))),
                Map.of("summarize", Map.of("group_by", List.of("day"), "measures", List.of("count")))));
        PipelineGraph g = PipelineLift.stageTwo(cfg);

        assertEquals(cfg.identity().pipelineName() + "_stage2", g.name());
        assertEquals(List.of("acquisition", "transform.dedup", "transform.summarize", "sink.persistent"),
                g.nodes().stream().map(PipelineNode::type).toList(), "seed → chain in order → sink");
        // the seed reads the store the linear path lands (canonical-name fallback = the pipeline name)
        assertEquals(cfg.identity().pipelineName(),
                g.byId().get("src").cfg(PipelineStores.CONFIG_SOURCE_STORE));
        // the sink writes the AUTHORED store, never a derived one
        assertEquals("orders_shaped", g.byId().get("sink").cfg(PipelineStores.CONFIG_STORE));
        // linear wiring, all data edges
        assertEquals(List.of("src>dedup", "dedup>summarize", "summarize>sink"),
                g.edges().stream().map(e -> e.from() + ">" + e.to()).toList());
        assertTrue(g.edges().stream().allMatch(e -> PipelineRel.DATA.equals(e.rel())));
        // and the graph is valid as-built — the executor's gate accepts it
        assertTrue(PipelineValidator.validate(g).ok(),
                () -> PipelineValidator.validate(g).errors().toString());
    }

    @Test
    void anExplicitPostMapFilterWithAPlainWhereIsAllowed() throws Exception {
        PipelineConfig cfg = config("kept", List.of(Map.of("filter", Map.of("where", "amt > 0"))));
        PipelineGraph g = PipelineLift.stageTwo(cfg);
        assertEquals("transform.filter", g.nodes().get(1).type());
    }

    @Test
    void refusesWithoutAChainAndWithoutAnAuthoredOutputStore() throws Exception {
        // no Stage-2 chain at all
        var noChain = assertThrows(IllegalArgumentException.class,
                () -> PipelineLift.stageTwo(config("x", null)));
        assertTrue(noChain.getMessage().contains("no Stage-2 chain"), noChain.getMessage());
        // a chain but no output_store: — the name is authored, never derived (operator call 2026-08-11)
        var noStore = assertThrows(IllegalArgumentException.class, () -> PipelineLift.stageTwo(
                config(null, List.of(Map.of("dedup", Map.of("keys", List.of("k")))))));
        assertTrue(noStore.getMessage().contains("output_store"), noStore.getMessage());
    }

    @Test
    void refusesARouteStep() throws Exception {
        var e = assertThrows(IllegalArgumentException.class, () -> PipelineLift.stageTwo(
                config("x", List.of(Map.of("route", Map.of("branches",
                        List.of(Map.of("key", "a", "where", "1=1"))))))));
        assertTrue(e.getMessage().contains("route"), e.getMessage());
    }

    /** The legacy filter is pre-map (fused csv_settings) — its vocabulary is gone from the landed store. */
    @Test
    void refusesALegacyProjectedFilter() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "LEGACY_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1, "csv_settings", Map.of("where", "c1 > 0")));
        m.put("output_store", "x");
        PipelineConfig cfg = PipelineConfig.fromMap(m);
        assertFalse(cfg.hasExplicitSteps());
        assertTrue(cfg.steps().stream().anyMatch(s -> PipelineConfig.Step.FILTER.equals(s.kind())),
                "precondition: the legacy projection carries the filter");
        var e = assertThrows(IllegalArgumentException.class, () -> PipelineLift.stageTwo(cfg));
        assertTrue(e.getMessage().contains("pre-map"), e.getMessage());
    }

    @Test
    void refusesAnExplicitFilterCarryingPreParseKeys() throws Exception {
        var e = assertThrows(IllegalArgumentException.class, () -> PipelineLift.stageTwo(
                config("x", List.of(Map.of("filter",
                        Map.of("include_prefixes", List.of("A"), "filter_target_column", 0))))));
        assertTrue(e.getMessage().contains("pre-parse"), e.getMessage());
    }
}
