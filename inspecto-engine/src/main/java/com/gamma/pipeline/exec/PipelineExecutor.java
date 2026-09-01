package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.StepProgress;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.PipelineValidator;
import com.gamma.pipeline.NodeCategory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>T12 — the branch-aware topological executor.</b> Drives a {@link PipelineGraph} over a DuckDB connection:
 * it walks the graph in topological order, runs each {@code transform.*} node through {@link RowShaper}
 * (T10) to produce named relations, <b>routes</b> each produced relation along its outgoing edge to the
 * consuming node, and at the {@code sink} nodes drives the {@link BranchCommitCoordinator} commit-split
 * (T11) — each sink is a branch, and the source is finalised only once every branch has committed.
 *
 * <p>Scheduling is the new piece (R3): the legacy {@code MultiCollectorProcessor} fan-out is per-config with
 * no intra-pipeline branch concept; here a single batch fans across the flow's branches. This first cut is
 * a deterministic sequential topological walk (correct + ordered); running independent branches on the
 * existing vthread pool/permit pattern is a follow-up optimisation.
 *
 * <p><b>Additive:</b> this does not replace the legacy single-output {@code ConsignmentIngestor} path. It starts
 * from a <em>seed</em> relation — the {@code data} output of the parse stage, already materialised in
 * {@code conn} by the existing ingest — and executes the downstream {@code transform → sink} subgraph.
 * Routing a legacy config's whole suite through it (byte-for-byte parity) is T5b, still future.
 *
 * <p>Relations flow by a <b>pull</b> model over the topological order: when a node is visited, each of its
 * inbound edges {@code (from, rel, to)} contributes the relation {@code rel} that {@code from} produced as
 * one of this node's inputs. {@code on_commit} edges are cross-flow and excluded from the walk.
 */
@PublicApi(since = "4.0.0")
public final class PipelineExecutor {

    private PipelineExecutor() {}

    /** Persists one sink node's input relation (the real sink write / DuckLake register / Parquet output). */
    @FunctionalInterface
    public interface SinkWriter {
        void write(PipelineNode sink, String inputTable) throws Exception;
    }

    /**
     * <b>T20 — the data-plane provenance hook.</b> Called once per <em>(node, outgoing relationship)</em> as the
     * executor walks the graph, with the number of records the node emitted on that relationship (§11.1/§11.3).
     * The structure plane (the {@link PipelineGraph} edges) carries the topology; these counts are the quantities
     * painted onto it. The default {@link #NONE} ignores them, so the live path is unchanged unless a collector
     * is supplied (a flow job passes one to persist a {@code ProvenanceRow} per call — T21).
     */
    @FunctionalInterface
    public interface ProvenanceCollector {
        void record(String nodeId, String rel, long rowCount);

        /** A collector that discards counts — the default for callers that don't observe provenance. */
        ProvenanceCollector NONE = (nodeId, rel, rowCount) -> {};
    }

    /** What the run produced: every node's named relations + which sinks fed which table + the commit outcome. */
    public record ExecResult(Map<String, Map<String, String>> produced,
                             Map<String, String> sinkInputs,
                             BranchCommitCoordinator.Result commit) {}

    /**
     * Execute the {@code transform → sink} subgraph downstream of {@code seedNodeId}.
     *
     * @param conn           DuckDB connection holding {@code seedTable}
     * @param g              the flow graph (validated up-front via {@link PipelineValidator#validateOrThrow})
     * @param seedNodeId     the node whose {@code data} relation is {@code seedTable} (typically the parser)
     * @param seedTable      the DuckDB table the parse stage already produced
     * @param batchId        the batch being committed
     * @param coordinator    the {@link BranchCommitCoordinator} that gates source-finalisation on all sinks
     * @param sinkWriter     writes a sink branch's output (called by the coordinator, once per branch)
     * @param sourceFinalize backup → markers LAST → ledger/watermark (called once, after all sinks commit)
     */
    public static ExecResult execute(Connection conn, PipelineGraph g, String seedNodeId, String seedTable,
                                     String batchId, BranchCommitCoordinator coordinator,
                                     SinkWriter sinkWriter,
                                     BranchCommitCoordinator.SourceFinalize sourceFinalize) throws Exception {
        return execute(conn, g, Map.of(seedNodeId, seedTable), batchId, coordinator, sinkWriter, sourceFinalize);
    }

