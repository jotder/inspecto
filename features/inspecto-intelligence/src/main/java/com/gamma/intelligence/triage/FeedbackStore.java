package com.gamma.intelligence.triage;

import com.gamma.intelligence.store.DurableJsonlRing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A durable, bounded ring of {@link Feedback} on Triage Runs (AGT-5 P5) — the corpus the learning
 * tier aggregates. Ring mechanics + JSON-lines durability come from {@link DurableJsonlRing}.
 *
 * <p>Unlike the ephemeral {@code TriageRunStore}, feedback outlives the Triage Run it points at (a Triage Run may be
 * evicted from its 256-deep ring); the {@code triageRunId} is the durable join key, so this ring is deeper.
 */
public final class FeedbackStore extends DurableJsonlRing<Feedback> {

    private static final int DEFAULT_CAPACITY = 1024;
    private static final Codec<Feedback> CODEC = new Codec<>() {
        @Override public Map<String, Object> toRecord(Feedback f) { return f.toRecord(); }
        @Override public Feedback fromRecord(Map<String, Object> m) { return Feedback.fromRecord(m); }
    };

    public FeedbackStore() { this(DEFAULT_CAPACITY, null); }

    public FeedbackStore(Path file) { this(DEFAULT_CAPACITY, file); }

    FeedbackStore(int capacity) { this(capacity, null); }

    FeedbackStore(int capacity, Path file) { super(capacity, file, CODEC, "triage-run-feedback entr(ies)"); }

    public void add(Feedback f) { append(f); }

    /** Newest-first, capped at {@code limit}. */
    public synchronized List<Feedback> recent(int limit) { return recentSnapshot(limit); }

    /** All feedback for one Triage Run, newest-first. */
    public synchronized List<Feedback> byTriageRunId(String triageRunId) {
        List<Feedback> out = new ArrayList<>();
        for (Feedback f : ring) if (f.triageRunId().equals(triageRunId)) out.add(f);
        Collections.reverse(out);
        return out;
    }

    public synchronized Optional<Feedback> byId(String id) {
        return ring.stream().filter(f -> f.id().equals(id)).findFirst();
    }
}
