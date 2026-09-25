package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code raw.fields[].format} — a date-like field's own strptime pattern, which REPLACES the pipeline's
 * {@code date_formats} list for that field ({@link SchemaFieldTypes#formatsOf}). The mini pipeline's own
 * list is ISO-only, so every assertion here would read NULL without the field format.
 */
class SchemaFieldFormatTest {

    /** The mini schema with EVENT_DATE written as {@code March 22,2025} and a DATE_DAY partition on it. */
    private static Map<String, Object> schema(String format) {
        Map<String, Object> eventDate = new LinkedHashMap<>();
        eventDate.put("name", "EVENT_DATE");
        eventDate.put("selector", "2");
        eventDate.put("type", "DATE");
        eventDate.put("format", format);
        return Map.of(
                "raw", Map.of("fields", List.of(
                        Map.of("name", "ID", "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "AMT", "selector", "1", "type", "DOUBLE"),
                        eventDate)),
                "partitions", List.of(Map.of("column", "day", "source", "EVENT_DATE", "type", "DATE_DAY")),
                "mapping", Map.of("fields", List.of(
                        Map.of("name", "ID", "from", "ID", "fn", "keep"),
                        Map.of("name", "AMT", "from", "AMT", "fn", "keep"),
                        Map.of("name", "EVENT_DATE", "from", "EVENT_DATE", "fn", "keep"))));
    }

    private static PipelineConfig config(Path dir) throws Exception {
        return PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
    }

    @Test
    void aFieldFormatLandsHumanDatesAsDateAndCutsTheirPartition(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        File db = DuckDbUtil.tempDbFile("field_fmt_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES "
                    + "('a', '1.5', 'March 22,2025', 0),"
                    + "('b', '2.5', 'April 1,2025', 0)) t(ID, AMT, EVENT_DATE, __src_id)");
            assertEquals(0, DataTransformer.countCastFailures(conn, schema("%B %d,%Y"), cfg, "raw_input"));
            DataTransformer.materialize(conn, schema("%B %d,%Y"), cfg);
            try (ResultSet rs = st.executeQuery(
                    "SELECT typeof(EVENT_DATE), EVENT_DATE, day FROM transformed ORDER BY ID")) {
                assertTrue(rs.next());
                assertEquals("DATE", rs.getString(1));
                assertEquals(LocalDate.of(2025, 3, 22), rs.getObject(2, LocalDate.class));
                assertEquals("22", rs.getString(3), "the partition is cut from the same parse");
                assertTrue(rs.next());
                assertEquals(LocalDate.of(2025, 4, 1), rs.getObject(2, LocalDate.class));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * The format is the field's ONLY format — it does not fall back to the pipeline's list — and a value
     * it does not match is a counted coercion failure (the existing NULL-and-count policy), never silent.
     */
    @Test
    void aValueTheFormatDoesNotMatchIsACountedFailure(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        File db = DuckDbUtil.tempDbFile("field_fmt_bad_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            // '2025-03-22' matches the PIPELINE's ISO list, but not this field's format.
            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES "
                    + "('a', '1.5', 'March 22,2025', 0),"
                    + "('b', '2.5', '2025-03-22', 0),"
                    + "('c', '3.5', 'Marchh 1,2025', 0)) t(ID, AMT, EVENT_DATE, __src_id)");
            assertEquals(2, DataTransformer.countCastFailures(conn, schema("%B %d,%Y"), cfg, "raw_input"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void formatsOfSkipsBlankRowsAndFormatsForPrefersTheField() {
        Map<String, Object> s = Map.of("raw", Map.of("fields", List.of(
                Map.of("name", "A", "type", "DATE", "format", " %d %B %Y "),
                Map.of("name", "B", "type", "DATE", "format", ""))));
        assertEquals(Map.of("A", "%d %B %Y"), SchemaFieldTypes.formatsOf(s));
        assertEquals(List.of("%d %B %Y"), SchemaFieldTypes.formatsFor("%d %B %Y", List.of("%Y-%m-%d")));
        assertEquals(List.of("%Y-%m-%d"), SchemaFieldTypes.formatsFor(null, List.of("%Y-%m-%d")));
    }

    /** Config-load refuses every format shape that would load and then mislead. */
    @Test
    void validateSchemaFailsClosedOnAMisplacedOrUnsafeFormat() {
        assertDoesNotThrow(() -> Identifiers.validateSchema(schema("%B %d,%Y"), "t"));
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validateSchema(Map.of("raw",
                Map.of("fields", List.of(Map.of("name", "X", "type", "BIGINT", "format", "%Y")))), "t"),
                "a format on a non-date type is dead config");
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validateSchema(Map.of("raw",
                Map.of("fields", List.of(Map.of("name", "X", "format", "%Y")))), "t"),
                "an absent type means VARCHAR — the format would never apply");
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validateSchema(schema("%d' OR 1=1"), "t"),
                "a quote would break out of the SQL literal");
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validateSchema(schema("March"), "t"),
                "no directive — not a strptime pattern");
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validateSchema(schema("%Y-%m-%d %z"), "t"),
                "zone directives are refused as they are on date_formats");
    }
}
