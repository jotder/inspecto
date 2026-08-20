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
 * Tests for the {@code frontend: json} parsing frontend (docs/parsing-options-reference.md §5/§6.4):
 * the unified {@code parsing:} block selects {@code read_ndjson}/{@code read_json}, each schema
 * field lands as a VARCHAR column keyed by {@code raw.fields[].selector} (= the top-level JSON key),
 * and the typing/mapping/partition backend is reused verbatim.
 */
class JsonParsingTest {

    // Schema: selectors are JSON keys, not column indices.
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
              frontend: json
              json:
                format: newline
            """;

    @Test
    void ndjsonRowsLandAsVarcharColumns(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "j1", PARSING);
        assertNotNull(cfg.json(), "json frontend parsed from the parsing: block");
        assertEquals("newline", cfg.json().format());
        assertTrue(DuckDbCsvIngester.usesDuckDb(cfg), "json frontend is always native");

        File jsonl = write(dir, "ev.jsonl", """
                {"account":"A00001","event_date":"2020-04-03","amount":1234.5}
                {"account":"B00002","event_date":"2020-04-04","amount":9999.0}
                {this is not json at all
                """);
        try (Connection conn = open()) {
            IngestResult r = DuckDbCsvIngester.ingest(jsonl, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows(), "malformed line dropped, two records land");
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
            assertEquals(List.of("2020-04-03", "2020-04-04"), col(conn, "raw_f0", "EVENT_DATE"));
            assertEquals(List.of("1234.5", "9999.0"), col(conn, "raw_f0", "AMOUNT"),
                    "JSON numbers cast to VARCHAR at ingest (typed later by DataTransformer)");
        }
    }

    @Test
    void arrayFormatReadsAJsonArrayDocument(@TempDir Path dir) throws Exception {
        String parsing = PARSING.replace("format: newline", "format: array");
        PipelineConfig cfg = load(dir, "j2", parsing);
        assertEquals("array", cfg.json().format());

        File json = write(dir, "ev.json", """
                [{"account":"A00001","event_date":"2020-04-03","amount":1},
                 {"account":"B00002","event_date":"2020-04-04","amount":2}]
                """);
        try (Connection conn = open()) {
            IngestResult r = DuckDbCsvIngester.ingest(json, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows());
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
        }
    }

    @Test
    void missingKeysLandAsNull(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "j3", PARSING);
        File jsonl = write(dir, "ev.jsonl",
                "{\"account\":\"A00001\",\"event_date\":\"2020-04-03\"}\n");
        try (Connection conn = open()) {
            DuckDbCsvIngester.ingest(jsonl, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(1, col(conn, "raw_f0", "ACCOUNT_NUMBER").size());
            List<String> amount = col(conn, "raw_f0", "AMOUNT");
            assertNull(amount.get(0), "absent JSON key lands as NULL");
        }
    }

    // ── validation ──────────────────────────────────────────────────────────────

    @Test
    void unknownJsonFormatFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> load(dir, "bad", PARSING.replace("format: newline", "format: xmlish")));
        assertTrue(e.getMessage().contains("json.format"), e.getMessage());
    }

    @Test
    void recordsPathOnNdjsonFailsLoad(@TempDir Path dir) {
        // NDJSON has no enclosing document for a path to walk — each line already IS a record.
        String parsing = PARSING + "    records_path: \"$.data\"\n";
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "rp", parsing));
        assertTrue(e.getMessage().contains("records_path"), e.getMessage());
        assertTrue(e.getMessage().contains("newline"), e.getMessage());
    }

    // ── nested records_path ─────────────────────────────────────────────────────

    /** A document whose records are buried two levels down, alongside unrelated wrapper keys. */
    private static final String NESTED_DOC = """
            {"meta":{"exported":"2020-04-05","count":2},
             "payload":{"records":[
               {"account":"A00001","event_date":"2020-04-03","amount":1},
               {"account":"B00002","event_date":"2020-04-04","amount":2}
             ]}}
            """;

    private static String nestedParsing(String recordsPath) {
        return PARSING.replace("format: newline", "format: auto")
                + "    records_path: \"" + recordsPath + "\"\n";
    }

    @Test
    void nestedRecordsPathReadsRecordsFromInsideTheDocument(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "n1", nestedParsing("$.payload.records"));
        assertEquals("$.payload.records", cfg.json().recordsPath());

        File json = write(dir, "ev.json", NESTED_DOC);
        try (Connection conn = open()) {
            IngestResult r = DuckDbCsvIngester.ingest(json, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(2, r.parsedRows(), "both nested records land; the wrapper is not a row");
            assertEquals(List.of("A00001", "B00002"), col(conn, "raw_f0", "ACCOUNT_NUMBER"));
            assertEquals(List.of("2020-04-03", "2020-04-04"), col(conn, "raw_f0", "EVENT_DATE"));
        }
    }

    @Test
    void recordsPathWorksWithoutTheDollarPrefix(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "n2", nestedParsing("payload.records"));
        File json = write(dir, "ev.json", NESTED_DOC);
        try (Connection conn = open()) {
            assertEquals(2, DuckDbCsvIngester.ingest(json, conn, cfg.schemas().single(), cfg, "raw_f0").parsedRows());
        }
    }

    /**
     * A nested records_path composes with dotted FIELD selectors — the two path layers are
     * independent, so reaching {@code party.number} inside a nested record needs no extra machinery.
     */
    @Test
    void nestedRecordsPathComposesWithDottedFieldSelectors(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema_n3.toon");
        Files.writeString(schema, """
                partitionKey: EVENT_DATE
                raw:
                  name: ev
                  format: CSV
                  fields[2]{name,selector,type}:
                    ACCOUNT_NUMBER,"account",VARCHAR
                    EVENT_DATE,"nested.event_date",DATE
                mapping:
                  canonicalName: ev
                  rawName: ev
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """, StandardCharsets.UTF_8);
        PipelineConfig cfg = loadWithSchema(dir, "n3", nestedParsing("$.payload.records"), schema);

        File json = write(dir, "ev.json", """
                {"payload":{"records":[
                  {"account":"A00001","nested":{"event_date":"2020-04-03"}},
                  {"account":"B00002","nested":{"event_date":"2020-04-04"}}
                ]}}
                """);
        try (Connection conn = open()) {
            DuckDbCsvIngester.ingest(json, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(List.of("2020-04-03", "2020-04-04"), col(conn, "raw_f0", "EVENT_DATE"),
                    "the field selector walks INSIDE each unnested record");
        }
    }

    @Test
    void missingRecordsPathFailsTheFileRatherThanIngestingNothing(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "n4", nestedParsing("$.payload.absent"));
        File json = write(dir, "ev.json", NESTED_DOC);
        try (Connection conn = open()) {
            Exception e = assertThrows(Exception.class,
                    () -> DuckDbCsvIngester.ingest(json, conn, cfg.schemas().single(), cfg, "raw_f0"));
            assertTrue(e.getMessage().contains("records_path"), e.getMessage());
        }
    }

    @Test
    void recordsPathNamingAnObjectFailsRatherThanIngestingNothing(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "n5", nestedParsing("$.meta"));
        File json = write(dir, "ev.json", NESTED_DOC);
        try (Connection conn = open()) {
            Exception e = assertThrows(Exception.class,
                    () -> DuckDbCsvIngester.ingest(json, conn, cfg.schemas().single(), cfg, "raw_f0"));
            assertTrue(e.getMessage().contains("ARRAY"), e.getMessage());
        }
    }

    @Test
    void emptyRecordsArrayIsZeroRowsNotAnError(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "n6", nestedParsing("$.payload.records"));
        File json = write(dir, "ev.json", "{\"payload\":{\"records\":[]}}\n");
        try (Connection conn = open()) {
            assertEquals(0, DuckDbCsvIngester.ingest(json, conn, cfg.schemas().single(), cfg, "raw_f0").parsedRows(),
                    "an empty array honestly means zero records");
        }
    }

    @Test
    void recordsPathSegmentsToleratesTheDollarPrefixAndEscapedDots() {
        assertEquals(List.of("payload", "records"), DuckDbCsvIngester.recordsPathSegments("$.payload.records"));
        assertEquals(List.of("payload", "records"), DuckDbCsvIngester.recordsPathSegments("payload.records"));
        assertEquals(List.of("odd.key"), DuckDbCsvIngester.recordsPathSegments("odd\\.key"));
        assertEquals(List.of("data"), DuckDbCsvIngester.recordsPathSegments("$data"));
    }

    // ── J1: the read_json reader knobs (array/auto only) ────────────────────────

    /**
     * ⚠ format: auto over multi-line JSON-ish content resolves to DuckDB's OWN newline_delimited
     * shape by its own sniff — which is the only shape ignore_errors is honored for at all (probed;
     * see {@link #ignoreErrorsUnderExplicitArrayFormatFailsLoad}). This test's fixture works BECAUSE
     * it looks like NDJSON to the sniffer, not because ignore_errors is unconditionally honored.
     */
    @Test
    void ignoreErrorsSkipsAMalformedRecordInsteadOfFailingTheFile(@TempDir Path dir) throws Exception {
        String parsing = PARSING.replace("format: newline", "format: auto") + "    ignore_errors: true\n";
        PipelineConfig cfg = load(dir, "ie", parsing);
        assertTrue(cfg.json().ignoreErrors());

        File json = write(dir, "ev.json", """
                {"account":"A00001","event_date":"2020-04-03","amount":1}
                {this is not json at all
                {"account":"B00002","event_date":"2020-04-04","amount":2}
                """);
        try (Connection conn = open()) {
            IngestResult r = DuckDbCsvIngester.ingest(json, conn, cfg.schemas().single(), cfg, "raw_f0");
            // ⚠ Probed semantics, not the docs' surface: with an explicit columns map, read_json's
            // ignore_errors keeps the malformed record as an all-NULL row rather than dropping it —
            // the file survives, and the honest description is "NULL row", never "skipped".
            assertEquals(3, r.parsedRows(), "the malformed record lands as an all-NULL row, not fatal");
            List<String> accounts = col(conn, "raw_f0", "ACCOUNT_NUMBER");
            assertTrue(accounts.containsAll(List.of("A00001", "B00002")), String.valueOf(accounts));
            assertTrue(accounts.contains(null), "the malformed record's columns are NULL");
        }
    }

    @Test
    void withoutIgnoreErrorsAMalformedRecordFailsTheFile(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "ie0", PARSING.replace("format: newline", "format: auto"));
        File json = write(dir, "ev.json",
                "{\"account\":\"A00001\",\"event_date\":\"2020-04-03\",\"amount\":1}\n{oops\n");
        try (Connection conn = open()) {
            assertThrows(Exception.class,
                    () -> DuckDbCsvIngester.ingest(json, conn, cfg.schemas().single(), cfg, "raw_f0"),
                    "read_json fails the unreadable file — quarantine, never a silent partial parse");
        }
    }

    /**
     * ⚠ `maximum_object_size` is UNOBSERVABLE at fixture scale — probed on 1.5.2.1: read_json clamps
     * it up to its internal buffer, so even size=8 over a 500-byte object parses fine; the bound only
     * bites on documents beyond the buffer scale (its real job: a memory ceiling for huge docs).
     * The honest pin is therefore assembly-level: the option reaches read_json correctly SPELLED and
     * ACCEPTED — a misspelled named parameter raises a Binder Error and this test goes red.
     */
    @Test
    void maximumObjectSizeIsEmittedAndAcceptedByTheReader(@TempDir Path dir) throws Exception {
        String parsing = PARSING.replace("format: newline", "format: auto")
                + "    maximum_object_size: 4194304\n";
        PipelineConfig cfg = load(dir, "mos", parsing);
        assertEquals(4194304, cfg.json().maximumObjectSize());

        File json = write(dir, "ev.json",
                "{\"account\":\"A00001\",\"event_date\":\"2020-04-03\",\"amount\":1}\n");
        try (Connection conn = open()) {
            IngestResult r = DuckDbCsvIngester.ingest(json, conn, cfg.schemas().single(), cfg, "raw_f0");
            assertEquals(1, r.parsedRows(), "read_json accepted the emitted maximum_object_size option");
        }
    }

    /**
     * ⚠ Probed refutation: format: array is a genuine JSON-array document, and DuckDB HARD-REJECTS
     * ignore_errors for any shape other than newline_delimited — a config combining them would load
     * looking honored and fail every batch at ingest. Refused at config load instead.
     */
    @Test
    void ignoreErrorsUnderExplicitArrayFormatFailsLoad(@TempDir Path dir) {
        String parsing = PARSING.replace("format: newline", "format: array") + "    ignore_errors: true\n";
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "iea", parsing));
        assertTrue(e.getMessage().contains("format: array"), e.getMessage());
    }

    /** The knobs belong to read_json — NDJSON's line reader has neither, so they refuse at load. */
    @Test
    void readerKnobsOnNdjsonFailLoad(@TempDir Path dir) {
        for (String knob : List.of("    ignore_errors: true\n", "    maximum_object_size: 1024\n")) {
            Exception e = assertThrows(IllegalArgumentException.class,
                    () -> load(dir, "k" + knob.length(), PARSING + knob));
            assertTrue(e.getMessage().contains("array or auto"), e.getMessage());
        }
    }

    @Test
    void unknownFrontendFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> load(dir, "uf", "parsing:\n  frontend: xml\n"));
        assertTrue(e.getMessage().contains("Unknown parsing.frontend"), e.getMessage());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static List<String> col(Connection conn, String table, String c) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT \"" + c + "\" FROM \"" + table + "\" ORDER BY \"ACCOUNT_NUMBER\"")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static Connection open() throws Exception {
        return DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("json_"));
    }

    private static File write(Path dir, String name, String content) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), content);
        return f;
    }

    private static String fwd(Path p) { return p.toString().replace('\\', '/'); }

    /** Build + load a pipeline with the given top-level {@code parsing:} block. */
    private static PipelineConfig load(Path dir, String tag, String parsingBlock) throws Exception {
        Path schema = dir.resolve("schema_" + tag + ".toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        return loadWithSchema(dir, tag, parsingBlock, schema);
    }

    /** As {@link #load}, but against a caller-supplied schema toon. */
    private static PipelineConfig loadWithSchema(Path dir, String tag, String parsingBlock, Path schema)
            throws Exception {
        String d = fwd(dir);
        String pipe =
                "name: JSON_" + tag + "\n" +
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
                "  file_pattern: \"glob:**/*.jsonl\"\n" +
                "  schema_file: " + fwd(schema) + "\n" +
                "  csv_settings:\n" +
                "    date_formats[1]: \"%Y-%m-%d\"\n" +
                "    timestamp_formats[1]: \"%Y-%m-%d\"\n" +
                parsingBlock;
        Path p = dir.resolve("pipe_" + tag + ".toon");
        Files.writeString(p, pipe, StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }
}
