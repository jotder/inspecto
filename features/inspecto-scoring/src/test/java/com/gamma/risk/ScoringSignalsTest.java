package com.gamma.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. */
class ScoringSignalsTest {

    @Test
    void signalTypesAreByteIdentical() {
        assertEquals("risk.score.produced", ScoringSignals.RISK_SCORE_PRODUCED);
    }
}
