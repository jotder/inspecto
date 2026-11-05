package com.gamma.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The decimal mark of a file's numbers ({@code ONBOARDING-FIRST-RUN-1} C1): {@code .} ({@code 1,234.50}) or {@code ,}
 * ({@code 1.234,50}). Digit groups may be split by the other mark or a space. Proposed as the draft's
 * {@code format.decimalMark} choice; a column whose numbers need the mark to be read is typed as a number and carries
 * the mark, so the commit reads it with that mark rather than leaving it as text.
 *
 * <p>A column decides the mark when every sampled value reads with one mark and not the other ({@code 12.5} decides
 * {@code .}, {@code 12,5} and {@code 1.234,50} decide {@code ,}); {@code 1.234} or {@code 1,234} reads either way and
 * decides nothing. Confidence follows OF-2: high when the deciding columns agree and hold at least
 * {@link OnboardDraft#MIN_SAMPLE} values.
 */
final class DecimalMark {

    private DecimalMark() {}

    private static final Pattern POINT = Pattern.compile("[+-]?(\\d+|\\d{1,3}([, ]\\d{3})+)(\\.\\d+)?");
    private static final Pattern COMMA = Pattern.compile("[+-]?(\\d+|\\d{1,3}([. ]\\d{3})+)(,\\d+)?");

    /**
     * Whether {@code value} is a number written with {@code mark}. A whole part with a leading zero ({@code 000 345 678},
     * {@code 012.000.789}) is an identifier, not a number: reading it as one would drop the zeros.
     */
    static boolean reads(String value, String mark) {
        String t = value.trim();
        if (!(",".equals(mark) ? COMMA : POINT).matcher(t).matches()) return false;
        String whole = t.replaceFirst("^[+-]", "");
        int at = whole.indexOf(mark.charAt(0));
        if (at >= 0) whole = whole.substring(0, at);
        whole = whole.replaceAll("[^0-9]", "");
        return whole.length() <= 1 || whole.charAt(0) != '0';
    }

    private static boolean all(List<String> values, String mark) {
        if (values.isEmpty()) return false;
        for (String v : values) if (!reads(v, mark)) return false;
        return true;
    }

    private static boolean marked(List<String> values) {
        for (String v : values) if (v.indexOf('.') >= 0 || v.indexOf(',') >= 0) return true;
        return false;
    }

    /** The draft's {@code decimalMark} choice over the sampled text columns; {@code stated} is the user's own. */
    static Map<String, Object> choose(List<String> names, List<List<String>> valuesOf, String stated) {
        if (stated != null) return OnboardDraft.choice(stated, "you chose '" + stated + "'", true);
        List<Integer> point = new ArrayList<>(), comma = new ArrayList<>();
        int eitherWay = -1, pointValues = 0, commaValues = 0;
        for (int i = 0; i < names.size(); i++) {
            List<String> v = valuesOf.get(i);
            boolean p = all(v, "."), c = all(v, ",");
            if (p && !c) {
                point.add(i);
                pointValues += v.size();
            } else if (c && !p) {
                comma.add(i);
                commaValues += v.size();
            } else if (p && marked(v) && eitherWay < 0) eitherWay = i;
        }
        if (!point.isEmpty() && !comma.isEmpty())
            return OnboardDraft.choice(".", "'" + names.get(point.get(0)) + "' reads with '.' (e.g. '" + example(valuesOf.get(point.get(0)), ".")
                    + "') but '" + names.get(comma.get(0)) + "' with ',' (e.g. '" + example(valuesOf.get(comma.get(0)), ",")
                    + "'); '.' is kept", false);
        if (!comma.isEmpty())
            return OnboardDraft.choice(",", "every sampled value of " + list(names, comma) + " is a number written with ',' as the decimal mark (e.g. '"
                    + example(valuesOf.get(comma.get(0)), ",") + "')", commaValues >= OnboardDraft.MIN_SAMPLE);
        if (!point.isEmpty())
            return OnboardDraft.choice(".", "every sampled value of " + list(names, point) + " is a number written with '.' as the decimal mark (e.g. '"
                    + example(valuesOf.get(point.get(0)), ".") + "')", pointValues >= OnboardDraft.MIN_SAMPLE);
        if (eitherWay >= 0)
            return OnboardDraft.choice(".", "'" + names.get(eitherWay) + "' holds numbers like '" + example(valuesOf.get(eitherWay), ".")
                    + "', which read either way; '.' is assumed", false);
        return OnboardDraft.choice(".", "no sampled number has a decimal mark, so the mark changes nothing", true);
    }

    /**
     * {@code DOUBLE} when a column's numbers need {@code mark} to be read, else {@code null}: not every value is such a
     * number, or a plain cast already reads them. Always DOUBLE, never BIGINT: a later file's {@code 7.500,25} must keep
     * its fraction even when the sample had none.
     */
    static String numberType(List<String> values, String mark) {
        if (!all(values, mark)) return null;
        for (String v : values) {
            String t = v.trim();
            if (",".equals(mark) ? t.indexOf('.') >= 0 || t.indexOf(',') >= 0 || t.indexOf(' ') >= 0
                    : t.indexOf(',') >= 0 || t.indexOf(' ') >= 0) return "DOUBLE";
        }
        return null;
    }

    /** The first value that shows a mark, else the first value. */
    static String example(List<String> values, String mark) {
        for (String v : values) if (v.indexOf('.') >= 0 || v.indexOf(',') >= 0) return v.trim();
        return values.isEmpty() ? "" : values.get(0).trim();
    }

    private static String list(List<String> names, List<Integer> at) {
        List<String> q = new ArrayList<>();
        for (int i : at.subList(0, Math.min(3, at.size()))) q.add("'" + names.get(i) + "'");
        return String.join(", ", q) + (at.size() > 3 ? " and " + (at.size() - 3) + " more" : "");
    }

    /**
     * The SQL reading {@code column} (a quoted identifier of text) as {@code type} with {@code mark}: spaces and the
     * grouping mark removed, a {@code ,} mark turned into {@code .}, then a forgiving cast (an unreadable value is empty).
     */
    static String sql(String column, String type, String mark) {
        String s = "REPLACE(TRIM(" + column + "), ' ', '')";
        s = ",".equals(mark) ? "REPLACE(REPLACE(" + s + ", '.', ''), ',', '.')" : "REPLACE(" + s + ", ',', '')";
        return "TRY_CAST(" + s + " AS " + type + ")";
    }
}
