package com.gamma.job;

import com.gamma.signal.SignalEmitter;
import com.gamma.util.RunLog;

import java.util.Map;

/**
 * Everything one {@link Job} run may read. A narrow façade the framework populates per Run and
 * passes to {@link Job#run(JobContext)} — never the whole hosting service. Introduced in the P0
 * job-framework refactor ({@code docs/job-framework-design.md} §6.2); the data-plane façade that
 * design named {@code JobServices} is {@link #services()} (platform-services plan, 2026-08-09).
 */
public interface JobContext {

    /** This run's id (matches the {@link JobRun#runId()} recorded for it). */
    String runId();

    /** The space this run executes under (per-space MDC routing). */
    String spaceId();

    /** How this run was started — {@code cron} / {@code event} / {@code manual} / {@code catch-up} + detail. */
    TriggerInfo trigger();

    /** The Job's own configuration (the {@code *_job.toon} params). Read-only. */
    Map<String, String> config();

    /**
     * The resolved runtime Parameters for this Run (R2/R3, §7): the Job Type's declared
     * {@link ParameterDecl}s resolved through config → deduce ({@code $}-context) → default. Empty when
     * the Job Type declares no parameters. Distinct from {@link #config()} (the raw authored {@code params:}).
     */
    Map<String, String> params();

    /** Structured, persisted per-run logging (R5). */
    RunLog log();

    /** Emit domain {@link com.gamma.signal.Signal}s onto the one ledger (R6); framework-stamped. */
    SignalEmitter signals();

    /** Record queryable Run Artifacts — produced Datasets / files (R7, §10). */
    ArtifactRecorder artifacts();

    /**
     * X2 cross-lane provenance — a Job reports the source Consignments it READ (the at-rest readers call
     * this per {@code source_store} view with the files the selector kept, mapped through the output
     * registry). The framework persists them beside the run row so the trail crosses the Stage-1 →
     * Stage-2 boundary by recorded identity instead of by store-path convention. Default no-op: a harness
     * or a job that reads no store has nothing to say, and silence here means "unknown", never "none".
     */
    default void readConsignments(java.util.List<com.gamma.consignment.ConsignmentSource> sources) {}

    /**
     * Whether this Run is a <b>dry run</b> (System Maintenance MNT-1, "Safe by Default"): a preview
     * fire that must mutate nothing. A destructive Job Type honours it by reporting the affected
     * objects and estimated impact instead of acting; one that cannot preview must do nothing and
     * say so — never fall through to the real action. Only manual triggers can request it
     * ({@code POST /jobs/{name}/trigger?dryRun=true}); cron/event/signal fires are always real.
     */
    default boolean dryRun() {
        return false;
    }

    /**
     * The {@link PlatformServices} granted to this Run (platform-services plan §3): a typed lookup
     * filtered to the Job Type's declared {@code requires:} list — a service that exists but was not
     * declared is invisible here. Empty until grant declaration lands (plan S1-2).
     */
    default PlatformServices services() {
        return PlatformServices.none();
    }
}
