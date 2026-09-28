package com.gamma.service;

import com.gamma.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * {@link SpaceManager#createFromTemplate(SpaceId, String, String, String, java.util.function.Consumer)}: a template
 * refused by its seed gate leaves no Space — and when the cleanup of the half-made tree itself fails (a locked
 * file), the gate's refusal is still what the caller sees and the leftover never blocks the id nor boots.
 */
class SpaceManagerTemplateGateTest {

    private static void seedTemplate(Path root) throws Exception {
        Path tpl = root.resolve("_templates").resolve("starter");
        Files.createDirectories(tpl.resolve("config").resolve("registry").resolve("datasets"));
        Files.writeString(tpl.resolve("template.toon"), "name: Starter\n");
        Files.writeString(tpl.resolve("config").resolve("registry").resolve("datasets").resolve("d.toon"),
                "physicalRef: d/database\n");
    }

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().startsWith("windows");

    /**
     * Make {@code file} undeletable: DOS read-only on Windows, a non-writable parent directory elsewhere. Chosen by
     * OS, not by which attribute view exists: Linux also exposes a {@code dos} view (backed by xattrs), but its
     * read-only bit never blocks an unlink — on POSIX only the parent directory's write bit does.
     */
    private static Path lock(Path file) throws Exception {
        if (WINDOWS) {
            Files.getFileAttributeView(file, DosFileAttributeView.class).setReadOnly(true);
            return file;
        }
        Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("r-xr-xr-x"));
        return file.getParent();
    }

    private static void unlock(Path locked) throws Exception {
        if (WINDOWS) Files.getFileAttributeView(locked, DosFileAttributeView.class).setReadOnly(false);
        else Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void aRefusalWhoseCleanupFailsStaysTheRefusalAndNeverBlocksTheId(@TempDir Path root) throws Exception {
        // root ignores directory permissions, so no portable lock can make the cleanup fail there
        assumeFalse(!WINDOWS && "root".equals(System.getProperty("user.name")), "cannot block a delete as root");
        seedTemplate(root);
        Path[] locked = new Path[1];
        try (SpaceManager spaces = SpaceManager.discover(root)) {
            IllegalStateException refused = new IllegalStateException("kpi refused");
            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    spaces.createFromTemplate(SpaceId.of("acme"), null, null, "starter", staging -> {
                        try {
                            locked[0] = lock(staging.resolve("config").resolve("registry").resolve("datasets").resolve("d.toon"));
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                        throw refused;
                    }));
            assertSame(refused, thrown, "the gate's refusal is the answer, not the cleanup failure");
            assertEquals(1, thrown.getSuppressed().length, "the failed cleanup rides along as suppressed");
            assertFalse(Files.exists(root.resolve("acme")), "nothing was created at the Space's own path");

            // the leftover staging dir does not block the id: the next create succeeds
            assertEquals("acme", spaces.createFromTemplate(SpaceId.of("acme"), null, null, "starter").id().value());
        } finally {
            if (locked[0] != null) unlock(locked[0]);
            MetricRegistry.global().reset();
        }
        try (Stream<Path> left = Files.list(root.resolve("_staging"))) {
            assertEquals(1, left.count(), "only the refused attempt's staging dir is left");
        }
        try (SpaceManager again = SpaceManager.discover(root)) {
            assertEquals(1, again.size(), "a leftover staging dir never boots as a Space");
        } finally {
            MetricRegistry.global().reset();
        }
    }
}
