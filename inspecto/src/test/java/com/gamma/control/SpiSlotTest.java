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

    /**
     * 🔴 The assertion above CANNOT detect the bug it describes, and did not: with an unregistered SPI,
     * "re-armed the scan" and "pinned to empty" are the same observation. {@code forTest} read
     * {@code cached = Optional.ofNullable(t)} until 2026-09-12, so {@code forTest(null)} cached an EMPTY
     * Optional and every later lookup returned it — the opposite of the documented teardown contract.
     *
     * <p>⚠ This probe re-arms a slot whose provider genuinely RESOLVES, so pinned-empty and
     * scanned-present are distinguishable. Keep both: the one above pins the Personal-edition path, this
     * one pins that the seam is actually released.
     */
    @Test
    void nullReArmsTheScanEvenWhenTheScanWouldFindSomething() {
        SpiSlot<SpiSlotFailClosedTest.Working> slot = new SpiSlot<>(SpiSlotFailClosedTest.Working.class);
        assertTrue(slot.active().isPresent(), "precondition: this SPI really is registered");

        SpiSlotFailClosedTest.Working fake = new SpiSlotFailClosedTest.Working() {};
        slot.forTest(fake);
        assertSame(fake, slot.active().orElseThrow(), "the seam wins while it is set");

        slot.forTest(null);
        assertInstanceOf(SpiSlotFailClosedTest.WorkingProvider.class, slot.active().orElseThrow(),
                "releasing the seam must SCAN again, not pin the slot to whatever it last held");
    }
}
