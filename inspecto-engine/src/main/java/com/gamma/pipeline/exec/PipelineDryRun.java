package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.NodeCategory;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.pipeline.PipelineStores;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>T18 — flow dry-run (§7.2): "test the pipeline incrementally".</b> Runs a bounded sample through a flow's
 * {@code transform → sink} subgraph on a throwaway DuckDB and reports per-node produced relations and the rows
 * each sink would receive — the per-edge record counts an operator watches as records flow. It reuses the
 * <em>production</em> walk ({@link PipelineExecutor#dryRun}, the same {@link RowShaper} as a real run) and commits
 * nothing; the scratch database is deleted afterwards.
 *
 * <p>The sample is the <b>post-parse</b> record set, so it is seeded at the flow's parser node (or, if the flow
 * has none, its entry node); the acquisition/parse stage upstream of the seed is not exercised here.
 */
@PublicApi(since = "4.3.0")
public final class PipelineDryRun {

    private PipelineDryRun() {}

    /** A produced relation at a node: the {@link com.gamma.pipeline.PipelineRel} + how many rows reached it (+ a sample). */
    public record RelationCount(String rel, int rowCount, List<Map<String, Object>> rows) {}

    /** One non-sink node's outputs in the dry-run. */
    public record NodeDryRun(String node, String type, List<RelationCount> relations) {}

    /** A sink branch in the dry-run: the table it would consume, the row count, and a sample. */
    public record SinkDryRun(String node, String store, int rowCount, List<Map<String, Object>> rows) {}

    /**
     * The dry-run outcome: where the sample was seeded, every transform node's outputs, each sink branch,
     * and any {@code warnings} about a run that <em>succeeded</em> yet tells the operator nothing.
     *
     * <p>DRYRUN-2: a sample that reaches no node at all used to answer an empty 200 — indistinguishable
     * from success in the UI, which is the worst way to report "nothing happened". The warning is a plain
     * string list, the shape the sink preview already returns for a missing partition column.
     */
    public record Result(String seedNode, List<NodeDryRun> nodes, List<SinkDryRun> sinks, List<String> warnings) {

        /** Pre-warnings shape, kept for {@code @PublicApi} source/binary compatibility. */
        public Result(String seedNode, List<NodeDryRun> nodes, List<SinkDryRun> sinks) {
            this(seedNode, nodes, sinks, List.of());
        }
    }

    /** Rows materialised per relation in the result (the counts are exact; the rows are a bounded sample). */
    public static final int SAMPLE_ROWS = 50;

    private static final String SEED = "dryrun_seed";

    /**
     * Dry-run {@code g} over {@code sampleRows} with no reference context, so a {@code transform.join} node
     * refuses ({@link RowShaper.ReferenceResolver#NONE}).
     */
    public static Result run(PipelineGraph g, List<Map<String, Object>> sampleRows) throws Exception {
        return run(g, sampleRows, RowShaper.ReferenceResolver.NONE);
    }

    /**
     * Dry-run {@code g} over {@code sampleRows}, resolving any {@code transform.join} reference through
     * {@code references}. Throws {@link IllegalArgumentException} for an empty sample or a flow with no
     * parser/entry node to seed at; validation errors surface from {@link PipelineExecutor#dryRun}.
     */
    public static Result run(PipelineGraph g, List<Map<String, Object>> sampleRows,
                             RowShaper.ReferenceResolver references) throws Exception {
        return run(g, sampleRows, references, null);
    }

    /**
     * As {@link #run(PipelineGraph, List, RowShaper.ReferenceResolver)}, bounded to the part of the graph that
     * feeds {@code stopAtNodeId} — the <em>run-to-here</em> cutoff. {@code null} means the whole graph.
     *
     * <p>The result then describes only the bounded subgraph: nodes below the target are absent rather than
     * present with zero counts, because "did not run" and "ran and produced nothing" are different answers and
     * the canvas renders them differently.
     *
     * @throws IllegalArgumentException if {@code stopAtNodeId} names no node in {@code g}
     */
    public static Result run(PipelineGraph g, List<Map<String, Object>> sampleRows,
                             RowShaper.ReferenceResolver references, String stopAtNodeId) throws Exception {
        if (sampleRows == null || sampleRows.isEmpty())
            throw new IllegalArgumentException("at least one sample row is required");
        g = withMappingContext(g);
        String seedNode = seedNodeOf(g);
        List<String> columns = ScratchTables.columnsOf(sampleRows);

        File db = DuckDbUtil.tempDbFile("dryrun_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, SEED, columns, sampleRows);
            PipelineExecutor.DryRunResult dr =
                    PipelineExecutor.dryRun(conn, g, seedNode, SEED, references, stopAtNodeId);
            Map<String, PipelineNode> byId = g.byId();

            List<NodeDryRun> nodes = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> e : dr.produced().entrySet()) {
                if (e.getKey().equals(seedNode)) continue;   // the seed is the input, not a produced node
                List<RelationCount> rels = new ArrayList<>();
                for (Map.Entry<String, String> r : e.getValue().entrySet()) {
                    rels.add(new RelationCount(r.getKey(),
                            ScratchTables.count(conn, r.getValue()),
                            ScratchTables.readRows(conn, r.getValue(), SAMPLE_ROWS)));
                }
                PipelineNode n = byId.get(e.getKey());
                nodes.add(new NodeDryRun(e.getKey(), n == null ? null : n.type(), rels));
            }

            List<SinkDryRun> sinks = new ArrayList<>();
            for (Map.Entry<String, String> s : dr.sinkInputs().entrySet()) {
                PipelineNode n = byId.get(s.getKey());
                Object store = n == null ? null : n.cfg(PipelineStores.CONFIG_STORE);
                sinks.add(new SinkDryRun(s.getKey(), store == null ? null : store.toString(),
                        ScratchTables.count(conn, s.getValue()),
                        ScratchTables.readRows(conn, s.getValue(), SAMPLE_ROWS)));
            }
            return new Result(seedNode, nodes, sinks, warningsFor(nodes, sinks, seedNode));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * DRYRUN-2 — say so when a technically-successful dry-run tells the operator nothing. Two distinct
     * silences, both of which answered a bare 200 before, which reads as success: the sample reached no
     * node at all (nothing downstream of the seed consumed it), and no sink would receive a single row.
     *
     * <p>⚠ The second condition is deliberately about <b>sinks</b>, not "every relation is empty". A
     * filter that drops all three sample rows produces a {@code data} of 0 and a {@code dropped} of 3 —
     * that run is informative, and warning "every relation produced zero rows" there would be both false
     * and noise. What the operator cannot see from row counts alone is that <em>nothing would be written</em>.
     *
     * <p>Warnings never fail the run — a zero-row dry-run is a legitimate answer about the sample, and the
     * operator is the one who can tell "my filter is wrong" from "this sample has no matching rows".
     */
    private static List<String> warningsFor(List<NodeDryRun> nodes, List<SinkDryRun> sinks, String seedNode) {
        if (nodes.isEmpty() && sinks.isEmpty())
            return List.of("the sample reached no node past the seed '" + seedNode
                    + "' — nothing downstream consumed it, so this run exercised nothing");
        if (!sinks.isEmpty() && sinks.stream().allMatch(s -> s.rowCount() == 0))
            return List.of("no sink received any rows — the sample was filtered or joined away before "
                    + "reaching an output, so this run cannot tell you the pipeline writes what you expect");
        return List.of();
    }

    /**
     * Put the parser node's {@code csv} settings within reach of each {@code transform.map} node that carries
     * mapping rules instead of authored {@code columns} — either a legacy {@code schema}, the shape
     * {@link com.gamma.pipeline.PipelineLift} produces for a registered pipeline, or the {@code rules} a
     * resolved {@code mapping} component contributed. {@link RowShaper} compiles them through the legacy
     * authority, which parses DATE/TIMESTAMP sources with the pipeline's configured format lists; a lifted graph
     * keeps them on the parser node, out of the map node's reach. Without this, a dry-run of any registered
     * pipeline with a schema fails on its map node.
     *
     * <p>In-memory only — the stored graph is never rewritten, and a graph that needs nothing is returned as is.
     */
    private static PipelineGraph withMappingContext(PipelineGraph g) {
        Object csv = null;
        for (PipelineNode n : g.nodes())
            if (PipelineNodeTypes.isCategory(n.type(), NodeCategory.PARSE) && n.cfg("csv") != null) csv = n.cfg("csv");
        if (csv == null) return g;

        List<PipelineNode> nodes = new ArrayList<>();
        boolean rewrote = false;
        for (PipelineNode n : g.nodes()) {
            if (BuiltinNodeType.TRANSFORM_MAP.type().equals(n.type())
                    && (n.cfg("schema") != null || n.cfg("rules") != null)
                    && n.cfg("columns") == null && n.cfg("csv") == null) {
                Map<String, Object> c = new LinkedHashMap<>(n.config());
                c.put("csv", csv);
                nodes.add(new PipelineNode(n.id(), n.type(), n.name(), n.description(), c, n.use()));
                rewrote = true;
            } else {
                nodes.add(n);
            }
        }
        return rewrote ? new PipelineGraph(g.name(), g.active(), nodes, g.edges()) : g;
    }

    /** Seed the sample at the parser node (its {@code data} output) if present, else the first entry node. */
    private static String seedNodeOf(PipelineGraph g) {
        for (PipelineNode n : g.nodes()) {
            if (PipelineNodeTypes.isCategory(n.type(), NodeCategory.PARSE)) return n.id();   // any parser subtype (B6)
        }
        if (!g.entryNodes().isEmpty()) return g.entryNodes().get(0).id();
        throw new IllegalArgumentException("pipeline '" + g.name() + "' has no parser or entry node to seed the sample at");
    }
}
