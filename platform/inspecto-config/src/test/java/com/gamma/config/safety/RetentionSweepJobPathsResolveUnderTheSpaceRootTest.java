package com.gamma.config.safety;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.spec.RawConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the three committed {@code task: cleanup} job path values against the rule their runtime
 * actually applies ({@code JOB-PATH-DEMO-CONFIG-REPOINT-1}, re-pointed 2026-09-16) — the sibling of
 * {@link DemoBackupJobPathsResolveUnderTheSpaceRootTest} for the other runtime that has moved.
 *
 * <p>All three are {@code retention-sweep} template instances, so their {@code params.dir} reaches
 * {@code CleanupTask:36}, which already calls
 * {@link PathJail#requireJobPathUnderAny(java.util.List, Path, String, String)} with
 * {@code SpaceConfigRoot.current()}. ⛔ <b>Their reader was moved by {@code JOB-DIR-CWD-CONTAINMENT-1}
 * and the configs were not</b>, which is the same one-sided break {@code 3f384182} made for
 * {@code BackupTask} — a cleanup sweeping an empty doubled directory and reporting success.
 *
 * <p>⚠ The two example instances are served with a <b>different base</b>: {@code serve-example.sh:66}
 * passes {@code -Dassist.write.root=out/write} and registers no Space, so
 * {@code SpaceConfigRoot.forSpace} falls through to that property — the base is {@code <example>/out/write},
 * not a Space config root. That is why their correct spelling is {@code ../backup}, not {@code backup}.
 *
 * <p>⚠ Deliberately NOT a sweep over every committed job config: the remaining 25 surveyed values
 * belong to {@code PipelineJobRunner} (19) and the compactors (3, incl. {@code store}), which have
 * <b>not</b> moved — a repo-wide assertion would be red for reasons this change does not own
 * ({@code docs/superpower/job-path-compat-survey.md}).
 */
class RetentionSweepJobPathsResolveUnderTheSpaceRootTest {

    /** The reactor root — surefire's working directory is the MODULE directory, never the repo root. */
    private static Path repoRoot() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    /** The demo Space's config root — the base {@code SpaceConfigRoot.current()} yields at run time. */
    private static Path demoConfig() {
        return repoRoot().resolve("spaces").resolve("demo").resolve("config");
    }

    /** The served example's directory; {@code serve-example.sh} makes this the process CWD. */
    private static Path maintenanceLibrary() {
        return repoRoot().resolve("inspecto").resolve("examples")
                .resolve("06-serve").resolve("maintenance-library");
    }

    private static String dirOf(Path configFile) throws IOException {
        // ASSERTION, not an assumption: the corpus is COMMITTED. A renamed or moved file must fail
        // here rather than let the assertion below pass vacuously over an absent value.
        assertTrue(Files.exists(configFile), "committed job config is missing: " + configFile
                + " — this test proves nothing if the file it reads is not there");
        String v = RawConfig.str(ConfigCodec.toMap(Files.readString(configFile, StandardCharsets.UTF_8)),
                "job.params.dir");
        assertNotNull(v, "job.params.dir read back as null from " + configFile
                + " — the key was renamed or the value was lost in a TOON edit");
        return v;
    }

    @Test
    void theDemoRetentionSweepStillSweepsTheOrdersBackupDirectory() throws IOException {
        String dir = dirOf(demoConfig().resolve("jobs").resolve("backup_retention_job.toon"));
        // Byte-identical to what the legacy CWD rule produced from the repo root — the re-point is
        // behaviour-preserving, which is the only thing that makes it safe to land without a migration.
        assertEquals(repoRoot().resolve("spaces/demo/data/orders/backup"),
                PathJail.resolveJobPath(demoConfig(), dir, "dir"),
                "backup_retention's `dir` must resolve to spaces/demo/data/orders/backup");
    }

    @Test
    void theExampleRetentionSweepsResolveAgainstTheServeWriteRoot() throws IOException {
        Path ex = maintenanceLibrary();
        Path writeRoot = ex.resolve("out").resolve("write");   // -Dassist.write.root, serve-example.sh:66

        assertEquals(ex.resolve("out").resolve("backup"),
                PathJail.resolveJobPath(writeRoot, dirOf(ex.resolve("backup_retention_job.toon")), "dir"),
                "the example's backup_retention must still sweep <example>/out/backup");
        assertEquals(ex.resolve("out").resolve("quarantine"),
                PathJail.resolveJobPath(writeRoot, dirOf(ex.resolve("quarantine_retention_job.toon")), "dir"),
                "the example's quarantine_retention must still sweep <example>/out/quarantine");
    }

    /**
     * The COMPACTOR pair, added 2026-09-16 when landing this lane against master rather than the base it
     * was written on. ⛔ <b>The lane held these two back because in ITS worktree {@code PartitionCompactor}
     * still read a raw {@code Path.of(cfg.require("dir"))}</b> — true of its tree, false of master, where
     * {@code ea862d1b} had already moved the reader to
     * {@link PathJail#requireJobPathUnderAny(java.util.List, Path, String, String)} ({@code :63}).
     *
     * <p>⚠ So the same one-sided break happened a THIRD time in one day, and this time the reader that
     * outran its configs was ours: {@code 3f384182} did it to {@code BackupTask},
     * {@code JOB-DIR-CWD-CONTAINMENT-1} did it to {@code CleanupTask}, and {@code ea862d1b} did it here.
     * ⛔ <b>Moving a reader is not done until its committed configs move with it</b> — and a lane working
     * from a stale base cannot see that it is the one who moved it.
     */
    @Test
    void theCompactorJobsStillCompactTheDirectoriesTheyName() throws IOException {
        Map<String, Object> demo = ConfigCodec.toMap(Files.readString(
                demoConfig().resolve("jobs").resolve("orders_weekly_compact_job.toon"), StandardCharsets.UTF_8));
        assertEquals(repoRoot().resolve("spaces/demo/data/orders"),
                PathJail.resolveJobPath(demoConfig(), RawConfig.str(demo, "job.dir"), "dir"),
                "orders_weekly_compact must still compact spaces/demo/data/orders");

        Path ex = maintenanceLibrary();
        Map<String, Object> sample = ConfigCodec.toMap(Files.readString(
                ex.resolve("compact_job.toon"), StandardCharsets.UTF_8));
        assertEquals(ex.resolve("out").resolve("database"),
                PathJail.resolveJobPath(ex.resolve("out").resolve("write"),
                        RawConfig.str(sample, "job.dir"), "dir"),
                "the example's compact_sales must still compact <example>/out/database");
    }

    /**
     * The control that gives the assertions above their meaning: the values these replaced no longer
     * denote the directories they name. ⚠ Without this, a revert could read as an equally valid
     * authoring choice rather than the run-time breakage it is.
     *
     * <p>⛔ <b>This asserts the DOUBLING, not a refusal, and that is deliberate</b> — the lesson
     * recorded on {@link DemoBackupJobPathsResolveUnderTheSpaceRootTest}. {@code resolveJobPath}
     * refuses only when the <i>CWD-relative</i> path exists, and surefire's CWD is the module
     * directory; the same authored value refuses under a served example (whose runner pre-creates
     * {@code out/backup}) and re-points silently here. Asserting the refusal would pin this test's
     * working directory, not the rule.
     */
    @Test
    void theOldSpellingsNoLongerDenoteTheDirectoriesTheyName() {
        Path writeRoot = maintenanceLibrary().resolve("out").resolve("write");

        Path exResolved = PathJail.resolveJobPath(writeRoot, "out/backup", "dir");
        assertNotEquals(maintenanceLibrary().resolve("out").resolve("backup"), exResolved,
                "the pre-2026-09-16 example value must NOT resolve to <example>/out/backup — if it "
                        + "does, the rule changed and this test is pinning the wrong thing");
        assertEquals(writeRoot.resolve("out").resolve("backup"), exResolved,
                "it should double the write-root prefix — that is the shape of the break being fixed");

        Path demoResolved = PathJail.resolveJobPath(demoConfig(), "spaces/demo/data/orders/backup", "dir");
        assertNotEquals(repoRoot().resolve("spaces/demo/data/orders/backup"), demoResolved,
                "the pre-2026-09-16 demo value must NOT resolve to the Space's data directory");
        assertEquals(demoConfig().resolve("spaces/demo/data/orders/backup"), demoResolved,
                "it should double the Space prefix");
    }
}
