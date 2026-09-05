package com.gamma.etl;

import com.gamma.util.SqlBuilder;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure SQL-expression compiler for {@link DataTransformer}'s PARTITION and event-time columns — the
 * DuckDB scalar expression that produces one output column, <em>without</em> the {@code AS "alias"}
 * suffix, which the caller adds.
 *
 * <p>Until 2026-09-05 this also compiled {@code mapping.rules[]} ({@code DIRECT} / {@code EXPR} /
 * {@code CONCAT_DT} / {@code FILENAME_DATE}). That path is gone with {@code transform.map}: data columns
 * are Record Transformer {@code fields[]} compiled by {@link RecordTransform}, and a stored
 * {@code rules[]} is converted to them at read time ({@link RecordTransform#fromMappingRules}).
 */
public final class TransformCompiler {

    private TransformCompiler() {}

    /**
     * The legacy {@code mapping.rules[].transformType} vocabulary, upper-case. The rules themselves no
     * longer compile here — {@link RecordTransform#fromMappingRules} converts each type to its catalog
     * function at read time — but an authoring-time validator ({@code MappingRules}) still rejects a typo
     * against this set.
     */
    public static final Set<String> TRANSFORM_TYPES =
            Collections.unmodifiableSet(new TreeSet<>(Set.of("DIRECT", "EXPR", "CONCAT_DT", "FILENAME_DATE")));

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
        return partitionColumn(pd, sourceTable, fieldTypes, csv, SourceZones.NONE);
    }

    /**
     * As {@link #partitionColumn(PartitionDef, String, Map, PipelineConfig.CsvSettings)}, with the
     * source time-zone policy applied — so the partition a row lands in is cut from the <em>same</em>
     * normalised instant the stored column holds, never from a differently-zoned re-parse.
     */
    public static String partitionColumn(PartitionDef pd, String sourceTable,
                                         Map<String, String> fieldTypes, PipelineConfig.CsvSettings csv,
                                         SourceZones zones) {
        String col = "\"" + sourceTable + "\".\"" + pd.source() + "\"";
        StringBuilder sb = new StringBuilder();
        switch (pd.type()) {
            case VARCHAR -> sb.append(col);
            case DOUBLE  -> sb.append("TRY_CAST(").append(col).append(" AS DOUBLE)");
            case INTEGER -> sb.append("TRY_CAST(").append(col).append(" AS INTEGER)");
            case DATE_YEAR, DATE_MONTH, DATE_DAY -> {
                String dateExpr = dateExpr(pd, sourceTable, fieldTypes, csv, zones);
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
                                   Map<String, String> fieldTypes, PipelineConfig.CsvSettings csv,
                                   SourceZones zones) {
        String col         = "\"" + sourceTable + "\".\"" + pd.source() + "\"";
        String srcType     = SchemaFieldTypes.normalize(fieldTypes.getOrDefault(pd.source(), SchemaFieldTypes.VARCHAR));
        // A TIMESTAMPTZ source carries a time component exactly like TIMESTAMP, so it must parse
        // with timestamp_formats too — a date-only parse would NULL every row into the sentinel.
        String castType    = ("TIMESTAMP".equals(srcType) || "TIMESTAMPTZ".equals(srcType)) ? "TIMESTAMP" : "DATE";
        String varcharExpr = "CAST(" + col + " AS VARCHAR)";
        String parsed      = SqlBuilder.buildCastExpr(varcharExpr, castType, csv.dateFormats(), csv.tsFormats());
        // A DATE partition source has no instant to shift; only the timestamp branch takes a zone.
        // Both a TIMESTAMP and a TIMESTAMPTZ source normalise to naive UTC here — deliberately, even
        // though the stored TIMESTAMPTZ column keeps its offset: a partition cut from an instant
        // rendered in the SESSION zone would move with the host, which is exactly what this feature
        // exists to stop.
        if (!"TIMESTAMP".equals(castType)) return parsed;
        return SourceZones.toNaiveUtc(parsed, zones.zoneArg(pd.source(), sourceTable));
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
        return eventTimeColumn(pd, sourceTable, fieldTypes, csv, SourceZones.NONE);
    }

    /**
     * As {@link #eventTimeColumn(PartitionDef, String, Map, PipelineConfig.CsvSettings)}, with the
     * source time-zone policy applied. It shares {@link #dateExpr} with the partition columns, so the
     * write-time {@code min()}/{@code max()} bounds and the partition a row lands in can never
     * disagree about which instant the row carries.
     */
    public static String eventTimeColumn(PartitionDef pd, String sourceTable,
                                         Map<String, String> fieldTypes, PipelineConfig.CsvSettings csv,
                                         SourceZones zones) {
        return "CAST(" + dateExpr(pd, sourceTable, fieldTypes, csv, zones) + " AS TIMESTAMP)";
    }
}
