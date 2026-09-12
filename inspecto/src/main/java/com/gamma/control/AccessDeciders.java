package com.gamma.control;

import java.util.Optional;

/**
 * Resolves the edition's {@link AccessDecider}, mirroring {@link Authenticators}' "absent module ⇒
 * no-op wins" pattern: Personal/Standard ship no {@code META-INF/services} registration, so
 * {@link #active()} is empty and both PEPs (the authorize stage, {@link RowScope}) skip policy
 * evaluation entirely. Typed facade over a {@link SpiSlot}, which owns the caching/first-wins semantics.
 *
 * <p>🔴 <b>Fail-CLOSED, for the same reason as {@link Authenticators} (operator decision 2026-09-13).</b>
 * "Absent ⇒ no-op wins" is only safe where absence costs a feature. Here both PEPs read absence as
 * <em>allow</em> — {@code ControlApi.authorize} returns early and {@link RowScope#visible} returns
 * {@code true} — and {@code PolicyEngine}'s seeded policies are {@code space-isolation} /
 * {@code space-isolation-rows}. So a registered-but-unloadable {@code inspecto-policy} would stop
 * enforcing the <b>multi-tenant Space boundary</b>, at route and row level, silently.
 *
 * <p>⚠ The trigger differs from the Authenticator's and that is worth knowing: {@code PolicyEngine}
 * takes no configuration, so no typo can break it — only a packaging fault or {@code LinkageError} can,
 * which is precisely the case PKG-5 made non-fatal on purpose. ⛔ The judgement is that a tenancy boundary
 * is not a feature: losing it silently is worse than refusing to boot. Absence when <em>nothing is
 * registered</em> is still legitimate and still resolves empty — that is the Personal/Standard path.
 */
final class AccessDeciders {
    private AccessDeciders() {}

    private static final SpiSlot<AccessDecider> SLOT = new SpiSlot<>(AccessDecider.class, true);

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
