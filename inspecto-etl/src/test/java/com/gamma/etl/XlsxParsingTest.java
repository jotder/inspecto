package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Assumptions;
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
 * The {@code frontend: xlsx} parsing frontend (multiformat-parser-lanes plan X1): DuckDB
 * {@code read_xlsx} over the workbook, selectors = sheet column names, all-VARCHAR landing, the
 * shared typing/mapping/partition backend untouched.
 *
 * <p>⚠ <b>Assumption-gated, never silently green:</b> the {@code excel} extension is not statically
 * linked into duckdb_jdbc (plan §0), so on a box where it cannot load (offline AND uncached AND no
 * {@code -Dduckdb.extension.dir}) these tests SKIP with a message naming the cache dir — a skip is
 * visible in the reactor's skip count, a pass is real. Fixtures are generated per test via
 * {@code COPY … TO (FORMAT xlsx)} — no committed binaries, no POI.
 */
class XlsxParsingTest {

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

    private static final String PARSING = """
            parsing:
              frontend: xlsx
            """;

    /** Two data rows under a header row — written through DuckDB itself, so read parity is exact. */
    private static File writeWorkbook(Connection conn, Path dir, String name) throws Exception {
        File f = dir.resolve(name).toFile();
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES"
                    + " ('A00001', '2020-04-03', 1234.5),"
                    + " ('B00002', '2020-04-04', 9999.0)"
                    + ") v(account, event_date, amount) ORDER BY account)"
                    + " TO '" + f.getAbsolutePath().replace("\\", "/").replace("'", "''")
                    + "' WITH (FORMAT xlsx, HEADER true)");
        }
        return f;
    }

    @Test
    void xlsxRowsLandAsVarcharColumnsKeyedByHeaderName(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "x1", PARSING);
        assertNotNull(cfg.xlsx(), "xlsx frontend parsed from the parsing: block");
        assertTrue(cfg.xlsx().header(), "header defaults true");
        assertTrue(DuckDbCsvIngester.usesDuckDb(cfg), "xlsx frontend is always native");

        try (Connection conn = open()) {
            File wb = writeWorkbook(conn, dir, "ev.xlsx");
            IngestResult r = DuckDbCsvIngester.ingest(wb, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows());
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
            assertEquals(List.of("2020-04-03", "2020-04-04"), col(conn, "raw_f0", "EVENT_DATE"));
            assertEquals(List.of("1234.5", "9999.0"), col(conn, "raw_f0", "AMOUNT"),
                    "cells land as VARCHAR at ingest (typed later by DataTransformer)");
        }
    }

    /** P3's positional-naming claim, PROBED not assumed: header=false ⇒ columns are A, B, C…. */
    @Test
    void headerFalseSelectsByPositionalLetterNames(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema_pos.toon");
        Files.writeString(schema, SCHEMA
                .replace("\"account\"", "\"A\"")
                .replace("\"event_date\"", "\"B\"")
                .replace("\"amount\"", "\"C\""), StandardCharsets.UTF_8);
        PipelineConfig cfg = loadWithSchema(dir, "pos", """
                parsing:
                  frontend: xlsx
                  xlsx:
                    header: false
                    range: A2:C3
                """, schema);

        try (Connection conn = open()) {
            File wb = writeWorkbook(conn, dir, "ev.xlsx"); // row 1 is the header; the range skips it
            DuckDbCsvIngester.ingest(wb, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
        }
    }

    @Test
    void rangeLimitsTheRowsRead(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "rg", """
                parsing:
                  frontend: xlsx
                  xlsx:
                    range: A1:C2
                """);
        assertEquals("A1:C2", cfg.xlsx().range());
        try (Connection conn = open()) {
            File wb = writeWorkbook(conn, dir, "ev.xlsx");
            IngestResult r = DuckDbCsvIngester.ingest(wb, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(1, r.parsedRows(), "header row + one data row inside the range");
            assertEquals(List.of("A00001"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
        }
    }

    @Test
    void normalizeNamesLowersHeaderCells(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema_nn.toon");
        Files.writeString(schema, SCHEMA.replace("\"account\"", "\"account_number\""), StandardCharsets.UTF_8);
        PipelineConfig cfg = loadWithSchema(dir, "nn", """
                parsing:
                  frontend: xlsx
                  xlsx:
                    normalize_names: true
                """, schema);

        try (Connection conn = open()) {
            File f = dir.resolve("ev.xlsx").toFile();
            try (Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT 'A00001' AS \"Account Number\", '2020-04-03' AS event_date,"
                        + " 1.5 AS amount)"
                        + " TO '" + f.getAbsolutePath().replace("\\", "/").replace("'", "''")
                        + "' WITH (FORMAT xlsx, HEADER true)");
            }
            DuckDbCsvIngester.ingest(f, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("A00001"), col(conn, "raw_f0", "ACCOUNT_NUMBER"),
                    "'Account Number' normalizes to account_number");
        }
    }

    @Test
    void excelAliasSelectsTheSameFrontend(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "al", "parsing:\n  frontend: excel\n");
        assertNotNull(cfg.xlsx(), "'excel' is an accepted alias for frontend xlsx");
    }

    // ── validation (no extension needed — pure config) ──────────────────────────

    @Test
    void malformedRangeFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "br", """
                parsing:
                  frontend: xlsx
                  xlsx:
                    range: "1A-9"
                """));
        assertTrue(e.getMessage().contains("xlsx.range"), e.getMessage());
    }

    @Test
    void blankSheetFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "bs", """
                parsing:
                  frontend: xlsx
                  xlsx:
                    sheet: ""
                """));
        assertTrue(e.getMessage().contains("xlsx.sheet"), e.getMessage());
    }

    // ── helpers (JsonParsingTest's shape) ───────────────────────────────────────

    private static List<String> col(Connection conn, String table, String c) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT \"" + c + "\" FROM \"" + table + "\" ORDER BY 1")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /** Opens a temp DB and SKIPS the test (visibly, never silently green) when excel can't load. */
    private static Connection open() throws Exception {
        Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("xlsx_"));
        boolean loaded = ExcelExtension.tryLoad(conn);
        if (!loaded) conn.close();
        Assumptions.assumeTrue(loaded, "DuckDB 'excel' extension unavailable on this box — run once "
                + "with network (caches under ~/.duckdb/extensions) or set -D" + ExcelExtension.DIR_PROPERTY);
        return conn;
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
                "name: XLSX_" + tag + "\n" +
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
                "  file_pattern: \"glob:**/*.xlsx\"\n" +
                "  schema_file: " + fwd(schema) + "\n" +
                "  csv_settings:\n" +
                "    date_formats[1]: \"%Y-%m-%d\"\n" +
                "    timestamp_formats[1]: \"%Y-%m-%d\"\n" +
                parsingBlock;
        Path p = dir.resolve("xlsx_" + tag + "_pipeline.toon");
        Files.writeString(p, pipe, StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }
}
