package com.gamma.ops.cases;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. */
class CaseSignalsTest {

    @Test
    void signalTypesAreByteIdentical() {
        assertEquals("caserule.evaluate.completed", CaseSignals.CASERULE_EVALUATE_COMPLETED);
    }
}
