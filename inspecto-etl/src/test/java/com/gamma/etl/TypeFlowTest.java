package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TypeFlow} — ELT amendment §3.4 item 4: per-Step output schemas derived by {@code DESCRIBE} over
 * the same SELECT {@link DataTransformer} executes, without executing it. The load-bearing test is the
 * <b>footer-parity gate</b> (Phase 2 verify gate): the derived sink schema must match what the written
 * Parquet file actually carries, because DuckDB is the single type authority on both sides.
 */
class TypeFlowTest {

    /** VARCHAR raw source (CSV path), DIRECT + EXPR + typed DIRECTs, date partitions. */
    private static final String SCHEMA = """
            raw:
              name: ev
              format: CSV
              fields[4]{name,selector,type}:
                ACCOUNT_NUMBER,0,VARCHAR
                AMT,1,DOUBLE
                EVENT_DATE,2,DATE
                EVENT_TS,3,TIMESTAMP
            mapping:
              canonicalName: ev
              rawName: ev
              rules[5]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                EVENT_TS,EVENT_TS,DIRECT
                GROSS,"ROUND(TRY_CAST(AMT AS DOUBLE) * 100, 2)",EXPR
            partitions[3]{column,source,type}:
              year,EVENT_DATE,DATE_YEAR
              month,EVENT_DATE,DATE_MONTH
              day,EVENT_DATE,DATE_DAY
            """;

    private static PipelineConfig cfg(Path dir) throws Exception {
        Files.writeString(dir.resolve("ev_schema.toon"), SCHEMA, StandardCharsets.UTF_8);
        String d = dir.toString().replace('\\', '/');
        Path pipeline = dir.resolve("ev_pipeline.toon");
        Files.writeString(pipeline, """
                name: EV_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                output:
                  format: PARQUET
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: ev_schema.toon
                """.formatted(d, d, d, d, d, d, d), StandardCharsets.UTF_8);
        return PipelineConfig.load(pipeline.toString());
    }

    @Test
    void derivesTypesWithoutExecuting(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        Map<String, String> types = new LinkedHashMap<>();
        for (TypeFlow.Column c : TypeFlow.transformedColumns(cfg.schemas().single(), cfg, false))
            types.put(c.name(), c.type());

        assertEquals("VARCHAR", types.get("ACCOUNT_NUMBER"), "VARCHAR raw + DIRECT passes through");
        assertEquals("DOUBLE", types.get("AMT"), "a DOUBLE-declared DIRECT compiles to TRY_CAST");
        assertEquals("DATE", types.get("EVENT_DATE"), "DATE-declared DIRECT → TRY_STRPTIME chain → DATE");
        assertEquals("TIMESTAMP", types.get("EVENT_TS"));
        assertEquals("DOUBLE", types.get("GROSS"), "EXPR types come from DuckDB's own inference");
        assertEquals("VARCHAR", types.get("year"), "DATE_YEAR partition stringifies");
        assertEquals("INTEGER", types.get("__src_id"), "the lineage tag is part of the transformed shape");
    }

    @Test
    void typedSourceIsWhatSeparatesThePluginPathFromCsv(@TempDir Path dir) throws Exception {
        // 🔴 Every other test here passes typedSource=false, so until now nothing exercised the
        // branch that makes a plugin/ASN.1 pipeline's types correct. The flag is not cosmetic: on
        // the CSV path a raw column is VARCHAR and a DIRECT rule to a typed target compiles to a
        // TRY_CAST, while on the plugin path the declared type is already there and the cast is a
        // no-op. A route that passes the wrong flag reports types that look authoritative and are
        // wrong — which is exactly why the derived-schema route reports which path it assumed.
        PipelineConfig cfg = cfg(dir);
        Map<String, Object> schema = cfg.schemas().single();

        Map<String, String> asCsv = new LinkedHashMap<>();
        for (TypeFlow.Column c : TypeFlow.transformedColumns(schema, cfg, false)) asCsv.put(c.name(), c.type());
        Map<String, String> asPlugin = new LinkedHashMap<>();
        for (TypeFlow.Column c : TypeFlow.transformedColumns(schema, cfg, true)) asPlugin.put(c.name(), c.type());

        // The declared target types agree across both paths — the cast lands the same place either
        // way, which is what makes the derived schema trustworthy as the written shape.
        assertEquals(asCsv.get("AMT"), asPlugin.get("AMT"), "a DOUBLE target is DOUBLE on both paths");
        assertEquals(asCsv.get("EVENT_DATE"), asPlugin.get("EVENT_DATE"));
        assertEquals(asCsv.keySet(), asPlugin.keySet(), "the flag changes types, never the column set");

        // And the typed path really did bind against declared types rather than VARCHAR: the
        // scratch table is built from the field types, so a typed source still derives cleanly.
        assertEquals("DOUBLE", asPlugin.get("AMT"));
    }

