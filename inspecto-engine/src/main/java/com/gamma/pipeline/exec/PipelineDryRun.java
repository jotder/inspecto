package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.NodeCategory;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.PipelineStores;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>T18 — pipeline dry-run (§7.2): "test the pipeline incrementally".</b> Runs a bounded sample through a pipeline's
 * {@code transform → sink} subgraph on a throwaway DuckDB and reports per-node produced relations and the rows
 * each sink would receive — the per-edge record counts an operator watches as records flow. It reuses the
 * <em>production</em> walk ({@link PipelineExecutor#dryRun}, the same {@link RowShaper} as a real run) and commits
 * nothing; the scratch database is deleted afterwards.
 *
 * <p>The sample is the <b>post-parse</b> record set, so it is seeded at the pipeline's parser node (or, if the pipeline
 * has none, its entry node); the acquisition/parse stage upstream of the seed is not exercised here.
 */
@PublicApi(since = "4.0.0")
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
     * {@code references}. Throws {@link IllegalArgumentException} for an empty sample or a pipeline with no
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
        return runSeeded(g, Map.of(PipelineRel.DATA, sampleRows), references, stopAtNodeId);
    }

    /**
     * Dry-run {@code g} seeding the parse node with <b>one relation per segment</b> — {@code WB-08}.
     *
     * <p>A segment-routed frontend lifts as {@code parse →(route:<segment>)→ map_<segment> →
     * sink_<segment>}, and the walk follows an edge only when the upstream node produced its {@code rel}.
     * Seeding one {@code data} table could therefore never leave such a parser: *Run to here* decoded
     * records and reported {@code relations: []} ({@code TESTRUN-SEGMENT-ROUTE-NO-FLOW-1}).
     *
     * <p>⚠ Keys are the caller's, and they must be the graph's own edge relations — build them with
     * {@link PipelineRel#route(String)} over {@link com.gamma.pipeline.PipelineLift#routeKey}, the ONE
     * definition of a branch key. Recomputing the sanitisation here would be a second implementation of
     * a name that has to match exactly, which is how the two surfaces in Sprint A came to disagree.
     *
     * @param rowsByRelation {@code rel → rows}; an entry with no rows is skipped rather than seeding an
     *                       empty table, so "this segment produced nothing" stays distinguishable from
     *                       "this segment was never routed".
     */
    public static Result runSeeded(PipelineGraph g, Map<String, List<Map<String, Object>>> rowsByRelation,
                                   RowShaper.ReferenceResolver references, String stopAtNodeId) throws Exception {
        if (rowsByRelation == null || rowsByRelation.values().stream().allMatch(r -> r == null || r.isEmpty()))
            throw new IllegalArgumentException("at least one sample row is required");
        g = withMappingContext(g);
        String seedNode = seedNodeOf(g);

        File db = DuckDbUtil.tempDbFile("dryrun_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            Map<String, String> seeds = new LinkedHashMap<>();
            int i = 0;
            for (Map.Entry<String, List<Map<String, Object>>> e : rowsByRelation.entrySet()) {
                List<Map<String, Object>> rows = e.getValue();
                if (rows == null || rows.isEmpty()) continue;
                // One scratch table per relation. The single-relation case keeps the historic name, so a
                // failure message about `dryrun_seed` still reads the way every existing test expects.
                String table = seeds.isEmpty() && rowsByRelation.size() == 1 ? SEED : SEED + "_" + (i++);
                ScratchTables.seed(conn, table, ScratchTables.columnsOf(rows), rows);
                seeds.put(e.getKey(), table);
            }
            PipelineExecutor.DryRunResult dr =
                    PipelineExecutor.dryRun(conn, g, seedNode, seeds, references, stopAtNodeId);
            Map<String, PipelineNode> byId = g.byId();
            // G8: a webhook branch refuses here exactly as the job dry run's DryRunSinkWriter refuses it —
            // the same WebhookSink.plan (config, edition transport, Connection, token), then nothing sent.
            // Before this, a bundle with no WebhookSinkTransport previewed the branch as healthy.
            for (String sinkId : dr.sinkInputs().keySet()) {
                PipelineNode n = byId.get(sinkId);
                if (n != null && BuiltinNodeType.SINK_WEBHOOK.type().equals(n.type())) WebhookSink.plan(n);
            }

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
            List<String> warnings = new ArrayList<>(notExecutedWarnings(g, dr));
            warnings.addAll(warningsFor(nodes, sinks, seedNode));
            return new Result(seedNode, nodes, sinks, List.copyOf(warnings));
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
     * G8 of {@code PROCESSOR-RELEASE-READINESS-1} — name every node the walk reached but could not run, and
     * the nodes below it that therefore received nothing. It used to be silent: an {@code enrichment} mid-walk
     * produced no relation, the sink under it vanished from {@code sinks}, and neither DRYRUN-2 warning fired
     * because there was no sink branch left to be empty.
     *
     * <p>⚖ <b>Warned, not run, and not refused.</b> An {@code enrichment} is a post-commit Stage-2 job over
     * the <em>committed store</em> (its partitions, prior batches, its own references) — neither run lane
     * executes it as a walk step, so running it over the sample would preview something no run does. Refusing
     * would make every graph carrying one un-previewable over a node the rest of the walk does not need.
     */
    private static List<String> notExecutedWarnings(PipelineGraph g, PipelineExecutor.DryRunResult dr) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : dr.notExecuted().entrySet()) {
            String why = BuiltinNodeType.ENRICHMENT.type().equals(e.getValue())
                    ? "an enrichment is a post-commit Stage-2 job over the committed store, and a dry run "
                      + "commits nothing — preview it with POST /enrichment/preview"
                    : "the dry run has no executor for a '" + e.getValue() + "' node";
            List<String> below = starvedBelow(g, e.getKey(), dr);
            out.add("node '" + e.getKey() + "' (" + e.getValue() + ") was NOT previewed: " + why
                    + (below.isEmpty() ? "" : "; so nothing reached "
                       + String.join(", ", below.stream().map(n -> "'" + n + "'").toList())));
        }
        return out;
    }

    /** Descendants of {@code nodeId} (within-graph edges) that neither produced a relation nor fed a sink. */
    private static List<String> starvedBelow(PipelineGraph g, String nodeId, PipelineExecutor.DryRunResult dr) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(nodeId));
        while (!todo.isEmpty()) {
            String cur = todo.poll();
            for (PipelineEdge edge : g.edgesFrom(cur))
                if (!PipelineRel.ON_COMMIT.equals(edge.rel()) && seen.add(edge.to())) todo.add(edge.to());
        }
        seen.removeIf(n -> !g.byId().containsKey(n)
                || dr.produced().containsKey(n) || dr.sinkInputs().containsKey(n));
        return List.copyOf(seen);
    }

    /**
     * Put the parser node's {@code csv} settings within reach of each projection-slot {@code transform.sql}
     * node that carries a schema or mapping rules instead of authored {@code columns} — either a legacy {@code schema}, the shape
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
            if (BuiltinNodeType.TRANSFORM_SQL.type().equals(n.type())
                    && (n.cfg("schema") != null || n.cfg("rules") != null || n.cfg("fields") != null)
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
