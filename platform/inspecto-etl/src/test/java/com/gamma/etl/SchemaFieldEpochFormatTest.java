package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MAPPING-EPOCH-TIMESTAMP-1 — the reserved field formats {@code epoch} (seconds) and {@code epoch_ms}
 * (milliseconds) land a count since 1970 as a {@code TIMESTAMP} holding its UTC wall clock, with {@code 0}
 * as "none" (NULL, not a failure) and every other non-convertible value a counted coercion failure — the
 * policy a strptime miss already has. Before this, {@code epoch} was refused at load (no {@code %} directive)
 * and the only way in was a {@code sql} step with {@code to_timestamp(x::BIGINT)} and a {@code 0} guard.
 */
class SchemaFieldEpochFormatTest {

    /** 1700000000 s since 1970 = 2023-11-14 22:13:20 UTC. */
    private static final LocalDateTime T = LocalDateTime.of(2023, 11, 14, 22, 13, 20);

    /** ID + TS, TS a TIMESTAMP read with {@code format} and a DATE_DAY partition cut from it. */
    private static Map<String, Object> schema(String format) {
        Map<String, Object> ts = new LinkedHashMap<>();
        ts.put("name", "TS");
        ts.put("selector", "1");
        ts.put("type", "TIMESTAMP");
        ts.put("format", format);
        return Map.of(
                "raw", Map.of("fields", List.of(Map.of("name", "ID", "selector", "0", "type", "VARCHAR"), ts)),
                "partitions", List.of(Map.of("column", "day", "source", "TS", "type", "DATE_DAY")),
                "mapping", Map.of("fields", List.of(
                        Map.of("name", "ID", "from", "ID", "fn", "keep"),
                        Map.of("name", "TS", "from", "TS", "fn", "keep"))));
    }

    private static PipelineConfig config(Path dir) throws Exception {
        return PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
    }

