package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.config.safety.PathJail;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.XlsxWorkbook;
import com.gamma.sql.SqlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.util.SqlIdent.q;

/**
 * <b>The {@code sink.excel} executor</b> (catalog {@code sink.file.excel}, operator 2026-10-06). Writes a committed
 * sink branch's relation as ONE {@code .xlsx} with a sheet per {@code excel.sheets} entry. Called by
 * {@link PartitionSinkWriter} on the at-rest lane — the only lane it runs on ({@code PipelineConfig.prepare()}
 * refuses the ingest lane, {@code IngestSinkWriter} throws as the backstop).
 *
 * <p>⛔ <b>No second workbook writer.</b> The bytes go through {@link XlsxWorkbook#writeSheets} — the report job's
 * path ({@code ASSURE-XLSX-ATTACHMENTS-1}): the staged DuckDB {@code excel} extension on a sealed connection, rows
 * bound as parameters, text cells formula-neutralised. No loadable extension ⇒ that write throws and the branch
 * fails (fail closed).
 *
 * <p>Each sheet's {@code sql} reads the branch relation under the alias {@code input} and must pass
 * {@link SqlGuard} (one read-only SELECT/WITH) — the {@code transform.sql} rule. A sheet over {@code max_rows}
 * FAILS the branch: a sink that truncated would deliver a report that looks complete and is not. The target is
 * {@code path} under the Space's data root, re-checked here with {@link PathJail#require} (symlinks included).
 */
@PublicApi(since = "4.0.0")
public final class ExcelSink {

    private static final Logger log = LoggerFactory.getLogger(ExcelSink.class);

    private ExcelSink() {}

    /**
     * Parse and check {@code sink}'s config without touching any data: the block itself and every sheet's SQL.
     * Every refusal a real write would raise before reading a row is raised here — which is what a dry run calls.
     */
    public static PipelineConfig.Excel plan(PipelineNode sink) {
        Map<String, Object> cfg = new LinkedHashMap<>(sink.config());
        cfg.remove("enabled");                     // the node-level flag, not an excel: key
        PipelineConfig.Excel e;
        try {
            e = PipelineConfig.Excel.fromMap(cfg);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException("sink '" + sink.id() + "': " + refused.getMessage(), refused);
        }
        for (PipelineConfig.Excel.ExcelSheet sh : e.sheets()) {
            String problem = sqlProblem(sh.sql());
            if (problem != null)
                throw new IllegalStateException("sink '" + sink.id() + "': sheet '" + sh.name() + "' refused: " + problem);
        }
        return e;
    }

    /** Why {@link SqlGuard} refuses a sheet's {@code sql}, or {@code null} when it may run (blank = every row). */
    public static String sqlProblem(String sql) {
        if (sql == null || sql.isBlank()) return null;
        List<String> messages = new ArrayList<>();
        for (com.gamma.config.spec.Finding f : SqlGuard.check(sql)) messages.add(f.message());
        return messages.isEmpty() ? null : String.join("; ", messages);
    }

    /** The workbook's absolute path: {@code excel.path} resolved under {@code dataRoot}, jailed to it. */
    public static Path target(PipelineConfig.Excel e, Path dataRoot) {
        Path root = dataRoot.toAbsolutePath().normalize();
        return PathJail.require(root, root.resolve(e.path()).toString(), "excel.path");
    }

    /** Write {@code inputTable} on {@code conn} as the workbook {@code sink} describes, under {@code dataRoot}. */
    public static Path write(Connection conn, PipelineNode sink, String inputTable, Path dataRoot) throws Exception {
        PipelineConfig.Excel e = plan(sink);
        Path target = target(e, dataRoot);
        List<XlsxWorkbook.Sheet> sheets = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TEMP VIEW input AS SELECT * FROM " + q(inputTable));
            try {
                for (PipelineConfig.Excel.ExcelSheet sh : e.sheets())
                    sheets.add(new XlsxWorkbook.Sheet(sh.name(), rows(st, sink, sh, e.maxRows())));
            } finally {
                st.execute("DROP VIEW IF EXISTS input");
            }
        }
        XlsxWorkbook.writeSheets(sheets, target);
        log.info("[PIPELINEJOB] sink '{}' → workbook {} ({} sheet(s))", sink.id(), target, sheets.size());
        return target;
    }

    /** One sheet's rows, refusing — never truncating — a result over {@code maxRows}. */
    private static List<Map<String, Object>> rows(Statement st, PipelineNode sink, PipelineConfig.Excel.ExcelSheet sh,
                                                  int maxRows) throws Exception {
        String query = sh.sql() == null ? "SELECT * FROM input" : sh.sql().trim().replaceAll(";\\s*$", "");
        List<Map<String, Object>> out = new ArrayList<>();
        // LIMIT cap+1: enough to KNOW the cap is exceeded without materialising the excess.
        try (ResultSet rs = st.executeQuery("SELECT * FROM (" + query + ") AS sheet_rows LIMIT " + (maxRows + 1L))) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                if (out.size() == maxRows)
                    throw new IllegalStateException("sink '" + sink.id() + "': sheet '" + sh.name()
                            + "' has more than max_rows (" + maxRows + ") rows — refused rather than truncated; "
                            + "raise excel.max_rows (at most " + PipelineConfig.Excel.MAX_ROWS
                            + ") or narrow the sheet's sql");
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    Object v = rs.getObject(i);
                    // HUGEINT/UHUGEINT arrive as BigInteger; longValue() would silently wrap, so keep them as text.
                    row.put(md.getColumnLabel(i), v instanceof BigInteger ? v.toString() : v);
                }
                out.add(row);
            }
        }
        return out;
    }
}
