package com.gamma.etl;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates SQL identifier values (column names, table names) sourced from
 * pipeline / schema configuration files.
 *
 * <p>The ETL framework interpolates these names directly into DDL and DML
 * (`CREATE TABLE "<name>" (...)`, `COPY ... PARTITION_BY (col1, col2)`, etc.).
 * That is the right call for an internal tool — schema configs are operator-curated,
 * not user-supplied — but a stray character in a name silently breaks SQL parsing
 * at write time, far from the misconfigured field.  This validator catches the
 * problem at config-load time with a clear message.
 *
 * <p>Identifiers must match {@code ^[A-Za-z_][A-Za-z0-9_]*$}: a leading letter or
 * underscore, followed by letters / digits / underscores.  Any name violating
 * this rule fails the load.
 *
 * <p>Apply once at {@link PipelineConfig#load} — runtime callers can assume
 * identifiers are safe to interpolate.
 */
public final class Identifiers {

    private Identifiers() {}

    private static final Pattern VALID = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * Throw {@link IllegalArgumentException} if {@code name} is null, blank, or
     * fails the {@code [A-Za-z_][A-Za-z0-9_]*} pattern.  {@code where} is included
     * in the error message so operators can locate the offending config entry.
     */
    public static void validate(String name, String where) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException(
                    "SQL identifier at " + where + " is null or blank");
        if (!VALID.matcher(name).matches())
            throw new IllegalArgumentException(
                    "SQL identifier at " + where + " is invalid: '" + name +
                    "'. Must match [A-Za-z_][A-Za-z0-9_]*  (no spaces, dots, quotes, hyphens, or operators).");
    }

    /**
     * Refuse a {@code raw.fields[].type} the engine cannot actually produce (operator decision,
     * 2026-08-22: <b>fail closed</b>).
     *
     * <p>Until then an unrecognised type fell through {@code TransformCompiler.direct}'s
     * {@code default} branch and the column was written as <b>text</b> — no error, no warning. A
     * schema declaring {@code BIGINT} produced a VARCHAR store, and the loss surfaced only when
     * something downstream tried to do arithmetic on it. A typo is now a load error, and the honoured
     * vocabulary is {@link SchemaFieldTypes}'.
     *
     * <p>An absent/blank type stays legal and means {@code VARCHAR} — that has always been the
     * meaning of omitting it, and pre-existing schemas rely on it.
     */
    static void validateFieldType(String type, String fieldName, String origin) {
        if (type == null || type.isBlank()) return;
        if (!SchemaFieldTypes.isHonored(type))
            throw new IllegalArgumentException(
                    "Schema field type at " + origin + ".raw.fields[" + fieldName + "].type is not supported: '"
                    + type + "'. Supported: " + String.join(", ", SchemaFieldTypes.names())
                    + " (DECIMAL takes precision 1-38 and scale 0-precision, e.g. DECIMAL(18,2)). "
                    + "Nested (LIST/STRUCT/MAP), JSON and INTERVAL are not supported for a parsed field.");
    }

    /**
     * Validate a field's source-time-zone declaration.
     *
     * <p>Three fail-closed rules, all for the same reason — an unknown zone is a hard DuckDB error at
     * run time that {@code TRY()} cannot catch, so a typo that loads would kill every batch:
     * <ul>
     *   <li>{@code timezone} must be an IANA region id ({@link SourceZones#validateZone});</li>
     *   <li>{@code timezone_column} must name a field this schema actually declares — otherwise the
     *       compiled lookup silently reads NULL and every value in the column becomes NULL;</li>
     *   <li>the two are mutually exclusive. {@code timezone_column} wins by precedence, so writing
     *       both makes {@code timezone} dead config that reads as if it were in force.</li>
     * </ul>
     */
    private static void validateFieldZone(Map<?, ?> field, java.util.Set<String> declaredFields,
                                          String origin) {
        String name = String.valueOf(field.get("name"));
        // ⚠ Blank is ABSENT, not empty. TOON's tabular field form declares one header for every
        // column, so a schema that gives any field a timezone writes "" for all the others — a
        // null-only check would refuse every such schema on the blank rows.
        String tz   = blankToNull(field.get("timezone"));
        String tzc  = blankToNull(field.get("timezone_column"));
        if (tz != null && tzc != null)
            throw new IllegalArgumentException("Schema field '" + name + "' at " + origin
                    + " sets both timezone and timezone_column. timezone_column takes precedence, so "
                    + "the fixed timezone would never apply — keep one.");
        if (tz != null)
            SourceZones.validateZone(tz, origin + ".raw.fields[" + name + "].timezone");
        if (tzc != null) {
            String col = tzc;
            if (!declaredFields.contains(col))
                throw new IllegalArgumentException("Schema field '" + name + "' at " + origin
                        + " sets timezone_column '" + col + "', which is not a declared raw field. "
                        + "It must name a sibling column of this same schema holding an IANA zone id "
                        + "per row.");
        }
    }

    /** Trimmed text, or {@code null} for absent/blank — see {@link #validateFieldZone}. */
    static String blankToNull(Object o) {
        if (!(o instanceof String s)) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Validate every name a schema config exposes to SQL: {@code raw.fields[].name},
     * {@code mapping.rules[].targetColumn}, and {@code partitions[].column / source}.
     * Called from {@link PipelineConfig#load} for the single-schema, multi-schema,
     * and plugin-segment paths alike.
     *
     * @param schema   the loaded schema config map
     * @param origin   short identifier of the schema's source (for error messages)
     */
    @SuppressWarnings("unchecked")
    public static void validateSchema(Map<String, Object> schema, String origin) {
        // raw.fields[].name
        Map<String, Object> rawSection = (Map<String, Object>) schema.get("raw");
        if (rawSection != null) {
            Object rawFields = rawSection.get("fields");
            if (rawFields instanceof List<?> fieldsList) {
                java.util.Set<String> declared = new java.util.LinkedHashSet<>();
                for (Object f : fieldsList)
                    if (f instanceof Map<?, ?> fm && fm.get("name") instanceof String n) declared.add(n);
                for (Object f : fieldsList) {
                    if (f instanceof Map<?, ?> fm) {
                        validate((String) fm.get("name"), origin + ".raw.fields[].name");
                        validateFieldType((String) fm.get("type"), (String) fm.get("name"), origin);
                        validateFieldZone(fm, declared, origin);
                    }
                }
            }
        }
        // mapping.rules[].targetColumn
        Map<String, Object> mapping = (Map<String, Object>) schema.get("mapping");
        if (mapping != null) {
            Object rules = mapping.get("rules");
            if (rules instanceof List<?> rulesList) {
                for (Object r : rulesList) {
                    if (r instanceof Map<?, ?> rm)
                        validate((String) rm.get("targetColumn"), origin + ".mapping.rules[].targetColumn");
                }
            }
        }
        // partitions[].column and partitions[].source
        Object partitions = schema.get("partitions");
        if (partitions instanceof List<?> partsList) {
            for (Object p : partsList) {
                if (p instanceof Map<?, ?> pm) {
                    validate((String) pm.get("column"), origin + ".partitions[].column");
                    validate((String) pm.get("source"), origin + ".partitions[].source");
                }
            }
        }
        // Legacy partitionKey
        Object pk = schema.get("partitionKey");
        if (pk instanceof String pkStr && !pkStr.isBlank())
            validate(pkStr, origin + ".partitionKey");
    }
}
