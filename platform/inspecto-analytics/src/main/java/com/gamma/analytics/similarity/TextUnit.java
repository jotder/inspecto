package com.gamma.analytics.similarity;

import java.nio.charset.StandardCharsets;

/**
 * What one "character" is when a string metric compares two texts. The metrics are defined over a sequence of units;
 * the unit decides what a sequence is.
 *
 * <ul>
 *   <li>{@link #CODE_POINT} — one Unicode code point (the default): {@code é} is one unit, an emoji is one unit.</li>
 *   <li>{@link #UTF16_CHAR} — one Java {@code char}: a code point above U+FFFF is two units (a surrogate pair). The
 *       same as {@code CODE_POINT} for every text without such a character. Screening's name method uses it.</li>
 *   <li>{@link #UTF8_BYTE} — one byte of the UTF-8 encoding: {@code é} is two units. DuckDB 1.5.6's
 *       {@code jaro_similarity}, {@code jaro_winkler_similarity} and {@code levenshtein} work this way, so this unit
 *       reproduces them on any text, accented or not.</li>
 * </ul>
 */
public enum TextUnit {
    CODE_POINT,
    UTF16_CHAR,
    UTF8_BYTE;

    /** The units of {@code s}, in order, each as a non-negative int. */
    int[] of(String s) {
        return switch (this) {
            case CODE_POINT -> s.codePoints().toArray();
            case UTF16_CHAR -> s.chars().toArray();
            case UTF8_BYTE -> {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                int[] out = new int[b.length];
                for (int i = 0; i < b.length; i++) out[i] = b[i] & 0xFF;
                yield out;
            }
        };
    }
}
