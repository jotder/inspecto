package com.gamma.pipeline;

import com.gamma.etl.ExcelExtension;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A dataset-scope report as a workbook ({@code format: xlsx}, ASSURE-XLSX-ATTACHMENTS-1 / D-P6).
 *
 * <p>⛔ <b>No new dependency</b> — DuckDB's {@code excel} extension writes it, the same staged binary the
 * {@code xlsx} parser and {@link com.gamma.pipeline.PipelineDocumentXlsx} use.
 *
 * <p>⛔ <b>Sealed connection</b> (see {@code ENGINE-INMEMORY-UNSEALED-1}): extension auto-install/-load is
 * switched off before anything runs, {@code excel} is loaded explicitly, the rows go in as bound parameters
 * (never spliced into SQL), and the connection is then sealed to the one output directory before the
 * {@code COPY}. Nothing on it can reach another file, a URL, or re-open its configuration.
 *
 * <p>🔴 <b>Formula injection.</b> Report cells carry data authored elsewhere — a subscriber name, a free-text
 * note. A text cell that starts with {@code = + - @} (or a tab / CR, which some readers strip first) is
 * evaluated as a formula by a spreadsheet, so {@code =HYPERLINK(...)} in a Dataset becomes a live link in the
 * finance team's inbox. {@link #neutralise} prefixes such a value with {@code '}, the OWASP remedy; it applies
 * to text only — a numeric column is written as numbers, where {@code -5} is a value, not a formula.
 *
 * <p><b>One writer, two callers</b> (operator, 2026-10-06): the report job ({@code format: xlsx}) and the
 * {@code sink.excel} node ({@link com.gamma.pipeline.exec.ExcelSink}, catalog {@code sink.file.excel}). Moved here
 * from {@code com.gamma.job.ReportXlsx} so the sink reuses the sealed path and the neutraliser instead of copying
 * them; {@link #writeSheets} adds the many-sheet workbook through {@link XlsxSheetMerger}.
 */
public final class XlsxWorkbook {

    private XlsxWorkbook() {}

    /**
     * Prefix a text value a spreadsheet would read as a formula with {@code '}; anything else unchanged. A leading
     * tab or CR is itself a lead (OWASP), and otherwise the first NON-WHITESPACE character decides, since readers
     * strip leading spaces, tabs, CR and LF before parsing. The leads are the ASCII {@code = + - @}, their
     * fullwidth forms {@code ＝ ＋ － ＠} (some readers fold them), and {@code |} / {@code %} (DDE and legacy-macro
     * leads). A value that is all spaces is left alone.
     */
    public static String neutralise(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.charAt(0) == '\t' || s.charAt(0) == '\r') return "'" + s;
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i == s.length()) return s;
        return FORMULA_LEADS.indexOf(s.charAt(i)) >= 0 ? "'" + s : s;
    }

    private static final String FORMULA_LEADS = "=+-@|%\uFF1D\uFF0B\uFF0D\uFF20";

    /** Write {@code rows} to {@code target} as a one-sheet workbook with a header row. */
    public static void write(String sheet, List<Map<String, Object>> rows, Path target) throws Exception {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Set<String> header = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) header.addAll(r.keySet());
        if (header.isEmpty()) header.add("(no rows)");
        List<String> keys = new ArrayList<>(header);

        List<String> kinds = new ArrayList<>(keys.size());   // BIGINT | DOUBLE | VARCHAR per column
        for (String k : keys) kinds.add(kind(rows, k));

        try (Connection conn = DuckDbUtil.openInMemory(null, List.of(dir), ExcelExtension::ensureLoaded)) {
            try (Statement st = conn.createStatement()) {
                List<String> cols = new ArrayList<>();
                Set<String> taken = new LinkedHashSet<>();
                for (int i = 0; i < keys.size(); i++) {
                    String name = neutralise(keys.get(i));
                    for (int n = 2; !taken.add(name.toLowerCase()); n++) name = neutralise(keys.get(i)) + "_" + n;
                    cols.add(SqlIdent.q(name) + " " + kinds.get(i));
                }
                st.execute("CREATE TABLE report_out (" + String.join(", ", cols) + ")");
            }
            String marks = String.join(", ", java.util.Collections.nCopies(keys.size(), "?"));
            try (PreparedStatement ins = conn.prepareStatement("INSERT INTO report_out VALUES (" + marks + ")")) {
                for (Map<String, Object> r : rows) {
                    for (int i = 0; i < keys.size(); i++) {
                        Object v = r.get(keys.get(i));
                        switch (kinds.get(i)) {
                            case "BIGINT" -> { if (v == null) ins.setObject(i + 1, null); else ins.setLong(i + 1, ((Number) v).longValue()); }
                            case "DOUBLE" -> { if (v == null) ins.setObject(i + 1, null); else ins.setDouble(i + 1, ((Number) v).doubleValue()); }
                            default -> ins.setString(i + 1, v == null ? null : neutralise(String.valueOf(v)));
                        }
                    }
                    ins.addBatch();
                }
                if (!rows.isEmpty()) ins.executeBatch();
            }
            DuckDbUtil.lockConfiguration(conn);
            try (Statement st = conn.createStatement()) {
                st.execute("COPY report_out TO '" + target.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                        + "' (FORMAT xlsx, HEADER true, SHEET '" + sheetName(sheet).replace("'", "''") + "')");
            }
        }
    }

    /** One sheet of a {@link #writeSheets} workbook: its (already valid) name and its rows. */
    public record Sheet(String name, List<Map<String, Object>> rows) {}

    /**
     * Write {@code sheets} to {@code target} as ONE workbook, a sheet per entry in order. Each sheet is written
     * by {@link #write} (sealed connection, neutraliser) to its own one-sheet part beside the target — DuckDB's
     * writer rewrites the file on every {@code COPY} — then {@link XlsxSheetMerger} stitches the parts into a
     * temporary file that is moved onto {@code target} atomically, so a reader never sees half a workbook and a
     * failure leaves the previous one in place.
     */
    public static void writeSheets(List<Sheet> sheets, Path target) throws Exception {
        if (sheets.isEmpty()) throw new IllegalArgumentException("a workbook needs at least one sheet");
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path parts = Files.createTempDirectory(dir, ".xlsx-parts-");
        try {
            List<Path> files = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < sheets.size(); i++) {
                Path part = parts.resolve("part" + i + ".xlsx");
                write(sheets.get(i).name(), sheets.get(i).rows(), part);
                files.add(part);
                names.add(sheetName(sheets.get(i).name()));
            }
            Path staged = parts.resolve("merged-" + java.util.UUID.randomUUID() + ".xlsx");   // no fixed name: an import cannot plant it
            XlsxSheetMerger.merge(files, names, staged);
            Files.move(staged, target.toAbsolutePath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            try (var walk = Files.list(parts)) {
                for (Path p : walk.toList()) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(parts);
        }
    }

    /** A column is numeric only when every non-null value is a {@link Number}; integral ⇒ BIGINT. */
    private static String kind(List<Map<String, Object>> rows, String key) {
        boolean any = false, fractional = false;
        for (Map<String, Object> r : rows) {
            Object v = r.get(key);
            if (v == null) continue;
            if (!(v instanceof Number)) return "VARCHAR";
            any = true;
            if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) fractional = true;
        }
        return !any ? "VARCHAR" : fractional ? "DOUBLE" : "BIGINT";
    }

    /** Excel's sheet-name rule: 31 chars, none of {@code []:*?/\}. */
    private static String sheetName(String raw) {
        String s = (raw == null ? "" : raw).replaceAll("[\\[\\]:*?/\\\\']", "_").trim();
        if (s.isEmpty()) s = "Report";
        return s.length() > 31 ? s.substring(0, 31) : s;
    }
}
