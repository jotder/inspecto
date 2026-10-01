package com.gamma.control;

import java.util.Optional;

/**
 * Resolves the edition's {@link Authenticator}, mirroring {@code com.gamma.acquire.CollectorConnectors}'
 * "absent module ⇒ no-op wins" pattern: Personal ships no {@code META-INF/services} registration, so
 * {@link #active()} is empty and {@link ControlApi#dispatch} skips authentication entirely. Typed
 * facade over a {@link SpiSlot}, which owns the caching/first-wins semantics.
 *
 * <p>🔴 <b>This slot is fail-CLOSED, and it is the only one that is.</b> "Absent ⇒ no-op wins" is safe
 * for a feature module, whose own routes then answer 503. It is not safe here: an absent Authenticator
 * means <em>every</em> route serves unauthenticated. Until 2026-09-12 this slot shared the fail-soft path,
 * so a Standard deployment with a mistyped {@code -Dauth.oidc.jwksUri} booted wide open while
 * {@link ControlApi}'s constructor promised it would "fail to boot instead of silently accepting
 * traffic" — pinned by {@code AuthenticatorDiscoveryFailClosedTest}.
 */
final class Authenticators {
    private Authenticators() {}

    // 🔴 failClosed: for THIS spi, absent means every route serves unauthenticated, so a registered
    // inspecto-security that refuses to construct (a typo in -Dauth.oidc.jwksUri) must propagate rather
    // than resolve empty. Personal registers no provider at all and still resolves empty, unchanged.
    private static final SpiSlot<Authenticator> SLOT = new SpiSlot<>(Authenticator.class, true);

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
