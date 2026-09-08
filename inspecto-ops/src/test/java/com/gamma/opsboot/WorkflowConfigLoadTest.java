package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.objects.ObjectType;
import com.gamma.ops.workflow.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ServiceBootstrap#loadWorkflows} scans {@code *_workflow.toon}, registering the valid ones and
 * warning + skipping any that fail to parse (the {@code loadRcaTemplates} robustness contract), and
 * {@link ServiceBootstrap#buildFrom} wires each override into {@link com.gamma.ops.ObjectService} so
 * {@code workflow(type)} (what {@code GET /workflows/{type}} serves) reflects it — the boot-scan seam that
 * was previously missing (the {@code workflows} map was frozen to {@link Workflow#defaultFor} at
 * construction).
 */
class ServiceBootstrapWorkflowTest {

    private static final String TASK_OVERRIDE = """
            workflow:
              object_type: TASK
              initial: TODO
              terminal[1]: "DONE"
              transitions[2]{from,to,action}:
                TODO,DOING,start
                DOING,DONE,finish
            """;

    @Test
    void loadsValidOverridesAndSkipsBad(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("task_workflow.toon");
        Files.writeString(good, TASK_OVERRIDE);
        Path bad = dir.resolve("broken_workflow.toon");
        Files.writeString(bad, "workflow:\n  initial: OPEN\n");   // no object_type → invalid, must be skipped

        // ⚠ Driven through the ENGINE since EDG-01 cell 7: the loader left ServiceBootstrap for this
        // module and is private to the provider, so the assertion is now on observable behaviour — the
        // override that registered — rather than on a returned list. That is the stronger form: it proves
        // the valid file took effect, not merely that parsing returned one element.
        var engine = new com.gamma.ops.OpsEngineProvider()
                .open(com.gamma.service.SpaceRoot.under(dir), dir.toString());
        engine.loadConfigs(List.of(good, bad));
        com.gamma.ops.ObjectService svc = ((com.gamma.ops.ObjectServiceAccess) engine.access()).service();

        assertEquals("TODO", svc.workflow(ObjectType.TASK).initialState(),
                "the valid override is registered");
        assertEquals(Workflow.defaultFor(ObjectType.INCIDENT).initialState(),
                svc.workflow(ObjectType.INCIDENT).initialState(),
                "the malformed override is skipped, leaving the built-in in place");
        engine.close();
    }

    @Test
    void buildFromWiresOverrideIntoObjectService(@TempDir Path dir) throws Exception {
        // a normal pipeline so the boot is realistic, plus the workflow override in the same config dir
        TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Files.writeString(dir.resolve("task_workflow.toon"), TASK_OVERRIDE);

        try (var svc = ServiceBootstrap.buildFrom(SpaceRoot.legacy(), new String[]{dir.toString()}, false)) {
            // ⚠ objects() is the narrow seam now — take it down to the engine, as the module's routes do.
            com.gamma.ops.ObjectService engine =
                    ((com.gamma.ops.ObjectServiceAccess) svc.objects().orElseThrow()).service();
            Workflow task = engine.workflow(ObjectType.TASK);
            assertEquals("TODO", task.initialState(), "the authored override replaced the OPEN→CLOSED default");
            assertEquals("DOING", task.apply("TODO", "start").orElseThrow());
            assertTrue(task.isTerminal("DONE"));

            // a type with no override still gets its built-in default
            assertEquals(Workflow.defaultFor(ObjectType.ALERT).initialState(),
                    engine.workflow(ObjectType.ALERT).initialState());
        }
    }
}
