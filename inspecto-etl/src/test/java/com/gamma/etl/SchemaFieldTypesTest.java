package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SchemaFieldTypes} — the honoured {@code raw.fields[].type} vocabulary and the SQL each one
 * compiles to (operator ask 2026-08-22: offer every DuckDB scalar type, fail closed on the rest).
 *
 * <p>🔴 The regression this class exists to prevent: the honoured set used to be a four-branch
 * {@code switch} whose {@code default} emitted the column UNCAST, so {@code type: BIGINT} silently
 * produced a VARCHAR column. The gate below is what turns that into a load error.
 */
class SchemaFieldTypesTest {

    private static final List<String> NO_FORMATS = List.of();

    @Test
    void honoursEveryDuckDbScalarTypeReachableFromText() {
        for (String t : List.of("VARCHAR", "BOOLEAN", "TINYINT", "SMALLINT", "INTEGER", "BIGINT",
                "HUGEINT", "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "FLOAT", "DOUBLE",
                "DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ", "UUID", "BLOB")) {
            assertTrue(SchemaFieldTypes.isHonored(t), t + " must be honoured");
        }
    }

    @Test
    void refusesWhatWouldOnlyPretendToWork() {
        // Nested types: a delimited token cannot be cast into one. JSON is extension-dependent, and
        // this deployment does not statically link DuckDB extensions.
        for (String t : List.of("LIST", "STRUCT", "MAP", "UNION", "JSON", "INTERVAL",
                "VARCHAR[]", "STRUCT(a INT)", "NOT_A_TYPE", "DUOBLE")) {
            assertFalse(SchemaFieldTypes.isHonored(t), t + " must NOT be honoured");
        }
    }

    @Test
    void decimalTakesPrecisionAndScaleWithinDuckDbsLimits() {
        assertTrue(SchemaFieldTypes.isHonored("DECIMAL(18,2)"));
        assertTrue(SchemaFieldTypes.isHonored("DECIMAL(38,38)"), "scale may equal precision");
        assertTrue(SchemaFieldTypes.isHonored("DECIMAL( 18 , 2 )"), "spacing is normalised");
        assertEquals("DECIMAL(18,2)", SchemaFieldTypes.normalize("decimal( 18 , 2 )"));

        assertFalse(SchemaFieldTypes.isHonored("DECIMAL(39,2)"), "precision > 38 is not a DuckDB decimal");
        assertFalse(SchemaFieldTypes.isHonored("DECIMAL(0,0)"), "precision must be at least 1");
        assertFalse(SchemaFieldTypes.isHonored("DECIMAL(2,5)"), "scale may not exceed precision");
        assertFalse(SchemaFieldTypes.isHonored("DECIMAL"), "bare DECIMAL is ambiguous — ask for p,s");
    }

    @Test
    void normalizesTheSpellingsDuckDbAndOperatorsBothUse() {
        assertEquals("VARCHAR", SchemaFieldTypes.normalize(null), "absent has always meant text");
        assertEquals("VARCHAR", SchemaFieldTypes.normalize("  "));
        assertEquals("VARCHAR", SchemaFieldTypes.normalize("string"));
        assertEquals("BIGINT", SchemaFieldTypes.normalize("int8"));
        assertEquals("INTEGER", SchemaFieldTypes.normalize("int"));
        assertEquals("DOUBLE", SchemaFieldTypes.normalize("float8"));
        assertEquals("FLOAT", SchemaFieldTypes.normalize("real"));
        assertEquals("BOOLEAN", SchemaFieldTypes.normalize("bool"));
        assertEquals("TIMESTAMPTZ", SchemaFieldTypes.normalize("timestamp with time zone"));
        assertEquals("TIMESTAMP", SchemaFieldTypes.normalize("TIMESTAMP WITHOUT TIME ZONE"));
    }

    @Test
    void varcharIsThePassThroughAndCoercesNothing() {
        assertFalse(SchemaFieldTypes.coerces("VARCHAR"), "text cannot be silently nulled by a cast");
        assertEquals("c", SchemaFieldTypes.castSql("c", "VARCHAR", NO_FORMATS, NO_FORMATS));
        // Everything else coerces, which is what makes it measurable by the cast-failure audit.
        for (String t : List.of("BIGINT", "DECIMAL(18,2)", "DATE", "TIMESTAMP", "BOOLEAN")) {
            assertTrue(SchemaFieldTypes.coerces(t), t + " coerces, so the audit must count it");
        }
    }

    @Test
    void nonDateTypesCompileToATryCastSoABadValueNullsRatherThanFailingTheBatch() {
        assertEquals("TRY_CAST(c AS BIGINT)", SchemaFieldTypes.castSql("c", "BIGINT", NO_FORMATS, NO_FORMATS));
        assertEquals("TRY_CAST(c AS DECIMAL(18,2))",
                SchemaFieldTypes.castSql("c", "decimal(18, 2)", NO_FORMATS, NO_FORMATS));
        assertEquals("TRY_CAST(c AS BOOLEAN)", SchemaFieldTypes.castSql("c", "BOOLEAN", NO_FORMATS, NO_FORMATS));
    }

    @Test
    void dateLikeTypesParseThroughTheirOwnFormatListAfterATextCast() {
        // The CAST(... AS VARCHAR) is load-bearing: an ALREADY typed DATE/TIMESTAMP (the plugin lane)
        // has to reach TRY_STRPTIME as text.
        String date = SchemaFieldTypes.castSql("c", "DATE", List.of("%d/%m/%Y"), List.of("%c"));
        assertEquals("COALESCE(TRY_STRPTIME(CAST(c AS VARCHAR), '%d/%m/%Y'))::DATE", date);

        String ts = SchemaFieldTypes.castSql("c", "TIMESTAMP", List.of("%d/%m/%Y"), List.of("%c"));
        assertEquals("COALESCE(TRY_STRPTIME(CAST(c AS VARCHAR), '%c'))::TIMESTAMP", ts,
                "a TIMESTAMP parses with timestamp_formats, never the date list");

        assertTrue(SchemaFieldTypes.isDateLike("TIMESTAMPTZ"));
        assertFalse(SchemaFieldTypes.isDateLike("TIME"), "TIME has no date component to partition on");
        assertFalse(SchemaFieldTypes.isDateLike("BIGINT"));
    }

    @Test
    void withNoDeclaredFormatsADateFallsBackToDuckDbsNativeParse() {
        // A zero-arg COALESCE() would be a syntax error — this is what keeps a DATE column with no
        // declared date_formats from crashing the transform.
        assertEquals("TRY_CAST(CAST(c AS VARCHAR) AS DATE)",
                SchemaFieldTypes.castSql("c", "DATE", NO_FORMATS, NO_FORMATS));
    }

    @Test
    void castSqlRefusesAnUnhonouredTypeRatherThanDegradingToText() {
        // Reaching here with an unhonoured type means the load gate was bypassed — that is a bug, and
        // silently emitting text is exactly the failure mode this whole change removes.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SchemaFieldTypes.castSql("c", "STRUCT(a INT)", NO_FORMATS, NO_FORMATS));
        assertTrue(e.getMessage().contains("unhonoured"), e.getMessage());
    }

    @Test
    void theOfferedNameListLeadsWithVarcharAndAdvertisesDecimal() {
        List<String> names = SchemaFieldTypes.names();
        assertEquals("VARCHAR", names.get(0), "the default and pass-through comes first");
        assertTrue(names.contains("DECIMAL"), "offered as a bare token; the UI asks for p,s");
        assertTrue(names.contains("BIGINT"));
        assertFalse(names.contains("JSON"));
    }
}
