package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.event.ParquetEventStore;
import com.gamma.job.JobConfig;
import com.gamma.job.MaintenanceTaskContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-AUDIT-CHAIN-RESIDUALS-1 (1): the scheduled off-box export of the signed audit anchors
 * ({@link AuditAnchorExport}, run as the {@code audit_anchor_export} maintenance task). Real anchors over a real
 * Parquet event store; the destination is verified with the SAME anchor verification the on-box file gets.
 */
class AuditAnchorExportTest {

    private static final long T0 = Instant.parse("2026-09-20T10:00:00Z").toEpochMilli();
    private static final long DAY = 86_400_000L;

    @AfterEach
    void clear() {
        System.clearProperty("assist.write.root");
    }

    private record Space(Path events, Path config) {}

    private static Space space(Path dir) throws Exception {
        return new Space(dir.resolve("events"), Files.createDirectories(dir.resolve("config")));
    }

    private static void emit(Space s, long... ts) {
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            for (int i = 0; i < ts.length; i++)
                log.emit(Event.builder(EventType.AUDIT).ts(ts[i]).source("audit").message("act " + i)
                        .actor("alice").action("pipeline.updated").target("pipeline", "p" + i));
        }
    }

    private static void roll(Space s, LocalDate today) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.roll(store, s.config(), today);
        }
    }

    /** Two anchors (two finished days). */
    private static Space twoAnchors(Path dir) throws Exception {
        Space s = space(dir);
        emit(s, T0, T0 + 1000, T0 + DAY);
        roll(s, LocalDate.of(2026, 9, 21));   // within the young-install grace: anchors the first finished day
        roll(s, LocalDate.of(2026, 9, 22));
        assertEquals(2,AuditAnchors.read(s.config()).size());
        return s;
    }

    private static List<String> lines(Path f) throws IOException {
        return Files.readAllLines(f, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
    }

    @Test
    void exportsTheSignedLinesVerbatimAndIsIdempotent(@TempDir Path dir) throws Exception {
        Space s = twoAnchors(dir);
        Path dest = dir.resolve("out").resolve(AuditAnchorExport.DEST_FILE);
        assertEquals(2, AuditAnchorExport.export(s.config(), dest));
        assertEquals(lines(AuditAnchors.file(s.config())), lines(dest), "byte-for-byte, not re-signed or reformatted");
        AuditAnchors.AnchorFile exported = AuditAnchors.readFileAt(s.config(), dest, null);
        assertNull(exported.firstProblem(), "the exported copy verifies with the Space's key");
        byte[] before = Files.readAllBytes(dest);
        assertEquals(0, AuditAnchorExport.export(s.config(), dest), "re-run: nothing new");
        assertArrayEquals(before, Files.readAllBytes(dest), "re-run neither duplicates nor truncates");

        emit(s, T0 + 2 * DAY);   // a third finished day
        roll(s, LocalDate.of(2026, 9, 23));
        assertEquals(1, AuditAnchorExport.export(s.config(), dest), "only the new anchor is appended");
        assertEquals(lines(AuditAnchors.file(s.config())), lines(dest));
    }

    @Test
    void aDestinationTruncatedAtTheHeadIsRefusedAndTheVerificationAlsoCatchesIt(@TempDir Path dir) throws Exception {
        Space s = twoAnchors(dir);
        Path dest = dir.resolve("out").resolve(AuditAnchorExport.DEST_FILE);
        AuditAnchorExport.export(s.config(), dest);
        Files.write(dest, (lines(dest).get(1) + "\n").getBytes(StandardCharsets.UTF_8));   // first anchor removed
        // a naive append-what-is-missing exporter would SUCCEED here; ours must not
        assertThrows(IOException.class, () -> AuditAnchorExport.export(s.config(), dest));
        assertEquals(1, lines(dest).size(), "the tampered destination is left as found, never repaired");
        assertNotNull(AuditAnchors.readFileAt(s.config(), dest, null).firstProblem(),
                "the existing anchor verification flags the truncated copy");
    }

    @Test
    void anEditedForeignOrDirectoryDestinationIsRefused(@TempDir Path dir) throws Exception {
        Space s = twoAnchors(dir);
        Path dest = dir.resolve("out").resolve(AuditAnchorExport.DEST_FILE);
        Files.createDirectories(dest.getParent());
        Files.writeString(dest, "{\"not\":\"an anchor\"}\n");
        assertThrows(IOException.class, () -> AuditAnchorExport.export(s.config(), dest));
        assertEquals("{\"not\":\"an anchor\"}\n", Files.readString(dest), "foreign content is never overwritten");
        Files.delete(dest);
        Files.createDirectory(dest);   // unwritable: a directory where the file must go
        assertThrows(IOException.class, () -> AuditAnchorExport.export(s.config(), dest));
    }

    @Test
    void aBrokenSourceOrNoAnchorsIsNeverExported(@TempDir Path dir) throws Exception {
        Space none = space(dir.resolve("a"));
        Path dest = dir.resolve("out").resolve(AuditAnchorExport.DEST_FILE);
        assertThrows(IOException.class, () -> AuditAnchorExport.export(none.config(), dest));
        Space s = twoAnchors(dir.resolve("b"));
        List<String> src = lines(AuditAnchors.file(s.config()));
        Files.write(AuditAnchors.file(s.config()),
                (src.get(0).replace("daily", "dailx") + "\nGARBAGE\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> AuditAnchorExport.export(s.config(), dest));
        assertFalse(Files.exists(dest));
    }

    private static MaintenanceTaskContext ctx(String outDir, boolean dryRun) {
        JobConfig cfg = JobConfig.fromMap(Map.of("job", Map.of("name", "anchors", "type", "maintenance",
                "task", AuditAnchorExportProvider.TASK, "out_dir", outDir)));
        return new MaintenanceTaskContext(cfg, null, dryRun, null, null, null, null);
    }

    @Test
    void theTaskResolvesOutDirUnderTheConfigRootAndRefusesEveryEscape(@TempDir Path dir) throws Exception {
        Space s = twoAnchors(dir);
        System.setProperty("assist.write.root", s.config().toString());
        AuditAnchorExportProvider p = new AuditAnchorExportProvider();

        assertEquals("SUCCESS", p.run(AuditAnchorExportProvider.TASK, ctx("exports", false)).status());
        assertEquals(2, lines(s.config().resolve("exports").resolve(AuditAnchorExport.DEST_FILE)).size());

        assertTrue(p.run(AuditAnchorExportProvider.TASK, ctx("dry", true)).message().contains("dry-run"));
        assertFalse(Files.exists(s.config().resolve("dry")), "a dry run writes nothing");

        // the surefire safety roots are the build dir and java.io.tmpdir, so the escape is under neither
        Path outside = Path.of(System.getProperty("user.home")).toAbsolutePath().resolve("zz-anchor-escape-probe");
        String relative = s.config().toAbsolutePath().relativize(outside).toString();
        for (String bad : new String[] {relative, outside.toString(), "config.secrets/x"}) {
            assertThrows(RuntimeException.class, () -> p.run(AuditAnchorExportProvider.TASK, ctx(bad, false)), bad);
        }
        assertFalse(Files.exists(outside), "the escape wrote nothing");
    }
}
