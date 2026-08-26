package com.gamma.control;

import java.util.Optional;

/**
 * Resolves the edition's {@link AccessDecider}, mirroring {@link Authenticators}' "absent module ⇒
 * no-op wins" pattern: Personal/Standard ship no {@code META-INF/services} registration, so
 * {@link #active()} is empty and both PEPs (the authorize stage, {@link RowScope}) skip policy
 * evaluation entirely. Typed facade over a {@link SpiSlot}, which owns the caching/first-wins semantics.
 */
final class AccessDeciders {
    private AccessDeciders() {}

    private static final SpiSlot<AccessDecider> SLOT = new SpiSlot<>(AccessDecider.class);

    static Optional<AccessDecider> active() {
        return SLOT.active();
    }

    /** Test seam (mirrors {@link Authenticators#forTest}): force {@link #active()} for the rest of this
     *  JVM's tests. A test must restore {@code null} in its teardown so later classes see the
     *  classpath-scanned behaviour again. */
    static void forTest(AccessDecider d) {
        SLOT.forTest(d);
    }
}
