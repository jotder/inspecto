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
 * Pins the three committed {@code spaces/demo} backup job path values against the rule their runtime
 * actually applies ({@code JOB-PATH-DEMO-CONFIG-REPOINT-1}, re-pointed 2026-09-16).
 *
 * <p>Why this test exists: {@code JOB-PATH-BACKUPTASK-SPLIT-1} moved {@code BackupTask} onto
 * {@link PathJail#resolveJobPath} <b>without</b> re-pointing the configs it reads, and nothing went
 * red — the values are nested under {@code params:}, which the dotted-path 422 gate cannot see, so
 * {@code config_backup} began failing at RUN and {@code backup_verify} began returning a green
 * "no archive to verify" over a doubled directory that exists nowhere. ⛔ <b>A config value and the
 * rule its reader applies are a pair, and until this test there was nothing holding them together.</b>
 *
 * <p>⚠ This is deliberately NOT a sweep over every committed job config: the other 29 surveyed values
 * belong to runtimes ({@code PipelineJobRunner}, the compactors) that have <b>not</b> moved, so a
 * repo-wide assertion would be red for reasons this change does not own
 * ({@code docs/superpower/job-path-compat-survey.md}).
 */
class DemoBackupJobPathsResolveUnderTheSpaceRootTest {

    /** The demo Space's config root — the base {@code SpaceConfigRoot.current()} yields at run time. */
    private static Path demoConfig() {
        return Path.of("..", "spaces", "demo", "config").toAbsolutePath().normalize();
    }

    private static String value(String jobFile, String dottedPath) throws IOException {
        Path f = demoConfig().resolve("jobs").resolve(jobFile);
        // ASSERTION, not an assumption: the corpus is COMMITTED. A renamed or moved file must fail
        // here rather than let every assertion below pass vacuously over an absent value.
        assertTrue(Files.exists(f), "committed job config is missing: " + f
                + " — this test proves nothing if the file it reads is not there");
        String v = RawConfig.str(ConfigCodec.toMap(Files.readString(f, StandardCharsets.UTF_8)), dottedPath);
        assertNotNull(v, dottedPath + " read back as null from " + f
                + " — the key was renamed or the value was lost in a TOON edit");
        return v;
    }

    @Test
    void configBackupBacksUpTheSpaceConfigRootItself() throws IOException {
        String dir = value("config_backup_job.toon", "job.params.dir");
        assertEquals(demoConfig(), PathJail.resolveJobPath(demoConfig(), dir, "dir"),
                "config_backup's `dir` must resolve to the demo Space config root");
    }

    @Test
    void configBackupWritesItsArchivesToTheSpaceDataDirectory() throws IOException {
        String backupDir = value("config_backup_job.toon", "job.params.backup_dir");
        assertEquals(demoConfig().resolveSibling("data").resolve("backups"),
                PathJail.resolveJobPath(demoConfig(), backupDir, "backup_dir"),
                "config_backup's `backup_dir` must resolve to spaces/demo/data/backups");
    }

    @Test
    void backupVerifyReadsTheSameDirectoryConfigBackupWrote() throws IOException {
        String verifyDir = value("backup_verify_job.toon", "job.backup_dir");
        String writeDir  = value("config_backup_job.toon", "job.params.backup_dir");
        Path verified = PathJail.resolveJobPath(demoConfig(), verifyDir, "backup_dir");
        assertEquals(demoConfig().resolveSibling("data").resolve("backups"), verified);
        // The chain is only meaningful if verify reads exactly what backup wrote. Asserting the two
        // RESOLVED paths (not the two authored strings) is what catches a half-done re-point.
        assertEquals(PathJail.resolveJobPath(demoConfig(), writeDir, "backup_dir"), verified,
                "backup_verify must verify the directory config_backup writes to");
    }

    /**
     * The control that gives the three assertions above their meaning: the value these replaced no
     * longer denotes the config root. ⚠ Without this, a revert to the old spelling could read as an
     * equally valid authoring choice rather than the run-time breakage it is.
     *
     * <p>⛔ <b>This asserts the DOUBLING, not a refusal, and that is deliberate.</b> The first version
     * of this test asserted {@code PathJail.Escape} and was RED — {@link PathJail#resolveJobPath}
     * refuses only when the <i>CWD-relative</i> path EXISTS, and surefire's working directory is the
     * module directory, where {@code spaces/demo/config} does not exist. The same authored value
     * therefore refuses on a server started from the repo root and re-points silently here. Asserting
     * the refusal would have pinned the test's own working directory, not the rule; asserting that the
     * value does not resolve to the config root holds in <b>both</b> columns.
     */
    @Test
    void theOldCwdPrefixedSpellingNoLongerDenotesTheSpaceConfigRoot() {
        Path resolved = PathJail.resolveJobPath(demoConfig(), "spaces/demo/config", "dir");
        assertNotEquals(demoConfig(), resolved,
                "the pre-2026-09-16 value must NOT resolve to the config root — if it does, the rule "
                        + "changed and this whole test file is pinning the wrong thing");
        assertEquals(demoConfig().resolve("spaces").resolve("demo").resolve("config"), resolved,
                "it should double the Space prefix — that is the shape of the break being fixed");
    }

    /**
     * The refusal branch is REACHABLE, and is keyed on EXISTENCE rather than on the value's shape.
     * ⚠ Both halves are the point: a probe that cannot fire reports a clean bill of health. The
     * negative case is what proves the refusal is not simply thrown for every relative value.
     */
    @Test
    void theRefusalBranchFiresOnlyWhenTheWorkingDirectoryPathExists() {
        // `pom.xml` exists relative to the module directory surefire runs in; the doubled path does not.
        PathJail.Escape e = assertThrows(PathJail.Escape.class,
                () -> PathJail.resolveJobPath(demoConfig(), "pom.xml", "dir"),
                "the ambiguous-case refusal did not fire for a value that DOES exist CWD-relative "
                        + "— the branch every assertion here relies on may be dead");
        assertTrue(e.getMessage().contains("Space config root"), e.getMessage());

        assertDoesNotThrow(() -> PathJail.resolveJobPath(demoConfig(), "src/nonexistent", "dir"),
                "a relative value whose CWD-relative path does NOT exist must re-point silently, not "
                        + "refuse — otherwise the assertion above proves nothing about existence");
    }
}
