package com.gamma.config.io;

import com.gamma.api.PublicApi;
import dev.toonformat.jtoon.DecodeOptions;
import dev.toonformat.jtoon.JToon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The canonical {@code .toon} encode/decode for configuration maps.
 *
 * <p>Centralising this matters because of the leniency the rest of the system relies on (footgun G5):
 * shipped configs carry {@code #} comment lines, and {@link JToon#decode(String) default decode} is
 * <b>lenient</b> enough to tolerate them, while a strict consumer may not. {@code JToon.encode} never
 * emits comments and always quotes values that need it, so anything this codec <em>produces</em> is a
 * canonical, comment-free form that is guaranteed strict-decodable — regardless of how commented the
 * on-disk source was. The contract here:
 *
 * <ul>
 *   <li>{@link #toMap(String)} — lenient decode, for reading whatever is on disk today (comments ok);
 *   <li>{@link #toToon(Object)} — canonical, comment-free, strict-decodable encode;
 *   <li>{@link #toMapStrict(String)} — strict decode, used to <em>assert</em> a produced string is canonical.
 * </ul>
 *
 * <p>The JSON wire form needed by the Control API is produced by the API's Jackson mapper directly
 * on the decoded {@code Map} / the spec records, so it is not duplicated here.
 */
@PublicApi(since = "4.0.0")
public final class ConfigCodec {

    private static final DecodeOptions STRICT = DecodeOptions.withStrict(true);

    private ConfigCodec() {}

    /** Lenient decode (tolerates {@code #} comments) — for reading existing on-disk configs. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String toon) {
        return (Map<String, Object>) decode(toon, DecodeOptions.DEFAULT);
    }

    /** Strict decode — rejects comments / non-canonical input; use to verify a string is canonical. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMapStrict(String toon) {
        return (Map<String, Object>) decode(toon, STRICT);
    }

    /** Canonical, comment-free, strict-decodable encode of a config map (or any JToon-encodable value). */
    public static String toToon(Object value) {
        return JToon.encode(value);
    }

    /** Whether {@code toon} decodes under strict rules (no comments, canonical form). */
    public static boolean isStrictDecodable(String toon) {
        try {
            JToon.decode(toon, STRICT);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── a tabular row whose width disagrees with its header (TOON-UNQUOTED-DECIMAL-SKIPS-PIPELINE-1) ──

    /** JToon's own refusal for a row whose value count differs from its header; it names no line and no key. */
    private static final String ROW_WIDTH_REFUSAL = "Tabular row value count";

    /** {@code key[N]{a,b}:} / {@code key[N|]{a|b}:} / {@code - key[N]{a,b}:} — JToon's tabular header forms. */
    private static final Pattern TABULAR_HEADER =
            Pattern.compile("^(?:- )?(\"?[^\\[\"]*\"?)\\[#?\\d+([\\t|])?]\\{(.+)}:\\s*$");

    /**
     * Decode with JToon; when it refuses a tabular row for its width, re-throw naming the line, the table,
     * both counts and the fix. The row is genuinely ambiguous ({@code AMT,DECIMAL(18,2)} under
     * {@code {name,type}} is three values), so it stays refused — only the message changes. Any other
     * refusal, or one the locator cannot pin to a line, passes through untouched.
     */
    private static Object decode(String toon, DecodeOptions options) {
        try {
            return JToon.decode(toon, options);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null || !e.getMessage().startsWith(ROW_WIDTH_REFUSAL)) throw e;
            String located = locateRowWidthMismatch(toon);
            if (located == null) throw e;
            throw new IllegalArgumentException(located, e);
        }
    }

    /** The first tabular row whose value count differs from its header, described for an author; else null. */
    private static String locateRowWidthMismatch(String toon) {
        String[] lines = toon.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher h = TABULAR_HEADER.matcher(lines[i].strip());
            if (!h.matches()) continue;
            char delim = h.group(2) == null ? ',' : h.group(2).charAt(0);
            int width = split(h.group(3), delim).size();
            int headerIndent = indent(lines[i]);
            int rowIndent = -1;
            for (int j = i + 1; j < lines.length; j++) {
                if (lines[j].isBlank()) continue;
                int ind = indent(lines[j]);
                if (ind <= headerIndent || (rowIndent >= 0 && ind < rowIndent)) break;
                if (rowIndent < 0) rowIndent = ind;
                if (ind > rowIndent) continue;
                String row = lines[j].substring(ind);
                if (unquotedColon(row)) break;                 // a key: value line ends the table, as in JToon
                List<String> values = split(row, delim);
                if (values.size() != width)
                    return describe(j + 1, h.group(1).replace("\"", ""), h.group(3), width, values, delim);
            }
        }
        return null;
    }

    private static String describe(int line, String key, String header, int width, List<String> values, char delim) {
        String d = delim == '\t' ? "tab" : "'" + delim + "'";
        StringBuilder m = new StringBuilder("line ").append(line).append(": a row of tabular array '").append(key)
                .append("' {").append(header).append("} has ").append(values.size())
                .append(values.size() == 1 ? " value" : " values").append(" but its header declares ")
                .append(width).append(" columns");
        if (values.size() > width) {
            m.append(" — a ").append(d).append(" inside a value splits it; double-quote that value");
            String suspect = unbalancedParenValue(values, delim);
            if (suspect != null) m.append(", e.g. \"").append(suspect).append('"');
        } else {
            m.append(" — a value is missing, or a quote is left open");
        }
        return m.toString();
    }

    /** A value like {@code DECIMAL(18} that the delimiter cut from its {@code 2)}, re-joined; else null. */
    private static String unbalancedParenValue(List<String> values, char delim) {
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i);
            int open = parenDepth(v);
            if (v.startsWith("\"") || open <= 0) continue;
            StringBuilder joined = new StringBuilder(v);
            for (int k = i + 1; k < values.size() && open > 0; k++) {
                joined.append(delim).append(values.get(k));
                open += parenDepth(values.get(k));
            }
            return open == 0 ? joined.toString() : null;
        }
        return null;
    }

    private static int parenDepth(String v) {
        int d = 0;
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) == '(') d++;
            else if (v.charAt(i) == ')') d--;
        }
        return d;
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    /** Split on {@code delim} outside double quotes, honouring backslash escapes — JToon's row rule. */
    private static List<String> split(String s, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { cur.append(c); escaped = false; }
            else if (c == '\\') { cur.append(c); escaped = true; }
            else if (c == '"') { cur.append(c); inQuotes = !inQuotes; }
            else if (c == delim && !inQuotes) { out.add(cur.toString().strip()); cur.setLength(0); }
            else cur.append(c);
        }
        if (!cur.isEmpty() || s.endsWith(String.valueOf(delim))) out.add(cur.toString().strip());
        return out;
    }

    /** Whether {@code s} holds a {@code :} outside quotes past its first character (a {@code key: value} line). */
    private static boolean unquotedColon(String s) {
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':' && !inQuotes) return i > 0;
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (c == '"') inQuotes = !inQuotes;
        }
        return false;
    }
}
