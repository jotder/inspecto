package com.gamma.intelligence.triage;

import com.gamma.intelligence.triage.Feedback.Rating;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AGT-5 P5: durable Triage Run feedback storage + rating parsing. */
class FeedbackStoreTest {

    private static Feedback fb(String id, String triageRunId, Rating r) {
        return new Feedback(id, triageRunId, r, "note-" + id, "alice", Instant.now());
    }

    @Test
    void addRecentByTriageRunAndById() {
        FeedbackStore store = new FeedbackStore();
        store.add(fb("f1", "run-1", Rating.HELPFUL));
        store.add(fb("f2", "run-1", Rating.NOT_HELPFUL));
        store.add(fb("f3", "run-2", Rating.HELPFUL));

        assertEquals(3, store.size());
        assertEquals("f3", store.recent(10).get(0).id()); // newest-first
        List<Feedback> forRun1 = store.byTriageRunId("run-1");
        assertEquals(2, forRun1.size());
        assertEquals("f2", forRun1.get(0).id());          // newest-first within a Triage Run
        assertEquals(Rating.HELPFUL, store.byId("f1").orElseThrow().rating());
        assertTrue(store.byId("nope").isEmpty());
        assertTrue(store.byTriageRunId("run-x").isEmpty());
    }

    @Test
    void boundedEvictsOldest() {
        FeedbackStore store = new FeedbackStore(2);
        store.add(fb("a", "c", Rating.HELPFUL));
        store.add(fb("b", "c", Rating.HELPFUL));
        store.add(fb("c", "c", Rating.HELPFUL));
        assertEquals(2, store.size());
        assertTrue(store.byId("a").isEmpty()); // oldest evicted
    }

    @Test
    void durableAcrossReload(@TempDir Path dir) {
        Path file = dir.resolve("agent").resolve("feedback.jsonl");
        FeedbackStore first = new FeedbackStore(file);
        first.add(fb("f1", "run-1", Rating.HELPFUL));
        first.add(fb("f2", "run-1", Rating.NOT_HELPFUL));

        FeedbackStore reloaded = new FeedbackStore(file);
        assertEquals(2, reloaded.size());
        assertEquals(Rating.NOT_HELPFUL, reloaded.byId("f2").orElseThrow().rating());
        assertEquals("note-f1", reloaded.byId("f1").orElseThrow().note());
    }

    @Test
    void preRenameFileKeyedOnCaseIdIsNotLoaded(@TempDir Path dir) throws Exception {
        // GLOSSARY-CASE-1: a feedback.jsonl written before the rename keys on `caseId`. It must not load as
        // feedback with a null join key (that NPEs byTriageRunId) - the whole file is ignored instead.
        Path file = dir.resolve("agent").resolve("feedback.jsonl");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, "{\"id\":\"f1\",\"caseId\":\"c1\",\"rating\":\"HELPFUL\","
                + "\"note\":null,\"submittedBy\":\"alice\",\"at\":\"2026-09-01T00:00:00Z\"}\n");
        FeedbackStore store = new FeedbackStore(file);
        assertEquals(0, store.size());
        assertTrue(store.byTriageRunId("c1").isEmpty());
    }

    @Test
    void parseRatingAcceptsSynonymsAndRejectsGarbage() {
        assertEquals(Rating.HELPFUL, Feedback.parseRating("helpful"));
        assertEquals(Rating.HELPFUL, Feedback.parseRating("UP"));
        assertEquals(Rating.NOT_HELPFUL, Feedback.parseRating("not_helpful"));
        assertEquals(Rating.NOT_HELPFUL, Feedback.parseRating("thumbs_down"));
        assertNull(Feedback.parseRating("banana"));
        assertNull(Feedback.parseRating(null));
    }

    @Test
    void viewRoundTripsThroughRecord() {
        Feedback f = fb("f1", "run-1", Rating.HELPFUL);
        Feedback back = Feedback.fromRecord(f.toView());
        assertEquals(f.id(), back.id());
        assertEquals(f.triageRunId(), back.triageRunId());
        assertEquals(f.rating(), back.rating());
        assertEquals(f.note(), back.note());
        assertEquals(f.submittedBy(), back.submittedBy());
    }
}
