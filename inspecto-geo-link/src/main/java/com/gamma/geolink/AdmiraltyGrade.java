package com.gamma.geolink;

import com.gamma.control.ApiException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The <b>Admiralty grade</b> of an Investigation annotation (LA-19, decision D-U9 — operator 2026-09-24): the
 * standard NATO / Admiralty two-axis grading, carried on the {@code annotate} op as {@code confidence}.
 *
 * <ul>
 *   <li><b>Source reliability</b>, a letter: A completely reliable · B usually reliable · C fairly reliable ·
 *       D not usually reliable · E unreliable · F reliability cannot be judged.</li>
 *   <li><b>Information credibility</b>, a digit: 1 confirmed by other sources · 2 probably true · 3 possibly true ·
 *       4 doubtful · 5 improbable · 6 truth cannot be judged.</li>
 * </ul>
 *
 * <p>So the scale is A–F × 1–6 (six by six — the "5×5" shorthand omits the two cannot-be-judged grades, which the
 * standard includes). Written as one token, letter then digit ({@code "B2"}); lower case is accepted and stored upper
 * case. ⛔ Not a 0–1 float: an analyst's judgement graded to a false decimal precision is the failure D-U9 names.
 */
final class AdmiraltyGrade {

    private AdmiraltyGrade() {}

    private static final Pattern GRADE = Pattern.compile("[A-F][1-6]");
    private static final List<String> RELIABILITY = List.of("completely reliable", "usually reliable",
            "fairly reliable", "not usually reliable", "unreliable", "reliability cannot be judged");
    private static final List<String> CREDIBILITY = List.of("confirmed by other sources", "probably true",
            "possibly true", "doubtful", "improbable", "truth cannot be judged");

    /** The validated, normalised grade; 422 for anything that is not A–F followed by 1–6. */
    static String validate(Object raw) {
        String v = raw instanceof String s ? s.trim().toUpperCase(Locale.ROOT) : null;
        if (v == null || !GRADE.matcher(v).matches())
            throw new ApiException(422, "'confidence' must be an Admiralty grade — source reliability A-F then "
                    + "information credibility 1-6, e.g. \"B2\" — got " + (raw instanceof String ? "'" + raw + "'" : raw));
        return v;
    }

    /** {@code "B2 (source usually reliable, information probably true)"}. */
    static String describe(String grade) {
        return grade + " (source " + RELIABILITY.get(grade.charAt(0) - 'A') + ", information "
                + CREDIBILITY.get(grade.charAt(1) - '1') + ")";
    }
}
