package com.gamma.etl;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The source-time-zone policy: {@link SourceZones}, the compile sites it reaches, and the
 * fail-closed config gates.
 *
 * <p>The value assertions run a real DuckDB rather than comparing SQL strings, because every
 * load-bearing fact here is a DuckDB behaviour (what {@code timezone()} accepts, what the session
 * zone does to a naive cast, whether an unknown zone errors or nulls) and a string comparison would
 * pass against SQL that does the wrong thing.
 */
class SourceZonesTest {

    private static final List<String> DF = List.of("%Y-%m-%d");
    private static final List<String> TF = List.of("%Y-%m-%d %H:%M:%S");

    // ── the validation gate ───────────────────────────────────────────────────

    /**
     * The gate admits exactly {@link ZoneId#getAvailableZoneIds()}. That is only safe because that
     * set is a subset of what DuckDB accepts — <b>measured here, not assumed</b>. If a JDK or ICU
     * upgrade ever breaks the containment, this fails instead of a batch dying at run time on a zone
     * the config layer had already blessed.
     */
    @Test
    void everyZoneTheGateAdmitsIsOneDuckDbAccepts() throws Exception {
        File db = DuckDbUtil.tempDbFile("tz_containment_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            Set<String> duck = new HashSet<>();
            try (ResultSet rs = st.executeQuery("SELECT name FROM pg_timezone_names()")) {
                while (rs.next()) duck.add(rs.getString(1));
            }
            assertFalse(duck.isEmpty(), "pg_timezone_names() returned nothing — is ICU missing?");
            Set<String> admittedButUnknown = new TreeSet<>(ZoneId.getAvailableZoneIds());
            admittedButUnknown.removeAll(duck);
            assertTrue(admittedButUnknown.isEmpty(),
                    "validateZone would admit ids DuckDB rejects: " + admittedButUnknown);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * 🔴 Offset forms are the trap: {@code ZoneId.of} accepts {@code +05:30} and {@code Z}, DuckDB
     * raises <i>Unknown TimeZone</i> on both. They must die at config load, not mid-batch.
     */
    @Test
    void offsetFormsAndTyposAreRefused() {
        for (String bad : List.of("+05:30", "-08:00", "Z", "utc", "Not/AZone", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SourceZones.validateZone(bad, "raw.fields[T].timezone"),
                    "should have been refused: '" + bad + "'");
        }
        for (String ok : List.of("UTC", "Asia/Kolkata", "Europe/Berlin", "America/New_York", "Etc/GMT+5"))
            assertDoesNotThrow(() -> SourceZones.validateZone(ok, "raw.fields[T].timezone"));
    }

    /** The message has to name the key, or the operator cannot find which field is wrong. */
    @Test
    void refusalNamesTheKeyAndTheFix() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SourceZones.validateZone("+05:30", "raw.fields[CALL_TS].timezone"));
        assertTrue(e.getMessage().contains("raw.fields[CALL_TS].timezone"), e.getMessage());
        assertTrue(e.getMessage().contains("Asia/Kolkata"), "should suggest the IANA form: " + e.getMessage());
    }

    // ── precedence ────────────────────────────────────────────────────────────

    @Test
    void precedenceIsRowColumnThenFieldThenPipeline() {
        Map<String, Object> schema = Map.of("raw", Map.of("fields", List.of(
                Map.of("name", "A", "type", "TIMESTAMP", "timezone_column", "TZ"),
                Map.of("name", "B", "type", "TIMESTAMP", "timezone", "Europe/Berlin"),
                Map.of("name", "C", "type", "TIMESTAMP"),
                Map.of("name", "TZ", "type", "VARCHAR"))));

        SourceZones z = SourceZones.of(schema, "Asia/Kolkata");
        assertTrue(z.zoneArg("A", "raw_input").contains("pg_timezone_names()"),
                "a row-column zone must resolve per row");
        assertTrue(z.zoneArg("A", "raw_input").contains("\"raw_input\".\"TZ\""));
        assertTrue(z.zoneArg("A", "raw_input").contains("AS VARCHAR"),
                "the zone column must be re-stringified — lower() only binds to VARCHAR");
        assertEquals("'Europe/Berlin'", z.zoneArg("B", "raw_input"), "the field's own zone wins over the pipeline's");
        assertEquals("'Asia/Kolkata'", z.zoneArg("C", "raw_input"), "otherwise the pipeline default applies");

        SourceZones none = SourceZones.of(schema, null);
        assertEquals("'Europe/Berlin'", none.zoneArg("B", "raw_input"));
        assertNull(none.zoneArg("C", "raw_input"), "no policy at all ⇒ no zone argument");
        assertTrue(SourceZones.of(Map.of(), null).isEmpty());
    }

