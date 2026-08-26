package com.gamma.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * The {@code incident_purge} maintenance task (BACKLOG D5 / MNT-14): physically remove {@code ARCHIVED}
 * Incidents whose retention window has expired, together with their notes, attachments, links and tag edges.
 *
 * <p><b>Named {@code purge}, not {@code *_prune} like every sibling.</b> The blast radius genuinely
 * differs: the {@code *_prune} tasks trim housekeeping telemetry (fingerprints, receipts, run logs),
 * whereas this deletes operator business records irreversibly. A distinct verb keeps an operator from
 * reading it as another log trim.
 *
 * <p>Retention is <b>derived</b>: an Incident is eligible once {@code closedAt + retention_days} has
 * passed, where {@code closedAt} is the archive timestamp the terminal transition already stamps. No
 * schema change, and no per-object expiry to keep in step. ⚠ The trade-off, accepted consciously:
 * shortening {@code retention_days} later retroactively makes older records eligible, so the sweep does
 * not honour "what was promised when this was archived". If that guarantee is ever needed it becomes a
 * stamped attribute — cheap to add then, so it is not pre-built.
 *
 * <p>Scoped to {@link com.gamma.ops.ObjectType#INCIDENT} because {@code ARCHIVED} exists only in the
 * Incident workflow today. Generalise when a second type gains a terminal archive state; inventing
 * retention policy for Cases and Alerts now would be policy without a requirement.
 *
 * <p>⚠ <b>A purge is not "all trace removed".</b> The event ledger is append-only, so each purged
 * Incident's {@code OBJECT_ACTIVITY} history — including its own purge record — survives it. The audit
 * log is not the record being retention-managed. This is a stated decision (MNT-14 G3), not an
 * oversight, and it is the first question a legal/DPA reviewer asks.
 *
 * <p>Legal hold: an Incident carrying {@link com.gamma.ops.ObjectService#ATTR_LEGAL_HOLD} is never
 * purged however old it is, and is reported as its own count ("12 eligible, 3 held") rather than
 * silently skipped — an operator cannot trust a sweep whose arithmetic does not add up.
 */
final class IncidentPurgeTask {

    private static final Logger log = LoggerFactory.getLogger(IncidentPurgeTask.class);

    private IncidentPurgeTask() {}

    static JobResult run(JobConfig cfg, JobService host, boolean dryRun) {
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("incident_purge retention_days must be >= 1");
        int limit = Integer.parseInt(cfg.opt("max_count", "1000"));  // bound one run's blast radius
        if (limit < 1) throw new IllegalArgumentException("incident_purge max_count must be >= 1");
        long t0 = System.nanoTime();
        // Fail-open on no Object Engine, like every sibling task. There is no partial-attachment case to
        // guard: ObjectService holds all four stores it cascades over, so it is present or absent whole.
        var engine = host == null
                ? java.util.Optional.<com.gamma.ops.ObjectService>empty() : host.objects();
        if (engine.isEmpty())
            return JobResult.ok("incident_purge: no object engine attached — nothing to purge", 0L);
        com.gamma.ops.ObjectService objects = engine.get();

        long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        List<com.gamma.ops.OperationalObject> candidates =
                objects.purgeEligible(com.gamma.ops.ObjectType.INCIDENT, "ARCHIVED", cutoff, limit);
        List<com.gamma.ops.OperationalObject> purgeable = candidates.stream()
                .filter(o -> !com.gamma.ops.ObjectService.hasLegalHold(o)).toList();
        int held = candidates.size() - purgeable.size();
        String scope = " archived more than " + days + "d ago"
                + (held == 0 ? "" : " (" + held + " held)");

        if (dryRun)
            return JobResult.ok("incident_purge[dry-run]: would purge " + purgeable.size()
                    + " incident(s)" + scope, (System.nanoTime() - t0) / 1_000_000L);

        int purged = 0, dependents = 0;
        for (com.gamma.ops.OperationalObject o : purgeable) {
            // Re-checked inside purge() against the live row: a hold applied between the query above and
            // this loop must still win. A refusal is that hold working, so it is logged, not fatal.
            try {
                var outcome = objects.purge(o.id(), "incident_purge");
                purged++;
                dependents += outcome.dependents();
            } catch (RuntimeException e) {
                log.warn("incident_purge: skipped {} — {}", o.id(), e.getMessage());
            }
        }
        return JobResult.ok("incident_purge: purged " + purged + " incident(s) and " + dependents
                + " dependent row(s)" + scope, (System.nanoTime() - t0) / 1_000_000L);
    }
}
