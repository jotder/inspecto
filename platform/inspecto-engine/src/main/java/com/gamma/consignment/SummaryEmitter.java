package com.gamma.consignment;

import com.gamma.api.PublicApi;

/**
 * <b>§14.4 step 4 — the §7.2 guardrail, deliberately not a raw writer.</b> How a processor emits summary rows.
 *
 * <p>§7.2's composability rules get wrong numbers <em>quietly</em> when unenforced, and a raw writer would make
 * every processor author responsible for them. So this seam enforces what §7.2 says the config validator
 * enforces: {@code count} is mandatory on every row, and every measure must declare how it composes — an
 * undeclared or mis-declared measure is <b>refused rather than guessed</b>, matching the {@code projection_author}
 * precedent.
 *
 * <p>This is also why the context exposes this and <em>not</em> {@code ArtifactRecorder}: two plausible ways to
 * emit the same thing would be a one-concept-two-words violation. Summary output goes through here, full stop.
 */
@PublicApi(since = "4.0.0")
public interface SummaryEmitter {

    /** The reserved, mandatory measure name (§7.2): every summary row must carry a row count. */
    String COUNT = "count";

    /**
     * Emit one validated summary row.
     *
     * @throws IllegalArgumentException when the row violates §7.2. The message names <em>every</em> violation,
     *                                 not just the first — a refusal should take one repair round, not several.
     */
    void emit(SummaryRow row);
}
