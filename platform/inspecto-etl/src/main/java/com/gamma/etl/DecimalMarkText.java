package com.gamma.etl;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Numbers written as text with a <b>decimal mark</b>: {@code .} ({@code 1,234.50}) or {@code ,} ({@code 1.234,50}); digit
 * groups may be split by the other mark or a space. One reading for every caller: the Grammar's
 * {@code decimal_separator} key at ingest ({@link SchemaFieldTypes#castSql}), the sample profiler's type vote
 * ({@code com.gamma.parse.SampleProfiler}) and Add data's decimal-mark choice ({@code ONBOARDING-FIRST-RUN-1} C1) —
 * {@code BASIC-ELT-PROFILE-1} U3.
 */
public final class DecimalMarkText {

    private DecimalMarkText() {}

    /** The marks the Grammar's {@code decimal_separator} accepts. */
    public static final List<String> MARKS = List.of(".", ",");

    private static final Pattern POINT = Pattern.compile("[+-]?(\\d+|\\d{1,3}([, ]\\d{3})+)(\\.\\d+)?");
    private static final Pattern COMMA = Pattern.compile("[+-]?(\\d+|\\d{1,3}([. ]\\d{3})+)(,\\d+)?");

    /**
     * Whether {@code value} is a number written with {@code mark}. A whole part with a leading zero ({@code 000 345 678},
     * {@code 012.000.789}) is an identifier, not a number: reading it as one would drop the zeros.
     */
    public static boolean reads(String value, String mark) {
        String t = value.trim();
        if (!(",".equals(mark) ? COMMA : POINT).matcher(t).matches()) return false;
        String whole = t.replaceFirst("^[+-]", "");
        int at = whole.indexOf(mark.charAt(0));
        if (at >= 0) whole = whole.substring(0, at);
        whole = whole.replaceAll("[^0-9]", "");
        return whole.length() <= 1 || whole.charAt(0) != '0';
    }

    /**
     * {@code DOUBLE} when a column's numbers need {@code mark} to be read, else {@code null}: not every value is such a
     * number, or a plain cast already reads them. Always DOUBLE, never BIGINT: a later file's {@code 7.500,25} must keep
     * its fraction even when the sample had none.
     */
    public static String numberType(List<String> values, String mark) {
        if (values.isEmpty()) return null;
        for (String v : values) if (!reads(v, mark)) return null;
        for (String v : values) {
            String t = v.trim();
            if (",".equals(mark) ? t.indexOf('.') >= 0 || t.indexOf(',') >= 0 || t.indexOf(' ') >= 0
                    : t.indexOf(',') >= 0 || t.indexOf(' ') >= 0) return "DOUBLE";
        }
        return null;
    }

    /**
     * The SQL reading {@code column} (an expression of text) as {@code type} with {@code mark}: spaces and the grouping
     * mark removed, a {@code ,} mark turned into {@code .}, then a forgiving cast (an unreadable value is NULL).
     */
    public static String sql(String column, String type, String mark) {
        String s = "REPLACE(TRIM(" + column + "), ' ', '')";
        s = ",".equals(mark) ? "REPLACE(REPLACE(" + s + ", '.', ''), ',', '.')" : "REPLACE(" + s + ", ',', '')";
        return "TRY_CAST(" + s + " AS " + type + ")";
    }
}
