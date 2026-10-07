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

    // ── real date cells (EXCEL-DATES-ARRIVE-AS-SERIALS-1) ────────────────────────
    //
    // A real Excel date is a day-serial number with a date style; all_varchar renders the NUMBER
    // (probed on duckdb_jdbc 1.5.2.1: DATE '2026-08-03' -> "46237.0", TIMESTAMP '2026-08-03
    // 10:30:15' -> "46237.437673611115"). A field DECLARED date-like must land as a date instead.

    private static final String DATED_SCHEMA = """
            partitions[3]{column,source,type}:
              year,POSTED,DATE_YEAR
              month,POSTED,DATE_MONTH
              day,POSTED,DATE_DAY
            raw:
              name: ev
              format: CSV
              fields[4]{name,selector,type}:
                ACCOUNT_NUMBER,"account",VARCHAR
                POSTED,"posted",DATE
                STAMPED,"stamped",TIMESTAMP
                POSTED_AS_TEXT,"posted_copy",VARCHAR
            mapping:
              canonicalName: ev
              rawName: ev
              rules[4]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                POSTED,POSTED,DIRECT
                STAMPED,STAMPED,DIRECT
                POSTED_AS_TEXT,POSTED_AS_TEXT,DIRECT
            """;

    /** Real DATE / TIMESTAMP cells (date-styled serials), plus a text cell in the DATE column. */
    private static File writeDatedWorkbook(Connection conn, Path dir) throws Exception {
        File f = dir.resolve("dated.xlsx").toFile();
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES"
                    + " ('A00001', DATE '2026-08-03', TIMESTAMP '2026-08-03 10:30:15', DATE '2026-08-03'),"
                    + " ('B00002', DATE '2026-09-30', TIMESTAMP '2026-09-30 23:59:59', DATE '2026-09-30')"
                    + ") v(account, posted, stamped, posted_copy) ORDER BY account)"
                    + " TO '" + f.getAbsolutePath().replace("\\", "/").replace("'", "''")
                    + "' WITH (FORMAT xlsx, HEADER true)");
        }
        return f;
    }

    @Test
    void aDateCellInADateDeclaredFieldLandsAsADateNotASerial(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema_dt.toon");
        Files.writeString(schema, DATED_SCHEMA, StandardCharsets.UTF_8);
        PipelineConfig cfg = loadWithSchema(dir, "dt", PARSING, schema,
                "    date_formats[1]: \"%Y-%m-%d\"\n"
                + "    timestamp_formats[1]: \"%Y-%m-%d %H:%M:%S\"\n");
        try (Connection conn = open()) {
            File wb = writeDatedWorkbook(conn, dir);
            DuckDbCsvIngester.ingest(wb, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("2026-08-03", "2026-09-30"), col(conn, "raw_f0", "POSTED"));
            assertEquals(List.of("2026-08-03 10:30:15", "2026-09-30 23:59:59"), col(conn, "raw_f0", "STAMPED"));
            assertEquals(List.of("46237.0", "46295.0"), col(conn, "raw_f0", "POSTED_AS_TEXT"),
                    "a VARCHAR-declared field keeps exactly what all_varchar yields — no reinterpretation");

            // ...and it types + partitions downstream: the whole point of the row. (__src_id is the
            // lineage column the batch orchestrator stamps; ingest alone does not add it.)
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE raw_f0 ADD COLUMN __src_id INTEGER DEFAULT 0");
            }
            DataTransformer.materialize(conn, cfg.schemas().single(), cfg, "raw_f0", "t_f0");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT typeof(POSTED), POSTED::VARCHAR, typeof(STAMPED),"
                         + " STAMPED::VARCHAR, year, month, day FROM t_f0 ORDER BY ACCOUNT_NUMBER")) {
                assertTrue(rs.next());
                assertEquals("DATE", rs.getString(1));
                assertEquals("2026-08-03", rs.getString(2));
                assertEquals("TIMESTAMP", rs.getString(3));
                assertEquals("2026-08-03 10:30:15", rs.getString(4));
                assertEquals("2026", rs.getString(5));
                assertEquals("08", rs.getString(6));
                assertEquals("03", rs.getString(7));
                assertTrue(rs.next());
                assertEquals("2026-09-30 23:59:59", rs.getString(4));
                assertEquals("30", rs.getString(7));
            }
        }
    }

    /** The landed text is written in the pipeline's OWN first format, so its typing always parses it. */
    @Test
    void aDateCellLandsInThePipelinesFirstDeclaredFormat(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema_fmt.toon");
        Files.writeString(schema, DATED_SCHEMA, StandardCharsets.UTF_8);
        PipelineConfig cfg = loadWithSchema(dir, "fmt", PARSING, schema,
                "    date_formats[2]: \"%d/%m/%Y\",\"%Y-%m-%d\"\n"
                + "    timestamp_formats[1]: \"%d/%m/%Y %H:%M:%S\"\n");
        try (Connection conn = open()) {
            File wb = writeDatedWorkbook(conn, dir);
            DuckDbCsvIngester.ingest(wb, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("03/08/2026", "30/09/2026"), col(conn, "raw_f0", "POSTED"));
            assertEquals(List.of("03/08/2026 10:30:15", "30/09/2026 23:59:59"), col(conn, "raw_f0", "STAMPED"));
        }
    }

    /** A TEXT cell in a date-declared field is not a serial and passes through untouched. */
    @Test
    void aTextCellInADateDeclaredFieldIsNotReinterpreted(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "tx", PARSING);   // SCHEMA declares EVENT_DATE DATE
        try (Connection conn = open()) {
            File f = dir.resolve("tx.xlsx").toFile();
            try (Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT * FROM (VALUES ('A00001', '2020-04-03', 1.0),"
                        + " ('B00002', '20260803', 2.0)) v(account, event_date, amount) ORDER BY account)"
                        + " TO '" + f.getAbsolutePath().replace("\\", "/").replace("'", "''")
                        + "' WITH (FORMAT xlsx, HEADER true)");
            }
            DuckDbCsvIngester.ingest(f, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("2020-04-03", "20260803"), col(conn, "raw_f0", "EVENT_DATE"),
                    "an ISO text date and an 8-digit yyyymmdd text (beyond any Excel serial) stay as keyed");
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
        return loadWithSchema(dir, tag, parsingBlock, schema,
                "    date_formats[1]: \"%Y-%m-%d\"\n"
                + "    timestamp_formats[1]: \"%Y-%m-%d\"\n");
    }

    private static PipelineConfig loadWithSchema(Path dir, String tag, String parsingBlock, Path schema,
                                                 String formatLines) throws Exception {
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
                formatLines +
                parsingBlock;
        Path p = dir.resolve("xlsx_" + tag + "_pipeline.toon");
        Files.writeString(p, pipe, StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }
}
