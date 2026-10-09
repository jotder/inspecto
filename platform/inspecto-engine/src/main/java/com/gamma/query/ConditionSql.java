package com.gamma.query;

import com.gamma.util.SqlIdent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import static com.gamma.util.Values.strOrEmpty;

/**
 * Renders the structured condition tree authored in the UI (the {@code query-types.ts} shape that
 * {@link ConditionTree} evaluates in-JVM) as a DuckDB SQL predicate, so the ETL engine can apply a
 * Decision Rule's {@code when} clause to a whole {@code transformed} table in one statement instead
 * of row-looping through the JVM.
 *
 * <p><b>Semantics parity.</b> The walk (AND/OR groups, incomplete leaves contribute nothing, an
 * empty/absent group imposes no constraint), the operator set, and the case-insensitive substring
 * ops all mirror {@link ConditionTree}. The one deliberate divergence is typing: {@code ConditionTree}
 * infers a column's type from sample values, while here the <em>operand literal</em> drives the cast —
 * a numeric operand compares via {@code TRY_CAST(col AS DOUBLE)} (a non-numeric cell casts to
 * {@code NULL} ⇒ no match, the SQL analogue of the evaluator's {@code NaN} path), an ISO date/time
 * operand via {@code TRY_CAST(col AS TIMESTAMP)}, {@code true/false} as booleans, anything else as a
 * case-sensitive {@code VARCHAR} comparison.
 *
 * <p>All identifiers and literals are quote-escaped here — the tree is authored config, and nothing
 * from it may reach the statement unescaped. A caller that builds a WRITE statement from the predicate
 * uses {@link #predicateBound} instead, where operand values never reach the statement text at all
 * ({@code DECISION-RULE-SQL-GUARD-1}).
 */
public final class ConditionSql {

    private ConditionSql() {
    }

    /**
     * The tree as a DuckDB boolean expression. An absent tree ({@code null} or an empty map) or an
     * empty group imposes no constraint and renders as {@code TRUE} (parity with
     * {@link ConditionTree}'s "empty group matches every row").
     *
     * @throws IllegalArgumentException when the root is present but is not a group — a bare leaf
     *         ({@code {kind:'condition',…}} or a kind-less {@code {field,operator,value}}), or not a
     *         map at all. Such a root used to render as {@code TRUE}, so it constrained nothing while
     *         the caller believed it filtered (fail-open); it is refused instead.
     */
    public static String predicate(Object when) {
        ConditionTree.requireGroupRoot(when);
        String g = group(when, null);
        return g == null ? "TRUE" : g;
    }

    /** A predicate whose operand values are JDBC parameters: one {@code ?} in {@code sql} per entry of {@code params}, in order. */
    public record Bound(String sql, List<Object> params) {
    }

    /**
     * {@link #predicate} with every operand value (string, number, regex, date) bound as a JDBC parameter
     * instead of rendered as a literal, so no rule value ever reaches the statement text — only quoted
     * identifiers and fixed SQL do. Same semantics, same refusals.
     */
    public static Bound predicateBound(Object when) {
        ConditionTree.requireGroupRoot(when);
        List<Object> params = new ArrayList<>();
        String g = group(when, params);
        return new Bound(g == null ? "TRUE" : g, List.copyOf(params));
    }

    /**
     * Strict twin of {@link #predicate}: refuses (via {@link ConditionTree#validateStrict}) every tree the
     * lenient renderer would silently weaken — empty group, incomplete leaf, unknown operator, empty
     * {@code in}, non-orderable operand, non-group root — BEFORE any SQL is emitted, so the result never
     * contains the lenient {@code TRUE}/skipped-leaf fallbacks.
     *
     * @throws IllegalArgumentException with a path-pointing message
     */
    public static String predicateStrict(Object when) {
        ConditionTree.validateStrict(when);
        return group(when, null);
    }

    // ── tree walk (mirrors ConditionTree.matchGroup) ─────────────────────────────

