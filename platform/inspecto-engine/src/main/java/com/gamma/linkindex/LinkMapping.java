package com.gamma.linkindex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How a Dataset registry entry's {@code defaultLinkMapping} is read (LA-ONBOARD-1 OB-8, LA-COLUMN-ROLES-1). Shared by
 * the control plane, which validates the entry, and by Link Analysis's import follow-on, which builds the link index
 * from it ({@code BASIC-ONBOARD-1} P1) - so both read the same entry the same way.
 */
public final class LinkMapping {

    /** The entry key that holds the mapping. */
    public static final String KEY = "defaultLinkMapping";
    /** The Time that dates each link and the Measure that weighs it; {@code time} / {@code amount} are their old names. */
    public static final String PRIMARY_TIME = "primaryTime", PRIMARY_MEASURE = "primaryMeasure";
    /** LA-COLUMN-ROLES-1 P2: {@code columns[].carried: false} keeps a Time, Measure or Category column off the links. */
    public static final String CARRIED = "carried";
    /** The roles whose columns ride on every link and in its index (plan Q-5: opt-out, not opt-in). */
    public static final Set<String> CARRIED_ROLES = Set.of("temporal", "measure", "dimension");
    /** At most this many carried columns: a link index build's own bound ({@code IndexRoutes.MAX_ATTR_COLS}). */
    public static final int MAX_CARRIED = 50;

    private LinkMapping() {}

    /**
     * The primary a link mapping names under {@code key}, else under its old name; {@code null} when neither. Two
     * different columns under the two names are refused: an entry says which column dates (or weighs) its links once.
     */
    public static String primary(Map<?, ?> m, String key, String oldKey) {
        String now = m.get(key) instanceof String s && !s.isBlank() ? s.trim() : null;
        String old = m.get(oldKey) instanceof String s && !s.isBlank() ? s.trim() : null;
        if (now != null && old != null && !now.equalsIgnoreCase(old))
            throw new IllegalArgumentException(KEY + " names " + key + " '" + now + "' and " + oldKey + " '" + old
                    + "' (its old name): state " + key + " only");
        return now != null ? now : old;
    }

    /**
     * The columns that ride on every link of the entry (LA-COLUMN-ROLES-1 P2): each Time, Measure and Category column,
     * in column order, unless it says {@code carried: false}, and never a link end; the first {@link #MAX_CARRIED} only, so
     * the index it builds can be rebuilt through {@code POST /inv/index/builds}. Empty for an entry without roles.
     * These become a link index's {@code attributeColumns} and a link read's {@code attrCols}.
     */
    public static List<String> carriedColumns(Map<String, Object> content) {
        List<String> out = new ArrayList<>();
        if (!(content.get("columns") instanceof List<?> cols)) return out;
        Set<String> ends = new HashSet<>();
        if (content.get(KEY) instanceof Map<?, ?> m)
            for (String k : List.of("from", "to"))
                if (m.get(k) instanceof String s) ends.add(s.trim().toLowerCase(Locale.ROOT));
        for (Object o : cols)
            if (o instanceof Map<?, ?> c && c.get("name") instanceof String n && !n.isBlank()
                    && c.get("role") instanceof String r && CARRIED_ROLES.contains(r.trim().toLowerCase(Locale.ROOT))
                    && !Boolean.FALSE.equals(c.get(CARRIED)) && !ends.contains(n.trim().toLowerCase(Locale.ROOT))
                    && out.size() < MAX_CARRIED)
                out.add(n.trim());
        return out;
    }
}
