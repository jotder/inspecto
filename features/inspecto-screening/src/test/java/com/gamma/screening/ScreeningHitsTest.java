package com.gamma.screening;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Screening Hit store and state machine (SCR-D10 … SCR-D12). */
class ScreeningHitsTest {

    @Test
    void aSavedHitReadsBackSignedAndValid(@TempDir Path root) throws Exception {
        String id = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        Map<String, Object> rec = ScreeningHits.read(root, id);
        assertFalse(ScreeningHits.invalid(rec), rec.toString());
        assertEquals("open", rec.get("state"));
        assertEquals(1, ((Number) rec.get("version")).intValue());
        assertEquals(ScreeningHits.dedupeKey("sanctions", "vladimir putin", "c1"), rec.get("dedupeKey"));
        assertFalse(ScreeningHits.render(rec).containsKey("mac"), "the MAC never goes on the wire");
        assertEquals(List.of(id), ScreeningHits.list(root).stream().map(r -> r.get("id")).toList());
        assertNull(ScreeningHits.read(root, "sh-20260101000000-000000"), "an unknown id is absent, not an error");
    }

    @Test
    void anEditedOrForgedDocumentReadsBackInvalidAndIsNeverReSigned(@TempDir Path root) throws Exception {
        String id = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        Path f = ScreeningLists.hitFile(root, id);
        Files.writeString(f, Files.readString(f).replace("\"open\"", "\"dismissed\""));
        Map<String, Object> rec = ScreeningHits.read(root, id);
        assertTrue(ScreeningHits.invalid(rec), "an edited state must not verify: " + rec);
        assertThrows(IllegalStateException.class, () -> ScreeningHits.save(root, rec));
    }

    @Test
    void theStateMachineAllowsOnlyTheReviewTransitions() {
        for (String to : List.of("confirmed", "dismissed", "escalated")) assertTrue(ScreeningHits.allowed("open", to), to);
        assertTrue(ScreeningHits.allowed("escalated", "confirmed"));
        assertTrue(ScreeningHits.allowed("escalated", "dismissed"));
        assertFalse(ScreeningHits.allowed("escalated", "escalated"));
        assertFalse(ScreeningHits.allowed("open", "open"));
        for (String fin : List.of("confirmed", "dismissed"))
            for (String to : List.of("open", "confirmed", "dismissed", "escalated"))
                assertFalse(ScreeningHits.allowed(fin, to), fin + " is final");
    }

    @Test
    void aTransitionBumpsTheVersionAndAppendsHistory(@TempDir Path root) throws Exception {
        String id = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        Map<String, Object> rec = ScreeningHits.read(root, id);
        ScreeningHits.transition(rec, "escalated", "analyst-1", "needs L2");
        ScreeningHits.save(root, rec);
        Map<String, Object> back = ScreeningHits.read(root, id);
        assertFalse(ScreeningHits.invalid(back));
        assertEquals("escalated", back.get("state"));
        assertEquals(2, ((Number) back.get("version")).intValue());
        assertEquals("analyst-1", back.get("decidedBy"));
        List<?> history = (List<?>) back.get("history");
        assertEquals(2, history.size());
        assertEquals("needs L2", ((Map<?, ?>) history.get(1)).get("reason"));
    }

