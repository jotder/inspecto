package com.gamma.etl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>Per-Step type flow (ELT amendment §3.4 item 4):</b> derive a Step's <em>output schema</em> from the
 * declared input Schema + Mapping by running DuckDB {@code DESCRIBE} over the identical SELECT that
 * {@link DataTransformer#materialize} executes — <b>without executing it</b>. DuckDB is the type authority;
 * the returned types are its own inferred types, which is exactly what the Parquet footer will carry,
 * because {@code PartitionWriter} writes via {@code COPY (SELECT …)} over the same relation.
 *
 * <p>The source relation is modelled as an <b>empty scratch table</b> shaped like the raw ingest table:
 * on the CSV path every field lands {@code VARCHAR} (the {@code read_csv columns=} clause,
 * {@link DuckDbCsvIngester}); on the plugin path fields carry their declared types. {@code DESCRIBE} binds
 * names and infers types against that shape without reading a row, so a mapping rule referencing a
 * nonexistent field fails here — at authoring time — with DuckDB's binder error naming the column.
 */
public final class TypeFlow {

    private TypeFlow() {}

    /** One derived output column: name + DuckDB type (verbatim, e.g. {@code VARCHAR}, {@code DOUBLE}). */
    public record Column(String name, String type) {}

    /**
     * The columns {@code DataTransformer.materialize} would produce for {@code schemaConfig} —
     * {@code __src_id} included, in SELECT order.
     *
     * @param typedSource {@code false} for the CSV path (raw columns are all {@code VARCHAR});
     *                    {@code true} for the plugin path (raw columns carry the declared field types)
     * @throws IllegalArgumentException when the compiled SELECT does not bind — a mapping rule referencing
     *                                  a nonexistent field, or an invalid {@code EXPR} — carrying DuckDB's
     *                                  own message, which names the offending column
     */
    public static List<Column> transformedColumns(Map<String, Object> schemaConfig, PipelineConfig cfg,
                                                  boolean typedSource) {
        String src = "__typeflow_src";
        String select = DataTransformer.selectFor(schemaConfig, cfg, src);
        try {
            com.gamma.util.DuckDbUtil.loadDriver();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("DuckDB JDBC driver not on the classpath", e);
        }
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            st.execute(scratchTableDdl(schemaConfig, src, typedSource));
            List<Column> out = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("DESCRIBE " + select)) {
                while (rs.next()) out.add(new Column(rs.getString("column_name"), rs.getString("column_type")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalArgumentException(
                    "Schema/Mapping do not compile to a valid transform: " + e.getMessage(), e);
        }
    }

    /**
     * The derived <b>sink</b> schema: {@link #transformedColumns} minus the internal {@code __src_id} tag and
     * {@code __event_time}, mirroring {@code PartitionWriter}'s {@code EXCLUDE} projection exactly. This is the
     * written table's full shape (Dataset auto-registration's view). Note the Parquet <em>file footer</em>
     * carries these columns minus the partition columns — Hive {@code PARTITION_BY} encodes those as
     * directories, not file columns — which is how the footer-parity gate compares (see {@code TypeFlowTest}).
     *
     * <p>The two filters must stay in lockstep with that projection: an internal column dropped here but not
     * there (or the reverse) is exactly the drift the footer-parity gate exists to catch.
     */
    public static List<Column> sinkColumns(Map<String, Object> schemaConfig, PipelineConfig cfg,
                                           boolean typedSource) {
        return transformedColumns(schemaConfig, cfg, typedSource).stream()
                .filter(c -> !"__src_id".equals(c.name()))
                .filter(c -> !TransformCompiler.EVENT_TIME_COL.equals(c.name())).toList();
    }

    /** Empty scratch table shaped like the raw ingest table: field columns + the {@code __src_id} tag. */
    @SuppressWarnings("unchecked")
    private static String scratchTableDdl(Map<String, Object> schemaConfig, String table, boolean typedSource) {
        List<Map<String, Object>> fields =
                (List<Map<String, Object>>) ((Map<String, Object>) schemaConfig.get("raw")).get("fields");
        StringBuilder ddl = new StringBuilder("CREATE TABLE \"").append(table).append("\" (");
        for (Map<String, Object> f : fields) {
            String type = typedSource && f.get("type") != null ? f.get("type").toString() : "VARCHAR";
            ddl.append('"').append(f.get("name")).append("\" ").append(type).append(", ");
        }
        ddl.append("\"__src_id\" INTEGER)");
        return ddl.toString();
    }
}
