package com.gamma.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The masked-answer stamp (AGENT-KIT-1 W6 S0): scoped per thread, nested, a no-op outside a scope, refused 2xx when unstamped. */
class AnswerMaskingTest {

    @AfterEach
    void reset() {
        AnswerMasking.ignoreStampsForTest(false);
    }

    @Test
    void aStampOutsideAScopeDoesNothingAndASendOutsideOneIsFree() {
        AnswerMasking.applied("ds", 1);
        AnswerMasking.requireProvenBeforeSend(200);   // no scope: a person's request, a non-data route
        try (AnswerMasking.Scope s = AnswerMasking.open()) {
            assertFalse(s.stamped(), "a stamp made before the scope opened is not this answer's");
        }
    }

    @Test
    void aStampedScopeSendsAndAnUnstampedOneRefusesOnly2xx() {
        try (AnswerMasking.Scope s = AnswerMasking.open()) {
            AnswerMasking.requireProvenBeforeSend(422);   // a refusal carries no rows
            AnswerMasking.requireProvenBeforeSend(304);
            assertThrows(AnswerMasking.NotProven.class, () -> AnswerMasking.requireProvenBeforeSend(200));
            AnswerMasking.applied("calls_ds", 0);   // mode none: zero masked still proves the step ran
            assertTrue(s.stamped());
            assertEquals(0, s.maskedColumns());
            AnswerMasking.applied("calls_ds", 2);
            assertEquals(2, s.maskedColumns());
            assertEquals("calls_ds", s.basis());
            AnswerMasking.requireProvenBeforeSend(200);
        }
    }

    @Test
    void scopesNestAndRestoreTheOuterOne() {
        try (AnswerMasking.Scope outer = AnswerMasking.open()) {
            try (AnswerMasking.Scope inner = AnswerMasking.open()) {
                AnswerMasking.applied("inner", 1);
                assertTrue(inner.stamped());
            }
            assertFalse(outer.stamped(), "an inner replay's stamp never proves the outer answer");
            AnswerMasking.applied("outer", 0);
            assertTrue(outer.stamped());
        }
        try (AnswerMasking.Scope fresh = AnswerMasking.open()) {
            assertFalse(fresh.stamped());
        }
    }

    @Test
    void aStampOnAnotherThreadIsNotSeen() throws Exception {
        try (AnswerMasking.Scope s = AnswerMasking.open()) {
            Thread t = new Thread(() -> AnswerMasking.applied("elsewhere", 1));
            t.start();
            t.join();
            assertFalse(s.stamped(), "masking moved off the request thread fails closed");
        }
    }

    @Test
    void theTestSeamMakesEveryStampANoOp() {
        AnswerMasking.ignoreStampsForTest(true);
        try (AnswerMasking.Scope s = AnswerMasking.open()) {
            AnswerMasking.applied("ds", 1);
            assertFalse(s.stamped());
        }
        try (AnswerMasking.Scope s = AnswerMasking.open(true)) {
            AnswerMasking.unmaskedByDecision("enrichment");
            assertFalse(s.stamped(), "the raw stamp too");
        }
    }

    /** D-AK22: a raw-by-decision stamp proves an answer only in a scope the host opened for such a route. */
    @Test
    void aRawByDecisionStampProvesOnlyAScopeOpenedForIt() {
        try (AnswerMasking.Scope s = AnswerMasking.open()) {
            AnswerMasking.unmaskedByDecision("enrichment");
            assertFalse(s.stamped(), "a raw answer on any other data route still fails closed");
            assertThrows(AnswerMasking.NotProven.class, () -> AnswerMasking.requireProvenBeforeSend(200));
        }
        try (AnswerMasking.Scope s = AnswerMasking.open(true)) {
            AnswerMasking.unmaskedByDecision("enrichment");
            assertTrue(s.stamped());
            assertEquals(0, s.maskedColumns());
            AnswerMasking.requireProvenBeforeSend(200);
        }
        try (AnswerMasking.Scope outer = AnswerMasking.open(true)) {
            try (AnswerMasking.Scope inner = AnswerMasking.open()) {
                AnswerMasking.unmaskedByDecision("enrichment");
                assertFalse(inner.stamped(), "a nested ordinary scope does not inherit the allowance");
            }
            assertFalse(outer.stamped());
        }
    }
}
