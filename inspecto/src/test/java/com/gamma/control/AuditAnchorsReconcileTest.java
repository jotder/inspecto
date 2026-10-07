package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.audit.EventLog;
import com.gamma.audit.EventType;
import com.gamma.event.ParquetEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-AUDIT-CHAIN-RESIDUALS-1 (6): {@code AuditAnchors.write} appends and fsyncs an anchor BEFORE it moves
 * {@code latestMac} onto it. A crash between the two leaves the record one anchor behind a file that holds it, and
 * truncating just that last anchor then went undetected. {@link AuditAnchors#reconcile} (run at startup) closes it.
 * The crash window is simulated by restoring the "anchoring started" record to its bytes from before the last anchor.
 */
class AuditAnchorsReconcileTest {

    private static final long T0 = Instant.parse("2026-09-20T10:00:00Z").toEpochMilli();
    private static final long DAY = 86_400_000L;
    private static final LocalDate D1 = LocalDate.of(2026, 9, 20);

    private record Space(Path events, Path config) {}

    private static Space space(Path dir) throws Exception {
        return new Space(dir.resolve("events"), Files.createDirectories(dir.resolve("config")));
    }

    /** One audit row per day for {@code days} days starting on D1. */
    private static void emitDays(Space s, int days) {
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            EventLog log = EventLog.create();
            log.installStore(store);
            for (int i = 0; i < days; i++)
                log.emit(Event.builder(EventType.AUDIT).ts(T0 + i * DAY).source("audit").message("act " + i)
                        .actor("alice").action("pipeline.updated").target("pipeline", "p" + i));
        }
    }

    private static void rollThrough(Space s, LocalDate today) throws Exception {
        try (ParquetEventStore store = new ParquetEventStore(s.events(), 1000, 0, 100)) {
            AuditAnchors.roll(store, s.config(), today);
        }
    }

    /** Anchors for {@code n} days, leaving the "anchoring started" record as it was after anchor n-1 — the crash
     *  window. Returns nothing: the Space's files ARE the state. */
    private static void crashedBeforeRecordingTheLastAnchor(Space s, int n) throws Exception {
        emitDays(s, n);
        for (int i = 1; i < n; i++) rollThrough(s, D1.plusDays(i));
        byte[] before = Files.readAllBytes(AuditAnchors.startedFile(s.config()));
        rollThrough(s, D1.plusDays(n));
        Files.write(AuditAnchors.startedFile(s.config()), before);   // the crash: anchor on disk, record behind
        assertEquals(n, AuditAnchors.read(s.config()).size());
    }

    private static AuditVerifier.Bad problem(Space s) throws Exception {
        return AuditAnchors.readFile(s.config()).firstProblem();
    }

    private static void dropLastAnchor(Space s) throws Exception {
        Path f = AuditAnchors.file(s.config());
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        Files.write(f, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8);
    }

    @Test
    void withoutReconcileTheCrashWindowLetsTheLastAnchorBeTruncatedUndetected(@TempDir Path dir) throws Exception {
        // the PROBE: this is the old behaviour — it would succeed (no problem) were reconcile absent
        Space s = space(dir);
        crashedBeforeRecordingTheLastAnchor(s, 3);
        assertNull(problem(s), "a crash between append and record is not itself an alarm");
        dropLastAnchor(s);
        assertNull(problem(s), "unreconciled, dropping the last anchor is undetected — the defect (6) closes");
    }

    @Test
    void reconcileAdvancesTheRecordSoTheTruncatedLastAnchorIsNowDetected(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        crashedBeforeRecordingTheLastAnchor(s, 3);
        List<AuditAnchors.Anchor> before = AuditAnchors.read(s.config());
        assertTrue(AuditAnchors.reconcile(s.config()), "the record was behind the file");
        assertEquals(before.get(2).mac(), AuditAnchors.readFile(s.config()).started().latestMac());
        assertEquals(before.get(0).mac(), AuditAnchors.readFile(s.config()).started().firstMac(), "first is kept");
        assertNull(problem(s), "an intact file raises no false alarm after reconciling");
        dropLastAnchor(s);
        AuditVerifier.Bad bad = problem(s);
        assertNotNull(bad, "the truncated last anchor is detected");
        assertEquals("anchor-file-truncated", bad.reason());
    }

    @Test
    void reconcileIsIdempotentAndANoOpWhenTheRecordIsCurrent(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emitDays(s, 2);
        rollThrough(s, D1.plusDays(1));
        rollThrough(s, D1.plusDays(2));
        byte[] record = Files.readAllBytes(AuditAnchors.startedFile(s.config()));
        assertFalse(AuditAnchors.reconcile(s.config()), "nothing was behind");
        assertArrayEquals(record, Files.readAllBytes(AuditAnchors.startedFile(s.config())), "record untouched");
        assertNull(problem(s));
        // and a space that never anchored has nothing to reconcile
        assertFalse(AuditAnchors.reconcile(space(dir.resolve("other")).config()));
    }

    @Test
    void aTruncationWithoutACrashIsStillDetectedAndNotMistakenForOne(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        emitDays(s, 3);
        for (int i = 1; i <= 3; i++) rollThrough(s, D1.plusDays(i));
        dropLastAnchor(s);
        assertFalse(AuditAnchors.reconcile(s.config()), "a file BEHIND its record is never 'reconciled' forward");
        assertEquals("anchor-file-truncated", problem(s).reason());
    }

    @Test
    void aTamperedMidFileAnchorIsNotReconciledOverAndStaysReported(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        crashedBeforeRecordingTheLastAnchor(s, 3);
        byte[] record = Files.readAllBytes(AuditAnchors.startedFile(s.config()));
        Path f = AuditAnchors.file(s.config());
        String text = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"day\":\"2026-09-21\""), text);
        Files.writeString(f, text.replace("\"day\":\"2026-09-21\"", "\"day\":\"2026-09-19\""), StandardCharsets.UTF_8);
        assertFalse(AuditAnchors.reconcile(s.config()), "a file that does not verify is never advanced");
        assertArrayEquals(record, Files.readAllBytes(AuditAnchors.startedFile(s.config())), "nothing repaired");
        assertEquals("anchor-mismatch", problem(s).reason());
    }

    @Test
    void aRemovedMidFileAnchorIsNotReconciledOverAndStaysReported(@TempDir Path dir) throws Exception {
        Space s = space(dir);
        crashedBeforeRecordingTheLastAnchor(s, 3);   // record names anchor 2; the file holds 1,2,3
        Path f = AuditAnchors.file(s.config());
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        Files.write(f, List.of(lines.get(0), lines.get(2)), StandardCharsets.UTF_8);   // anchor 2 removed
        byte[] record = Files.readAllBytes(AuditAnchors.startedFile(s.config()));
        assertFalse(AuditAnchors.reconcile(s.config()));
        assertArrayEquals(record, Files.readAllBytes(AuditAnchors.startedFile(s.config())));
        assertNotNull(problem(s), "the break is reported, never repaired silently");
    }
}
