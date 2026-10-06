package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code MODULE-REORG-1 P4a} (plan §2.5): backup and restore carry the config and store families of a module
 * that is not installed OPAQUELY. The backup task walks the source directory — it keeps no roster of
 * families or kinds — so a file nothing on this class path understands travels byte-for-byte.
 *
 * <p>⚠ This pins the walk. A change that made backup enumerate known families/kinds would turn this red,
 * which is the point: an absent module's data would silently stop being backed up.
 */
class ModuleRemovalBackupTest {

    @Test
    void filesNamingAnAbsentModuleSurviveBackupAndRestoreByteForByte(@TempDir Path source, @TempDir Path backupDir,
                                                                    @TempDir Path target) throws Exception {
        Map<String, String> absent = new LinkedHashMap<>();
        absent.put("config/modules.toon", "disabled[1]: zz-absent-module\nx-keep: 1\n");
        absent.put("config/registry/zz-absent-kind/thing.toon", "name: thing\nzz_key: 1\n");
        absent.put("config/zz_absent_job.toon", "job:\n  name: zz\n  type: zz.absent-module-job\n  zz_param: keep\n");
        absent.put("data/zz-absent-family/part-0.parquet", "not really parquet — opaque bytes");
        for (var e : absent.entrySet()) {
            Path p = source.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }

        JobResult backup = new MaintenanceJob(new JobConfig("m", JobType.MAINTENANCE, null, null, true, false,
                Map.of("task", "backup", "dir", source.toString(), "backup_dir", backupDir.toString()))).run();
        assertEquals("SUCCESS", backup.status(), backup.message());
        Path zip;
        try (var s = Files.list(backupDir)) {
            zip = s.filter(p -> p.getFileName().toString().endsWith(".zip")).findFirst().orElseThrow();
        }
        JobResult restore = new MaintenanceJob(new JobConfig("m", JobType.MAINTENANCE, null, null, true, false,
                Map.of("task", "restore", "archive", zip.toString(), "target_dir", target.toString()))).run();
        assertEquals("SUCCESS", restore.status(), restore.message());

        for (String rel : absent.keySet())
            assertArrayEquals(Files.readAllBytes(source.resolve(rel)), Files.readAllBytes(target.resolve(rel)),
                    rel + " must come back byte-identical — backup keeps no roster of known families");
    }
}