    /**
     * Execute the {@code transform → sink} subgraph downstream of one or more <b>seed</b> nodes. Each entry of
     * {@code seeds} maps a node id to the DuckDB table/view already holding that node's {@code data} relation.
     * The common case is a single parser-seeded relation (the {@code (seedNodeId, seedTable)} overload); a flow
     * job seeds <em>one view per {@code source_store}</em> (T32 Phase C, multi-source), so a {@code transform.merge}
     * can join/union several at-rest stores into one branch.
     */
    public static ExecResult execute(Connection conn, PipelineGraph g, Map<String, String> seeds,
                                     String batchId, BranchCommitCoordinator coordinator,
                                     SinkWriter sinkWriter,
                                     BranchCommitCoordinator.SourceFinalize sourceFinalize) throws Exception {
        return execute(conn, g, seeds, batchId, coordinator, sinkWriter, sourceFinalize, ProvenanceCollector.NONE);
    }

    /**
     * As {@link #execute(Connection, PipelineGraph, Map, String, BranchCommitCoordinator, SinkWriter,
     * BranchCommitCoordinator.SourceFinalize)}, but reports per-{@code (node, relationship)} record counts to
     * {@code prov} as it walks (T20, §11.3). Counts are taken while the scratch relations are still live in
     * {@code conn} — they cannot be recovered after the connection closes, which is why the hook is inline.
     */
    public static ExecResult execute(Connection conn, PipelineGraph g, Map<String, String> seeds,
                                     String batchId, BranchCommitCoordinator coordinator,
                                     SinkWriter sinkWriter,
                                     BranchCommitCoordinator.SourceFinalize sourceFinalize,
                                     ProvenanceCollector prov) throws Exception {
        return execute(conn, g, seeds, batchId, coordinator, sinkWriter, sourceFinalize, prov,
                RowShaper.ReferenceResolver.NONE);
    }

    /**
     * As the {@link ProvenanceCollector} overload, with reference context: {@code references} resolves a
     * {@code transform.join} node's Reference Dataset to a relation on {@code conn}
     * ({@link RowShaper.ReferenceResolver}). The default {@link RowShaper.ReferenceResolver#NONE} refuses,
     * so a caller with no reference context fails a join loudly rather than resolving wrongly.
     */
    public static ExecResult execute(Connection conn, PipelineGraph g, Map<String, String> seeds,
                                     String batchId, BranchCommitCoordinator coordinator,
                                     SinkWriter sinkWriter,
                                     BranchCommitCoordinator.SourceFinalize sourceFinalize,
                                     ProvenanceCollector prov,
                                     RowShaper.ReferenceResolver references) throws Exception {
        return execute(conn, g, seeds, batchId, coordinator, sinkWriter, sourceFinalize, prov, references, null);
    }

    /**
     * Hands a disabled SINK node's live inbound relation to the at-rest lane so it can PARK the rows
     * durably (Phase 4 S4b, D-13) instead of the scratch paths' skip-and-vanish bypass. Called while
     * the relation is still live on the walk's connection — the same inline constraint as
     * {@link ProvenanceCollector}.
     */
    public interface ParkWriter {
        void park(PipelineNode node, String inputTable) throws Exception;
    }

