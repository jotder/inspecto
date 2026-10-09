package com.gamma.recon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. */
class ReconSignalsTest {

    @Test
    void signalTypesAreByteIdentical() {
        assertEquals("recon.run.completed", ReconSignals.RECON_RUN_COMPLETED);
    }
}
