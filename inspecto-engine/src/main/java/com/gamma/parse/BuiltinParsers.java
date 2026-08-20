package com.gamma.parse;

import com.gamma.config.spec.FieldSpec;
import com.gamma.config.spec.FieldType;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.exec.ComponentPreview;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The five built-in {@link ParserPlugin}s — thin self-description adapters over the engine's own
 * DuckDB-native parsing frontends. Their {@link ParserPlugin#preview} builds a minimal pipeline
 * draft around the grammar and delegates to {@link ComponentPreview#parsing}, i.e. the exact
 * read specs {@code DuckDbCsvIngester} runs at ingest — full transparency: the caller never learns
 * (nor needs to) that these are DuckDB while others are custom Java.
 *
 * <p>Grammar paths mirror the Stage-1 {@code parsing:} TOON block verbatim (shared csv-settings
 * keys live under {@code delimited.*} — that block IS csv_settings under its canonical name, e.g.
 * {@code delimited.has_header} also drives the fixed-width header skip; see
 * {@code PipelineConfigParser.mergeParsing}).
 */
final class BuiltinParsers {

    private static final Set<String> IDS = Set.of("delimited", "fixedwidth", "json", "text_regex", "xlsx");

    private BuiltinParsers() {}

    static boolean isBuiltin(String id) {
        return IDS.contains(id);
    }

    static List<ParserPlugin> all() {
        return List.of(
                new Builtin("delimited", "Delimited — CSV / TSV / pipe, one record per line", List.of(
                        // Dialect
                        FieldSpec.withDefault("delimited.delimiter", "Delimiter", FieldType.STRING, ",",
                                "Column separator character."),
                        FieldSpec.of("delimited.quote", "Quote character", FieldType.STRING,
                                "Single character wrapping fields that contain the delimiter (default \")."),
                        FieldSpec.of("delimited.escape", "Escape character", FieldType.STRING,
                                "Single character escaping a literal quote inside a quoted field (default: the quote, doubled)."),
                        FieldSpec.of("delimited.comment", "Comment character", FieldType.STRING,
                                "Lines starting with this single character are skipped (default: none)."),
                        FieldSpec.withDefault("delimited.has_header", "First line is a header", FieldType.BOOL, true,
                                "Whether line one carries the column names."),
                        FieldSpec.of("delimited.skip_header_lines", "Skip leading lines", FieldType.INT,
                                "Banner/preamble lines before the data (and header)."),
                        FieldSpec.of("delimited.skip_junk_lines", "Skip junk lines (adaptive)", FieldType.INT,
                                "Max preamble lines to probe past until a parseable data row; -1 = unlimited."),
                        FieldSpec.of("delimited.skip_tail_lines", "Skip trailing lines", FieldType.INT,
                                "Footer lines dropped from the end of each file."),
                        FieldSpec.of("delimited.skip_tail_columns", "Skip trailing columns", FieldType.INT,
                                "Phantom columns stripped from the right of each record."),
                        FieldSpec.of("encoding", "Encoding", FieldType.STRING, "Character encoding (default UTF-8)."),
                        // Types & columns
                        FieldSpec.of("delimited.date_formats", "Date formats", FieldType.LIST,
                                "Accepted DATE parse patterns, tried in order."),
                        FieldSpec.of("delimited.timestamp_formats", "Timestamp formats", FieldType.LIST,
                                "Accepted TIMESTAMP parse patterns, tried in order."),
                        FieldSpec.of("delimited.null_strings", "Null strings", FieldType.LIST,
                                "Literal text values read as NULL, e.g. ['', 'NULL', 'N/A']."),
                        // Robustness
                        FieldSpec.of("delimited.strict_mode", "Strict mode (RFC-4180)", FieldType.BOOL,
                                "Blank = engine default (true). false tolerates quote/column drift."),
                        FieldSpec.enumField("delimited.engine", "Parse engine",
                                List.of("auto", "duckdb", "java"), "auto",
                                "duckdb = native reader; java = univocity fallback for messy files."),
                        FieldSpec.of("delimited.include_prefixes", "Include rows: prefixes", FieldType.LIST,
                                "Keep only rows whose filter target column starts with any of these."),
                        FieldSpec.of("delimited.include_regex", "Include rows: regex", FieldType.LIST,
                                "Keep only rows whose filter target column matches any of these."),
                        FieldSpec.of("delimited.exclude_prefixes", "Exclude rows: prefixes", FieldType.LIST,
                                "Drop rows whose filter target column starts with any of these."),
                        FieldSpec.of("delimited.exclude_regex", "Exclude rows: regex", FieldType.LIST,
                                "Drop rows whose filter target column matches any of these."),
                        FieldSpec.withDefault("delimited.filter_target_column", "Filter target column",
                                FieldType.INT, 0, "0-based physical column the include/exclude filters apply to."),
                        FieldSpec.of("delimited.where", "Row filter (SQL)", FieldType.SQL,
                                "Post-parse SQL predicate over the mapped, typed columns, e.g. amount > 0."),
                        // Files
                        FieldSpec.of("compression", "Input compression", FieldType.STRING,
                                "auto / gzip / zstd / none — decompressed inline at read."))),
                new Builtin("fixedwidth", "Fixed width — positional slices carved from each line", List.of(
                        FieldSpec.required("fixedwidth.fields", "Fields", FieldType.LIST,
                                "Positional slices: one {name, start, length} per field."),
                        FieldSpec.withDefault("delimited.has_header", "First line is a header", FieldType.BOOL, true,
                                "Header/banner line to skip before the records."),
                        FieldSpec.of("fixedwidth.min_record_length", "Minimum record length", FieldType.INT,
                                "Shorter lines (footers, blanks) are dropped. Absent = the widest field end."),
                        FieldSpec.enumField("fixedwidth.trim", "Trim fields",
                                List.of("BOTH", "LEFT", "RIGHT", "NONE"), "BOTH", "Whitespace trim per slice."),
                        FieldSpec.of("encoding", "Encoding", FieldType.STRING, "Character encoding (default UTF-8)."),
                        FieldSpec.of("compression", "Input compression", FieldType.STRING, "e.g. gzip."))),
                new Builtin("json", "JSON — NDJSON (one object per line) or a JSON array document", List.of(
                        FieldSpec.enumField("json.format", "Document shape",
                                List.of("newline", "array", "auto"), "newline",
                                "NDJSON, one JSON array of records, or auto-detect."),
                        FieldSpec.of("delimited.skip_header_lines", "Skip leading lines", FieldType.INT,
                                "Non-JSON preamble lines before the records."),
                        // J1 reader knobs — read_json (array/auto) only; the config load refuses them
                        // under format: newline, whose line reader already routes malformed lines away.
                        FieldSpec.withDefault("json.ignore_errors", "Keep malformed records as NULL rows",
                                FieldType.BOOL, false,
                                "auto only, and only when the file's content is itself line-delimited "
                                        + "(DuckDB's own sniff): a malformed record then lands as an "
                                        + "all-NULL row. Refused under format: array, which DuckDB always "
                                        + "rejects this option for."),
                        FieldSpec.of("json.maximum_object_size", "Maximum object size (bytes)", FieldType.INT,
                                "array/auto only: bound on a single document/record the reader buffers."),
                        FieldSpec.of("compression", "Input compression", FieldType.STRING, "e.g. gzip."))),
                new Builtin("xlsx", "MS Excel — read_xlsx over a workbook (DuckDB excel extension)", List.of(
                        FieldSpec.of("xlsx.sheet", "Sheet", FieldType.STRING,
                                "Sheet NAME to read; empty = the workbook's first sheet."),
                        FieldSpec.of("xlsx.range", "Cell range", FieldType.STRING,
                                "A1-style anchor or span (B2 or A1:F100); empty = the whole sheet."),
                        FieldSpec.withDefault("xlsx.header", "First row is a header", FieldType.BOOL, true,
                                "Header cells name the columns; without one, columns are A, B, C…"),
                        FieldSpec.withDefault("xlsx.stop_at_empty", "Stop at first empty row", FieldType.BOOL, false,
                                "Forced on by the extension when no explicit range is given."),
                        FieldSpec.withDefault("xlsx.ignore_errors", "Ignore cell errors", FieldType.BOOL, false,
                                "Unrepresentable cells land as NULL instead of failing the file."),
                        FieldSpec.withDefault("xlsx.normalize_names", "Normalize header names", FieldType.BOOL, false,
                                "Lower-snake identifiers from the header cells."))),
                new Builtin("text_regex", "Text / regex — named capture groups over matching lines", List.of(
                        FieldSpec.required("text_regex.pattern", "Pattern", FieldType.STRING,
                                "At least one named capture group — group names become the columns; "
                                        + "non-matching lines are dropped."),
                        FieldSpec.of("delimited.skip_header_lines", "Skip leading lines", FieldType.INT,
                                "Preamble lines before the records."),
                        FieldSpec.of("encoding", "Encoding", FieldType.STRING, "Character encoding (default UTF-8)."))));
    }

    /** One built-in: id + label + grammar schema; preview via the engine's own frontend reads. */
    private record Builtin(String id, String label, List<FieldSpec> grammarSchema) implements ParserPlugin {

        @Override
        public boolean hierarchical() {
            return false;
        }

        @Override
        public ParseResult preview(byte[] sample, Map<String, Object> grammar) throws Exception {
            Map<String, Object> parsing = new LinkedHashMap<>(grammar == null ? Map.of() : grammar);
            parsing.put("frontend", id);
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("name", "parser_preview");
            // The parser requires dirs (poll + database) and processing sections; the preview is
            // scratch-only — ComponentPreview writes to temp files exclusively, so these paths are
            // never touched and the processing knobs never run.
            draft.put("dirs", Map.of("poll", "preview/inbox", "database", "preview/database"));
            draft.put("processing", Map.of("threads", 1));
            draft.put("parsing", parsing);
            PipelineConfig cfg = PipelineConfig.fromMap(draft);
            // xlsx is BINARY: the sample stays bytes (sample_b64 transport) and never round-trips
            // through a charset — the same rule the ASN.1 plugin follows.
            ComponentPreview.GrammarResult r = "xlsx".equals(id)
                    ? ComponentPreview.parsingXlsx(cfg, sample)
                    : ComponentPreview.parsing(cfg, new String(sample, charsetOf(grammar)));
            return new ParseResult.Table(r.columns(), r.rows(), r.rowCount(), r.rejectedRows(), r.columnTypes());
        }
    }

    /** The grammar's declared {@code encoding}, defaulting to UTF-8; unknown names are a 422-able error. */
    private static Charset charsetOf(Map<String, Object> grammar) {
        Object enc = grammar == null ? null : grammar.get("encoding");
        String name = enc == null ? "" : String.valueOf(enc).trim();
        if (name.isEmpty()) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            throw new IllegalArgumentException("unknown encoding: " + name);
        }
    }
}
