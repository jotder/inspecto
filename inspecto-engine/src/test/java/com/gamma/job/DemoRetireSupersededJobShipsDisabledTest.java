package com.gamma.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the committed {@code spaces/demo} {@code retire_superseded} maintenance job — the example the
 * Consignment-addressing row found missing (every shipped space hit the
 * {@code PipelineJobRunner.supersedeEarlierRevisions} WARN with nothing to copy from).
 *
 * <p>⛔ <b>It ships DISABLED, and that is the point of the first assertion.</b> Retirement deletes bytes an
 * operator may rely on; turning it on is an operator call, not a demo default. {@code JobService
 * .retireSupersededConfigured} counts only {@code enabled} jobs, so the demo still takes the WARN path.
 *
 * <p>⚠ Unlike its path-carrying siblings ({@code DemoBackupJobPathsResolveUnderTheSpaceRootTest},
 * {@code RetentionSweepJobPathsResolveUnderTheSpaceRootTest}) this job has <b>no path and no store key to
 * resolve</b>: {@link RetireSupersededTask} reads only {@code retention_days} and walks the process-wide
 * {@code ConsignmentOutputStores.shared()} registry. A {@code dir:} or {@code store:} added "for symmetry"
 * would be captured into params and silently ignored — so the key set is pinned exactly.
 */
class DemoRetireSupersededJobShipsDisabledTest {

    /** Surefire's working directory is the module root; the demo Space sits one level up. */
    private static final Path JOB = Path.of("..", "spaces", "demo", "config", "jobs", "retire_superseded_job.toon");

    private static JobConfig committed() throws Exception {
        // ASSERTION, not an assumption: a renamed or moved file must fail here, not pass vacuously.
        assertTrue(Files.exists(JOB), "committed job config is missing: " + JOB.toAbsolutePath());
        return JobConfig.load(JOB.toAbsolutePath().toString());
    }

    @AfterEach
    void resetRegistry() {
        com.gamma.consignment.ConsignmentOutputStores.use(null);
    }

    @Test
    void shipsDisabledSoTheDefaultIsNotFlipped() throws Exception {
        JobConfig c = committed();
        assertEquals("maintenance", c.type());
        assertEquals("retire_superseded", c.params().get("task"));
        assertFalse(c.enabled(), "the demo retire_superseded job must ship DISABLED — enabling retirement "
                + "deletes bytes and is an operator call");
    }

    @Test
    void carriesOnlyTheKeysTheTaskReads() throws Exception {
        JobConfig c = committed();
        assertEquals(Set.of("task", "retention_days"), c.params().keySet(),
                "RetireSupersededTask reads only retention_days — any path/store key would be decorative");
        assertTrue(Long.parseLong(c.params().get("retention_days")) >= 1,
                "the task refuses retention_days < 1");
    }

    /** Positive control: the committed values are accepted by the real task, which then previews an aged
     *  superseded file and deletes nothing. */
    @Test
    void theCommittedValuesRunAsAPreview(@TempDir Path dir) throws Exception {
        Path old = Files.writeString(dir.resolve("old.parquet"), "x");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(30))));

        try (var db = com.gamma.consignment.DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            db.record(List.of(new com.gamma.consignment.ConsignmentOutput("c1", "run-1", "orders", "", null,
                    old.toString(), 1, 1, "2026-08-01T10:00:00Z", 0,
                    com.gamma.consignment.ConsignmentOutput.State.SUPERSEDED)));
            com.gamma.consignment.ConsignmentOutputStores.use(db);

            RunContext ctx = new RunContext("r-dry", "default", "retire_superseded", "manual", "r-dry", null, 0,
                    Map.of(), new RunLogStore(dir.toString()), 100, new RunArtifactStore(dir.toString()));
            ctx.dryRun(true);
            JobResult r = new MaintenanceJob(committed()).run(ctx);

            assertTrue(r.message().contains("would retire 1"), r.message());
            assertTrue(Files.exists(old), "a preview must not delete");
        }
    }
}
