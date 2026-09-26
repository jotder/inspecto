package com.gamma.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ReconStateStore} — a Reconciliation's recorded run + Break lifecycle (R2-03): one JSON document per
 * Reconciliation under {@code <write-root>/recon-state/}, merged on record, fail-closed on a corrupt file and on
 * an id or directory that would leave the write root.
 */
class ReconStateStoreTest {

    private static ReconBreaks.Break open(String type, String key, String column) {
        return ReconBreaks.Break.fresh(key, null, type, column, 1.0, 2.0, 1.0);
    }

    @Test
    void aNeverRunReconciliationReadsAsEmptyNotAsAnError(@TempDir Path root) throws Exception {
        ReconStateStore.State s = new ReconStateStore(root).read("orders");
        assertNull(s.lastRunAt());
        assertEquals(0, s.runs());
        assertTrue(s.breaks().isEmpty());
        assertFalse(Files.exists(root.resolve("recon-state")), "a read writes nothing");
    }

    @Test
    void recordMergesCountsAndPersistsTheRun(@TempDir Path root) throws Exception {
        ReconStateStore store = new ReconStateStore(root);
        store.record("orders", List.of(open("value_break", "EU", "amount"), open("missing_left", "US", null)), "2026-07-01T00:00:00Z");
        ReconStateStore.State s = store.record("orders", List.of(open("value_break", "EU", "amount")), "2026-08-01T00:00:00Z");

        assertEquals("2026-08-01T00:00:00Z", s.lastRunAt());
        assertEquals(2, s.runs());
        assertEquals("2026-07-01T00:00:00Z", s.breaks().get(0).firstSeenAt());
        assertEquals("auto_closed", s.breaks().get(1).status());

        ReconStateStore.State reread = new ReconStateStore(root).read("orders");
        assertEquals(s.toMap(), reread.toMap(), "round-trips through the file");
        assertTrue(Files.exists(root.resolve("recon-state").resolve("orders.json")));
    }

    @Test
    void setStatusChangesAKnownBreakAndAppendsAnUnknownOneIdentityOnly(@TempDir Path root) throws Exception {
        ReconStateStore store = new ReconStateStore(root);
        store.record("orders", List.of(open("value_break", "EU", "amount")), "2026-07-01T00:00:00Z");

        ReconBreaks.Break resolved = store.setStatus("orders", "AB", "value_break", "EU", "amount", "resolved", "FX gap", null);
        assertEquals("resolved", resolved.status());
        assertEquals("FX gap", resolved.note());
        assertEquals("2026-07-01T00:00:00Z", resolved.firstSeenAt(), "a status change keeps the sighting");

        ReconBreaks.Break appended = store.setStatus("orders", "AB", "missing_left", "APAC", null, "resolved", null, null);
        assertNull(appended.firstSeenAt());
        ReconStateStore.State s = store.read("orders");
        assertEquals(2, s.breaks().size());
        assertEquals(1, s.runs(), "a status change is not a run");
        assertEquals("2026-07-01T00:00:00Z", s.lastRunAt());
    }

    @Test
    void anAssignmentPersistsAcrossARunAndResolveKeepsTheAssigneeWhileReopenClearsIt(@TempDir Path root) throws Exception {
        ReconStateStore store = new ReconStateStore(root);
        store.record("orders", List.of(open("value_break", "EU", "amount")), "2026-07-01T00:00:00Z");
        ReconBreaks.Break assigned = store.setStatus("orders", "AB", "value_break", "EU", "amount", "assigned", null, "dana");
        assertEquals("assigned", assigned.status());
        assertEquals(1, assigned.occurrences(), "a status change is not an occurrence");

        ReconBreaks.Break afterRun = store.record("orders", List.of(open("value_break", "EU", "amount")),
                "2026-08-01T00:00:00Z").breaks().get(0);
        assertEquals("assigned", afterRun.status());
        assertEquals("dana", afterRun.assignee());
        assertEquals(2, afterRun.occurrences());
        assertEquals("dana", new ReconStateStore(root).read("orders").breaks().get(0).assignee(), "persisted");

        assertEquals("dana", store.setStatus("orders", "AB", "value_break", "EU", "amount", "resolved", "fixed", null).assignee(),
                "resolving keeps who owned it");
        assertNull(store.setStatus("orders", "AB", "value_break", "EU", "amount", "open", null, null).assignee(),
                "re-opening clears it");
    }

