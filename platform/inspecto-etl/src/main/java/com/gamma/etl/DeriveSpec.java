package com.gamma.etl;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code derive} block of a Decode Profile (Parsing D9 slice 1): <b>derived columns</b> computed from a
 * repeated child (a list) of a decoded record, from a CLOSED vocabulary — {@code select} (pick one element,
 * surface a field) and {@code reduce} (aggregate a list to one scalar). No expression, no {@code $}-reference:
 * the profile is untrusted input, so this parses a map into immutable compiled operations and evaluates them
 * over the decoded record map; nothing in it is ever interpreted as code.
 *
 * <p>Shape (segment key, then column name, then operation):
 * <pre>
 * derive:
 *   CCNRecord:
 *     SERVED_MSISDN: { select: servedSubscriptionIDs, where: { subscriptionIDType: 0 }, take: subscriptionIDValue }
 *     DA_CHARGE:     { reduce: dedicatedAccounts, op: sum, of: dedicatedAccountValue }
 * </pre>
 * Values are VARCHAR strings. A missing path, an empty list, no match, no numeric value is {@code null} — never
 * {@code 0}. Failures name an {@code ASN1-DERIVE-*} code and never echo file contents.
 */
public final class DeriveSpec {

    /** Default and ceiling of {@code max_list_elements}: the profile may lower or raise it up to the ceiling. */
    public static final int DEFAULT_MAX_LIST_ELEMENTS = 10_000;
    public static final int CEILING_MAX_LIST_ELEMENTS = 1_000_000;
    /** Default and ceiling of {@code max_derived_columns} per segment. */
    public static final int DEFAULT_MAX_DERIVED_COLUMNS = 64;
    public static final int CEILING_MAX_DERIVED_COLUMNS = 256;
    static final int MAX_PATH_DEPTH = 8;

    private static final Set<String> SELECT_KEYS = Set.of("select", "where", "take", "nth");
    private static final Set<String> REDUCE_KEYS = Set.of("reduce", "op", "of", "group_by", "sep");
    private static final Set<String> OPS = Set.of("sum", "min", "max", "first", "count", "join");

    /** Per-file counters the caller surfaces (never failures). */
    public static final class Stats {
        public long nonNumeric;
        public long noMatch;
    }

    private record Op(String column, boolean select, String[] list, String[] where, String whereValue,
                      String[] leaf, int nth, String op, String[][] groupBy, String sep) {
    }

    private final Map<String, List<Op>> bySegment;
    private final int maxListElements;

    private DeriveSpec(Map<String, List<Op>> bySegment, int maxListElements) {
        this.bySegment = bySegment;
        this.maxListElements = maxListElements;
    }

    /** No {@code derive}: a spec that adds nothing. */
    public static DeriveSpec none() {
        return new DeriveSpec(Map.of(), DEFAULT_MAX_LIST_ELEMENTS);
    }

    /** Derived column names of a segment, in profile order (empty when none). */
    public List<String> columns(String segmentKey) {
        List<String> out = new ArrayList<>();
        for (Op o : bySegment.getOrDefault(segmentKey, List.of())) out.add(o.column);
        return out;
    }

