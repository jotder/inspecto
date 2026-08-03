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
    @SuppressWarnings("unchecked")
    public static void materialize(Connection conn, Map<String, Object> schemaConfig,
                                   PipelineConfig cfg,
                                   String sourceTable, String destTable) throws Exception {

        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (Map<String, Object> f : fields)
            fieldTypes.put((String) f.get("name"), (String) f.get("type"));

        List<Map<String, String>> rules =
                (List<Map<String, String>>) ((Map<String, Object>) schemaConfig.get("mapping")).get("rules");

        StringBuilder select = new StringBuilder("SELECT ");

        // ── mapped data columns ───────────────────────────────────────────────
        // Per-column expression generation is delegated to TransformCompiler (a
        // transformType → function registry); this method only assembles the SELECT.
        for (int i = 0; i < rules.size(); i++) {
            Map<String, String> rule = rules.get(i);
            select.append(TransformCompiler.dataColumn(rule, fieldTypes, sourceTable, cfg));
            select.append(" AS \"").append(rule.get("targetColumn")).append('"');
            if (i < rules.size() - 1) select.append(", ");
        }

        // ── partition columns ─────────────────────────────────────────────────
        List<PartitionDef> partDefs = PartitionDef.fromSchema(schemaConfig);
        if (partDefs.isEmpty()) {
            // No partition key at all — land everything in the 1900/01/01 sentinel
            select.append(", '1900' AS year, '01' AS month, '01' AS day");
        } else {
            for (PartitionDef pd : partDefs) {
                select.append(", ");
                select.append(TransformCompiler.partitionColumn(pd, sourceTable, fieldTypes, cfg));
                select.append(" AS \"").append(pd.column()).append('"');
            }
        }

        // ── lineage tag ───────────────────────────────────────────────────────
        select.append(", \"").append(sourceTable).append("\".\"__src_id\" AS __src_id");
        select.append(" FROM \"").append(sourceTable).append('"');

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
}
