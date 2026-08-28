package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.DataTransformer;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.PipelineNode;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>T18 — per-component dry-run / preview (validate + bounded sample, scratch-only).</b> Runs a
 * {@code transform.*} node over a handful of sample rows and returns the produced named relations, so the
 * UI can "test a component in isolation" (doc §7.2). It reuses the <em>production</em> row-shaping logic
 * ({@link RowShaper}) — no divergent test path — executed against a throwaway embedded DuckDB seeded from
 * the sample; the scratch database is deleted afterwards, so it never touches any real output.
 *
 * <p>Single-input transforms only ({@code filter}/{@code validate}/{@code route}/{@code dedup}/{@code split}/
 * {@code map}/{@code select}/{@code derive}); {@code transform.merge} is multi-input and not previewable here.
 * Sample values are seeded as {@code VARCHAR} columns (the union of the rows' keys); operator predicates cast
 * as needed, exactly as in production.
 */
@PublicApi(since = "4.0.0")
public final class ComponentPreview {

    private ComponentPreview() {}

    /** The maximum rows returned per produced relation (a preview is bounded). */
    public static final int MAX_ROWS = 1000;

    private static final String INPUT = "preview_input";

    /** One produced relation in a preview: the {@link com.gamma.pipeline.PipelineRel} and the sampled output rows. */
    public record RelationPreview(String rel, int rowCount, List<Map<String, Object>> rows) {}

    /** The preview outcome: the input column set + every relation the node produced over the sample. */
    public record Result(List<String> inputColumns, List<RelationPreview> relations) {}

    /**
     * Preview {@code node} (a {@code transform.*} node) over {@code sampleRows}. Throws
     * {@link IllegalArgumentException} for an empty sample or a non-previewable node type.
     */
    public static Result transform(PipelineNode node, List<Map<String, Object>> sampleRows)
            throws SQLException, java.io.IOException {
        if (sampleRows == null || sampleRows.isEmpty())
            throw new IllegalArgumentException("at least one sample row is required");
        List<String> columns = ScratchTables.columnsOf(sampleRows);
        if (columns.isEmpty()) throw new IllegalArgumentException("sample rows have no columns");

        File db = DuckDbUtil.tempDbFile("preview_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, INPUT, columns, sampleRows);
            List<RowShaper.Relation> produced = RowShaper.shape(conn, node, INPUT, "preview_" + node.id());
            List<RelationPreview> out = new ArrayList<>();
            for (RowShaper.Relation r : produced) {
                out.add(new RelationPreview(r.rel(),
                        ScratchTables.count(conn, r.table()),
                        ScratchTables.readRows(conn, r.table(), MAX_ROWS)));
            }
            return new Result(columns, out);
        } finally {
            DuckDbUtil.deleteTempDb(db);   // throwaway scratch DB
        }
    }

    // ── grammar (parse raw sample text via DuckDB read_csv) ────────────────────────

    /**
     * A grammar parse preview: the columns the dialect produced, the parsed rows, and the reject count.
     * {@code columnTypes} (B2, additive — old clients ignore the key) carries a per-column
     * {@code {name, type}} pair from a second {@code auto_detect=true} sniff of the same sample —
     * advisory only (production ingest stays all-VARCHAR); empty when the frontend has no sniff
     * (non-delimited) or the sniff failed.
     */
    public record GrammarResult(List<String> columns, int rowCount, List<Map<String, Object>> rows,
                                int rejectedRows, List<Map<String, String>> columnTypes) {
        public GrammarResult {
            columnTypes = columnTypes == null ? List.of() : List.copyOf(columnTypes);
        }

        /** The pre-B2 shape — no inferred types. */
        public GrammarResult(List<String> columns, int rowCount, List<Map<String, Object>> rows,
                             int rejectedRows) {
            this(columns, rowCount, rows, rejectedRows, List.of());
        }
    }

    /**
     * Preview a {@code grammar} component over raw {@code sampleText}: parse it with the grammar's CSV dialect
     * (delimiter / header / skip / quote / escape / encoding) using the <em>production</em> {@code read_csv}
     * reader on a throwaway DuckDB, then read the parsed columns/rows back. Mirrors how a parser node reads a
     * landed file (doc §7.2). Throws {@link IllegalArgumentException} for empty input.
     */
    public static GrammarResult grammar(Map<String, Object> content, String sampleText)
            throws SQLException, java.io.IOException {
        if (sampleText == null || sampleText.isBlank())
            throw new IllegalArgumentException("sample text is required");

        File db = DuckDbUtil.tempDbFile("preview_");
        java.nio.file.Path sample = java.nio.file.Files.createTempFile("preview_grammar_", ".csv");
        try {
            java.nio.file.Files.writeString(sample, sampleText);
            String path = sample.toAbsolutePath().toString().replace("\\", "/");

            StringBuilder opts = new StringBuilder();
            opts.append("delim=").append(ScratchTables.sqlStr(strOr(content, "delimiter", ",")));
            opts.append(", header=").append(boolOr(content, "has_header", false));
            opts.append(", skip=").append(intOr(content, "skip_header_lines", 0));
            String quote = strOrNull(content, "quote");
            if (quote != null) opts.append(", quote=").append(ScratchTables.sqlStr(quote));
            // Same escape-defaults-to-quote rule as the ingest path (DuckDbCsvIngester.dialectOptions).
            String escape = strOrNull(content, "escape");
            if (escape == null) escape = quote;
            if (escape != null) opts.append(", escape=").append(ScratchTables.sqlStr(escape));
            String comment = strOrNull(content, "comment");
            if (comment != null) opts.append(", comment=").append(ScratchTables.sqlStr(comment));
            String enc = strOrNull(content, "encoding");
            if (enc != null) opts.append(", encoding=").append(ScratchTables.sqlStr(enc));
            opts.append(", auto_detect=true, ignore_errors=true, store_rejects=true");

            try (Connection conn = DuckDbUtil.openConnection(db)) {
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE preview_parsed AS SELECT * FROM read_csv("
                            + ScratchTables.sqlStr(path) + ", " + opts + ")");
                }
                return new GrammarResult(
                        ScratchTables.columnNames(conn, "preview_parsed"),
                        ScratchTables.count(conn, "preview_parsed"),
                        ScratchTables.readRows(conn, "preview_parsed", MAX_ROWS),
                        rejectCount(conn));
            }
        } finally {
            java.nio.file.Files.deleteIfExists(sample);
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── pipeline parsing frontend (parse raw sample text with a draft's parsing: settings) ──

    /**
     * Preview a pipeline draft's <b>parsing frontend</b> over raw {@code sampleText} (stream
     * onboarding, v5.1.0): write the sample to a scratch file and read it with the same DuckDB
     * idioms {@link com.gamma.etl.DuckDbCsvIngester} uses per frontend — delimited
     * {@code read_csv} with the draft's dialect, fixed-width {@code substring} slices,
     * {@code read_json}, or {@code regexp_extract} named groups. Schema-less by design: this
     * is the parse step, before names/types are attached — delimited/json columns come from
     * the header/keys (auto-detected), fixed-width slices and regex groups project under their
     * own declared names. Plugin ingesters and binary fixed-width records cannot run over
     * pasted text and are rejected with {@link IllegalArgumentException}.
     */
    public static GrammarResult parsing(PipelineConfig cfg, String sampleText)
            throws SQLException, java.io.IOException {
        if (sampleText == null || sampleText.isBlank())
            throw new IllegalArgumentException("sample text is required");
        if (cfg.fixedWidth() != null && cfg.fixedWidth().binary())
            throw new IllegalArgumentException(
                    "binary fixed-width records cannot be previewed from pasted text");
        if (cfg.xlsx() != null)
            throw new IllegalArgumentException(
                    "an xlsx workbook is binary and cannot be previewed from pasted text — "
                    + "send the file bytes (sample_b64)");
        if (cfg.parquet() != null)
            throw new IllegalArgumentException(
                    "a parquet file is binary and cannot be previewed from pasted text — "
                    + "send the file bytes (sample_b64)");
        if (cfg.fixedWidth() == null && cfg.json() == null && cfg.textRegex() == null
                && cfg.schemas().ingesterClass() != null)
            throw new IllegalArgumentException("parsing preview is not supported for the plugin frontend ("
                    + cfg.schemas().ingesterClass() + ") — run the pipeline against a real file instead");

        File db = DuckDbUtil.tempDbFile("preview_");
        java.nio.file.Path sample = java.nio.file.Files.createTempFile("preview_parsing_", ".txt");
        try {
            java.nio.file.Files.writeString(sample, sampleText);
            String path = sample.toAbsolutePath().toString().replace("\\", "/");
            try (Connection conn = DuckDbUtil.openConnection(db)) {
                if (cfg.json() != null && cfg.json().newlineDelimited())
                    return ndjsonPreview(conn, cfg, path, sampleText);
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE preview_parsed AS " + parsingSelect(cfg, path));
                }
                boolean delimited = cfg.fixedWidth() == null && cfg.json() == null && cfg.textRegex() == null;
                return new GrammarResult(
                        ScratchTables.columnNames(conn, "preview_parsed"),
                        ScratchTables.count(conn, "preview_parsed"),
                        ScratchTables.readRows(conn, "preview_parsed", MAX_ROWS),
                        rejectCount(conn),
                        delimited ? sniffColumnTypes(conn, cfg, path) : List.of());
            }
        } finally {
            java.nio.file.Files.deleteIfExists(sample);
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * Preview a draft's <b>xlsx</b> parsing frontend over the workbook's BYTES (multiformat X2):
     * the binary sibling of {@link #parsing(PipelineConfig, String)}. Writes the bytes to a scratch
     * {@code .xlsx}, loads the excel extension fail-closed, and reads with the SAME
     * {@code read_xlsx} relation ingest builds ({@code DuckDbCsvIngester.xlsxReadRelation}) —
     * all-VARCHAR for the rows, plus a second non-all-VARCHAR sniff for the B2 {@code columnTypes}.
     */
    public static GrammarResult parsingXlsx(PipelineConfig cfg, byte[] sample)
            throws SQLException, java.io.IOException {
        if (cfg.xlsx() == null) throw new IllegalArgumentException("draft's parsing frontend is not xlsx");
        if (sample == null || sample.length == 0)
            throw new IllegalArgumentException("a workbook sample is required");

        File db = DuckDbUtil.tempDbFile("preview_");
        java.nio.file.Path wb = java.nio.file.Files.createTempFile("preview_parsing_", ".xlsx");
        try {
            java.nio.file.Files.write(wb, sample);
            String path = wb.toAbsolutePath().toString().replace("\\", "/");
            try (Connection conn = DuckDbUtil.openConnection(db)) {
                com.gamma.etl.ExcelExtension.ensureLoaded(conn);
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE preview_parsed AS SELECT * FROM "
                            + com.gamma.etl.DuckDbCsvIngester.xlsxReadRelation(path, cfg.xlsx(), true));
                }
                return new GrammarResult(
                        ScratchTables.columnNames(conn, "preview_parsed"),
                        ScratchTables.count(conn, "preview_parsed"),
                        ScratchTables.readRows(conn, "preview_parsed", MAX_ROWS),
                        0,
                        sniffXlsxColumnTypes(conn, cfg, path));
            }
        } finally {
            java.nio.file.Files.deleteIfExists(wb);
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * Preview a draft's <b>parquet</b> parsing frontend over the file's BYTES (ELT Phase 3 S3c-1):
     * the binary sibling of {@link #parsing(PipelineConfig, String)}, mirroring
     * {@link #parsingXlsx}. Writes the bytes to a scratch {@code .parquet} and reads with the SAME
     * {@code read_parquet} relation ingest builds ({@code DuckDbCsvIngester.parquetReadRelation}) —
     * no extension load (parquet is built into DuckDB). {@code columnTypes} are the file's own
     * embedded types, read straight from the relation's metadata.
     */
    public static GrammarResult parsingParquet(PipelineConfig cfg, byte[] sample)
            throws SQLException, java.io.IOException {
        if (cfg.parquet() == null) throw new IllegalArgumentException("draft's parsing frontend is not parquet");
        if (sample == null || sample.length == 0)
            throw new IllegalArgumentException("a parquet file sample is required");

        File db = DuckDbUtil.tempDbFile("preview_");
        java.nio.file.Path pq = java.nio.file.Files.createTempFile("preview_parsing_", ".parquet");
        try {
            java.nio.file.Files.write(pq, sample);
            String path = pq.toAbsolutePath().toString().replace("\\", "/");
            try (Connection conn = DuckDbUtil.openConnection(db)) {
                String relation = com.gamma.etl.DuckDbCsvIngester.parquetReadRelation(path, cfg.parquet());
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE preview_parsed AS SELECT * FROM " + relation);
                }
                List<String> columns = ScratchTables.columnNames(conn, "preview_parsed");
                List<Map<String, String>> types = new java.util.ArrayList<>();
                try (java.sql.Statement st = conn.createStatement();
                     java.sql.ResultSet rs = st.executeQuery("SELECT * FROM " + relation + " LIMIT 0")) {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Map<String, String> col = new java.util.LinkedHashMap<>();
                        col.put("name", md.getColumnName(i));
                        col.put("type", md.getColumnTypeName(i));
                        types.add(col);
                    }
                }
                return new GrammarResult(
                        columns,
                        ScratchTables.count(conn, "preview_parsed"),
                        ScratchTables.readRows(conn, "preview_parsed", MAX_ROWS),
                        0,
                        types);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(pq);
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** B2 for xlsx: the same relation without {@code all_varchar} — the extension's own cell typing.
     *  Advisory by construction, so a failed sniff returns empty rather than failing the preview. */
    private static List<Map<String, String>> sniffXlsxColumnTypes(Connection conn, PipelineConfig cfg,
                                                                  String path) {
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE preview_sniff AS SELECT * FROM "
                    + com.gamma.etl.DuckDbCsvIngester.xlsxReadRelation(path, cfg.xlsx(), false));
            List<Map<String, String>> out = new java.util.ArrayList<>();
            try (java.sql.ResultSet rs = st.executeQuery(
                    "SELECT column_name, column_type FROM (DESCRIBE preview_sniff)")) {
                while (rs.next()) {
                    Map<String, String> col = new java.util.LinkedHashMap<>();
                    col.put("name", rs.getString(1));
                    col.put("type", rs.getString(2));
                    out.add(col);
                }
            }
            return out;
        } catch (Exception sniffFail) {
            return List.of();
        }
    }

    /**
     * The frontend-specific {@code SELECT} reading {@code path} — the same read specs
     * {@code DuckDbCsvIngester} builds, minus the schema projection (which does not exist yet
     * at the parsing stage). NDJSON is handled separately ({@link #ndjsonPreview}).
     */
    private static String parsingSelect(PipelineConfig cfg, String path) {
        if (cfg.fixedWidth() != null) return fixedWidthSelect(cfg, path);
        if (cfg.textRegex() != null)  return textRegexSelect(cfg, path);
        if (cfg.json() != null)       return jsonSelect(cfg, path);
        return delimitedSelect(cfg, path);
    }

    /**
     * NDJSON preview, mirroring the engine's newline path exactly: the single-column line
     * reader (header-skip semantics included), a {@code json_valid} filter — a malformed line
     * is routed away, never null-padded — then {@code json_extract_string} per top-level key.
     * Keys are discovered from the valid records in first-seen order (schema-less: the keys
     * ARE the columns). {@code rejectedRows} counts the dropped invalid lines.
     */
    private static GrammarResult ndjsonPreview(Connection conn, PipelineConfig cfg,
                                               String path, String sampleText) throws SQLException {
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE js_lines AS SELECT \"line\" FROM " + lineReader(cfg, path)
                    + " WHERE json_valid(\"line\")");
        }
        int skip = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
        long totalAfterSkip = Math.max(0, sampleText.lines().count() - skip);
        int valid = ScratchTables.count(conn, "js_lines");
        int invalid = (int) Math.max(0, totalAfterSkip - valid);

        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT json_keys(\"line\") FROM js_lines")) {
            while (rs.next()) {
                java.sql.Array arr = rs.getArray(1);
                if (arr != null) for (Object k : (Object[]) arr.getArray()) keys.add(String.valueOf(k));
            }
        }
        if (keys.isEmpty())
            return new GrammarResult(List.of(), 0, List.of(), invalid + rejectCount(conn));

        StringBuilder proj = new StringBuilder();
        boolean first = true;
        for (String k : keys) {
            if (!first) proj.append(", ");
            first = false;
            proj.append("json_extract_string(\"line\", '$.\"").append(k.replace("'", "''"))
                .append("\"') AS ").append(quoteIdent(k));
        }
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE preview_parsed AS SELECT " + proj + " FROM js_lines");
        }
        return new GrammarResult(
                ScratchTables.columnNames(conn, "preview_parsed"),
                ScratchTables.count(conn, "preview_parsed"),
                ScratchTables.readRows(conn, "preview_parsed", MAX_ROWS),
                invalid + rejectCount(conn));
    }

    private static String delimitedSelect(PipelineConfig cfg, String path) {
        // all_varchar mirrors production raw ingest (100% VARCHAR columns) AND keeps the preview
        // rows JSON-serializable — auto-detect would otherwise type a date column as a DuckDB
        // DATE, which the parsed→typed hop is precisely meant to make explicit, not implicit.
        return "SELECT * FROM read_csv(" + ScratchTables.sqlStr(path)
                + delimitedReadOptions(cfg)
                + ", auto_detect=true, all_varchar=true, ignore_errors=true, store_rejects=true)";
    }

    /** The shared delimited read options: dialect chars + header/skip, mirroring production. */
    private static String delimitedReadOptions(PipelineConfig cfg) {
        String delim = (cfg.csv().delimiter() == null || cfg.csv().delimiter().isEmpty())
                ? "," : cfg.csv().delimiter();
        StringBuilder opts = new StringBuilder();
        opts.append(", delim=").append(ScratchTables.sqlStr(delim));
        opts.append(", header=").append(cfg.csv().hasHeader());
        opts.append(", skip=").append(cfg.csv().skipHeaderLines());
        if (cfg.csv().quote() != null)
            opts.append(", quote=").append(ScratchTables.sqlStr(cfg.csv().quote()));
        // Escape defaults to the quote char (RFC doubling) when a custom quote is set — the same
        // rule DuckDbCsvIngester.dialectOptions applies, so the preview mirrors production.
        String parseEscape = cfg.csv().escape() != null ? cfg.csv().escape() : cfg.csv().quote();
        if (parseEscape != null)
            opts.append(", escape=").append(ScratchTables.sqlStr(parseEscape));
        if (cfg.csv().comment() != null)
            opts.append(", comment=").append(ScratchTables.sqlStr(cfg.csv().comment()));
        return opts.toString();
    }

    /**
     * B2: a second, {@code auto_detect=true} sniff of the same sample — per-column inferred types for
     * the Data-types Auto mode. Advisory by construction: production ingest stays all-VARCHAR, so a
     * failed sniff returns empty rather than failing the preview. Deliberately no {@code store_rejects}
     * — the sniff must not pollute the reject count the parse above just produced.
     */
    private static List<Map<String, String>> sniffColumnTypes(Connection conn, PipelineConfig cfg, String path) {
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE preview_sniff AS SELECT * FROM read_csv(" + ScratchTables.sqlStr(path)
                    + delimitedReadOptions(cfg)
                    + ", auto_detect=true, ignore_errors=true)");
            List<Map<String, String>> out = new java.util.ArrayList<>();
            try (java.sql.ResultSet rs = st.executeQuery(
                    "SELECT column_name, column_type FROM (DESCRIBE preview_sniff)")) {
                while (rs.next()) {
                    Map<String, String> col = new java.util.LinkedHashMap<>();
                    col.put("name", rs.getString(1));
                    col.put("type", rs.getString(2));
                    out.add(col);
                }
            }
            return out;
        } catch (Exception sniffFail) {
            return List.of();
        }
    }

    /** The engine's single-column line reader: each physical line intact as VARCHAR {@code line}. */
    private static String lineReader(PipelineConfig cfg, String path) {
        int skip = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
        return "read_csv(" + ScratchTables.sqlStr(path)
                + ", columns={'line':'VARCHAR'}, delim='', quote='', escape=''"
                + ", header=false, skip=" + skip
                + ", ignore_errors=true, null_padding=true, auto_detect=false, store_rejects=true)";
    }

    private static String fixedWidthSelect(PipelineConfig cfg, String path) {
        PipelineConfig.FixedWidth fw = cfg.fixedWidth();
        List<PipelineConfig.FixedWidth.Slice> slices = fw.slices();
        StringBuilder proj = new StringBuilder();
        for (int i = 0; i < slices.size(); i++) {
            PipelineConfig.FixedWidth.Slice s = slices.get(i);
            String name = (s.name() == null || s.name().isBlank()) ? "field_" + i : s.name();
            if (i > 0) proj.append(", ");
            proj.append(trimmed(fw.trim(), "substring(\"line\", " + (s.start() + 1) + ", " + s.length() + ")"))
                .append(" AS ").append(quoteIdent(name));
        }
        return "SELECT " + proj + " FROM (SELECT \"line\" FROM " + lineReader(cfg, path)
                + " WHERE length(\"line\") >= " + fw.minRecordLength() + ") AS fw";
    }

    private static String textRegexSelect(PipelineConfig cfg, String path) {
        PipelineConfig.TextRegex tr = cfg.textRegex();
        StringBuilder names = new StringBuilder("[");
        StringBuilder proj = new StringBuilder();
        for (int i = 0; i < tr.groupNames().size(); i++) {
            String g = tr.groupNames().get(i);
            if (i > 0) { names.append(", "); proj.append(", "); }
            names.append(ScratchTables.sqlStr(g));
            proj.append("rec[").append(ScratchTables.sqlStr(g)).append("] AS ").append(quoteIdent(g));
        }
        names.append(']');

        if (!"\n".equals(tr.recordSplit())) {
            // Block mode: match the (?s)-prefixed pattern against whole (trimmed) records split on
            // the literal delimiter, mirroring DuckDbCsvIngester.buildTextRegexBlockReadSpec.
            String blockPattern = ScratchTables.sqlStr("(?s)" + tr.pattern());
            int skip = cfg.csv().skipHeaderLines() + (cfg.csv().hasHeader() ? 1 : 0);
            return "SELECT " + proj + " FROM (SELECT regexp_extract(trim(blk), "
                    + blockPattern + ", " + names + ") AS rec"
                    + " FROM (SELECT unnest(list_slice(str_split(content, "
                    + ScratchTables.sqlStr(tr.recordSplit()) + "), " + (skip + 1) + ", 2147483647)) AS blk"
                    + " FROM read_text(" + ScratchTables.sqlStr(path) + "))"
                    + " WHERE trim(blk) != '' AND regexp_matches(trim(blk), " + blockPattern
                    + ")) AS tr";
        }

        return "SELECT " + proj + " FROM (SELECT regexp_extract(\"line\", "
                + ScratchTables.sqlStr(tr.pattern()) + ", " + names + ") AS rec FROM "
                + lineReader(cfg, path)
                + " WHERE regexp_matches(\"line\", " + ScratchTables.sqlStr(tr.pattern()) + ")) AS tr";
    }

    /** {@code format: array | auto} only ({@code newline} goes through {@link #ndjsonPreview}).
     *  Like the engine's {@code read_json}, a malformed document fails the whole file. */
    private static String jsonSelect(PipelineConfig cfg, String path) {
        String format = "array".equals(cfg.json().format()) ? "array" : "auto";
        // Cast every column to VARCHAR (the array/auto counterpart of the NDJSON path's json_extract_string)
        // so an auto-detected timestamp comes back as its raw string, not a DuckDB TIMESTAMP → java.time —
        // keeping this preview byte-consistent with every other format. read_json has no all_varchar option,
        // so COLUMNS(*)::VARCHAR does the blanket cast without needing to know the column names. The
        // parsed→typed hop downstream is where typing becomes explicit, not this raw preview.
        return "SELECT COLUMNS(*)::VARCHAR FROM read_json(" + ScratchTables.sqlStr(path)
                + ", format='" + format + "')";
    }

    /** Quote a projection identifier, escaping embedded quotes. */
    private static String quoteIdent(String name) {
        return SqlIdent.q(name);
    }

    /** Wrap an expression in the configured fixed-width trim function (mirrors the ingester). */
    private static String trimmed(PipelineConfig.FixedWidth.Trim trim, String inner) {
        return switch (trim) {
            case NONE  -> inner;
            case LEFT  -> "ltrim(" + inner + ")";
            case RIGHT -> "rtrim(" + inner + ")";
            case BOTH  -> "trim(" + inner + ")";
        };
    }

    // ── schema (TRY_CAST each field to its declared type; split data / rejected) ────

    /**
     * Preview a {@code schema} component over {@code sampleRows} (all VARCHAR): {@code TRY_CAST} each declared
     * field to its type and split rows into {@code data} (every typed field casts, or is null/blank) and
     * {@code rejected} (some non-blank value fails its cast) — the same data-vs-reject split production applies
     * (doc §7.2). Throws {@link IllegalArgumentException} for an empty sample or a schema with no typed fields.
     *
     * <p>When {@code content} also carries {@code mapping.rules}, a third {@code mapped} relation is produced:
     * the rules compiled over the {@code data} rows through {@link DataTransformer#dataColumns} — the very
     * projection the graph executor's {@code transform.map} runs ({@link RowShaper#mappedColumns}) — so the
     * Load drawer's "mapped output" table shows target columns, not the passing input. This is what makes an
     * {@code EXPR} rule reviewable at all: its effect exists only once compiled. Absent or empty rules leave
     * the result exactly as it was, so the cast-only callers are unaffected.
     */
    public static Result schema(Map<String, Object> content, List<Map<String, Object>> sampleRows)
            throws SQLException, java.io.IOException {
        if (sampleRows == null || sampleRows.isEmpty())
            throw new IllegalArgumentException("at least one sample row is required");
        List<String> columns = ScratchTables.columnsOf(sampleRows);
        if (columns.isEmpty()) throw new IllegalArgumentException("sample rows have no columns");
        List<Map<String, Object>> fields = schemaFields(content);
        if (fields.isEmpty())
            throw new IllegalArgumentException("schema has no typed fields (expected 'raw.fields' / 'fields' / 'columns')");

        List<String> conds = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            String name = String.valueOf(f.get("name"));
            String type = f.get("type") == null ? null : f.get("type").toString();
            if (name == null || name.isBlank() || !columns.contains(name) || type == null) continue;
            String castOk = castExpr(name, type, f.get("format"));
            if (castOk == null) continue;   // VARCHAR / unknown → never rejects
            conds.add("((" + ScratchTables.q(name) + " IS NULL OR " + ScratchTables.q(name) + " = '') OR (" + castOk + "))");
        }
        String allOk = conds.isEmpty() ? "TRUE" : String.join(" AND ", conds);

        File db = DuckDbUtil.tempDbFile("preview_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, INPUT, columns, sampleRows);
            String data = "preview_schema__data";
            String rejected = "preview_schema__rejected";
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE " + ScratchTables.q(data) + " AS SELECT * FROM " + ScratchTables.q(INPUT)
                        + " WHERE COALESCE((" + allOk + "), FALSE)");
                st.execute("CREATE TABLE " + ScratchTables.q(rejected) + " AS SELECT * FROM " + ScratchTables.q(INPUT)
                        + " WHERE NOT COALESCE((" + allOk + "), FALSE)");
            }
            List<RelationPreview> out = new ArrayList<>();
            out.add(new RelationPreview("data", ScratchTables.count(conn, data),
                    ScratchTables.readRows(conn, data, MAX_ROWS)));
            out.add(new RelationPreview("rejected", ScratchTables.count(conn, rejected),
                    ScratchTables.readRows(conn, rejected, MAX_ROWS)));

            List<Map<String, Object>> projection = mappedProjection(content, data);
            if (projection != null) {
                String mapped = "preview_schema__mapped";
                StringBuilder sel = new StringBuilder();
                for (Map<String, Object> col : projection) {
                    if (!sel.isEmpty()) sel.append(", ");
                    sel.append(col.get("expr")).append(" AS ")
                       .append(ScratchTables.q(String.valueOf(col.get("name"))));
                }
                try (java.sql.Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE " + ScratchTables.q(mapped) + " AS SELECT " + sel
                            + " FROM " + ScratchTables.q(data));
                }
                out.add(new RelationPreview("mapped", ScratchTables.count(conn, mapped),
                        ScratchTables.readRows(conn, mapped, MAX_ROWS)));
            }
            return new Result(columns, List.copyOf(out));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * The compiled {@code {name, expr}} projection for this schema draft's mapping rules over
     * {@code sourceTable}, or {@code null} when the draft declares none — in which case the preview stays
     * the cast-only split it has always been.
     *
     * <p>Guards the exact shape {@link DataTransformer#dataColumns} requires ({@code raw.fields} +
     * {@code mapping.rules}, both lists) rather than the looser set {@link #schemaFields} accepts: that
     * helper also reads a top-level {@code fields}/{@code columns}, and handing such a draft to
     * {@code dataColumns} would fail on a cast rather than simply skip the mapped relation.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mappedProjection(Map<String, Object> content, String sourceTable) {
        if (!(content.get("raw") instanceof Map<?, ?> raw) || !(raw.get("fields") instanceof List<?> fields)
                || fields.isEmpty()) return null;
        if (!(content.get("mapping") instanceof Map<?, ?> mapping)
                || !(mapping.get("rules") instanceof List<?> rules) || rules.isEmpty()) return null;
        List<Map<String, Object>> cols =
                DataTransformer.dataColumns(content, csvSettingsOf(content), sourceTable);
        return cols.isEmpty() ? null : cols;
    }

    /**
     * The {@code csv} settings to compile a draft's rules with — only the two format lists are read
     * ({@code DIRECT} on a DATE/TIMESTAMP field parses through them). A draft posted to the preview route
     * carries its {@code csv} block as a plain map, if at all; absent, the empty lists compile a typed
     * column to a plain {@code TRY_CAST}. Mirrors {@link RowShaper}'s own resolution for the same reason.
     */
    private static PipelineConfig.CsvSettings csvSettingsOf(Map<String, Object> content) {
        if (!(content.get("csv") instanceof Map<?, ?> m))
            return PipelineConfig.CsvSettings.ofFormats(List.of(), List.of());
        return PipelineConfig.CsvSettings.ofFormats(
                formatList(m.get("dateFormats")), formatList(m.get("tsFormats")));
    }

    /** One decoded format list — empty for anything that is not a list, so a malformed block degrades. */
    private static List<String> formatList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) if (o != null) out.add(o.toString());
        return List.copyOf(out);
    }

    // ── sink (scratch-validate config against the sample — no write) ────────────────

    /** A sink preview: the bound store, the rows that would be written (bounded sample), and any warnings. */
    public record SinkResult(String store, int rowCount, List<Map<String, Object>> rows, List<String> warnings) {}

    /**
     * Scratch-validate a {@code sink} component against {@code sampleRows}: confirm it declares a {@code store},
     * its {@code format} is recognised, and any declared partition columns (and the {@code source} columns they
     * derive from, which the write path reads for event-time bounds) are present in the sample — reporting
     * the row count + bounded sample that <em>would</em> be written. Pure validation; nothing is persisted
     * (doc §7.2).
     */
    public static SinkResult sink(Map<String, Object> content, List<Map<String, Object>> sampleRows) {
        List<Map<String, Object>> rows = sampleRows == null ? List.of() : sampleRows;
        List<String> columns = ScratchTables.columnsOf(rows);
        List<String> warnings = new ArrayList<>();

        String store = strOrNull(content, "store");
        if (store == null) warnings.add("sink declares no 'store' name");

        String format = strOrNull(content, "format");
        if (format != null && !ALLOWED_SINK_FORMATS.contains(format.toLowerCase()))
            warnings.add("unrecognised format '" + format + "' (expected one of " + ALLOWED_SINK_FORMATS + ")");

        Object partitions = content.get("partitions");
        for (String bad : SinkPartitions.entriesWithoutColumn(partitions))
            warnings.add("partition entry " + bad + " declares no 'column'"
                    + " — the sink will refuse to write until it does");

        for (String pc : SinkPartitions.columns(partitions))
            if (!columns.contains(pc))
                warnings.add("partition column '" + pc + "' is not present in the sample rows");

        List<String> sources = SinkPartitions.declaredSources(partitions);
        if (sources.remove(""))   // present but empty: the write path reads no bounds at all
            warnings.add("a partition declares an empty 'source'"
                    + " — no event-time bounds will be recorded for this sink");
        for (String ps : sources)
            if (!SinkPartitions.SAFE_COLUMN.matcher(ps).matches())
                warnings.add("partition source '" + ps + "' is not a plain column identifier"
                        + " — no event-time bounds will be recorded for this sink");
            else if (!columns.contains(ps))
                warnings.add("partition source '" + ps + "' is not present in the sample rows"
                        + " — no event-time bounds will be recorded for this sink");
        if (sources.size() > 1)
            warnings.add("partitions declare more than one 'source' (" + String.join(", ", sources)
                    + ") — no single event time, so no bounds will be recorded for this sink");

        int cap = Math.min(rows.size(), MAX_ROWS);
        return new SinkResult(store, rows.size(), new ArrayList<>(rows.subList(0, cap)), warnings);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static final Set<String> ALLOWED_SINK_FORMATS = Set.of("parquet", "csv", "json", "avro");

    /** Count rows the grammar's {@code read_csv} rejected (0 if {@code store_rejects} never fired). */
    private static int rejectCount(Connection conn) {
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM reject_errors")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException noRejects) {
            return 0;   // reject tables only exist once store_rejects has fired
        }
    }

    /** The typed field list of a schema component: {@code raw.fields} (parse schema) or {@code fields}/{@code columns}.
     *  Package-private rather than private so {@link SchemaSuggest#drift} reads a draft's fields through this
     *  same accessor — a third reader of {@code raw.fields} is a third thing to drift. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> schemaFields(Map<String, Object> content) {
        if (content.get("raw") instanceof Map<?, ?> raw && raw.get("fields") instanceof List<?> rf)
            return castFields(rf);
        if (content.get("fields") instanceof List<?> f)   return castFields(f);
        if (content.get("columns") instanceof List<?> c)  return castFields(c);
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castFields(List<?> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : raw) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    /**
     * A boolean "this value casts to {@code type}" SQL expression over column {@code name}, or {@code null} for
     * string/unknown types (which never reject). Date/timestamp with a {@code format} use {@code TRY_STRPTIME}.
     */
    private static String castExpr(String name, String type, Object format) {
        String col = ScratchTables.q(name);
        String t = type.trim().toLowerCase();
        String fmt = format == null ? null : format.toString();
        return switch (t) {
            case "int", "integer", "int32"            -> tryCast(col, "INTEGER");
            case "long", "bigint", "int64"            -> tryCast(col, "BIGINT");
            case "short", "smallint"                  -> tryCast(col, "SMALLINT");
            case "double", "float", "real", "decimal", "numeric" -> tryCast(col, "DOUBLE");
            case "bool", "boolean"                    -> tryCast(col, "BOOLEAN");
            case "date"      -> fmt != null ? "TRY_STRPTIME(" + col + ", " + ScratchTables.sqlStr(fmt) + ") IS NOT NULL"
                                            : tryCast(col, "DATE");
            case "timestamp", "datetime" -> fmt != null ? "TRY_STRPTIME(" + col + ", " + ScratchTables.sqlStr(fmt) + ") IS NOT NULL"
                                            : tryCast(col, "TIMESTAMP");
            default -> null;   // varchar / string / text / unknown — always valid
        };
    }

    private static String tryCast(String col, String sqlType) {
        return "TRY_CAST(" + col + " AS " + sqlType + ") IS NOT NULL";
    }

    private static String strOr(Map<String, Object> m, String key, String dflt) {
        String v = strOrNull(m, key);
        return v == null ? dflt : v;
    }

    private static String strOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private static boolean boolOr(Map<String, Object> m, String key, boolean dflt) {
        Object v = m.get(key);
        return v == null ? dflt : Boolean.parseBoolean(v.toString());
    }

    private static int intOr(Map<String, Object> m, String key, int dflt) {
        Object v = m.get(key);
        if (v == null) return dflt;
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return dflt; }
    }
}
