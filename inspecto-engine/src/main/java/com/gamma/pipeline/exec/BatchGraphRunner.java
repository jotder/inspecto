package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.PartitionOutput;
import com.gamma.pipeline.PipelineGraph;

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
        PartitionSinkWriter writer = new PartitionSinkWriter(in.conn(), in.dataDir(), in.baseName());
        BranchCommitCoordinator coordinator =
                new BranchCommitCoordinator(new BranchCommitLog(in.branchCommitLog().toString()));
        PipelineExecutor.ExecResult exec = PipelineExecutor.execute(
                in.conn(), in.graph(), in.seedNodeId(), in.seedTable(), in.batchId(),
                coordinator, writer, () -> finalizer.finalizeSource(writer.outputs()));
        return new Result(exec, writer.outputs(), writer.totalRows());
    }
}