    /** A state file written before ASSURE-BREAK-LIFECYCLE-1 — no counters, no assignee — loads, then counts on. */
    @Test
    void aStateFileWithoutTheCountersLoadsWithDefaults(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("recon-state"));
        Files.writeString(root.resolve("recon-state").resolve("orders.json"), """
                {"reconciliation":"orders","lastRunAt":"2026-07-01T00:00:00Z","runs":1,
                 "breaks":[{"pair":"AB","key":"EU","type":"value_break","column":"amount","status":"open",
                            "firstSeenAt":"2026-07-01T00:00:00Z"}]}""");
        ReconStateStore store = new ReconStateStore(root);
        ReconBreaks.Break legacy = store.read("orders").breaks().get(0);
        assertEquals(1, legacy.occurrences());
        assertEquals("2026-07-01T00:00:00Z", legacy.lastSeenAt());

        ReconBreaks.Break next = store.record("orders", List.of(open("value_break", "EU", "amount")),
                "2026-08-01T00:00:00Z").breaks().get(0);
        assertEquals(2, next.occurrences());
        assertEquals("2026-08-01T00:00:00Z", next.lastSeenAt());
        assertEquals("2026-07-01T00:00:00Z", next.firstSeenAt());
    }

    @Test
    void aCorruptStateIsAnErrorNeverAnEmptyState(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("recon-state"));
        Files.writeString(root.resolve("recon-state").resolve("orders.json"), "{not json");
        IOException e = assertThrows(IOException.class, () -> new ReconStateStore(root).read("orders"));
        assertTrue(e.getMessage().contains("refusing"), e.getMessage());
        assertThrows(IOException.class, () -> new ReconStateStore(root).record("orders", List.of(), "2026-07-01T00:00:00Z"));
    }

    @Test
    void anIdThatCouldTraverseIsRefused(@TempDir Path root) {
        ReconStateStore store = new ReconStateStore(root);
        for (String bad : List.of("../x", "a/b", "..", "", " "))
            assertThrows(IllegalArgumentException.class, () -> store.read(bad), bad);
    }

    /**
     * A directory link out of {@code root}: a symlink where the OS allows one, else — Windows without the
     * symlink privilege — a junction, which needs none and which {@code toRealPath} resolves the same way.
     */
    static boolean linkDir(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception unsupported) {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return false;
        }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 && Files.isDirectory(link);
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void aStateDirectoryLinkedOutOfTheWriteRootIsJailed(@TempDir Path root, @TempDir Path elsewhere) throws Exception {
        assumeTrue(linkDir(root.resolve("recon-state"), elsewhere), "directory links unavailable here");
        ReconStateStore store = new ReconStateStore(root);
        assertThrows(SecurityException.class, () -> store.read("orders"));
        assertThrows(SecurityException.class, () -> store.record("orders", List.of(), "2026-07-01T00:00:00Z"));
        try (var files = Files.list(elsewhere)) {
            assertEquals(0, files.count(), "nothing was written outside the write root");
        }
    }

    @Test
    void deleteDropsTheState(@TempDir Path root) throws Exception {
        ReconStateStore store = new ReconStateStore(root);
        store.record("orders", List.of(open("missing_left", "US", null)), "2026-07-01T00:00:00Z");
        store.delete("orders");
        assertEquals(0, store.read("orders").runs());
    }
}
