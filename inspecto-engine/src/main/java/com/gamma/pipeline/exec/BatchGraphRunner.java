package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.PartitionOutput;
import com.gamma.pipeline.NodeCategory;
import com.gamma.pipeline.PipelineGraph;
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
 * supplies the real {@link BranchCommitCoordinator.SourceFinalize} (the re-homed {@code BatchProcessor.commit}
 * finalize body: backup → markers LAST → ledger / watermark).
 */
@PublicApi(since = "4.3.0")
public final class BatchGraphRunner {

    private BatchGraphRunner() {}

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
     */
    public record Input(Connection conn, PipelineGraph graph, String seedNodeId, String seedTable,
                        String batchId, String dataDir, String baseName, Path branchCommitLog) {}

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
     * ingest path passes the re-homed {@code BatchProcessor.commit} body; a test may pass a no-op.
     */
    public static Result run(Input in, SourceFinalizer finalizer) throws Exception {
        PartitionSinkWriter writer =
                new PartitionSinkWriter(in.conn(), in.dataDir(), in.baseName(), in.batchId());
        BranchCommitCoordinator coordinator =
                new BranchCommitCoordinator(new BranchCommitLog(in.branchCommitLog().toString()));
        PipelineExecutor.ExecResult exec = PipelineExecutor.execute(
                in.conn(), in.graph(), in.seedNodeId(), in.seedTable(), in.batchId(),
                coordinator, writer, () -> finalizer.finalizeSource(writer.outputs()));
        return new Result(exec, writer.outputs(), writer.totalRows());
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
        return dataFedSinkCount(g) > 1;
    }

    /**
     * Count of data-fed sink <b>branches</b>: distinct {@code route:*} relations reaching a
     * {@code SINK}-category node, plus one for the trunk when any sink is reached by a plain
     * {@code data} edge. (N plain-data-fed sinks are one branch with N destinations — see
     * {@link #engages}.)
     */
    public static long dataFedSinkCount(PipelineGraph g) {
        java.util.Set<String> branches = new java.util.HashSet<>();
        for (var n : g.nodes()) {
            if (!PipelineNodeTypes.isCategory(n.type(), NodeCategory.SINK)) continue;
            for (var e : g.edgesTo(n.id())) {
                if (PipelineRel.isRoute(e.rel())) branches.add(e.rel());
                else if (PipelineRel.DATA.equals(e.rel())) branches.add(PipelineRel.DATA);
            }
        }
        return branches.size();
    }
}
