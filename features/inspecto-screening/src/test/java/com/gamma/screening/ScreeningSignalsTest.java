package com.gamma.screening;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. */
class ScreeningSignalsTest {

    @Test
    void signalTypesAreByteIdentical() {
        assertEquals("screening.hits.raised", ScreeningSignals.SCREENING_HITS_RAISED);
    }
}
