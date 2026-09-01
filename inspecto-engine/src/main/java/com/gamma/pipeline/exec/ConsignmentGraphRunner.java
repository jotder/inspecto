package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.PartitionOutput;
import com.gamma.pipeline.NodeCategory;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.pipeline.PipelineRel;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

/**
 * <b>Stage A — run one materialised batch through the graph, on the ingest path.</b> A first-class unit that
 * drives {@link PipelineExecutor#execute} for a single already-parsed batch: it wires the real
 * {@link PartitionSinkWriter} and a {@link BranchCommitCoordinator} over a fresh {@link BranchCommitLog},
 * then commits every sink branch and finalises the source exactly once (T11's commit-split).
 *
 * <p>This is the seam the plan draws now so the driver can change later (branch-aware-executor-plan §5):
 * the poll loop calls this unit today; a queue-driven driver can call the same unit in Stage B with no
 * rework. It deliberately stays <b>config-agnostic</b> — like {@link PipelineExecutor} and
 * {@link PartitionSinkWriter} it takes primitives ({@code conn}, {@code dataDir}, {@code baseName},
 * {@code batchId}), never a {@code PipelineConfig} — so the {@code inspecto.exec} layer keeps no dependency
 * on the ingest config model. The {@code inspector}-package caller maps its config to these primitives and
 * supplies the real {@link BranchCommitCoordinator.SourceFinalize} (the re-homed {@code ConsignmentIngestor.commit}
 * finalize body: backup → markers LAST → ledger / watermark).
 */
@PublicApi(since = "4.0.0")
public final class ConsignmentGraphRunner {

    private ConsignmentGraphRunner() {}

    /**
     * Everything the runner needs to drive one batch through {@link PipelineExecutor#execute}.
     *
     * @param conn            DuckDB connection already holding {@code seedTable}
     * @param graph           the lifted flow graph (validated inside the executor)
     * @param seedNodeId      the node whose {@code data} relation is {@code seedTable} (typically the parser)
     * @param seedTable       the DuckDB table the parse stage already produced
     * @param batchId         the batch being committed
     * @param dataDir         data root under which each sink's {@code store} is written as a sub-directory
     * @param baseName        the output file stem (typically the pipeline / batch id)
     * @param branchCommitLog path to this batch's durable {@link BranchCommitLog} (partial-commit state)
     * @param writeScope      discriminator when one batch performs several writes (chunked / segmented
     *                        ingest) — see {@link BranchCommitCoordinator#BranchCommitCoordinator(BranchCommitLog, String)};
     *                        {@code ""} for the whole-batch write
     */
    public record Input(Connection conn, PipelineGraph graph, String seedNodeId, String seedTable,
                        String batchId, String dataDir, String baseName, Path branchCommitLog,
                        String writeScope) {

        /** The whole-batch write: no scope, so the ledger keys are the bare branch ids. */
        public Input(Connection conn, PipelineGraph graph, String seedNodeId, String seedTable,
                     String batchId, String dataDir, String baseName, Path branchCommitLog) {
            this(conn, graph, seedNodeId, seedTable, batchId, dataDir, baseName, branchCommitLog, "");
        }
    }

    /** What the run produced: the executor result plus the files written across every sink branch. */
    public record Result(PipelineExecutor.ExecResult exec, List<PartitionOutput> outputs, long totalRows) {}

    /**
     * The once-per-batch source finalisation, given the files written across <em>all</em> sink branches.
     * Runs after every branch is durable (the T11 commit-split), so {@code sinkOutputs} is complete — the
     * ingest path uses it for DuckLake register / manifest before the input-file steps (backup → markers
     * LAST → ledger / watermark). A no-arg finaliser can ignore the argument (e.g. a flow job or a test).
     */
    @FunctionalInterface
    public interface SourceFinalizer {
        void finalizeSource(List<PartitionOutput> sinkOutputs) throws Exception;
    }

