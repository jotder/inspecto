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
    private volatile Optional<T> cached;

    SpiSlot(Class<T> spi) {
        this.spi = spi;
    }

    /** The edition's provider, empty when no module registers one. */
    Optional<T> active() {
        Optional<T> c = cached;
        if (c != null) return c;
        // Fail-soft: an optional module that is present but unloadable resolves to EMPTY, which is the
        // absence contract, rather than throwing an Error out of whatever happened to ask first.
        return cached = com.gamma.service.OptionalSpi.first(spi);
    }

    /** Test seam: force {@link #active()} for the rest of this JVM's tests, bypassing the classpath
     *  scan. A test must restore {@code null} in its teardown so later classes see the scanned
     *  behaviour again. Production code never calls this. */
    void forTest(T t) {
        cached = Optional.ofNullable(t);
    }
}
