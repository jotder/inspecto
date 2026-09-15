package com.gamma.job;

/**
 * How a Run was started: a {@code kind} ({@code schedule} | {@code event} | {@code manual} |
 * {@code catch-up}) and its {@code detail} (the actor for {@code manual:<actor>}, the upstream
 * pipeline for {@code event:<pipeline>}, else blank). Parsed from the recorded trigger string so a
 * Job can branch on how it fired without re-parsing.
 */
public record TriggerInfo(String kind, String detail) {

    /**
     * The pipeline that owns {@code ctx}'s run, or {@code null} when none does — the value a
     * {@code dataset.write} Signal carries so the scheduler can break a self-loop
     * ({@code DATASET-SELF-TRIGGER-1}).
     *
     * <p>⚠ Only an {@code event:<pipeline>} trigger has an owning pipeline; a {@code schedule} or
     * {@code manual} run genuinely has none, and {@code null} there means "suppress nothing" — never
     * "suppress everything".
     *
     * <p>⛔ Null-safe in BOTH {@code ctx} and {@code ctx.trigger()} on purpose: several job tests construct a
     * context with no trigger, and one passes no context at all. Reading {@code ctx.trigger().kind()}
     * directly at the call sites threw {@code NullPointerException} across nineteen tests — an announcement
     * seam must not be able to fail the write it announces.
     */
    static String owningPipeline(JobContext ctx) {
        TriggerInfo t = ctx == null ? null : ctx.trigger();
        if (t == null || !"event".equals(t.kind())) return null;
        return t.detail() == null || t.detail().isBlank() ? null : t.detail();
    }

    /** Parse a recorded trigger string ({@code "manual:alice"}, {@code "event:X"}, {@code "schedule"}). */
    public static TriggerInfo parse(String trigger) {
        if (trigger == null || trigger.isBlank()) return new TriggerInfo("", "");
        int i = trigger.indexOf(':');
        return i < 0 ? new TriggerInfo(trigger, "")
                     : new TriggerInfo(trigger.substring(0, i), trigger.substring(i + 1));
    }
}
