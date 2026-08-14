package com.gamma.job;

import com.gamma.signal.SignalEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link JobContext} that keeps what a Run reported, so a test can assert on the findings
 * themselves rather than on a count.
 *
 * <p>⚠ <b>Why this exists.</b> A Job's findings do not travel in its {@link JobResult} — that record
 * carries a status, one human-readable message and a duration, and it is what
 * {@code JobRunLedger}/{@code DbJobRunStore} persist. The detail goes to {@link RunLog} (persisted
 * per-run) and to the Signal ledger. A test driving the ctx-less {@link Job#run()} overload therefore
 * throws away every finding and can only count them, which cannot tell "reported the right thing"
 * from "reported the wrong thing the right number of times". Drive {@link Job#run(JobContext)} with
 * this instead.
 *
 * <p>⚠ {@link #artifacts()} returns {@code null} deliberately — no Job under test records artifacts
 * yet, and a stub that silently swallowed them would hide the day one starts.
 */
final class CapturingJobContext implements JobContext {

    /** Every {@link RunLog#warn} message, in order. */
    final List<String> warnings = new ArrayList<>();
    /** Every emitted Signal's payload, each with its type under {@code __type}. */
    final List<Map<String, Object>> signals = new ArrayList<>();

    private final Map<String, String> params;
    private final boolean dryRun;

    CapturingJobContext() {
        this(Map.of(), false);
    }

    CapturingJobContext(Map<String, String> params, boolean dryRun) {
        this.params = params;
        this.dryRun = dryRun;
    }

    /** True when some warning contains {@code needle} — the usual "did it report X?" assertion. */
    boolean warned(String needle) {
        return warnings.stream().anyMatch(w -> w.contains(needle));
    }

    @Override public String runId() { return "run-1"; }
    @Override public String spaceId() { return "default"; }
    @Override public TriggerInfo trigger() { return null; }
    @Override public Map<String, String> config() { return Map.of(); }
    @Override public Map<String, String> params() { return params; }
    @Override public ArtifactRecorder artifacts() { return null; }
    @Override public boolean dryRun() { return dryRun; }

    @Override
    public RunLog log() {
        return new RunLog() {
            @Override public void info(String message, Object... kv) { }
            @Override public void warn(String message, Object... kv) { warnings.add(message); }
            @Override public void error(String message, Throwable t, Object... kv) { }
        };
    }

    @Override
    public SignalEmitter signals() {
        return (type, severity, payload) -> {
            Map<String, Object> got = new LinkedHashMap<>(payload);
            got.put("__type", type);
            signals.add(got);
        };
    }
}
