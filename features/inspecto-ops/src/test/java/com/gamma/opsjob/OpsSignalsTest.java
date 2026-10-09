package com.gamma.opsjob;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. */
class OpsSignalsTest {

    @Test
    void signalTypesAreByteIdentical() {
        assertEquals("objects.analytics.completed", OpsSignals.OBJECTS_ANALYTICS_COMPLETED);
    }
}
