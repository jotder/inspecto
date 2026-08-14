package com.gamma.job;

import com.gamma.config.safety.PathJail;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3 of the path-containment unification: every operator-supplied path field on a job task resolves
 * under the allowed roots, or the task refuses.
 *
 * <p>These tests narrow {@code assist.safety.roots} to a single temp dir, deliberately overriding the
 * permissive sandbox the surefire config sets for the rest of the suite — otherwise an escape would be
 * "contained" by the temp root and every assertion here would pass for the wrong reason.
 *
 * <p>⚠ The escape values are built from a real sibling directory rather than a literal like
 * {@code /etc/passwd}: on Windows an absolute POSIX path normalises to something under the current
 * drive and can land <em>inside</em> the root by accident, which would make these tests green while
 * testing nothing. See {@code escapesTo}.
 */
class JobPathContainmentTest {

    private String priorRoots;

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("m", JobType.MAINTENANCE, null, null, true, false, params);
    }

    /** Narrow the jail to {@code root} alone, saving whatever surefire or another test had set. */
    private void jailTo(Path root) {
        priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", root.toAbsolutePath().normalize().toString());
    }

    @AfterEach
    void restoreRoots() {
        if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
        else System.clearProperty("assist.safety.roots");
    }

    /**
     * A path that provably escapes {@code root}: a real sibling of it, reached via {@code ..} so the
     * value looks like something an operator would actually author.
     */
    private static String escapesTo(Path root, String name) throws Exception {
        Path sibling = root.getParent().resolve(name);
        Files.createDirectories(sibling);
        assertFalse(PathJail.contains(root, sibling), "fixture is not an escape — the test would be vacuous");
        return root.resolve("..").resolve(name).toString();
    }

    // ── cleanup ──────────────────────────────────────────────────────────────────

    @Test
    void cleanupRefusesADirOutsideTheAllowedRoots(@TempDir Path root) throws Exception {
        jailTo(root);
        String outside = escapesTo(root, "elsewhere");
        PathJail.Escape e = assertThrows(PathJail.Escape.class,
                () -> new MaintenanceJob(job(Map.of("task", "cleanup", "dir", outside))).run());
        assertEquals("dir", e.field(), "the message must name the offending field");
    }

    @Test
    void cleanupRefusesAnArchiveDirOutsideTheAllowedRootsEvenWhenDirIsFine(@TempDir Path root) throws Exception {
        jailTo(root);
        Path junk = Files.createDirectories(root.resolve("junk"));
        String outside = escapesTo(root, "exfil");
        PathJail.Escape e = assertThrows(PathJail.Escape.class,
                () -> new MaintenanceJob(job(Map.of("task", "cleanup", "dir", junk.toString(),
                        "retention_days", "0", "archive_instead_of_delete", "true",
                        "archive_dir", outside))).run());
        assertEquals("archive_dir", e.field());
    }

    @Test
    void cleanupStillRunsWhenBothDirsAreInsideTheRoots(@TempDir Path root) throws Exception {
        jailTo(root);
        Path junk = Files.createDirectories(root.resolve("junk"));
        Files.writeString(junk.resolve("a.log"), "x");
        JobResult r = new MaintenanceJob(job(Map.of("task", "cleanup", "dir", junk.toString(),
                "retention_days", "0", "archive_instead_of_delete", "true",
                "archive_dir", root.resolve("archive").toString()))).run();
        assertTrue(r.success(), () -> "a contained cleanup must not be refused: " + r.message());
    }

    // ── storage_report (read-only, but still jailed) ─────────────────────────────

    @Test
    void storageReportRefusesADirOutsideTheAllowedRoots(@TempDir Path root) throws Exception {
        jailTo(root);
        String outside = escapesTo(root, "secrets");
        PathJail.Escape e = assertThrows(PathJail.Escape.class,
                () -> new MaintenanceJob(job(Map.of("task", "storage_report", "dir", outside))).run());
        assertEquals("dir", e.field(),
                "read-only is not exempt — the walk logs every largest file by full path");
    }

    // ── backup / backup_verify / restore ─────────────────────────────────────────

    @Test
    void backupRefusesABackupDirOutsideTheAllowedRoots(@TempDir Path root) throws Exception {
        jailTo(root);
        Path src = Files.createDirectories(root.resolve("src"));
        Files.writeString(src.resolve("a.txt"), "x");
        String outside = escapesTo(root, "backups");
        PathJail.Escape e = assertThrows(PathJail.Escape.class,
                () -> new MaintenanceJob(job(Map.of("task", "backup", "dir", src.toString(),
                        "backup_dir", outside))).run());
        assertEquals("backup_dir", e.field());
    }

    @Test
    void restoreRefusesATargetDirOutsideTheAllowedRoots(@TempDir Path root) throws Exception {
        jailTo(root);
        Path archive = root.resolve("b.zip");
        Files.writeString(archive, "not really a zip");
        String outside = escapesTo(root, "restored");
        PathJail.Escape e = assertThrows(PathJail.Escape.class,
                () -> new MaintenanceJob(job(Map.of("task", "restore", "archive", archive.toString(),
                        "target_dir", outside))).run());
        assertEquals("target_dir", e.field(),
                "target_dir must be jailed BEFORE the archive is opened, not after");
    }

    /**
     * {@code archive} on backup_verify names a file inside {@code backup_dir}, so it is jailed against
     * that dir — a traversal here reads back out of the box even though backup_dir itself is legal.
     */
    // ── report delivery ──────────────────────────────────────────────────────────

    /**
     * {@code out_dir} is the one S3 field on a {@link ReportJob} rather than a maintenance task, and
     * the only one reached through {@code assist.write.root} scaffolding — so it gets its own case
     * rather than being assumed covered by the maintenance ones.
     */
    @Test
    void reportDeliveryRefusesAnOutDirOutsideTheAllowedRoots(@TempDir Path root, @TempDir Path writeRoot)
            throws Exception {
        jailTo(root);
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0)) AS t(region,amount)", "2026-07-08T00:00:00Z"));
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view"));
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            String outside = escapesTo(root, "exfil-reports");
            PathJail.Escape e = assertThrows(PathJail.Escape.class,
                    () -> new ReportJob(new JobConfig("weekly_sales", JobType.REPORT, null, null, true, false,
                            Map.of("scope", "dataset", "dataset", "sales_ds", "out_dir", outside)), null).run());
            assertEquals("out_dir", e.field());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    @Test
    void verifyRefusesAnArchiveThatTraversesOutOfTheBackupDir(@TempDir Path root) throws Exception {
        jailTo(root);
        Path backups = Files.createDirectories(root.resolve("backups"));
        Files.writeString(root.resolve("outside.zip"), "x");
        PathJail.Escape e = assertThrows(PathJail.Escape.class,
                () -> new MaintenanceJob(job(Map.of("task", "backup_verify",
                        "backup_dir", backups.toString(), "archive", "../outside.zip"))).run());
        assertEquals("archive", e.field());
    }
}
