package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/** INGEST-RAW-SOURCE-COPIES-RETENTION-1: processing.raw_copy_retention_days ages backup/ + quarantine raw copies. */
class RawCopyRetentionTest {

    private static PipelineConfig cfg(Path dir, String extra) throws Exception {
        Path toon = Files.createDirectories(dir).resolve("p.toon");
        StringBuilder dirs = new StringBuilder();
        for (String d : new String[]{"poll", "database", "backup", "temp", "errors", "quarantine", "markers", "status_dir", "log_dir"})
            dirs.append("  ").append(d).append(": ").append(dir.resolve("data").resolve(d).toString().replace('\\', '/')).append('\n');
        Files.writeString(toon, "name: p\nactive: true\ndirs:\n" + dirs + "processing:\n  schema_file: s.toon\n" + extra);
        Files.writeString(dir.resolve("s.toon"), "raw:\n  name: R\n  format: CSV\n  fields[1]{name,selector,type}:\n    A,\"0\",VARCHAR\n");
        return PipelineConfig.load(toon.toString());
    }

    private static Path file(Path p, int ageDays) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - ageDays * 86_400_000L));
        return p;
    }

    @Test
    void oldBackupAndQuarantineCopiesGoButFreshParkedAndRestrictedStay(@TempDir Path dir) throws Exception {
        PipelineConfig c = cfg(dir, "  raw_copy_retention_days: 5\n");
        Path data = dir.resolve("data");
        Path oldBackup = file(data.resolve("backup/sub/a.csv"), 9);
        Path newBackup = file(data.resolve("backup/b.csv"), 1);
        Path parked = file(data.resolve("backup/parked/c.csv"), 30);
        Path oldQuar = file(data.resolve("quarantine/d.csv"), 9);
        Path restricted = file(data.resolve("quarantine/.restricted/e.csv"), 30);
        assertEquals(2, RawCopyRetention.sweepNow(c));
        assertFalse(Files.exists(oldBackup));
        assertFalse(Files.exists(oldQuar));
        assertTrue(Files.exists(newBackup));
        assertTrue(Files.exists(parked), "parked files await drain");
        assertTrue(Files.exists(restricted), "the restricted store has its own retention");
    }

    @Test
    void unsetMeansKeptForeverAndZeroIsRejected(@TempDir Path dir) throws Exception {
        PipelineConfig c = cfg(dir, "");
        assertNull(c.rawCopyRetentionDays());
        Path old = file(dir.resolve("data/backup/a.csv"), 400);
        assertEquals(0, RawCopyRetention.sweepNow(c));
        assertTrue(Files.exists(old));
        assertThrows(Exception.class, () -> cfg(dir.resolve("z"), "  raw_copy_retention_days: 0\n"));
    }
}
