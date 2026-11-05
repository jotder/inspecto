package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.util.SqlIdent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * No-code row filtering in Add data ({@code ADD-DATA-ROW-FILTER-1}, DT-7: "filter rows" is Basic shaping). A commit's
 * {@code filter: [{column, op, value | min | max}]} - several conditions, all of which a row must meet - is checked here
 * and rendered to the import Pipeline's post-parse row predicate ({@code processing.csv_settings.where}), which
 * {@code DataTransformer.materialize} applies on every run of that Pipeline: the commit, <i>Add a file</i>, <i>Run now</i>,
 * the inbox poll and a Postgres snapshot refresh.
 *
 * <p><b>No raw value reaches SQL.</b> Every value is parsed to its column's type first (a number to a {@link BigDecimal} or
 * {@code long}, a date to a {@link LocalDate}, a time to a {@link LocalDateTime}, a yes/no to a boolean) and rendered from
 * that parsed value; text is the one kind kept as given, and it is rendered as a quoted literal ({@link SqlIdent#sqlStr})
 * with control characters refused. The column is a kept column of the plan, quoted as an identifier. The rendered
 * predicate then passes the Pipeline save gate, which binds it with {@code SqlGuard} and {@code TypeFlow} against the
 * import's own columns.
 *
 * <p>Operators: {@code eq}, {@code ne}, {@code contains} (text only; ignoring case unless the condition says
 * {@code matchCase: true}, operator 2026-11-05) and {@code range} (numbers, dates and
 * times; {@code min} and {@code max} inclusive, either optional). {@code ne} keeps a row whose value is empty
 * ({@code col IS NULL OR col <> v}); every other condition drops it.
 */
final class ImportRowFilter {

    private ImportRowFilter() {}

    static final String EQ = "eq", NE = "ne", CONTAINS = "contains", RANGE = "range";
    /** The closed set of operators. */
    static final Set<String> OPS = Set.of(EQ, NE, CONTAINS, RANGE);
    /** At most this many conditions. */
    static final int MAX_CONDITIONS = 20;
    /** At most this many characters in one text value. */
    static final int MAX_TEXT = 200;
    private static final Set<String> KEYS = Set.of("column", "op", "value", "min", "max", "matchCase");
    private static final Set<String> NUMBERS = Set.of("BIGINT", "DOUBLE");
    private static final Set<String> TIMES = Set.of("DATE", "TIMESTAMP");
    private static final DateTimeFormatter TS_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** One column the filter may name: its draft name (the body's), its Dataset name (the predicate's) and its type. */
    record Column(String source, String name, String type) {}

    /** The conditions as the Dataset entry keeps them (Dataset column names, canonical values) and the predicate. */
    record Result(List<Map<String, Object>> stored, String where) {
        static final Result NONE = new Result(List.of(), null);
    }

    /**
     * Check {@code raw} (the commit body's {@code filter}; null or absent = no filter) against the kept columns and render
     * it. {@code dropped} names the draft columns left out (named in the refusal). Any refusal is 422 naming the condition.
     */
    static Result of(Object raw, List<Column> kept, Set<String> dropped) {
        if (raw == null) return Result.NONE;
        if (!(raw instanceof List<?> list))
            throw refuse("filter must be a list of {column, op, value | min | max}");
        if (list.size() > MAX_CONDITIONS)
            throw refuse("filter holds " + list.size() + " conditions; at most " + MAX_CONDITIONS);
        Map<String, Column> bySource = new LinkedHashMap<>();
        for (Column c : kept) bySource.put(c.source(), c);
        List<Map<String, Object>> stored = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String at = "filter[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> m))
                throw refuse(at + " must be {column, op, value | min | max}");
            for (Object k : m.keySet())
                if (!KEYS.contains(String.valueOf(k)))
                    throw refuse(at + " has an unknown key '" + k + "'; a condition is {column, op, value | min | max}");
            if (!(m.get("column") instanceof String col) || col.isBlank())
                throw refuse(at + " needs the draft column's name");
            Column c = bySource.get(col);
            if (c == null)
                throw refuse(at + ": " + (dropped.contains(col) ? "'" + col + "' is left out, so no row can be kept by it"
                        : "the draft has no column '" + col + "'"));
            if (!(m.get("op") instanceof String op) || !OPS.contains(op))
                throw refuse(at + " (" + col + "): op must be one of eq, ne, contains, range; not '" + m.get("op") + "'");
            String label = at + " (" + col + " " + op + ")";
            Object matchCase = m.get("matchCase");
            if (matchCase != null && !(matchCase instanceof Boolean))
                throw refuse(label + ": matchCase is true or false");
            if (Boolean.TRUE.equals(matchCase) && !CONTAINS.equals(op))
                throw refuse(label + ": matchCase belongs to contains");
            boolean exact = Boolean.TRUE.equals(matchCase);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("column", c.name());
            s.put("op", op);
            String ident = SqlIdent.q(c.name());
            if (RANGE.equals(op)) {
                if (!NUMBERS.contains(c.type()) && !TIMES.contains(c.type()))
                    throw refuse(label + ": a range applies to number, date and time columns; '" + col + "' is " + c.type());
                if (m.containsKey("value")) throw refuse(label + ": a range takes min and max, not value");
                Typed lo = m.get("min") == null ? null : parse(m.get("min"), c.type(), label + " min");
                Typed hi = m.get("max") == null ? null : parse(m.get("max"), c.type(), label + " max");
                if (lo == null && hi == null) throw refuse(label + ": a range needs a min, a max or both");
                if (lo != null && hi != null && lo.compareTo(hi) > 0)
                    throw refuse(label + ": min " + lo.text() + " is after max " + hi.text());
                List<String> bounds = new ArrayList<>();
                if (lo != null) {
                    s.put("min", lo.text());
                    bounds.add(ident + " >= " + lo.sql());
                }
                if (hi != null) {
                    s.put("max", hi.text());
                    bounds.add(ident + " <= " + hi.sql());
                }
                parts.add(String.join(" AND ", bounds));
            } else {
                if (m.containsKey("min") || m.containsKey("max")) throw refuse(label + ": min and max belong to a range");
                if (m.get("value") == null) throw refuse(label + " needs a value");
                if (CONTAINS.equals(op) && !"VARCHAR".equals(c.type()))
                    throw refuse(label + ": contains applies to text columns; '" + col + "' is " + c.type());
                Typed v = parse(m.get("value"), c.type(), label);
                s.put("value", v.text());
                if (exact) s.put("matchCase", true);
                parts.add(switch (op) {
                    case EQ -> ident + " = " + v.sql();
                    // not IS DISTINCT FROM: SqlGuard's lexical scan reads "FROM '<text>'" as a file read and refuses it
                    case NE -> ident + " IS NULL OR " + ident + " <> " + v.sql();
                    default -> exact ? "contains(" + ident + ", " + v.sql() + ")"
                            : "contains(lower(" + ident + "), lower(" + v.sql() + "))";
                });
            }
            stored.add(s);
        }
        if (stored.isEmpty()) return Result.NONE;
        StringBuilder where = new StringBuilder();
        for (String p : parts) {
            if (!where.isEmpty()) where.append(" AND ");
            where.append('(').append(p).append(')');
        }
        return new Result(List.copyOf(stored), where.toString());
    }

    /** A value parsed to its column's type: the canonical text kept, the SQL literal rendered from the parsed value. */
    private record Typed(String text, String sql, Comparable<Object> key) {
        int compareTo(Typed o) {
            return key.compareTo(o.key);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Typed typed(String text, String sql, Comparable key) {
        return new Typed(text, sql, key);
    }

    private static Typed parse(Object raw, String type, String label) {
        if (!(raw instanceof String) && !(raw instanceof Number) && !(raw instanceof Boolean))
            throw refuse(label + ": the value must be text, a number or true / false");
        String v = String.valueOf(raw).trim();
        if (v.isEmpty()) throw refuse(label + " needs a value");
        try {
            switch (type) {
                case "BIGINT" -> {
                    long n = Long.parseLong(v);
                    return typed(Long.toString(n), Long.toString(n), BigDecimal.valueOf(n));
                }
                case "DOUBLE" -> {
                    if (v.length() > 40) throw new NumberFormatException();
                    BigDecimal n = new BigDecimal(v);
                    // toPlainString expands an exponent into its digits: 1E999999999 would build a 1e9-character literal
                    if (Math.abs(n.scale()) > 40 || n.precision() - n.scale() > 40) throw new NumberFormatException();
                    String plain = n.stripTrailingZeros().toPlainString();
                    return typed(plain, plain, n);
                }
                case "DATE" -> {
                    LocalDate d = LocalDate.parse(v);
                    return typed(d.toString(), "DATE '" + d + "'", d);
                }
                case "TIMESTAMP" -> {
                    LocalDateTime t = v.length() == 10 ? LocalDate.parse(v).atStartOfDay()
                            : LocalDateTime.parse(v.replace(' ', 'T'));
                    String out = TS_OUT.format(t);
                    return typed(out, "TIMESTAMP '" + out + "'", t);
                }
                case "BOOLEAN" -> {
                    String b = v.toLowerCase(java.util.Locale.ROOT);
                    if (!b.equals("true") && !b.equals("false")) throw new IllegalArgumentException();
                    return typed(b, b.toUpperCase(java.util.Locale.ROOT), b);
                }
                default -> {
                    if (v.length() > MAX_TEXT) throw refuse(label + ": a text value is at most " + MAX_TEXT + " characters");
                    for (int i = 0; i < v.length(); i++)
                        if (Character.isISOControl(v.charAt(i))) throw refuse(label + ": a text value holds no control characters");
                    return typed(v, SqlIdent.sqlStr(v), v);
                }
            }
        } catch (DateTimeParseException | IllegalArgumentException notThatType) {
            throw refuse(label + ": '" + shown(v) + "' is not " + switch (type) {
                case "BIGINT" -> "a whole number";
                case "DOUBLE" -> "a number";
                case "DATE" -> "a date (yyyy-mm-dd)";
                case "TIMESTAMP" -> "a time (yyyy-mm-dd hh:mm[:ss])";
                default -> "true or false";
            });
        }
    }

    /** A refused value as shown back: shortened, control characters replaced. */
    private static String shown(String v) {
        String s = v.length() > 40 ? v.substring(0, 40) + "…" : v;
        return s.replaceAll("\\p{Cntrl}", "?");
    }

    private static ApiException refuse(String message) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message);
    }
}
