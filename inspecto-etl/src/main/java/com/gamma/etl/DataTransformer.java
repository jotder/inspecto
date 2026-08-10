package com.gamma.etl;

import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

/**
 * Builds the {@code transformed} table from a raw-ingestion table by applying the
 * schema's mapping rules and type casts, plus the partition columns declared in
 * {@code partitions[]} (or synthesised from the legacy {@code partitionKey:} field),
 * and the internal {@code __src_id} lineage tag.
 *
 * <p>The raw-ingestion table must already exist in {@code conn} and carry a trailing
 * {@code __src_id INTEGER} column added by {@link com.gamma.inspector.BatchProcessor}.
 * Writing the partitioned output is the responsibility of {@link PartitionWriter};
 * computing lineage is {@link LineageCollector}.
 *
 * <p>When {@code processing.csv_settings.where} is set, this is also the <b>post-parse row filter</b>
 * site: the predicate runs against the mapped, typed target columns produced here — the only place a
 * predicate like {@code amount > 0} can be evaluated. It is not the same feature as the pre-parse
 * {@code include_*}/{@code exclude_*} regex lists, which match one raw physical column inside
 * {@code read_csv} ({@link DuckDbCsvIngester#filterWhere}).
 *
 * <h3>Partition column SQL generation</h3>
 * <ul>
 *   <li>{@code VARCHAR / DOUBLE / INTEGER} — direct reference or TRY_CAST.</li>
 *   <li>{@code DATE_YEAR / DATE_MONTH / DATE_DAY} — if the source field type is
 *       {@code DATE} or {@code TIMESTAMP} (already typed by the ingester), the column
 *       is referenced directly and wrapped with {@code YEAR(…)}, {@code MONTH(…)},
 *       {@code DAY(…)}.  For {@code VARCHAR} sources, a
 *       {@code COALESCE(TRY_STRPTIME(…))::DATE} chain is applied first.</li>
 * </ul>
 */
public final class DataTransformer {

    private DataTransformer() {}

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Backward-compatible overload: reads from {@code raw_input}, writes to
     * {@code transformed}.  Used by the existing CSV single-schema path.
     */
    public static void materialize(Connection conn, Map<String, Object> schemaConfig,
                                   PipelineConfig cfg) throws Exception {
        materialize(conn, schemaConfig, cfg, "raw_input", "transformed");
    }