    /**
     * The default has to be a <b>literal</b> no-op, not an equivalent rewrite: every pipeline that
     * declares no zone must compile the byte-identical SQL it did before this feature existed.
     */
    @Test
    void noZonePolicyCompilesExactlyAsBefore() {
        for (String type : List.of("VARCHAR", "DATE", "TIMESTAMP", "BIGINT", "DOUBLE")) {
            assertEquals(SchemaFieldTypes.castSql("c", type, DF, TF),
                         SchemaFieldTypes.castSql("c", type, DF, TF, null),
                         "the zone-aware overload changed the default shape for " + type);
        }
    }

    /** ⚠ A DATE has no instant — shifting one would move a calendar day across midnight. */
    @Test
    void dateColumnsAreNeverShifted() {
        assertEquals(SchemaFieldTypes.castSql("c", "DATE", DF, TF),
                     SchemaFieldTypes.castSql("c", "DATE", DF, TF, "'Asia/Kolkata'"));
    }

    /** A TIMESTAMPTZ with no zone source is a bug that must have been refused at load. */
    @Test
    void timestampTzWithoutAZoneThrowsRatherThanUsingTheHostZone() {
        assertThrows(IllegalArgumentException.class,
                () -> SchemaFieldTypes.castSql("c", "TIMESTAMPTZ", DF, TF, null));
    }

    // ── what the SQL actually does, on a real engine ──────────────────────────

    /**
     * The whole point: a fixed source zone reinterprets the naive text and stores the same instant as
     * naive UTC, so the value no longer depends on which machine ran the batch.
     */
    @Test
    void fixedZoneNormalisesToNaiveUtc() throws Exception {
        Map<String, Object> schema = tsSchema(Map.of("name", "CALL_TS", "selector", "0",
                "type", "TIMESTAMP", "timezone", "Asia/Kolkata"));
        assertEquals("2026-08-29 04:30:00", transformOne(schema, null, "2026-08-29 10:00:00", null));
    }

    /** With no zone declared the value stays exactly the wall clock that was in the file. */
    @Test
    void noZoneKeepsTheWallClock() throws Exception {
        Map<String, Object> schema = tsSchema(Map.of("name", "CALL_TS", "selector", "0", "type", "TIMESTAMP"));
        assertEquals("2026-08-29 10:00:00", transformOne(schema, null, "2026-08-29 10:00:00", null));
    }

    /** The pipeline-level default reaches a field that declares nothing of its own. */
    @Test
    void pipelineZoneAppliesToAnUndeclaredField() throws Exception {
        Map<String, Object> schema = tsSchema(Map.of("name", "CALL_TS", "selector", "0", "type", "TIMESTAMP"));
        assertEquals("2026-08-29 08:00:00",
                transformOne(schema, "Europe/Berlin", "2026-08-29 10:00:00", null));
    }

    /**
     * 🔴 The per-row form's real risk: an unknown zone in the data. DuckDB raises a <i>Not
     * implemented</i> error that {@code TRY()} does <b>not</b> catch, so without the
     * {@code pg_timezone_names()} lookup one bad row would kill the entire batch. It must null that
     * row instead — the same "bad value becomes NULL" contract every other coercion here has.
     */
    @Test
    void aBadZoneInAZoneColumnNullsThatRowInsteadOfKillingTheBatch() throws Exception {
        Map<String, Object> schema = tsSchema(
                Map.of("name", "CALL_TS", "selector", "0", "type", "TIMESTAMP", "timezone_column", "TZ"),
                Map.of("name", "TZ", "selector", "1", "type", "VARCHAR"));

        assertEquals("2026-08-29 04:30:00", transformOne(schema, null, "2026-08-29 10:00:00", "Asia/Kolkata"));
        assertNull(transformOne(schema, null, "2026-08-29 10:00:00", "Not/AZone"));
        // A NULL zone value: also the non-VARCHAR case, since a bare NULL literal types as INTEGER.
        assertNull(transformOne(schema, null, "2026-08-29 10:00:00", null));
    }

