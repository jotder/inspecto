package com.gamma.la.api;

import com.gamma.la.core.InputFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A timed-out listing is cached for the TTL like any answer, so a pathological Dataset is not re-walked on every request. */
class InputFingerprintCacheTest {

    @AfterEach
    void restore() {
        InputFingerprintCache.forTest(null, 0);
    }

    @Test
    void anUnknownTimeoutIsCachedForItsTtlThenListedAgain() {
        AtomicLong now = new AtomicLong();
        InputFingerprintCache.forTest(now::get, 30_000L);
        Path root = Path.of("cache-test-root");
        long start = InputFingerprintCache.loads();
        InputFingerprint first = InputFingerprintCache.get(root, "ds", "m", () -> InputFingerprint.unknown(InputFingerprint.TIMEOUT));
        assertFalse(first.known());
        InputFingerprintCache.get(root, "ds", "m", () -> { throw new AssertionError("walked again within the TTL"); });
        assertEquals(start + 1, InputFingerprintCache.loads());
        now.addAndGet(30_001L);
        InputFingerprintCache.get(root, "ds", "m", () -> InputFingerprint.unknown(InputFingerprint.TIMEOUT));
        assertEquals(start + 2, InputFingerprintCache.loads(), "listed again once the TTL passed");
    }
}
