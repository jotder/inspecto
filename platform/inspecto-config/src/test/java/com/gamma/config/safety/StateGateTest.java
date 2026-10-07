package com.gamma.config.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slice S6 of {@code policy-narrowing-design.md}: {@link StateGate} over a run's pinned tier. */
class StateGateTest {

    private static void under(Boolean advance, Boolean rewind, SafetyPolicyTier.Mode mode, Runnable r) {
        SafetyPolicyTier t = new SafetyPolicyTier(mode, null, null, advance, rewind,
                null, null, null, null, null, null, null, null, null, null);
        SafetyPolicy.runWithPinned(new SafetyPolicy.Pin("default", SafetyPolicy.withRoots(), t), () -> { r.run(); return null; });
    }

    @Test
    void anAbsentPermitAdvancesAndRewinds() {
        under(null, null, null, () -> {
            assertDoesNotThrow(() -> StateGate.requireAdvance("x"));
            assertDoesNotThrow(() -> StateGate.requireRewind("x"));
        });
    }

    @Test
    void advanceFalseRefusesAdvanceButNotRewind() {
        under(false, null, null, () -> {
            StateRefusedException e = assertThrows(StateRefusedException.class, () -> StateGate.requireAdvance("processed markers"));
            assertTrue(e.getMessage().contains("processed markers") && e.getMessage().contains("permit.advance_state"), e.getMessage());
            assertDoesNotThrow(() -> StateGate.requireRewind("x"));
        });
    }

    @Test
    void rewindFalseRefusesRewindButNotAdvance() {
        under(null, false, null, () -> {
            assertThrows(StateRefusedException.class, () -> StateGate.requireRewind("reprocess"));
            assertDoesNotThrow(() -> StateGate.requireAdvance("x"));
        });
    }

    @Test
    void auditModeLetsTheActProceed() {
        under(false, false, SafetyPolicyTier.Mode.AUDIT, () -> {
            assertDoesNotThrow(() -> StateGate.requireAdvance("x"));
            assertDoesNotThrow(() -> StateGate.requireRewind("x"));
        });
    }
}
