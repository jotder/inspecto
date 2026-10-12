package com.gamma.etl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** One explode: the list under the parent record and the child segment its elements become. */
    public record Child(String[] list, String segment) {
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
        Set<String> out = new java.util.LinkedHashSet<>();
        for (List<Child> l : byParent.values()) for (Child c : l) out.add(c.segment);
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
        Set<String> childKeys = new java.util.LinkedHashSet<>();
        for (Map.Entry<?, ?> pe : parents.entrySet()) {
            String parent = String.valueOf(pe.getKey());
            known(segmentKeys, parent, "children names parent segment '" + parent + "'");
            if (!(pe.getValue() instanceof Map<?, ?> lists) || lists.isEmpty())
                throw err("BAD-PATH", "children." + parent + " must be a non-empty map of list path to child segment key");
            List<Child> cs = new ArrayList<>();
            for (Map.Entry<?, ?> le : lists.entrySet()) {
                String at = "children." + parent + "." + le.getKey();
                String[] list = path(at, le.getKey());
                if (!(le.getValue() instanceof String child) || child.isBlank())
                    throw err("UNKNOWN-SEGMENT", at + " must name a child segment key");
                child = child.trim();
                known(segmentKeys, child, at + " names child segment '" + child + "'");
                if (child.equals(parent))
                    throw err("SELF", at + " makes segment '" + child + "' its own child");
                childKeys.add(child);
                cs.add(new Child(list, child));
            }
            out.put(parent, List.copyOf(cs));
        }
        for (String parent : out.keySet())
            if (childKeys.contains(parent))
                throw err("NESTED", "segment '" + parent + "' is both a parent and a child; explode is one level");
        return new ChildSpec(out, cap);
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
