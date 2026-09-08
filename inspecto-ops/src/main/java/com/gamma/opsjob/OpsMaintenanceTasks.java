package com.gamma.opsjob;

import com.gamma.job.JobResult;
import com.gamma.job.MaintenanceTaskContext;
import com.gamma.job.MaintenanceTaskProvider;

import java.util.Set;

/**
 * Contributes {@code incident_purge} to the maintenance library (EDG-01 cell 7, 2026-09-08).
 *
 * <p>Incident retention with legal-hold rules is operational-object domain, so it left mandatory core with
 * the rest of {@code com.gamma.ops}. This is the <b>first</b> reuse of the {@code MaintenanceTaskProvider}
 * seam cell 2 built for OPS-06.
 *
 * <p>⚠ The named {@code case} for this task had to be <b>removed</b> from {@code MaintenanceJob}'s switch,
 * not merely duplicated here: a named case always beats a contributed provider, so leaving it would have
 * silently kept the built-in on every edition and this provider would never have run.
 *
 * <p>⛔ On a Personal build the module is absent, so no provider claims the name and
 * {@code task: incident_purge} is an <b>unknown task, refused loudly</b> — the same posture cell 2 chose
 * for {@code backup}. A silent skip would let a scheduled retention job report success while deleting
 * nothing, which for a retention control is worse than failing.
 */
public final class OpsMaintenanceTasks implements MaintenanceTaskProvider {

    @Override
    public Set<String> tasks() {
        return Set.of("incident_purge");
    }

    @Override
    public JobResult run(String task, MaintenanceTaskContext ctx) {
        return IncidentPurgeTask.run(ctx.cfg(), ctx.host(), ctx.dryRun());
    }
}