    @Test
    void theDedupeIndexCoversEveryHitAndFollowsTheDirectory(@TempDir Path root) throws Exception {
        String a = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        String b = ScreeningLists.seedHit(root, "c2", "Kim Jong Un", "sanctions", "kim jong un", 0.95);
        Map<String, Object> index = ScreeningHits.dedupeIndex(root);
        assertEquals(Map.of(a, ScreeningHits.dedupeKey("sanctions", "vladimir putin", "c1"),
                b, ScreeningHits.dedupeKey("sanctions", "kim jong un", "c2")), index);
        ScreeningHits.saveIndex(root, index);
        assertTrue(Files.isRegularFile(root.resolve(ScreeningHits.DIR).resolve(ScreeningHits.INDEX)));
        assertEquals(List.of(a, b).stream().sorted().toList(),
                ScreeningHits.list(root).stream().map(r -> String.valueOf(r.get("id"))).sorted().toList(),
                "the index file is not listed as a hit");

        String c = ScreeningLists.seedHit(root, "c3", "Ali Hassan", "pep", "ali hassan", 0.9);
        Files.delete(ScreeningLists.hitFile(root, a));
        assertEquals(Map.of(b, ScreeningHits.dedupeKey("sanctions", "kim jong un", "c2"),
                c, ScreeningHits.dedupeKey("pep", "ali hassan", "c3")), ScreeningHits.dedupeIndex(root),
                "a hit written after the index is read; a hit whose document is gone is dropped");
    }

    @Test
    void aHitTheIndexCoversIsNotReadAgain(@TempDir Path root) throws Exception {
        String id = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        ScreeningHits.saveIndex(root, ScreeningHits.dedupeIndex(root));
        Files.writeString(ScreeningLists.hitFile(root, id), "not json");
        assertThrows(java.io.IOException.class, () -> ScreeningHits.list(root), "listing reads every document");
        assertEquals(Map.of(id, ScreeningHits.dedupeKey("sanctions", "vladimir putin", "c1")),
                ScreeningHits.dedupeIndex(root), "the Run reads the index, not the covered document");
    }

    @Test
    void anEditedIndexIsRebuiltFromTheDocuments(@TempDir Path root) throws Exception {
        String id = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        String real = ScreeningHits.dedupeKey("sanctions", "vladimir putin", "c1");
        ScreeningHits.saveIndex(root, ScreeningHits.dedupeIndex(root));
        Path f = root.resolve(ScreeningHits.DIR).resolve(ScreeningHits.INDEX);
        // Re-keying an entry to another subject's key would suppress that subject's hit forever.
        String suppress = ScreeningHits.dedupeKey("sanctions", "kim jong un", "c2");
        Files.writeString(f, Files.readString(f).replace(real, suppress));
        assertEquals(Map.of(id, real), ScreeningHits.dedupeIndex(root), "an index that fails its MAC is rebuilt");
        Files.writeString(f, "{broken");
        assertEquals(Map.of(id, real), ScreeningHits.dedupeIndex(root), "an unreadable index is rebuilt");
        Files.writeString(f, "null");
        assertEquals(Map.of(id, real), ScreeningHits.dedupeIndex(root), "an index of null is rebuilt");
    }

    @Test
    void aFreshIdNeverReusesATakenOne() {
        java.time.Instant at = java.time.Instant.parse("2026-11-01T10:00:00Z");
        String taken = "sh-20261101100000-00002a";
        int[] draws = {0x2a, 0x2a, 0x2b};
        int[] n = {0};
        assertEquals("sh-20261101100000-00002b",
                ScreeningHits.freshId(Map.of(taken, "k"), taken, at, () -> draws[n[0]++]), "two taken draws are skipped");
        assertEquals("sh-20261101100000-000001", ScreeningHits.freshId(Map.of(taken, "k"), "sh-20261101100000-000001",
                at, () -> { throw new AssertionError("a free id is kept"); }));
    }

    @Test
    void theDedupeKeyDependsOnListEntryAndSubject() {
        String k = ScreeningHits.dedupeKey("l", "e", "s");
        assertEquals(k, ScreeningHits.dedupeKey("l", "e", "s"));
        assertNotEquals(k, ScreeningHits.dedupeKey("l", "e2", "s"));
        assertNotEquals(k, ScreeningHits.dedupeKey("l2", "e", "s"));
        assertNotEquals(k, ScreeningHits.dedupeKey("l", "e", "s2"));
        assertNotEquals(ScreeningHits.dedupeKey("ab", "c", "s"), ScreeningHits.dedupeKey("a", "bc", "s"), "parts are separated");
    }
}
