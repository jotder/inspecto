package com.gamma.backup;

import com.gamma.job.JobResult;
import com.gamma.job.MaintenanceTaskContext;
import com.gamma.job.MaintenanceTaskProvider;

import java.util.Set;

/**
 * Contributes {@code backup} / {@code backup_verify} / {@code restore} to the core's {@code maintenance} Job
 * Type through the {@link MaintenanceTaskProvider} seam (EDG-01 cell 2, 2026-09-07).
 *
 * <p>This is the whole of the edition gate for EDITIONS {@code OPS-06}: the three tasks exist wherever this
 * jar is on the classpath ({@code META-INF/services/com.gamma.job.MaintenanceTaskProvider}) and are unknown
 * tasks everywhere else. There is no flag — the classpath entry IS the switch, exactly as it is for
 * {@code inspecto-security} and {@code inspecto-policy}.
 *
 * <p>The dispatch below is the three {@code case} arms that used to sit in {@code MaintenanceJob}'s switch,
 * moved verbatim: same arguments, same dry-run handling, same {@link BackupTask} methods.
 */
public final class BackupTaskProvider implements MaintenanceTaskProvider {

    @Override
    public Set<String> tasks() {
        return Set.of("backup", "backup_verify", "restore");
    }

    @Override
    public JobResult run(String task, MaintenanceTaskContext ctx) throws Exception {
        return switch (task) {
            case "backup_verify" -> BackupTask.verify(ctx.cfg(), ctx.jobCtx());
            case "backup"        -> BackupTask.backup(ctx.cfg(), ctx.jobCtx(), ctx.dryRun(), ctx.dataDir());
            case "restore"       -> BackupTask.restore(ctx.cfg(), ctx.jobCtx(), ctx.dryRun());
            // Unreachable while tasks() and this switch agree; loud rather than a silent SKIPPED if they drift.
            default -> throw new IllegalArgumentException(getClass().getSimpleName() + " does not run '" + task + "'");
        };
    }
}
