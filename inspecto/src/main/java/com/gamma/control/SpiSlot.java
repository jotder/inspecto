package com.gamma.control;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The one implementation of the control plane's "absent module ⇒ no-op wins" edition seam:
 * resolve at most one {@link ServiceLoader} provider of {@code spi}, first found wins, cached
 * after the first lookup (the classpath does not change at runtime). {@link Authenticators},
 * {@link AccessDeciders} and {@link TokenRelays} are typed facades over one slot each — they
 * used to carry three hand-mirrored copies of this logic.
 */
final class SpiSlot<T> {

    private final Class<T> spi;
    private final boolean failClosed;
    private volatile Optional<T> cached;

    SpiSlot(Class<T> spi) {
        this(spi, false);
    }

    /**
     * @param failClosed when true, a provider that IS registered but cannot be constructed propagates
     *                   instead of degrading to absence. ⚠ Only for an SPI whose absence removes a
     *                   safety property — see {@link #active()}.
     */
    SpiSlot(Class<T> spi, boolean failClosed) {
        this.spi = spi;
        this.failClosed = failClosed;
    }

    /**
     * The edition's provider, empty when no module registers one.
     *
     * <p>Fail-soft by default: an optional module that is present but unloadable resolves to EMPTY, which
     * is the absence contract, rather than throwing an Error out of whatever happened to ask first. That
     * is right for a module whose absence only costs a feature — its own routes answer 503 (PKG-5: the
     * assistant sidecar, compiled for a newer JDK, must not stop the server booting).
     *
     * <p>🔴 <b>It is the wrong default for an SPI whose absence removes a safety property</b>, which is
     * why {@code failClosed} exists. For {@link Authenticator}, absent means <em>every route serves
     * unauthenticated</em> — there are no "its routes" to answer 503. Degrading a misconfigured
     * {@code inspecto-security} to absence therefore turns a typo in {@code -Dauth.oidc.jwksUri} into a
     * wide-open control plane, which is precisely what {@link ControlApi}'s constructor says it prevents.
     *
     * <p>⚠ The distinction under {@code failClosed} is <b>registered-but-broken</b> vs <b>not
     * registered</b>, and it has to be: Personal ships no {@code META-INF/services} entry at all, and that
     * absence is legitimate and must stay empty. Only a provider that announced itself and then failed to
     * construct propagates.
     */
    Optional<T> active() {
        Optional<T> c = cached;
        if (c != null) return c;
        if (!failClosed) return cached = com.gamma.service.OptionalSpi.first(spi);
        // Deliberately NOT OptionalSpi: it catches ServiceConfigurationError, which is exactly what
        // ServiceLoader wraps a provider constructor's throw in.
        var it = ServiceLoader.load(spi).iterator();
        if (!it.hasNext()) return cached = Optional.empty();   // nothing registered — a real absence
        return cached = Optional.of(it.next());                // registered: let a failure propagate
    }

    /**
     * Test seam: force {@link #active()} for the rest of this JVM's tests, bypassing the classpath
     * scan. A test must restore {@code null} in its teardown so later classes see the scanned
     * behaviour again. Production code never calls this.
     *
     * <p>🔴 {@code null} must clear the cache, NOT cache an empty Optional. This read
     * {@code cached = Optional.ofNullable(t)} until 2026-09-12, which pinned the slot to EMPTY for the
     * rest of the JVM instead of re-arming the scan — so a teardown that claimed to restore scanned
     * behaviour actually guaranteed "no provider" to every later test class.
     *
     * <p>⚠ {@code SpiSlotTest} asserted the documented contract and still passed, because it used an SPI
     * with no registration: pinned-empty and scanned-empty are the same observation there. A negative
     * assertion needs a probe that would otherwise SUCCEED — the strengthened test now re-arms a slot
     * whose provider DOES resolve, so the two outcomes differ.
     */
    void forTest(T t) {
        cached = t == null ? null : Optional.of(t);
    }
}