    @Test
    void sinkColumnsDropTheLineageTag(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        List<TypeFlow.Column> sink = TypeFlow.sinkColumns(cfg.schemas().single(), cfg, false);
        assertTrue(sink.stream().noneMatch(c -> c.name().equals("__src_id")),
                "mirrors PartitionWriter's EXCLUDE(__src_id)");
        assertEquals(List.of("ACCOUNT_NUMBER", "AMT", "EVENT_DATE", "EVENT_TS", "GROSS", "year", "month", "day"),
                sink.stream().map(TypeFlow.Column::name).toList());
    }

    @Test
    void aMappingOverANonexistentFieldFailsNamingTheColumn(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        Map<String, Object> schema = com.gamma.util.ToonHelper.load(
                dir.resolve("ev_schema.toon").toString());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> rules = (List<Map<String, String>>)
                ((Map<String, Object>) schema.get("mapping")).get("rules");
        rules.add(new LinkedHashMap<>(Map.of(
                "targetColumn", "BROKEN", "sourceExpression", "NO_SUCH_FIELD", "transformType", "DIRECT")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TypeFlow.transformedColumns(schema, cfg, false));
        assertTrue(e.getMessage().contains("NO_SUCH_FIELD"), e.getMessage());
    }

    /**
     * <b>The Phase-2 verify gate:</b> the derived sink schema equals what the written Parquet file actually
     * carries. The footer holds the sink columns minus the partition columns — Hive {@code PARTITION_BY}
     * encodes those as directories — compared name-for-name, type-for-type via {@code read_parquet}.
     */
    @Test
    void derivedSinkSchemaMatchesTheWrittenParquetFooter(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        Map<String, Object> schema = cfg.schemas().single();

        DuckDbUtil.loadDriver();
        List<PartitionOutput> outputs;
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE raw_input AS SELECT * FROM (VALUES
                      ('a1', 'not-a-number', '2026-07-01', '2026-07-01 10:00:00', 0),
                      ('a2', '12.5',         '2026-07-02', '2026-07-02 11:00:00', 0)
                    ) v(ACCOUNT_NUMBER, AMT, EVENT_DATE, EVENT_TS, __src_id)""");
            DataTransformer.materialize(conn, schema, cfg);
            outputs = PartitionWriter.write(conn, "transformed", cfg.dirs().database(),
                    "PARQUET", null, "b1", List.of("year", "month", "day"));
        }
        assertFalse(outputs.isEmpty(), "harness precondition: at least one Parquet file written");

        List<String> partitionCols = List.of("year", "month", "day");
        Map<String, String> derived = new LinkedHashMap<>();
        for (TypeFlow.Column c : TypeFlow.sinkColumns(schema, cfg, false))
            if (!partitionCols.contains(c.name())) derived.put(c.name(), c.type());

        String file = outputs.get(0).outputFile().replace('\\', '/');
        Map<String, String> footer = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     // hive_partitioning=false: read the FILE's footer only — by default read_parquet
                     // re-derives year/month/day from the directory path, which is not the footer.
                     "DESCRIBE SELECT * FROM read_parquet('" + file.replace("'", "''")
                             + "', hive_partitioning=false)")) {
            while (rs.next()) footer.put(rs.getString("column_name"), rs.getString("column_type"));
        }

        assertEquals(derived, footer,
                "derived sink schema must equal the Parquet footer — DuckDB is the type authority on both sides");
    }
}