    /** {@code ID|TS|day} per row of {@code transformed}, ordered by ID. */
    private static List<String> landed(Statement st) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT ID, typeof(TS), TS, day FROM transformed ORDER BY ID")) {
            while (rs.next()) {
                assertEquals("TIMESTAMP", rs.getString(2));
                LocalDateTime v = rs.getObject(3, LocalDateTime.class);
                out.add(rs.getString(1) + "|" + v + "|" + rs.getString(4));
            }
        }
        return out;
    }

    /**
     * Seconds land as the UTC wall clock whatever the DuckDB session zone is (the session zone is the
     * host's, so {@code to_timestamp(x)::TIMESTAMP} would move with the machine); {@code 0} and a blank are
     * NULL and are NOT counted as failures; the partition is cut from the same instant.
     */
    @Test
    void epochSecondsLandAsTheUtcTimestampWithZeroAsNoneOnAnyHostZone(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        File db = DuckDbUtil.tempDbFile("epoch_s_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("SET TimeZone = 'America/New_York'");   // a host zone that is not UTC
            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES "
                    + "('a', '1700000000', 0), ('b', '0', 0), ('c', '', 0), ('d', ' 1700003600 ', 0))"
                    + " t(ID, TS, __src_id)");
            assertEquals(0, DataTransformer.countCastFailures(conn, schema("epoch"), cfg, "raw_input"),
                    "0 means none and a blank is absent — neither is a failed coercion");
            DataTransformer.materialize(conn, schema("epoch"), cfg);
            assertEquals(List.of("a|" + T + "|14", "b|null|null", "c|null|null",
                    "d|" + T.plusHours(1) + "|14"), landed(st));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void epochMsReadsMillisecondsAndTheFormatIsCaseInsensitive(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        File db = DuckDbUtil.tempDbFile("epoch_ms_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES "
                    + "('a', '1700000000123', 0), ('b', '0', 0)) t(ID, TS, __src_id)");
            Identifiers.validateSchema(schema("EPOCH_MS"), "t");
            DataTransformer.materialize(conn, schema("EPOCH_MS"), cfg);
            assertEquals(List.of("a|" + T.plusNanos(123_000_000) + "|14", "b|null|null"), landed(st));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * A value that is not a whole count, or is outside the TIMESTAMP range, is NULL — never a failed batch —
     * and counted, exactly as a value a strptime format does not match. {@code TRY_CAST} alone would round
     * {@code 1700000000.5} and accept {@code 1.7e9}; both are refused rather than guessed.
     */
    @Test
    void aNonIntegerOrOutOfRangeCountIsANullAndACountedFailure(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        File db = DuckDbUtil.tempDbFile("epoch_bad_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES "
                    + "('a', '1700000000', 0), ('b', 'abc', 0), ('c', '1700000000.5', 0), ('d', '1.7e9', 0),"
                    + " ('e', '99999999999999', 0), ('f', '99999999999999999999999', 0), ('g', '0', 0))"
                    + " t(ID, TS, __src_id)");
            assertEquals(5, DataTransformer.countCastFailures(conn, schema("epoch"), cfg, "raw_input"));
            DataTransformer.materialize(conn, schema("epoch"), cfg);
            List<String> rows = landed(st);
            assertEquals("a|" + T + "|14", rows.get(0));
            for (String r : rows.subList(1, rows.size())) assertTrue(r.endsWith("|null|null"), r);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * Review fix: the TIMESTAMP range is guarded on the COUNT. Below 290309-12-22 (BC) {@code make_timestamp}
     * returned a value {@code TRY} did not catch and that could not be rendered later ("Date out of range in
     * timestamp conversion") — written uncounted. Both bounds, both units: the bound itself lands, one past it
     * is a counted NULL, and every landed value renders.
     */
    @Test
    void theTimestampRangeIsGuardedAtBothBoundsForBothUnits(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        File db = DuckDbUtil.tempDbFile("epoch_range_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            assertEquals(List.of(
                            "-9223372022400|290309-12-22 (BC) 00:00:00", "-9223372022401|null",
                            "-9223372036854|null", "9223372036854|294247-01-10 04:00:54", "9223372036855|null"),
                    rendered(st, "epoch",
                            "-9223372022400", "-9223372022401", "-9223372036854", "9223372036854", "9223372036855"));
            assertEquals(List.of(
                            "-9223372022400000|290309-12-22 (BC) 00:00:00", "-9223372022400001|null",
                            "-9223372036854775|null", "9223372036854775|294247-01-10 04:00:54.775",
                            "9223372036854776|null"),
                    rendered(st, "epoch_ms", "-9223372022400000", "-9223372022400001", "-9223372036854775",
                            "9223372036854775", "9223372036854776"));
            assertEquals(SchemaFieldTypes.TS_MIN_MICROS, -9223372022400L * 1_000_000L);

            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES "
                    + "('a', '-9223372022400', 0), ('b', '-9223372022401', 0), ('c', '-9223372036854', 0),"
                    + " ('d', '9223372036855', 0)) t(ID, TS, __src_id)");
            assertEquals(3, DataTransformer.countCastFailures(conn, schema("epoch"), cfg, "raw_input"),
                    "a count outside the range is a counted failure, not a silent write");
            DataTransformer.materialize(conn, schema("epoch"), cfg);
            try (ResultSet rs = st.executeQuery("SELECT count(TS), max(TS::VARCHAR) FROM transformed")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("290309-12-22 (BC) 00:00:00", rs.getString(2), "every landed value renders");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** {@code value|epochSql(value)::VARCHAR} per input, in input order. */
    private static List<String> rendered(Statement st, String fmt, String... values) throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < values.length; i++)
            rows.append(i == 0 ? "" : ", ").append("(").append(i).append(", '").append(values[i]).append("')");
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT v, (" + SchemaFieldTypes.epochSql("v", fmt)
                + ")::VARCHAR FROM (VALUES " + rows + ") t(i, v) ORDER BY i")) {
            while (rs.next()) out.add(rs.getString(1) + "|" + rs.getString(2));
        }
        return out;
    }

    /**
     * Review fix: DuckDB's one-argument {@code TRIM} strips spaces only, so a tab-padded count used to be a
     * counted NULL. Tabs and CR/LF are trimmed too, and a whitespace-only cell is "none" like a blank.
     */
    @Test
    void tabsAndLineEndsAroundACountAreTrimmed(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        File db = DuckDbUtil.tempDbFile("epoch_tab_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE raw_input AS SELECT * FROM (VALUES "
                    + "('a', chr(9) || '1700000000' || chr(13), 0), ('b', chr(9) || '0' || chr(10), 0),"
                    + " ('c', chr(9), 0)) t(ID, TS, __src_id)");
            assertEquals(0, DataTransformer.countCastFailures(conn, schema("epoch"), cfg, "raw_input"));
            DataTransformer.materialize(conn, schema("epoch"), cfg);
            assertEquals(List.of("a|" + T + "|14", "b|null|null", "c|null|null"), landed(st));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** A source zone never applies: an epoch is already UTC (the pipeline-level zone reaches every field). */
    @Test
    void aSourceZoneDoesNotShiftAnEpochColumn() {
        String col = "\"raw\".\"TS\"";
        String zoned = SchemaFieldTypes.castSql(col, "TIMESTAMP", List.of(), List.of("epoch"), "'Asia/Kolkata'");
        assertEquals(SchemaFieldTypes.epochSql(col, "epoch"), zoned);
        assertFalse(zoned.contains("timezone("), zoned);
    }

    /** Config load: an epoch format is honoured on TIMESTAMP only, and never beside a zone it would ignore. */
    @Test
    void validateSchemaHonoursEpochOnTimestampOnly() {
        assertDoesNotThrow(() -> Identifiers.validateSchema(schema("epoch"), "t"));
        assertDoesNotThrow(() -> Identifiers.validateSchema(schema(" Epoch "), "t"));
        assertDoesNotThrow(() -> Identifiers.validateSchema(schema("epoch_ms"), "t"));
        for (String type : List.of("DATE", "TIMESTAMPTZ", "BIGINT"))
            assertThrows(IllegalArgumentException.class, () -> Identifiers.validateSchema(Map.of("raw",
                    Map.of("fields", List.of(Map.of("name", "X", "type", type, "format", "epoch")))), "t"), type);
        for (String key : List.of("timezone", "timezone_column")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Identifiers.validateSchema(Map.of("raw", Map.of("fields", List.of(
                            Map.of("name", "Z", "type", "VARCHAR"),
                            Map.of("name", "X", "type", "TIMESTAMP", "format", "epoch",
                                    key, "timezone".equals(key) ? "Asia/Kolkata" : "Z")))), "t"), key);
            assertTrue(e.getMessage().contains("already UTC"), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validateSchema(schema("epoch_s"), "t"),
                "only the two reserved spellings are epoch — anything else must still be a strptime pattern");
    }
}
