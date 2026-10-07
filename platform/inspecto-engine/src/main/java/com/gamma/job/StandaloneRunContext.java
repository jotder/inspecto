package com.gamma.job;

import com.gamma.signal.SignalEmitter;
import com.gamma.util.RunLog;

import java.util.Map;

/**
 * A minimal {@link JobContext} for work that runs <b>outside {@link JobService}</b> — the CLI entry
 * points, a legacy no-arg {@code Job.run()}, and the Collector's own poll cycle.
 *
 * <p>🔴 <b>Why this exists.</b> Those paths had no {@code JobContext} at all, so they wrote a NULL
 * {@code run_id} into {@code consignment_outputs} — and one NULL path is enough to make a unique key over
 * that table a silent no-op, because NULL ≠ NULL in a UNIQUE constraint on both DuckDB and Postgres. They
 * needed a Run identity, and the operator's choice (2026-09-13,
 * {@code docs/superpower/run-model-plan.md} §6) was to give them a real one from the single
 * {@link RunIds} generator rather than mint a second dialect of "run id" at the Collector.
 *
 * <p>⚠ <b>It is deliberately inert, not a stub-by-omission.</b> There is no run log to write to, no
 * signal bus and no artifact store on these paths — a Run that nothing scheduled has nowhere to report.
 * So logging is dropped, signals are dropped and artifacts are refused. ⛔ Do NOT "improve" this by
 * wiring it to the real stores: a standalone context that recorded runs would put CLI invocations into
 * {@code job_runs} as though the scheduler had run them, which is a different claim entirely.
 *
 * <p>⚠ {@link #dryRun()} is always {@code false}: these paths are invoked to do work, and a context that
 * silently reported a dry run would make them no-ops.
 */
public final class StandaloneRunContext implements JobContext {

    private final String runId;
    private final String jobName;

    private StandaloneRunContext(String runId, String jobName) {
        this.runId = runId;
        this.jobName = jobName;
    }

    /** A context carrying a freshly minted Run id for {@code jobName}. */
    public static JobContext forJob(String jobName) {
        return new StandaloneRunContext(RunIds.next(jobName), jobName);
    }

    @Override public String runId()               { return runId; }
    @Override public String spaceId()             { return "default"; }
    @Override public TriggerInfo trigger()        { return TriggerInfo.parse("manual"); }
    @Override public Map<String, String> config() { return Map.of(); }
    @Override public Map<String, String> params() { return Map.of(); }
    @Override public boolean dryRun()             { return false; }

    @Override
    public RunLog log() {
        return new RunLog() {
            @Override public void info(String m, Object... kv) { }
            @Override public void warn(String m, Object... kv) { }
            @Override public void error(String m, Throwable t, Object... kv) { }
        };
    }

    @Override
    public SignalEmitter signals() {
        return (type, severity, payload) -> { };
    }

    /**
     * ⚠ A no-op recorder, NOT a throw. It threw on the first attempt, and that was wrong twice over:
     * callers such as {@code PipelineJobRunner.execute} resolve {@code ctx.artifacts()} unconditionally,
     * so throwing turned every legacy-path run into a failure; and it was inconsistent with
     * {@link #log()} and {@link #signals()}, which drop their input for exactly the same reason. Dropping
     * is the contract this whole context keeps — there is nowhere to record to.
     */
    @Override
    public ArtifactRecorder artifacts() {
        return new ArtifactRecorder() {
            @Override public void dataset(String name, String datasetRef, ResultSetMeta resultSet,
                                          long rows, java.time.Instant watermark) { }
            @Override public void file(String name, java.nio.file.Path path, long bytes) { }
        };
    }

    @Override
    public String toString() {
        return "StandaloneRunContext[" + jobName + ", runId=" + runId + "]";
    }
}
