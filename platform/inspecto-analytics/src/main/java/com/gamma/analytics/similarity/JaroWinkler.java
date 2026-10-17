package com.gamma.analytics.similarity;

import java.util.Arrays;
import java.util.Objects;

/**
 * The one Jaro and Jaro-Winkler definition (ANALYTICS-CORE-1 S1), with its knobs stated as data so every caller's
 * exact behaviour is a named instance rather than a private copy.
 *
 * <p><b>Jaro.</b> Over the two unit sequences {@code s} (length |s|) and {@code t}: two units match when they are equal
 * and no further apart than {@code w = max(0, max(|s|, |t|) / 2 − 1)} (integer division); each unit of {@code t} is
 * matched at most once, scanning {@code s} left to right and taking the first free match in the window. With {@code m}
 * matches and {@code x} the number of positions where the matched units, read in order, differ:
 * {@code jaro = (m/|s| + m/|t| + (m − h)/m) / 3}, where {@code h = x / 2} — real ({@link Transpositions#HALF}, the
 * textbook) or rounded down ({@link Transpositions#HALF_FLOORED}, DuckDB's). {@code m = 0} gives 0. Equal non-empty
 * sequences give 1. <b>An empty side gives 0</b>, even when both are empty: there is nothing to match on.
 *
 * <p><b>Jaro-Winkler.</b> {@code jw = jaro + ℓ · p · (1 − jaro)} with {@code ℓ} the length of the common prefix, at most
 * {@link #maxPrefix}, and {@code p} = {@link #prefixScale}; the prefix boost is applied only when
 * {@code jaro > boostThreshold} (Winkler's 0.7; 0 = always). Result in [0, 1] when {@code ℓ · p ≤ 1}.
 *
 * @param unit           what one character is ({@link TextUnit})
 * @param transpositions how the half-transposition count is taken
 * @param prefixScale    {@code p}, 0 ≤ p ≤ 0.25 (so ℓ · p ≤ 1 with the usual prefix of 4)
 * @param maxPrefix      the longest prefix that counts, 0 ≤ ℓ ≤ 4
 * @param boostThreshold the prefix boost applies only above this Jaro score, in [0, 1]
 */
public record JaroWinkler(TextUnit unit, Transpositions transpositions, double prefixScale, int maxPrefix,
                          double boostThreshold) {

    /** How the half-transposition count {@code h} is taken from the {@code x} differing matched positions. */
    public enum Transpositions {
        /** {@code h = x / 2.0} — Jaro's definition. */
        HALF,
        /** {@code h = ⌊x / 2⌋} — DuckDB's (rapidfuzz) arithmetic. */
        HALF_FLOORED
    }

    /** The default: code points, textbook Jaro, Winkler's p = 0.1, prefix up to 4, boost above 0.7. */
    public static final JaroWinkler STANDARD = new JaroWinkler(TextUnit.CODE_POINT, Transpositions.HALF, 0.1, 4, 0.7);

    /**
     * DuckDB 1.5.6's {@code jaro_similarity} / {@code jaro_winkler_similarity}: UTF-8 bytes, half-transpositions
     * rounded down, p = 0.1, prefix up to 4, boost above 0.7. {@code SimilarityDuckDbParityTest} holds it equal to
     * the built-ins.
     */
    public static final JaroWinkler DUCKDB = new JaroWinkler(TextUnit.UTF8_BYTE, Transpositions.HALF_FLOORED, 0.1, 4, 0.7);

    public JaroWinkler {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(transpositions, "transpositions");
        if (!(prefixScale >= 0 && prefixScale <= 0.25)) throw new IllegalArgumentException("prefixScale must be in [0, 0.25]: " + prefixScale);
        if (maxPrefix < 0 || maxPrefix > 4) throw new IllegalArgumentException("maxPrefix must be in [0, 4]: " + maxPrefix);
        if (!(boostThreshold >= 0 && boostThreshold <= 1)) throw new IllegalArgumentException("boostThreshold must be in [0, 1]: " + boostThreshold);
    }

    /** The Jaro similarity of {@code a} and {@code b} under this instance's unit and transposition rule, 0..1. */
    public double jaro(String a, String b) {
        return jaro(unit.of(a), unit.of(b));
    }

    /** The Jaro-Winkler similarity of {@code a} and {@code b}, 0..1. */
    public double similarity(String a, String b) {
        int[] s = unit.of(a), t = unit.of(b);
        double jaro = jaro(s, t);
        if (!(jaro > boostThreshold)) return jaro;
        int prefix = 0;
        for (int i = 0; i < Math.min(maxPrefix, Math.min(s.length, t.length)) && s[i] == t[i]; i++) prefix++;
        return jaro + prefix * prefixScale * (1.0 - jaro);
    }

    private double jaro(int[] s, int[] t) {
        int ls = s.length, lt = t.length;
        if (ls == 0 || lt == 0) return 0.0;
        if (Arrays.equals(s, t)) return 1.0;
        int window = Math.max(0, Math.max(ls, lt) / 2 - 1);
        boolean[] ms = new boolean[ls], mt = new boolean[lt];
        int matches = 0;
        for (int i = 0; i < ls; i++) {
            int lo = Math.max(0, i - window), hi = Math.min(lt - 1, i + window);
            for (int j = lo; j <= hi; j++) {
                if (mt[j] || s[i] != t[j]) continue;
                ms[i] = mt[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;
        int differing = 0;
        for (int i = 0, j = 0; i < ls; i++) {
            if (!ms[i]) continue;
            while (!mt[j]) j++;
            if (s[i] != t[j]) differing++;
            j++;
        }
        double m = matches;
        double half = transpositions == Transpositions.HALF ? differing / 2.0 : differing / 2;
        return (m / ls + m / lt + (m - half) / m) / 3.0;
    }
}
