package com.gamma.la.api;

import com.gamma.entitystore.IndustryPack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** An Industry pack's {@code measure.<id>} terms may name exactly the Value Measures (the core's list is a mirror). */
class ValueMeasuresPackKeysTest {

    @Test
    void thePackMeasureKeysAreTheAlertableValueMeasures() {
        assertEquals(ValueMeasures.ALERTABLE, IndustryPack.MEASURES);
    }
}
