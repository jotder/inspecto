package com.gamma.config.safety;

/**
 * The act-time state gate of the Safety Policy (policy-narrowing-design §4.2, S6): every site that moves a Space's
 * progress state forward (markers, fingerprint ledger, DB/Kafka watermark, slice frontier, incremental high-water)
 * passes {@link #requireAdvance} first; every site that rewinds it (reprocess, prune) passes {@link #requireRewind}.
 * Reads the calling thread's pinned snapshot ({@code SafetyPolicy.pinnedForRun}). {@code mode: audit} (D5) logs the
 * would-refuse and lets the act proceed.
 *
 * <p>⚠ Ordering: the ledger/marker sites run <em>after</em> outputs are durable, so refusing there strands outputs
 * without their ledger row (the next run re-ingests). A run therefore also checks once at batch start, before any
 * output is written; the per-site checks are the backstop that turns a planner bypass into a loud failure.
 */
public final class StateGate {

    private static final System.Logger log = System.getLogger(StateGate.class.getName());

    private StateGate() {}

    /** @param what the state being advanced ({@code "processed markers"}) - named in the refusal */
    public static void requireAdvance(String what) {
        SafetyPolicyTier t = SafetyPolicy.effectiveTier();
        check(t, t.permitsAdvanceState(), "permit.advance_state", "advance", what);
    }

    /** @param what the state being rewound ({@code "reprocess"}) - named in the refusal */
    public static void requireRewind(String what) {
        SafetyPolicyTier t = SafetyPolicy.effectiveTier();
        check(t, t.permitsRewindState(), "permit.rewind_state", "rewind", what);
    }

    private static void check(SafetyPolicyTier t, boolean permitted, String key, String verb, String what) {
        if (permitted) return;
        String msg = "state " + verb + " refused by the Safety Policy: " + what + " (" + key + " is false)";
        if (t.effectiveMode() == SafetyPolicyTier.Mode.AUDIT) {
            log.log(System.Logger.Level.WARNING, "AUDIT would refuse: " + msg);
            return;
        }
        throw new StateRefusedException(msg);
    }
}