    /**
     * Render a group, or {@code null} when it contributes no constraint. {@code p} collects bound operand
     * values ({@link #predicateBound}); {@code null} renders them as escaped literals.
     */
    private static String group(Object node, List<Object> p) {
        if (!(node instanceof Map<?, ?> g)) return null;
        Object rawItems = g.get("items");
        if (rawItems == null) rawItems = g.get("conditions");
        List<?> items = rawItems instanceof List<?> l ? l : List.of();
        Object op = g.get("op");
        String joiner = "OR".equalsIgnoreCase(op == null ? "AND" : String.valueOf(op)) ? " OR " : " AND ";

        StringJoiner sql = new StringJoiner(joiner, "(", ")");
        int rendered = 0;
        for (Object it : items) {
            if (!(it instanceof Map<?, ?> m)) continue;
            String part = isGroup(m) ? group(m, p) : isComplete(m) ? condition(m, p) : null;
            if (part != null) {
                sql.add(part);
                rendered++;
            }
        }
        if (rendered == 0) return null;
        // NOT over SQL's three-valued logic would turn "NULL" into "NULL" (row dropped) where ConditionTree
        // says false ⇒ NOT false ⇒ true; COALESCE pins the group to two values first.
        return ConditionTree.flag(g, "negate") ? "(NOT COALESCE(" + sql + ", FALSE))" : sql.toString();
    }

    private static boolean isGroup(Map<?, ?> m) {
        Object kind = m.get("kind");
        if ("group".equals(kind)) return true;
        if ("condition".equals(kind)) return false;
        return m.containsKey("items") || m.containsKey("conditions");
    }

    private static boolean isComplete(Map<?, ?> c) {
        return ConditionTree.isComplete(c);
    }

    // ── one leaf ─────────────────────────────────────────────────────────────────

    private static String condition(Map<?, ?> c, List<Object> p) {
        String f = ident(strOrEmpty(c.get("field")));
        String operator = strOrEmpty(c.get("operator"));
        String value = strOrEmpty(c.get("value"));
        String value2 = strOrEmpty(c.get("value2"));
        boolean ic = ConditionTree.flag(c, "ignoreCase");
        String valueField = strOrEmpty(c.get("valueField"));
        if (!valueField.isEmpty()) return fields(f, operator, ident(valueField), ic);
        return switch (operator) {
            // ConditionTree treats null and '' alike for the null checks
            case "isNull" -> "(" + f + " IS NULL OR CAST(" + f + " AS VARCHAR) = '')";
            case "isNotNull" -> "(" + f + " IS NOT NULL AND CAST(" + f + " AS VARCHAR) <> '')";
            case "contains" -> like(f, value, true, true, p);
            case "startsWith" -> like(f, value, false, true, p);
            case "endsWith" -> like(f, value, true, false, p);
            case "matches" -> "regexp_matches(CAST(" + f + " AS VARCHAR), " + lit(value, p) + (ic ? ", 'i')" : ")");
            case "in" -> in(f, value, ic, p);
            case "between" -> "(" + typed(f, ">=", value, false, p) + " AND " + typed(f, "<=", value2, false, p) + ")";
            case "=", "!=" -> typed(f, operator, value, ic, p);
            case "<", "<=", ">", ">=" -> typed(f, operator, value, false, p);
            default -> "FALSE";
        };
    }

    private static String in(String f, String csv, boolean ic, List<Object> p) {
        StringJoiner sql = new StringJoiner(" OR ", "(", ")");
        int n = 0;
        for (String x : csv.split(",")) {
            String xt = x.trim();
            if (!xt.isEmpty()) {
                sql.add(typed(f, "=", xt, ic, p));
                n++;
            }
        }
        return n == 0 ? "FALSE" : sql.toString();
    }

    /** Case-insensitive substring match; {@code %}/{@code _}/{@code \} in the operand are escaped
     *  before the wildcard ends are added. */
    private static String like(String f, String value, boolean pre, boolean post, List<Object> p) {
        String v = value.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = (pre ? "%" : "") + v + (post ? "%" : "");
        return "LOWER(CAST(" + f + " AS VARCHAR)) LIKE " + lit(pattern, p) + " ESCAPE '\\'";
    }

