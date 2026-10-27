package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Which folders under the Spaces root {@link SpaceManager#discover} admits — real folder trees, no mocks. */
class SpaceDiscoveryRuleTest {

    private static Path space(Path root, String name, boolean manifest) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.createDirectories(dir.resolve("config"));
        Files.createDirectories(dir.resolve("data"));
        if (manifest) Files.writeString(dir.resolve("space.toon"), "name: " + name + "\n");
        return dir;
    }

    private static List<String> booted(Path root) throws Exception {
        try (SpaceManager mgr = SpaceManager.discover(root)) {
            return mgr.all().stream().map(c -> c.id().value()).sorted().toList();
        }
    }

    @Test
    void aSpaceBootsAndItsBackupCopyDoesNot(@TempDir Path root) throws Exception {
        space(root, "mfs", true);
        space(root, "mfs-backup-20261026", true);   // a byte-for-byte copy: config/ and space.toon included
        space(root, "backup_mfs", true);
        space(root, "mfs.bak", true);
        space(root, "mfs-snapshot", true);
        space(root, "mfs-scratch", true);
        assertEquals(List.of("mfs"), booted(root));
    }

    @Test
    void hiddenAndUnderscoreFoldersAreNeverSpaces(@TempDir Path root) throws Exception {
        space(root, "real", true);
        space(root, ".git-copy", true);
        space(root, "_templates", true);
        space(root, "_staging", true);
        assertEquals(List.of("real"), booted(root));
    }

    @Test
    void aFolderWithoutConfigIsNotASpace(@TempDir Path root) throws Exception {
        space(root, "real", true);
        Files.createDirectories(root.resolve("uat").resolve("data"));
        Files.writeString(Files.createDirectories(root.resolve("notes")).resolve("space.toon"), "name: x\n");
        assertEquals(List.of("real"), booted(root));
    }

    @Test
    void aSpaceWithTheOldLayoutStillBootsWithoutAManifest(@TempDir Path root) throws Exception {
        space(root, "legacy", false);   // config/ only, no space.toon (pre-manifest layout)
        space(root, "old", false);      // "old" is a legitimate Space word, not a copy marker
        assertEquals(List.of("legacy", "old"), booted(root));
    }

    @Test
    void notASpaceReasonNamesWhyForEachRule(@TempDir Path root) throws Exception {
        assertNull(SpaceManager.notASpaceReason(space(root, "ok", true)));
        assertTrue(SpaceManager.notASpaceReason(space(root, "x-backup", true)).contains("backup"));
        assertTrue(SpaceManager.notASpaceReason(space(root, ".hid", true)).contains("hidden"));
        assertTrue(SpaceManager.notASpaceReason(space(root, "_t", true)).contains("reserved"));
        assertTrue(SpaceManager.notASpaceReason(Files.createDirectories(root.resolve("bare"))).contains("config"));
    }

    @Test
    void lastOnDiskIgnoresIgnoredFolders(@TempDir Path root) throws Exception {
        space(root, "mfs", true);
        space(root, "mfs-backup", true);
        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertTrue(mgr.isLastOnDisk(SpaceId.of("mfs")), "a backup copy must not count as a surviving Space");
            assertEquals(new TreeSet<>(List.of("mfs")),
                    new TreeSet<>(mgr.all().stream().map(c -> c.id().value()).toList()));
        }
    }
}
