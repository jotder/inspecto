package com.gamma.etl;

import com.gamma.api.PublicApi;

import java.util.List;

/**
 * Fired when a batch is committed — the trigger signal for downstream stages
 * (e.g. Stage-2 enrichment recomputing the partitions this batch wrote).
 *
 * <p>Emitted by {@link ConsignmentAuditWriter} on a {@code SUCCESS} flush (after the audit
 * rows and commit-log line are written, so the event implies durability). The
 * {@code service} layer owns the pub/sub bus; this record lives in {@code etl} so the
 * low layer can emit without depending on the higher one.
 *
 * <p>Emitted for every <em>terminal</em> batch (both {@code SUCCESS} and {@code FAILED})
 * so observability sees error rates and latency; consumers that act only on success
 * (Stage-2 enrichment) filter on {@link #status()}.
 *
 * <p>Since v3.7.0 the event also carries <em>error detail</em> ({@link #error()},
 * {@link #offendingFile()}, {@link #errorRows()}) so the optional assist agent's failure-diagnosis
 * reactor (M7) has something to reason about on a {@code FAILED} batch. These are operational
 * metadata (an error message / a filename / a count), never row content. The detail is populated at
 * the emission site ({@link ConsignmentAuditWriter#flush}); the 7-arg {@linkplain
 * #ConsignmentEvent(String, String, String, List, long, long, int) back-compat constructor} (used by the
 * Stage-2 enrichment emitters, which only ever announce {@code SUCCESS}) defaults them to
 * {@code null}/{@code null}/{@code 0}.
 *
 * @param pipeline      pipeline name
 * @param batchId       committed batch id
 * @param status        batch status ({@code SUCCESS} or {@code FAILED})
 * @param partitions    distinct output partition paths this batch wrote (from lineage),
 *                      e.g. {@code event_type=CALL/year=2020/month=04/day=03} — exactly
 *                      the set a Stage-2 enrichment recompute should be scoped to
 *                      (empty for a failed batch)
 * @param outputRows    total rows written by the batch
 * @param durationMs    batch wall-clock duration in milliseconds
 * @param rejectedCount rejected (quarantined) member files in the batch
 * @param error         batch-level error message ({@code null}/blank when none), e.g. on a FAILED batch
 * @param offendingFile the first member file that errored/was rejected ({@code null} when none)
 * @param errorRows     total rows that failed to parse across the batch's member files
 * @param dryRun        <b>this batch was SIMULATED</b> (PIPELINE-DRYRUN-1 step 5): the ingest lane ran
 *                      with its mutating sites suppressed, so nothing was written — no outputs, no audit
 *                      or commit-log rows, no provenance row, no markers, no backup/ledger moves.
 *                      <p>🔴 <b>Every consumer of a published event MUST either honour this flag or refuse
 *                      loudly.</b> A consumer that ignores it acts for real on a run that did not happen,
 *                      and the flag becomes a lie — the same failure mode {@code DryRunServices}'s javadoc
 *                      states for Platform Services. The consumers as built: {@code JobService} fires the
 *                      chained Job with {@code dryRun=true} (it runs dry too), while
 *                      {@code AlertService}, {@code CollectorService}'s event-log bridge,
 *                      {@code PipelineScheduler.onUpstreamCommit} and {@code EnrichmentService} refuse and
 *                      log what they would have done.
 */
@PublicApi(since = "4.0.0")
public record ConsignmentEvent(String pipeline, String batchId, String status,
                         List<String> partitions, long outputRows,
                         long durationMs, int rejectedCount,
                         String error, String offendingFile, long errorRows,
                         boolean dryRun) {

    /**
     * Back-compat constructor (pre-v3.7.0 shape) — defaults the error detail to
     * {@code null}/{@code null}/{@code 0}. Used by the Stage-2 enrichment emitters
     * ({@code EnrichmentService}/{@code EnrichJob}), which announce only {@code SUCCESS} commits.
     *
     * <p>⚠ Unrelated to {@link #dryRun()}: these emitters have no dry-run mode of their own, so a real
     * enrichment commit is exactly what they announce.
     */
    public ConsignmentEvent(String pipeline, String batchId, String status,
                      List<String> partitions, long outputRows,
                      long durationMs, int rejectedCount) {
        this(pipeline, batchId, status, partitions, outputRows, durationMs, rejectedCount,
                null, null, 0L, false);
    }
}
