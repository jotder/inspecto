package com.gamma.screening;

import com.gamma.analytics.similarity.JaroWinkler;
import com.gamma.analytics.similarity.JaroWinkler.Transpositions;
import com.gamma.analytics.similarity.TextUnit;
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
 *
 * <p><b>Phonetic reading (SCREENING-1).</b> When the plain score is below {@link #PHONETIC_CAP}, the same score is
 * taken over {@link #phonetic} transliteration skeletons and capped at {@link #PHONETIC_CAP}; the larger wins.
 * {@link #blockKeys} gives Screener's blocking the skeleton initial as well, so Gaddafi / Qadhafi are compared.
 */
public final class NameMatcher {

    private NameMatcher() {}

    /**
     * The token similarity: the Analytics Kernel's Jaro-Winkler (ANALYTICS-CORE-1 S1) over UTF-16 chars, textbook
     * transpositions, prefix scale 0.1, prefix up to 4, the prefix boost always applied (threshold 0) — exactly this
     * method's own arithmetic before it moved, pinned by {@code NameMatcherGoldenTest}. The folding, honorifics,
     * transliteration and blocking stay here.
     */
    static final JaroWinkler TOKEN_JARO_WINKLER = new JaroWinkler(TextUnit.UTF16_CHAR, Transpositions.HALF, 0.1, 4, 0.0);

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
        for (String run : s.split("[^\\p{L}\\p{N}]+")) {
            if (run.isEmpty()) continue;
            b.append(' ');
            if (isGenuineCyrillic(run)) { b.append(transliterate(run)); continue; }
            for (int i = 0; i < run.length(); i++) {
                char c = run.charAt(i);
                Character conf = CONFUSABLES.get(c);
                if (conf != null) { b.append(conf.charValue()); continue; }
                String exp = EXPANSIONS.get(c);
                if (exp != null) { b.append(exp); continue; }
                b.append(c);
            }
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

    /** Cyrillic → Latin, a plain reading of the sound (Виктор → viktor). Letters the map lacks pass through. */
    private static final Map<Character, String> CYRILLIC = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"), Map.entry('д', "d"),
            Map.entry('е', "e"), Map.entry('ж', "zh"), Map.entry('з', "z"), Map.entry('и', "i"), Map.entry('к', "k"),
            Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"), Map.entry('п', "p"),
            Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"), Map.entry('у', "u"), Map.entry('ф', "f"),
            Map.entry('х', "kh"), Map.entry('ц', "ts"), Map.entry('ч', "ch"), Map.entry('ш', "sh"), Map.entry('щ', "sh"),
            Map.entry('ъ', ""), Map.entry('ы', "y"), Map.entry('ь', ""), Map.entry('э', "e"), Map.entry('ю', "yu"),
            Map.entry('я', "ya"), Map.entry('і', "i"), Map.entry('ї', "i"), Map.entry('є', "ye"), Map.entry('ґ', "g"));

    /**
     * A token of Cyrillic letters only, at least one of them with no Latin look-alike (и д л п б г ж з ц ч ш ф ...): a
     * real Cyrillic name, read by sound. A token that is all look-alikes, or mixes in a Latin letter, keeps the
     * look-alike fold, so "Pаvel" (Cyrillic а) still reads as Pavel.
     */
    private static boolean isGenuineCyrillic(String run) {
        boolean unique = false;
        for (int i = 0; i < run.length(); i++) {
            char c = run.charAt(i);
            if (c < 'Ѐ' || c > 'ӿ') return false;
            if (!CONFUSABLES.containsKey(c)) unique = true;
        }
        return unique;
    }

    private static String transliterate(String run) {
        StringBuilder b = new StringBuilder(run.length() + 4);
        for (int i = 0; i < run.length(); i++) {
            String t = CYRILLIC.get(run.charAt(i));
            b.append(t != null ? t : String.valueOf(run.charAt(i)));
        }
        return b.toString();
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

    /** The phonetic score is capped here: a transliteration-equal pair clears the default threshold, never reads 1.0. */
    static final double PHONETIC_CAP = 0.95;

    /** A token this long (after digraph reduction) may start with a q / g read as k: Gaddafi yes, Gamal no. */
    private static final int KAF_MIN_LENGTH = 6;

    /**
     * The transliteration skeleton of one folded token (Arabic-name variants): {@code dh gh kh} read as their first
     * letter, {@code ou oo o} as u (Muhammad / Mohamed), {@code ei ai ay ey} as i, doubled letters collapsed, and a long token's leading
     * q / g as k — so Gaddafi, Qadhafi, Kadhafi, Gadhafi, Khadafi all give "kadafi" and Hussein, Husain, Hussain
     * "husin". A token holding a digit is returned as is. Used only by {@link #score(List, List)}'s second reading
     * and by {@link #blockKeys(List)}.
     */
    static String phonetic(String t) {
        for (int i = 0; i < t.length(); i++) if (Character.isDigit(t.charAt(i))) return t;
        String s = t.replace("dh", "d").replace("gh", "g").replace("kh", "k").replace("ou", "u").replace("oo", "u")
                .replace("ei", "i").replace("ai", "i").replace("ay", "i").replace("ey", "i").replace('o', 'u');
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) if (i == 0 || s.charAt(i) != s.charAt(i - 1)) b.append(s.charAt(i));
        if (b.length() >= KAF_MIN_LENGTH && (b.charAt(0) == 'q' || b.charAt(0) == 'g')) b.setCharAt(0, 'k');
        return b.toString();
    }

    /**
     * The blocking keys of a name's tokens: the first letter of each token AND of its {@link #phonetic} skeleton, so
     * a pair that differ only by a transliteration (Gaddafi / Qadhafi) share a block. A superset of the plain initials.
     */
    public static java.util.Set<Character> blockKeys(List<String> tokens) {
        java.util.Set<Character> out = new java.util.LinkedHashSet<>();
        for (String t : tokens) {
            out.add(t.charAt(0));
            out.add(phonetic(t).charAt(0));
        }
        return out;
    }

    /** The Match Score of two names, 0..1 rounded to 4 decimals; 0 when either folds to nothing. */
    public static double score(String a, String b) {
        return score(tokens(a), tokens(b));
    }

    /** {@link #score(String, String)} over already-folded tokens. */
    public static double score(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        double set = a.size() == b.size() || Math.min(a.size(), b.size()) >= 2 ? tokenSet(a, b) : 0.0;
        double joined = a.size() != b.size() ? TOKEN_JARO_WINKLER.similarity(String.join("", a), String.join("", b)) : 0.0;
        double best = Math.max(set, joined);
        if (best < PHONETIC_CAP) {   // second reading: the same score over transliteration skeletons, capped
            List<String> pa = a.stream().map(NameMatcher::phonetic).toList(), pb = b.stream().map(NameMatcher::phonetic).toList();
            if (!pa.equals(a) || !pb.equals(b)) best = Math.max(best, Math.min(PHONETIC_CAP, rawScore(pa, pb)));
        }
        return Math.round(best * 10_000.0) / 10_000.0;
    }

    private static double rawScore(List<String> a, List<String> b) {
        double set = a.size() == b.size() || Math.min(a.size(), b.size()) >= 2 ? tokenSet(a, b) : 0.0;
        double joined = a.size() != b.size() ? TOKEN_JARO_WINKLER.similarity(String.join("", a), String.join("", b)) : 0.0;
        return Math.max(set, joined);
    }

    static double tokenSet(List<String> a, List<String> b) {
        List<String> shorter = a.size() <= b.size() ? a : b;
        List<String> longer = shorter == a ? b : a;
        int n = shorter.size(), m = longer.size();
        double[][] sim = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) sim[i][j] = TOKEN_JARO_WINKLER.similarity(shorter.get(i), longer.get(j));
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
}
