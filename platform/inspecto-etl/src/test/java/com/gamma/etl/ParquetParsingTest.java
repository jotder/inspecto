package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code frontend: parquet} parsing frontend (ELT Phase 3 S3c-1): DuckDB {@code read_parquet}
 * over the file, selectors = parquet column names, all-VARCHAR landing via per-column CAST
 * (probed: {@code read_parquet} yields the file's real types and has no {@code all_varchar}
 * option), the shared typing/mapping/partition backend untouched. Unlike xlsx there is no
 * extension gate — parquet is built into DuckDB — so nothing here skips. Fixtures are generated
 * per test via {@code COPY … (FORMAT PARQUET)} — no committed binaries.
 */
class ParquetParsingTest {

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: ev
              format: CSV
              fields[3]{name,selector,type}:
                ACCOUNT_NUMBER,"account",VARCHAR
                EVENT_DATE,"event_date",DATE
                AMOUNT,"amount",DOUBLE
            mapping:
              canonicalName: ev
              rawName: ev
              rules[3]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                AMOUNT,AMOUNT,DIRECT
            """;

    /** Two TYPED rows — DATE and DOUBLE columns, so the VARCHAR-landing CAST is actually exercised. */
    private static File writeParquet(Connection conn, Path dir, String name) throws Exception {
        File f = dir.resolve(name).toFile();
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES"
                    + " ('A00001', DATE '2020-04-03', 1234.5),"
                    + " ('B00002', DATE '2020-04-04', 9999.0)"
                    + ") v(account, event_date, amount) ORDER BY account)"
                    + " TO '" + f.getAbsolutePath().replace("\\", "/").replace("'", "''")
                    + "' (FORMAT PARQUET)");
        }
        return f;
    }

    @Test
    void parquetRowsLandAsVarcharColumnsKeyedByColumnName(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "p1", "parsing:\n  frontend: parquet\n");
        assertNotNull(cfg.parquet(), "parquet frontend parsed from the parsing: block");
        assertTrue(DuckDbCsvIngester.usesDuckDb(cfg), "parquet frontend is always native");

        try (Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("pq_"))) {
            File pq = writeParquet(conn, dir, "ev.parquet");
            IngestResult r = DuckDbCsvIngester.ingest(pq, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows());
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
            assertEquals(List.of("2020-04-03", "2020-04-04"), col(conn, "raw_f0", "EVENT_DATE"),
                    "a typed DATE column lands as its VARCHAR rendering (typed later by DataTransformer)");
            assertEquals(List.of("1234.5", "9999.0"), col(conn, "raw_f0", "AMOUNT"));
        }
    }

    /** The declared-projection contract is fail-closed: a selector naming no parquet column fails. */
    @Test
    void aMissingSelectorFailsTheReadInsteadOfReadingEverything(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema_miss.toon");
        Files.writeString(schema, SCHEMA.replace("\"account\"", "\"no_such_column\""), StandardCharsets.UTF_8);
        PipelineConfig cfg = loadWithSchema(dir, "miss", "parsing:\n  frontend: parquet\n", schema);

        try (Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("pq_"))) {
            File pq = writeParquet(conn, dir, "ev.parquet");
            assertThrows(Exception.class,
                    () -> DuckDbCsvIngester.ingest(pq, conn, cfg.schemas().single(), cfg, "raw_f0"));
        }
    }

    // ── validation (pure config, no DB) ─────────────────────────────────────────

    @Test
    void anUnknownParquetOptionFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "opt", """
                parsing:
                  frontend: parquet
                  parquet:
                    file_row_number: true
                """));
        assertTrue(e.getMessage().contains("file_row_number"), e.getMessage());
    }

    /** The one honored option, probed real: hive key=value dir levels become selectable columns. */
    @Test
    void hivePartitioningExposesDirectoryLevelsAsColumns(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema_hive.toon");
        Files.writeString(schema, SCHEMA.replace("\"event_date\"", "\"year\""), StandardCharsets.UTF_8);
        PipelineConfig cfg = loadWithSchema(dir, "hive", """
                parsing:
                  frontend: parquet
                  parquet:
                    hive_partitioning: true
                """, schema);
        assertTrue(cfg.parquet().hivePartitioning());

        try (Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("pq_"))) {
            Path part = dir.resolve("hive/year=2026");
            Files.createDirectories(part);
            File pq = part.resolve("part.parquet").toFile();
            try (Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT 'A00001' AS account, 1.5 AS amount)"
                        + " TO '" + pq.getAbsolutePath().replace("\\", "/").replace("'", "''")
                        + "' (FORMAT PARQUET)");
            }
            DuckDbCsvIngester.ingest(pq, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("2026"), col(conn, "raw_f0", "EVENT_DATE"),
                    "the hive dir level 'year' feeds the schema field selecting it");
        }
    }

    @Test
    void otherFrontendsLeaveParquetNull(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "off", "parsing:\n  frontend: delimited\n");
        assertNull(cfg.parquet(), "parquet stays null for every other frontend");
    }

    // ── helpers (XlsxParsingTest's shape) ───────────────────────────────────────

    private static List<String> col(Connection conn, String table, String c) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT \"" + c + "\" FROM \"" + table + "\" ORDER BY 1")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static String fwd(Path p) { return p.toString().replace('\\', '/'); }

    private static PipelineConfig load(Path dir, String tag, String parsingBlock) throws Exception {
        Path schema = dir.resolve("schema_" + tag + ".toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        return loadWithSchema(dir, tag, parsingBlock, schema);
    }

    private static PipelineConfig loadWithSchema(Path dir, String tag, String parsingBlock, Path schema)
            throws Exception {
        String d = fwd(dir);
        String pipe =
                "name: PQ_" + tag + "\n" +
                "version: 1\n" +
                "dirs:\n" +
                "  poll: " + d + "/inbox\n" +
                "  database: " + d + "/db\n" +
                "  backup: " + d + "/backup\n" +
                "  temp: " + d + "/temp\n" +
                "  errors: " + d + "/errors\n" +
                "  quarantine: " + d + "/quarantine\n" +
                "  status_dir: " + d + "/status\n" +
                "output:\n" +
                "  format: PARQUET\n" +
                "processing:\n" +
                "  threads: 1\n" +
                "  file_pattern: \"glob:**/*.parquet\"\n" +
                "  schema_file: " + fwd(schema) + "\n" +
                "  csv_settings:\n" +
                "    date_formats[1]: \"%Y-%m-%d\"\n" +
                "    timestamp_formats[1]: \"%Y-%m-%d\"\n" +
                parsingBlock;
        Path p = dir.resolve("pq_" + tag + "_pipeline.toon");
        Files.writeString(p, pipe, StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }
}
