package com.gamma.etl;

import com.gamma.util.SqlBuilder;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure SQL-expression compiler for {@link DataTransformer}. Turns a single mapping
 * rule (or partition definition) into the DuckDB scalar expression that produces one
 * output column — <em>without</em> the {@code AS "alias"} suffix, which the caller adds.
 *
 * <h3>Why a separate seam</h3>
 * The transform vocabulary ({@code DIRECT}, {@code CONCAT_DT}, {@code FILENAME_DATE})
 * was previously an inline {@code if/else} chain inside {@code DataTransformer.materialize}.
 * Modelling each transform type as a {@link ColumnRule} function in a lookup
 * {@link #DATA_RULES registry} keeps {@code materialize} a thin SELECT-assembler and
 * makes a new transform type a one-line registry addition (functional injection) rather
 * than another branch in a growing switch.
 *
 * <h3>Behaviour parity</h3>
 * Every method here emits the byte-identical SQL the inline code produced — it reuses the
 * same {@link SqlBuilder} calls in the same order. Note the deliberate asymmetry preserved
 * from the original: data columns wrap DATE/TIMESTAMP sources in {@code CAST(col AS VARCHAR)}
 * before the {@code TRY_STRPTIME} chain (so an already-typed plugin column is re-stringified),
 * whereas partition columns route through {@link SqlBuilder#buildCastExpr}. The two paths are
 * intentionally distinct.
 */
public final class TransformCompiler {

    private TransformCompiler() {}

    /**
     * Compiles one mapping rule into a column expression. Implementations receive the
     * already-extracted {@code sourceExpression} / {@code targetColumn} plus the schema's
     * field-type map and the source table name.
     */
    @FunctionalInterface
    public interface ColumnRule {
        String compile(String source, String target, Map<String, String> fieldTypes,
                       String sourceTable, PipelineConfig.CsvSettings csv);
    }

    /**
     * transformType → expression compiler. {@code DIRECT} (and a blank/omitted type) is handled
     * directly by {@link #dataColumn} via {@link #direct}; any other non-blank value not in this
     * map is rejected.
     */
    private static final Map<String, ColumnRule> DATA_RULES = Map.of(
            "CONCAT_DT",     TransformCompiler::concatDt,
            "FILENAME_DATE", TransformCompiler::filenameDate,
            "EXPR",          TransformCompiler::expr
    );

    /**
     * The transform-type vocabulary this compiler accepts, upper-case, including the implicit
     * {@code DIRECT} (which {@link #dataColumn} handles before the registry lookup). Exposed so an
     * authoring-time validator can reject a typo against the SAME set the runtime enforces at
     * {@link #dataColumn} instead of restating it and drifting.
     */
    public static final Set<String> TRANSFORM_TYPES = transformTypes();

    private static Set<String> transformTypes() {
        Set<String> s = new TreeSet<>(DATA_RULES.keySet());
        s.add("DIRECT");
        return Collections.unmodifiableSet(s);
    }

    // ── data columns ────────────────────────────────────────────────────────────

    /**
     * Build the expression for one mapping rule (no {@code AS} alias). The {@code transformType}
     * is optional and case-insensitive: <b>blank or omitted means {@code DIRECT}</b>. Recognised
     * types are {@code DIRECT}, {@code EXPR}, {@code CONCAT_DT}, {@code FILENAME_DATE}; any other
     * non-blank value is rejected with an {@link IllegalArgumentException} so a typo (e.g.
     * {@code EXPER}) fails fast instead of silently degrading to a pass-through.
     */
    public static String dataColumn(Map<String, String> rule, Map<String, String> fieldTypes,
                                    String sourceTable, PipelineConfig.CsvSettings csv) {
        String source = rule.get("sourceExpression");
        String target = rule.get("targetColumn");
        String type   = rule.get("transformType");
        String norm   = type == null ? "" : type.trim().toUpperCase();

        if (norm.isEmpty() || norm.equals("DIRECT"))   // blank / omitted / DIRECT → pass-through cast
            return direct(source, target, fieldTypes, sourceTable, csv);

        ColumnRule r = DATA_RULES.get(norm);
        if (r == null)
            throw new IllegalArgumentException(
                    "Unknown transformType '" + type + "' for target column '" + target
                    + "'. Valid: DIRECT (or leave blank), EXPR, CONCAT_DT, FILENAME_DATE.");
        return r.compile(source, target, fieldTypes, sourceTable, csv);
    }

    private static String direct(String source, String target, Map<String, String> fieldTypes,
                                 String sourceTable, PipelineConfig.CsvSettings csv) {
        String col  = "\"" + sourceTable + "\".\"" + source + '"';
        // The honoured vocabulary and the SQL each type compiles to live in ONE place
        // (SchemaFieldTypes) — this was a four-branch switch whose `default` emitted the column
        // UNCAST, so a declared BIGINT silently produced text. Unhonoured types are now refused at
        // config load, so every type reaching here casts.
        String type = fieldTypes.getOrDefault(source, SchemaFieldTypes.VARCHAR);
        return SchemaFieldTypes.castSql(col, type, csv.dateFormats(), csv.tsFormats());
    }

    /**
     * EXPR: the {@code sourceExpression} <em>is</em> a DuckDB scalar expression, emitted verbatim.
     * Unqualified column references resolve against the single source table ({@code raw_input}),
     * giving access to DuckDB's full scalar-function library (e.g. {@code UPPER(TRIM(col))},
     * {@code TRY_CAST(amt AS DOUBLE) / 100}, {@code CASE WHEN … END}). The author owns validity and
     * any explicit cast; it must stay a <b>per-row scalar</b> expression — no aggregates or joins,
     * which belong to Stage-2 enrichment. Schema config is operator-authored and trusted (same model
     * as the Stage-2 transform SQL), so the expression is not sandbox-validated.
     */
    private static String expr(String source, String target, Map<String, String> fieldTypes,
                               String sourceTable, PipelineConfig.CsvSettings csv) {
        return source;
    }

    private static String concatDt(String source, String target, Map<String, String> fieldTypes,
                                   String sourceTable, PipelineConfig.CsvSettings csv) {
        String[] parts  = source.split("\\|", 2);
        String dateCol  = "\"" + sourceTable + "\".\"" + parts[0] + '"';
        String timeCol  = "\"" + sourceTable + "\".\"" + parts[1] + '"';
        StringBuilder sb = new StringBuilder();
        SqlBuilder.appendCoalesce(sb,
                dateCol + " || ' ' || " + timeCol, csv.tsFormats(), "TIMESTAMP");
        return sb.toString();
    }

    private static String filenameDate(String source, String target, Map<String, String> fieldTypes,
                                       String sourceTable, PipelineConfig.CsvSettings csv) {
        if (!"EVENT_DATE".equals(target)) {
            throw new IllegalArgumentException(
                    "FILENAME_DATE transform is only supported for the EVENT_DATE column, got: " + target);
        }
        String[] parts  = source.split("\\|", 3);
        String   col    = "\"" + sourceTable + "\".\"" + parts[0] + '"';
        String   prefix = parts.length > 1 ? parts[1] : "";
        String   fmt    = parts.length > 2 ? parts[2] : "%Y%m%d";
        return "TRY_STRPTIME(regexp_extract(" + col + ", '" + prefix
                + "([0-9]{8})', 1), '" + fmt + "')::DATE";
    }

    // ── partition columns ─────────────────────────────────────────────────────────

    /**
     * The internal event-time column {@link #eventTimeColumn} materialises on the transformed relation
     * (consignment addressing §3.1). Internal like {@code __src_id}: excluded from every written output, so it
     * exists only for the duration of the write that computes bounds from it.
     */
    public static final String EVENT_TIME_COL = "__event_time";

    /**
     * Build the expression for one partition column (no {@code AS} alias).
     *
     * <p>{@code DATE_YEAR}/{@code MONTH}/{@code DAY} stringify the source column and parse it with the
     * format list that matches the source field's <em>declared type</em>: a {@code TIMESTAMP} source
     * uses {@code timestamp_formats}, everything else ({@code VARCHAR}/{@code DATE}) uses
     * {@code date_formats}. This matters because a {@code TIMESTAMP} value rendered to text carries a
     * time component that a date-only format cannot match — so a date-only parse would yield {@code NULL}
     * and send every row to the {@code 1900/01/01} sentinel partition. {@code YEAR}/{@code MONTH}/
     * {@code DAY} accept both {@code DATE} and {@code TIMESTAMP}, so the extracted component is correct
     * either way.
     */
    public static String partitionColumn(PartitionDef pd, String sourceTable,
                                         Map<String, String> fieldTypes, PipelineConfig.CsvSettings csv) {
        String col = "\"" + sourceTable + "\".\"" + pd.source() + "\"";
        StringBuilder sb = new StringBuilder();
        switch (pd.type()) {
            case VARCHAR -> sb.append(col);
            case DOUBLE  -> sb.append("TRY_CAST(").append(col).append(" AS DOUBLE)");
            case INTEGER -> sb.append("TRY_CAST(").append(col).append(" AS INTEGER)");
            case DATE_YEAR, DATE_MONTH, DATE_DAY -> {
                String dateExpr = dateExpr(pd, sourceTable, fieldTypes, csv);
                switch (pd.type()) {
                    case DATE_YEAR  -> sb.append("YEAR(").append(dateExpr).append(")::VARCHAR");
                    case DATE_MONTH -> sb.append("LPAD(MONTH(").append(dateExpr)
                                         .append(")::VARCHAR, 2, '0')");
                    case DATE_DAY   -> sb.append("LPAD(DAY(").append(dateExpr)
                                         .append(")::VARCHAR, 2, '0')");
                    default -> throw new AssertionError();
                }
            }
        }
        return sb.toString();
    }

    /**
     * The coerced date/timestamp expression a {@code DATE_*} partition def extracts its component from.
     *
     * <p>Parse with the format list matching the source's declared type: {@code timestamp_formats} for a
     * {@code TIMESTAMP} source (its text has a time component), {@code date_formats} otherwise. This matters
     * because a {@code TIMESTAMP} value rendered to text carries a time component that a date-only format
     * cannot match — so a date-only parse would yield {@code NULL} and send every row to the
     * {@code 1900/01/01} sentinel partition.
     */
    private static String dateExpr(PartitionDef pd, String sourceTable,
                                   Map<String, String> fieldTypes, PipelineConfig.CsvSettings csv) {
        String col         = "\"" + sourceTable + "\".\"" + pd.source() + "\"";
        String srcType     = SchemaFieldTypes.normalize(fieldTypes.getOrDefault(pd.source(), SchemaFieldTypes.VARCHAR));
        // A TIMESTAMPTZ source carries a time component exactly like TIMESTAMP, so it must parse
        // with timestamp_formats too — a date-only parse would NULL every row into the sentinel.
        String castType    = ("TIMESTAMP".equals(srcType) || "TIMESTAMPTZ".equals(srcType)) ? "TIMESTAMP" : "DATE";
        String varcharExpr = "CAST(" + col + " AS VARCHAR)";
        return SqlBuilder.buildCastExpr(varcharExpr, castType, csv.dateFormats(), csv.tsFormats());
    }

    /**
     * The <b>event-time</b> expression for a date-typed partition def (no {@code AS} alias): the very same
     * coerced {@link #dateExpr} that {@link #partitionColumn} extracts {@code YEAR}/{@code MONTH}/{@code DAY}
     * from, kept whole and widened to {@code TIMESTAMP} (consignment addressing §3.1).
     *
     * <p><b>Why this has to be materialised rather than computed later.</b> The written relation keeps mapped
     * columns under their <em>target</em> names; {@code pd.source()} is a <em>raw</em> column that is generally
     * absent from it, and where the raw column is {@code VARCHAR} the correct parse needs the schema's declared
     * field type plus the pipeline's format lists — none of which survive downstream. Computing it here, in the
     * one place that already has all three, is what makes an exact {@code min()}/{@code max()} possible at write
     * time. It is excluded from the written output, so the output schema is unchanged.
     */
    public static String eventTimeColumn(PartitionDef pd, String sourceTable,
                                         Map<String, String> fieldTypes, PipelineConfig.CsvSettings csv) {
        return "CAST(" + dateExpr(pd, sourceTable, fieldTypes, csv) + " AS TIMESTAMP)";
    }
}
