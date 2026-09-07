package com.gamma.job;

/**
 * What a {@link MaintenanceTaskProvider} is handed: exactly the values the built-in switch in
 * {@link MaintenanceJob} has in scope, and nothing the switch does not — so a task moved out of the core
 * neither gains nor loses anything by moving.
 *
 * @param cfg      the authored job config ({@code task:} and the task's own keys)
 * @param jobCtx   the run's {@link JobContext} — log, signals, artifacts, dry-run flag. ⚠ {@code null} when
 *                 the job was run without one ({@link MaintenanceJob#run()}); every built-in tolerates
 *                 that and a provider must too
 * @param dryRun   MNT-1: {@code true} ⇒ preview only, write nothing
 * @param dataDir  the space's data root, or {@code null} when none is configured
 * @param auditDir the space's audit root, or {@code null}
 * @param runStore the job-run reporting store, or {@code null} when reporting is off
 * @param host     the owning {@link JobService}, or {@code null} under a bare harness
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public record MaintenanceTaskContext(JobConfig cfg, JobContext jobCtx, boolean dryRun, String dataDir,
                                     String auditDir, DbJobRunStore runStore, JobService host) {
}
