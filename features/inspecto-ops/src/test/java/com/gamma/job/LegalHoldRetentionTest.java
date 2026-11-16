package com.gamma.job;

import com.gamma.alert.Alert;
import com.gamma.alert.AlertLifecycle;
import com.gamma.alert.AlertRecords;
import com.gamma.alert.AlertService;
import com.gamma.alert.AlertStore;
import com.gamma.alert.InMemoryAlertStore;
import com.gamma.audit.Event;
import com.gamma.audit.EventQuery;
import com.gamma.audit.EventStore;
import com.gamma.ops.ObjectService;
import com.gamma.ops.link.LinkRelationship;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LEGAL-HOLD-SCOPE-1 — the space-wide retention tasks skip a held Incident's evidence and take it on the first run
 * after the hold is released: {@code incident_purge} (an Incident in a held Case), {@code alert_purge} (the Alerts
 * escalated into a held Incident) and {@code event_prune} (the audit days from the held evidence's first day on).
 * Each runs through {@link MaintenanceJob} over a real {@link ObjectService}, the way a scheduled Job runs it.
 */
class LegalHoldRetentionTest {

    private static final long NOW = System.currentTimeMillis();

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("m", JobType.MAINTENANCE, null, null, true, false, params);
    }

    private record Ops(ObjectService objects, com.gamma.ops.ObjectStore store) {}

    private static Ops ops() {
        com.gamma.ops.ObjectStore store = new com.gamma.ops.InMemoryObjectStore();
        return new Ops(new ObjectService(store, Map.of(), new com.gamma.ops.link.InMemoryLinkStore(),
                new com.gamma.ops.note.InMemoryNoteStore(), new com.gamma.ops.tag.InMemoryTagAssignmentStore()), store);
    }

    private static void hold(Ops ops, String id, String value) {
        ops.store.update(ops.store.get(id).orElseThrow().withAttributes(Map.of(ObjectService.ATTR_LEGAL_HOLD, value), NOW));
    }

    private static JobService jobs(Path audit, com.gamma.util.Scheduler s) {
        return new JobService(List.of(), new com.gamma.etl.ConsignmentEventBus(), s, null, audit.toString());
    }

    @Test
    void incidentPurgeSkipsAnIncidentWhoseCaseIsHeldAndPurgesItOnceReleased(@TempDir Path audit) throws Exception {
        Ops ops = ops();
        var inc = ops.objects.open(ObjectType.INCIDENT, "old", "d", "WARNING", null, Map.of());
        ops.store.update(inc.withStatus("ARCHIVED", NOW - Duration.ofDays(400).toMillis(), true));
        var kase = ops.objects.open(ObjectType.CASE, "litigation", "d", "HIGH", null, Map.of());
        ops.objects.link(kase.id(), inc.id(), LinkRelationship.CONTAINS, null);
        hold(ops, kase.id(), "LIT-7");
        try (com.gamma.util.Scheduler s = new com.gamma.util.Scheduler(); JobService js = jobs(audit, s)) {
            js.objects(ops.objects.access());
            JobConfig cfg = job(Map.of("task", "incident_purge", "retention_days", "90"));

            JobResult run = new MaintenanceJob(cfg, null, audit.toString(), null, js).run();
            assertTrue(run.message().contains("purged 0 incident(s)") && run.message().contains("(1 held)"), run.message());
            assertTrue(ops.store.get(inc.id()).isPresent(), "an Incident in a held Case is held");
            assertThrows(com.gamma.objects.LegalHoldException.class, () -> ops.objects.purge(inc.id(), "op"),
                    "the purge chokepoint refuses it too");

            hold(ops, kase.id(), "false");
            run = new MaintenanceJob(cfg, null, audit.toString(), null, js).run();
            assertTrue(run.message().contains("purged 1 incident(s)"), run.message());
            assertTrue(ops.store.get(inc.id()).isEmpty(), "released: normal retention is back");
        }
    }

    @Test
    void alertPurgeKeepsTheAlertsOfAHeldIncidentUntilTheHoldIsReleased(@TempDir Path audit) throws Exception {
        Ops ops = ops();
        var inc = ops.objects.open(ObjectType.INCIDENT, "fraud", "d", "HIGH", null, Map.of());
        hold(ops, inc.id(), "DPA-2026-14");
        InMemoryAlertStore store = new InMemoryAlertStore();
        long closed = NOW - Duration.ofDays(100).toMillis();
        Alert fired = new Alert("r", "warning", "p", "failed_batches", 1, "gte", 1, "1h", closed, "msg");
        store.insert(new AlertStore.Row("HELD", "t", "m", "warning", "sc", Map.of("rule", "r"), AlertLifecycle.RESOLVED,
                closed - 1000, closed, "u", inc.id(), fired));
        store.insert(new AlertStore.Row("FREE", "t", "m", "warning", "sc", Map.of("rule", "r"), AlertLifecycle.RESOLVED,
                closed - 1000, closed, "u", null, fired));
        AlertService alerts = new AlertService(List.of(), null, null,
                AlertRecords.of(store, Optional.of(ops.objects.access())), AlertRecords.NO_INCIDENTS);
        try (com.gamma.util.Scheduler s = new com.gamma.util.Scheduler(); JobService js = jobs(audit, s)) {
            js.alertService(alerts);
            JobConfig cfg = job(Map.of("task", "alert_purge", "retention_days", "30"));

            JobResult run = new MaintenanceJob(cfg, null, audit.toString(), null, js).run();
            assertTrue(run.message().contains("deleted 1 resolved Alert(s)"), run.message());
            assertTrue(run.message().contains("1 held by a legal hold on their Incident"), run.message());
            assertTrue(store.get("HELD").isPresent(), "the held Incident's Alert stays");
            assertTrue(store.get("FREE").isEmpty(), "an Alert with no held Incident goes as before");

            hold(ops, inc.id(), "no");
            run = new MaintenanceJob(cfg, null, audit.toString(), null, js).run();
            assertTrue(run.message().contains("deleted 1 resolved Alert(s)"), run.message());
            assertTrue(store.get("HELD").isEmpty(), "released: it goes on the next run");
        }
    }

    @Test
    void eventPruneKeepsEveryDayFromTheHeldEvidencesFirstDay(@TempDir Path audit) throws Exception {
        Ops ops = ops();
        long occurred = NOW - Duration.ofDays(400).toMillis();
        var inc = ops.objects.open(ObjectType.INCIDENT, "old fraud", "d", "HIGH", null,
                Map.of(ObjectService.ATTR_OCCURRED_AT, String.valueOf(occurred)));
        hold(ops, inc.id(), "LIT-9");
        PruneProbe events = new PruneProbe();
        try (com.gamma.util.Scheduler s = new com.gamma.util.Scheduler(); JobService js = jobs(audit, s)) {
            js.objects(ops.objects.access());
            js.eventStore(events);
            JobConfig cfg = job(Map.of("task", "event_prune", "retention_days", "90"));

            JobResult run = new MaintenanceJob(cfg, null, audit.toString(), null, js).run();
            LocalDate floor = Instant.ofEpochMilli(occurred).atZone(ZoneOffset.UTC).toLocalDate();
            assertEquals(List.of(floor), events.asked, "pruned only before the held evidence's first day");
            assertTrue(run.message().contains("under legal hold keep every day from " + floor), run.message());

            hold(ops, inc.id(), "0");
            new MaintenanceJob(cfg, null, audit.toString(), null, js).run();
            assertEquals(LocalDate.now(ZoneOffset.UTC).minusDays(90), events.asked.get(1), "released: the plain window again");
        }
    }

    /** An event store that records the cutoff it is asked to prune before, and prunes nothing. */
    private static final class PruneProbe implements EventStore {
        final List<LocalDate> asked = new ArrayList<>();
        @Override public void append(Event event) { }
        @Override public List<Event> query(EventQuery query) { return List.of(); }
        @Override public List<Event> recent(int limit) { return List.of(); }
        @Override public int prune(LocalDate before, boolean dryRun) { asked.add(before); return 0; }
    }
}