    /** Operand-driven typed comparison (see class doc). */
    private static String typed(String f, String op, String v, boolean ic, List<Object> p) {
        String sqlOp = "!=".equals(op) ? "<>" : op;
        if (isNumeric(v)) {
            double d = Double.parseDouble(v);
            if (p != null) {
                p.add(d);
                return "TRY_CAST(" + f + " AS DOUBLE) " + sqlOp + " CAST(? AS DOUBLE)";
            }
            // NaN / Infinity print as bare words DuckDB reads as column names; quote them as DOUBLE literals
            return "TRY_CAST(" + f + " AS DOUBLE) " + sqlOp + " "
                    + (Double.isFinite(d) ? String.valueOf(d) : "'" + d + "'::DOUBLE");
        }
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            // ConditionTree: a cell is true when Boolean true or the string 'true'; false orders below true
            String cell = "(CASE WHEN LOWER(CAST(" + f + " AS VARCHAR)) = 'true' THEN 1 ELSE 0 END)";
            return cell + " " + sqlOp + " " + ("true".equalsIgnoreCase(v) ? 1 : 0);
        }
        if (isDateLike(v))
            return "TRY_CAST(CAST(" + f + " AS VARCHAR) AS TIMESTAMP) " + sqlOp + " TRY_CAST(" + lit(v, p) + " AS TIMESTAMP)";
        if (ic) return "LOWER(CAST(" + f + " AS VARCHAR)) " + sqlOp + " " + lit(v.toLowerCase(Locale.ROOT), p);
        return "CAST(" + f + " AS VARCHAR) " + sqlOp + " " + lit(v, p);
    }

    /**
     * Field-to-field leaf; {@code g} is an already-quoted identifier ({@link #ident}, the same rule as
     * {@code field}). Mirrors {@code ConditionTree.matchFields}: per row, both cells numeric &rArr; numbers;
     * else both date-like &rArr; timestamps; else strings (lower-cased when {@code ic}). A NULL cell on either
     * side yields NULL, i.e. no match.
     */
    private static String fields(String f, String operator, String g, boolean ic) {
        String sa = "CAST(" + f + " AS VARCHAR)", sb = "CAST(" + g + " AS VARCHAR)";
        switch (operator) {
            case "contains": return "(LOWER(" + sa + ") LIKE '%' || " + escLike("LOWER(" + sb + ")") + " || '%' ESCAPE '\\')";
            case "startsWith": return "(LOWER(" + sa + ") LIKE " + escLike("LOWER(" + sb + ")") + " || '%' ESCAPE '\\')";
            case "endsWith": return "(LOWER(" + sa + ") LIKE '%' || " + escLike("LOWER(" + sb + ")") + " ESCAPE '\\')";
            case "=", "!=", "<", "<=", ">", ">=": break;
            default: return "FALSE";
        }
        String sqlOp = "!=".equals(operator) ? "<>" : operator;
        String na = "TRY_CAST(" + sa + " AS DOUBLE)", nb = "TRY_CAST(" + sb + " AS DOUBLE)";
        String ta = "TRY_CAST(" + sa + " AS TIMESTAMP)", tb = "TRY_CAST(" + sb + " AS TIMESTAMP)";
        String shape = "regexp_matches(" + sa + ", '\\d{4}') AND regexp_matches(" + sa + ", '[-/:T]') AND "
                + "regexp_matches(" + sb + ", '\\d{4}') AND regexp_matches(" + sb + ", '[-/:T]')";
        String str = ic ? "LOWER(" + sa + ") " + sqlOp + " LOWER(" + sb + ")" : sa + " " + sqlOp + " " + sb;
        return "(CASE WHEN regexp_full_match(" + sa + ", '-?\\d+(\\.\\d+)?') AND regexp_full_match(" + sb + ", '-?\\d+(\\.\\d+)?') "
                + "THEN " + na + " " + sqlOp + " " + nb
                + " WHEN " + shape + " AND " + ta + " IS NOT NULL AND " + tb + " IS NOT NULL THEN " + ta + " " + sqlOp + " " + tb
                + " ELSE " + str + " END)";
    }

    /** SQL expression escaping LIKE metacharacters of a runtime string expression. */
    private static String escLike(String expr) {
        return "REPLACE(REPLACE(REPLACE(" + expr + ", '\\', '\\\\'), '%', '\\%'), '_', '\\_')";
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Same shape-guarded date sniff as {@link ConditionTree}: 4-digit year + a date/time separator. */
    private static boolean isDateLike(String s) {
        if (s == null || !s.matches(".*\\d{4}.*") || !s.matches(".*[-/:T].*")) return false;
        String v = s.trim();
        try {
            Instant.parse(v);
            return true;
        } catch (Exception ignored) {
            // try the next shape
        }
        try {
            LocalDateTime.parse(v);
            return true;
        } catch (Exception ignored) {
            // try the next shape
        }
        try {
            LocalDate.parse(v);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ── quoting ──────────────────────────────────────────────────────────────────

    private static String ident(String name) {
        return SqlIdent.q(name);
    }

    /** A string operand: bound as a {@code VARCHAR} parameter when {@code p} collects them, else an escaped literal. */
    private static String lit(String v, List<Object> p) {
        if (p == null) return "'" + v.replace("'", "''") + "'";
        p.add(v);
        return "CAST(? AS VARCHAR)";
    }
}
