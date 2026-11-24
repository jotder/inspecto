package com.gamma.etl;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    private static final Set<String> SELECT_KEYS = Set.of("select", "where", "take", "nth", "rename", "prefix", "postfix");
    private static final Set<String> REDUCE_KEYS = Set.of("reduce", "op", "of", "sep", "rename", "prefix", "postfix");
    private static final Set<String> FOLD_KEYS = Set.of(
            "fold", "mode", "item_delim", "field_delim", "fields", "of", "group_by", "aggs",
            "rename", "prefix", "postfix");
    private static final Set<String> OPS = Set.of("sum", "min", "max", "mean", "first", "count", "join");
    static final Set<String> AGG_OPS = Set.of("sum", "min", "max", "mean", "avg", "count", "keep");

    /** Per-file counters the caller surfaces (never failures). */
    public static final class Stats {
        public long nonNumeric;
        public long noMatch;
    }

    private record Op(String column, String kind, String[] list, String[] where, String whereValue,
                      String[] leaf, int nth, String op, String sep,
                      String foldMode, String itemDelim, String fieldDelim,
                      List<String> foldFields, List<String> groupBy, Map<String, String> aggs) {
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
                String rawCol = String.valueOf(ce.getKey()).trim();
                String col = rawCol;
                if (ce.getValue() instanceof Map<?, ?> cm) {
                    col = applyNameAffixes(rawCol, cm.get("rename"), cm.get("prefix"), cm.get("postfix")).trim();
                }
                if (col.isEmpty() || !seen.add(col) || col.equalsIgnoreCase("EVENT_TYPE"))
                    throw err("COLUMN-CLASH", "derive." + seg + " column name '" + col + "' is empty, duplicated or reserved");
                ops.add(compile(seg, col, ce.getValue()));
            }
            out.put(seg, List.copyOf(ops));
        }
        return new DeriveSpec(out, listCap);
    }

    /** Apply optional leaf {@code rename}, {@code prefix}, and {@code postfix} to a column name. */
    public static String applyNameAffixes(String baseName, Object rename, Object prefix, Object postfix) {
        String r = rename == null ? "" : String.valueOf(rename).trim();
        String p = prefix == null ? "" : String.valueOf(prefix);
        String s = postfix == null ? "" : String.valueOf(postfix);
        String effective = r.isEmpty() ? baseName : r;
        return p + effective + s;
    }

    /**
     * Refuse a derived column that shares a name with a segment's {@code raw.fields} column,
     * or duplicate/reserved column names introduced by {@code rename} / {@code prefix} / {@code postfix}.
     *
     * @param rawColumns the segment's {@code raw.fields} names
     */
    public void checkClash(String segmentKey, List<String> rawColumns) {
        Set<String> seenRaw = new LinkedHashSet<>();
        for (String rc : rawColumns) {
            if (rc == null || rc.isEmpty() || !seenRaw.add(rc) || rc.equalsIgnoreCase("EVENT_TYPE"))
                throw err("COLUMN-CLASH", "segment '" + segmentKey + "' raw.fields column '" + rc + "' is empty, duplicated or reserved");
        }
        for (Op o : bySegment.getOrDefault(segmentKey, List.of()))
            if (seenRaw.contains(o.column))
                throw err("COLUMN-CLASH", "derive." + segmentKey + " column '" + o.column + "' clashes with a raw.fields column");
    }

    /** Evaluate a segment's operations over the decoded record; one value (or {@code null}) per derived column. */
    public Object[] evaluate(String segmentKey, Map<String, Object> decoded, Stats stats) throws IOException {
        List<Op> ops = bySegment.get(segmentKey);
        if (ops == null) return new Object[0];
        Object[] out = new Object[ops.size()];
        for (int i = 0; i < out.length; i++) {
            Op o = ops.get(i);
            out[i] = switch (o.kind) {
                case "select" -> select(o, decoded, stats);
                case "fold" -> fold(o, decoded, stats);
                default -> reduce(o, decoded, stats);
            };
        }
        return out;
    }

    // ── compile ──────────────────────────────────────────────────────────────

    private static Op compile(String seg, String col, Object raw) {
        String at = "derive." + seg + "." + col;
        if (!(raw instanceof Map<?, ?> m))
            throw err("UNKNOWN-OP", at + " must be a map holding select, reduce, or fold");
        boolean sel = m.containsKey("select"), red = m.containsKey("reduce"), fld = m.containsKey("fold");
        int count = (sel ? 1 : 0) + (red ? 1 : 0) + (fld ? 1 : 0);
        if (count != 1) throw err("UNKNOWN-OP", at + " must hold exactly one of select / reduce / fold");
        Set<String> allowed = sel ? SELECT_KEYS : (red ? REDUCE_KEYS : FOLD_KEYS);
        for (Object k : m.keySet()) {
            String ks = String.valueOf(k);
            if (!fld && "group_by".equals(ks))
                throw err("UNKNOWN-OP", at + ".group_by was removed from the derive vocabulary (it never changed the "
                        + "scalar); to get one row per group use a children explode");
            if (!allowed.contains(ks))
                throw err("UNKNOWN-OP", at + " has key '" + k + "' outside the closed vocabulary");
        }
        String[] list = path(at, m.get(sel ? "select" : (red ? "reduce" : "fold")));
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
            return new Op(col, "select", list, path(at + ".where", we.getKey()), String.valueOf(we.getValue()),
                    path(at + ".take", m.get("take")), nth, null, null, null, null, null, List.of(), List.of(), Map.of());
        }
        if (fld) {
            String mode = m.get("mode") == null ? "delim" : String.valueOf(m.get("mode")).trim().toLowerCase(Locale.ROOT);
            if (!mode.equals("delim") && !mode.equals("json"))
                throw err("UNKNOWN-OP", at + ".mode must be 'delim' or 'json'");
            String itemDelim = m.get("item_delim") == null ? "|" : String.valueOf(m.get("item_delim"));
            String fieldDelim = m.get("field_delim") == null ? ":" : String.valueOf(m.get("field_delim"));
            List<String> foldFields = parseFieldList(at + ".fields", m.get("fields") != null ? m.get("fields") : m.get("of"));
            List<String> groupBy = parseFieldList(at + ".group_by", m.get("group_by"));
            Map<String, String> aggs = parseAggs(at + ".aggs", m.get("aggs"));
            return new Op(col, "fold", list, null, null, null, 0, null, null,
                    mode, itemDelim, fieldDelim, foldFields, groupBy, aggs);
        }
        String op = String.valueOf(m.get("op")).trim();
        if (m.get("op") == null || !OPS.contains(op))
            throw err("UNKNOWN-OP", at + ".op must be one of " + new java.util.TreeSet<>(OPS));
        String[] leaf = null;
        if (m.get("of") != null) leaf = path(at + ".of", m.get("of"));
        else if (!op.equals("count") && !op.equals("join") && !op.equals("first"))
            throw err("BAD-PATH", at + ".of is required for op " + op);
        String sep = "";
        if (op.equals("join")) {
            sep = m.get("sep") == null ? "," : String.valueOf(m.get("sep"));
            if (sep.length() != 1) throw err("UNKNOWN-OP", at + ".sep must be one character");
        } else if (m.get("sep") != null) throw err("UNKNOWN-OP", at + ".sep applies to op join only");
        return new Op(col, "reduce", list, null, null, leaf, 0, op, sep,
                null, null, null, List.of(), List.of(), Map.of());
    }

    static List<String> parseFieldList(String at, Object raw) {
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>();
        if (raw instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) throw err("BAD-PATH", at + " must not be blank");
            path(at, t);
            out.add(t);
        } else if (raw instanceof List<?> list) {
            for (Object item : list) {
                String t = item == null ? "" : String.valueOf(item).trim();
                if (t.isEmpty()) throw err("BAD-PATH", at + " entries must not be blank");
                path(at, t);
                out.add(t);
            }
        } else {
            throw err("BAD-PATH", at + " must be a field path or list of field paths");
        }
        return List.copyOf(out);
    }

    static Map<String, String> parseAggs(String at, Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> m))
            throw err("UNKNOWN-OP", at + " must be a map of field to aggregation op");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String field = e.getKey() == null ? "" : String.valueOf(e.getKey()).trim();
            if (field.isEmpty()) throw err("BAD-PATH", at + " field key must not be blank");
            path(at, field);
            String op = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim().toLowerCase(Locale.ROOT);
            if (!AGG_OPS.contains(op))
                throw err("UNKNOWN-OP", at + "." + field + " op must be one of " + new java.util.TreeSet<>(AGG_OPS));
            out.put(field, "avg".equals(op) ? "mean" : op);
        }
        return java.util.Collections.unmodifiableMap(out);
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

    /** sum / min / max / mean over BigDecimal leaves. */
    private static Object numeric(Op o, List<?> l, Stats stats) {
        BigDecimal total = null;
        int count = 0;
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
            total = total == null ? d : combine(o.op, total, d);
            count++;
        }
        if (total == null) return null;
        if ("mean".equals(o.op)) {
            return total.divide(BigDecimal.valueOf(count), MathContext.DECIMAL64)
                    .stripTrailingZeros()
                    .toPlainString();
        }
        return total.toPlainString();
    }

    private static BigDecimal combine(String op, BigDecimal a, BigDecimal b) {
        return switch (op) {
            case "min" -> a.min(b);
            case "max" -> a.max(b);
            default -> a.add(b);
        };
    }

    private Object fold(Op o, Map<String, Object> decoded, Stats stats) throws IOException {
        List<?> l = list(o, decoded);
        if (l == null || l.isEmpty()) {
            return "json".equals(o.foldMode) ? "[]" : "";
        }
        List<Map<String, Object>> items;
        if (!o.groupBy.isEmpty() || !o.aggs.isEmpty()) {
            items = groupAndAggregate(l, o.groupBy, o.aggs, stats);
        } else {
            items = new ArrayList<>(l.size());
            for (Object e : l) {
                items.add(asStringObjectMap(e));
            }
        }
        return formatFold(items, o.foldMode, o.itemDelim, o.fieldDelim, o.foldFields);
    }

    /**
     * Group a {@code SET_OF}/{@code SEQUENCE_OF} list by {@code groupBy} paths and compute per-member
     * aggregations ({@code sum}, {@code min}, {@code max}, {@code mean}, {@code count}, {@code keep}).
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> groupAndAggregate(List<?> list, List<String> groupBy,
                                                              Map<String, String> aggs, Stats stats) {
        if (list == null || list.isEmpty()) return List.of();
        Map<List<String>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        List<String[]> groupSteps = new ArrayList<>(groupBy.size());
        for (String g : groupBy) groupSteps.add(g.split("\\.", -1));

        for (Object e : list) {
            Map<String, Object> elem = e instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            List<String> key = new ArrayList<>(groupSteps.size());
            for (String[] gs : groupSteps) {
                key.add(leaf(elem, gs));
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(elem);
        }

        List<Map<String, Object>> result = new ArrayList<>(groups.size());
        for (Map.Entry<List<String>, List<Map<String, Object>>> ge : groups.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            List<String> key = ge.getKey();
            for (int i = 0; i < groupBy.size(); i++) {
                row.put(groupBy.get(i), key.get(i));
            }
            List<Map<String, Object>> members = ge.getValue();
            for (Map.Entry<String, String> ae : aggs.entrySet()) {
                String field = ae.getKey();
                String op = "avg".equals(ae.getValue()) ? "mean" : ae.getValue();
                String[] fSteps = field.split("\\.", -1);
                String outName = "keep".equals(op) ? field : field + "_" + op;
                row.put(outName, aggregateField(members, fSteps, op, stats));
            }
            result.add(row);
        }
        return result;
    }

    private static Object aggregateField(List<Map<String, Object>> members, String[] fSteps,
                                         String op, Stats stats) {
        if ("count".equals(op)) {
            return Long.valueOf(members.size());
        }
        if ("keep".equals(op)) {
            for (Map<String, Object> m : members) {
                String v = leaf(m, fSteps);
                if (v != null) return v;
            }
            return null;
        }
        BigDecimal total = null;
        int count = 0;
        for (Map<String, Object> m : members) {
            String v = leaf(m, fSteps);
            if (v == null) continue;
            BigDecimal d;
            try {
                d = new BigDecimal(v.trim());
            } catch (NumberFormatException bad) {
                if (stats != null) stats.nonNumeric++;
                continue;
            }
            total = total == null ? d : combine(op, total, d);
            count++;
        }
        if (total == null) return null;
        if ("mean".equals(op)) {
            return total.divide(BigDecimal.valueOf(count), MathContext.DECIMAL64).stripTrailingZeros();
        }
        return total.stripTrailingZeros();
    }

    /** Format shaped/raw sub-records as a delimited string or JSON array string. */
    public static String formatFold(List<Map<String, Object>> items, String mode,
                                    String itemDelim, String fieldDelim, List<String> fields) {
        if (items == null || items.isEmpty()) {
            return "json".equals(mode) ? "[]" : "";
        }
        if ("json".equals(mode)) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                appendJsonObject(sb, items.get(i), fields);
            }
            sb.append(']');
            return sb.toString();
        }
        String iDelim = itemDelim == null ? "|" : itemDelim;
        String fDelim = fieldDelim == null ? ":" : fieldDelim;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(iDelim);
            Map<String, Object> item = items.get(i);
            if (fields != null && !fields.isEmpty()) {
                for (int f = 0; f < fields.size(); f++) {
                    if (f > 0) sb.append(fDelim);
                    Object v = walkOrGet(item, fields.get(f));
                    if (v != null && !(v instanceof Map<?, ?>) && !(v instanceof List<?>)) {
                        sb.append(formatScalar(v));
                    }
                }
            } else {
                int f = 0;
                for (Object v : item.values()) {
                    if (v instanceof Map<?, ?> || v instanceof List<?>) continue;
                    if (f++ > 0) sb.append(fDelim);
                    if (v != null) sb.append(formatScalar(v));
                }
            }
        }
        return sb.toString();
    }

    private static Object walkOrGet(Map<String, Object> item, String path) {
        if (item.containsKey(path)) return item.get(path);
        return walk(item, path.split("\\.", -1));
    }

    private static String formatScalar(Object v) {
        if (v instanceof BigDecimal bd) return bd.toPlainString();
        return String.valueOf(v);
    }

    private static void appendJsonObject(StringBuilder sb, Map<String, Object> item, List<String> fields) {
        sb.append('{');
        boolean first = true;
        if (fields != null && !fields.isEmpty()) {
            for (String f : fields) {
                if (!first) sb.append(',');
                first = false;
                appendJsonString(sb, f);
                sb.append(':');
                appendJsonValue(sb, walkOrGet(item, f));
            }
        } else {
            for (Map.Entry<String, Object> e : item.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                appendJsonString(sb, e.getKey());
                sb.append(':');
                appendJsonValue(sb, e.getValue());
            }
        }
        sb.append('}');
    }

    private static void appendJsonValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof BigDecimal bd) {
            sb.append(bd.toPlainString());
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else {
            appendJsonString(sb, String.valueOf(v));
        }
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringObjectMap(Object e) {
        if (!(e instanceof Map<?, ?> m)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> en : m.entrySet()) {
            out.put(String.valueOf(en.getKey()), en.getValue());
        }
        return out;
    }
}
