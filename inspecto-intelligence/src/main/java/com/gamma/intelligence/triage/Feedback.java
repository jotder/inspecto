package com.gamma.intelligence.triage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An operator's verdict on an investigation {@link TriageRun} (AGT-5 P5, "Learning",
 * {@code docs/superpower/embedded-intelligence-plan.md} §8). Feedback is the raw signal the learning
 * tier turns into eval growth and per-skill tuning: was the agent's root-cause analysis + fix draft
 * actually useful? Captured via {@code POST /agent/triage-runs/{id}/feedback} and stored durably (unlike the
 * ephemeral {@code TriageRunStore}) so it accrues across restarts.
 *
 * <p>Immutable once recorded — feedback is an append-only observation, never edited.
 */
public record Feedback(String id, String triageRunId, Rating rating, String note, String submittedBy, Instant at) {

    /** Was the Triage Run useful? A two-value verdict keeps aggregation simple (helpful-rate per skill). */
    public enum Rating { HELPFUL, NOT_HELPFUL }

    /** The persisted / {@code GET} view — a plain, JSON-friendly map (the core stays free of this type). */
    public Map<String, Object> toView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("triageRunId", triageRunId);
        m.put("rating", rating.name());
        m.put("note", note);
        m.put("submittedBy", submittedBy);
        m.put("at", at.toString());
        return m;
    }

    Map<String, Object> toRecord() { return toView(); }

    static Feedback fromRecord(Map<String, Object> m) {
        // Fail closed on a record without the join key (one written before GLOSSARY-CASE-1 keyed it `caseId`):
        // the ring's load then drops the whole file rather than holding feedback that matches nothing.
        String triageRunId = Objects.requireNonNull((String) m.get("triageRunId"), "triageRunId");
        return new Feedback((String) m.get("id"), triageRunId,
                Rating.valueOf((String) m.get("rating")), (String) m.get("note"),
                (String) m.get("submittedBy"), Instant.parse((String) m.get("at")));
    }

    /** Parse a request's {@code rating} field → {@link Rating}, or {@code null} when unrecognized (→ 400). */
    public static Rating parseRating(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "helpful", "up", "thumbs_up", "good", "yes", "true", "1" -> Rating.HELPFUL;
            case "not_helpful", "not-helpful", "unhelpful", "down", "thumbs_down", "bad", "no", "false", "0" ->
                    Rating.NOT_HELPFUL;
            default -> null;
        };
    }
}
