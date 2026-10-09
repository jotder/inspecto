package com.gamma.config.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slice S2 of {@code policy-narrowing-design.md}: strict loading, the unreadable states (§5.1), the fold wiring. */
class SafetyPolicyFilesTest {

    @TempDir Path tmp;

    @AfterEach
    void clean() {
        DiscoveredRoots.clear();
        System.clearProperty("system.config.dir");
    }

    private Path serverDir() throws IOException { return Files.createDirectories(tmp.resolve("server")); }

    private Path spaceBase(String id) throws IOException {
        Path b = Files.createDirectories(tmp.resolve(id));
        Files.createDirectories(b.resolve("config"));
        return b;
    }

    private static void write(Path dir, String text) throws IOException {
        Path f = dir.resolve(SafetyPolicyFiles.FILE);
        // The parsed-file cache is keyed on (mtime ms, size): a same-size rewrite inside one mtime tick would
        // read as unchanged, so every rewrite is stamped strictly later than the file it replaces.
        java.nio.file.attribute.FileTime before = Files.exists(f) ? Files.getLastModifiedTime(f) : null;
        Files.writeString(f, text);
        if (before != null && Files.getLastModifiedTime(f).compareTo(before) <= 0)
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(before.toMillis() + 1000));
    }

    private SafetyPolicyTier eff(Path server, Path base, String id) {
        return SafetyPolicyFiles.effective(server, base, id, List.of(base));
    }

    @Test
    void noFilesNarrowNothing() throws IOException {
        Path base = spaceBase("s1");
        SafetyPolicyTier t = eff(serverDir(), base, "s1");
        assertTrue(t.permitsNetwork());
        assertEquals(List.of(base.toAbsolutePath().normalize()), t.allowRoots());
    }

    @Test
    void aSpaceFileNarrowsAndAServerDenyStillWins() throws IOException {
        Path server = serverDir();
        write(server, "deny:\n  hosts[1]: bad.example\n");
        Path base = spaceBase("s1");
        write(base.resolve("config"), "permit:\n  install_extensions: false\ndeny:\n  hosts[1]: worse.example\ncaps:\n  max_threads: 2\n");
        SafetyPolicyTier t = eff(server, base, "s1");
        assertFalse(t.permitsHost("bad.example"));
        assertFalse(t.permitsHost("worse.example"));
        assertTrue(t.permitsHost("fine.example"));
        assertFalse(t.permitsInstallExtensions());
        assertEquals(2, t.capThreads(64));
    }

    @Test
    void aSpaceCannotWidenWhatTheServerAllows() throws IOException {
        Path server = serverDir();
        write(server, "allow:\n  hosts[1]: a.example\n");
        Path base = spaceBase("s1");
        write(base.resolve("config"), "allow:\n  hosts[2]: a.example,b.example\n");
        SafetyPolicyTier t = eff(server, base, "s1");
        assertTrue(t.permitsHost("a.example"));
        assertFalse(t.permitsHost("b.example"));
    }

    @Test
    void theTwinOfEveryRefusalIsAValidFile() throws IOException {
        Path base = spaceBase("s1");
        write(base.resolve("config"), "allow:\n  hosts[1]: a.example\n");
        assertTrue(eff(serverDir(), base, "s1").permitsHost("a.example"));
    }

    @Test
    void modeInASpaceFileIsUnreadableButLegalInTheServerFile() throws IOException {
        Path server = serverDir();
        write(server, "mode: audit\n");
        Path base = spaceBase("s1");
        assertEquals(SafetyPolicyTier.Mode.AUDIT, eff(server, base, "s1").effectiveMode());
        write(base.resolve("config"), "mode: enforce\n");
        var e = assertThrows(SafetyPolicyUnreadableException.class, () -> eff(server, base, "s1"));
        assertTrue(e.getMessage().startsWith("ERR_SAFETY_POLICY_UNREADABLE"));
    }

    @Test
    void anUnknownKeyIsUnreadableBecauseATypoWouldSilentlyDropTheNarrowing() throws IOException {
        Path base = spaceBase("s1");
        write(base.resolve("config"), "alow:\n  hosts[1]: a.example\n");
        assertThrows(SafetyPolicyUnreadableException.class, () -> eff(serverDir(), base, "s1"));
        write(base.resolve("config"), "allow:\n  hostz[1]: a.example\n");
        assertThrows(SafetyPolicyUnreadableException.class, () -> eff(serverDir(), base, "s1"));
    }

    @Test
    void badValuesAndDamageAreUnreadable() throws IOException {
        Path base = spaceBase("s1");
        Path cfg = base.resolve("config");
        for (String bad : List.of("permit:\n  network: maybe\n", "caps:\n  max_threads: -1\n",
                "allow:\n  roots[1]: relative/dir\n", "allow:\n  hosts[1]: a*b\n", "allow:\n  hosts[2]: a.example\n")) {
            write(cfg, bad);
            assertThrows(SafetyPolicyUnreadableException.class, () -> eff(serverDir(), base, "s1"), bad);
        }
    }

    @Test
    void aDirectoryNamedLikeTheFileForcesTheIoBranch() throws IOException {
        Path base = spaceBase("s1");
        Files.createDirectories(base.resolve("config").resolve(SafetyPolicyFiles.FILE));
        assertThrows(SafetyPolicyUnreadableException.class, () -> eff(serverDir(), base, "s1"));
    }

    @Test
    void anUnreadableServerFileRefusesEverySpace() throws IOException {
        Path server = serverDir();
        write(server, "permit:\n  network: nope\n");
        assertThrows(SafetyPolicyUnreadableException.class, () -> eff(server, spaceBase("s1"), "s1"));
        assertThrows(SafetyPolicyUnreadableException.class, () -> eff(server, spaceBase("s2"), "s2"));
    }

    @Test
    void aNamedSpaceWithNoFileIsUnreadableButAnUnnamedOneIsNot() throws IOException {
        Path server = serverDir();
        write(server, "require_spaces[1]: s1\n");
        assertThrows(SafetyPolicyUnreadableException.class, () -> eff(server, spaceBase("s1"), "s1"));
        assertTrue(eff(server, spaceBase("s2"), "s2").permitsNetwork());
        write(spaceBase("s1").resolve("config"), "permit:\n  network: false\n");
        assertFalse(eff(server, spaceBase("s1"), "s1").permitsNetwork());
    }

    @Test
    void requireSpacesIsServerOnly() throws IOException {
        Path base = spaceBase("s1");
        write(base.resolve("config"), "require_spaces[1]: s1\n");
        assertThrows(SafetyPolicyUnreadableException.class, () -> eff(serverDir(), base, "s1"));
    }

    @Test
    void aFixedFileIsReReadAfterItsStampChanges() throws IOException {
        Path base = spaceBase("s1");
        Path cfg = base.resolve("config");
        write(cfg, "permit:\n  network: nope\n");
        assertThrows(SafetyPolicyUnreadableException.class, () -> eff(serverDir(), base, "s1"));
        write(cfg, "permit:\n  network: false\n");
        assertFalse(eff(serverDir(), base, "s1").permitsNetwork());
    }

    @Test
    void forSpaceAppliesTheSpaceFileToRootsAndCapsAndRefusesWhenUnreadable() throws IOException {
        Path base = spaceBase("s1");
        DiscoveredRoots.register("s1", base);
        Path other = Files.createDirectories(tmp.resolve("other"));
        write(base.resolve("config"), "allow:\n  roots[1]: " + base.resolve("sub").toString().replace("\\", "/")
                + "\n  formats[1]: csv\ncaps:\n  max_threads: 1\n  max_batch_files: 7\n");
        SafetyPolicy p = SafetyPolicy.forSpace("s1");
        assertEquals(List.of(base.resolve("sub").toAbsolutePath().normalize()), p.allowedRoots());
        assertEquals(1, p.maxThreads());
        assertEquals(7, p.maxBatchFiles());
        assertEquals(java.util.Set.of("CSV"), p.allowedFormats());
        assertFalse(p.allowedRoots().contains(other));

        write(base.resolve("config"), "caps:\n  max_threads: x\n");
        assertThrows(SafetyPolicyUnreadableException.class, () -> SafetyPolicy.forSpace("s1"));
    }

    @Test
    void aRunPinsItsPolicyAtPlanTimeAndTheNextRunSeesTheTightenedFile() throws IOException {
        Path base = spaceBase("s1");
        DiscoveredRoots.register("default", base);
        Path cfg = base.resolve("config");
        write(cfg, "caps:\n  max_threads: 4\n");
        int[] seen = new int[2];
        {
            SafetyPolicy.pinnedForRun(() -> {
                seen[0] = SafetyPolicy.defaultPolicy().maxThreads();
                try { write(cfg, "caps:\n  max_threads: 1\n"); } catch (IOException e) { throw new RuntimeException(e); }
                seen[1] = SafetyPolicy.defaultPolicy().maxThreads();   // mid-run: still the plan-time snapshot (D8)
                return null;
            });
            assertEquals(4, seen[0]);
            assertEquals(4, seen[1]);
            assertEquals(1, SafetyPolicy.defaultPolicy().maxThreads());   // the NEXT run applies the tightened file
        }
    }

    @Test
    void aSameSizeRewriteInsideOneMtimeTickIsNotServedFromTheCache() throws IOException {
        Path base = spaceBase("s1");
        DiscoveredRoots.register("default", base);
        Path f = base.resolve("config").resolve(SafetyPolicyFiles.FILE);
        java.nio.file.attribute.FileTime tick = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis());
        Files.writeString(f, "caps:\n  max_threads: 4\n");
        Files.setLastModifiedTime(f, tick);
        assertEquals(4, SafetyPolicy.defaultPolicy().maxThreads());
        Files.writeString(f, "caps:\n  max_threads: 1\n");   // same size, same mtime: the stamp cannot tell
        Files.setLastModifiedTime(f, tick);
        assertEquals(1, SafetyPolicy.defaultPolicy().maxThreads(), "a tightened policy must never be missed");
    }

    @Test
    void anUnreadableFileRefusesTheRunBeforeItsBodyStartsAndIsListedForHealth() throws IOException {
        Path base = spaceBase("s1");
        DiscoveredRoots.register("default", base);
        write(base.resolve("config"), "mode: audit\n");
        boolean[] started = {false};
        {
            assertThrows(SafetyPolicyUnreadableException.class, () -> SafetyPolicy.pinnedForRun(() -> {
                started[0] = true;
                return null;
            }));
        }
        assertFalse(started[0], "the run body never starts under an unreadable policy");
        assertEquals(1, SafetyPolicy.unreadable().size());
        write(base.resolve("config"), "caps:\n  max_threads: 2\n");
        assertTrue(SafetyPolicy.unreadable().isEmpty());
    }
}
