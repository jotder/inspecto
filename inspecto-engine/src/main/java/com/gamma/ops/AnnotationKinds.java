package com.gamma.ops.note;

import com.gamma.pipeline.ComponentStore;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The note-target kind vocabulary (BACKLOG D10): what an {@link ObjectNote} may be attached to.
 *
 * <p>Deliberately <b>not</b> a new enum. The set is {@code "object"} (an
 * {@link com.gamma.ops.OperationalObject}) plus every registry component type in
 * {@link ComponentStore#WRITABLE_TYPES} — the same strings the Exchange kind axis and
 * {@code BundleRoutes.OWN_STORE_KINDS} already use, so widening the component registry widens note
 * targets for free and the axes cannot drift apart.
 *
 * <p>Unknown kinds are rejected at the seam ({@link #require(String)}): a note must never become an
 * orphan pointing at nothing.
 *
 * @since 4.9.0
 */
@com.gamma.api.PublicApi(since = "4.9.0")
public final class NoteTargets {

    private NoteTargets() {}

    /** The operational-object target kind — the pre-D10 default and the value backfilled onto old rows. */
    public static final String OBJECT = "object";

    /** Every valid {@code targetKind}: {@link #OBJECT} + the writable component types. */
    public static final Set<String> KINDS = kinds();

    private static Set<String> kinds() {
        Set<String> all = new LinkedHashSet<>();
        all.add(OBJECT);
        all.addAll(ComponentStore.WRITABLE_TYPES);
        return Set.copyOf(all);
    }

    /** Whether {@code targetKind} names a supported target family (case-insensitive). */
    public static boolean isKnown(String targetKind) {
        return targetKind != null && KINDS.contains(targetKind.trim().toLowerCase(Locale.ROOT));
    }

    /** The normalised kind, or {@link IllegalArgumentException} — fail closed on an unknown target family. */
    public static String require(String targetKind) {
        if (!isKnown(targetKind))
            throw new IllegalArgumentException("unknown note target kind '" + targetKind + "'");
        return targetKind.trim().toLowerCase(Locale.ROOT);
    }
}
