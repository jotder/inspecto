package com.gamma.screening;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Screening name method (SCR-D3, {@code docs/superpower/screening-addon-plan.md}): fold a name, then score two
 * folded names 0..1. Pure and deterministic — no I/O, no locale, no DuckDB.
 *
 * <p><b>Fold.</b> NFKD, combining marks stripped, Cyrillic / Greek look-alike letters mapped to their Latin twin,
 * {@code ß æ œ ø đ ł þ} expanded, a digit inside a token that also holds letters read as its look-alike letter
 * ({@code 0→o 1→i 3→e 4→a 5→s 7→t}), lower-cased, anything else not a letter or digit → space, honorifics
 * dropped. A token of digits only stays digits ("Louis 14" is not "Louis ia").
 *
 * <p><b>Score.</b> {@code max(tokenSet(a, b), joined(a, b))}. {@code tokenSet}: each token of the name with fewer
 * tokens takes its best Jaro-Winkler partner among the other's (each partner used once, greedily from the best
 * pair; order-free, so "Putin, Vladimir" is "Vladimir Putin"); a pair below {@link #TOKEN_FLOOR} counts as 0 (so
 * "John Smith" is not "Jane Smith"); the result is weighted by each pair's mean token length (symmetric) and multiplied by the coverage factor
 * {@code 0.8 + 0.2·min(lenA, lenB)/max(lenA, lenB)} (total token lengths). It applies when both names have the same number of tokens or the
 * shorter has two or more, so one common token never matches a long name. {@code joined}: Jaro-Winkler of the
 * tokens written together, only when the token counts differ ("Abdul Rahman" is "Abdulrahman").
 */
public final class NameMatcher {

    private NameMatcher() {}

    /** A token pair less similar than this contributes nothing to {@code tokenSet}. */
    static final double TOKEN_FLOOR = 0.8;

    /** Honorifics and titles that carry no identity. */
    static final Set<String> HONORIFICS = Set.of("mr", "mrs", "ms", "miss", "mx", "dr", "prof", "sir", "dame",
            "madam", "mme", "mlle", "herr", "frau", "sr", "sra", "srta", "sheikh", "shaikh", "haji", "hajji");

    /** Look-alike letters (Cyrillic, Greek) → the Latin letter they are mistaken for, lower-case. */
    private static final Map<Character, Character> CONFUSABLES = Map.ofEntries(
            // Cyrillic
            Map.entry('а', 'a'), Map.entry('в', 'b'), Map.entry('е', 'e'), Map.entry('ё', 'e'), Map.entry('к', 'k'),
            Map.entry('м', 'm'), Map.entry('н', 'h'), Map.entry('о', 'o'), Map.entry('р', 'p'), Map.entry('с', 'c'),
            Map.entry('т', 't'), Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('і', 'i'), Map.entry('ї', 'i'),
            Map.entry('ј', 'j'), Map.entry('ѕ', 's'), Map.entry('ԁ', 'd'), Map.entry('ԛ', 'q'), Map.entry('ԝ', 'w'),
            // Greek
            Map.entry('α', 'a'), Map.entry('β', 'b'), Map.entry('ε', 'e'), Map.entry('η', 'n'), Map.entry('ι', 'i'),
            Map.entry('κ', 'k'), Map.entry('μ', 'm'), Map.entry('ν', 'v'), Map.entry('ο', 'o'), Map.entry('ρ', 'p'),
            Map.entry('τ', 't'), Map.entry('υ', 'u'), Map.entry('χ', 'x'), Map.entry('ζ', 'z'), Map.entry('γ', 'y'));

    private static final Map<Character, String> EXPANSIONS = Map.of(
            'ß', "ss", 'æ', "ae", 'œ', "oe", 'ø', "o", 'đ', "d", 'ł', "l", 'þ', "th", 'ı', "i", 'ð', "d");

