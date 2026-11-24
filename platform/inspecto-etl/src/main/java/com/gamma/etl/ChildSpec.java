package com.gamma.etl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code children} block of a Decode Profile (Parsing D9 slice 2): an <b>explode</b> of a repeated child. Each
 * element of a list in a decoded record becomes one row of a declared <em>child segment</em>, in addition to the
 * record's own row. Like {@link DeriveSpec} this is a CLOSED, structured map; nothing in it is code.
 *
 * <p>Shape (parent segment key, then list path, then child segment key):
 * <pre>
 * children:
 *   CCNRecord:
 *     usedServiceUnits.usedServiceUnit: UsedServiceUnitRow
 * </pre>
 * The child segment is an ordinary entry of {@code segments}; its schema selectors resolve relative to the child
 * element, plus the reserved selectors {@code $index} (0-based position) and {@code $parent.<dotted path>} (a
 * leaf of the parent record). The engine generates no surrogate key: {@code $parent.*} is the join key.
 *
 * <p>Limits fail closed: more than {@code max_child_rows_per_record} child rows from one record fails the FILE
 * ({@code ASN1-CHILD-TOO-MANY-ROWS}, surfaced by the ingester) — a silently truncated child list is a wrong
 * charge. Errors start with an {@code ASN1-CHILD-*} code and never echo file contents.
 */
public final class ChildSpec {

    /** Default and ceiling of {@code max_child_rows_per_record}; the profile may lower or raise it up to the ceiling. */
    public static final int DEFAULT_MAX_CHILD_ROWS = 10_000;
    public static final int CEILING_MAX_CHILD_ROWS = 1_000_000;

    private static final Set<String> MAP_KEYS = Set.of(
            "segment", "fields", "group_by", "aggs", "join_mode", "mode", "fold",
            "item_delim", "field_delim", "target_column", "prefix", "postfix", "rename");

    /** One explode/shape: the list under the parent record and the child segment (or fold target) it produces. */
    public record Child(
            String[] list,
            String segment,
            List<String> fields,
            List<String> groupBy,
            Map<String, String> aggs,
            String joinMode,
            boolean explicitJoinMode,
            String foldMode,
            String itemDelim,
            String fieldDelim,
            String targetColumn,
            String prefix,
            String postfix,
            Map<String, String> rename) {

        public Child(String[] list, String segment) {
            this(list, segment, List.of(), List.of(), Map.of(), "left", false, null, "|", ":", null, null, null, Map.of());
        }
    }

    private final Map<String, List<Child>> byParent;
    private final int maxChildRows;

    private ChildSpec(Map<String, List<Child>> byParent, int maxChildRows) {
        this.byParent = byParent;
        this.maxChildRows = maxChildRows;
    }

    /** No {@code children}: a spec that adds nothing. */
    public static ChildSpec none() {
        return new ChildSpec(Map.of(), DEFAULT_MAX_CHILD_ROWS);
    }

    public int maxChildRows() {
        return maxChildRows;
    }

    /** The explodes of a parent segment, in profile order (empty when none). */
    public List<Child> of(String parentSegment) {
        return byParent.getOrDefault(parentSegment, List.of());
    }

    /** Every child segment key (a child segment is declared in {@code segments} but is never a record name match). */
    public Set<String> childSegments() {
        Set<String> out = new LinkedHashSet<>();
        for (List<Child> l : byParent.values()) {
            for (Child c : l) {
                if (c.segment != null) out.add(c.segment);
            }
        }
        return out;
    }

