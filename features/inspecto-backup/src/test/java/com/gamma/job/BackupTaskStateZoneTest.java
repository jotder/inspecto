package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SE-4 step 1: a backup of a Space's {@code config/} carries the Space's {@code state/} zone beside it (as
 * {@code ../state/…} entries, the secret rule applied), and a restore into a Space's {@code config/} puts them back
 * under that Space's {@code state/} — and nowhere else. Package {@code com.gamma.job} like {@code BackupTaskTest}.
 */
class BackupTaskStateZoneTest {

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("m", JobType.MAINTENANCE, null, null, true, false, params);
    }

    private static Path onlyZip(Path backupDir) throws Exception {
        try (var s = Files.list(backupDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".zip")).findFirst().orElseThrow();
        }
    }

    @Test
    void aConfigBackupCarriesTheStateZoneAndRestoresItBesideConfig(@TempDir Path tmp) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("demo").resolve("config"));
        Files.writeString(config.resolve("roles.toon"), "roles");
        Path state = tmp.resolve("demo").resolve("state");
        Files.writeString(Files.createDirectories(state.resolve("recon-state")).resolve("orders.json"), "{\"runs\":1}");
        Files.writeString(Files.createDirectories(state.resolve("action-requests")).resolve("ar.json"), "payload");
        Path backups = Files.createDirectories(tmp.resolve("backups"));

        JobResult r = new MaintenanceJob(job(Map.of("task", "backup", "dir", config.toString(),
                "backup_dir", backups.toString()))).run();
        assertEquals("SUCCESS", r.status(), r.message());
        Path zip = onlyZip(backups);
        try (ZipFile z = new ZipFile(zip.toFile())) {
            assertNotNull(z.getEntry("roles.toon"));
            assertNotNull(z.getEntry("../state/recon-state/orders.json"), "the state zone rides with its config");
            assertNull(z.getEntry("../state/action-requests/ar.json"), "the secret rule holds in state/ too");
        }

        Path target = Files.createDirectories(tmp.resolve("restored").resolve("config"));
        JobResult restored = new MaintenanceJob(job(Map.of("task", "restore", "archive", zip.toString(),
                "target_dir", target.toString()))).run();
        assertEquals("SUCCESS", restored.status(), restored.message());
        assertEquals("roles", Files.readString(target.resolve("roles.toon")));
        assertEquals("{\"runs\":1}", Files.readString(tmp.resolve("restored").resolve("state").resolve("recon-state")
                .resolve("orders.json")), "back beside config/, not inside it");
        assertFalse(Files.exists(target.resolve("state")), "never inside config/");
    }

    @Test
    void aSpaceWithoutStateBacksUpExactlyAsBefore(@TempDir Path tmp) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("demo").resolve("config"));
        Files.writeString(config.resolve("roles.toon"), "roles");
        Path backups = Files.createDirectories(tmp.resolve("backups"));
        new MaintenanceJob(job(Map.of("task", "backup", "dir", config.toString(), "backup_dir", backups.toString()))).run();
        try (ZipFile z = new ZipFile(onlyZip(backups).toFile())) {
            assertTrue(z.stream().noneMatch(e -> e.getName().startsWith("../")), "no state/, no extra entry");
        }
        assertFalse(Files.exists(tmp.resolve("demo").resolve("state")), "a backup never makes state/");
    }

    @Test
    void aStateEntryNeverRestoresIntoAFolderThatIsNotASpaceConfig(@TempDir Path tmp) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("demo").resolve("config"));
        Files.writeString(Files.createDirectories(tmp.resolve("demo").resolve("state").resolve("recon-state"))
                .resolve("orders.json"), "{}");
        Path backups = Files.createDirectories(tmp.resolve("backups"));
        new MaintenanceJob(job(Map.of("task", "backup", "dir", config.toString(), "backup_dir", backups.toString()))).run();
        Path plain = Files.createDirectories(tmp.resolve("plain"));
        JobResult r = new MaintenanceJob(job(Map.of("task", "restore", "archive", onlyZip(backups).toString(),
                "target_dir", plain.toString()))).run();
        assertEquals("FAILED", r.status(), r.message());
        assertTrue(r.message().contains("entry escapes target_dir: ../state/recon-state/orders.json"), r.message());
        assertFalse(Files.exists(tmp.resolve("state")));
    }

    @Test
    void aLinkedStateFolderIsNeitherBackedUpNorRestoredThrough(@TempDir Path tmp) throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside").resolve("recon-state"));
        Files.writeString(outside.resolve("orders.json"), "evil");
        Path config = Files.createDirectories(tmp.resolve("demo").resolve("config"));
        Files.writeString(config.resolve("roles.toon"), "roles");
        link(tmp.resolve("demo").resolve("state"), outside.getParent());
        Path backups = Files.createDirectories(tmp.resolve("backups"));
        new MaintenanceJob(job(Map.of("task", "backup", "dir", config.toString(), "backup_dir", backups.toString()))).run();
        try (ZipFile z = new ZipFile(onlyZip(backups).toFile())) {
            assertTrue(z.stream().noneMatch(e -> e.getName().startsWith("../")), "a linked state/ is not archived");
        }

        // a genuine archive with a state entry, restored into a Space whose state/ is a link: refused, nothing written
        Path src = Files.createDirectories(tmp.resolve("good").resolve("config"));
        Files.writeString(Files.createDirectories(tmp.resolve("good").resolve("state").resolve("recon-state"))
                .resolve("orders.json"), "{}");
        Path backups2 = Files.createDirectories(tmp.resolve("backups2"));
        new MaintenanceJob(job(Map.of("task", "backup", "dir", src.toString(), "backup_dir", backups2.toString()))).run();
        Path target = Files.createDirectories(tmp.resolve("victim").resolve("config"));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        link(tmp.resolve("victim").resolve("state"), elsewhere);
        JobResult r = new MaintenanceJob(job(Map.of("task", "restore", "archive", onlyZip(backups2).toString(),
                "target_dir", target.toString()))).run();
        assertEquals("FAILED", r.status(), r.message());
        assertTrue(r.message().contains("entry escapes target_dir"), r.message());
        assertFalse(Files.exists(elsewhere.resolve("recon-state")), "nothing written through the link");
    }

    /** {@code link} → {@code target} as a symlink, else (Windows without the privilege) a junction; the test is skipped when neither can be made. */
    static void link(Path link, Path target) throws Exception {
        java.nio.file.Files.createDirectories(link.getParent());
        try {
            java.nio.file.Files.createSymbolicLink(link, target);
            return;
        } catch (Exception noSymlink) {
            // fall through to a junction
        }
        boolean made = false;
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            made = p.waitFor() == 0 && java.nio.file.Files.isDirectory(link);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(made, "this OS cannot create a symlink or junction here");
    }
}