    /**
     * Create {@code destTable} in {@code conn} from {@code sourceTable}.
     *
     * @param conn         worker DuckDB connection containing {@code sourceTable}
     * @param schemaConfig schema config map ({@code raw.fields}, {@code mapping},
     *                     {@code partitions[]} or legacy {@code partitionKey})
     * @param cfg          pipeline configuration (date/timestamp formats)
     * @param sourceTable  DuckDB table to read from (e.g. {@code "raw_CALL"})
     * @param destTable    DuckDB table to create    (e.g. {@code "transformed_CALL"})
     */
    public static void materialize(Connection conn, Map<String, Object> schemaConfig,
                                   PipelineConfig cfg,
                                   String sourceTable, String destTable) throws Exception {

        String select = selectFor(schemaConfig, cfg, sourceTable);

        // ── post-parse row predicate (csv_settings.where) ─────────────────────
        // Wrapped as a derived table rather than appended as a WHERE on the SELECT above: the
        // predicate is written against the *target* column names, and SQL cannot reference a
        // SELECT's own output aliases from its own WHERE. The pre-parse include_*/exclude_*
        // filters are a different moment entirely (DuckDbCsvIngester.filterWhere, inside read_csv).
        String sql = "CREATE TABLE \"" + destTable + "\" AS " + select;
        if (cfg.csv().hasRowPredicate())
            sql = "CREATE TABLE \"" + destTable + "\" AS SELECT * FROM (" + select + ") AS __shaped "
                    + "WHERE COALESCE((" + cfg.csv().where() + "), FALSE)";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * The transform SELECT — mapped data columns, partition columns, {@code __src_id} — as pure SQL text,
     * <b>without</b> the row-predicate wrapper or any {@code CREATE TABLE}. Separated from
     * {@link #materialize} so {@link TypeFlow} can hand the identical query to {@code DESCRIBE} and derive
     * the Step's output schema without executing it (ELT amendment §3.4 item 4).
     */
    @SuppressWarnings("unchecked")
    public static String selectFor(Map<String, Object> schemaConfig, PipelineConfig cfg, String sourceTable) {

        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Map<String, Object> f : fields)
            fieldTypes.put((String) f.get("name"), (String) f.get("type"));

        StringBuilder select = new StringBuilder("SELECT ");

        // ── mapped data columns ───────────────────────────────────────────────
        // The per-rule expressions come from dataColumns() — the same list the graph
        // executor projects with — so the two paths cannot drift.
        List<Map<String, Object>> cols = dataColumns(schemaConfig, cfg.csv(), sourceTable);
        for (int i = 0; i < cols.size(); i++) {
            select.append(cols.get(i).get("expr"));
            select.append(" AS \"").append(cols.get(i).get("name")).append('"');
            if (i < cols.size() - 1) select.append(", ");
        }

        // ── partition columns ─────────────────────────────────────────────────
        List<PartitionDef> partDefs = PartitionDef.fromSchema(schemaConfig);
        if (partDefs.isEmpty()) {
            // No partition key at all — land everything in the 1900/01/01 sentinel
            select.append(", '1900' AS year, '01' AS month, '01' AS day");
        } else {
            for (PartitionDef pd : partDefs) {
                select.append(", ");
                select.append(TransformCompiler.partitionColumn(pd, sourceTable, fieldTypes, cfg.csv()));
                select.append(" AS \"").append(pd.column()).append('"');
            }
        }

        // ── event time (consignment addressing §3.1) ──────────────────────────
        // The same coerced expression year/month/day are cut from, kept whole, so a write-time min()/max()
        // over this relation is exact. Always emitted — NULL when the schema declares no date partition, or
        // when its date defs disagree on a source column — so PartitionWriter's EXCLUDE can be unconditional
        // and the written output schema never depends on the schema's partition shape.
        select.append(", ").append(PartitionDef.eventTimeDef(partDefs)
                        .map(pd -> TransformCompiler.eventTimeColumn(pd, sourceTable, fieldTypes, cfg.csv()))
                        .orElse("CAST(NULL AS TIMESTAMP)"))
                .append(" AS ").append(TransformCompiler.EVENT_TIME_COL);

        // ── lineage tag ───────────────────────────────────────────────────────
        select.append(", \"").append(sourceTable).append("\".\"__src_id\" AS __src_id");
        select.append(" FROM \"").append(sourceTable).append('"');
        return select.toString();
    }

    /**
     * The schema's mapping rules as an ordered {@code [{name, expr}]} list — one entry per rule,
     * {@code name} the target column and {@code expr} its DuckDB scalar expression (no {@code AS}).
     *
     * <p>Extracted from {@link #selectFor}, which now assembles its SELECT from this same list, so the
     * legacy engine and the graph executor's {@code transform.map} projection compile every rule through
     * one code path instead of two that can drift. Deliberately <b>data columns only</b>: partition
     * columns and the {@code __src_id} lineage tag are not per-rule output and stay in {@code selectFor}
     * (in the graph model they belong to the sink node, not to {@code transform.map}).
     *
     * <p>Pure. {@code sourceTable} qualifies every column reference, so it must name the table the
     * caller's {@code FROM} actually reads.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> dataColumns(Map<String, Object> schemaConfig,
                                                       PipelineConfig.CsvSettings csv, String sourceTable) {
        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Map<String, Object> f : fields)
            fieldTypes.put((String) f.get("name"), (String) f.get("type"));

        List<Map<String, String>> rules =
                (List<Map<String, String>>) ((Map<String, Object>) schemaConfig.get("mapping")).get("rules");

        List<Map<String, Object>> cols = new ArrayList<>();
        for (Map<String, String> rule : rules)
            cols.add(Map.of("name", rule.get("targetColumn"),
                    "expr", TransformCompiler.dataColumn(rule, fieldTypes, sourceTable, csv)));
        return cols;
    }
}
