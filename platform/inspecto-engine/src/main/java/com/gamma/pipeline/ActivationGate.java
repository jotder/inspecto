package com.gamma.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SE-1b, D-SE12 — the seam both schedulers consult before an AUTOMATIC run of a Pipeline or a Job: a held object
 * acts as if it were not active and says why ({@code spec-space-lock.md} §10). The engine is identity- and
 * Space-policy-agnostic, so the control plane installs the {@link Check} ({@code ActivationHolds}); with none
 * installed nothing is held, which is every engine-only use (the one-shot CLI, most tests).
 *
 * <p>A check that throws holds nothing (logged): a hold is an opt-in narrowing, and a fault in it must never stop
 * the Space's other work (plan §2 rule 4).
 */
public final class ActivationGate {

    private static final Logger log = LoggerFactory.getLogger(ActivationGate.class);

    /** Why {@code name} of {@code kind} ({@code pipeline} or {@code job}) in {@code spaceId} is held, or {@code null}. */
    @FunctionalInterface
    public interface Check {
        String reason(String spaceId, String kind, String name);
    }

    private static volatile Check check;

    private ActivationGate() {}

    /** Install (or, with {@code null}, remove) the process-wide check. */
    public static void install(Check c) {
        check = c;
    }

    public static Check installed() {
        return check;
    }

    /** Why the object is held, or {@code null} when it may run (no check installed, or the check threw). */
    public static String reason(String spaceId, String kind, String name) {
        Check c = check;
        if (c == null || name == null) return null;
        try {
            return c.reason(spaceId, kind, name);
        } catch (RuntimeException e) {
            log.warn("[ACTIVATION] the hold check failed for {} '{}' of Space '{}', so it is not held: {}",
                    kind, name, spaceId, e.toString());
            return null;
        }
    }
}
