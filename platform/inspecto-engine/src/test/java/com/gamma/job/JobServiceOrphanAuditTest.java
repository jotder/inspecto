package com.gamma.job;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default-on orphan {@code output_store:} audit ({@link JobService#auditOrphanOutputStores()}) —
 * the fail-closed complement to the {@code scheduler_audit} maintenance task: it must fire on a space
 * that ships NO such job, emit once per orphan transition (debounced across cycles), honor the
 * {@code -Djobs.orphan.audit=false} kill switch, and re-run on job-registry transitions
 * ({@code upsertJob}/{@code removeJob}).
 */
class JobServiceOrphanAuditTest {

    private static JobService service(Path audit, List<JobConfig> jobs) {
        return new JobService(jobs, new com.gamma.etl.ConsignmentEventBus(),
                new com.gamma.util.Scheduler(), null, audit.toString());
    }

    @Test
    void orphanIsFoundWithNoSchedulerAuditJobConfigured(@TempDir Path audit) throws Exception {
        // NO scheduler_audit job anywhere — only an unrelated heartbeat.
        JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "heartbeat"));
        List<Event> captured = new CopyOnWriteArrayList<>();
        EventLog el = EventLog.create();
        el.addSubscriber(captured::add);
        try (JobService js = service(audit, List.of(hb))) {
            js.eventLog(el);
            js.pipelineOutputStores(() -> Map.of("orphaned", "orphan_store"));

            List<String> fresh = js.auditOrphanOutputStores();

            assertEquals(1, fresh.size(), fresh.toString());
            assertTrue(fresh.get(0).contains("orphan output_store: pipeline 'orphaned'"), fresh.get(0));
            assertTrue(fresh.get(0).contains("orphan_store"), fresh.get(0));
            assertTrue(captured.stream().anyMatch(e ->
                            e.attributes().toString().contains("maintenance.scheduler.findings")),
                    "the orphan finding is signalled to the ledger: " + captured);
        }
    }

    @Test
    void secondCycleDoesNotReEmitButAResolvedThenRegressedOrphanDoes(@TempDir Path audit) throws Exception {
        JobConfig runner = new JobConfig("rollup", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", "config/covered_pipeline.toon"));
        AtomicReference<Map<String, String>> stores =
                new AtomicReference<>(Map.of("orphaned", "orphan_store", "covered", "covered_out"));
        try (JobService js = service(audit, List.of(runner))) {
            js.pipelineOutputStores(stores::get);

            assertEquals(1, js.auditOrphanOutputStores().size(), "first cycle emits the one orphan");
            assertTrue(js.auditOrphanOutputStores().isEmpty(), "second cycle is debounced — no re-emit");
            assertTrue(js.auditOrphanOutputStores().isEmpty(), "…and stays quiet");

            stores.set(Map.of("covered", "covered_out"));           // orphan resolved (pipeline retired)
            assertTrue(js.auditOrphanOutputStores().isEmpty(), "a resolved orphan emits nothing");

            stores.set(Map.of("orphaned", "orphan_store", "covered", "covered_out"));
            assertEquals(1, js.auditOrphanOutputStores().size(), "a regression is a NEW transition — re-emit");
        }
    }

    @Test
    void jobRegistryTransitionsReRunTheAudit(@TempDir Path audit) throws Exception {
        JobConfig runner = new JobConfig("rollup", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", "config/covered_pipeline.toon"));
        List<Event> captured = new CopyOnWriteArrayList<>();
        EventLog el = EventLog.create();
        el.addSubscriber(captured::add);
        try (JobService js = service(audit, List.of(runner))) {
            js.eventLog(el);
            js.pipelineOutputStores(() -> Map.of("covered", "covered_out"));

            assertTrue(js.auditOrphanOutputStores().isEmpty(), "the chain has a runner — healthy");
            js.removeJob("rollup");   // deleting the runner orphans the chain — removeJob re-audits itself
            assertTrue(captured.stream().anyMatch(e ->
                            e.attributes().toString().contains("maintenance.scheduler.findings")),
                    "removeJob emitted the orphan transition: " + captured);

            captured.clear();
            js.upsertJob(runner);     // re-adding the runner resolves it (no emit)…
            assertTrue(captured.isEmpty(), "resolution is silent: " + captured);
            js.upsertJob(new JobConfig("rollup", JobType.PIPELINE, null, null, false, false,
                    Map.of("pipeline_config", "config/covered_pipeline.toon")));   // …disabling re-orphans
            assertTrue(captured.stream().anyMatch(e ->
                            e.attributes().toString().contains("maintenance.scheduler.findings")),
                    "disabling the runner emitted the orphan transition: " + captured);
        }
    }

    @Test
    void killSwitchDisablesTheDefaultOnAudit(@TempDir Path audit) throws Exception {
        System.setProperty(JobService.ORPHAN_AUDIT_FLAG, "false");
        try (JobService js = service(audit, List.of())) {
            js.pipelineOutputStores(() -> Map.of("orphaned", "orphan_store"));
            assertTrue(js.auditOrphanOutputStores().isEmpty(), "-Djobs.orphan.audit=false silences the audit");
        } finally {
            System.clearProperty(JobService.ORPHAN_AUDIT_FLAG);
        }
    }

    @Test
    void unwiredHostSkipsRatherThanGuesses(@TempDir Path audit) throws Exception {
        try (JobService js = service(audit, List.of())) {
            assertTrue(js.auditOrphanOutputStores().isEmpty(), "no supplier wired — skip, don't guess");
        }
    }
}