    /**
     * Parse and validate. All errors are {@link IllegalArgumentException}s starting with an {@code ASN1-CHILD-*} code.
     *
     * @param children    the {@code children} value (may be {@code null} = none)
     * @param segmentKeys the declared segment keys, or {@code null} when not known here (then
     *                    {@code UNKNOWN-SEGMENT} is not checked)
     * @param maxRows     the {@code max_child_rows_per_record} value or {@code null} for the default
     */
    public static ChildSpec parse(Object children, Set<String> segmentKeys, Object maxRows) {
        int cap = limit(maxRows);
        if (children == null) return new ChildSpec(Map.of(), cap);
        if (!(children instanceof Map<?, ?> parents))
            throw err("BAD-PATH", "children must be a map of parent segment key to {list path: child segment key}");
        Map<String, List<Child>> out = new LinkedHashMap<>();
        Set<String> childKeys = new LinkedHashSet<>();
        for (Map.Entry<?, ?> pe : parents.entrySet()) {
            String parent = String.valueOf(pe.getKey());
            known(segmentKeys, parent, "children names parent segment '" + parent + "'");
            if (!(pe.getValue() instanceof Map<?, ?> lists) || lists.isEmpty())
                throw err("BAD-PATH", "children." + parent + " must be a non-empty map of list path to child segment key");
            List<Child> cs = new ArrayList<>();
            for (Map.Entry<?, ?> le : lists.entrySet()) {
                String at = "children." + parent + "." + le.getKey();
                String[] list = path(at, le.getKey());
                Object val = le.getValue();
                if (val instanceof String s) {
                    if (s.isBlank()) throw err("UNKNOWN-SEGMENT", at + " must name a child segment key");
                    String child = s.trim();
                    known(segmentKeys, child, at + " names child segment '" + child + "'");
                    if (child.equals(parent))
                        throw err("SELF", at + " makes segment '" + child + "' its own child");
                    childKeys.add(child);
                    cs.add(new Child(list, child));
                } else if (val instanceof Map<?, ?> cfg) {
                    Child parsed = parseMapChild(at, parent, list, cfg, segmentKeys);
                    if (parsed.segment != null) childKeys.add(parsed.segment);
                    cs.add(parsed);
                } else {
                    throw err("UNKNOWN-SEGMENT", at + " must name a child segment key");
                }
            }
            out.put(parent, List.copyOf(cs));
        }
        for (String parent : out.keySet())
            if (childKeys.contains(parent))
                throw err("NESTED", "segment '" + parent + "' is both a parent and a child; explode is one level");
        return new ChildSpec(out, cap);
    }

    private static Child parseMapChild(String at, String parent, String[] list,
                                       Map<?, ?> cfg, Set<String> segmentKeys) {
        for (Object k : cfg.keySet()) {
            if (!MAP_KEYS.contains(String.valueOf(k)))
                throw err("BAD-PATH", at + " has unknown key '" + k + "'");
        }
        String segment = null;
        if (cfg.get("segment") != null) {
            String s = String.valueOf(cfg.get("segment")).trim();
            if (s.isEmpty()) throw err("UNKNOWN-SEGMENT", at + ".segment must not be blank");
            known(segmentKeys, s, at + ".segment names child segment '" + s + "'");
            if (s.equals(parent))
                throw err("SELF", at + ".segment makes segment '" + s + "' its own child");
            segment = s;
        }
        List<String> fields = parseGroupBy(at + ".fields", cfg.get("fields"));
        List<String> groupBy = parseGroupBy(at + ".group_by", cfg.get("group_by"));
        Map<String, String> aggs = parseAggs(at + ".aggs", cfg.get("aggs"));
        boolean explicitJoinMode = cfg.containsKey("join_mode") && cfg.get("join_mode") != null;
        String joinMode = "left";
        if (explicitJoinMode) {
            joinMode = String.valueOf(cfg.get("join_mode")).trim().toLowerCase(Locale.ROOT);
            if (!joinMode.equals("left") && !joinMode.equals("inner"))
                throw err("BAD-JOIN-MODE", at + ".join_mode must be 'left' or 'inner'");
        }

        String foldMode = null;
        String itemDelim = cfg.get("item_delim") == null ? "|" : String.valueOf(cfg.get("item_delim"));
        String fieldDelim = cfg.get("field_delim") == null ? ":" : String.valueOf(cfg.get("field_delim"));
        if (cfg.get("fold") != null) {
            Object f = cfg.get("fold");
            if (f instanceof String fs) {
                foldMode = fs.trim().toLowerCase(Locale.ROOT);
            } else if (f instanceof Map<?, ?> fm) {
                foldMode = fm.get("mode") == null ? "delim" : String.valueOf(fm.get("mode")).trim().toLowerCase(Locale.ROOT);
                if (fm.get("item_delim") != null) itemDelim = String.valueOf(fm.get("item_delim"));
                if (fm.get("field_delim") != null) fieldDelim = String.valueOf(fm.get("field_delim"));
            } else {
                throw err("BAD-PATH", at + ".fold must be 'delim', 'json', or a fold config map");
            }
        } else if (cfg.get("mode") != null) {
            String m = String.valueOf(cfg.get("mode")).trim().toLowerCase(Locale.ROOT);
            if (m.equals("delim") || m.equals("json")) foldMode = m;
            else if (m.equals("fold")) foldMode = "delim";
            else if (!m.equals("join"))
                throw err("BAD-PATH", at + ".mode must be 'join', 'fold', 'delim', or 'json'");
        }
        if (foldMode != null && !foldMode.equals("delim") && !foldMode.equals("json"))
            throw err("BAD-PATH", at + ".fold mode must be 'delim' or 'json'");
        if (segment == null)
            throw err("UNKNOWN-SEGMENT", at + ".segment must name a child segment key");

        String targetColumn = cfg.get("target_column") == null ? null : String.valueOf(cfg.get("target_column")).trim();
        String prefix = cfg.get("prefix") == null ? null : String.valueOf(cfg.get("prefix"));
        String postfix = cfg.get("postfix") == null ? null : String.valueOf(cfg.get("postfix"));
        Map<String, String> rename = parseRenameMap(at + ".rename", cfg.get("rename"));

        return new Child(list, segment, fields, groupBy, aggs, joinMode, explicitJoinMode,
                foldMode, itemDelim, fieldDelim, targetColumn, prefix, postfix, rename);
    }

