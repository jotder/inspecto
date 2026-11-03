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
 * time). {@code from} and {@code to} must differ. The same entry point also refuses a {@code calculated} column whose name
 * is a declared column or is repeated ({@code DatasetRelation#requireFreeCalculatedNames}).
 */
final class DatasetEntryRules {

    static final String PRIMARY_ENTITY_TYPE = "primaryEntityType";
    static final String DEFAULT_LINK_MAPPING = "defaultLinkMapping";

    private static final Set<String> MAPPING_KEYS = Set.of("from", "to", "kind", "time", "amount", "details");

    private DatasetEntryRules() {}

    /** @throws IllegalArgumentException naming the first broken rule */
    static void validate(Map<String, Object> content) {
        // DATASET-CALC-NAME-COLLISION-1: a calculated column may not reuse a declared column's name, nor repeat another's
        com.gamma.query.DatasetRelation.requireFreeCalculatedNames(content);
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

    static final String ORIGIN_MAPPING = "mapping";
    static final String BASED_ON = "basedOn";

    /**
     * LA-DATA-PAGE-1 (plan §8): a <b>Mapping</b> is a Dataset entry ({@code origin: mapping}) that reads ANOTHER
     * Dataset's stored data its own way - its own Entity Types, link mapping and event date. Its {@code basedOn} must name
     * a stored Dataset of this Space (not itself, not a Mapping, not computed - Add data or Pipeline-fed since
     * LA-PIPELINE-MAPPINGS-1), and its {@code physicalRef} must be exactly that base's: a Mapping never points at data of
     * its own choosing. Who may read it: {@code ComponentAccess.baseVisible}. Run on every Dataset write with the registry.
     *
     * @throws IllegalArgumentException naming the broken rule
     */
    static void validateMapping(java.nio.file.Path writeRoot, String id, Map<String, Object> content) {
        Object basedOn = content.get(BASED_ON);
        boolean mapping = ORIGIN_MAPPING.equals(String.valueOf(content.get("origin")));
        if (!mapping) {
            if (basedOn != null) throw new IllegalArgumentException(BASED_ON + " is only for a Mapping (origin: mapping)");
            return;
        }
        if (!(basedOn instanceof String base) || base.isBlank())
            throw new IllegalArgumentException("a Mapping names the Dataset it reads: " + BASED_ON + " is required");
        if (base.equals(id)) throw new IllegalArgumentException("a Mapping cannot be based on itself");
        if (writeRoot == null) throw new IllegalArgumentException("a Mapping needs this Space's registry to check '" + base + "'");
        Map<String, Object> baseEntry = new com.gamma.pipeline.ComponentStore(writeRoot.resolve("registry")).get("dataset", base)
                .map(com.gamma.pipeline.ComponentRegistry.Component::content)
                .orElseThrow(() -> new IllegalArgumentException(BASED_ON + " names no Dataset '" + base + "'"));
        // LA-PIPELINE-MAPPINGS-1 (Q-1): any STORED, plain Dataset - added with Add data or kept up to date by a Pipeline -
        // never a Mapping, never one computed at read time (it has no stored rows a Mapping could point at)
        if (ORIGIN_MAPPING.equals(String.valueOf(baseEntry.get("origin"))))
            throw new IllegalArgumentException("a Mapping reads a stored Dataset; '" + base + "' is itself a Mapping");
        for (String k : List.of("sql", "view", "sourceName", "calculated"))
            if (baseEntry.get(k) != null)
                throw new IllegalArgumentException("a Mapping reads a stored Dataset; '" + base + "' is computed (" + k + ")");
        if (!(baseEntry.get("physicalRef") instanceof String ref) || ref.isBlank())
            throw new IllegalArgumentException("a Mapping reads a stored Dataset; '" + base + "' has no stored data (physicalRef)");
        if (!String.valueOf(baseEntry.get("physicalRef")).equals(String.valueOf(content.get("physicalRef"))))
            throw new IllegalArgumentException("a Mapping reads exactly the data of '" + base + "': physicalRef must be '"
                    + baseEntry.get("physicalRef") + "'");
        // nothing that would read other data, or compute columns the base does not have
        for (String k : List.of("sql", "view", "sourceName", "calculated"))
            if (content.get(k) != null) throw new IllegalArgumentException("a Mapping reads its base's data as stored: '" + k + "' is not allowed");
        Set<String> mine = columnNames(content), theirs = columnNames(baseEntry);
        if (mine != null && theirs != null)
            for (String c : mine)
                if (!theirs.contains(c))
                    throw new IllegalArgumentException("a Mapping names only columns of '" + base + "'; '" + c + "' is not one");
    }

    /** The Mappings of this Space based on {@code base}, by id (sorted); empty when there are none. */
    static List<String> mappingsOf(java.nio.file.Path writeRoot, String base) {
        List<String> out = new java.util.ArrayList<>();
        if (writeRoot == null || base == null) return out;
        com.gamma.pipeline.ComponentStore store = new com.gamma.pipeline.ComponentStore(writeRoot.resolve("registry"));
        for (com.gamma.pipeline.ComponentRegistry.Component c : store.list("dataset"))
            if (ORIGIN_MAPPING.equals(String.valueOf(c.content().get("origin"))) && base.equals(c.content().get(BASED_ON)))
                out.add(c.name());
        java.util.Collections.sort(out);
        return out;
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