    /**
     * The partition a row lands in and the {@code __event_time} bound written for it must come from
     * the same normalised instant as the stored column — otherwise a row is filed under a day it does
     * not claim to be on. Here 00:30 in Kolkata is the PREVIOUS day in UTC, so a naive partition would
     * say 29 while the value says 28.
     */
    @Test
    void partitionAndEventTimeFollowTheNormalisedInstant() throws Exception {
        Map<String, Object> schema = Map.of(
                "partitions", List.of(
                        Map.of("column", "year",  "source", "CALL_TS", "type", "DATE_YEAR"),
                        Map.of("column", "month", "source", "CALL_TS", "type", "DATE_MONTH"),
                        Map.of("column", "day",   "source", "CALL_TS", "type", "DATE_DAY")),
                "raw", Map.of("fields", List.of(
                        Map.of("name", "CALL_TS", "selector", "0", "type", "TIMESTAMP",
                               "timezone", "Asia/Kolkata"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "CALL_TS", "sourceExpression", "CALL_TS",
                               "transformType", "DIRECT"))));

        PipelineConfig cfg = cfgWithZone(null);
        File db = DuckDbUtil.tempDbFile("tz_part_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES ('2026-08-29 00:30:00',0)) t(CALL_TS,__src_id)");
            DataTransformer.materialize(conn, schema, cfg, "src", "dst");
            try (ResultSet rs = st.executeQuery(
                    "SELECT CALL_TS::VARCHAR, year, month, day, " + TransformCompiler.EVENT_TIME_COL + "::VARCHAR FROM dst")) {
                assertTrue(rs.next());
                assertEquals("2026-08-28 19:00:00", rs.getString(1), "the stored value is naive UTC");
                assertEquals("2026", rs.getString(2));
                assertEquals("08",   rs.getString(3));
                assertEquals("28",   rs.getString(4), "the partition must follow the value, not the wall clock");
                assertEquals("2026-08-28 19:00:00", rs.getString(5), "__event_time must agree with both");
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** CONCAT_DT was missing from the board row's site list — it takes the zone too. */
    @Test
    void concatDtIsNormalisedLikeADirectTimestamp() throws Exception {
        Map<String, Object> schema = Map.of(
                "raw", Map.of("fields", List.of(
                        Map.of("name", "D", "selector", "0", "type", "VARCHAR", "timezone", "Asia/Kolkata"),
                        Map.of("name", "T", "selector", "1", "type", "VARCHAR"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "TS", "sourceExpression", "D|T", "transformType", "CONCAT_DT"))));

        PipelineConfig cfg = cfgWithZone(null);
        File db = DuckDbUtil.tempDbFile("tz_concat_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES ('2026-08-29','10:00:00',0)) t(D,T,__src_id)");
            DataTransformer.materialize(conn, schema, cfg, "src", "dst");
            try (ResultSet rs = st.executeQuery("SELECT TS::VARCHAR FROM dst")) {
                assertTrue(rs.next());
                assertEquals("2026-08-29 04:30:00", rs.getString(1));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── the config gates ──────────────────────────────────────────────────────

    private static final String TZTZ_NO_ZONE = """
            fields[2]{name,selector,type}:
                CALL_TS,"0",TIMESTAMPTZ
                TZ,"1",VARCHAR""";
    private static final String TZTZ_FIXED_ZONE = """
            fields[2]{name,selector,timezone,type}:
                CALL_TS,"0","Asia/Kolkata",TIMESTAMPTZ
                TZ,"1","",VARCHAR""";
    private static final String TS_BAD_ZONE_COLUMN = """
            fields[2]{name,selector,timezone_column,type}:
                CALL_TS,"0",NOPE,TIMESTAMP
                TZ,"1","",VARCHAR""";
    private static final String TS_BOTH_ZONE_FORMS = """
            fields[2]{name,selector,timezone,timezone_column,type}:
                CALL_TS,"0",UTC,TZ,TIMESTAMP
                TZ,"1","","",VARCHAR""";
    private static final String TS_PLAIN = """
            fields[2]{name,selector,type}:
                CALL_TS,"0",TIMESTAMP
                TZ,"1",VARCHAR""";

    /**
     * 🔴 The trap this feature exists to close: before, a TIMESTAMPTZ field loaded happily and
     * imported the SERVER's zone. It must now refuse — and load again as soon as any one of the
     * three zone sources is present.
     */
    @Test
    void aTimestampTzFieldWithNoZoneSourceIsRefusedAtLoad(@TempDir Path dir) {
        Exception e = assertThrows(Exception.class, () -> load(dir, TZTZ_NO_ZONE, null));
        assertTrue(String.valueOf(e.getMessage()).contains("TIMESTAMPTZ"), e.getMessage());
        assertDoesNotThrow(() -> load(dir, TZTZ_FIXED_ZONE, null));
        assertDoesNotThrow(() -> load(dir, TZTZ_NO_ZONE, "Asia/Kolkata"));
    }

    /**
     * ⚠ A blank cell in TOON's tabular field form is ABSENT, not an empty zone — every schema that
     * gives one field a zone writes "" for the others, so a null-only check would refuse them all.
     */
    @Test
    void aBlankZoneCellIsAbsentNotEmpty(@TempDir Path dir) {
        assertDoesNotThrow(() -> load(dir, TZTZ_FIXED_ZONE, null));
    }

    @Test
    void anUnknownPipelineZoneIsRefusedAtLoad(@TempDir Path dir) {
        Exception e = assertThrows(Exception.class, () -> load(dir, TS_PLAIN, "+05:30"));
        assertTrue(String.valueOf(e.getMessage()).contains("+05:30"), e.getMessage());
    }

    @Test
    void aTimezoneColumnMustNameADeclaredField(@TempDir Path dir) {
        Exception e = assertThrows(Exception.class, () -> load(dir, TS_BAD_ZONE_COLUMN, null));
        assertTrue(String.valueOf(e.getMessage()).contains("NOPE"), e.getMessage());
    }

    @Test
    void declaringBothZoneFormsOnOneFieldIsRefused(@TempDir Path dir) {
        Exception e = assertThrows(Exception.class, () -> load(dir, TS_BOTH_ZONE_FORMS, null));
        assertTrue(String.valueOf(e.getMessage()).contains("timezone_column"), e.getMessage());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SafeVarargs
    private static Map<String, Object> tsSchema(Map<String, Object>... fields) {
        return Map.of(
                "raw", Map.of("fields", List.of(fields)),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "CALL_TS", "sourceExpression", "CALL_TS",
                               "transformType", "DIRECT"))));
    }

    private static PipelineConfig cfgWithZone(String pipelineZone) throws Exception {
        Map<String, Object> delimited = Map.of("timestamp_formats", TF, "date_formats", DF);
        Map<String, Object> parsing = pipelineZone == null
                ? Map.of("delimited", delimited)
                : Map.of("source_timezone", pipelineZone, "delimited", delimited);
        return PipelineConfig.fromMap(Map.of(
                "name", "tz_test",
                "dirs", Map.of("poll", "in", "database", "out"),
                "parsing", parsing,
                "processing", Map.of("threads", 1)));
    }

    /** Transform one row and return the mapped {@code CALL_TS} as text (or {@code null}). */
    private static String transformOne(Map<String, Object> schema, String pipelineZone,
                                       String ts, String tz) throws Exception {
        PipelineConfig cfg = cfgWithZone(pipelineZone);
        File db = DuckDbUtil.tempDbFile("tz_e2e_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            boolean withTz = schema.toString().contains("timezone_column");
            st.execute(withTz
                    ? "CREATE TABLE src AS SELECT * FROM (VALUES ('" + ts + "', "
                      + (tz == null ? "NULL" : "'" + tz + "'") + ", 0)) t(CALL_TS,TZ,__src_id)"
                    : "CREATE TABLE src AS SELECT * FROM (VALUES ('" + ts + "',0)) t(CALL_TS,__src_id)");
            DataTransformer.materialize(conn, schema, cfg, "src", "dst");
            try (ResultSet rs = st.executeQuery("SELECT CALL_TS::VARCHAR FROM dst")) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * Write a schema + pipeline pair and load it through the real parser, so the fail-closed gates
     * are exercised where they actually live.
     */
    private static PipelineConfig load(Path dir, String fieldsBlock, String pipelineZone)
            throws Exception {
        Path root = Files.createTempDirectory(dir, "tz");
        String rootPath = root.toString().replace(File.separatorChar, '/');
        Path schema = root.resolve("s.toon");
        Files.writeString(schema, """
                raw:
                  name: s
                  format: CSV
                  %s
                mapping:
                  canonicalName: s
                  rawName: s
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    CALL_TS,CALL_TS,DIRECT
                """.formatted(fieldsBlock));

        String zoneLine = pipelineZone == null ? "" : "\n  source_timezone: \"" + pipelineZone + "\"";
        Path pipeline = root.resolve("p.toon");
        Files.writeString(pipeline, """
                name: TZ_TEST
                active: true
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                output:
                  format: PARQUET
                parsing:
                  frontend: delimited%s
                  delimited:
                    delimiter: ","
                    has_header: true
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d %%H:%%M:%%S"
                processing:
                  threads: 1
                  file_pattern: "glob:**/*"
                  schema_file: "%s"
                """.formatted(rootPath, rootPath, zoneLine,
                        schema.toString().replace(File.separatorChar, '/')));

        return PipelineConfig.load(pipeline.toString());
    }
}
