package com.gamma.pipeline.exec;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.etl.TestConfigs.csv;
import static org.junit.jupiter.api.Assertions.*;

/** {@link PipelineDryRun} (T18): a bounded sample through a flow's transform→sink subgraph, scratch-only. */
class PipelineDryRunTest {

    private static Map<String, Object> row(String id, String amt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("amt", amt);
        return m;
    }

    private static final List<Map<String, Object>> SAMPLE = List.of(row("1", "150"), row("2", "50"), row("3", "200"));

    private static PipelineDryRun.NodeDryRun node(PipelineDryRun.Result r, String id) {
        return r.nodes().stream().filter(n -> n.node().equals(id)).findFirst().orElseThrow();
    }

    private static int relCount(PipelineDryRun.NodeDryRun n, String rel) {
        return n.relations().stream().filter(x -> x.rel().equals(rel)).findFirst().orElseThrow().rowCount();
    }

    @Test
    void runsSampleThroughTransformToSinkWithCounts() throws Exception {
        PipelineGraph g = new PipelineGraph("demo", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "CAST(amt AS INT) >= 100")),
                        new PipelineNode("sink", "sink.persistent", "Big", null, Map.of("store", "big"), null)),
                List.of(PipelineEdge.data("acq", "flt"), PipelineEdge.data("flt", "sink")));

        PipelineDryRun.Result r = PipelineDryRun.run(g, SAMPLE);

        assertEquals("acq", r.seedNode());                       // no parser → seed at the entry node
        PipelineDryRun.NodeDryRun flt = node(r, "flt");
        assertEquals(2, relCount(flt, PipelineRel.DATA));            // amt 150, 200 kept
        assertEquals(1, relCount(flt, PipelineRel.DROPPED));         // amt 50 dropped

        assertEquals(1, r.sinks().size());
        PipelineDryRun.SinkDryRun sink = r.sinks().get(0);
        assertEquals("sink", sink.node());
        assertEquals("big", sink.store());
        assertEquals(2, sink.rowCount());                        // the sink receives the filter's data branch
        assertFalse(sink.rows().isEmpty());
    }

    /**
     * A <b>registered</b> pipeline dry-runs too. Its graph comes from {@link com.gamma.pipeline.PipelineLift},
     * whose {@code transform.map} node carries the legacy {@code schema} rather than authored {@code columns} —
     * which used to throw {@code needs a non-empty 'columns' list} (surfaced by the route as a misleading 400),
     * making dry-run unusable for every pipeline that has a schema.
     *
     * <p>The sample carries an extra {@code JUNK} column the mapping does not name, and a text {@code AMT}: a
     * pass-through would keep {@code JUNK} and leave {@code AMT} a string, so dropping it and typing {@code AMT}
     * as a {@code DOUBLE} is what proves the schema's mapping rules were really compiled and projected.
     */
    @Test
    void runsSampleThroughALiftedRegisteredPipeline(@TempDir Path dir) throws Exception {
        PipelineGraph g = PipelineLift.lift(csv(dir, PipelineConfigBatchTest.miniSchema()).load());

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("ID", "a1");
        sample.put("AMT", "12.50");
        sample.put("EVENT_DATE", "2026-01-15");
        sample.put("JUNK", "not mapped");

        PipelineDryRun.Result r = PipelineDryRun.run(g, List.of(sample));

        assertEquals("parse", r.seedNode());                     // lifted graphs always have a parser
        PipelineDryRun.NodeDryRun map = node(r, "map");
        assertEquals(1, relCount(map, PipelineRel.DATA));

        Map<String, Object> out = map.relations().get(0).rows().get(0);
        assertEquals(List.of("ID", "AMT", "EVENT_DATE"), List.copyOf(out.keySet()),
                "the mapping's target columns, in rule order — JUNK is not one of them");
        assertEquals(12.5, out.get("AMT"));                      // DOUBLE field ⇒ TRY_CAST, not the raw string
    }

    /**
     * A {@code transform.map} whose rules live in a {@code mapping} component projects those rules. Nothing
     * resolved {@code use:} references before a run — {@code ComponentRegistry.effectiveConfig} existed but had
     * no production caller — so a referenced mapping was invisible to the executor and the node projected
     * nothing at all. The registry resolves the graph first; {@code PipelineDryRun} then finds the rules the
     * component contributed.
     */
    @Test
    void dryRunProjectsAMappingComponentsRules(@TempDir Path root, @TempDir Path cfgDir) throws Exception {
        Files.createDirectories(root.resolve("mappings"));
        Files.writeString(root.resolve("mappings/std.csv"), """
                targetColumn,sourceExpression,transformType
                ID,ID,DIRECT
                DOUBLED,TRY_CAST(AMT AS DOUBLE) * 2,EXPR
                """);
        // the csv settings the map node needs come off the parser node, as they do in a lifted graph
        PipelineConfig.CsvSettings csv = csv(cfgDir, PipelineConfigBatchTest.miniSchema()).load().csv();

        PipelineGraph g = new PipelineGraph("demo", true,
                List.of(PipelineNode.of("parse", "parser", Map.of("csv", csv)),
                        new PipelineNode("map", "transform.sql", null, null, Map.of(), "mapping/std"),
                        new PipelineNode("sink", "sink.persistent", "S", null, Map.of("store", "out"), null)),
                List.of(PipelineEdge.data("parse", "map"), PipelineEdge.data("map", "sink")));

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("ID", "a1");
        sample.put("AMT", "12.50");

        PipelineDryRun.Result r =
                PipelineDryRun.run(ComponentRegistry.scan(root).effectiveGraph(g), List.of(sample));

        Map<String, Object> out = node(r, "map").relations().get(0).rows().get(0);
        assertEquals(List.of("ID", "DOUBLED"), List.copyOf(out.keySet()), "the component's target columns");
        assertEquals("a1", out.get("ID"));
        assertEquals(25.0, out.get("DOUBLED"));       // the EXPR rule really ran
    }

    /**
     * A graph decoded from JSON carries its {@code csv} block as a plain map, never a
     * {@link PipelineConfig.CsvSettings} — {@code PipelineCodec} keeps node config verbatim, and nothing
     * converts it. {@link RowShaper} used to demand the record itself, so every dry-run over a candidate
     * body (and every {@code graph/raw} round-trip) whose map node carried rules failed with a misleading
     * 400. The format lists are read back off the map: this DATE rule can only parse with the one declared.
     */
    @Test
    void dryRunCompilesRulesWhenTheCsvBlockArrivedAsPlainJson() throws Exception {
        Map<String, Object> schema = Map.of(
                "raw", Map.of("fields", List.of(Map.of("name", "WHEN", "type", "DATE"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "EVENT_DAY", "sourceExpression", "WHEN", "transformType", "DIRECT"))));
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("WHEN", "24/06/2026");          // only "%d/%m/%Y" reads this correctly

        // exactly what Jackson hands back for a serialised CsvSettings: component names, plain collections
        Map<String, Object> csvAsJson = Map.of("dateFormats", List.of("%d/%m/%Y"), "tsFormats", List.of());
        PipelineDryRun.Result r = PipelineDryRun.run(graphWithMapNode(Map.of("schema", schema, "csv", csvAsJson)),
                List.of(sample));
        assertEquals("2026-06-24", String.valueOf(node(r, "map").relations().get(0).rows().get(0).get("EVENT_DAY")),
                "the format list was read off the JSON csv block");

        // no csv block at all — the rules still compile, the undeclared format just cannot parse this value
        PipelineDryRun.Result bare = PipelineDryRun.run(graphWithMapNode(Map.of("schema", schema)), List.of(sample));
        assertNull(node(bare, "map").relations().get(0).rows().get(0).get("EVENT_DAY"),
                "degrades to TRY_CAST rather than failing the whole dry-run");
    }

    /** seed --data--> map(config) --data--> sink, the shape a candidate body posts. */
    private static PipelineGraph graphWithMapNode(Map<String, Object> config) {
        return new PipelineGraph("demo", true,
                List.of(PipelineNode.of("seed", "acquisition"),
                        new PipelineNode("map", "transform.sql", null, null, config, null),
                        new PipelineNode("sink", "sink.persistent", "S", null, Map.of("store", "out"), null)),
                List.of(PipelineEdge.data("seed", "map"), PipelineEdge.data("map", "sink")));
    }

    @Test
    void emptySampleIsRejected() {
        PipelineGraph g = new PipelineGraph("demo", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        new PipelineNode("sink", "sink.persistent", "S", null, Map.of("store", "s"), null)),
                List.of(PipelineEdge.data("acq", "sink")));
        assertThrows(IllegalArgumentException.class, () -> PipelineDryRun.run(g, List.of()));
    }

    // ── DRYRUN-1: a join pipeline is dry-runnable when the caller supplies reference context ────

    /** seed --data--> join(reference/groups) --data--> sink. */
    private static PipelineGraph graphWithJoin() {
        return new PipelineGraph("demo", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("j", "transform.join",
                                Map.of("reference", "reference/groups", "on", "id")),
                        new PipelineNode("sink", "sink.persistent", "S", null, Map.of("store", "out"), null)),
                List.of(PipelineEdge.data("acq", "j"), PipelineEdge.data("j", "sink")));
    }

    @Test
    void aJoinPipelineDryRunsWhenAReferenceResolverIsSupplied() throws Exception {
        PipelineDryRun.Result r = PipelineDryRun.run(graphWithJoin(), SAMPLE, (conn, reference) -> {
            assertEquals("reference/groups", reference);   // the node's cfg value reaches the resolver verbatim
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE refdim AS SELECT * FROM (VALUES ('1','Alpha')) t(id, label)");
            }
            return "refdim";
        });

        assertEquals(3, relCount(node(r, "j"), PipelineRel.DATA));   // LEFT JOIN keeps the unmatched rows
        assertEquals(3, r.sinks().get(0).rowCount());
        assertTrue(r.warnings().isEmpty(), "a join that produced rows warns about nothing: " + r.warnings());
    }

    /**
     * Without reference context the join still refuses — the resolver is opt-in, so the two-arg entry point
     * cannot start resolving references by accident.
     */
    @Test
    void aJoinPipelineStillRefusesWithNoReferenceContext() {
        Exception e = assertThrows(Exception.class, () -> PipelineDryRun.run(graphWithJoin(), SAMPLE));
        assertTrue(e.getMessage() != null && e.getMessage().contains("reference/groups"),
                "the refusal names the reference it could not reach: " + e.getMessage());
    }

    // ── DRYRUN-2: a technically-successful dry-run that tells the operator nothing says so ──────

    @Test
    void aSampleThatReachesNoNodeCarriesAWarningRatherThanASilentEmpty200() throws Exception {
        // A lone entry node: the seed IS the whole graph, so nothing downstream consumes the sample.
        PipelineGraph g = new PipelineGraph("demo", true,
                List.of(PipelineNode.of("acq", "acquisition")), List.of());

        PipelineDryRun.Result r = PipelineDryRun.run(g, SAMPLE);

        assertTrue(r.nodes().isEmpty());
        assertTrue(r.sinks().isEmpty());
        assertEquals(1, r.warnings().size(), "the empty run is reported, not left to look like success");
        assertTrue(r.warnings().get(0).contains("reached no node"), r.warnings().get(0));
    }

    /**
     * The sample is filtered away, so the sink would write nothing — worth saying. Note the run is NOT
     * relation-empty: the filter's {@code dropped} branch carries all three rows, which is exactly why the
     * warning asks about sinks rather than about every relation.
     */
    @Test
    void aSampleThatReachesNoSinkRowWarnsThatNothingWouldBeWritten() throws Exception {
        PipelineGraph g = new PipelineGraph("demo", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "CAST(amt AS INT) > 9999")),
                        new PipelineNode("sink", "sink.persistent", "S", null, Map.of("store", "out"), null)),
                List.of(PipelineEdge.data("acq", "flt"), PipelineEdge.data("flt", "sink")));

        PipelineDryRun.Result r = PipelineDryRun.run(g, SAMPLE);

        assertEquals(0, r.sinks().get(0).rowCount());
        assertEquals(3, relCount(node(r, "flt"), PipelineRel.DROPPED), "the dropped branch is not empty");
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("no sink received any rows"), r.warnings().get(0));
    }

    @Test
    void aRunThatProducesRowsCarriesNoWarnings() throws Exception {
        PipelineGraph g = new PipelineGraph("demo", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "CAST(amt AS INT) >= 100")),
                        new PipelineNode("sink", "sink.persistent", "Big", null, Map.of("store", "big"), null)),
                List.of(PipelineEdge.data("acq", "flt"), PipelineEdge.data("flt", "sink")));

        assertTrue(PipelineDryRun.run(g, SAMPLE).warnings().isEmpty());
    }

    // ── 5b: the run-to-here cutoff ─────────────────────────────────────────────────────────────

    /**
     * A fork: the seed feeds two independent branches, each ending in its own sink.
     * <pre>
     *   acq ─┬─> left  ─> leftSink
     *        └─> right ─> rightSink
     * </pre>
     * The branches are siblings, so no topological order puts one wholly before the other for a reason the
     * cutoff could rely on — which is what makes this the shape that tells ancestor-closure apart from a
     * truncated topo order.
     */
    private static PipelineGraph forkedGraph() {
        return new PipelineGraph("demo", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("left", "transform.filter", Map.of("where", "CAST(amt AS INT) >= 100")),
                        PipelineNode.of("right", "transform.filter", Map.of("where", "CAST(amt AS INT) < 100")),
                        new PipelineNode("leftSink", "sink.persistent", "L", null, Map.of("store", "l"), null),
                        new PipelineNode("rightSink", "sink.persistent", "R", null, Map.of("store", "r"), null)),
                List.of(PipelineEdge.data("acq", "left"), PipelineEdge.data("left", "leftSink"),
                        PipelineEdge.data("acq", "right"), PipelineEdge.data("right", "rightSink")));
    }

    private static boolean ran(PipelineDryRun.Result r, String id) {
        return r.nodes().stream().anyMatch(n -> n.node().equals(id))
                || r.sinks().stream().anyMatch(s -> s.node().equals(id));
    }

    /**
     * Stopping at {@code right} runs {@code right} and nothing else: not its own sink (below the target), and
     * crucially not {@code left}, which is neither an ancestor nor a descendant of the target.
     *
     * <p>⚠ This is the assertion that pins the <b>ancestor closure</b>, and <b>the target must be {@code right}
     * specifically</b>. A cutoff implemented as "topological order, truncated at the target" keeps every node
     * that happens to sort earlier — and Kahn's order here is {@code acq, left, right, …}, so bounding at
     * {@code left} yields the correct set <em>by coincidence</em> and passes under the broken implementation.
     * Bounding at the sibling that sorts LATER is what tells the two apart. This was verified by probe: the
     * prefix implementation leaves this test red and the {@code left} version green.
     */
    @Test
    void stoppingAtANodeRunsItsAncestorsAndNeitherItsDescendantsNorItsSiblings() throws Exception {
        PipelineDryRun.Result r = PipelineDryRun.run(forkedGraph(), SAMPLE, RowShaper.ReferenceResolver.NONE, "right");

        assertTrue(ran(r, "right"), "the target itself runs");
        assertEquals(1, relCount(node(r, "right"), PipelineRel.DATA));   // amt 50 — really executed
        assertFalse(ran(r, "rightSink"), "below the target");
        assertFalse(ran(r, "left"), "a sibling branch is not an ancestor of the target, even sorting earlier");
        assertFalse(ran(r, "leftSink"), "nor is anything below that sibling");
    }

    /**
     * The same graph with no cutoff runs everything. Pins that the new parameter's default really is "whole
     * graph" — the production dry-run path passes {@code null} and must be unchanged by 5b.
     */
    @Test
    void noCutoffStillRunsTheWholeGraph() throws Exception {
        PipelineGraph g = forkedGraph();
        PipelineDryRun.Result r = PipelineDryRun.run(g, SAMPLE);

        for (String id : List.of("left", "right", "leftSink", "rightSink"))
            assertTrue(ran(r, id), id + " should run when nothing bounds the walk");

        // and the explicit null is the same answer as the overload that omits it
        PipelineDryRun.Result viaNull = PipelineDryRun.run(g, SAMPLE, RowShaper.ReferenceResolver.NONE, null);
        assertEquals(r.nodes().size(), viaNull.nodes().size());
        assertEquals(r.sinks().size(), viaNull.sinks().size());
    }

    /** Stopping at a sink includes that sink but still not the other branch. */
    @Test
    void stoppingAtASinkIncludesTheSinkItself() throws Exception {
        PipelineDryRun.Result r =
                PipelineDryRun.run(forkedGraph(), SAMPLE, RowShaper.ReferenceResolver.NONE, "leftSink");

        assertTrue(ran(r, "leftSink"));
        assertEquals(2, r.sinks().get(0).rowCount());
        assertFalse(ran(r, "right"));
    }

    /**
     * A cutoff above every sink is a legitimate run, not a silent nothing — and it must NOT trip the DRYRUN-2
     * "no sink received any rows" warning, which is about a flow that would write nothing. Here no sink ran at
     * all because the operator bounded the run, which is a different statement.
     */
    @Test
    void aCutoffAboveEverySinkDoesNotWarnThatNoSinkWasReached() throws Exception {
        PipelineDryRun.Result r = PipelineDryRun.run(forkedGraph(), SAMPLE, RowShaper.ReferenceResolver.NONE, "left");

        assertTrue(r.sinks().isEmpty());
        assertTrue(r.warnings().isEmpty(), "bounded on purpose is not the same as 'nothing would be written': "
                + r.warnings());
    }

    /** An unknown target is rejected by name rather than quietly running the whole graph. */
    @Test
    void anUnknownStopNodeIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PipelineDryRun.run(forkedGraph(), SAMPLE, RowShaper.ReferenceResolver.NONE, "nope"));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }
}
