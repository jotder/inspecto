package com.gamma.job;

import java.util.Map;

/**
 * DUCKLE-C1 residual (1): the minute-cadence {@code alert.evaluate} instance, scoped to
 * {@code freshness}, that the platform arms while any Alert Rule with {@code maximumAge} exists and
 * disarms when the last one goes.
 *
 * <p><b>Why a system job, not a thread.</b> A freshness rule is the one check whose trigger is the
 * clock, so without a sweep it never fires between batches — and before this, a rule was only as good
 * as the operator who remembered to schedule {@code alert.evaluate} for it. The cadence seam already
 * existed ({@link AlertEvaluateJob}, {@code scope: freshness}); arming it through
 * {@link JobService#upsertSystemJob} gives it a run ledger, a next-fire time, cross-pod arming claims
 * and a row in {@code GET /jobs} — everything a hidden thread would lack.
 *
 * <p><b>Idempotent across restarts</b> because nothing is persisted: the job is re-derived from the
 * armed rules on every boot and on every rule change, and re-arming an identical config is a no-op.
 */
public final class FreshnessSweep {

    /** The system job's name. ⚠ Contains a '.', which an authored job name may also contain — an authored
     *  job holding it first wins, and the sweep is refused with a WARN rather than clobbering it. */
    public static final String JOB_NAME = "system.freshness-sweep";

    private FreshnessSweep() {}

    /** The sweep's config: every minute, freshness rules only, no catch-up (a missed minute is not owed). */
    static JobConfig config() {
        return new JobConfig(JOB_NAME, "alert.evaluate", "* * * * *", null, true, false,
                Map.of("scope", "freshness"), null, null);
    }

    /** Arm the sweep when {@code wanted}, disarm it otherwise. Safe to call any number of times. */
    public static void reconcile(JobService jobs, boolean wanted) {
        if (jobs == null) return;
        if (wanted) jobs.upsertSystemJob(config());
        else jobs.removeSystemJob(JOB_NAME);
    }
}
