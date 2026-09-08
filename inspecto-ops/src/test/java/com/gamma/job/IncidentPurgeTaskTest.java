package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code incident_purge} — Incident retention with legal-hold rules (BACKLOG D5 / MNT-14).
 *
 * <p><b>Moved here from {@code MaintenanceLibraryTest} in EDG-01 cell 7</b> (2026-09-08) with the task
 * itself. It needs a real {@code ObjectService}, which core can no longer construct, and the task is now
 * contributed by this module's {@code MaintenanceTaskProvider} rather than being a case in
 * {@code MaintenanceJob}'s switch.
 *
 * <p>⚠ Core kept ONE test in its place, asserting the opposite: without this module
 * {@code incident_purge} is an unknown task and is refused. The old "no object engine attached — nothing
 * to purge" no-op was right for a built-in with an optional engine and is wrong for an absent module —
 * a retention job that reports success while deleting nothing misleads the operator who scheduled it.
 */
class IncidentPurgeTaskTest {

    // ⚠ Package com.gamma.job, matching core's — MaintenanceJob, RunContext, RunLogStore and
    // RunArtifactStore are package-private there, so a split package is what lets this moved test drive
    // them unchanged. The same arrangement inspecto-events and inspecto-metrics use for com.gamma.control.

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("m", JobType.MAINTENANCE, null, null, true, false, params);
    }

    /** A real RunContext marked as a preview fire (MNT-1), audit files under {@code auditDir}. */
    private static JobContext dryCtx(Path auditDir) {
        RunContext ctx = new RunContext("r-dry", "default", "m", "manual", "r-dry", null, 0, Map.of(),
                new RunLogStore(auditDir.toString()), 100, new RunArtifactStore(auditDir.toString()));
        ctx.dryRun(true);
        return ctx;
    }

    /** An {@link com.gamma.ops.ObjectService} over in-memory stores, object store handed back for setup. */
    private record Ops(com.gamma.ops.ObjectService objects, com.gamma.ops.ObjectStore store) {}

    private static Ops ops() {
        com.gamma.ops.ObjectStore store = new com.gamma.ops.InMemoryObjectStore();
        return new Ops(new com.gamma.ops.ObjectService(store, Map.of(),
                new com.gamma.ops.link.InMemoryLinkStore(), new com.gamma.ops.note.InMemoryNoteStore(),
                new com.gamma.ops.tag.InMemoryTagAssignmentStore()), store);
    }

    /** Archive {@code o} {@code daysAgo} days back — retention is measured from {@code closedAt}. */
    private static String archivedDaysAgo(Ops ops, String title, int daysAgo) {
        var o = ops.objects.open(com.gamma.objects.ObjectType.INCIDENT, title, "d", "WARNING", null, Map.of());
        ops.objects.comment(o.id(), "alice", "note that must go with it");
        ops.store.update(o.withStatus("ARCHIVED",
                System.currentTimeMillis() - Duration.ofDays(daysAgo).toMillis(), true));
        return o.id();
    }

    @Test
    void incidentPurgeRemovesOnlyIncidentsPastTheirRetentionWindow(@TempDir Path audit) throws Exception {
        Ops ops = ops();
        String expired = archivedDaysAgo(ops, "expired", 400);
        String recent = archivedDaysAgo(ops, "recent", 10);
        try (com.gamma.util.Scheduler s = new com.gamma.util.Scheduler();
             JobService js = new JobService(List.of(), new com.gamma.etl.ConsignmentEventBus(), s, null, audit.toString())) {
            js.objects(ops.objects.access());
            JobConfig cfg = job(Map.of("task", "incident_purge", "retention_days", "90"));

            JobResult dry = new MaintenanceJob(cfg, null, audit.toString(), null, js).run(dryCtx(audit));
            assertTrue(dry.message().contains("would purge 1 incident(s)"), dry.message());
            assertTrue(ops.store.get(expired).isPresent(), "dry run must not purge");

            JobResult real = new MaintenanceJob(cfg, null, audit.toString(), null, js).run();
            assertTrue(real.message().contains("purged 1 incident(s) and 1 dependent row(s)"), real.message());
            assertTrue(ops.store.get(expired).isEmpty(), "the expired one is gone");
            assertTrue(ops.store.get(recent).isPresent(), "the one still inside its window is untouched");

            JobResult again = new MaintenanceJob(cfg, null, audit.toString(), null, js).run();
            assertTrue(again.message().contains("purged 0 incident(s)"), "idempotent: " + again.message());
        }
    }

    @Test
    void incidentPurgeReportsHeldIncidentsAsTheirOwnCountRatherThanSkippingSilently(@TempDir Path audit)
            throws Exception {
        // "12 eligible, 3 held" — an operator cannot trust a sweep whose arithmetic does not add up.
        Ops ops = ops();
        String held = archivedDaysAgo(ops, "held", 400);
        ops.store.update(ops.store.get(held).orElseThrow().withAttributes(
                Map.of(com.gamma.ops.ObjectService.ATTR_LEGAL_HOLD, "DPA-2026-14"), System.currentTimeMillis()));
        try (com.gamma.util.Scheduler s = new com.gamma.util.Scheduler();
             JobService js = new JobService(List.of(), new com.gamma.etl.ConsignmentEventBus(), s, null, audit.toString())) {
            js.objects(ops.objects.access());
            JobConfig cfg = job(Map.of("task", "incident_purge", "retention_days", "90"));

            JobResult dry = new MaintenanceJob(cfg, null, audit.toString(), null, js).run(dryCtx(audit));
            assertTrue(dry.message().contains("would purge 0 incident(s)"), dry.message());
            assertTrue(dry.message().contains("(1 held)"), "the hold is reported, not hidden: " + dry.message());

            new MaintenanceJob(cfg, null, audit.toString(), null, js).run();
            assertTrue(ops.store.get(held).isPresent(), "a held incident is never purged, however old");
        }
    }

    @Test
    void incidentPurgeWithNoObjectEngineAttachedIsANoOp(@TempDir Path audit) throws Exception {
        JobResult r = new MaintenanceJob(job(Map.of("task", "incident_purge", "retention_days", "90")))
                .run(dryCtx(audit));
        assertTrue(r.message().contains("no object engine attached"), r.message());
    }

    @Test
    void incidentPurgeRejectsAMissingOrNonsensicalRetentionWindow(@TempDir Path audit) {
        // retention_days is required, exactly like the *_prune tasks: forgetting must be deliberate, and a
        // defaulted window on the one task that hard-deletes business records would be indefensible.
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceJob(job(Map.of("task", "incident_purge"))).run(dryCtx(audit)));
        assertThrows(IllegalArgumentException.class, () -> new MaintenanceJob(
                job(Map.of("task", "incident_purge", "retention_days", "0"))).run(dryCtx(audit)));
    }
}
