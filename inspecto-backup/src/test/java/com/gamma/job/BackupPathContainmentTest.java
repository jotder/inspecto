package com.gamma.job;

import com.gamma.config.safety.PathJail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Path containment for the three backup tasks — every operator-supplied directory is jailed against the
 * allowed roots BEFORE anything is read or written. Moved VERBATIM from the engine's
 * {@code JobPathContainmentTest} on 2026-09-07 (EDG-01 cell 2); the other tasks' cases stayed there.
 *
 * <p>⚠ On Windows a {@code @TempDir} lives on the same drive as the repo and can land <em>inside</em> the
 * root by accident, which would make these tests green while testing nothing — hence {@link #escapesTo}
 * proves the fixture escapes first.
 */
class BackupPathContainmentTest {

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
