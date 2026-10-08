package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.ops.ObjectService;
import com.gamma.ops.cases.CaseOperations;
import com.gamma.ops.cases.CaseRule;
import com.gamma.ops.tag.TagRule;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.service.CollectorService;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code caserule.evaluate} (contributed by this module through {@code ServiceLoader}) RUN inside a real
 * {@link CollectorService}, through {@link JobService}'s {@code requires:} grant. Moved with the Job Type from
 * {@code OpsJobTypesRunInAServiceTest} (MODULE-REORG-P7); the original finding stands: a Job Type that does not
 * declare {@code objects} fails every deployed run while a hand-built context passes.
 */
class CaseRuleEvaluateRunInAServiceTest {

    private static final String SPACE = "default";

    @TempDir Path tmp;
    private String priorAuditDir;
    private String priorDataDir;
    private Path priorConfigRoot;

    @BeforeEach
    void pinDirs() {
        priorAuditDir = System.getProperty("jobs.audit.dir");
        priorDataDir = System.getProperty("data.dir");
        System.setProperty("jobs.audit.dir", tmp.resolve("jobs_audit").toString());
        System.setProperty("data.dir", tmp.resolve("data").toString());
        priorConfigRoot = SpaceConfigRoot.forSpace(SPACE);
        SpaceConfigRoot.register(SPACE, tmp.resolve("config-root"));
    }

    @AfterEach
    void restoreDirs() {
        restore("jobs.audit.dir", priorAuditDir);
        restore("data.dir", priorDataDir);
        if (priorConfigRoot != null) SpaceConfigRoot.register(SPACE, priorConfigRoot);
        else SpaceConfigRoot.forget(SPACE);
    }

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
    void caseRuleEvaluateReachesTheObjectEngineInsideAService() throws Exception {
        try (CollectorService svc = service()) {
            ObjectService objects = TestOpsEngine.of(svc);
            CaseOperations.of(objects).registerCaseRule(new CaseRule("crit-cluster", "Critical incident cluster",
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
