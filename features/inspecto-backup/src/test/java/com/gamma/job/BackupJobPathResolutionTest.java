package com.gamma.job;

import com.gamma.event.EventLog;
import com.gamma.pipeline.SpaceConfigRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three backup tasks resolve a RELATIVE path value against the <b>Space config root</b>, not the
 * process working directory — `JOB-PATH-BACKUPTASK-SPLIT-1`, the residual of
 * `JOB-DIR-CWD-CONTAINMENT-1` (operator 2026-09-16).
 *
 * <p>⛔ <b>What these pin is agreement, not containment</b> ({@link BackupPathContainmentTest} covers
 * containment, and every value there is absolute so it never exercised resolution). The 422 write gate
 * ({@code ConfigSafetyValidator.checkJob}) resolved {@code dir} / {@code backup_dir} / {@code archive} /
 * {@code target_dir} against the Space root from the day that row shipped, while this module — which owns
 * four of those five uses — still called the plain {@code PathJail.requireUnderAny} and so resolved against
 * the CWD. A job could be refused at SAVE and still run reading somewhere else.
 *
 * <p>⚠ Each case is built so the CWD reading is an <b>escape from the jail</b>: the jail is narrowed to a
 * {@code @TempDir}, so a value resolved against the module's working directory cannot be contained and
 * throws. Mutating the task back to {@code requireUnderAny} turns every test here red for exactly that
 * reason, naming the field under test.
 */
class BackupJobPathResolutionTest {

