package com.gamma.job;

import com.gamma.etl.ExcelExtension;
import com.gamma.sql.SqlSandbox;
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
 */
final class ReportXlsx {

    private ReportXlsx() {}

    /** Prefix a text value a spreadsheet would read as a formula with {@code '}; anything else unchanged. */
    static String neutralise(String s) {
        if (s == null || s.isEmpty()) return s;
        char c = s.charAt(0);
        return (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') ? "'" + s : s;
    }

    /** Write {@code rows} to {@code target} as a one-sheet workbook with a header row. */
    static void write(String sheet, List<Map<String, Object>> rows, Path target) throws Exception {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Set<String> header = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) header.addAll(r.keySet());
        if (header.isEmpty()) header.add("(no rows)");
        List<String> keys = new ArrayList<>(header);

        List<String> kinds = new ArrayList<>(keys.size());   // BIGINT | DOUBLE | VARCHAR per column
        for (String k : keys) kinds.add(kind(rows, k));

        try (Connection conn = DuckDbUtil.openInMemory(null)) {
            SqlSandbox.disableExtensionAutoload(conn);
            ExcelExtension.ensureLoaded(conn);
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
            SqlSandbox.sealAllowing(conn, List.of(dir));
            try (Statement st = conn.createStatement()) {
                st.execute("COPY report_out TO '" + target.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                        + "' (FORMAT xlsx, HEADER true, SHEET '" + sheetName(sheet).replace("'", "''") + "')");
            }
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