    private static List<String> parseGroupBy(String at, Object raw) {
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

    private static Map<String, String> parseAggs(String at, Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> m))
            throw err("BAD-AGGS", at + " must be a map of field to aggregation op");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String field = e.getKey() == null ? "" : String.valueOf(e.getKey()).trim();
            if (field.isEmpty()) throw err("BAD-AGGS", at + " field key must not be blank");
            path(at, field);
            String op = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim().toLowerCase(Locale.ROOT);
            if (!DeriveSpec.AGG_OPS.contains(op))
                throw err("BAD-AGGS", at + "." + field + " op must be one of " + new java.util.TreeSet<>(DeriveSpec.AGG_OPS));
            out.put(field, "avg".equals(op) ? "mean" : op);
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> parseRenameMap(String at, Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> m))
            throw err("BAD-PATH", at + " must be a map of oldName to newName");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = e.getKey() == null ? "" : String.valueOf(e.getKey()).trim();
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            if (k.isEmpty() || v.isEmpty())
                throw err("BAD-PATH", at + " entries must have non-blank keys and values");
            out.put(k, v);
        }
        return Collections.unmodifiableMap(out);
    }

    private static void known(Set<String> segmentKeys, String key, String what) {
        if (segmentKeys != null && !segmentKeys.contains(key))
            throw err("UNKNOWN-SEGMENT", what + ", which is not a key of segments");
    }

    private static String[] path(String at, Object v) {
        String s = v == null ? "" : String.valueOf(v).trim();
        String[] steps = s.split("\\.", -1);
        if (s.isEmpty() || steps.length > DeriveSpec.MAX_PATH_DEPTH)
            throw err("BAD-PATH", at + " path is empty or deeper than " + DeriveSpec.MAX_PATH_DEPTH);
        for (String st : steps)
            if (!st.matches("[A-Za-z_][A-Za-z0-9_-]*"))
                throw err("BAD-PATH", at + " path steps must be identifiers separated by dots");
        return steps;
    }

    private static int limit(Object v) {
        if (v == null) return DEFAULT_MAX_CHILD_ROWS;
        int n;
        try {
            n = Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            n = 0;
        }
        if (n <= 0 || n > CEILING_MAX_CHILD_ROWS)
            throw err("LIMIT", "max_child_rows_per_record must be between 1 and " + CEILING_MAX_CHILD_ROWS);
        return n;
    }

    private static IllegalArgumentException err(String code, String msg) {
        return new IllegalArgumentException("ASN1-CHILD-" + code + ": " + msg);
    }
}
