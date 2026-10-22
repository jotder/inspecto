package com.gamma.control;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The shape rules of the two Link Analysis keys a Dataset registry entry may carry ({@code LA-ONBOARD-1} OB-8):
 * {@code primaryEntityType} (the Entity Type id its graph leads with) and {@code defaultLinkMapping}
 * ({@code {from, to, kind?, time?, amount?, details?[]}}: the link every start draws without asking again; entity
 * profiles assess links from it when no saved view or index names one). Run by {@code ComponentRoutes.validateKind}
 * on every Dataset write, so the generic component routes and Add data refuse the same malformed entry (422).
 *
 * <p>A column a mapping names must be one of the entry's {@code columns[].name} when the entry lists columns; an
 * entry without a column list is not checked against one (a Dataset over a store whose columns are read at query
 * time). {@code from} and {@code to} must differ.
 */
final class DatasetEntryRules {

    static final String PRIMARY_ENTITY_TYPE = "primaryEntityType";
    static final String DEFAULT_LINK_MAPPING = "defaultLinkMapping";

    private static final Set<String> MAPPING_KEYS = Set.of("from", "to", "kind", "time", "amount", "details");

    private DatasetEntryRules() {}

    /** @throws IllegalArgumentException naming the first broken rule */
    static void validate(Map<String, Object> content) {
        Object primary = content.get(PRIMARY_ENTITY_TYPE);
        if (primary != null && (!(primary instanceof String s) || s.isBlank()))
            throw new IllegalArgumentException(PRIMARY_ENTITY_TYPE + " must be an Entity Type id (a non-blank string)");
        Object raw = content.get(DEFAULT_LINK_MAPPING);
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> m))
            throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + " must be an object {from, to, kind?, time?, amount?, details?}");
        for (Object k : m.keySet())
            if (!MAPPING_KEYS.contains(String.valueOf(k)))
                throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + " has an unknown key '" + k + "' (allowed: " + MAPPING_KEYS + ")");
        Set<String> columns = columnNames(content);
        String from = column(m, "from", true, columns);
        String to = column(m, "to", true, columns);
        if (from.equalsIgnoreCase(to))
            throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + ".from and .to must be two different columns");
        for (String k : List.of("kind", "time", "amount")) column(m, k, false, columns);
        Object details = m.get("details");
        if (details != null) {
            if (!(details instanceof List<?> l))
                throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + ".details must be a list of column names");
            for (Object d : l) {
                if (!(d instanceof String s) || s.isBlank())
                    throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + ".details must be a list of column names");
                requireColumn(s, "details", columns);
            }
        }
    }

    private static String column(Map<?, ?> m, String key, boolean required, Set<String> columns) {
        Object v = m.get(key);
        if (v == null) {
            if (required) throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + "." + key + " is required");
            return null;
        }
        if (!(v instanceof String s) || s.isBlank())
            throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + "." + key + " must be a column name");
        requireColumn(s, key, columns);
        return s.trim();
    }

    private static void requireColumn(String name, String key, Set<String> columns) {
        if (columns != null && !columns.contains(name.trim().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + "." + key + " names '" + name
                    + "', which is not one of the Dataset's columns");
    }

    /** The entry's column names, lower-cased; {@code null} when it lists none. */
    private static Set<String> columnNames(Map<String, Object> content) {
        if (!(content.get("columns") instanceof List<?> cols) || cols.isEmpty()) return null;
        Set<String> out = new HashSet<>();
        for (Object o : cols)
            if (o instanceof Map<?, ?> c && c.get("name") != null) out.add(String.valueOf(c.get("name")).trim().toLowerCase(Locale.ROOT));
        return out;
    }
}