    /**
     * As the {@link RowShaper.ReferenceResolver} overload, with the at-rest park hook: when
     * {@code parkWriter} is non-null, a <b>disabled sink</b> whose inbound relation is live is handed
     * to it before the bypass — park-at-boundary (§2.7: the pause boundary IS the materialisation
     * boundary; a route's branch table is already materialised when its sink is reached). A
     * {@code null} hook (every scratch path: dry-run, run-to-here) keeps the pure in-memory bypass —
     * D-13 keeps those scratch-only on purpose. Disabled NON-sink nodes always bypass: arming
     * ({@code StepDisableArming}) restricts an armed pipeline's disable list to route-fed sinks, so a
     * disabled mid-graph node can only occur on paths that never park.
     */
    public static ExecResult execute(Connection conn, PipelineGraph g, Map<String, String> seeds,
                                     String batchId, BranchCommitCoordinator coordinator,
                                     SinkWriter sinkWriter,
                                     BranchCommitCoordinator.SourceFinalize sourceFinalize,
                                     ProvenanceCollector prov,
                                     RowShaper.ReferenceResolver references,
                                     ParkWriter parkWriter) throws Exception {
        PipelineValidator.validateOrThrow(g);
        Map<String, PipelineNode> byId = g.byId();

        Map<String, Map<String, String>> produced = new LinkedHashMap<>();
        seeds.forEach((nodeId, table) -> produced.put(nodeId, new LinkedHashMap<>(Map.of(PipelineRel.DATA, table))));
        Map<String, String> sinkInputs = new LinkedHashMap<>();
        // Each seed's data relation is the flow's recordsIn at that source/parse node.
        for (Map.Entry<String, String> seed : seeds.entrySet())
            prov.record(seed.getKey(), PipelineRel.DATA, count(conn, seed.getValue()));

        List<String> order = topoOrder(g);
        int step = 0;
        try {
        for (String nodeId : order) {
            // Live step gauge (G6/S7): where this Consignment is in the walk — in-memory only,
            // poll-read via the control plane; never a Signal, never persisted (see StepProgress).
            StepProgress.track(g.name(), batchId, nodeId, ++step, order.size());
            if (seeds.containsKey(nodeId)) continue;       // pre-seeded source/parse relation
            PipelineNode node = byId.get(nodeId);
            if (!node.enabled()) {                         // disabled node (§3.6) — produces nothing; downstream inert
                // At-rest park (S4b): a disabled sink's branch relation is already materialised at
                // this point — hand it to the park hook so the rows survive durably instead of
                // vanishing with the bypass. Scratch paths pass no hook and keep the bypass.
                if (parkWriter != null && PipelineNodeTypes.isCategory(node.type(), NodeCategory.SINK)) {
                    List<PipelineEdge> parkedIn = liveInbound(g, nodeId, produced);
                    if (!parkedIn.isEmpty()) parkWriter.park(node, tableOf(parkedIn.get(0), produced));
                }
                continue;
            }
            List<PipelineEdge> inbound = liveInbound(g, nodeId, produced);
            if (inbound.isEmpty()) continue;               // upstream not executed here (e.g. acquisition) — skip

            if (PipelineNodeTypes.isCategory(node.type(), NodeCategory.SINK)) {
                String in = tableOf(inbound.get(0), produced);
                sinkInputs.put(nodeId, in);                 // a sink consumes one relation
                prov.record(nodeId, PipelineRel.DATA, count(conn, in));   // recordsIn at the (terminal) sink
            } else if (BuiltinNodeType.TRANSFORM_MERGE.type().equals(node.type())) {
                List<String> inputs = new ArrayList<>();
                for (PipelineEdge e : inbound) inputs.add(tableOf(e, produced));
                Map<String, String> rels = index(RowShaper.merge(conn, node, inputs, nodeId));
                produced.put(nodeId, rels);
                recordCounts(conn, prov, nodeId, rels);
            } else if (isShapeable(node.type())) {
                Map<String, String> rels = index(RowShaper.shape(conn, node, tableOf(inbound.get(0), produced),
                        nodeId, references));
                produced.put(nodeId, rels);
                recordCounts(conn, prov, nodeId, rels);
            }
            // control terminals (gap/alert/event) consume but produce no data relation — nothing to route on
        }

        if (sinkInputs.isEmpty())
            throw new IllegalStateException("pipeline '" + g.name() + "' produced no sink branches downstream of " + seeds.keySet());

        BranchCommitCoordinator.Result commit = coordinator.commit(batchId, new LinkedHashSet<>(sinkInputs.keySet()),
                branch -> sinkWriter.write(byId.get(branch), sinkInputs.get(branch)),
                sourceFinalize);
        return new ExecResult(produced, sinkInputs, commit);
        } finally {
            StepProgress.clear(g.name());   // a snapshot must never outlive the run — success or failure alike
        }
    }

