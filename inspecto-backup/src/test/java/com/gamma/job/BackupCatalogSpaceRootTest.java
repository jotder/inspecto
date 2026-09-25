package com.gamma.job;

import com.gamma.event.EventLog;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BACKUP-CATALOG-SPACE-ROOT-1} — <b>a backup in a named Space registers its {@code maintenance_backups}
 * catalog Dataset in that Space's own registry.</b>
 *
 * <p>Until 2026-09-25 {@code BackupTask.catalogRow} took the registry from the JVM-wide
 * {@code -Dassist.write.root} while the catalog Parquet went to the Space's own data root. In multi-Space mode
 * the property is normally unset (the row was skipped) or names one Space's config (every Space's row was
 * registered there, beside Parquet it cannot see). Same bug class as {@code MEASURE-PROBE-SPACE-ROOT-1}.
 *
 * <p>The JVM-wide property is deliberately SET here, to a third directory, so the old wiring puts the
 * registration in the wrong place rather than skipping it — that is the mutation check.
 */
class BackupCatalogSpaceRootTest {

    private String priorRoots;
    private String priorWriteRoot;

    @BeforeEach
    void isolate() {
        priorRoots = System.getProperty("assist.safety.roots");
        priorWriteRoot = System.getProperty("assist.write.root");
        SpaceConfigRoot.clear();
        MDC.remove(EventLog.SPACE_MDC_KEY);
    }

    @AfterEach
    void restore() {
        restoreProperty("assist.safety.roots", priorRoots);
        restoreProperty("assist.write.root", priorWriteRoot);
        SpaceConfigRoot.clear();
        MDC.remove(EventLog.SPACE_MDC_KEY);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @Test
    void aBackupInSpaceBRegistersItsCatalogInSpaceBsRegistryOnly(@TempDir Path root) throws Exception {
        System.setProperty("assist.safety.roots", root.toAbsolutePath().normalize().toString());
        Path configA = Files.createDirectories(root.resolve("spaces/a/config"));
        Path configB = Files.createDirectories(root.resolve("spaces/b/config"));
        Path dataB = Files.createDirectories(root.resolve("spaces/b/data"));
        Path jvmWide = Files.createDirectories(root.resolve("jvm-wide"));
        System.setProperty("assist.write.root", jvmWide.toString());
        SpaceConfigRoot.register("a", configA);
        SpaceConfigRoot.register("b", configB);
        MDC.put(EventLog.SPACE_MDC_KEY, "b");

        Path source = root.resolve("spaces/b/src");
        Files.writeString(Files.createDirectories(source).resolve("space.toon"), "root");
        JobConfig cfg = new JobConfig("m", JobType.MAINTENANCE, null, null, true, false, Map.of(
                "task", "backup", "dir", source.toString(),
                "backup_dir", root.resolve("spaces/b/backups").toString()));

        JobResult r = new MaintenanceJob(cfg, dataB.toString()).run();
        assertEquals("SUCCESS", r.status(), r.message());
        try (var s = Files.list(dataB.resolve("maintenance_backups"))) {
            assertEquals(1, s.filter(p -> p.getFileName().toString().endsWith(".parquet")).count(),
                    "the catalog row Parquet lands in Space b's data root");
        }

        assertTrue(new ComponentStore(configB.resolve("registry")).exists("dataset", "maintenance_backups"),
                "the catalog Dataset must be registered in Space b's own registry, beside its Parquet");
        assertFalse(new ComponentStore(jvmWide.resolve("registry")).exists("dataset", "maintenance_backups"),
                "the JVM-wide -Dassist.write.root registry must not receive Space b's catalog Dataset");
        assertFalse(new ComponentStore(configA.resolve("registry")).exists("dataset", "maintenance_backups"),
                "Space a's registry must not receive Space b's catalog Dataset");
    }
}
