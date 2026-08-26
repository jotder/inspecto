package com.gamma.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the edition-seam slot semantics the three facades (Authenticators / AccessDeciders /
 * TokenRelays) rely on: absent provider ⇒ empty (and cached), forTest overrides, forTest(null)
 * re-arms the classpath scan.
 */
class SpiSlotTest {

    /** No META-INF/services registration anywhere — the "absent module" case. */
    interface Unregistered {}

    @Test
    void absentProviderResolvesEmptyAndCaches() {
        SpiSlot<Unregistered> slot = new SpiSlot<>(Unregistered.class);
        assertTrue(slot.active().isEmpty());
        assertSame(slot.active(), slot.active(), "second lookup returns the cached Optional");
    }

    @Test
    void forTestOverridesAndNullReArmsTheScan() {
        SpiSlot<Unregistered> slot = new SpiSlot<>(Unregistered.class);
        Unregistered fake = new Unregistered() {};
        slot.forTest(fake);
        assertSame(fake, slot.active().orElseThrow());
        slot.forTest(null);
        assertTrue(slot.active().isEmpty(), "null restores classpath-scanned behaviour");
    }
}