    private static final Map<Character, Character> DIGIT_LOOKALIKES = Map.of(
            '0', 'o', '1', 'i', '3', 'e', '4', 'a', '5', 's', '7', 't');

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    /** The folded tokens of {@code name} (possibly empty). */
    public static List<String> tokens(String name) {
        if (name == null) return List.of();
        // lower-case FIRST: "İ".toLowerCase() is "i" + a combining dot, which the mark strip then removes
        String s = MARKS.matcher(Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)).replaceAll("");
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character conf = CONFUSABLES.get(c);
            if (conf != null) { b.append(conf.charValue()); continue; }
            String exp = EXPANSIONS.get(c);
            if (exp != null) { b.append(exp); continue; }
            b.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        List<String> out = new ArrayList<>();
        for (String t : b.toString().trim().split(" +")) {
            if (t.isEmpty()) continue;
            String tok = digitsAsLetters(t);
            if (!HONORIFICS.contains(tok)) out.add(tok);
        }
        return out;
    }

    /** The folded name: its tokens joined by one space. */
    public static String fold(String name) {
        return String.join(" ", tokens(name));
    }

    /** A token mixing letters and digits reads each look-alike digit as its letter; an all-digit token is kept. */
    private static String digitsAsLetters(String t) {
        boolean letter = false, digit = false;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isDigit(t.charAt(i))) digit = true;
            else letter = true;
        }
        if (!(letter && digit)) return t;
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            Character l = DIGIT_LOOKALIKES.get(t.charAt(i));
            b.append(l != null ? l.charValue() : t.charAt(i));
        }
        return b.toString();
    }

    /** The Match Score of two names, 0..1 rounded to 4 decimals; 0 when either folds to nothing. */
    public static double score(String a, String b) {
        return score(tokens(a), tokens(b));
    }

    /** {@link #score(String, String)} over already-folded tokens. */
    public static double score(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        double set = a.size() == b.size() || Math.min(a.size(), b.size()) >= 2 ? tokenSet(a, b) : 0.0;
        double joined = a.size() != b.size() ? jaroWinkler(String.join("", a), String.join("", b)) : 0.0;
        return Math.round(Math.max(set, joined) * 10_000.0) / 10_000.0;
    }

    static double tokenSet(List<String> a, List<String> b) {
        List<String> shorter = a.size() <= b.size() ? a : b;
        List<String> longer = shorter == a ? b : a;
        int n = shorter.size(), m = longer.size();
        double[][] sim = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) sim[i][j] = jaroWinkler(shorter.get(i), longer.get(j));
        boolean[] usedI = new boolean[n], usedJ = new boolean[m];
        double weighted = 0, weight = 0;   // a pair weighs the mean of its two lengths: symmetric in a and b
        for (int k = 0; k < n; k++) {   // greedy: take the best remaining pair each round (ties: lowest i, then j)
            int bi = -1, bj = -1;
            double bs = -1;
            for (int i = 0; i < n; i++) {
                if (usedI[i]) continue;
                for (int j = 0; j < m; j++)
                    if (!usedJ[j] && sim[i][j] > bs) { bs = sim[i][j]; bi = i; bj = j; }
            }
            usedI[bi] = true;
            usedJ[bj] = true;
            double w = (shorter.get(bi).length() + longer.get(bj).length()) / 2.0;
            weight += w;
            if (bs >= TOKEN_FLOOR) weighted += bs * w;
        }
        int lenShort = 0, lenLong = 0;
        for (String t : shorter) lenShort += t.length();
        for (String t : longer) lenLong += t.length();
        double coverage = 0.8 + 0.2 * Math.min(lenShort, lenLong) / Math.max(lenShort, lenLong);
        return (weighted / weight) * coverage;
    }

    /** Jaro-Winkler similarity (prefix scale 0.1, prefix up to 4), 0..1. */
    static double jaroWinkler(String s, String t) {
        if (s.equals(t)) return s.isEmpty() ? 0.0 : 1.0;
        int ls = s.length(), lt = t.length();
        if (ls == 0 || lt == 0) return 0.0;
        int window = Math.max(0, Math.max(ls, lt) / 2 - 1);
        boolean[] ms = new boolean[ls], mt = new boolean[lt];
        int matches = 0;
        for (int i = 0; i < ls; i++) {
            int lo = Math.max(0, i - window), hi = Math.min(lt - 1, i + window);
            for (int j = lo; j <= hi; j++) {
                if (mt[j] || s.charAt(i) != t.charAt(j)) continue;
                ms[i] = mt[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;
        int transpositions = 0;
        for (int i = 0, j = 0; i < ls; i++) {
            if (!ms[i]) continue;
            while (!mt[j]) j++;
            if (s.charAt(i) != t.charAt(j)) transpositions++;
            j++;
        }
        double m = matches;
        double jaro = (m / ls + m / lt + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(ls, lt)) && s.charAt(i) == t.charAt(i); i++) prefix++;
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }
}
