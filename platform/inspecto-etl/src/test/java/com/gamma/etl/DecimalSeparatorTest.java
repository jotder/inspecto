package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} U3: the Grammar's decimal mark, {@code csv_settings.decimal_separator} (also spelled
 * {@code parsing.delimited.decimal_separator}). It loads fail-closed, and the engine honours it where text becomes a
 * number — the typing step's {@code keep} cast, the cast-failure audit and a number partition — so {@code 1.234,50}
 * under {@code ","} is stored as {@code 1234.5}. Absent, a number column is the plain cast it always was.
 */
class DecimalSeparatorTest {

    private static PipelineConfig.CsvSettings csv(Map<String, Object> csvSettings, Map<String, Object> parsing) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "DECIMALS");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1, "csv_settings", csvSettings));
        if (parsing != null) m.put("parsing", parsing);
        return PipelineConfig.fromMap(m).csv();
    }

    @Test
    void theKeyLoadsFromEitherSpellingAndRefusesAnyOtherMark() throws Exception {
        assertEquals(",", csv(Map.of("decimal_separator", ","), null).decimalSeparator());
        assertEquals(",", csv(Map.of(), Map.of("frontend", "delimited", "delimited", Map.of("decimal_separator", ","))).decimalSeparator());
        assertEquals(".", csv(Map.of("decimal_separator", "."), null).decimalSeparator());
        assertNull(csv(Map.of(), null).decimalSeparator(), "absent: the plain cast");
        Exception e = assertThrows(Exception.class, () -> csv(Map.of("decimal_separator", ";"), null));
        assertTrue(String.valueOf(e.getMessage()).contains("decimal_separator must be '.' or ','"), e.toString());
        assertEquals(",", PipelineConfig.fromMap(Map.of("name", "DECIMALS", "dirs", Map.of("poll", "in", "database", "out"),
                        "processing", Map.of("threads", 1, "csv_settings", Map.of("decimal_separator", ","))))
                        .forRecordReplay().csv().decimalSeparator(), "the record-replay copy keeps it too");
    }

    /** The values a {@code keep} of {@code type} stores for {@code values}, read with {@code csv}'s mark. */
    private static List<String> kept(PipelineConfig.CsvSettings csv, String type, List<String> values) throws Exception {
        Map<String, Object> schema = Map.of("raw", Map.of("fields", List.of(Map.of("name", "AMOUNT", "selector", "0", "type", type))));
        String expr = String.valueOf(RecordTransform.compile(List.of(Map.of("name", "AMOUNT", "from", "AMOUNT", "fn", "keep")),
                Map.of("AMOUNT", type), csv, SourceZones.of(schema, null), "raw_input", false).get(0).get("expr"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE raw_input (AMOUNT VARCHAR)");
            for (String v : values) st.execute("INSERT INTO raw_input VALUES ('" + v + "')");
            List<String> out = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT CAST(" + expr + " AS VARCHAR) FROM raw_input")) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        }
    }

    @Test
    void aCommaMarkReadsNumbersWrittenWithIt() throws Exception {
        PipelineConfig.CsvSettings comma = csv(Map.of("decimal_separator", ","), null);
        assertEquals(java.util.Arrays.asList("3.5", "1234.5", "1234567.25", null),
                kept(comma, "DOUBLE", List.of("3,50", "1.234,50", "1 234 567,25", "abc")));
        assertEquals(java.util.Arrays.asList("1234", null), kept(comma, "BIGINT", List.of("1.234", "x")));
        // a point mark drops comma grouping; absent is the plain cast, which reads neither '3,50' nor '1,234.50'
        assertEquals(List.of("1234.5"), kept(csv(Map.of("decimal_separator", "."), null), "DOUBLE", List.of("1,234.50")));
        assertEquals(java.util.Arrays.asList(null, null, "3.5"), kept(csv(Map.of(), null), "DOUBLE", List.of("3,50", "1,234.50", "3.50")));
        // text and dates are not numbers: the mark leaves them alone
        assertEquals(List.of("3,50"), kept(comma, "VARCHAR", List.of("3,50")));
    }

    @Test
    void theCastFailureAuditAndANumberPartitionReadTheSameMark() throws Exception {
        PipelineConfig.CsvSettings comma = csv(Map.of("decimal_separator", ","), null);
        Map<String, Object> schema = Map.of(
                "raw", Map.of("fields", List.of(Map.of("name", "AMOUNT", "selector", "0", "type", "DOUBLE"))),
                "mapping", Map.of("fields", List.of(Map.of("name", "AMOUNT", "from", "AMOUNT", "fn", "keep"))));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE raw_input (AMOUNT VARCHAR)");
            st.execute("INSERT INTO raw_input VALUES ('3,50'), ('1.234,50'), ('abc')");
            assertEquals(1, DataTransformer.countCastFailures(c, schema, comma, "raw_input"), "only 'abc' is lost");
            assertEquals(3, DataTransformer.countCastFailures(c, schema, csv(Map.of(), null), "raw_input"),
                    "without the key '3,50' and '1.234,50' are lost too");
            String part = TransformCompiler.partitionColumn(new PartitionDef("a", "AMOUNT", PartitionDef.Type.DOUBLE),
                    "raw_input", Map.of("AMOUNT", "DOUBLE"), comma);
            try (ResultSet rs = st.executeQuery("SELECT CAST(" + part + " AS VARCHAR) FROM raw_input LIMIT 1")) {
                rs.next();
                assertEquals("3.5", rs.getString(1));
            }
        }
    }
}
