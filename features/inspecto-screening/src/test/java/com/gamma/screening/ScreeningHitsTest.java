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
    void theDedupeKeyDependsOnListEntryAndSubject() {
        String k = ScreeningHits.dedupeKey("l", "e", "s");
        assertEquals(k, ScreeningHits.dedupeKey("l", "e", "s"));
        assertNotEquals(k, ScreeningHits.dedupeKey("l", "e2", "s"));
        assertNotEquals(k, ScreeningHits.dedupeKey("l2", "e", "s"));
        assertNotEquals(k, ScreeningHits.dedupeKey("l", "e", "s2"));
        assertNotEquals(ScreeningHits.dedupeKey("ab", "c", "s"), ScreeningHits.dedupeKey("a", "bc", "s"), "parts are separated");
    }
}
