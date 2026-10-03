package com.gamma.la.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D7-3 - the Draft store: id safety, one-rename creation, the rollback of a failed append, an idempotent discard. */
class DraftStoreTest {

    @AfterEach
    void restore() {
        DraftStore.mover = DraftStore.ATOMIC;
    }

    private static long entries(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }

    @Test
    void aDraftIdIsExactlyAServerGeneratedOneAndCannotEscape(@TempDir Path inv) {
        String ok = DraftStore.newId();
        assertTrue(DraftStore.DRAFT_ID.matcher(ok).matches());
        assertEquals(inv.resolve("drafts").resolve(ok).normalize(), DraftStore.draftDir(inv, ok));
        for (String bad : new String[] {"..", ".", "", "draft-", "../x", "draft-../../x", ".fork-abc", "a/b", "draft-123",
                ok + "/..", ok + "x", ok.toUpperCase(java.util.Locale.ROOT), " " + ok, ok + "\u0000"})
            assertThrows(IllegalArgumentException.class, () -> DraftStore.draftDir(inv, bad), bad);
        assertThrows(IllegalArgumentException.class, () -> DraftStore.draftDir(inv, null));
    }

    @Test
    void createStagesThenRenamesAndAFailedRenameLeavesNothing(@TempDir Path inv) throws Exception {
        String id = DraftStore.newId();
        DraftStore.mover = (from, to) -> { throw new IOException("injected rename failure"); };
        assertThrows(IOException.class, () -> DraftStore.create(inv, id, "{\"draftId\":\"" + id + "\"}"));
        assertEquals(0, entries(DraftStore.draftsDir(inv)), "no partial directory and no scratch directory remains");
        assertNull(DraftStore.readHeader(inv, id));
        assertEquals(List.of(), DraftStore.listIds(inv));

        DraftStore.mover = DraftStore.ATOMIC;
        assertTrue(DraftStore.create(inv, id, "{\"draftId\":\"" + id + "\"}"));
        assertTrue(Files.isDirectory(DraftStore.draftDir(inv, id).resolve("sets")));
        assertEquals(List.of(id), DraftStore.listIds(inv));
        assertFalse(DraftStore.create(inv, id, "{}"), "an existing id is never replaced");
        assertEquals("{\"draftId\":\"" + id + "\"}", DraftStore.readHeader(inv, id));
        assertEquals(1, entries(DraftStore.draftsDir(inv)));
    }

    @Test
    void aFailedAppendLeavesTheLogAsItWas(@TempDir Path inv) throws Exception {
        String id = DraftStore.newId();
        DraftStore.create(inv, id, "{}");
        Path dir = DraftStore.draftDir(inv, id);
        DraftStore.appendStep(dir, 3, "{\"step\":3}", "{\"set\":3}");
        String before = Files.readString(dir.resolve("log.jsonl"));
        Files.createDirectories(dir.resolve("sets").resolve("4.json"));   // the set file of step 4 cannot be created: the append fails after the log line
        assertThrows(IOException.class, () -> DraftStore.appendStep(dir, 4, "{\"step\":4}", "{\"set\":4}"));
        assertEquals(before, Files.readString(dir.resolve("log.jsonl")), "the log line was taken back");
    }

    @Test
    void theMainLogWriterIsTheSameCode(@TempDir Path root) throws Exception {
        SnapshotStore store = new SnapshotStore(root);
        store.createInvestigation("inv-1", "{}");
        store.appendStep("inv-1", 1, "{\"step\":1}", "{\"s\":1}");
        Path d = store.investigationDir("inv-1");
        assertEquals("{\"step\":1}\n", Files.readString(d.resolve("log.jsonl")));
        assertEquals("{\"s\":1}", Files.readString(d.resolve("sets").resolve("1.json")));
        assertEquals(List.of("{\"step\":1}"), SnapshotStore.readLogAt(d));
        assertEquals(store.readLog("inv-1"), SnapshotStore.readLogAt(d));
    }

    @Test
    void discardKeepsTheHeaderAndMarkerDeletesTheLogAndIsIdempotent(@TempDir Path inv) throws Exception {
        String id = DraftStore.newId();
        DraftStore.create(inv, id, "{\"draftId\":\"x\"}");
        Path dir = DraftStore.draftDir(inv, id);
        DraftStore.appendStep(dir, 1, "{\"step\":1}", "{}");
        assertFalse(DraftStore.isDiscarded(dir));
        assertTrue(DraftStore.markDiscarded(dir, "{\"discardedBy\":\"me\"}"));
        assertFalse(DraftStore.markDiscarded(dir, "{\"discardedBy\":\"someone-else\"}"), "the second discard changes nothing");
        assertTrue(DraftStore.isDiscarded(dir));
        assertEquals("{\"discardedBy\":\"me\"}", DraftStore.readDiscarded(dir));
        assertFalse(Files.exists(dir.resolve("log.jsonl")));
        assertFalse(Files.exists(dir.resolve("sets")));
        assertTrue(Files.isRegularFile(dir.resolve("header.json")));
    }

    @Test
    void thePrefixHashCoversExactlyTheFirstKLines() {
        List<String> log = List.of("{\"step\":1}", "{\"step\":2}", "{\"step\":3}");
        assertEquals(DraftStore.prefixHash(log, 2), DraftStore.prefixHash(List.of("{\"step\":1}", "{\"step\":2}"), 2));
        assertNotEquals(DraftStore.prefixHash(log, 2), DraftStore.prefixHash(log, 3));
        assertNotEquals(DraftStore.prefixHash(log, 2), DraftStore.prefixHash(List.of("{\"step\":1}", "{\"step\":9}"), 2));
        assertEquals(DraftStore.prefixHash(List.of(), 0), DraftStore.prefixHash(log, 0));
    }
}
