package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Sanity checks for {@link Identifiers} — every name fed to SQL DDL must pass. */
class IdentifiersTest {

    @Test
    void acceptsTypicalIdentifiers() {
        Identifiers.validate("ID", "test");
        Identifiers.validate("EVENT_DATE", "test");
        Identifiers.validate("_internal", "test");
        Identifiers.validate("col_123", "test");
        Identifiers.validate("a", "test");
    }

    @Test
    void rejectsBlankOrNull() {
        var e1 = assertThrows(IllegalArgumentException.class, () -> Identifiers.validate(null, "site"));
        assertTrue(e1.getMessage().contains("site"));
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validate("", "site"));
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validate("   ", "site"));
    }

    @Test
    void rejectsInjectionAttempts() {
        // Double-quote → would close the quoted identifier in CREATE TABLE
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validate("col\"; DROP TABLE x; --", "test"));
        // Semicolon, space, hyphen — common DDL-breaking characters
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validate("a;b", "test"));
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validate("with space", "test"));
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validate("dash-name", "test"));
        // Dot — would be parsed as schema.table by DuckDB
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validate("schema.table", "test"));
    }

    @Test
    void rejectsLeadingDigit() {
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validate("1col", "test"));
    }

    /** validateSchema walks the standard config structure and rejects bad names anywhere. */
    @Test
    void validateSchemaCatchesBadFieldName() {
        Map<String, Object> schema = Map.of(
                "raw", Map.of("fields", List.of(
                        Map.of("name", "good_col", "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "bad name", "selector", "1", "type", "VARCHAR"))));
        var e = assertThrows(IllegalArgumentException.class,
                () -> Identifiers.validateSchema(schema, "my_schema"));
        assertTrue(e.getMessage().contains("my_schema.raw.fields[].name"),
                "Error should mention the offending location");
        assertTrue(e.getMessage().contains("bad name"));
    }

    @Test
    void validateSchemaCatchesBadPartitionColumn() {
        Map<String, Object> schema = Map.of(
                "partitions", List.of(
                        Map.of("column", "bad-name", "source", "X", "type", "VARCHAR")));
        assertThrows(IllegalArgumentException.class,
                () -> Identifiers.validateSchema(schema, "seg"));
    }

    @Test
    void validateSchemaCatchesBadLegacyPartitionKey() {
        Map<String, Object> schema = Map.of("partitionKey", "weird;key");
        assertThrows(IllegalArgumentException.class,
                () -> Identifiers.validateSchema(schema, "seg"));
    }

    @Test
    void validateSchemaAcceptsEmptyOrAbsentSections() {
        // No raw/mapping/partitions — nothing to validate, should not throw.
        Identifiers.validateSchema(Map.of(), "seg");
    }

    /**
     * 🔴 Fail closed on a field type the engine cannot produce (operator decision 2026-08-22).
     * Before this, an unrecognised type fell through {@code TransformCompiler.direct}'s default and
     * the column was written as TEXT — silently. The error has to name the field and the vocabulary,
     * because the whole point is that the operator finds out at load instead of at analysis time.
     */
    @Test
    void validateSchemaRefusesAnUnsupportedFieldType() {
        Map<String, Object> schema = Map.of(
                "raw", Map.of("fields", List.of(
                        Map.of("name", "AMT", "selector", "0", "type", "STRUCT(a INT)"))));
        var e = assertThrows(IllegalArgumentException.class,
                () -> Identifiers.validateSchema(schema, "my_schema"));
        assertTrue(e.getMessage().contains("AMT"), "names the offending field: " + e.getMessage());
        assertTrue(e.getMessage().contains("STRUCT(a INT)"), e.getMessage());
        assertTrue(e.getMessage().contains("BIGINT"), "lists what IS supported: " + e.getMessage());
    }

    /** Every DuckDB scalar type the engine now casts loads cleanly, DECIMAL(p,s) included. */
    @Test
    void validateSchemaAcceptsTheWholeHonouredVocabulary() {
        for (String type : List.of("VARCHAR", "BIGINT", "INTEGER", "DECIMAL(18,2)", "BOOLEAN",
                "TIMESTAMPTZ", "UUID", "HUGEINT")) {
            Identifiers.validateSchema(
                    Map.of("raw", Map.of("fields", List.of(Map.of("name", "COL", "selector", "0", "type", type)))),
                    "seg");
        }
    }

    /**
     * An absent or blank type stays legal and means VARCHAR — pre-existing schemas omit it, and
     * failing them closed would be a migration nobody asked for.
     */
    @Test
    void validateSchemaAcceptsAnAbsentOrBlankFieldType() {
        Identifiers.validateSchema(
                Map.of("raw", Map.of("fields", List.of(Map.of("name", "COL", "selector", "0")))), "seg");
        Identifiers.validateSchema(
                Map.of("raw", Map.of("fields", List.of(Map.of("name", "COL", "selector", "0", "type", "  ")))),
                "seg");
    }
}
