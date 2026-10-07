package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link JobPackManager#sweepOrphans}: a crashed process's {@code job-packs-*} staging dir is removed at the
 * next use, but only when proven orphaned (its sibling {@code <dir>.lock} is free), and nothing outside the staging
 * root is ever deleted.
 */
class JobPackStagingSweepTest {

    /** What a crashed process leaves: a dir with an (unheld) owner.lock and a staged copy. */
    private static Path crashedDir(Path root, String name) throws IOException {
        Path d = Files.createDirectories(root.resolve(name));
        Files.createFile(JobPackManager.lockFor(d));
        Files.writeString(d.resolve("pack-1-greet.jar"), "stale");
        return d;
    }

    @Test
    void anOrphanedDirIsRemoved(@TempDir Path root) throws Exception {
        Path orphan = crashedDir(root, "job-packs-1");
        assertEquals(List.of(orphan), JobPackManager.sweepOrphans(root));
        assertFalse(Files.exists(orphan));
        assertFalse(Files.exists(JobPackManager.lockFor(orphan)), "its lock goes too");
    }

    @Test
    void aDirWhoseLockIsHeldIsKept(@TempDir Path root) throws Exception {
        Path live = crashedDir(root, "job-packs-live");
        try (FileChannel ch = FileChannel.open(JobPackManager.lockFor(live), StandardOpenOption.WRITE);
             FileLock held = ch.lock()) {
            assertEquals(List.of(), JobPackManager.sweepOrphans(root));
            assertTrue(Files.exists(live.resolve("pack-1-greet.jar")), "a live owner's copy survives");
        }
    }

    /** The production case: the owner is ANOTHER process, so the in-JVM overlap guard does not apply. */
    @Test
    void aDirLockedByAnotherLiveProcessIsKept(@TempDir Path root) throws Exception {
        Path live = crashedDir(root, "job-packs-other");
        Path src = Files.writeString(root.resolve("Locker.java"), """
                import java.nio.channels.FileChannel; import java.nio.file.*;
                public class Locker { public static void main(String[] a) throws Exception {
                    try (var ch = FileChannel.open(Path.of(a[0]), StandardOpenOption.WRITE); var l = ch.lock()) {
                        System.out.println("locked"); System.out.flush(); System.in.read(); } } }
                """);
        String javaBin = ProcessHandle.current().info().command().orElseThrow();
        Process p = new ProcessBuilder(javaBin, src.toString(), JobPackManager.lockFor(live).toString()).start();
        try {
            assertEquals("locked", new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream())).readLine());
            assertEquals(List.of(), JobPackManager.sweepOrphans(root));
            assertTrue(Files.exists(live.resolve("pack-1-greet.jar")), "another process's live copy survives");
        } finally {
            p.getOutputStream().close();
            p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
        }
        assertEquals(List.of(live), JobPackManager.sweepOrphans(root), "once that process is gone it is an orphan");
    }

    @Test
    void aDirWithNoLockCannotBeProvenOrphanedAndIsKept(@TempDir Path root) throws Exception {
        Path d = Files.createDirectories(root.resolve("job-packs-old"));
        Files.writeString(d.resolve("pack-1-greet.jar"), "x");
        assertEquals(List.of(), JobPackManager.sweepOrphans(root));
        assertTrue(Files.exists(d.resolve("pack-1-greet.jar")));
    }

    @Test
    void aSymlinkedDirIsNeverFollowed(@TempDir Path work) throws Exception {
        Path root = Files.createDirectories(work.resolve("root"));
        Path outside = crashedDir(work, "outside");
        Path link = root.resolve("job-packs-escape");
        try { Files.createSymbolicLink(link, outside); }
        catch (IOException | UnsupportedOperationException e) { assumeTrue(false, "no symlink privilege: " + e); }
        assertEquals(List.of(), JobPackManager.sweepOrphans(root));
        assertTrue(Files.exists(outside.resolve("pack-1-greet.jar")), "the escape target is untouched");
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void aSymlinkInsideAnOrphanIsNotFollowedAndTheDirStays(@TempDir Path work) throws Exception {
        Path root = Files.createDirectories(work.resolve("root"));
        Path victim = Files.writeString(work.resolve("victim.txt"), "keep");
        Path orphan = crashedDir(root, "job-packs-1");
        try { Files.createSymbolicLink(orphan.resolve("pack-2-evil.jar"), victim); }
        catch (IOException | UnsupportedOperationException e) { assumeTrue(false, "no symlink privilege: " + e); }
        assertEquals(List.of(), JobPackManager.sweepOrphans(root));
        assertEquals("keep", Files.readString(victim));
        assertTrue(Files.exists(orphan));
    }

    /** Windows junctions need no privilege and are NOT reported by isSymbolicLink — the real-path check. */
    @Test
    void aJunctionedDirIsNeverFollowed(@TempDir Path work) throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "junctions are Windows-only");
        Path root = Files.createDirectories(work.resolve("root"));
        Path outside = crashedDir(work, "outside");
        Path junction = root.resolve("job-packs-escape");
        Files.createFile(JobPackManager.lockFor(junction));
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true).start();
        assumeTrue(p.waitFor() == 0, "mklink /J failed");
        assertEquals(List.of(), JobPackManager.sweepOrphans(root));
        assertTrue(Files.exists(outside.resolve("pack-1-greet.jar")), "the junction target is untouched");
        Files.delete(junction);   // remove the junction itself before @TempDir cleanup walks it
    }

    @Test
    void aSecondManagerSweepsACrashedDirButNotALiveManagersDir(@TempDir Path work) throws Exception {
        Path packs = Files.createDirectories(work.resolve("packs"));
        Path root = work.resolve("staging");
        Path orphan = crashedDir(root, "job-packs-crashed");
        Files.writeString(packs.resolve("a.jar"), "not a jar");
        try (JobPackManager first = new JobPackManager(packs.toString(), new JobTypeRegistry(),
                     ExpressionRegistry.withBuiltins(), (t, s, p) -> {}, null, root)) {
            first.rescan();
            assertFalse(Files.exists(orphan), "the next use swept the crashed dir");
            List<Path> mine;
            try (var s = Files.list(root)) { mine = s.filter(Files::isDirectory).toList(); }
            assertEquals(1, mine.size(), "only the live manager's own dir remains");
            try (JobPackManager second = new JobPackManager(packs.toString(), new JobTypeRegistry(),
                         ExpressionRegistry.withBuiltins(), (t, s, p) -> {}, null, root)) {
                second.rescan();
                assertTrue(Files.exists(mine.get(0)), "the live dir is kept");
            }
        }
    }
}