    /**
     * Drive the {@code transform → sink} subgraph downstream of the seed and commit it. {@code finalizer}
     * runs once, after every sink branch is durable, with the outputs written across all branches — the
     * ingest path passes the re-homed {@code ConsignmentIngestor.commit} body; a test may pass a no-op.
     */
    public static Result run(Input in, SourceFinalizer finalizer) throws Exception {
        PartitionSinkWriter writer =
                new PartitionSinkWriter(in.conn(), in.dataDir(), in.baseName(), in.batchId());
        PipelineExecutor.ExecResult exec = run(in, writer, () -> finalizer.finalizeSource(writer.outputs()));
        return new Result(exec, writer.outputs(), writer.totalRows());
    }

    /**
     * As {@link #run(Input, SourceFinalizer)}, but with a caller-supplied {@link PipelineExecutor.SinkWriter}
     * (arming plan S2): the ingest path writes each route branch to its paired {@code sinks[]} destination —
     * with the destination's own format/compression/{@code filename_column} and per-branch lineage — which
     * the flow-job-shaped {@link PartitionSinkWriter} (one {@code dataDir/store} root, no lineage) cannot
     * express. The runnable/finalize contract is identical: every sink branch commits through the durable
     * {@link BranchCommitLog}, and {@code onAllBranchesDurable} runs exactly once after the last of them.
     */
    public static PipelineExecutor.ExecResult run(Input in, PipelineExecutor.SinkWriter writer,
                                                  BranchCommitCoordinator.SourceFinalize onAllBranchesDurable)
            throws Exception {
        return run(in, writer, onAllBranchesDurable, null);
    }

    /**
     * As above, with the at-rest park hook (Phase 4 S4b): a disabled route-branch sink's live relation
     * is handed to {@code parkWriter} before the bypass, so its rows survive durably instead of
     * vanishing. {@code null} keeps the bypass — the pre-S4b behavior every scratch path retains.
     */
    public static PipelineExecutor.ExecResult run(Input in, PipelineExecutor.SinkWriter writer,
                                                  BranchCommitCoordinator.SourceFinalize onAllBranchesDurable,
                                                  PipelineExecutor.ParkWriter parkWriter)
            throws Exception {
        BranchCommitCoordinator coordinator = new BranchCommitCoordinator(
                new BranchCommitLog(in.branchCommitLog().toString()), in.writeScope());
        return PipelineExecutor.execute(
                in.conn(), in.graph(), java.util.Map.of(in.seedNodeId(), in.seedTable()), in.batchId(),
                coordinator, writer, onAllBranchesDurable,
                PipelineExecutor.ProvenanceCollector.NONE, RowShaper.ReferenceResolver.NONE, parkWriter);
    }

    /**
     * <b>Stage A engagement predicate.</b> Whether {@code g} needs this branch-aware runner instead of the
     * flat single-output write path: it fans one batch across <b>more than one data-fed sink BRANCH</b>.
     * This is deliberately {@code false} for every legacy single-sink {@code *_pipeline.toon} — a
     * single-schema lift produces one data sink plus a quarantine sink wired only by the {@code unmatched}
     * control edge, and {@link #dataFedSinkCount} excludes the latter (exactly as {@link PipelineExecutor}
     * does — a control edge carries no relation produced from a seed, so the executor never commits it as a
     * branch).
     *
     * <p>⚠ A branch is NOT a sink node (refined 2026-08-26, arming plan S1 — the original node count
     * was refuted by its own falsification test): a plain {@code sinks[2]} fan-out lifts to TWO
     * persistent-sink nodes each on a plain {@code data} edge, but that is N destinations of ONE
     * branch — it shipped 2026-08-02 as flat-path fan-out in {@code writeAndTrace} and must never
     * divert here. What makes a second <em>branch</em> is a second {@code route:<key>} relation:
     * exactly what {@link PipelineExecutor} commits as a branch. So the count is
     * <em>distinct route keys feeding sinks, plus one for the trunk if any sink is plain-data-fed</em>.
     */
    public static boolean engages(PipelineGraph g) {
        // MIDBRANCH-1 (R3): a route:<key> edge feeding a NON-sink node is a flattened branch
        // sub-chain (PipelineLift's expansion) — a node ONLY this walk executes, so the graph must
        // engage even when the branch count alone would not (a single-branch route whose chain would
        // otherwise be silently skipped by the flat lane). This is the deliberate extension of the
        // boundary the R3 unblock verdict names.
        return dataFedSinkCount(g) > 1 || hasRouteFedChain(g);
    }

