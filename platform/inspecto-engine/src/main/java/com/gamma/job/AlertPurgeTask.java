package com.gamma.job;

import java.time.Duration;
import java.time.Instant;

/**
 * The {@code alert_purge} maintenance task (MODULE-REORG-P7-INCIDENTS, "retention of resolved Alert rows"): physically
 * delete {@code RESOLVED} Alert rows whose own {@code closedAt} is older than {@code retention_days}, so the
 * Alert-owned store stops growing without bound on every edition (Personal included).
 *
 * <p>A <b>base</b> task - an Alert exists on every edition, so this is a case of {@link MaintenanceJob}'s switch like
 * {@code event_prune}, not a module-contributed provider. Alerts carry no module gate.
 *
 * <p>Rules, all by design:
 * <ul>
 *   <li>{@code retention_days} defaults to 90 when absent. Present-but-blank, {@code 0}, negative or non-numeric is
 *       <b>refused</b> - a mistyped window must never read as "delete everything".</li>
 *   <li>{@code OPEN} and {@code ACKNOWLEDGED} Alerts are never touched: they carry de-duplication state, and a deleted
 *       active row would let its rule fire again. A resolved row closed exactly at the cutoff, or with no close
 *       time, is kept.</li>
 *   <li>Age only. An Alert's {@code incident_id} is a text reference to a row in another store; nothing reads an
 *       Alert row back through an Incident (the Incident's {@code ESCALATED_FROM} edge has no node behind it by
 *       design), so a purged Alert leaves a harmless dangling reference.</li>
 *   <li>Without an Alert engine attached the task <b>fails</b> rather than reporting success: a retention control
 *       that deletes nothing and says "ok" is worse than one that fails (the {@code incident_purge} lesson).</li>
 *   <li>Ships <b>unscheduled</b>: nothing runs it until an operator adds a maintenance Job with
 *       {@code task: alert_purge}.</li>
 * </ul>
 */
final class AlertPurgeTask {

    static final int DEFAULT_RETENTION_DAYS = 90;

    private AlertPurgeTask() {}

    static JobResult run(JobConfig cfg, JobService host, boolean dryRun) {
        long days = retentionDays(cfg);
        long t0 = System.nanoTime();
        var alerts = host == null ? java.util.Optional.<com.gamma.alert.AlertService>empty() : host.alertService();
        if (alerts.isEmpty())
            throw new IllegalStateException("alert_purge: no Alert engine attached - refusing to report a purge that did not run");
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        var p = alerts.get().purgeResolved(cutoff, dryRun);
        String kept = p.keptResolved() + " resolved within retention, " + p.keptActive() + " open/acknowledged kept";
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (dryRun)
            return JobResult.ok("alert_purge[dry-run]: would delete " + p.purged() + " resolved Alert(s) closed more than "
                    + days + "d ago (" + kept + ")", ms);
        if (p.purged() > 0)
            host.attachedEventLog().ifPresent(log -> log.emit(com.gamma.audit.Event.builder(com.gamma.audit.EventType.AUDIT)
                    .source("job").message("alert_purge deleted " + p.purged() + " resolved Alert(s) closed before " + cutoff)
                    .actor("job:" + cfg.name()).actorType("system")
                    .action("alerts.purged").actionCategory("retention")
                    .target("job", cfg.name())
                    .attr("purge_before", cutoff).attr("alerts_removed", p.purged()).attr("retention_days", days)));
        return JobResult.ok("alert_purge: deleted " + p.purged() + " resolved Alert(s) closed more than " + days
                + "d ago (" + kept + ")", ms);
    }

    private static long retentionDays(JobConfig cfg) {
        String raw = cfg.params().get("retention_days");
        if (raw == null) return DEFAULT_RETENTION_DAYS;
        long days;
        try {
            days = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("alert_purge retention_days must be a whole number of days >= 1, got '" + raw + "'");
        }
        if (days < 1)
            throw new IllegalArgumentException("alert_purge retention_days must be >= 1 (got " + days + "): a window of zero would delete every resolved Alert");
        return days;
    }
}
