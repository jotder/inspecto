package com.gamma.inspector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 🔴 <b>CONFIG-MIGRATOR-LOSES-MAPPINGS-1 (b)</b> — {@code migrate-configs <config_root> --apply}, the form
 * {@code MainApp.printUsage()} itself prints, must write the registry where the command says it does.
 *
 * <p>The bug: the flag loop stripped {@code --dry-run} only, so {@code --apply} fell through into the
 * positional list. {@code subArgs.length} was then 2, {@code outRoot} became {@code Paths.get("--apply")} —
 * a directory literally named {@code --apply} in the process CWD — and the command still reported success
 * and still archived the originals.
 *
 * <p>⚠ This drives {@code MainApp.main} for real, because the defect is in the argument loop and nothing
 * else calls it. The success path of {@code migrate-configs} does not {@code System.exit}; the inputs are a
 * fresh temp dir holding one schema, so no refusal (which would exit and kill the surefire fork) is
 * reachable.
 */
class MainAppMigrateConfigsArgsTest {

    @Test
    void theDocumentedApplyFormWritesTheRegistryUnderTheConfigRootNotIntoADirectoryCalledApply(
            @TempDir Path dir) throws Exception {
        // A COMMITTED schema, so this pins the real corpus shape rather than a hand-built one.
        Path src = Path.of("..", "spaces", "demo", "config", "orders", "orders_schema.toon")
                .toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(src), "committed fixture must exist: " + src);

        Path config = dir.resolve("config");
        Files.createDirectories(config);
        Files.copy(src, config.resolve("orders_schema.toon"));

        MainApp.main(new String[]{"migrate-configs", config.toString(), "--apply"});

        assertTrue(Files.isRegularFile(config.resolve("registry/schemas/orders.toon")),
                "the default registry root is <config_root>/registry");
        assertFalse(Files.exists(Path.of("--apply")),
                "and nothing lands in a directory named after the flag");
        assertTrue(Files.isRegularFile(config.resolve("archived-config/orders_schema.toon")),
                "the original is archived, which is what makes writing to the wrong root data loss");
        assertTrue(Files.readString(config.resolve("registry/schemas/orders.toon"), StandardCharsets.UTF_8)
                        .contains("UPPER(TRIM(REGION))"),
                "and the mapping expressions travelled with it (half (a))");
    }
}
