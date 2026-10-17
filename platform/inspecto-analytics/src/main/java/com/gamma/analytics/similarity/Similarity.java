package com.gamma.analytics.similarity;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The similarity kit (ANALYTICS-CORE-1 S1): one definition each of Jaro, Jaro-Winkler ({@link JaroWinkler}),
 * Levenshtein distance and its normalised similarity, set and token-set Jaccard, and the text normalisation the
 * Step offers. Pure, deterministic, no locale, no dependency. {@link SimilaritySql} is the DuckDB form.
 *
 * <p><b>Every score is a candidate, never an identity.</b> A high score proposes that two texts may name the same thing;
 * it never decides that they do (decision D-M1, plan Won't W6).
 *
 * <p><b>One empty rule.</b> Every similarity in [0, 1] here scores <b>0 when either side is empty</b> — even when both
 * are — so a value with no content never looks like a match. A distance ({@link #levenshtein}) is a plain count.
 */
public final class Similarity {

    private Similarity() {
    }

    /** Jaro similarity under {@link JaroWinkler#STANDARD} (code points, textbook transpositions), 0..1. */
    public static double jaro(String a, String b) {
        return JaroWinkler.STANDARD.jaro(a, b);
    }

    /** Jaro-Winkler similarity under {@link JaroWinkler#STANDARD} (p = 0.1, prefix up to 4, boost above 0.7), 0..1. */
    public static double jaroWinkler(String a, String b) {
        return JaroWinkler.STANDARD.similarity(a, b);
    }

    /** {@link #jaroWinkler(String, String)} under the given instance's unit, transpositions, scale, prefix and threshold. */
    public static double jaroWinkler(String a, String b, JaroWinkler method) {
        return method.similarity(a, b);
    }

    /** Levenshtein distance over code points: the fewest single-unit insertions, deletions and substitutions. */
    public static int levenshtein(String a, String b) {
        return levenshtein(a, b, TextUnit.CODE_POINT);
    }

    /** Levenshtein distance over the given unit; {@link TextUnit#UTF8_BYTE} = DuckDB's {@code levenshtein}. */
    public static int levenshtein(String a, String b, TextUnit unit) {
        return distance(unit.of(a), unit.of(b));
    }

    /**
     * Normalised Levenshtein similarity over code points: {@code 1 − d / max(|a|, |b|)}, 0..1; 0 when either side is
     * empty.
     */
    public static double levenshteinSimilarity(String a, String b) {
        return levenshteinSimilarity(a, b, TextUnit.CODE_POINT);
    }

    /** {@link #levenshteinSimilarity(String, String)} over the given unit (lengths counted in that unit). */
    public static double levenshteinSimilarity(String a, String b, TextUnit unit) {
        int[] s = unit.of(a), t = unit.of(b);
        if (s.length == 0 || t.length == 0) return 0.0;
        return 1.0 - (double) distance(s, t) / Math.max(s.length, t.length);
    }

    private static int distance(int[] s, int[] t) {
        int[] prev = new int[t.length + 1], cur = new int[t.length + 1];
        for (int j = 0; j <= t.length; j++) prev[j] = j;
        for (int i = 1; i <= s.length; i++) {
            cur[0] = i;
            for (int j = 1; j <= t.length; j++) {
                int sub = prev[j - 1] + (s[i - 1] == t[j - 1] ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[t.length];
    }

    /**
     * Jaccard similarity of two sets, {@code |A ∩ B| / |A ∪ B|}, 0..1; 0 when either set is empty. Elements are
     * compared with {@code equals}.
     */
    public static double jaccard(Set<?> a, Set<?> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int intersection = 0;
        for (Object t : a) if (b.contains(t)) intersection++;
        int union = a.size() + b.size() - intersection;
        return (double) intersection / union;
    }

    /** ASCII whitespace: space, tab, line feed, vertical tab, form feed, carriage return. Nothing else splits. */
    static final String WHITESPACE_CLASS = "[ \\t\\n\\x0B\\f\\r]";
    private static final Pattern WHITESPACE = Pattern.compile(WHITESPACE_CLASS + "+");

    /**
     * The tokens of {@code text}: its runs of non-whitespace ({@link #WHITESPACE_CLASS}), each once, in first-seen order.
     * No case folding — normalise first ({@link #normalise}) to compare without case or accents.
     */
    public static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : WHITESPACE.split(text)) if (!t.isEmpty()) out.add(t);
        return out;
    }

    /** Token-set Jaccard: {@link #jaccard} of the two texts' {@link #tokens}; 0 when either has no token. */
    public static double tokenJaccard(String a, String b) {
        return jaccard(tokens(a), tokens(b));
    }

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    /**
     * The normalised text, in this order:
     * <ol>
     *   <li><b>accents stripped</b> — canonical decomposition (NFD), every combining mark (general category M) removed,
     *       then canonical composition (NFC). Only CANONICAL decompositions: {@code é → e}, {@code Å → A}, but
     *       {@code ß}, {@code ø}, {@code ł}, {@code æ} and compatibility forms ({@code ﬁ}, full-width letters, no-break
     *       space) are kept as they are;</li>
     *   <li><b>case folded</b> — each code point mapped by {@link Character#toLowerCase(int)} (the simple, context-free
     *       mapping: {@code Σ → σ} everywhere, never the final {@code ς});</li>
     *   <li><b>whitespace collapsed</b> — every run of ASCII whitespace ({@link #WHITESPACE_CLASS}) becomes one space,
     *       and leading and trailing spaces are dropped.</li>
     * </ol>
     * The DuckDB form is {@link SimilaritySql#normalise}; the two agree except on the code points DuckDB's Unicode
     * tables predate (see the parity test).
     */
    public static String normalise(String text) {
        Objects.requireNonNull(text, "text");
        String stripped = Normalizer.normalize(
                MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll(""), Normalizer.Form.NFC);
        StringBuilder lower = new StringBuilder(stripped.length());
        stripped.codePoints().forEach(c -> lower.appendCodePoint(Character.toLowerCase(c)));
        String collapsed = WHITESPACE.matcher(lower).replaceAll(" ");
        int from = 0, to = collapsed.length();
        while (from < to && collapsed.charAt(from) == ' ') from++;
        while (to > from && collapsed.charAt(to - 1) == ' ') to--;
        return collapsed.substring(from, to);
    }
}
