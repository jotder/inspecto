package com.gamma.signal;

/**
 * The home for dotted <b>Signal types</b> (operator, 2026-10-06 — completeness KPI §7-e).
 *
 * <p>⛔ Not {@link com.gamma.audit.EventType}: that class holds {@code Event.type} values ({@code UPPER_SNAKE}).
 * A {@link Signal} persists as an Event of type {@code SIGNAL} and its dotted type rides in the attributes,
 * so the two vocabularies stay in separate classes.
 *
 * <p>⚠ Deliberately open, like {@code EventType}: a constant here is a name, not a closed set. It starts
 * with the {@code kpi.completeness.*} types. ⛔ It holds CORE names only (operator, 2026-10-09): an optional
 * module's Signal types live in that module's own constants class ({@code ReconSignals}, {@code BackupSignals},
 * {@code ScreeningSignals}, {@code ScoringSignals}, {@code CaseSignals}, {@code OpsSignals}), so core never
 * names module vocabulary. Every value is pinned byte-for-byte by {@code SignalTypeTest} and the module tests.
 */
public final class SignalType {

    private SignalType() {}

    /** Every {@code kpi.completeness} run that produced an answer — status, volume, and the unknown-day bucket. */
    public static final String KPI_COMPLETENESS_EVALUATED = "kpi.completeness.evaluated";

    /** A {@code kpi.completeness} run whose day sits below its baseline by more than the tolerance. */
    public static final String KPI_COMPLETENESS_BREACHED = "kpi.completeness.breached";

    /** Three or more consecutive days with nothing registered — a WARN, never an Incident (operator, 2026-10-06). */
    public static final String KPI_COMPLETENESS_UNKNOWN_STREAK = "kpi.completeness.unknown_streak";

    /** Signal type {@code alert.evaluate.completed}. */
    public static final String ALERT_EVALUATE_COMPLETED = "alert.evaluate.completed";

    /** Signal type {@code la.detect.completed}. */
    public static final String LA_DETECT_COMPLETED = "la.detect.completed";

    /** Signal type {@code mail.sent}. */
    public static final String MAIL_SENT = "mail.sent";

    /** Signal type {@code publish.postgres.completed}. */
    public static final String PUBLISH_POSTGRES_COMPLETED = "publish.postgres.completed";

    /** Signal type {@code publish.postgres.refused}. */
    public static final String PUBLISH_POSTGRES_REFUSED = "publish.postgres.refused";

    /** Signal type {@code report.attach.refused}. */
    public static final String REPORT_ATTACH_REFUSED = "report.attach.refused";

    /** Signal type {@code sample.hello.completed}. */
    public static final String SAMPLE_HELLO_COMPLETED = "sample.hello.completed";

    /** Signal type {@code job.dataset.produced}. */
    public static final String JOB_DATASET_PRODUCED = "job.dataset.produced";

    /** Signal type {@code job.pack.loaded}. */
    public static final String JOB_PACK_LOADED = "job.pack.loaded";

    /** Signal type {@code job.pack.rejected}. */
    public static final String JOB_PACK_REJECTED = "job.pack.rejected";

    /** Signal type {@code job.pack.unloaded}. */
    public static final String JOB_PACK_UNLOADED = "job.pack.unloaded";

    /** Signal type {@code job.run.started}. */
    public static final String JOB_RUN_STARTED = "job.run.started";

    /** Signal type {@code job.run.completed}. */
    public static final String JOB_RUN_COMPLETED = "job.run.completed";

    /** Signal type {@code job.run.failed}. */
    public static final String JOB_RUN_FAILED = "job.run.failed";

    /** Signal type {@code job.run.rejected}. */
    public static final String JOB_RUN_REJECTED = "job.run.rejected";

    /** Signal type {@code job.chain.cut}. */
    public static final String JOB_CHAIN_CUT = "job.chain.cut";

    /** Signal type {@code pipeline.commit}. */
    public static final String PIPELINE_COMMIT = "pipeline.commit";

    /** Signal type {@code maintenance.filerepo.findings}. */
    public static final String MAINTENANCE_FILEREPO_FINDINGS = "maintenance.filerepo.findings";

    /** Signal type {@code maintenance.metadata.findings}. */
    public static final String MAINTENANCE_METADATA_FINDINGS = "maintenance.metadata.findings";

    /** Signal type {@code maintenance.storage.threshold}. */
    public static final String MAINTENANCE_STORAGE_THRESHOLD = "maintenance.storage.threshold";

    /** Signal type {@code maintenance.storage.trend}. */
    public static final String MAINTENANCE_STORAGE_TREND = "maintenance.storage.trend";

    /** Signal type {@code maintenance.scheduler.findings}. */
    public static final String MAINTENANCE_SCHEDULER_FINDINGS = "maintenance.scheduler.findings";

    /** Signal type {@code job.signal.refused}. */
    public static final String JOB_SIGNAL_REFUSED = "job.signal.refused";

    /** Signal type {@code la.index.build.completed} (Link Analysis is core, like {@link #LA_DETECT_COMPLETED}). */
    public static final String LA_INDEX_BUILD_COMPLETED = "la.index.build.completed";

    /**
     * Signal type {@code risk.score.produced} — a CROSS-MODULE contract: the scoring module emits it and core
     * ({@code CollectorService}) matches it, so it lives here; {@code ScoringSignals.RISK_SCORE_PRODUCED}
     * references this constant so the two cannot drift.
     */
    public static final String RISK_SCORE_PRODUCED = "risk.score.produced";
}
