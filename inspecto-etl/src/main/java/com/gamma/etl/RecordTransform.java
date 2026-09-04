package com.gamma.etl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The <b>Record Transformer</b> function catalog and compiler — the Java mirror of
 * {@code inspecto-ui/src/app/modules/admin/pipelines/sql-functions.ts}, and the second input spelling
 * {@link DataTransformer#dataColumns} understands beside {@code mapping.rules[]}.
 *
 * <p><b>Why this exists.</b> Until 2026-09-04 a {@code transform.sql} node's {@code fields[]} was an
 * authoring artifact the engine never read: SQL generation lived only in TypeScript, so the engine could
 * consume a node's stored {@code sql} string and nothing else. That made Record Transformer unable to
 * occupy the projection slot {@code transform.map} owns, because running an opaque author string there
 * would lose both of the properties the mapping provides — schema-driven compilation with the pipeline's
 * date/timestamp format lists, and coverage by the cast-failure audit.
 *
 * <p>⛔ <b>So {@code fields[]} is now ENGINE-READ.</b> Four comments used to say the opposite and have
 * moved with this change. A field row is {@code {id, name, from, fn, args}}; {@code id} is grid identity
 * and is never read here.
 *
 * <p><b>Forgiving by construction</b>, exactly as the TS catalog promises: every cast is
 * {@code TRY_CAST}, every division guards with {@code NULLIF}, and text→date goes through
 * {@code TRY_STRPTIME}. A bad cell nulls one cell rather than killing the batch — the semantic
 * {@link DataTransformer#countCastFailures} counts against.
 *
 * <p>🔴 <b>This file and {@code sql-functions.ts} must not drift.</b> {@code RecordTransformContractTest}
 * pins them: it writes {@code sql-functions.contract.json} from this catalog
 * ({@code -Drecord.transform.write=true}) and a vitest spec asserts the TS catalog matches. A silent
 * divergence would compile a field one way in the browser preview and another way in the engine.
 */
public final class RecordTransform {

    private RecordTransform() {}

    /** How a parameter renders into SQL — this drives the ESCAPING, so it is not cosmetic. */
    public enum ParamType {
        /** A column of the input relation → quoted identifier. */
        COLUMN,
        /** Free text → single-quoted literal, embedded quotes doubled. */
        TEXT,
        /** A number → verbatim after validation; non-numeric is a row problem. */
        NUMBER,
        /** One of {@code options} → verbatim (SQL keywords/types, never user text). */
        ENUM,
        /** Raw SQL → verbatim. Only the deliberate escape hatch uses this. */
        SQL;

        String wire() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** One parameter of a catalog function, beyond the row's source column. */
    public record Param(String name, String label, ParamType type, String defaultValue,
                        List<String> options, String placeholder, boolean optional) {
        public Param {
            options = options == null ? List.of() : List.copyOf(options);
        }
        static Param of(String name, String label, ParamType type) {
            return new Param(name, label, type, null, List.of(), null, false);
        }
        Param withDefault(String d)          { return new Param(name, label, type, d, options, placeholder, optional); }
        Param withOptions(String... o)       { return new Param(name, label, type, defaultValue, List.of(o), placeholder, optional); }
        Param withPlaceholder(String p)      { return new Param(name, label, type, defaultValue, options, p, optional); }
        Param asOptional()                   { return new Param(name, label, type, defaultValue, options, placeholder, true); }
    }

    /** One function a Fields row may apply to its source column. */
    public record Fn(String id, String label, String category, String template,
                     List<Param> params, String help) {
        public Fn {
            params = params == null ? List.of() : List.copyOf(params);
        }
        /** True when the template reads the row's source column. */
        public boolean usesSource() { return template.contains("{source}"); }
    }

    /** DuckDB types offered wherever a type must be chosen, in the order a business user thinks of them. */
    public static final List<String> SQL_TYPES =
            List.of("VARCHAR", "BIGINT", "DOUBLE", "DECIMAL(18,2)", "BOOLEAN", "DATE", "TIMESTAMP", "TIME");

    private static final String[] DATE_PARTS =
            {"YEAR", "QUARTER", "MONTH", "WEEK", "DAY", "HOUR", "MINUTE", "SECOND"};
    private static final String[] COMPARISONS = {"=", "<>", ">", ">=", "<", "<=", "LIKE"};

    /** The id of the pass-through function — the {@code DIRECT} analogue. See {@link #compile}. */
    public static final String KEEP = "keep";
    /** The id of the raw-SQL escape hatch — the {@code EXPR} analogue, and unauditable for the same reason. */
    public static final String CUSTOM = "custom";

    /**
     * The catalog, in the TS file's declaration order (the contract compares order too).
     *
     * <p>⚠ Adding an entry is a product decision, not a dev convenience: every entry becomes a permanent
     * option in front of a non-technical author. Prefer one parameterised function over three fixed ones.
     */
    public static final List<Fn> SQL_FUNCTIONS = List.of(
            // ── Keep ──────────────────────────────────────────────────────────────────────────────────
            new Fn(KEEP, "Keep as it is", "Keep", "{source}", List.of(),
                    "The value is copied through unchanged."),

            // ── Text ──────────────────────────────────────────────────────────────────────────────────
            new Fn("text.trim", "Remove spaces around the value", "Text", "TRIM({source})", List.of(),
                    "Leading and trailing spaces are removed. \" Anna \" becomes \"Anna\"."),
            new Fn("text.upper", "Make UPPERCASE", "Text", "UPPER({source})", List.of(), null),
            new Fn("text.lower", "Make lowercase", "Text", "LOWER({source})", List.of(), null),
            new Fn("text.replace", "Replace text", "Text", "REPLACE({source}, {find}, {replacement})",
                    List.of(Param.of("find", "Find", ParamType.TEXT).withPlaceholder("-"),
                            Param.of("replacement", "Replace with", ParamType.TEXT).withDefault("")
                                    .asOptional().withPlaceholder("leave blank to delete")),
                    null),
            new Fn("text.substring", "Take part of the text", "Text", "SUBSTRING({source}, {start}, {length})",
                    List.of(Param.of("start", "Start at character", ParamType.NUMBER).withDefault("1"),
                            Param.of("length", "How many characters", ParamType.NUMBER).withDefault("10")),
                    "Counting starts at 1."),
            new Fn("text.split_part", "Take one part after splitting", "Text",
                    "SPLIT_PART({source}, {delimiter}, {index})",
                    List.of(Param.of("delimiter", "Split on", ParamType.TEXT).withDefault(","),
                            Param.of("index", "Which part", ParamType.NUMBER).withDefault("1")),
                    null),
            new Fn("text.join", "Join with another column", "Text", "CONCAT({source}, {separator}, {other})",
                    List.of(Param.of("separator", "Separator", ParamType.TEXT).withDefault(" "),
                            Param.of("other", "Other column", ParamType.COLUMN)),
                    null),
            new Fn("text.pad_left", "Pad on the left", "Text", "LPAD({source}, {length}, {pad})",
                    List.of(Param.of("length", "Total length", ParamType.NUMBER).withDefault("10"),
                            Param.of("pad", "Pad with", ParamType.TEXT).withDefault("0")),
                    "Useful for account or reference numbers that lost their leading zeros."),

            // ── Numbers ───────────────────────────────────────────────────────────────────────────────
            new Fn("num.round", "Round", "Numbers", "ROUND({source}, {decimals})",
                    List.of(Param.of("decimals", "Decimal places", ParamType.NUMBER).withDefault("0")), null),
            new Fn("num.multiply", "Multiply by", "Numbers", "({source} * {factor})",
                    List.of(Param.of("factor", "Factor", ParamType.NUMBER).withDefault("100")),
                    "Multiplying by 100 turns an amount into cents."),
            new Fn("num.divide", "Divide by", "Numbers", "({source} / NULLIF({divisor}, 0))",
                    List.of(Param.of("divisor", "Divide by", ParamType.NUMBER).withDefault("100")),
                    "Dividing by zero gives an empty value rather than failing the batch."),
            new Fn("num.abs", "Absolute value", "Numbers", "ABS({source})", List.of(), null),

            // ── Dates ─────────────────────────────────────────────────────────────────────────────────
            new Fn("date.parse", "Read text as a date", "Dates", "TRY_STRPTIME({source}, {format})",
                    List.of(Param.of("format", "Format it is written in", ParamType.TEXT).withDefault("%d/%m/%Y")),
                    "%d day, %m month, %Y four-digit year. 15/03/2024 is %d/%m/%Y."),
            new Fn("date.format", "Write a date as text", "Dates", "STRFTIME({source}, {format})",
                    List.of(Param.of("format", "Format to write", ParamType.TEXT).withDefault("%Y-%m-%d")), null),
            new Fn("date.part", "Take part of a date", "Dates", "EXTRACT({part} FROM {source})",
                    List.of(Param.of("part", "Part", ParamType.ENUM).withOptions(DATE_PARTS).withDefault("YEAR")),
                    null),
            new Fn("date.truncate", "Start of the period", "Dates", "DATE_TRUNC({unit}, {source})",
                    List.of(Param.of("unit", "Period", ParamType.TEXT).withDefault("month")),
                    "A date in March with period \"month\" becomes the 1st of March."),

            // ── Logic ─────────────────────────────────────────────────────────────────────────────────
            new Fn("logic.default_if_empty", "Use a default when empty", "Logic",
                    "COALESCE(NULLIF({source}, ''), {fallback})",
                    List.of(Param.of("fallback", "Default value", ParamType.TEXT).withDefault("unknown")), null),
            new Fn("logic.if_then_else", "If … then … otherwise …", "Logic",
                    "CASE WHEN {source} {comparison} {value} THEN {then} ELSE {otherwise} END",
                    List.of(Param.of("comparison", "Is", ParamType.ENUM).withOptions(COMPARISONS).withDefault("="),
                            Param.of("value", "This value", ParamType.TEXT).withPlaceholder("shipped"),
                            Param.of("then", "Then use", ParamType.TEXT).withPlaceholder("Y"),
                            Param.of("otherwise", "Otherwise use", ParamType.TEXT).withPlaceholder("N")),
                    null),

            // ── Convert ───────────────────────────────────────────────────────────────────────────────
            new Fn("convert.type", "Change the type", "Convert", "TRY_CAST({source} AS {type})",
                    List.of(Param.of("type", "New type", ParamType.ENUM)
                            .withOptions(SQL_TYPES.toArray(new String[0])).withDefault("VARCHAR")),
                    "A value that cannot be converted becomes empty; the row is kept."),

            // ── Custom ────────────────────────────────────────────────────────────────────────────────
            new Fn(CUSTOM, "Write my own expression", "Custom", "{expression}",
                    List.of(Param.of("expression", "SQL expression", ParamType.SQL)
                            .withPlaceholder("ROUND(amount * 100)")),
                    "Written into the SQL exactly as typed. Column names are used as they appear in the input."));

    private static final Map<String, Fn> BY_ID = new LinkedHashMap<>();
    static {
        for (Fn f : SQL_FUNCTIONS) BY_ID.put(f.id(), f);
    }

    /** The catalog entry for an id, or {@code null} when a stored field names a function this build removed. */
    public static Fn function(String id) { return BY_ID.get(id); }

    // ── rendering (mirrors sql-functions.ts § rendering) ─────────────────────────────────────────────

    private static final Pattern PLAIN_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern NUMERIC     = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    /** Double-quote an identifier unless it is already plain — parsed headers carry spaces and punctuation. */
    public static String quoteIdentifier(String name) {
        return PLAIN_IDENT.matcher(name).matches() ? name : '"' + name.replace("\"", "\"\"") + '"';
    }

    /** Single-quote a SQL string literal, doubling any embedded quote. */
    public static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Render one parameter value per its declared type, or {@code null} when unusable.
     *
     * <p>⚠ A {@code TEXT} parameter is deliberately NOT trimmed: a single space is a legitimate separator
     * for "Join with another column", and trimming it reported a valid row as missing its separator.
     * Every other type is whitespace-insensitive and IS trimmed before validating.
     */
    static String renderParam(Param param, String raw) {
        String supplied = raw != null ? raw : (param.defaultValue() != null ? param.defaultValue() : "");
        if (param.type() == ParamType.TEXT) {
            if (supplied.isEmpty()) return param.optional() ? quoteLiteral("") : null;
            return quoteLiteral(supplied);
        }
        String value = supplied.trim();
        if (value.isEmpty()) return null;
        return switch (param.type()) {
            case COLUMN -> quoteIdentifier(value);
            case NUMBER -> NUMERIC.matcher(value).matches() ? value : null;
            case ENUM   -> param.options().contains(value) ? value : null;
            case SQL    -> value;
            case TEXT   -> null;   // handled above
        };
    }

    /** The outcome of compiling one field: an expression, or the first parameter problem. */
    public record Rendered(String expr, String problem) {
        public boolean ok() { return expr != null; }
    }

    /**
     * The SQL expression a field compiles to, unaliased — or a problem naming the first parameter that is
     * missing or malformed. Mirrors {@code renderExpression} in the TS catalog, including its messages.
     */
    public static Rendered renderExpression(Fn fn, String source, Map<String, String> args) {
        String src = source == null ? "" : source;
        if (fn.usesSource() && src.trim().isEmpty())
            return new Rendered(null, "Pick the column this field reads.");
        String out = fn.template().replace("{source}", quoteIdentifier(src.trim()));
        for (Param p : fn.params()) {
            String rendered = renderParam(p, args == null ? null : args.get(p.name()));
            if (rendered == null) {
                String expected = switch (p.type()) {
                    case NUMBER -> "“" + p.label() + "” needs a number.";
                    case ENUM   -> "Choose a value for “" + p.label() + "”.";
                    default     -> "“" + p.label() + "” is required.";
                };
                return new Rendered(null, expected);
            }
            out = out.replace("{" + p.name() + "}", rendered);
        }
        return new Rendered(out, null);
    }

    // ── compilation into the shared [{name, expr}] seam ──────────────────────────────────────────────

    /**
     * Compile a {@code fields[]} list into the ordered {@code [{name, expr}]} shape
     * {@link DataTransformer#dataColumns} returns — the one seam both execution lanes read
     * ({@code DataTransformer.selectFor} on ingest, {@code RowShaper.columnsOf} at rest).
     *
     * <p>🔴 <b>{@code keep} means different things per lane, and this is the whole subtlety.</b> The TS
     * template for {@code keep} is a bare {@code {source}} pass-through, which is right at rest where the
     * input is already typed. On the INGEST lane the raw relation is deliberately ALL-VARCHAR (so the two
     * ingest engines agree on types), so a bare pass-through would land every column as text. When
     * {@code typedSource} is false a {@code keep} row therefore compiles through
     * {@link SchemaFieldTypes#castSql} against its declared type — byte-identical to what a {@code DIRECT}
     * mapping rule emits today, which is what lets the migration assert equality.
     *
     * @param fields      the node's {@code fields[]}, each {@code {name, from, fn, args}}
     * @param fieldTypes  declared {@code name → type} from {@code raw.fields[]}; empty at rest
     * @param csv         format lists for the DATE/TIMESTAMP coercion chain
     * @param zones       per-column source zones
     * @param sourceTable the relation the columns read from
     * @param typedSource false ⇒ the source is the raw ALL-VARCHAR table and {@code keep} must cast
     * @throws IllegalArgumentException naming the field when a row cannot compile
     */
    public static List<Map<String, Object>> compile(List<Map<String, Object>> fields,
                                                    Map<String, String> fieldTypes,
                                                    PipelineConfig.CsvSettings csv,
                                                    SourceZones zones,
                                                    String sourceTable,
                                                    boolean typedSource) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            String name = str(field, "name");
            String from = str(field, "from");
            String fnId = str(field, "fn");
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("a Record Transformer field has no name");

            Fn fn = function(fnId == null || fnId.isBlank() ? KEEP : fnId);
            if (fn == null)
                throw new IllegalArgumentException("Unknown Record Transformer function '" + fnId
                        + "' for field '" + name + "'. Valid: " + BY_ID.keySet());

            String expr;
            if (KEEP.equals(fn.id()) && !typedSource) {
                // The DIRECT analogue on the raw table: cast to the declared type, forgivingly.
                if (from == null || from.isBlank())
                    throw new IllegalArgumentException("field '" + name + "' keeps a column but names none");
                String col = "\"" + sourceTable + "\".\"" + from + '"';
                String type = fieldTypes.getOrDefault(from, SchemaFieldTypes.VARCHAR);
                expr = SchemaFieldTypes.castSql(col, type, csv.dateFormats(), csv.tsFormats(),
                        zones.zoneArg(from, sourceTable));
            } else {
                Rendered r = renderExpression(fn, from, args(field));
                if (!r.ok())
                    throw new IllegalArgumentException("field '" + name + "': " + r.problem());
                expr = r.expr();
            }
            out.add(Map.of("name", name, "expr", expr));
        }
        return out;
    }

    /**
     * The source column whose non-blankness the cast-failure audit tests for this field, or {@code null}
     * when the field is not measurable.
     *
     * <p>⛔ Not measurable, for the same reason {@code EXPR} is excluded from the mapping audit: a
     * {@link #CUSTOM} row's expression is author-owned SQL rather than a column reference, so "the source
     * was non-blank" has no defined meaning and counting it would invent a denominator. A function that
     * does not read {@code {source}} at all is excluded for the same reason.
     *
     * <p>⚠ Also excluded, matching {@code DataTransformer.coercedSourceColumn}: a column whose DECLARED
     * type is VARCHAR, because a pass-through cannot null out. The lookup is keyed by the SOURCE column,
     * not the target.
     *
     * <p>⚠ Known narrowing: {@code from} is a single column, so a two-source function
     * (e.g. {@code text.join}) tests only its primary input for non-blankness.
     */
    public static String auditedSourceColumn(Map<String, Object> field, Map<String, String> fieldTypes) {
        String fnId = str(field, "fn");
        Fn fn = function(fnId == null || fnId.isBlank() ? KEEP : fnId);
        if (fn == null || CUSTOM.equals(fn.id()) || !fn.usesSource()) return null;
        String from = str(field, "from");
        if (from == null || from.isBlank()) return null;
        String declared = fieldTypes.getOrDefault(from, SchemaFieldTypes.VARCHAR);
        return SchemaFieldTypes.coerces(declared) ? from : null;
    }

    /** The catalog as plain maps, for the committed contract the TS suite asserts against. */
    public static List<Map<String, Object>> toContract() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Fn f : SQL_FUNCTIONS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id());
            m.put("label", f.label());
            m.put("category", f.category());
            m.put("template", f.template());
            List<Map<String, Object>> ps = new ArrayList<>();
            for (Param p : f.params()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.name());
                pm.put("label", p.label());
                pm.put("type", p.type().wire());
                if (p.defaultValue() != null) pm.put("default", p.defaultValue());
                if (!p.options().isEmpty())   pm.put("options", p.options());
                if (p.placeholder() != null)  pm.put("placeholder", p.placeholder());
                if (p.optional())             pm.put("optional", true);
                ps.add(pm);
            }
            if (!ps.isEmpty()) m.put("params", ps);
            if (f.help() != null) m.put("help", f.help());
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> args(Map<String, Object> field) {
        Object a = field.get("args");
        if (!(a instanceof Map<?, ?> m)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet())
            if (e.getValue() != null) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
