package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Pending Change store lock waits a BOUNDED time: a held lock yields 503 STORE_BUSY, never a hang. */
class PendingChangesLockTimeoutTest {

    @TempDir Path root;

    @AfterEach
    void clearProp() {
        System.clearProperty(PendingChanges.PROP_LOCK_WAIT_MS);
    }

    private Path lockFile() throws Exception {
        Path d = root.toAbsolutePath().normalize().resolve(PendingChanges.DIR);
        Files.createDirectories(d);
        return d.resolve(PendingChanges.LOCK_FILE);
    }

    @Test
    void freeLockRunsTheAction() throws Exception {
        lockFile();
        assertEquals("ok", PendingChanges.<String, Exception>underStoreLock(root, () -> "ok"));
    }

    @Test
    void heldLockFailsWith503WithinTheBound() throws Exception {
        System.setProperty(PendingChanges.PROP_LOCK_WAIT_MS, "300");
        try (FileChannel ch = FileChannel.open(lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = ch.lock()) {
            long t0 = System.nanoTime();
            ApiException e = assertThrows(ApiException.class,
                    () -> PendingChanges.<String, Exception>underStoreLock(root, () -> "never"));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertEquals(503, e.status);
            assertEquals(ErrorCodes.STORE_BUSY, e.errorCode);
            assertTrue(ms >= 250 && ms < 5000, "waited " + ms + " ms");
        }
        assertEquals("ok", PendingChanges.<String, Exception>underStoreLock(root, () -> "ok"));   // freed → works again
    }
}
