package com.gamma.control;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Pending Change MAC key (`ASSURE-MAKER-CHECKER-1` round-3 findings 2 and 4): it lives OUTSIDE the config
 * tree, the first writer wins a creation race, and it is owner-only where the platform allows.
 */
class PendingChangesKeyTest {

    @Test
    void theKeyLivesBesideTheConfigRootNeverInsideIt(@TempDir Path tmp) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        byte[] key = PendingChanges.key(config);
        Path file = PendingChanges.keyFile(config);
        assertEquals(tmp.resolve("config.secrets").resolve(".pending-changes.key").toAbsolutePath().normalize(), file);
        assertFalse(file.startsWith(config.toAbsolutePath().normalize()), "outside the config tree");
        assertEquals(32, key.length);
        assertFalse(Files.exists(config.resolve("pending-changes").resolve(".pending-changes.key")));
        assertArrayEquals(key, PendingChanges.key(config), "stable once created");
    }

    /** No import entry reaches the key: the sibling is outside the config-root jail, and the leading dot fails
     *  the segment rules wherever it is named (upstream {@code ImportPaths}, real-path check included). */
    @Test
    void noImportEntryCanReachTheKey(@TempDir Path tmp) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        PendingChanges.key(config);   // the key and its directory EXIST, so the real-path check sees them
        for (String entry : new String[]{"../config.secrets/.pending-changes.key", "..\\config.secrets\\.pending-changes.key",
                ".pending-changes.key", "pending-changes/.pending-changes.key", "config.secrets/.pending-changes.key"})
            assertTrue(com.gamma.service.ImportPaths.refusal(config, entry) != null, entry);
    }

    /** Two threads racing the first use get ONE key: CREATE_NEW, the loser reads the winner's — never replaces it. */
    @Test
    void theFirstWriterWinsARace(@TempDir Path tmp) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                Path config = Files.createDirectories(tmp.resolve("r" + round).resolve("config"));
                CountDownLatch go = new CountDownLatch(1);
                Future<byte[]> a = pool.submit(() -> { go.await(); return PendingChanges.key(config); });
                Future<byte[]> b = pool.submit(() -> { go.await(); return PendingChanges.key(config); });
                go.countDown();
                byte[] ka = a.get(), kb = b.get();
                assertTrue(Arrays.equals(ka, kb), "round " + round + ": both racers must hold the same key");
                assertArrayEquals(ka, PendingChanges.key(config), "round " + round + ": the file holds that key");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theKeyIsOwnerOnlyWhereThePlatformAllows(@TempDir Path tmp) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        PendingChanges.key(config);
        Path file = PendingChanges.keyFile(config);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        Assumptions.assumeTrue(acl != null, "neither POSIX nor ACL permissions on this filesystem");
        var entries = acl.getAcl();
        assertEquals(1, entries.size(), "one ACE: " + entries);
        assertEquals(acl.getOwner(), entries.get(0).principal(), "the owner's");
    }

    /** A sample Space's key directory and Pending Change store are git-ignored, so neither is ever committed. */
    @Test
    void theKeyAndTheStoreAreGitIgnored() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        for (String sample : java.util.List.of("spaces/demo/config.secrets/.pending-changes.key",
                "spaces/demo/config/pending-changes/pc-20260101000000-abcdef.json")) {
            Process p;
            try {
                p = new ProcessBuilder("git", "check-ignore", "-q", sample).directory(repo.toFile())
                        .redirectErrorStream(true).start();
            } catch (java.io.IOException noGit) {
                Assumptions.abort("git is not on PATH: " + noGit.getMessage());
                return;
            }
            p.getInputStream().readAllBytes();
            assertEquals(0, p.waitFor(), sample + " must be git-ignored (.gitignore)");
        }
    }
}
