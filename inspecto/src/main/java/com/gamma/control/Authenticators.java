package com.gamma.control;

import java.util.Optional;

/**
 * Resolves the edition's {@link Authenticator}, mirroring {@code com.gamma.acquire.CollectorConnectors}'
 * "absent module ⇒ no-op wins" pattern: Personal ships no {@code META-INF/services} registration, so
 * {@link #active()} is empty and {@link ControlApi#dispatch} skips authentication entirely. Typed
 * facade over a {@link SpiSlot}, which owns the caching/first-wins semantics.
 */
final class Authenticators {
    private Authenticators() {}

    private static final SpiSlot<Authenticator> SLOT = new SpiSlot<>(Authenticator.class);

    static Optional<Authenticator> active() {
        return SLOT.active();
    }

    /** Test seam: force {@link #active()} to a specific value for the rest of this JVM's tests, bypassing
     *  the classpath scan (the core's own test classpath carries no {@code Authenticator} registration,
     *  so a real Standard-edition gate can only be exercised this way). Production code never calls this;
     *  a test must restore {@code null} in its teardown so later test classes see Personal behaviour again. */
    static void forTest(Authenticator a) {
        SLOT.forTest(a);
    }
}