    /** Whether any {@code route:*} edge feeds a non-sink node — i.e. a flattened branch sub-chain exists. */
    public static boolean hasRouteFedChain(PipelineGraph g) {
        var byId = g.byId();
        for (var e : g.edges()) {
            if (!PipelineRel.isRoute(e.rel())) continue;
            var target = byId.get(e.to());
            // Only a transform.route node's branches are chains — a multi-schema parser's route:<key>
            // dispatch edges also feed transforms, and those are the flat lane's own per-schema trees.
            var from = byId.get(e.from());
            if (from == null || !"transform.route".equals(from.type())) continue;
            if (target != null && !PipelineNodeTypes.isCategory(target.type(), NodeCategory.SINK)) return true;
        }
        return false;
    }

    /**
     * Count of data-fed sink <b>branches</b>: distinct {@code route:*} relations reaching a
     * {@code SINK}-category node, plus one for the trunk when any sink is reached by a plain
     * {@code data} edge. (N plain-data-fed sinks are one branch with N destinations — see
     * {@link #engages}.)
     *
     * <p>MIDBRANCH-1: a branch whose sub-chain is flattened between the {@code route:<key>} edge and
     * its sink reaches that sink by a plain {@code data} edge — so the count traces each sink's
     * feeding relation UPSTREAM through chain transforms to the route edge (or to the trunk). Without
     * the trace, every chained branch would collapse into the one trunk {@code data} bucket and a
     * two-branch route with chains would stop engaging.
     */
    public static long dataFedSinkCount(PipelineGraph g) {
        var byId = g.byId();
        java.util.Set<String> branches = new java.util.HashSet<>();
        for (var n : g.nodes()) {
            if (!PipelineNodeTypes.isCategory(n.type(), NodeCategory.SINK)) continue;
            for (var e : g.edgesTo(n.id())) {
                String rel = branchRelOf(g, byId, e, 0);
                if (rel != null) branches.add(rel);
            }
        }
        return branches.size();
    }

    /** The branch identity of edge {@code e}: its own {@code route:*} rel, or — for a {@code data}
     *  edge — the {@code route:*} rel found walking upstream through transform chain nodes, else the
     *  trunk {@code data}. {@code null} for control edges (they carry no seeded relation). */
    private static String branchRelOf(PipelineGraph g, java.util.Map<String, PipelineNode> byId,
                                      PipelineEdge e, int depth) {
        if (PipelineRel.isRoute(e.rel())) return e.rel();
        if (!PipelineRel.DATA.equals(e.rel())) return null;
        if (depth > g.nodes().size()) return PipelineRel.DATA;   // defensive: the validator owns acyclicity
        PipelineNode from = byId.get(e.from());
        if (from != null && from.type().startsWith("transform.") && !"transform.route".equals(from.type())) {
            for (PipelineEdge up : g.edgesTo(from.id())) {
                // ⚠ Only a transform.route node's route:<key> edges are BRANCHES; a multi-schema
                // parser's route:<key> dispatch edges feed the flat lane's own per-schema trees and
                // counted as trunk before R3 — the trace must not change that (engagement, not
                // topology, is the question here).
                if (PipelineRel.isRoute(up.rel())) {
                    PipelineNode upFrom = byId.get(up.from());
                    if (upFrom != null && "transform.route".equals(upFrom.type())) return up.rel();
                    continue;
                }
                String rel = branchRelOf(g, byId, up, depth + 1);
                if (rel != null) return rel;
            }
        }
        return PipelineRel.DATA;
    }
}
