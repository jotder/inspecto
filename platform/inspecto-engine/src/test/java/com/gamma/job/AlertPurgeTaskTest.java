package com.gamma.job;

import com.gamma.alert.Alert;
import com.gamma.alert.AlertLifecycle;
import com.gamma.alert.AlertRecords;
import com.gamma.alert.AlertService;
import com.gamma.alert.AlertStore;
import com.gamma.alert.InMemoryAlertStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code alert_purge} maintenance task: retention window rules, dry run, the live history and the audit row. */
class AlertPurgeTaskTest {

    private static final long NOW = System.currentTimeMillis();

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("purge-alerts", JobType.MAINTENANCE, null, null, true, false, params);
    }

    private static JobContext dryCtx(Path auditDir) {
        RunContext ctx = new RunContext("r-dry", "default", "m", "manual", "r-dry", null, 0, Map.of(),
                new RunLogStore(auditDir.toString()), 100, new RunArtifactStore(auditDir.toString()));
        ctx.dryRun(true);
        return ctx;
    }

    private static Alert fired(String rule) {
        return new Alert(rule, "warning", "p", "failed_batches", 1, "gte", 1, "1h", NOW, "msg");
    }

    /** {@code closedDaysAgo < 0} = a never-closed (active) row. */
    private static void put(AlertStore s, String id, String state, long closedDaysAgo, Alert a) {
        long closed = closedDaysAgo < 0 ? 0 : NOW - Duration.ofDays(closedDaysAgo).toMillis();
        s.insert(new AlertStore.Row(id, "t", "m", "warning", "sc", Map.of("rule", id), state,
                (closed == 0 ? NOW : closed) - 1000, closed, closed > 0 ? "u" : null, null, a));
    }

    private static AlertService service(AlertStore store) {
        return new AlertService(List.of(), null, null, AlertRecords.of(store, Optional.empty()), AlertRecords.NO_INCIDENTS);
    }

    private static List<String> rules(AlertService alerts) {
        return alerts.recent(10).stream().map(m -> (String) m.get("rule")).sorted().toList();
    }

    @Test
    void deletesOnlyTheOldResolvedAlertsKeepsEverythingElseAndTheLiveHistoryAgrees(@TempDir Path audit) throws Exception {
        InMemoryAlertStore store = new InMemoryAlertStore();
        put(store, "OLD", AlertLifecycle.RESOLVED, 100, fired("old"));
        put(store, "RECENT", AlertLifecycle.RESOLVED, 10, fired("recent"));
        put(store, "OPEN", AlertLifecycle.OPEN, -1, fired("open"));
        put(store, "ACK", AlertLifecycle.ACKNOWLEDGED, -1, fired("ack"));
        AlertService alerts = service(store);
        assertEquals(4, alerts.recent(10).size());
        try (com.gamma.util.Scheduler s = new com.gamma.util.Scheduler();
             JobService js = new JobService(List.of(), new com.gamma.etl.ConsignmentEventBus(), s, null, audit.toString())) {
            js.alertService(alerts);
            JobConfig cfg = job(Map.of("task", "alert_purge", "retention_days", "30"));

            JobResult dry = new MaintenanceJob(cfg, null, audit.toString(), null, js).run(dryCtx(audit));
            assertTrue(dry.message().contains("would delete 1 resolved Alert(s)"), dry.message());
            assertTrue(dry.message().contains("1 resolved within retention, 2 open/acknowledged kept"), dry.message());
            assertEquals(4, store.size());
            assertEquals(4, alerts.recent(10).size(), "a dry run leaves the history alone");

            JobResult real = new MaintenanceJob(cfg, null, audit.toString(), null, js).run();
            assertTrue(real.message().contains("deleted 1 resolved Alert(s)"), real.message());
            assertEquals(3, store.size());
            assertTrue(store.get("OLD").isEmpty());
            assertEquals(List.of("ack", "open", "recent"), rules(alerts),
                    "GET /alerts no longer lists the purged Alert and keeps the rest");
            assertTrue(alerts.recent(10).stream().allMatch(m -> m.get("id") != null), "kept ones still carry their record id");
            assertEquals(List.of("ack", "open", "recent"), rules(service(store)), "a restart re-seeds without the purged row");
        }
    }

    @Test
    void absentRetentionDaysDefaultsToNinetyAndBadWindowsAreRefused(@TempDir Path audit) throws Exception {
        InMemoryAlertStore store = new InMemoryAlertStore();
        put(store, "OLD", AlertLifecycle.RESOLVED, 120, fired("old"));
        put(store, "MID", AlertLifecycle.RESOLVED, 60, fired("mid"));
        try (com.gamma.util.Scheduler s = new com.gamma.util.Scheduler();
             JobService js = new JobService(List.of(), new com.gamma.etl.ConsignmentEventBus(), s, null, audit.toString())) {
            js.alertService(service(store));
            for (String bad : List.of("", "  ", "0", "-5", "abc")) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> new MaintenanceJob(job(Map.of("task", "alert_purge", "retention_days", bad)), null,
                                audit.toString(), null, js).run(), "'" + bad + "' must be refused");
                assertTrue(e.getMessage().contains("alert_purge retention_days"), e.getMessage());
            }
            assertEquals(2, store.size(), "a refused window deletes nothing");
            new MaintenanceJob(job(Map.of("task", "alert_purge")), null, audit.toString(), null, js).run();
            assertTrue(store.get("OLD").isEmpty() && store.get("MID").isPresent(), "absent = 90 days");
        }
    }

    @Test
    void withoutAnAlertEngineTheTaskFailsInsteadOfReportingASilentSuccess() {
        assertThrows(IllegalStateException.class,
                () -> new MaintenanceJob(job(Map.of("task", "alert_purge", "retention_days", "30"))).run());
    }

    @Test
    void aRealPurgeIsRecordedOnTheAuditChainAndANoOpIsNot(@TempDir Path audit, @TempDir Path events) throws Exception {
        InMemoryAlertStore store = new InMemoryAlertStore();
        put(store, "OLD", AlertLifecycle.RESOLVED, 100, fired("old"));
        try (var evStore = new com.gamma.event.ParquetEventStore(events, 1000, 0, 100);
             com.gamma.util.Scheduler s = new com.gamma.util.Scheduler();
             JobService js = new JobService(List.of(), new com.gamma.etl.ConsignmentEventBus(), s, null, audit.toString())) {
            com.gamma.event.EventLog log = com.gamma.event.EventLog.create();
            log.installStore(evStore);
            js.eventLog(log);
            js.alertService(service(store));
            JobConfig cfg = job(Map.of("task", "alert_purge", "retention_days", "30"));
            new MaintenanceJob(cfg, null, audit.toString(), null, js).run();
            new MaintenanceJob(cfg, null, audit.toString(), null, js).run();   // nothing left to delete
            var rows = evStore.chainPage(1, 10).stream()
                    .filter(e -> "alerts.purged".equals(e.attributes().get(com.gamma.audit.AuditAttrs.ACTION))).toList();
            assertEquals(1, rows.size());
            assertEquals("1", rows.get(0).attributes().get("alerts_removed"));
        }
    }

    @Test
    void theTaskIsABuiltInOfTheBaseSwitch() {
        assertTrue(MaintenanceJob.BUILT_IN_TASKS.contains("alert_purge"));
        assertTrue(MaintenanceJob.availableTasks().contains("alert_purge"));
    }
}
