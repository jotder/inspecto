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
        Map<String, Object> index = keyMap(ScreeningHits.index(root));
        assertEquals(Map.of(a, ScreeningHits.dedupeKey("sanctions", "vladimir putin", "c1"),
                b, ScreeningHits.dedupeKey("sanctions", "kim jong un", "c2")), index);
        ScreeningHits.saveIndex(root, ScreeningHits.index(root));
        assertTrue(Files.isRegularFile(root.resolve(ScreeningHits.DIR).resolve(ScreeningHits.INDEX)));
        assertEquals(List.of(a, b).stream().sorted().toList(),
                ScreeningHits.list(root).stream().map(r -> String.valueOf(r.get("id"))).sorted().toList(),
                "the index file is not listed as a hit");

        String c = ScreeningLists.seedHit(root, "c3", "Ali Hassan", "pep", "ali hassan", 0.9);
        Files.delete(ScreeningLists.hitFile(root, a));
        assertEquals(Map.of(b, ScreeningHits.dedupeKey("sanctions", "kim jong un", "c2"),
                c, ScreeningHits.dedupeKey("pep", "ali hassan", "c3")), keyMap(ScreeningHits.index(root)),
                "a hit written after the index is read; a hit whose document is gone is dropped");
    }

    @Test
    void aHitTheIndexCoversIsNotReadAgain(@TempDir Path root) throws Exception {
        String id = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        ScreeningHits.saveIndex(root, ScreeningHits.index(root));
        Path f = ScreeningLists.hitFile(root, id);
        var at = Files.getLastModifiedTime(f);
        Files.writeString(f, "not json");
        Files.setLastModifiedTime(f, at);   // the index's file time still matches: the entry covers the document
        assertThrows(java.io.IOException.class, () -> ScreeningHits.list(root), "listing reads every document");
        assertEquals(Map.of(id, ScreeningHits.dedupeKey("sanctions", "vladimir putin", "c1")),
                keyMap(ScreeningHits.index(root)), "the Run reads the index, not the covered document");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(at.toMillis() + 2_000));
        assertThrows(java.io.IOException.class, () -> ScreeningHits.index(root),
                "a document whose file time moved is read again (fail closed when it no longer parses)");
    }

    /** SCREENING-1 D8: a decision whose index write was lost is still filed under its new state. */
    @Test
    void aDocumentRewrittenAfterTheIndexIsReadAgain(@TempDir Path root) throws Exception {
        String id = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        ScreeningHits.saveIndex(root, ScreeningHits.index(root));
        Map<String, Object> rec = ScreeningHits.read(root, id);
        ScreeningHits.transition(rec, "escalated", "analyst-1", "needs L2");
        ScreeningHits.save(root, rec);   // no reindex, as when the index write fails
        Path f = ScreeningLists.hitFile(root, id);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 2_000));
        assertEquals("escalated", ((Map<?, ?>) ScreeningHits.index(root).get(id)).get("state"));
        assertEquals(List.of(id), ids(ScreeningHits.page(root, "escalated", null, 10)));
        assertEquals(List.of(), ids(ScreeningHits.page(root, "open", null, 10)));
    }

    /** SCREENING-1 D8: a decision's reindex saves the index, so no listing has to read that document again. */
    @Test
    void aDecisionsReindexIsSaved(@TempDir Path root) throws Exception {
        String id = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        ScreeningHits.saveIndex(root, ScreeningHits.index(root));
        Map<String, Object> rec = ScreeningHits.read(root, id);
        ScreeningHits.transition(rec, "escalated", "analyst-1", "needs L2");
        ScreeningHits.save(root, rec);
        ScreeningHits.reindex(root, rec);
        Path f = ScreeningLists.hitFile(root, id);
        var at = Files.getLastModifiedTime(f);
        Files.writeString(f, "not json");
        Files.setLastModifiedTime(f, at);   // the document is unreadable, so the state below comes from the saved index
        assertEquals("escalated", ((Map<?, ?>) ScreeningHits.index(root).get(id)).get("state"), "reindex saved it");
    }

    /** SCREENING-1 D8: the filter trusts the document, not the index — a stale state never lists a hit under it. */
    @Test
    void aHitWhoseDocumentLeftTheStateIsLeftOut(@TempDir Path root) throws Exception {
        String id = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        Map<String, Object> stale = new java.util.LinkedHashMap<>(ScreeningHits.entry(root, ScreeningHits.read(root, id)));
        stale.put("state", "escalated");   // its file time still matches, so the index is not re-read
        ScreeningHits.saveIndex(root, new java.util.LinkedHashMap<>(Map.of(id, stale)));
        assertEquals(List.of(), ids(ScreeningHits.page(root, "escalated", null, 10)), "the open document is left out");
        assertEquals(List.of(id), ids(ScreeningHits.page(root, null, null, 10)));
    }

    /** SCREENING-1 D8: pages newest first, without a repeat or a gap, and reads only the page's documents. */
    @Test
    void pagesWalkTheHitsNewestFirstAndReadOnlyTheirDocuments(@TempDir Path root) throws Exception {
        java.util.List<String> all = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Map<String, Object> rec = ScreeningHits.raise(new Screener.Subject("c" + i, "n", null),
                    new Screener.Match("sanctions", "block", "e" + i, "name", 0.9), 0.85, Map.of(), "t");
            rec.put("id", String.format("sh-20260101000000-%06x", i));
            if (i % 2 == 1) ScreeningHits.transition(rec, "escalated", "a", "r");
            ScreeningHits.save(root, rec);
            all.add(String.valueOf(rec.get("id")));
        }
        ScreeningHits.saveIndex(root, ScreeningHits.index(root));
        java.util.Collections.reverse(all);
        java.util.List<String> walked = new java.util.ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            ScreeningHits.Page p = ScreeningHits.page(root, null, after, 3);
            assertEquals(7, p.total());
            walked.addAll(ids(p));
            after = p.next();
            pages++;
        } while (after != null && pages < 10); // bounded: a cursor that never advances must fail, not hang
        assertEquals(all, walked, "newest first, each hit once");
        assertEquals(3, pages);
        assertEquals(List.of("sh-20260101000000-000005", "sh-20260101000000-000003"),
                ids(ScreeningHits.page(root, "escalated", null, 2)));
        ScreeningHits.Page rest = ScreeningHits.page(root, "escalated", "sh-20260101000000-000003", 2);
        assertEquals(List.of("sh-20260101000000-000001"), ids(rest));
        assertNull(rest.next());
        assertEquals(3, rest.total());
        assertEquals(List.of("sh-20260101000000-000002"), ids(ScreeningHits.page(root, "open", "sh-20260101000000-000003", 1)),
                "a cursor between matching ids resumes after it");

        Path oldest = ScreeningLists.hitFile(root, "sh-20260101000000-000000");
        var at = Files.getLastModifiedTime(oldest);
        Files.writeString(oldest, "not json");
        Files.setLastModifiedTime(oldest, at);
        assertEquals(3, ids(ScreeningHits.page(root, null, null, 3)).size(), "a document off the page is never read");
        assertThrows(java.io.IOException.class, () -> ScreeningHits.page(root, null, "sh-20260101000000-000001", 3),
                "the page that holds it reads it (fail closed)");

        Path forged = ScreeningLists.hitFile(root, "sh-20260101000000-000006");
        at = Files.getLastModifiedTime(forged);
        Files.writeString(forged, Files.readString(forged).replace("\"score\" : 0.9", "\"score\" : 0.1"));
        Files.setLastModifiedTime(forged, at);
        assertTrue(ScreeningHits.invalid(ScreeningHits.page(root, null, null, 1).hits().get(0)),
                "a returned hit is verified, whatever the index says");
    }

    /** SCREENING-1 D8: a listing that had to rebuild the index saves it, so the next listing does not rebuild again. */
    @Test
    void aListingSavesAnIndexItHadToRebuild(@TempDir Path root) throws Exception {
        String id = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        Path f = root.resolve(ScreeningHits.DIR).resolve(ScreeningHits.INDEX);
        assertFalse(Files.exists(f));
        assertEquals(List.of(id), ids(ScreeningHits.page(root, null, null, 10)));
        assertTrue(Files.isRegularFile(f), "the rebuilt index is saved");
        var saved = Files.getLastModifiedTime(f);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(saved.toMillis() - 60_000));
        var before = Files.getLastModifiedTime(f);
        ScreeningHits.page(root, null, null, 10);
        assertEquals(before, Files.getLastModifiedTime(f), "an index that covers every hit is not rewritten");
    }

    private static List<String> ids(ScreeningHits.Page p) {
        return p.hits().stream().map(r -> String.valueOf(r.get("id"))).toList();
    }

    /** An index as id → dedupe key. */
    private static Map<String, Object> keyMap(Map<String, Object> index) {
        Map<String, Object> out = new java.util.HashMap<>();
        index.forEach((id, e) -> out.put(id, ((Map<?, ?>) e).get("key")));
        return out;
    }

    @Test
    void anEditedIndexIsRebuiltFromTheDocuments(@TempDir Path root) throws Exception {
        String id = ScreeningLists.seedHit(root, "c1", "Vladimir Putin", "sanctions", "vladimir putin", 0.97);
        String real = ScreeningHits.dedupeKey("sanctions", "vladimir putin", "c1");
        ScreeningHits.saveIndex(root, ScreeningHits.index(root));
        Path f = root.resolve(ScreeningHits.DIR).resolve(ScreeningHits.INDEX);
        // Re-keying an entry to another subject's key would suppress that subject's hit forever.
        String suppress = ScreeningHits.dedupeKey("sanctions", "kim jong un", "c2");
        Files.writeString(f, Files.readString(f).replace(real, suppress));
        assertEquals(Map.of(id, real), keyMap(ScreeningHits.index(root)), "an index that fails its MAC is rebuilt");
        Files.writeString(f, "{broken");
        assertEquals(Map.of(id, real), keyMap(ScreeningHits.index(root)), "an unreadable index is rebuilt");
        Files.writeString(f, "null");
        assertEquals(Map.of(id, real), keyMap(ScreeningHits.index(root)), "an index of null is rebuilt");
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
