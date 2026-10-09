package com.gamma.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A same-size rewrite of {@code modules.toon} inside one mtime tick must take effect: keyed on (mtime ms, size) the
 * cache kept the OLD disabled set, so a module switched off stayed on (docs/okf/backend/module-reorganisation-gotchas.md).
 */
class ModuleSettingsSameTickTest {

    @Test
    void aSameSizeSameTickRewriteChangesTheDisabledSet(@TempDir Path root) throws Exception {
        Path f = root.resolve(ModuleSettings.FILE);
        Files.writeString(f, "disabled[1]: abc\n");
        assertEquals(Set.of("abc"), ModuleSettings.disabled(root));
        FileTime tick = Files.getLastModifiedTime(f);
        Files.writeString(f, "disabled[1]: xyz\n");
        Files.setLastModifiedTime(f, tick);
        assertEquals(Set.of("xyz"), ModuleSettings.disabled(root));
    }
}
