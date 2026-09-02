package com.gamma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link TarUtil#extractTar}'s containment — the archive-extraction family PATH-2 left unpinned. */
class TarUtilTest {

    /** An entry that climbs out of destDir is refused BEFORE any byte lands, and nothing outside is written. */
    @Test
    void anEscapingEntryIsRefused(@TempDir Path root) throws Exception {
        Path dest = root.resolve("out");
        Files.createDirectories(dest);
        Path tar = root.resolve("evil.tar");
        TarFixtures.writeTar(tar, Map.of("../evil.txt", "owned"));

        IOException ex = assertThrows(IOException.class, () -> TarUtil.extractTar(tar, dest));
        assertTrue(ex.getMessage().contains("Unsafe path"), ex.getMessage());
        assertFalse(Files.exists(root.resolve("evil.txt")), "the slip target must not exist");
    }

    /** Both sides are compared in ONE frame: a destDir carrying a redundant "./" component must still
     *  admit a perfectly safe entry (the un-normalised prefix used to refuse good archives). */
    @Test
    void aRedundantDestDirComponentStillAdmitsASafeEntry(@TempDir Path root) throws Exception {
        Path dest = root.resolve("work").resolve(".").resolve("out");
        Files.createDirectories(dest);
        Path tar = root.resolve("ok.tar");
        TarFixtures.writeTar(tar, Map.of("inner/data.csv", "a,b\n"));

        assertEquals(1, TarUtil.extractTar(tar, dest));
        assertTrue(Files.exists(root.resolve("work").resolve("out").resolve("inner").resolve("data.csv")));
    }
}
