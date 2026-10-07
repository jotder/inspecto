package com.gamma.inspector;

import com.gamma.acquire.IntakeGovernor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pre-materialise <b>byte</b> cap (operator 2026-09-16: cap on BYTES, remainder DEFERRED).
 *
 * <p>⚠ The unit is the point: fetch bandwidth is the scarce resource, and a file COUNT cannot bound it —
 * one very large file blows straight through a count cap.
 */
class IntakeByteCapTest {

    @AfterEach
    void clear() {
        System.clearProperty("ingest.maxBytesPerCycle");
    }

    @Test
    void theByteCapIsOffByDefault() {
        assertEquals(IntakeGovernor.Policy.UNBOUNDED_BYTES,
                IntakeGovernor.Policy.fromSystemProperties().maxBytesPerCycle(),
                "admission control must stay opt-in - a default cap would throttle every existing install");
    }

    @Test
    void theByteCapIsReadFromTheProperty() {
        System.setProperty("ingest.maxBytesPerCycle", "1048576");
        assertEquals(1048576L, IntakeGovernor.Policy.fromSystemProperties().maxBytesPerCycle());
    }

    /** ⚠ A malformed or negative value falls back rather than throttling to nothing mid-flight. */
    @Test
    void aMalformedValueFallsBack() {
        System.setProperty("ingest.maxBytesPerCycle", "not-a-number");
        assertEquals(IntakeGovernor.Policy.UNBOUNDED_BYTES,
                IntakeGovernor.Policy.fromSystemProperties().maxBytesPerCycle());

        System.setProperty("ingest.maxBytesPerCycle", "-5");
        assertEquals(0L, IntakeGovernor.Policy.fromSystemProperties().maxBytesPerCycle(),
                "a negative cap is clamped to 'off', never to 'admit nothing'");
    }

    /** The three-arg shape every pre-byte-cap caller used still means "no byte cap". */
    @Test
    void theLegacyConstructorMeansNoByteCap() {
        assertEquals(IntakeGovernor.Policy.UNBOUNDED_BYTES,
                new IntakeGovernor.Policy(10, 1, true).maxBytesPerCycle());
    }
}
