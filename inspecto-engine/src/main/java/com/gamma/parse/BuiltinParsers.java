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
 * The four built-in {@link ParserPlugin}s — thin self-description adapters over the engine's own
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

    private static final Set<String> IDS = Set.of("delimited", "fixedwidth", "json", "text_regex");

    private BuiltinParsers() {}

    static boolean isBuiltin(String id) {
        return IDS.contains(id);
    }

    static List<ParserPlugin> all() {
        return List.of(
                new Builtin("delimited", "Delimited — CSV / TSV / pipe, one record per line", List.of(
                        FieldSpec.withDefault("delimited.delimiter", "Delimiter", FieldType.STRING, ",",
                                "Column separator character."),
                        FieldSpec.withDefault("delimited.has_header", "First line is a header", FieldType.BOOL, true,
                                "Whether line one carries the column names."),
                        FieldSpec.of("delimited.skip_header_lines", "Skip leading lines", FieldType.INT,
                                "Banner/preamble lines before the data (and header)."),
                        FieldSpec.of("delimited.null_strings", "Null strings", FieldType.STRING,
                                "Values read as NULL; comma-separate multiple."),
                        FieldSpec.of("encoding", "Encoding", FieldType.STRING, "Character encoding (default UTF-8)."),
                        FieldSpec.of("compression", "Input compression", FieldType.STRING, "e.g. gzip."))),
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
                        FieldSpec.of("compression", "Input compression", FieldType.STRING, "e.g. gzip."))),
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
            String text = new String(sample, charsetOf(grammar));
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
            ComponentPreview.GrammarResult r = ComponentPreview.parsing(cfg, text);
            return new ParseResult.Table(r.columns(), r.rows(), r.rowCount(), r.rejectedRows());
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
