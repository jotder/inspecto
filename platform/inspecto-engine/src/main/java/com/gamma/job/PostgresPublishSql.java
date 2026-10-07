package com.gamma.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The SQL {@code publish.postgres} sends to its target (ASSURE-BI-PUBLICATION-1). Pure string building, no I/O, so
 * every statement is testable on its own. ⛔ Nothing is concatenated unquoted: every schema / table / column name is
 * first validated against {@link #SAFE_ID} and then double-quoted; every comment is a single-quoted literal with
 * {@code '} doubled (the target runs with {@code standard_conforming_strings = on}, so a backslash is literal).
 */
final class PostgresPublishSql {

    private PostgresPublishSql() {}

    /** A name the publisher will write: a letter or {@code _}, then letters, digits, {@code _}; at most 40 chars so
     *  the stage suffix still fits Postgres's 63-byte identifier limit. */
    static final Pattern SAFE_ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,39}");
    static final String STAGE_SUFFIX = "__inspecto_stage";
    /** The per-schema ledger of published partitions and their fingerprints (partition-incremental). */
    static final String STATE_TABLE = "_inspecto_publication";

    /** {@code name}, validated and double-quoted; {@link IllegalArgumentException} naming {@code what} otherwise. */
    static String ident(String name, String what) {
        if (name == null || !SAFE_ID.matcher(name).matches())
            throw new IllegalArgumentException(what + " '" + name + "' is not a safe identifier (" + SAFE_ID.pattern()
                    + ") — refused rather than quoted around");
        return "\"" + name + "\"";
    }

    /** A single-quoted SQL literal; a NUL is refused. */
    static String literal(String s) {
        if (s.indexOf('\0') >= 0) throw new IllegalArgumentException("a comment carries a NUL byte");
        return "'" + s.replace("'", "''") + "'";
    }

    /** The target table a Dataset id publishes to: lower-cased, every other character → {@code _}, then validated. */
    static String tableFor(String datasetId) {
        String t = datasetId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        ident(t, "target table for dataset '" + datasetId + "'");
        return t;
    }

    /** The Postgres column type for a DuckDB column type; {@code null} for one the publisher does not carry. */
    static String pgType(String duck) {
        String t = duck.trim().toUpperCase(Locale.ROOT);
        if (t.matches("DECIMAL\\(\\d{1,2},\\d{1,2}\\)")) return "numeric" + t.substring(7);
        return switch (t) {
            case "VARCHAR", "TEXT", "STRING", "UUID" -> "text";
            case "BOOLEAN" -> "boolean";
            case "TINYINT", "SMALLINT", "UTINYINT" -> "smallint";
            case "INTEGER", "USMALLINT" -> "integer";
            case "BIGINT", "UINTEGER" -> "bigint";
            case "UBIGINT", "HUGEINT" -> "numeric(38,0)";
            case "FLOAT" -> "real";
            case "DOUBLE" -> "double precision";
            case "DATE" -> "date";
            case "TIME" -> "time";
            case "TIMESTAMP", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS" -> "timestamp";
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> "timestamptz";
            case "BLOB" -> "bytea";
            default -> null;
        };
    }

    /** The partition column types whose text form is identical in DuckDB and Postgres. */
    static boolean partitionable(String duck) {
        return switch (duck.trim().toUpperCase(Locale.ROOT)) {
            case "VARCHAR", "TEXT", "DATE", "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "BOOLEAN" -> true;
            default -> false;
        };
    }

    record Col(String name, String duckType, String pgType, String description) {}

    static String qualified(String schema, String table) {
        return ident(schema, "schema") + "." + ident(table, "table");
    }

    static String createTable(String schema, String table, List<Col> cols) {
        List<String> defs = new ArrayList<>();
        for (Col c : cols) defs.add(ident(c.name(), "column") + " " + c.pgType());
        return "CREATE TABLE " + qualified(schema, table) + " (" + String.join(", ", defs) + ")";
    }

    static String insert(String schema, String table, List<Col> cols) {
        List<String> names = new ArrayList<>();
        for (Col c : cols) names.add(ident(c.name(), "column"));
        return "INSERT INTO " + qualified(schema, table) + " (" + String.join(", ", names) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
    }

    /** Table and column comments from the Catalog descriptions; none for an undescribed element. */
    static List<String> comments(String schema, String table, String tableDescription, List<Col> cols) {
        List<String> out = new ArrayList<>();
        if (tableDescription != null && !tableDescription.isBlank())
            out.add("COMMENT ON TABLE " + qualified(schema, table) + " IS " + literal(tableDescription));
        for (Col c : cols)
            if (c.description() != null && !c.description().isBlank())
                out.add("COMMENT ON COLUMN " + qualified(schema, table) + "." + ident(c.name(), "column") + " IS "
                        + literal(c.description()));
        return out;
    }

    /** The full-refresh swap, run inside the caller's transaction after the stage is filled. */
    static List<String> swap(String schema, String table) {
        return List.of("DROP TABLE IF EXISTS " + qualified(schema, table),
                "ALTER TABLE " + qualified(schema, table + STAGE_SUFFIX) + " RENAME TO " + ident(table, "table"));
    }

    static String createState(String schema) {
        return "CREATE TABLE IF NOT EXISTS " + qualified(schema, STATE_TABLE)
                + " (tbl text NOT NULL, part text NOT NULL, fingerprint text NOT NULL, run_id text, "
                + "PRIMARY KEY (tbl, part))";
    }

    /** Delete one partition's rows; {@code null} value ⇒ the NULL partition. One {@code ?} unless null. */
    static String deletePartition(String schema, String table, String column, boolean isNull) {
        String c = ident(column, "partition column");
        return "DELETE FROM " + qualified(schema, table) + " WHERE "
                + (isNull ? c + " IS NULL" : "CAST(" + c + " AS text) = ?");
    }

    /** DuckDB: per-partition row count + order-independent row hash sum over the published columns. */
    static String fingerprints(String relation, String partitionColumn, List<Col> cols) {
        List<String> names = new ArrayList<>();
        for (Col c : cols) names.add(ident(c.name(), "column"));
        String p = ident(partitionColumn, "partition column");
        return "SELECT CAST(" + p + " AS VARCHAR) AS p, CAST(count(*) AS VARCHAR) || ':' || "
                + "CAST(sum(CAST(hash(" + String.join(", ", names) + ") AS HUGEINT)) AS VARCHAR) AS fp FROM ("
                + relation + ") t GROUP BY 1";
    }

    static String select(String relation, List<Col> cols) {
        List<String> names = new ArrayList<>();
        for (Col c : cols) names.add(ident(c.name(), "column"));
        return "SELECT " + String.join(", ", names) + " FROM (" + relation + ") t";
    }
}
