package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.workflow.ObjectType;
import com.gamma.ops.ObjectService;
import com.gamma.ops.tag.CaseRule;
import com.gamma.ops.tag.TagRule;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two Job Types {@code inspecto-ops} contributes through {@code ServiceLoader} — {@code objects.analytics}
 * (which writes the {@code impact_ledger} Dataset) and {@code caserule.evaluate} — RUN inside a real
 * {@link CollectorService}, through {@link JobService}'s grant.
 *
 * <p>🔴 Found by the impact-ledger browser pass (2026-09-28): both resolve their Space's Object Engine from
 * {@code JobContext.services()}, which holds only what the Type's descriptor {@code requires:} (platform-services
 * S1-2). Neither declared {@code objects}, so every run in a deployed build failed with "needs the space Object
 * Engine … not installed" — while their own tests passed, because they construct the Job with a supplier or a
 * hand-built context and never go through the grant.
 */
class OpsJobTypesRunInAServiceTest {

    @TempDir Path tmp;
    private String priorAuditDir;
    private String priorDataDir;

    @BeforeEach
    void pinDirs() {
        priorAuditDir = System.getProperty("jobs.audit.dir");
        priorDataDir = System.getProperty("data.dir");
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        System.setProperty("data.dir", tmp.resolve("data").toString());
        // What SpaceBootstrap does for every booted Space: objects.analytics registers its Datasets there.
        priorConfigRoot = SpaceConfigRoot.forSpace(SPACE);
        SpaceConfigRoot.register(SPACE, tmp.resolve("config-root"));
    }

    private Path priorConfigRoot;

    @AfterEach
    void restoreDirs() {
        restore("jobs.audit.dir", priorAuditDir);
        restore("data.dir", priorDataDir);
        if (priorConfigRoot != null) SpaceConfigRoot.register(SPACE, priorConfigRoot);
        else SpaceConfigRoot.forget(SPACE);
    }

    /** The single-tenant (legacy) Space a bare CollectorService runs as. */
    private static final String SPACE = "default";

    private static void restore(String key, String prior) {
        if (prior != null) System.setProperty(key, prior);
        else System.clearProperty(key);
    }

    private CollectorService service() throws Exception {
        Path toon = TestConfigs.csv(tmp.resolve("cfg"), PipelineConfigBatchTest.miniSchema()).write();
        return new CollectorService(List.of(toon), 3600, 1);
    }

    private static JobRun runToEnd(JobService js, String name) throws Exception {
        String runId = js.triggerRun(name, "t").orElseThrow();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JobRun r = js.runById(runId).orElse(null);
            if (r != null && r.endTime() != null && !r.endTime().isBlank()) return r;
            Thread.sleep(50);
        }
        return fail("run " + runId + " did not finish");
    }

    @Test
    void objectsAnalyticsWritesTheImpactLedgerInsideAService() throws Exception {
        try (CollectorService svc = service()) {
            TestOpsEngine.of(svc).open(ObjectType.INCIDENT, "leak", "d", "HIGH", null, null, null, "c",
                    Map.of("impact", "{\"confirmed\":\"150.5\",\"currency\":\"EUR\"}"));
            JobService js = svc.jobServiceOrCreate();
            js.upsertJob(new JobConfig("ledger", "objects.analytics", null, null, true, false, Map.of(), null, null));

            JobRun run = runToEnd(js, "ledger");

            assertEquals("SUCCESS", run.status(), run.message());
            try (Stream<Path> files = Files.walk(tmp.resolve("data"))) {
                assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("impact_")),
                        "the impact_ledger sample was written");
            }
        }
    }

    /**
     * The boot path: a Space whose {@code *_job.toon} exists at start builds its JobService in the
     * CollectorService constructor, which validates {@code requires:} there — so the "objects" service must be
     * registered before it, or the type is refused and the job cannot even be loaded.
     */
    @Test
    void aJobAuthoredBeforeBootRunsToo() throws Exception {
        Path toon = TestConfigs.csv(tmp.resolve("cfg"), PipelineConfigBatchTest.miniSchema()).write();
        JobConfig ledger = new JobConfig("ledger", "objects.analytics", null, null, true, false, Map.of(), null, null);
        try (CollectorService svc = new CollectorService(List.of(toon), List.of(), List.of(ledger), 3600L, 1, null)) {
            JobService js = svc.jobService().orElseThrow(() -> new AssertionError("the boot JobService was built"));
            assertTrue(js.jobs().stream().anyMatch(j -> "ledger".equals(j.name())), "the job loaded: " + js.jobs());

            JobRun run = runToEnd(js, "ledger");

            assertEquals("SUCCESS", run.status(), run.message());
        }
    }

    @Test
    void caseRuleEvaluateReachesTheObjectEngineInsideAService() throws Exception {
        try (CollectorService svc = service()) {
            ObjectService objects = TestOpsEngine.of(svc);
            objects.registerCaseRule(new CaseRule("crit-cluster", "Critical incident cluster",
                    new TagRule.Filter("INCIDENT", null, null, "CRITICAL", null, null),
                    1, 1440, "Pipeline / Ingest", "auto", 1));
            objects.open(ObjectType.INCIDENT, "one", "d", "HIGH", "CRITICAL", null, null, "corr", Map.of());
            JobService js = svc.jobServiceOrCreate();
            js.upsertJob(new JobConfig("cases", "caserule.evaluate", null, null, true, false,
                    Map.of("rule", "crit-cluster"), null, null));

            JobRun run = runToEnd(js, "cases");

            assertEquals("SUCCESS", run.status(), run.message());
        }
    }
}
