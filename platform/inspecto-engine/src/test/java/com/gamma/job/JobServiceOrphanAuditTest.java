package com.gamma.job;

import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
 * ({@code upsertJob}/{@code removeJob}). A Job covers a Pipeline when its {@code pipeline_config:} file's
 * registered id is that Pipeline's (SCHEDULER-AUDIT-PIPELINE-NAME-1), so the runner Jobs here name real files
 * under the jail.
 */
class JobServiceOrphanAuditTest {

    /** The runner's jail for these tests: {@code pipeline_config:} files are written here. */
    @TempDir Path configs;
    private String priorRoots;

    @BeforeEach
    void jail() {
        priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", configs.toString());
    }

    @AfterEach
    void unjail() {
        if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
        else System.clearProperty("assist.safety.roots");
    }

    /** A Pipeline file {@code fileName} under the jail whose {@code name:} is {@code name}; its absolute path. */
    private String pipelineFile(String fileName, String name) throws Exception {
        Path f = configs.resolve(fileName);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "name: " + name + "\nactive: true\n");
        return f.toString();
    }

    private static JobService service(Path audit, List<JobConfig> jobs) {
        return new JobService(jobs, new com.gamma.etl.ConsignmentEventBus(),
                new com.gamma.util.Scheduler(), null, audit.toString());
    }

    /**
     * SCHEDULER-AUDIT-PIPELINE-NAME-1, the {@code inspecto/examples/07-steps} shape: {@code orders_pipeline.toon}
     * holds a Pipeline called {@code STEP_DEDUP}, and its Job names that file. The Pipeline is covered, although
     * its file name is not its id; and a Job naming the file of ANOTHER Pipeline covers only that one.
     */
    @Test
    void aJobCoversThePipelineItsFileHoldsNotThePipelineNamedLikeTheFile(@TempDir Path audit) throws Exception {
        JobConfig rollup = new JobConfig("dedup_rollup", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", pipelineFile("dedup/orders_pipeline.toon", "STEP_DEDUP")));
        try (JobService js = service(audit, List.of(rollup))) {
            js.pipelineOutputStores(() -> Map.of("step_dedup", "rollup", "orders", "orders_out"));

            List<String> fresh = js.auditOrphanOutputStores();

            assertEquals(1, fresh.size(), fresh.toString());
            assertTrue(fresh.get(0).contains("pipeline 'orders'"), "the file's name runs nothing: " + fresh);
        }
    }

    /**
     * A named Space's audit, called from a thread bound to no Space (a registry rebuild), resolves a relative
     * {@code pipeline_config:} from THAT Space's config read root, as the Job's own run does — not the default's.
     */
    @Test
    void aNamedSpaceResolvesARelativePathFromItsOwnReadRoot(@TempDir Path audit) throws Exception {
        pipelineFile("dedup/orders_pipeline.toon", "STEP_DEDUP");
        JobConfig rollup = new JobConfig("dedup_rollup", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", "dedup/orders_pipeline.toon"));
        com.gamma.pipeline.SpaceConfigRoot.registerConfigReadRoot("orphan-audit-space", configs);
        try (JobService js = service(audit, List.of(rollup))) {
            js.spaceId("orphan-audit-space");
            js.pipelineOutputStores(() -> Map.of("step_dedup", "rollup"));

            assertNull(org.slf4j.MDC.get(EventLog.SPACE_MDC_KEY), "the caller is bound to no Space");
            assertTrue(js.auditOrphanOutputStores().isEmpty(), "the Space's Job covers its Pipeline");
            assertNull(org.slf4j.MDC.get(EventLog.SPACE_MDC_KEY), "the audit leaves the caller unbound");
        } finally {
            com.gamma.pipeline.SpaceConfigRoot.forget("orphan-audit-space");
        }
    }

    /** A Job whose {@code pipeline_config:} the runner could not run — missing, or outside the jail — covers nothing. */
    @Test
    void aJobTheRunnerCannotRunCoversNothing(@TempDir Path audit, @TempDir Path outside) throws Exception {
        Path escaped = outside.resolve("escaped_pipeline.toon");
        Files.writeString(escaped, "name: escaped\n");
        JobConfig missing = new JobConfig("missing", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", configs.resolve("gone_pipeline.toon").toString()));
        JobConfig jailed = new JobConfig("jailed", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", escaped.toString()));
        try (JobService js = service(audit, List.of(missing, jailed))) {
            js.pipelineOutputStores(() -> Map.of("gone", "gone_out", "escaped", "escaped_out"));

            assertEquals(2, js.auditOrphanOutputStores().size(), "neither Job runs its chain");
        }
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
                Map.of("pipeline_config", pipelineFile("config/covered_pipeline.toon", "covered")));
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
        String coveredFile = pipelineFile("config/covered_pipeline.toon", "covered");
        JobConfig runner = new JobConfig("rollup", JobType.PIPELINE, null, null, true, false,
                Map.of("pipeline_config", coveredFile));
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
                    Map.of("pipeline_config", coveredFile)));   // …disabling re-orphans
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