    /** What a dry-run produced: every node's named relations + which table each sink would consume. No commit. */
    public record DryRunResult(Map<String, Map<String, String>> produced, Map<String, String> sinkInputs) {}

    /**
     * <b>T18 — bounded-sample dry-run.</b> The same topological walk + {@link RowShaper} as {@link #execute}, but
     * <em>without</em> the commit: it shapes each {@code transform.*} node over the seeded relation and records
     * the table each sink would consume, so a preview can report per-node / per-edge row counts. Scratch-only —
     * no {@link BranchCommitCoordinator}, no sink write, no source finalisation. {@code seedTable} is the sample
     * already materialised in {@code conn} (typically the parser's {@code data} output).
     */
    public static DryRunResult dryRun(Connection conn, PipelineGraph g, String seedNodeId, String seedTable)
            throws Exception {
        return dryRun(conn, g, seedNodeId, seedTable, RowShaper.ReferenceResolver.NONE);
    }

    /** As {@link #dryRun(Connection, PipelineGraph, String, String)}, with reference context for {@code transform.join}. */
    public static DryRunResult dryRun(Connection conn, PipelineGraph g, String seedNodeId, String seedTable,
                                      RowShaper.ReferenceResolver references) throws Exception {
        return dryRun(conn, g, seedNodeId, seedTable, references, null);
    }

