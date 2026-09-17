package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code COMPACT-SUCCEEDS-ON-A-MISSING-DIR-1}: the four maintenance tasks that walk an operator-authored
 * {@code dir} all guarded it with a bare {@code !Files.isDirectory(dir)} and reported SUCCESS on every
 * failing answer — so a {@code dir} that names an existing FILE was reported as "directory not present,
 * nothing to do".
 *
 * <p><b>What this test does NOT claim.</b> A {@code dir} that is merely absent stays a successful no-op,
 * deliberately: a path under the jail that nothing has written yet is indistinguishable from a typo'd one,
 * and the two cases below pin that the fix did not quietly turn the legitimate no-op into an error. The one
 * case that IS decidable at this point is "the path exists and is not a directory", and that is the only
 * thing promoted to a failure here.
 */
class MaintenanceDirNotADirectoryTest {

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("m", JobType.MAINTENANCE, null, null, true, false, params);
    }

    /** A regular file standing exactly where the task's {@code dir} points. */
    private static String fileNamedAsADir(Path root, String name) throws Exception {
        return Files.writeString(root.resolve(name), "not a directory").toString();
    }

    // ── the decidable case: dir exists, but is not a directory ───────────────────

    @Test
    void compactRefusesADirThatNamesAFile(@TempDir Path root) throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceJob(job(Map.of("task", "compact",
                        "dir", fileNamedAsADir(root, "database")))).run());
        assertTrue(e.getMessage().contains("not a directory"),
                () -> "the message must say why the config is broken: " + e.getMessage());
    }

    @Test
    void cleanupRefusesADirThatNamesAFile(@TempDir Path root) throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceJob(job(Map.of("task", "cleanup",
                        "dir", fileNamedAsADir(root, "logs")))).run());
    }

    @Test
    void storageReportRefusesADirThatNamesAFile(@TempDir Path root) throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceJob(job(Map.of("task", "storage_report",
                        "dir", fileNamedAsADir(root, "store")))).run());
    }

    @Test
    void partitionPruneRefusesADirThatNamesAFile(@TempDir Path root) throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceJob(job(Map.of("task", "partition_prune", "retention_days", "1",
                        "dir", fileNamedAsADir(root, "sink")))).run());
    }

    // ── the two cases that must STAY a successful no-op ──────────────────────────

    @Test
    void compactStillSucceedsWhenTheDirIsMerelyAbsent(@TempDir Path root) throws Exception {
        JobResult r = new MaintenanceJob(job(Map.of("task", "compact",
                "dir", root.resolve("never-written").toString()))).run();
        assertTrue(r.success(), () -> "an absent dir is a legitimate no-op, not an error: " + r.message());
    }

    @Test
    void compactStillSucceedsWhenTheDirExistsAndIsEmpty(@TempDir Path root) throws Exception {
        Path empty = Files.createDirectories(root.resolve("empty"));
        JobResult r = new MaintenanceJob(job(Map.of("task", "compact", "dir", empty.toString()))).run();
        assertTrue(r.success(), () -> "an empty dir has nothing to compact, which is success: " + r.message());
    }
}
