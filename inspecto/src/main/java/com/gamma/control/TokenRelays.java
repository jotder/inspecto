package com.gamma.control;

import java.util.Optional;

/**
 * Resolves the edition's {@link TokenRelay} — same "absent module ⇒ nothing found ⇒ capability
 * unavailable" pattern as {@link Authenticators}. Typed facade over a {@link SpiSlot}, which owns
 * the caching/first-wins semantics.
 */
final class TokenRelays {
    private TokenRelays() {}

    private static final SpiSlot<TokenRelay> SLOT = new SpiSlot<>(TokenRelay.class);

    static Optional<TokenRelay> active() {
        return SLOT.active();
    }

    /** Test seam (mirrors {@link Authenticators#forTest}): force {@link #active()}, bypassing the
     *  classpath scan. Tests must restore {@code null} in teardown. */
    static void forTest(TokenRelay r) {
        SLOT.forTest(r);
    }
}