    /**
     * As {@link #dryRun(Connection, PipelineGraph, String, String, RowShaper.ReferenceResolver)}, bounded to the
     * part of the graph that feeds {@code stopAtNodeId} — the <em>run-to-here</em> cutoff. {@code null} means no
     * cutoff, which is the only shape the production paths use, so they are unaffected by this parameter.
     *
     * <p><b>The cutoff is the ancestor closure of the target, not a prefix of the topological order.</b> Topo
     * order is arbitrary between sibling branches, so truncating it would run whichever unrelated branch happened
     * to sort earlier — reporting counts for nodes the operator did not ask about. Walking edges backwards from
     * the target keeps exactly the nodes whose output it depends on.
     *
     * @throws IllegalArgumentException if {@code stopAtNodeId} names no node in {@code g}
     */
    public static DryRunResult dryRun(Connection conn, PipelineGraph g, String seedNodeId, String seedTable,
                                      RowShaper.ReferenceResolver references, String stopAtNodeId) throws Exception {
        PipelineValidator.validateOrThrow(g);
        Map<String, PipelineNode> byId = g.byId();
        Set<String> bounds = ancestorsOf(g, stopAtNodeId, byId);
        Map<String, Map<String, String>> produced = new LinkedHashMap<>();
        produced.put(seedNodeId, new LinkedHashMap<>(Map.of(PipelineRel.DATA, seedTable)));
        Map<String, String> sinkInputs = new LinkedHashMap<>();

        for (String nodeId : topoOrder(g)) {
            if (nodeId.equals(seedNodeId)) continue;
            if (bounds != null && !bounds.contains(nodeId)) continue;
            PipelineNode node = byId.get(nodeId);
            if (!node.enabled()) continue;
            List<PipelineEdge> inbound = liveInbound(g, nodeId, produced);
            if (inbound.isEmpty()) continue;
            if (PipelineNodeTypes.isCategory(node.type(), NodeCategory.SINK)) {
                sinkInputs.put(nodeId, tableOf(inbound.get(0), produced));
            } else if (BuiltinNodeType.TRANSFORM_MERGE.type().equals(node.type())) {
                List<String> inputs = new ArrayList<>();
                for (PipelineEdge e : inbound) inputs.add(tableOf(e, produced));
                produced.put(nodeId, index(RowShaper.merge(conn, node, inputs, nodeId)));
            } else if (isShapeable(node.type())) {
                produced.put(nodeId, index(RowShaper.shape(conn, node, tableOf(inbound.get(0), produced),
                        nodeId, references)));
            }
        }
        return new DryRunResult(produced, sinkInputs);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Inbound edges whose source has already produced the relation they carry (the live inputs for this node). */
    private static List<PipelineEdge> liveInbound(PipelineGraph g, String nodeId, Map<String, Map<String, String>> produced) {
        List<PipelineEdge> out = new ArrayList<>();
        for (PipelineEdge e : g.edgesTo(nodeId)) {
            Map<String, String> up = produced.get(e.from());
            if (up != null && up.containsKey(e.rel())) out.add(e);
        }
        return out;
    }

    private static String tableOf(PipelineEdge e, Map<String, Map<String, String>> produced) {
        return produced.get(e.from()).get(e.rel());
    }

    private static Map<String, String> index(List<RowShaper.Relation> rels) {
        Map<String, String> m = new LinkedHashMap<>();
        for (RowShaper.Relation r : rels) m.put(r.rel(), r.table());
        return m;
    }

    /** Report a record count to {@code prov} for every produced relation of {@code nodeId}. */
    private static void recordCounts(Connection conn, ProvenanceCollector prov, String nodeId,
                                     Map<String, String> rels) throws SQLException {
        for (Map.Entry<String, String> e : rels.entrySet())
            prov.record(nodeId, e.getKey(), count(conn, e.getValue()));
    }

    /** Row count of a live scratch relation (long-precision; used for provenance counts). */
    private static long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + ScratchTables.q(table))) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static boolean isShapeable(String type) {
        return type.startsWith("transform.") && !BuiltinNodeType.TRANSFORM_MERGE.type().equals(type);
    }

    /**
     * {@code stopAt} plus every node that can reach it, walking edges backwards — the set a run-to-here may
     * execute. {@code null} in, {@code null} out, meaning "no cutoff"; that is the production shape.
     *
     * <p>{@code on_commit} edges are skipped for the same reason {@link #topoOrder} skips them: they are
     * cross-flow triggers, not within-graph data dependencies, so an upstream flow is not an ancestor here.
     */
    private static Set<String> ancestorsOf(PipelineGraph g, String stopAt, Map<String, PipelineNode> byId) {
        if (stopAt == null || stopAt.isBlank()) return null;
        if (!byId.containsKey(stopAt))
            throw new IllegalArgumentException("pipeline '" + g.name() + "' has no node '" + stopAt + "' to stop at");
        Map<String, List<String>> incoming = new LinkedHashMap<>();
        for (PipelineEdge e : g.edges()) {
            if (PipelineRel.ON_COMMIT.equals(e.rel())) continue;
            incoming.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
        }
        Set<String> keep = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(List.of(stopAt));
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (!keep.add(cur)) continue;
            for (String up : incoming.getOrDefault(cur, List.of())) stack.push(up);
        }
        return keep;
    }

    /**
     * Kahn topological order over all edges except cross-flow {@code on_commit}. The graph is a DAG over
     * data edges (the validator guarantees it); forward control/route edges keep the full order acyclic.
     */
    private static List<String> topoOrder(PipelineGraph g) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (PipelineNode n : g.nodes()) {
            indegree.put(n.id(), 0);
            adj.put(n.id(), new ArrayList<>());
        }
        for (PipelineEdge e : g.edges()) {
            if (PipelineRel.ON_COMMIT.equals(e.rel())) continue;     // cross-flow trigger, not a within-graph edge
            adj.get(e.from()).add(e.to());
            indegree.merge(e.to(), 1, Integer::sum);
        }
        Deque<String> ready = new ArrayDeque<>();
        for (var e : indegree.entrySet()) if (e.getValue() == 0) ready.add(e.getKey());
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String n = ready.poll();
            order.add(n);
            for (String m : adj.get(n))
                if (indegree.merge(m, -1, Integer::sum) == 0) ready.add(m);
        }
        if (order.size() != g.nodes().size())
            throw new IllegalStateException("pipeline '" + g.name() + "' is not acyclic over data/route/control edges");
        return order;
    }
}