    /**
     * Parse and validate. All errors are {@link IllegalArgumentException}s starting with an {@code ASN1-DERIVE-*} code.
     *
     * @param derive      the {@code derive} value (may be {@code null} = none)
     * @param segmentKeys the declared segment keys, or {@code null} when the segments are not known here (then
     *                    {@code UNKNOWN-SEGMENT} is not checked)
     * @param maxList     the {@code max_list_elements} value or {@code null} for the default
     * @param maxColumns  the {@code max_derived_columns} value or {@code null} for the default
     */
    public static DeriveSpec parse(Object derive, Set<String> segmentKeys, Object maxList, Object maxColumns) {
        int listCap = limit("max_list_elements", maxList, DEFAULT_MAX_LIST_ELEMENTS, CEILING_MAX_LIST_ELEMENTS);
        int colCap = limit("max_derived_columns", maxColumns, DEFAULT_MAX_DERIVED_COLUMNS, CEILING_MAX_DERIVED_COLUMNS);
        if (derive == null) return new DeriveSpec(Map.of(), listCap);
        if (!(derive instanceof Map<?, ?> segs))
            throw err("UNKNOWN-OP", "derive must be a map of segment key to derived columns");
        Map<String, List<Op>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> se : segs.entrySet()) {
            String seg = String.valueOf(se.getKey());
            if (segmentKeys != null && !segmentKeys.contains(seg))
                throw err("UNKNOWN-SEGMENT", "derive names segment '" + seg + "', which is not a key of segments");
            if (!(se.getValue() instanceof Map<?, ?> cols))
                throw err("UNKNOWN-OP", "derive." + seg + " must be a map of column name to operation");
            if (cols.size() > colCap)
                throw err("LIMIT", "derive." + seg + " has " + cols.size() + " columns, over max_derived_columns " + colCap);
            Set<String> seen = new LinkedHashSet<>();
            List<Op> ops = new ArrayList<>();
            for (Map.Entry<?, ?> ce : cols.entrySet()) {
                String col = String.valueOf(ce.getKey()).trim();
                if (col.isEmpty() || !seen.add(col) || col.equalsIgnoreCase("EVENT_TYPE"))
                    throw err("COLUMN-CLASH", "derive." + seg + " column name '" + col + "' is empty, duplicated or reserved");
                ops.add(compile(seg, col, ce.getValue()));
            }
            out.put(seg, List.copyOf(ops));
        }
        return new DeriveSpec(out, listCap);
    }

    /**
     * Refuse a derived column that shares a name with a segment's {@code raw.fields} column.
     *
     * @param rawColumns the segment's {@code raw.fields} names
     */
    public void checkClash(String segmentKey, List<String> rawColumns) {
        for (Op o : bySegment.getOrDefault(segmentKey, List.of()))
            if (rawColumns.contains(o.column))
                throw err("COLUMN-CLASH", "derive." + segmentKey + " column '" + o.column + "' clashes with a raw.fields column");
    }

    /** Evaluate a segment's operations over the decoded record; one value (or {@code null}) per derived column. */
    public Object[] evaluate(String segmentKey, Map<String, Object> decoded, Stats stats) throws IOException {
        List<Op> ops = bySegment.get(segmentKey);
        if (ops == null) return new Object[0];
        Object[] out = new Object[ops.size()];
        for (int i = 0; i < out.length; i++) {
            Op o = ops.get(i);
            out[i] = o.select ? select(o, decoded, stats) : reduce(o, decoded, stats);
        }
        return out;
    }

    // ── compile ──────────────────────────────────────────────────────────────

    private static Op compile(String seg, String col, Object raw) {
        String at = "derive." + seg + "." + col;
        if (!(raw instanceof Map<?, ?> m))
            throw err("UNKNOWN-OP", at + " must be a map holding select or reduce");
        boolean sel = m.containsKey("select"), red = m.containsKey("reduce");
        if (sel == red) throw err("UNKNOWN-OP", at + " must hold exactly one of select / reduce");
        Set<String> allowed = sel ? SELECT_KEYS : REDUCE_KEYS;
        for (Object k : m.keySet())
            if (!allowed.contains(String.valueOf(k)))
                throw err("UNKNOWN-OP", at + " has key '" + k + "' outside the closed vocabulary");
        String[] list = path(at, m.get(sel ? "select" : "reduce"));
        if (sel) {
            if (!(m.get("where") instanceof Map<?, ?> w) || w.size() != 1)
                throw err("UNKNOWN-OP", at + ".where must be a map of exactly one field to a literal");
            Map.Entry<?, ?> we = w.entrySet().iterator().next();
            if (we.getValue() == null || we.getValue() instanceof Map<?, ?> || we.getValue() instanceof List<?>)
                throw err("UNKNOWN-OP", at + ".where value must be a scalar literal");
            int nth = 0;
            if (m.get("nth") != null) {
                try {
                    nth = Integer.parseInt(String.valueOf(m.get("nth")).trim());
                } catch (NumberFormatException e) {
                    nth = -1;
                }
                if (nth < 0) throw err("UNKNOWN-OP", at + ".nth must be a non-negative integer");
            }
            return new Op(col, true, list, path(at + ".where", we.getKey()), String.valueOf(we.getValue()),
                    path(at + ".take", m.get("take")), nth, null, null, null);
        }
        String op = String.valueOf(m.get("op")).trim();
        if (m.get("op") == null || !OPS.contains(op))
            throw err("UNKNOWN-OP", at + ".op must be one of " + new java.util.TreeSet<>(OPS));
        String[] leaf = null;
        if (m.get("of") != null) leaf = path(at + ".of", m.get("of"));
        else if (!op.equals("count") && !op.equals("join") && !op.equals("first"))
            throw err("BAD-PATH", at + ".of is required for op " + op);
        String[][] groupBy = null;
        if (m.get("group_by") != null) {
            if (op.equals("first") || op.equals("join"))
                throw err("UNKNOWN-OP", at + ".group_by is not supported with op " + op);
            if (!(m.get("group_by") instanceof List<?> gl) || gl.isEmpty())
                throw err("BAD-PATH", at + ".group_by must be a non-empty list of leaf fields");
            groupBy = new String[gl.size()][];
            for (int i = 0; i < groupBy.length; i++) groupBy[i] = path(at + ".group_by", gl.get(i));
        }
        String sep = "";
        if (op.equals("join")) {
            sep = m.get("sep") == null ? "," : String.valueOf(m.get("sep"));
            if (sep.length() != 1) throw err("UNKNOWN-OP", at + ".sep must be one character");
        } else if (m.get("sep") != null) throw err("UNKNOWN-OP", at + ".sep applies to op join only");
        return new Op(col, false, list, null, null, leaf, 0, op, groupBy, sep);
    }

    private static String[] path(String at, Object v) {
        String s = v == null ? "" : String.valueOf(v).trim();
        String[] steps = s.split("\\.", -1);
        if (s.isEmpty() || steps.length > MAX_PATH_DEPTH)
            throw err("BAD-PATH", at + " path is empty or deeper than " + MAX_PATH_DEPTH);
        for (String st : steps)
            if (!st.matches("[A-Za-z_][A-Za-z0-9_-]*"))
                throw err("BAD-PATH", at + " path steps must be identifiers separated by dots");
        return steps;
    }

    private static int limit(String key, Object v, int dflt, int ceiling) {
        if (v == null) return dflt;
        int n;
        try {
            n = Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            n = 0;
        }
        if (n <= 0 || n > ceiling)
            throw err("LIMIT", key + " must be between 1 and " + ceiling);
        return n;
    }

    private static IllegalArgumentException err(String code, String msg) {
        return new IllegalArgumentException("ASN1-DERIVE-" + code + ": " + msg);
    }

    // ── evaluate ─────────────────────────────────────────────────────────────

    private static Object walk(Object current, String[] steps) {
        for (String step : steps) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = m.get(step);
            if (current == null) return null;
        }
        return current;
    }

    private static String leaf(Object element, String[] steps) {
        Object v = steps == null ? element : walk(element, steps);
        return v == null || v instanceof Map<?, ?> || v instanceof List<?> ? null : String.valueOf(v);
    }

    /** The list at the path, or null when absent / not a list; a list over the cap fails the file. */
    private List<?> list(Op o, Map<String, Object> decoded) throws IOException {
        if (!(walk(decoded, o.list) instanceof List<?> l)) return null;
        if (l.size() > maxListElements)
            throw new IOException("ASN1-DERIVE-LIST-TOO-LARGE: derived column " + o.column + " list has "
                    + l.size() + " elements, over max_list_elements " + maxListElements);
        return l;
    }

    private Object select(Op o, Map<String, Object> decoded, Stats stats) throws IOException {
        List<?> l = list(o, decoded);
        int seen = 0;
        if (l != null)
            for (Object e : l)
                if (o.whereValue.equals(leaf(e, o.where)) && seen++ == o.nth) return leaf(e, o.leaf);
        stats.noMatch++;
        return null;
    }

    private Object reduce(Op o, Map<String, Object> decoded, Stats stats) throws IOException {
        List<?> l = list(o, decoded);
        if (l == null || l.isEmpty()) return null;
        switch (o.op) {
            case "count": {
                return String.valueOf(l.size());
            }
            case "first": {
                for (Object e : l) {
                    String v = leaf(e, o.leaf);
                    if (v != null) return v;
                }
                return null;
            }
            case "join": {
                StringBuilder sb = new StringBuilder();
                for (Object e : l) {
                    String v = leaf(e, o.leaf);
                    if (v == null) continue;
                    if (sb.length() > 0) sb.append(o.sep);
                    sb.append(v);
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            default:
                return numeric(o, l, stats);
        }
    }

    /** sum / min / max over BigDecimal leaves; with group_by, per group then combined (same op) into one scalar. */
    private static Object numeric(Op o, List<?> l, Stats stats) {
        Map<List<String>, BigDecimal> groups = new LinkedHashMap<>();
        for (Object e : l) {
            BigDecimal d;
            try {
                String v = leaf(e, o.leaf);
                if (v == null) continue;
                d = new BigDecimal(v.trim());
            } catch (NumberFormatException bad) {
                stats.nonNumeric++;
                continue;
            }
            List<String> key = new ArrayList<>();
            if (o.groupBy != null) for (String[] g : o.groupBy) key.add(leaf(e, g));
            groups.merge(key, d, (a, b) -> combine(o.op, a, b));
        }
        BigDecimal total = null;
        for (BigDecimal g : groups.values()) total = total == null ? g : combine(o.op, total, g);
        return total == null ? null : total.toPlainString();
    }

    private static BigDecimal combine(String op, BigDecimal a, BigDecimal b) {
        return switch (op) {
            case "min" -> a.min(b);
            case "max" -> a.max(b);
            default -> a.add(b);
        };
    }
}