    private String priorRoots;

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("m", JobType.MAINTENANCE, null, null, true, false, params);
    }

    @BeforeEach
    void narrowAndClear() {
        priorRoots = System.getProperty("assist.safety.roots");
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

    /**
     * Jail to {@code root} and publish {@code root/config} as the named Space's config root — the shape
     * {@code SpaceBootstrap} registers, and the one {@code SpaceConfigRoot.current()} reads off the MDC.
     */
    private Path spaceAt(Path root) throws Exception {
        System.setProperty("assist.safety.roots", root.toAbsolutePath().normalize().toString());
        Path config = Files.createDirectories(root.resolve("config"));
        SpaceConfigRoot.register("bk", config);
        MDC.put(EventLog.SPACE_MDC_KEY, "bk");
        return config;
    }

    @Test
    void backupResolvesRelativeDirAndBackupDirAgainstTheSpaceRoot(@TempDir Path root) throws Exception {
        Path config = spaceAt(root);
        Path src = Files.createDirectories(config.resolve("src"));
        Files.writeString(src.resolve("a.txt"), "x");

        JobResult r = new MaintenanceJob(job(Map.of("task", "backup",
                "dir", "src", "backup_dir", "backups"))).run();

        assertEquals("SUCCESS", r.status(), r.message());
        Path backups = config.resolve("backups");
        assertTrue(Files.isDirectory(backups),
                "the archive must land under the SPACE root, not the working directory: " + r.message());
        try (Stream<Path> s = Files.list(backups)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().endsWith(".zip")));
        }
    }

    @Test
    void verifyResolvesARelativeBackupDirAgainstTheSpaceRoot(@TempDir Path root) throws Exception {
        Path config = spaceAt(root);
        // ⚠ The SETUP uses ABSOLUTE values so it cannot fail for the reason under test — otherwise a
        // regression in `backup` would turn this red here and the `backup_verify` site would go unprobed.
        Path src = Files.createDirectories(config.resolve("src"));
        Files.writeString(src.resolve("a.txt"), "x");
        assertEquals("SUCCESS", new MaintenanceJob(job(Map.of("task", "backup",
                "dir", src.toString(),
                "backup_dir", config.resolve("backups").toString()))).run().status());

        JobResult r = new MaintenanceJob(job(Map.of("task", "backup_verify",
                "backup_dir", "backups"))).run();

        assertEquals("SUCCESS", r.status(), r.message());
        // Not "no archive to verify" — that message is what a backup_dir resolved somewhere EMPTY says,
        // and it is also ok(), so asserting ok() alone would pass on the wrong directory.
        assertTrue(r.message().contains("1 archive(s) OK"), r.message());
    }

    @Test
    void restoreResolvesARelativeArchiveAndTargetDirAgainstTheSpaceRoot(@TempDir Path root) throws Exception {
        Path config = spaceAt(root);
        // Absolute in the setup, relative in the act — see the note on the verify case.
        Path src = Files.createDirectories(config.resolve("src"));
        Files.writeString(src.resolve("a.txt"), "payload");
        assertEquals("SUCCESS", new MaintenanceJob(job(Map.of("task", "backup",
                "dir", src.toString(), "backup_dir", config.resolve("backups").toString(),
                "prefix", "snap"))).run().status());
        String zipName;
        try (Stream<Path> s = Files.list(config.resolve("backups"))) {
            zipName = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".zip")).findFirst().orElseThrow();
        }

        JobResult r = new MaintenanceJob(job(Map.of("task", "restore",
                "archive", "backups/" + zipName, "target_dir", "restored"))).run();

        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals("payload", Files.readString(config.resolve("restored").resolve("a.txt")));
    }

    /**
     * {@code target_dir} on its own. ⚠ In the case above {@code archive} is resolved FIRST, so a mutation
     * there hides {@code target_dir} behind it — this one keeps {@code archive} absolute so only
     * {@code target_dir} can be the reason it turns red.
     */
    @Test
    void restoreResolvesARelativeTargetDirAgainstTheSpaceRoot(@TempDir Path root) throws Exception {
        Path config = spaceAt(root);
        Path src = Files.createDirectories(config.resolve("src"));
        Files.writeString(src.resolve("a.txt"), "payload");
        assertEquals("SUCCESS", new MaintenanceJob(job(Map.of("task", "backup",
                "dir", src.toString(), "backup_dir", config.resolve("backups").toString(),
                "prefix", "snap"))).run().status());
        Path zip;
        try (Stream<Path> s = Files.list(config.resolve("backups"))) {
            zip = s.filter(p -> p.getFileName().toString().endsWith(".zip")).findFirst().orElseThrow();
        }

        JobResult r = new MaintenanceJob(job(Map.of("task", "restore",
                "archive", zip.toString(), "target_dir", "restored"))).run();

        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals("payload", Files.readString(config.resolve("restored").resolve("a.txt")));
    }

    /**
     * ⛔ The refusal the operator chose, reaching the backup runtime too: a relative value absent under the
     * Space root but present where the OLD working-directory rule would have put it throws naming BOTH
     * paths, rather than silently reading the other one.
     */
    @Test
    void theAmbiguousCaseIsRefusedAtRunTimeToo(@TempDir Path root) throws Exception {
        spaceAt(root);
        Path cwdRelative = Path.of("target", "backup-path-probe-" + java.util.UUID.randomUUID());
        Files.createDirectories(cwdRelative);
        try {
            String authored = "target/" + cwdRelative.getFileName();
            com.gamma.config.safety.PathJail.Escape e = assertThrows(
                    com.gamma.config.safety.PathJail.Escape.class,
                    () -> new MaintenanceJob(job(Map.of("task", "backup",
                            "dir", authored, "backup_dir", "backups"))).run());
            assertEquals("dir", e.field());
            assertTrue(e.getMessage().contains("not resolved silently"), e.getMessage());
        } finally {
            Files.deleteIfExists(cwdRelative);
        }
    }

    /** A null Space root keeps the legacy working-directory behaviour — no MDC, no registration. */
    @Test
    void aNullSpaceRootKeepsTheLegacyBehaviour(@TempDir Path root) throws Exception {
        System.setProperty("assist.safety.roots", root.toAbsolutePath().normalize().toString());
        // No space registered and the MDC is clear, so SpaceConfigRoot.current() is null (the default
        // space's `assist.write.root` fallback is not set in this module's surefire).
        assumeNoWriteRoot();
        Path cwdRelative = Path.of("target", "backup-legacy-probe-" + java.util.UUID.randomUUID());
        Files.createDirectories(cwdRelative);
        try {
            // Resolved against the CWD, which is NOT under the narrowed jail ⇒ refused by containment,
            // proving the legacy resolution still happened rather than a Space-root one.
            com.gamma.config.safety.PathJail.Escape e = assertThrows(
                    com.gamma.config.safety.PathJail.Escape.class,
                    () -> new MaintenanceJob(job(Map.of("task", "backup",
                            "dir", "target/" + cwdRelative.getFileName(),
                            "backup_dir", "backups"))).run());
            assertEquals("dir", e.field());
            assertFalse(e.getMessage().contains("Space config root"),
                    "with no Space there is nothing to refuse for ambiguity: " + e.getMessage());
        } finally {
            Files.deleteIfExists(cwdRelative);
        }
    }

    private static void assumeNoWriteRoot() {
        String wr = System.getProperty("assist.write.root");
        org.junit.jupiter.api.Assumptions.assumeTrue(wr == null || wr.isBlank(),
                "another test left assist.write.root set; this case needs a genuinely null Space root");
    }
}
