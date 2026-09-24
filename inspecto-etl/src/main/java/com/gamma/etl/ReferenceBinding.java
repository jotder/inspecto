package com.gamma.etl;

/**
 * The one test for "does this {@code transform.join} {@code reference} value bind a Reference BY NAME?".
 * Both spellings do: {@code reference/<pipeline>} (the flat/graph form) and {@code references/<pipeline>}
 * (the recipe form, which {@code RecipeCompiler} normalises to the singular). Anything else is a data path.
 *
 * <p>⚠ Every reader of a join reference must ask HERE: the parser once accepted both prefixes while
 * {@code ReferenceReader.parse} accepted only the singular, so a hand-written flat config saying
 * {@code references/x} survived load as a name and was then opened as a FILE at run time.
 */
public final class ReferenceBinding {

    /** The canonical by-name prefix — what a compiled recipe and the graph carry. */
    public static final String PREFIX = "reference/";
    /** The recipe's plural spelling, accepted everywhere as a synonym of {@link #PREFIX}. */
    public static final String PLURAL_PREFIX = "references/";

    private ReferenceBinding() {}

    /**
     * The bound pipeline name (trimmed, possibly empty when nothing follows the prefix), or {@code null}
     * when {@code reference} is not a by-name binding.
     */
    public static String name(String reference) {
        if (reference == null) return null;
        String s = reference.trim();
        if (s.startsWith(PREFIX)) return s.substring(PREFIX.length()).trim();
        if (s.startsWith(PLURAL_PREFIX)) return s.substring(PLURAL_PREFIX.length()).trim();
        return null;
    }

    /** Whether {@code reference} binds by name (either spelling). */
    public static boolean isByName(String reference) {
        return name(reference) != null;
    }
}
