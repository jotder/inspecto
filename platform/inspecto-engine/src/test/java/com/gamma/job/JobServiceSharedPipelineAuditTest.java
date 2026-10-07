package com.gamma.job;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.pipeline.PipelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default-on shared-pipeline audit ({@link JobService#auditSharedPipelines()}) —
 * {@code JOB-PIPELINE-PARAM-UNIQUE-1}.
 *
 * <p>A job's {@code name} is its only unique key; the pipeline it targets is an unvalidated
 * {@code pipeline:} param, so two jobs may point at one authored pipeline. Since B3 that is <b>safe</b> —
 * the authored-pipeline claim makes the second record {@code SKIPPED} rather than overlap — but it is
 * <b>invisible</b>: the operator sees intermittent skips with no stated cause. This audit names it at
 * authoring time instead.
 *
 * <p>⛔ It WARNS and never refuses. Two jobs on one pipeline with different schedules or params may be
 * deliberate; failing closed on it is an operator decision that has not been taken.
 *
 * <p>⚠ The finding itself is a PURE config scan, so most of these drive
 * {@link SchedulerAuditTask#sharedPipelineFindings} directly. Only the transition/debounce and kill-switch
 * behaviour needs a live {@link JobService} — and a {@code type: pipeline} job <b>fails closed at
 * construction</b> without an authored-pipeline store, so those tests must supply one.
 */
class JobServiceSharedPipelineAuditTest {

    private static JobConfig pipelineJob(String name, String paramKey, String pipeline, boolean enabled) {
        return new JobConfig(name, JobType.PIPELINE, null, null, enabled, false,
                Map.of(paramKey, pipeline));
    }

    /** A service that CAN build pipeline jobs — the 5-arg constructor leaves the store null and fails closed. */
    private static JobService service(Path dir, List<JobConfig> jobs) {
        return new JobService(jobs, new com.gamma.etl.ConsignmentEventBus(),
                new com.gamma.util.Scheduler(), null, dir.resolve("audit").toString(), null,
                new PipelineStore(dir.resolve("flows")), dir.resolve("data").toString());
    }

    // ── the finding, as a pure function ──────────────────────────────────────────────

    @Test
    void twoJobsOnOnePipelineAreReportedAndBothAreNamed() {
        List<String> findings = SchedulerAuditTask.sharedPipelineFindings(List.of(
                pipelineJob("nightly", "pipeline", "evt_rollup", true),
                pipelineJob("hourly", "pipeline", "evt_rollup", true)));

        assertEquals(1, findings.size(), "one finding for the one shared pipeline");
        String f = findings.get(0);
        assertTrue(f.contains("evt_rollup"), "the pipeline is named: " + f);
        assertTrue(f.contains("nightly"), "the first job is named: " + f);
        assertTrue(f.contains("hourly"),
                "⛔ the OTHER job must be named too — naming only one leaves the operator exactly as "
                        + "unable to act as the bare skip message: " + f);
    }

    /** ⛔ The discriminator for the key: distinct pipelines are not a finding, however many jobs. */
    @Test
    void oneJobPerPipelineIsHealthy() {
        assertTrue(SchedulerAuditTask.sharedPipelineFindings(List.of(
                pipelineJob("a", "pipeline", "orders", true),
                pipelineJob("b", "pipeline", "events", true),
                pipelineJob("c", "pipeline", "invoices", true))).isEmpty(),
                "three jobs, three pipelines — healthy");
    }

    /**
     * 🔴 The finding must key off the SAME param reader the claim uses, including the Tier-3 dual read:
     * {@code pipeline:} is canonical, {@code flow:} is the pre-rename key, and a job authored either way
     * targets the same pipeline — so a pair spelling the key differently must still be reported.
     *
     * <p>⛔ If the audit ever recomputes the key instead of calling
     * {@link JobService#authoredPipelineKeyOf}, this is the test that fails — and the production symptom
     * would be a pair of jobs that skip each other while the audit calls them healthy.
     */
    @Test
    void theLegacyFlowKeyAndTheCanonicalPipelineKeyAreTheSamePipeline() {
        List<String> findings = SchedulerAuditTask.sharedPipelineFindings(List.of(
                pipelineJob("canonical", "pipeline", "evt_rollup", true),
                pipelineJob("legacy", "flow", "evt_rollup", true)));

        assertEquals(1, findings.size(),
                "`flow:` and `pipeline:` name ONE pipeline — the claim keys them together, so must this");
        assertTrue(findings.get(0).contains("canonical") && findings.get(0).contains("legacy"),
                findings.get(0));
    }

    /** A disabled job cannot run, so it cannot hold the claim — it must not be reported as a sharer. */
    @Test
    void aDisabledJobIsNotASharer() {
        assertTrue(SchedulerAuditTask.sharedPipelineFindings(List.of(
                pipelineJob("live", "pipeline", "evt_rollup", true),
                pipelineJob("retired", "pipeline", "evt_rollup", false))).isEmpty(),
                "a disabled job never runs, so it never causes a skip");
    }

    /** A non-pipeline job has no authored pipeline at all, whatever its params happen to say. */
    @Test
    void aMaintenanceJobCarryingAPipelineParamIsNotASharer() {
        JobConfig maint = new JobConfig("prune", JobType.MAINTENANCE, null, null, true, false,
                Map.of("task", "heartbeat", "pipeline", "evt_rollup"));
        assertTrue(SchedulerAuditTask.sharedPipelineFindings(List.of(
                pipelineJob("nightly", "pipeline", "evt_rollup", true), maint)).isEmpty(),
                "only a `type: pipeline` job takes the authored-pipeline claim");
    }

    // ── the hosted audit: transitions and the kill switch ────────────────────────────

    /**
     * Once per TRANSITION, not once per cycle — the same debounce the orphan audit carries.
     *
     * <p>⚠ {@code upsertJob}/{@code removeJob} run the audit THEMSELVES (they are the transition sources),
     * so the re-add below is already audited by the time it returns; calling the audit again would report
     * empty because it was debounced, not because nothing was found. The transition is therefore asserted
     * on the emitted signals, which is also what an operator actually sees.
     */
    @Test
    void theFindingIsEmittedOncePerTransition(@TempDir Path dir) throws Exception {
        List<Event> captured = new CopyOnWriteArrayList<>();
        EventLog el = EventLog.create();
        el.addSubscriber(captured::add);
        try (JobService js = service(dir, List.of(
                pipelineJob("nightly", "pipeline", "evt_rollup", true),
                pipelineJob("hourly", "pipeline", "evt_rollup", true)))) {
            js.eventLog(el);

            assertEquals(1, js.auditSharedPipelines().size(), "first cycle emits");
            assertTrue(js.auditSharedPipelines().isEmpty(), "second cycle is debounced");
            assertTrue(js.auditSharedPipelines().isEmpty(), "…and stays quiet");
            int afterFirst = captured.size();

            // resolve it: the sharer goes away (removeJob audits internally)
            js.removeJob("hourly");
            assertTrue(js.auditSharedPipelines().isEmpty(), "a resolved finding emits nothing");
            assertEquals(afterFirst, captured.size(), "resolving emits no new signal");

            // …and the finding RETURNING is a new transition, emitted by upsertJob itself
            js.upsertJob(pipelineJob("hourly", "pipeline", "evt_rollup", true));
            assertTrue(captured.size() > afterFirst,
                    "the finding returning is a NEW transition and must re-emit");
        }
    }

    @Test
    void theKillSwitchSilencesIt(@TempDir Path dir) throws Exception {
        System.setProperty(JobService.ORPHAN_AUDIT_FLAG, "false");
        try (JobService js = service(dir, List.of(
                pipelineJob("nightly", "pipeline", "evt_rollup", true),
                pipelineJob("hourly", "pipeline", "evt_rollup", true)))) {
            assertTrue(js.auditSharedPipelines().isEmpty(), "-Djobs.orphan.audit=false silences it");
        } finally {
            System.clearProperty(JobService.ORPHAN_AUDIT_FLAG);
        }
    }
}
