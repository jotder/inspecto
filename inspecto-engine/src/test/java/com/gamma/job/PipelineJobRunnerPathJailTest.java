package com.gamma.job;

import com.gamma.config.safety.PathJail;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.event.EventLog;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.SpaceConfigRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1} (operator 2026-09-17, option 1): the two operator-authored paths a
 * {@link PipelineJobRunner} reads — {@code pipeline_config} and {@code data_dir} — are contained by the same
 * one job-path rule as every maintenance task, against the Space's config READ root
 * ({@link SpaceConfigRoot#currentConfigReadRoot()}), not the write root.
 *
 * <p>Two halves, both load-bearing: an escape is REFUSED (before {@code PipelineConfig.load} ever sees the
 * path), and a value that resolves resolves to the same file it always did. Only the first half is new
 * behaviour; the second is what makes option 1 additive rather than a re-point.
 *
 * <p>The jail is narrowed to a temp dir as in {@link JobPathContainmentTest}, because surefire's permissive
 * sandbox would otherwise "contain" the escape and every refusal here would pass for the wrong reason.
 */
class PipelineJobRunnerPathJailTest {

    @TempDir Path root;
    private String priorRoots;

    @BeforeEach
    void jailToRoot() {
        priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", root.toAbsolutePath().normalize().toString());
        SpaceConfigRoot.clear();
        MDC.remove(EventLog.SPACE_MDC_KEY);
    }

    @AfterEach
    void restore() {
        if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
        else System.clearProperty("assist.safety.roots");
        SpaceConfigRoot.clear();
        MDC.remove(EventLog.SPACE_MDC_KEY);
    }

    /** A real sibling of {@code root}, reached via {@code ..} — provably outside, and shaped like an authored value. */
    private String escapesTo(String name) throws Exception {
        Path sibling = Files.createDirectories(root.getParent().resolve(name));
        assertFalse(PathJail.contains(root, sibling), "fixture is not an escape — the test would be vacuous");
        return root.resolve("..").resolve(name).toString();
    }

    private static PipelineStore storeWithOneGraph(Path dir, String name) throws Exception {
        PipelineStore store = new PipelineStore(dir);
        store.write(name, new PipelineGraph(name, true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        new PipelineNode("out", "sink.persistent", "O", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "out"))));
        return store;
    }

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("pj", JobType.PIPELINE, null, null, true, false, params);
    }

    @Test
    void aPipelineConfigThatEscapesEveryAllowedRootIsRefusedBeforeItIsLoaded() throws Exception {
        // The file does NOT exist: had the runner reached PipelineConfig.load, the failure would be an I/O one,
        // not a PathJail.Escape — so the exception TYPE is what proves the refusal came first.
        String outside = escapesTo("elsewhere") + "/x_pipeline.toon";
        String dataDir = root.resolve("data").toString();
        PipelineJobRunner runner = new PipelineJobRunner(job(Map.of("pipeline_config", outside, "data_dir", dataDir)),
                new ConsignmentEventBus(), null, dataDir, root.resolve("audit").toString());

        PathJail.Escape e = assertThrows(PathJail.Escape.class, runner::run);
        assertEquals("job.pipeline_config", e.field(), "the message must name the offending field");
    }

    @Test
    void aDataDirThatEscapesEveryAllowedRootIsRefused() throws Exception {
        String outside = escapesTo("exfil");
        String dataDir = root.resolve("data").toString();
        PipelineStore store = storeWithOneGraph(root.resolve("flows"), "ext_flow");
        PipelineJobRunner runner = new PipelineJobRunner(job(Map.of("pipeline", "ext_flow", "data_dir", outside)),
                new ConsignmentEventBus(), store, dataDir, root.resolve("audit").toString());

        PathJail.Escape e = assertThrows(PathJail.Escape.class, runner::run);
        assertEquals("job.data_dir", e.field());
        assertFalse(Files.exists(Path.of(outside, "rollup")), "nothing may be written outside the roots");
    }

    @Test
    void aRelativeValueResolvesAgainstTheReadRootExactlyAsTheOldWorkingDirectoryRuleDid() {
        // Nothing registered (how every runner test, and the engine CLI, reaches this code): the read root is
        // the launch dir, so the one job-path rule yields the path the pre-change CWD-relative read produced.
        // The "before" path is computed independently of anything under test.
        Path before = Path.of("target", "x_pipeline.toon").toAbsolutePath().normalize();
        Path readRoot = SpaceConfigRoot.currentConfigReadRoot();
        assertNotNull(readRoot);
        assertEquals(before, PathJail.resolveJobPath(readRoot, "target/x_pipeline.toon", "job.pipeline_config"));

        // ⚠ and it is NOT the write root: setting one must not move a relative pipeline_config anywhere.
        System.setProperty("assist.write.root", root.resolve("write").toString());
        try {
            assertEquals(before, PathJail.resolveJobPath(SpaceConfigRoot.currentConfigReadRoot(),
                    "target/x_pipeline.toon", "job.pipeline_config"));
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    @Test
    void aRelativePipelineConfigUnderARegisteredReadRootIsResolvedThereNotRefused() throws Exception {
        // The control-plane shape: SpaceManager.single() registers the launch dir as the default space's read
        // root; here a temp dir stands in for it, and the job names its flat config RELATIVE to that root.
        SpaceConfigRoot.registerConfigReadRoot(EventLog.DEFAULT_SPACE_ID, root);
        String dataDir = root.resolve("data").toString();
        Path flat = root.resolve("shape_pipeline.toon");
        Files.writeString(flat, """
                name: shape_etl
                active: false
                output_store: shaped
                dirs:
                  poll: in
                  database: out
                processing:
                  threads: 1
                """);
        String landed = com.gamma.etl.PipelineConfig.load(flat.toString()).identity().pipelineName();
        Files.createDirectories(Path.of(dataDir, landed));
        PipelineJobRunner runner = new PipelineJobRunner(
                job(Map.of("pipeline_config", "shape_pipeline.toon", "data_dir", dataDir)),
                new ConsignmentEventBus(), null, dataDir, root.resolve("audit").toString());

        // Neither a PathJail.Escape nor a missing-file error may surface: the relative value was resolved under
        // the read root and the file was found there. What a run over an EMPTY landed store then reports is
        // not this test's subject.
        try {
            runner.run();
        } catch (PathJail.Escape e) {
            fail("a relative pipeline_config under the read root was refused: " + e.getMessage());
        } catch (java.io.IOException e) {
            fail("the relative pipeline_config was not resolved under the read root: " + e);
        } catch (Exception downstream) {
            // outcome over an empty seed store — outside this test's claim
        }
    }
}
