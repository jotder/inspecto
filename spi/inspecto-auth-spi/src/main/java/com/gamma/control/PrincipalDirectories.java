package com.gamma.control;

import java.util.Optional;

/** The active {@link PrincipalDirectory}, or none (no IAM on this edition). Typed facade over a {@link SpiSlot}. */
final class PrincipalDirectories {
    private PrincipalDirectories() {}

    private static final SpiSlot<PrincipalDirectory> SLOT = new SpiSlot<>(PrincipalDirectory.class);

    static Optional<PrincipalDirectory> active() {
        return SLOT.active();
    }

    /**
     * Refuse a user id the IAM does not know. No directory registered ⇒ returns (nothing to ask); a directory that
     * says no, or cannot answer, ⇒ {@link IllegalArgumentException} (a 422 — fail closed).
     */
    static void requireKnown(String userId, String what) {
        Optional<PrincipalDirectory> d = active();
        if (d.isEmpty() || userId == null) return;
        boolean known;
        try {
            known = d.get().exists(userId);
        } catch (Exception e) {
            throw new IllegalArgumentException(what + " '" + userId + "' could not be verified against the IAM ("
                    + e.getMessage() + ") — refusing rather than guessing");
        }
        if (!known) throw new IllegalArgumentException(what + " '" + userId + "' is not a known user in the IAM");
    }

    /** Test seam; {@code null} restores discovery. */
    static void forTest(PrincipalDirectory d) {
        SLOT.forTest(d);
    }
}
