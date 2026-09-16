package com.gamma.query;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the R3 <b>Result Set</b> descriptor (W4; design §6.2) — the Java mirror of the UI's
 * {@code inspecto/viz/result-set.ts}: each output column described as {name, type, analytic role,
 * cardinality}, independent of how it is rendered, so the Presentation Network can match candidate
 * visualizations against the shape (Show-Me). Types come from JDBC {@link Types}; roles + cardinality
 * are inferred exactly as the UI does (date⇒temporal, non-id number⇒measure, else dimension).
 */
public final class ResultSetDescriptor {

    private ResultSetDescriptor() {}

    /** One described column: name, coarse type ({@code number|string|date|boolean}), role, and (dimensions) cardinality. */
    public record Column(String name, String type, String role, Integer cardinality) {}

    private static final Pattern ID_COLUMN = Pattern.compile("(^|_)id$", Pattern.CASE_INSENSITIVE);

    /** Map a JDBC {@link Types} constant to the UI's coarse {@code ColumnType}. */
    public static String columnType(int sqlType) {
        return switch (sqlType) {
            case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT,
                 Types.DECIMAL, Types.NUMERIC, Types.DOUBLE, Types.FLOAT, Types.REAL -> "number";
            case Types.BOOLEAN, Types.BIT -> "boolean";
            case Types.DATE, Types.TIMESTAMP, Types.TIME,
                 Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE -> "date";
            default -> "string";
        };
    }

    /**
     * Map a <b>DuckDB type NAME</b> — what {@code DESCRIBE} reports over a real relation — to the same coarse
     * {@code ColumnType} vocabulary {@link #columnType(int)} produces from JDBC constants
     * (`TYPEFLOW-DATASET-COLUMNS-1` step 3). Both feed one heuristic and one contract
     * (`inspecto-ui/src/app/inspecto/contracts/column-role.contract.json`), so they must not drift apart.
     *
     * <p>⚠ Parameterised and nested types arrive spelled out — {@code DECIMAL(18,2)},
     * {@code TIMESTAMP WITH TIME ZONE}, {@code VARCHAR[]}, {@code STRUCT(a INTEGER)} — so this matches on the
     * leading token rather than the whole string.
     *
     * <p>⛔ {@code INTERVAL} is deliberately NOT a date: it is a duration, and calling it temporal would make
     * the role heuristic hand it to a time axis it cannot sit on. ⛔ A composite ({@code LIST}/{@code STRUCT}/
     * {@code MAP}/{@code UNION}) falls to {@code string}: the coarse vocabulary has no composite, and
     * pretending otherwise would put an unrenderable column on a chart.
     */
    public static String columnType(String duckdbType) {
        if (duckdbType == null) return "string";
        String t = duckdbType.trim().toUpperCase(java.util.Locale.ROOT);
        int cut = t.indexOf('(');                 // DECIMAL(18,2) -> DECIMAL
        if (cut > 0) t = t.substring(0, cut).trim();
        if (t.endsWith("[]")) return "string";    // a LIST of anything is not that thing
        return switch (t) {
            case "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT",
                 "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT",
                 "DECIMAL", "NUMERIC", "REAL", "FLOAT", "DOUBLE" -> "number";
            case "BOOLEAN", "BOOL", "LOGICAL" -> "boolean";
            case "DATE", "TIME", "TIMETZ", "TIMESTAMP", "TIMESTAMPTZ",
                 "DATETIME", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS" -> "date";
            default -> {
                // Spelled-out forms DuckDB also reports, e.g. "TIMESTAMP WITH TIME ZONE".
                if (t.startsWith("TIMESTAMP") || t.startsWith("TIME ")) yield "date";
                yield "string";
            }
        };
    }

    /** Describe columns from their JDBC types + the returned rows (cardinality = distinct values, dimensions only). */
    public static List<Column> describe(List<String> names, List<Integer> sqlTypes, List<Map<String, Object>> rows) {
        List<Column> cols = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String type = columnType(sqlTypes.get(i));
            String role = roleFor(name, type);
            Integer cardinality = "dimension".equals(role) ? distinctCount(rows, name) : null;
            cols.add(new Column(name, type, role, cardinality));
        }
        return cols;
    }

    /**
     * Derive columns from a relation's {@code DESCRIBE} output: the names and DuckDB type names, with no
     * rows in hand (so no cardinality). The temporal tie-break is §6-Q2's answer and lives HERE rather than
     * in {@link #roleFor}, because it is a property of the column SET, not of any one column.
     *
     * <p>✅ <b>Q2 (operator): several date columns ⇒ derive {@code temporal} for NONE of them.</b> Guessing
     * which of {@code created_at} / {@code updated_at} / {@code event_time} is *the* time axis is exactly
     * the kind of confident wrong answer a human then has to find and undo; with none marked, the Studio
     * editor shows an unanswered question instead of a plausible mistake. The extra dates become
     * {@code dimension}, which is what a date the chart does not treat as its axis is.
     */
    public static List<Column> describeTypeNames(List<String> names, List<String> duckdbTypes) {
        List<String> coarse = new ArrayList<>(names.size());
        for (String t : duckdbTypes) coarse.add(columnType(t));
        long dates = coarse.stream().filter("date"::equals).count();
        List<Column> cols = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String role = roleFor(names.get(i), coarse.get(i));
            if ("temporal".equals(role) && dates > 1) role = "dimension";
            cols.add(new Column(names.get(i), coarse.get(i), role, null));
        }
        return cols;
    }

    static String roleFor(String name, String type) {
        if ("date".equals(type)) return "temporal";
        if ("number".equals(type) && !ID_COLUMN.matcher(name).find()) return "measure";
        return "dimension";
    }

    private static int distinctCount(List<Map<String, Object>> rows, String col) {
        Set<Object> seen = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) seen.add(r.get(col));
        return seen.size();
    }

    /**
     * A coarse server-side candidate list of renderings from the column roles (design §6.2 / guideline 21).
     * The UI's richer {@code recommend()} refines this; here we give a stable, honest first cut.
     */
    public static List<String> renderings(List<Column> cols) {
        long measures = cols.stream().filter(c -> "measure".equals(c.role())).count();
        long dims = cols.stream().filter(c -> "dimension".equals(c.role())).count();
        long temporal = cols.stream().filter(c -> "temporal".equals(c.role())).count();
        List<String> out = new ArrayList<>();
        out.add("table");                                       // always applicable
        if (measures == 1 && dims == 0 && temporal == 0) out.add("kpi");
        if (temporal >= 1 && measures >= 1) out.add("line-chart");
        if (dims >= 1 && measures >= 1) out.add("bar-chart");
        if (dims >= 2 && measures >= 1) out.add("heatmap");
        return out;
    }
}
