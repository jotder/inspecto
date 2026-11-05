package com.gamma.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fix catalogue behind "problems in plain words" ({@code ONBOARDING-FIRST-RUN-1} B3, slice C2): one server-side list
 * that both apps read. A draft finding, a commit refusal or a quarantine reason that matches an entry carries a plain
 * {@code cause} and a one-click {@code fix}; a fix only edits what the user is about to confirm and is then re-run by
 * the user - nothing is applied silently. Every edition (correctness is not an edition feature).
 *
 * <p>A finding is {@code {entry, column?, cause, fix:{label, redraft? | columns? | newDataset?}}}:
 * <ul>
 *   <li>{@code redraft:{param: value}} - add the same file again to {@code POST /onboard/files} with these query
 *       parameters (they replace the draft's proposal), then commit;</li>
 *   <li>{@code columns:[{name, type, format?}]} - send these entries in the commit body's {@code columns};</li>
 *   <li>{@code newDataset:true} - the file does not fit the Dataset: add it with {@code POST /onboard/files} as its own.</li>
 * </ul>
 */
final class OnboardFixes {

    private OnboardFixes() {}

    /** The entries, in the order the plan lists them (B3). */
    static final List<String> ENTRIES = List.of("decimal_mark", "date_format", "delimiter", "header_row", "encoding", "quote",
            "missing_column");

    private static Map<String, Object> finding(String entry, String column, String cause, Map<String, Object> fix) {
        if (!ENTRIES.contains(entry)) throw new IllegalArgumentException(entry);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("entry", entry);
        if (column != null) f.put("column", column);
        f.put("cause", cause);
        f.put("fix", fix);
        return f;
    }

    private static Map<String, Object> redraft(String label, String param, String value) {
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("label", label);
        fix.put("redraft", Map.of(param, value));
        return fix;
    }

    static Map<String, Object> decimalMark(String column, String example, int matching, int of, String mark) {
        return finding("decimal_mark", column, "'" + column + "' has numbers like '" + example + "' in " + matching + " of " + of
                + " sampled rows: this file uses '" + mark + "' as the decimal mark", redraft("Switch decimal mark", "decimal", mark));
    }

    private static Map<String, Object> dateFix(String column, String format) {
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("name", column);
        col.put("type", "DATE");
        col.put("format", format);
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("label", "Read '" + column + "' as dates (" + format + ")");
        fix.put("columns", List.of(col));
        return fix;
    }

    /**
     * {@code alsoAs}: another format that reads exactly as many values ({@code 03/04/2026} is day first or month first) -
     * then the cause says so, and the fix carries it under {@code alternatives}, so the user chooses: never a silent guess.
     */
    static Map<String, Object> dateFormat(String column, String format, int matching, int of, String odd, String alsoAs) {
        Map<String, Object> fix = dateFix(column, format);
        if (alsoAs != null) fix.put("alternatives", List.of(dateFix(column, alsoAs)));
        return finding("date_format", column, matching + " of " + of + " sampled values of '" + column + "' are dates written " + format
                + (alsoAs == null ? "" : " - or " + alsoAs + ", which reads them just as well; say which")
                + (odd == null ? (alsoAs == null ? ", so it is read as text" : "") : "; " + (of - matching) + (of - matching == 1 ? " is" : " are")
                + " not a real date (e.g. '" + odd + "') and would stay empty"), fix);
    }

    static Map<String, Object> delimiter(String now, int agreeing, int lines, String better, int fields) {
        return finding("delimiter", null, (agreeing == lines ? "every sampled line is one column on " + name(now)
                : "only " + agreeing + " of " + lines + " sampled lines split alike on " + name(now)) + "; every line splits into "
                + fields + " columns on " + name(better), redraft("Split on " + name(better), "delimiter", better));
    }

    static Map<String, Object> quote(String now, int agreeing, int lines, String better, int fields) {
        return finding("quote", null, "only " + agreeing + " of " + lines + " sampled lines split alike when " + quoteName(now)
                + " quotes a field; with " + quoteName(better) + " every line splits into " + fields + " columns",
                redraft(better.isEmpty() ? "Read quotes as text" : "Quote with " + better, "quote", better));
    }

    static Map<String, Object> headerRow(boolean header, List<String> firstLine) {
        String shown = String.join(", ", firstLine.subList(0, Math.min(3, firstLine.size())));
        return header
                ? finding("header_row", null, "the first line (" + shown + ") holds values like the rows below, so it is data, not names",
                        redraft("Read the first line as data", "header", "false"))
                : finding("header_row", null, "the first line (" + shown + ") holds names, not values like the rows below",
                        redraft("Use the first line as column names", "header", "true"));
    }

    /** {@code utf16}: the file starts with a UTF-16 byte-order mark (Excel's "Unicode Text"), else it is read as Latin-1. */
    static Map<String, Object> encoding(String file, boolean utf16) {
        return utf16
                ? finding("encoding", null, "'" + file + "' is UTF-16 text (it starts with a UTF-16 byte-order mark)",
                        redraft("Read it as UTF-16", "encoding", "utf-16"))
                : finding("encoding", null, "'" + file + "' is not UTF-8 text; it reads as Latin-1 (Windows Western)",
                        redraft("Read it as Latin-1", "encoding", "latin-1"));
    }

    static Map<String, Object> missingColumn(String file, String dataset, String column) {
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("label", "Add '" + file + "' as its own Dataset");
        fix.put("newDataset", true);
        return finding("missing_column", column, "'" + file + "' has no column '" + column + "', which '" + dataset + "' reads", fix);
    }

    static String name(String delimiter) {
        return switch (delimiter) {
            case "\t" -> "a tab";
            default -> "'" + delimiter + "'";
        };
    }

    private static String quoteName(String q) {
        return q.isEmpty() ? "no character" : "'" + q + "'";
    }

    /** A refusal that names its findings: answered as the usual error, with {@code findings} under {@code error.details}. */
    static final class Refusal extends RuntimeException {
        final int status;
        final List<Map<String, Object>> findings;

        Refusal(int status, String message, List<Map<String, Object>> findings) {
            super(message);
            this.status = status;
            this.findings = List.copyOf(findings);
        }
    }

    /** {@code f} about column {@code from} said of column {@code to} (a file with no header line numbers its columns). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> renamed(Map<String, Object> f, String from, String to) {
        if (!from.equals(f.get("column"))) return f;
        Map<String, Object> g = new LinkedHashMap<>(f);
        g.put("column", to);
        g.put("cause", String.valueOf(f.get("cause")).replace("'" + from + "'", "'" + to + "'"));
        Map<String, Object> fix = new LinkedHashMap<>((Map<String, Object>) f.get("fix"));
        fix.put("label", String.valueOf(fix.get("label")).replace("'" + from + "'", "'" + to + "'"));
        if (fix.get("columns") instanceof List<?> cols) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : cols) {
                Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) o);
                if (from.equals(c.get("name"))) c.put("name", to);
                out.add(c);
            }
            fix.put("columns", out);
        }
        g.put("fix", fix);
        return g;
    }

    /** The findings among {@code all} that are not already in {@code into} (same entry and column). */
    static List<Map<String, Object>> merge(List<Map<String, Object>> into, List<Map<String, Object>> all) {
        List<Map<String, Object>> out = new ArrayList<>(into);
        for (Map<String, Object> f : all)
            if (out.stream().noneMatch(o -> o.get("entry").equals(f.get("entry")) && java.util.Objects.equals(o.get("column"), f.get("column"))))
                out.add(f);
        return out;
    }
}
