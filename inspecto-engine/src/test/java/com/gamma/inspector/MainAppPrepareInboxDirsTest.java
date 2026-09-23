package com.gamma.inspector;

import com.gamma.util.TarInboxPreparer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DATA-PATH-RESIDUALS-1} (b): the tar → inbox preparer reads its {@code dirs.*} ONLY through the engine
 * CLI ({@code ura prepare-inbox}), whose {@code loadToon} resolves them under the config's Space directory.
 * {@code TarInboxPreparer} used to carry its own {@code main} + path-taking constructor that read a relative
 * {@code dirs.poll} against the process working directory.
 */
class MainAppPrepareInboxDirsTest {

    @Test
    void theCliResolvesThePreparersDirsUnderTheSpaceDirectory(@TempDir Path tmp) throws Exception {
        Path space = tmp.resolve("spaces/demo").toAbsolutePath().normalize();
        Path cfg = space.resolve("config/adjustment/adjustment_pipeline.toon");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, """
                name: adjustment
                dirs:
                  poll: data/inbox/adjustment
                  temp: data/adjustment/temp
                  backup: data/adjustment/backup
                """, StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        Map<String, Object> dirs = (Map<String, Object>) MainApp.loadToon(
                new String[]{cfg.toString()}, "prepare-inbox").get("dirs");

        assertEquals(space.resolve("data/inbox/adjustment").toString(), dirs.get("poll"));
        assertEquals(space.resolve("data/adjustment/temp").toString(), dirs.get("temp"));
        assertEquals(space.resolve("data/adjustment/backup").toString(), dirs.get("backup"));
    }

    @Test
    void thePreparerHasNoEntryPointThatReadsItsDirsFromTheWorkingDirectory() {
        assertTrue(Arrays.stream(TarInboxPreparer.class.getDeclaredMethods())
                        .noneMatch(m -> m.getName().equals("main") && Modifier.isStatic(m.getModifiers())),
                "TarInboxPreparer.main read dirs.* against the CWD — `ura prepare-inbox` is the entry point");
        assertTrue(Arrays.stream(TarInboxPreparer.class.getConstructors())
                        .noneMatch(c -> c.getParameterTypes()[0] == String.class),
                "a path-taking constructor loads the config without resolving its dirs");
    }
}
