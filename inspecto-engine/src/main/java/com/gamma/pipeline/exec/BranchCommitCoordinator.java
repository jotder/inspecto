package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;

import java.util.Set;

/**
 * <b>T11 — the commit-split.</b> Splits a batch commit into <b>per-branch</b> commit
 * (register + manifest, one branch at a time) and a single <b>source-finalisation</b>
 * (backup → markers LAST → ledger / watermark LAST) that runs <em>only after every branch is durable</em>
 * — generalising the legacy single-output {@code BatchProcessor.commit} to a branch-aware flow without
 * losing its crash-ordering invariant.
 *
 * <p>Driven by {@link BranchCommitLog} (partial-commit state), the coordinator is <b>idempotent and
 * crash-safe</b>:
 * <ul>
 *   <li>a branch already recorded {@code BRANCH}-committed is skipped on replay (branch outputs are
 *       written with overwrite-or-ignore, so re-running a partially-committed batch is safe);</li>
 *   <li>source-finalisation runs <b>exactly once</b>, and only once <em>all</em> expected branches are
 *       committed — a crash between the last branch and finalisation is recovered by re-running, which
 *       finalises without re-committing any branch.</li>
 * </ul>
 *
 * <p>A single-branch flow (one expected branch) reduces to "commit the one branch, then finalise the
 * source" — the same observable sequence as today, so the legacy path's behaviour is preserved.
 */
@PublicApi(since = "4.0.0")
public final class BranchCommitCoordinator {

    /** Write one branch's outputs + manifest durably (idempotent — overwrite-or-ignore). */
    @FunctionalInterface
    public interface BranchCommit {
        void commit(String branch) throws Exception;
    }

    /** Finalise the source files for the batch: backup → markers LAST → ledger / watermark LAST. */
    @FunctionalInterface
    public interface SourceFinalize {
        void finalizeSource() throws Exception;
    }

    /** What a {@link #commit} call actually did (for audit / tests). */
    public record Result(java.util.List<String> committedBranches, boolean sourceFinalized) {}

    private final BranchCommitLog log;
    private final String writeScope;

    public BranchCommitCoordinator(BranchCommitLog log) {
        this(log, "");
    }

    /**
     * With a <b>write scope</b> — the discriminator that keeps branch keys unique when ONE batch performs
     * SEVERAL writes. The chunked and segmented ingest paths call the write seam once per chunk / per
     * segment, and those writes reuse the same sink node ids; without a scope the second write's branches
     * would look already committed and be skipped, so the batch would silently lose every chunk after the
     * first.
     *
     * <p>The scope qualifies only the LEDGER key — {@code branchCommit} still receives the bare branch id,
     * because that is the graph node the caller has to write. An empty scope (the whole-batch case, and
     * every route pipeline) records exactly the keys it always did, so the drain — which reads bare sink
     * ids back out of the log — and {@code BatchProcessor.commit}'s single-log cleanup are untouched.
     */
    public BranchCommitCoordinator(BranchCommitLog log, String writeScope) {
        this.log = log;
        this.writeScope = writeScope == null ? "" : writeScope;
    }

    /**
     * Commit {@code batchId} across {@code expectedBranches}, then finalise the source once all are durable.
     *
     * @param batchId          the batch being committed
     * @param expectedBranches every branch this batch must commit (e.g. one {@code sink} per route);
     *                         finalisation is gated on all of them
     * @param branchCommit     writes a single branch's outputs + manifest (called once per not-yet-committed branch)
     * @param sourceFinalize   the source-finalisation step (markers LAST); called at most once, only when all done
     * @return what happened — the branches committed in this call + whether the source was finalised here
     */
    public Result commit(String batchId, Set<String> expectedBranches,
                         BranchCommit branchCommit, SourceFinalize sourceFinalize) throws Exception {
        java.util.function.Function<String, String> key =
                branch -> writeScope.isEmpty() ? branch : writeScope + "::" + branch;
        if (expectedBranches.isEmpty())
            throw new IllegalArgumentException("batch '" + batchId + "' has no branches to commit");

        java.util.List<String> committedNow = new java.util.ArrayList<>();
        Set<String> already = log.committedBranches(batchId);
        for (String branch : expectedBranches) {
            if (already.contains(key.apply(branch))) continue;   // idempotent replay — already durable
            branchCommit.commit(branch);
            log.recordBranch(batchId, key.apply(branch));        // durable: this branch is committed
            committedNow.add(branch);
        }

        boolean finalizedNow = false;
        Set<String> expectedKeys = new java.util.LinkedHashSet<>();
        for (String branch : expectedBranches) expectedKeys.add(key.apply(branch));
        boolean allCommitted = log.committedBranches(batchId).containsAll(expectedKeys);
        if (allCommitted && !log.isSourceFinalized(batchId)) {
            sourceFinalize.finalizeSource();            // backup -> markers LAST -> ledger/watermark LAST
            log.recordSourceFinalized(batchId);
            finalizedNow = true;
        }
        return new Result(committedNow, finalizedNow);
    }
}
