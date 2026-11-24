package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies typed {@link DuckDbRecordSink#defineTyped} staging table creation, safe {@code TRY_CAST}
 * handling for invalid values, backwards compatibility for {@link DuckDbRecordSink#define}, and
 * {@code PipelineConfigParser.asn1PluginBlock} forwarding of {@code event_roots},
 * {@code header_fields}, and {@code field_decoders}.
 */
class TypedDuckDbRecordSinkTest {

    @Test
    void defineTypedCreatesTypedColumnsAndSafelyNullsInvalidValues(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = setupTypedPipeline(dir);
        DuckDbUtil.loadDriver();
        try (Connection conn = DuckDbUtil.openInMemory(null);
             DuckDbRecordSink sink = new DuckDbRecordSink(conn, 1, cfg, "batch1", "sample", "sample.bin", 0, true)) {

            Map<String, String> sqlTypes = new LinkedHashMap<>();
            sqlTypes.put("DURATION", "BIGINT");
            sqlTypes.put("EVENT_TS", "TIMESTAMP");
            sqlTypes.put("CHARGE", "DOUBLE");
            sqlTypes.put("ROAMING", "BOOLEAN");
            sqlTypes.put("EVENT_TYPE", "VARCHAR");

            sink.defineTyped("CALL", List.of("DURATION", "EVENT_TS", "CHARGE", "ROAMING", "EVENT_TYPE"), sqlTypes);
            sink.emit("CALL", "42", "2026-01-15 10:20:30", "12.5", "true", "CALL");
            sink.emit("CALL", "not-a-number", "invalid-ts", "bad-double", "maybe", "CALL");
            sink.emit("CALL", "", null, "", "", "CALL");
            sink.finish();

            String table = sink.rawTables().get("CALL");
            assertNotNull(table);

            Map<String, String> actualTypes = new LinkedHashMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("DESCRIBE \"" + table + "\"")) {
                while (rs.next()) {
                    actualTypes.put(rs.getString("column_name"), rs.getString("column_type"));
                }
            }
            assertEquals("BIGINT", actualTypes.get("DURATION"));
            // Date/timestamp columns stage as VARCHAR so DataTransformer.materialize handles TRY_STRPTIME/telecom formats
            assertEquals("VARCHAR", actualTypes.get("EVENT_TS"));
            assertEquals("DOUBLE", actualTypes.get("CHARGE"));
            assertEquals("BOOLEAN", actualTypes.get("ROAMING"));
            assertEquals("VARCHAR", actualTypes.get("EVENT_TYPE"));

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT DURATION, EVENT_TS, CHARGE, ROAMING, EVENT_TYPE FROM \"" + table + "\" ORDER BY rowid")) {
                assertTrue(rs.next());
                assertEquals(42L, rs.getLong("DURATION"));
                assertEquals("2026-01-15 10:20:30", rs.getString("EVENT_TS"));
                assertEquals(12.5d, rs.getDouble("CHARGE"), 0.0001);
                assertTrue(rs.getBoolean("ROAMING"));
                assertEquals("CALL", rs.getString("EVENT_TYPE"));

                assertTrue(rs.next());
                assertNull(rs.getObject("DURATION"));
                assertEquals("invalid-ts", rs.getString("EVENT_TS"));
                assertNull(rs.getObject("CHARGE"));
                assertNull(rs.getObject("ROAMING"));
                assertEquals("CALL", rs.getString("EVENT_TYPE"));

                assertTrue(rs.next());
                assertNull(rs.getObject("DURATION"));
                assertNull(rs.getObject("EVENT_TS"));
                assertNull(rs.getObject("CHARGE"));
                assertNull(rs.getObject("ROAMING"));
                assertEquals("CALL", rs.getString("EVENT_TYPE"));

                assertFalse(rs.next());
            }
        }
    }

    @Test
    void defineWithoutTypesPreservesVarcharBackwardsCompatibility(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = setupTypedPipeline(dir);
        DuckDbUtil.loadDriver();
        try (Connection conn = DuckDbUtil.openInMemory(null);
             DuckDbRecordSink sink = new DuckDbRecordSink(conn, 2, cfg, "batch2", "sample", "sample.bin", 0, true)) {
            sink.define("CALL", List.of("DURATION", "EVENT_TS", "CHARGE", "ROAMING", "EVENT_TYPE"));
            sink.emit("CALL", "42", "2026-01-15 10:20:30", "12.5", "true", "CALL");
            sink.finish();

            String table = sink.rawTables().get("CALL");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("DESCRIBE \"" + table + "\"")) {
                while (rs.next()) {
                    String col = rs.getString("column_name");
                    if (!"__src_id".equals(col)) {
                        assertEquals("VARCHAR", rs.getString("column_type"), "column " + col);
                    }
                }
            }
        }
    }

    @Test
    void asn1PluginBlockForwardsRoutingAndFieldDecoders(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = setupTypedPipeline(dir);
        Map<String, Object> ic = cfg.schemas().ingesterConfig();
        assertNotNull(ic);
        assertEquals(Map.of("CallRecord", "CALL"), ic.get("event_roots"));
        assertEquals(List.of("Header.sequenceNumber"), ic.get("header_fields"));
        assertEquals(Map.of("CallRecord.calledNumber", "tbcd"), ic.get("field_decoders"));
    }

    private static PipelineConfig setupTypedPipeline(Path dir) throws Exception {
        Path callSchema = dir.resolve("call_schema.toon");
        Files.writeString(callSchema, """
                raw:
                  name: call
                  format: ASN1
                  fields[5]{name,type}:
                    DURATION,BIGINT
                    EVENT_TS,TIMESTAMP
                    CHARGE,DOUBLE
                    ROAMING,BOOLEAN
                    EVENT_TYPE,VARCHAR
                mapping:
                  canonicalName: call
                  rawName: call
                  rules[5]{targetColumn,sourceExpression,transformType}:
                    DURATION,DURATION,DIRECT
                    EVENT_TS,EVENT_TS,DIRECT
                    CHARGE,CHARGE,DIRECT
                    ROAMING,ROAMING,DIRECT
                    EVENT_TYPE,EVENT_TYPE,DIRECT
                """);

        Path grammar = dir.resolve("sample.asn");
        Files.writeString(grammar, """
                Sample DEFINITIONS IMPLICIT TAGS ::= BEGIN
                CallRecord ::= SEQUENCE {
                  duration INTEGER,
                  calledNumber OCTET STRING
                }
                END
                """);

        String d = dir.toString().replace("\\", "/");
        Path pipeline = dir.resolve("pipeline.toon");
        Files.writeString(pipeline, """
                name: typed-asn1-test
                version: 1
                active: true
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                  log_dir: %s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.bin"
                  csv_settings:
                    frontend: asn1
                    asn1:
                      grammar_file: %s
                      root_type: CallRecord
                      segments:
                        CALL: %s
                      event_roots:
                        CallRecord: CALL
                      header_fields[1]: Header.sequenceNumber
                      field_decoders:
                        CallRecord.calledNumber: tbcd
                """.formatted(
                d, d, d, d, d, d, d, d,
                grammar.getFileName(),
                callSchema.getFileName()));
        return PipelineConfig.load(pipeline.toString());
    }
}
