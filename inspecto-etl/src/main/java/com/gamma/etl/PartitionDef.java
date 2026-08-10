package com.gamma.etl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One declared partition column: the output Hive-directory segment name, the raw-table
 * source column it is derived from, and how to produce the SQL expression.
 *
 * <p>Read from the {@code partitions[]} list in a schema toon:
 * <pre>
 * partitions:
 *   - column: event_type
 *     source: EVENT_TYPE
 *     type: VARCHAR
 *   - column: year
 *     source: TXN_DATE
 *     type: DATE_YEAR
 *   - column: month
 *     source: TXN_DATE
 *     type: DATE_MONTH
 *   - column: day
 *     source: TXN_DATE
 *     type: DATE_DAY
 * </pre>
 *
 * <p>{@link #fromSchema} is the sole entry point; it handles three cases:
 * <ol>
 *   <li>{@code partitions[]} present — parsed directly.</li>
 *   <li>Legacy {@code partitionKey:} present — synthesised to three DATE_YEAR/MONTH/DAY
 *       defs on the same source column.</li>
 *   <li>Neither present — returns an empty list (caller emits the {@code 1900/01/01}
 *       fallback partition).</li>
 * </ol>
 *
 * @param column  output partition-directory segment name, e.g. {@code "year"}
 * @param source  raw-table column the expression is derived from, e.g. {@code "TXN_DATE"}
 * @param type    how to produce the SQL expression from the source column
 */
public record PartitionDef(String column, String source, Type type) {

    public enum Type {
        /** Direct VARCHAR reference — {@code sourceTable."SOURCE"} */
        VARCHAR,
        /** {@code TRY_CAST(sourceTable."SOURCE" AS DOUBLE)} */
        DOUBLE,
        /** {@code TRY_CAST(sourceTable."SOURCE" AS INTEGER)} */
        INTEGER,
        /**
         * {@code YEAR(dateExpr)::VARCHAR} where {@code dateExpr} is
         * {@code COALESCE(TRY_STRPTIME(…))} for VARCHAR sources or a direct
         * column reference for DATE/TIMESTAMP sources.
         */
        DATE_YEAR,
        /** {@code LPAD(MONTH(dateExpr)::VARCHAR, 2, '0')} — same dateExpr rules as DATE_YEAR. */
        DATE_MONTH,
        /** {@code LPAD(DAY(dateExpr)::VARCHAR, 2, '0')} — same dateExpr rules as DATE_YEAR. */
        DATE_DAY
    }

    /**
     * Parse {@code partitions[]} from a schema config map, falling back to the
     * legacy {@code partitionKey:} field, or returning an empty list when neither
     * is present.
     *
     * @param schemaConfig the loaded schema toon map
     * @return ordered list of partition defs; empty list means "no partitioning"
     */
    @SuppressWarnings("unchecked")
    public static List<PartitionDef> fromSchema(Map<String, Object> schemaConfig) {
        Object raw = schemaConfig.get("partitions");
        if (raw != null && !(raw instanceof List<?>)) {
            // Catches a common JToon mis-syntax error: writing partitions as a
            // YAML-style "- key: value" block, which JToon parses as something
            // non-null but non-List (typically a Map).  Without this guard, the
            // method silently falls through to the empty-list branch and every
            // row lands in the year=1900/month=01/day=01 sentinel partition.
            throw new IllegalArgumentException(
                    "Schema 'partitions' must be a JToon tabular list of the form " +
                    "'partitions[N]{column,source,type}:'; got " +
                    raw.getClass().getSimpleName() +
                    ". YAML-style '- key: value' is not parsed by JToon.");
        }
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .map(e -> (Map<String, Object>) e)
                    .map(m -> new PartitionDef(
                            (String) m.get("column"),
                            (String) m.get("source"),
                            Type.valueOf(((String) m.get("type")).toUpperCase().replace('-', '_'))))
                    .toList();
        }
        // Legacy fallback: single partitionKey → three date components
        String pk = (String) schemaConfig.get("partitionKey");
        if (pk != null && !pk.isBlank()) {
            return List.of(
                    new PartitionDef("year",  pk, Type.DATE_YEAR),
                    new PartitionDef("month", pk, Type.DATE_MONTH),
                    new PartitionDef("day",   pk, Type.DATE_DAY));
        }
        return List.of();
    }

    /** Extract just the column names in declaration order. */
    public static List<String> columnNames(List<PartitionDef> defs) {
        return defs.stream().map(PartitionDef::column).toList();
    }

    /** The date-typed defs — the ones {@code year}/{@code month}/{@code day} are cut from. */
    private boolean isDate() {
        return type == Type.DATE_YEAR || type == Type.DATE_MONTH || type == Type.DATE_DAY;
    }

    /**
     * The def whose {@link #source} column is this schema's <b>event time</b> — the raw column the
     * {@code DATE_*} partition components are all cut from (consignment addressing §3.1).
     *
     * <p>Empty when the schema declares no date-typed partition, and <b>also empty when the date defs
     * disagree on their source column</b>: {@code year} from one column and {@code day} from another is a
     * partition scheme with no single event time, and guessing one would put bounds in the catalog that the
     * partitioning itself contradicts. Degrade to no bounds, never to wrong ones.
     */
    public static Optional<PartitionDef> eventTimeDef(List<PartitionDef> defs) {
        if (defs == null) return Optional.empty();
        List<PartitionDef> dates = defs.stream().filter(PartitionDef::isDate).toList();
        if (dates.isEmpty()) return Optional.empty();
        return dates.stream().map(PartitionDef::source).distinct().count() == 1
                ? Optional.of(dates.get(0))
                : Optional.empty();
    }
}
