package com.gamma.analytics.score;

/**
 * The ceiling on entities one score run may score (D-AD10, operator 2026-10-06): past it the run FAILS rather than
 * scoring a silent subset. Configurable, never disabled: a system property per kind sets the default (malformed or
 * non-positive keeps {@link #DEFAULT}), and a model's own {@code maxEntities} (1..{@link #CEILING}) overrides it. The
 * refusal's wording is the kind's; the numbers are these.
 */
public final class EntityCap {
    private EntityCap() {}

    /** The system default when no property is set. */
    public static final int DEFAULT = 200_000;
    /** The highest {@code maxEntities} a model may author: 10x the system default. */
    public static final int CEILING = 2_000_000;

    /** The system default from a property value: a positive whole number, else {@link #DEFAULT}. */
    public static int systemDefault(String raw) {
        if (raw == null) return DEFAULT;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : DEFAULT;
        } catch (NumberFormatException e) {
            return DEFAULT;
        }
    }

    /** The cap a run uses: the model's own when set, else the system default read from {@code property}. */
    public static int resolve(Integer modelCap, String property) {
        return modelCap != null ? modelCap : systemDefault(System.getProperty(property));
    }
}
