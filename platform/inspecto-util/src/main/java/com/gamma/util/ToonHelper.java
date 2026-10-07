package com.gamma.util;

import dev.toonformat.jtoon.JToon;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared helpers for reading pipeline {@code .toon} configuration files.
 *
 * <p>These tiny methods were previously copy-pasted independently into every class
 * that loaded or validated a toon — {@link FileOrganizer}, {@link FileBackup},
 * {@link TarArranger}, {@link TarInboxPreparer}, and others.  This class is the
 * single canonical home.
 */
public final class ToonHelper {

    private ToonHelper() {}

    // ── loading ───────────────────────────────────────────────────────────────

    /**
     * Parse and return the top-level map from a {@code .toon} file at {@code path}.
     *
     * @throws FileNotFoundException    if the file does not exist
     * @throws IOException              on any I/O failure
     * @throws IllegalArgumentException if the text does not decode — the message starts {@code <path>: }
     */
    public static Map<String, Object> load(String path) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p))
            throw new FileNotFoundException("Toon file not found: " + path);
        String text = Files.readString(p, StandardCharsets.UTF_8);
        try {
            return decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(p + ": " + e.getMessage(), e);
        }
    }

    /**
     * THE decode of {@code .toon} text to its top-level map — every reader goes through here (directly, via
     * {@link #load}, or via {@code ConfigCodec.toMap}). It is <b>strict</b> (JToon's
     * {@code DecodeOptions.DEFAULT}); there is no lenient mode, because non-strict JToon silently truncates
     * a too-wide tabular row, drops the missing columns of a too-narrow one and reads a bad array header as
     * an empty list ({@code CONFIGCODEC-LENIENT-IS-STRICT-1}). ⚠ JToon has no comment syntax: a raw
     * {@code #} line is parsed as TOON, never skipped. A tabular row whose width disagrees with its header is refused naming the line, the table,
     * both counts and the fix (see {@link #locateRowWidthMismatch}); any other refusal passes through as
     * JToon's own message.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> decode(String toon) {
        try {
            return (Map<String, Object>) JToon.decode(toon);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null || !e.getMessage().startsWith(ROW_WIDTH_REFUSAL)) throw e;
            String located = locateRowWidthMismatch(toon);
            if (located == null) throw e;
            throw new IllegalArgumentException(located, e);
        }
    }

    // ── a tabular row whose width disagrees with its header (TOON-UNQUOTED-DECIMAL-SKIPS-PIPELINE-1) ──
    //
    // The row is genuinely ambiguous (AMT,DECIMAL(18,2) under {name,type} is three values), so it stays
    // refused — only the message changes. A refusal the locator cannot pin to a line passes through.

    /** JToon's own refusal for a row whose value count differs from its header; it names no line and no key. */
    private static final String ROW_WIDTH_REFUSAL = "Tabular row value count";

    /** {@code key[N]{a,b}:} / {@code key[N|]{a|b}:} / {@code - key[N]{a,b}:} — JToon's tabular header forms. */
    private static final Pattern TABULAR_HEADER =
            Pattern.compile("^(?:- )?(\"?[^\\[\"]*\"?)\\[#?\\d+([\\t|])?]\\{(.+)}:\\s*$");

    /** The first tabular row whose value count differs from its header, described for an author; else null. */
    private static String locateRowWidthMismatch(String toon) {
        String[] lines = toon.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher h = TABULAR_HEADER.matcher(lines[i].strip());
            if (!h.matches()) continue;
            char delim = h.group(2) == null ? ',' : h.group(2).charAt(0);
            int width = splitRow(h.group(3), delim).size();
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
                List<String> values = splitRow(row, delim);
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
    private static List<String> splitRow(String s, char delim) {
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

    // ── section access ────────────────────────────────────────────────────────

    /**
     * Return the value at {@code key} cast to {@code Map<String,Object>}.
     * Throws {@link IllegalArgumentException} when absent or of the wrong type.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> requireSection(Map<String, Object> toon, String key) {
        Object val = toon.get(key);
        if (!(val instanceof Map))
            throw new IllegalArgumentException(
                    "Pipeline toon is missing required section '" + key + "'");
        return (Map<String, Object>) val;
    }

    /**
     * Return a required, non-blank String key from a toon section.
     * Throws {@link IllegalArgumentException} when the key is absent or blank.
     */
    public static String require(Map<String, Object> section, String key, String sectionName) {
        Object val = section.get(key);
        if (val == null || val.toString().isBlank())
            throw new IllegalArgumentException(
                    "Missing required key '" + key + "' in toon section '" + sectionName + "'");
        return val.toString();
    }

    /**
     * Return an optional String key from a toon section,
     * falling back to {@code defaultVal} when absent or blank.
     */
    public static String opt(Map<String, Object> section, String key, String defaultVal) {
        Object val = section.get(key);
        return (val != null && !val.toString().isBlank()) ? val.toString() : defaultVal;
    }

    // ── base_dirs ─────────────────────────────────────────────────────────────

    /**
     * Parse {@code base_dirs} from a toon section into an unmodifiable list of
     * absolute, normalised {@link Path}s.
     *
     * <p>{@code base_dirs} may be a JToon array ({@code List<String>}) or a
     * comma-separated scalar string — both forms are accepted.
     *
     * @throws IllegalArgumentException if the key is absent or resolves to an empty list
     */
    @SuppressWarnings("unchecked")
    public static List<Path> parseBaseDirs(Map<String, Object> section) {
        Object val = section.get("base_dirs");
        if (val == null)
            throw new IllegalArgumentException("Missing 'base_dirs' in toon section");

        List<Path> result = new ArrayList<>();
        Iterable<?> items = (val instanceof List)
                ? (List<?>) val
                : Arrays.asList(val.toString().split(","));

        for (Object item : items) {
            String s = item.toString().trim();
            if (!s.isEmpty())
                result.add(Paths.get(s).toAbsolutePath().normalize());
        }
        if (result.isEmpty())
            throw new IllegalArgumentException("'base_dirs' is empty");

        return Collections.unmodifiableList(result);
    }
}
