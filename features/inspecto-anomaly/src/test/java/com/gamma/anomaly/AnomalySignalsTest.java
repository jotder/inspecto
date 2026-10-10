package com.gamma.anomaly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. */
class AnomalySignalsTest {

    @Test
    void signalTypesAreByteIdentical() {
        assertEquals("anomaly.score.produced", AnomalySignals.ANOMALY_SCORE_PRODUCED);
    }
}
