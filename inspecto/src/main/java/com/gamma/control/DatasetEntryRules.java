package com.gamma.control;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The shape rules of the two Link Analysis keys a Dataset registry entry may carry ({@code LA-ONBOARD-1} OB-8):
 * {@code primaryEntityType} (the Entity Type id its graph leads with) and {@code defaultLinkMapping}
 * ({@code {from, to, kind?, primaryTime?, primaryMeasure?, details?[]}}: the link every start draws without asking again; entity
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
    /** What one row is (ENTITY-TYPE-CATALOG-1 P2): a record type id of the Space's packs. */
    static final String RECORD_TYPE = "recordType";

    /**
     * LA-COLUMN-ROLES-1 P1: {@code primaryTime} / {@code primaryMeasure} name the Time that dates each link and the Measure
     * that weighs it; {@code time} / {@code amount} are their old names, still read for entries written before (one or the
     * other, never two different columns).
     */
    static final String PRIMARY_TIME = "primaryTime", PRIMARY_MEASURE = "primaryMeasure";
    private static final Set<String> MAPPING_KEYS = Set.of("from", "to", "kind", "time", "amount", PRIMARY_TIME, PRIMARY_MEASURE,
            "details");

    private DatasetEntryRules() {}

    /** @throws IllegalArgumentException naming the first broken rule */
    static void validate(Map<String, Object> content) {
        // DATASET-CALC-NAME-COLLISION-1: a calculated column may not reuse a declared column's name, nor repeat another's
        com.gamma.query.DatasetRelation.requireFreeCalculatedNames(content);
        columnRoles(content);
        Object primary = content.get(PRIMARY_ENTITY_TYPE);
        if (primary != null && (!(primary instanceof String s) || s.isBlank()))
            throw new IllegalArgumentException(PRIMARY_ENTITY_TYPE + " must be an Entity Type id (a non-blank string)");
        Object recordType = content.get(RECORD_TYPE);
        if (recordType != null && (!(recordType instanceof String s) || s.isBlank()))
            throw new IllegalArgumentException(RECORD_TYPE + " must be a record type id (a non-blank string)");
        Object raw = content.get(DEFAULT_LINK_MAPPING);
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> m))
            throw new IllegalArgumentException(DEFAULT_LINK_MAPPING
                    + " must be an object {from, to, kind?, primaryTime?, primaryMeasure?, details?}");
        for (Object k : m.keySet())
            if (!MAPPING_KEYS.contains(String.valueOf(k)))
                throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + " has an unknown key '" + k + "' (allowed: " + MAPPING_KEYS + ")");
        Set<String> columns = columnNames(content);
        String from = column(m, "from", true, columns);
        String to = column(m, "to", true, columns);
        if (from.equalsIgnoreCase(to))
            throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + ".from and .to must be two different columns");
        for (String k : List.of("kind", "time", "amount", PRIMARY_TIME, PRIMARY_MEASURE)) column(m, k, false, columns);
        String time = primary(m, PRIMARY_TIME, "time"), measure = primary(m, PRIMARY_MEASURE, "amount");
        Map<String, Map<?, ?>> byName = columnsByName(content);
        if (time != null) requireRole(byName, time, "temporal", PRIMARY_TIME, "a Time");
        if (measure != null) {
            requireRole(byName, measure, "measure", PRIMARY_MEASURE, "a Measure");
            Map<?, ?> c = byName.get(measure.toLowerCase(Locale.ROOT));
            if (c != null && !numeric(c.get("type")))
                throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + "." + PRIMARY_MEASURE + " names '" + measure + "', which holds "
                        + c.get("type") + " values, not numbers");
        }
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

    /** A column's role (LA-COLUMN-ROLES-1); the UI says Entity · Time · Measure · Category · Detail. */
    static final List<String> ROLES = List.of("entity", "temporal", "measure", "dimension", "detail");
    private static final int UNIT_MAX = 32;

    /**
     * {@code columns[].role} is one of {@link #ROLES} or absent (an absent role reads from the rest of the entry, so an
     * entry written before roles still loads); an {@code entity} column names what it holds ({@code classification});
     * {@code unit} is a short text on a {@code measure} column only.
     */
    private static void columnRoles(Map<String, Object> content) {
        if (!(content.get("columns") instanceof List<?> cols)) return;
        for (Object o : cols) {
            if (!(o instanceof Map<?, ?> c)) continue;
            Object name = c.get("name"), unit = c.get("unit");
            // matched without case, as DatasetRelation.temporalColumn reads it: the block is hand-editable TOON too
            Object role = c.get("role") instanceof String r ? r.trim().toLowerCase(Locale.ROOT) : c.get("role");
            if (role != null && !(role instanceof String r && ROLES.contains(r)))
                throw new IllegalArgumentException("column '" + name + "' has role '" + role + "' (allowed: " + ROLES + ")");
            if ("entity".equals(role) && !(c.get("classification") instanceof String k && !k.isBlank()))
                throw new IllegalArgumentException("column '" + name + "' is an Entity column but holds no Entity Type (classification)");
            if (c.get(CARRIED) != null && !(c.get(CARRIED) instanceof Boolean))
                throw new IllegalArgumentException("column '" + name + "' has carried '" + c.get(CARRIED) + "' (true or false)");
            if ("measure".equals(role) && !numeric(c.get("type")))
                throw new IllegalArgumentException("column '" + name + "' is a measure but holds " + c.get("type") + " values, not numbers");
            if (unit == null) continue;
            if (!"measure".equals(role))
                throw new IllegalArgumentException("column '" + name + "' has a unit, which only a measure column has");
            if (!(unit instanceof String u) || u.isBlank() || u.length() > UNIT_MAX)
                throw new IllegalArgumentException("column '" + name + "' has a unit that is not a short text (1-" + UNIT_MAX + " characters)");
        }
    }

    /**
     * The primary a link mapping names under {@code key}, else under its old name; {@code null} when neither. Two
     * different columns under the two names are refused: an entry says which column dates (or weighs) its links once.
     */
    static String primary(Map<?, ?> m, String key, String oldKey) {
        String now = m.get(key) instanceof String s && !s.isBlank() ? s.trim() : null;
        String old = m.get(oldKey) instanceof String s && !s.isBlank() ? s.trim() : null;
        if (now != null && old != null && !now.equalsIgnoreCase(old))
            throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + " names " + key + " '" + now + "' and " + oldKey + " '" + old
                    + "' (its old name): state " + key + " only");
        return now != null ? now : old;
    }

    /** A primary's column, when it states a role, has the role the primary needs (an entry without roles is read as before). */
    private static void requireRole(Map<String, Map<?, ?>> byName, String column, String role, String key, String what) {
        Map<?, ?> c = byName.get(column.toLowerCase(Locale.ROOT));
        Object r = c == null ? null : c.get("role");
        if (r instanceof String s && !s.isBlank() && !role.equalsIgnoreCase(s.trim()))
            throw new IllegalArgumentException(DEFAULT_LINK_MAPPING + "." + key + " names '" + column + "', which is not " + what
                    + " column (its role is " + s.trim().toLowerCase(Locale.ROOT) + ")");
    }

    /** LA-COLUMN-ROLES-1 P2: {@code columns[].carried: false} keeps a Time, Measure or Category column off the links. */
    static final String CARRIED = "carried";
    /** The roles whose columns ride on every link and in its index (plan Q-5: opt-out, not opt-in). */
    static final Set<String> CARRIED_ROLES = Set.of("temporal", "measure", "dimension");
    /** At most this many carried columns: a link index build's own bound ({@code IndexRoutes.MAX_ATTR_COLS}). */
    static final int MAX_CARRIED = 50;

    /**
     * The columns that ride on every link of the entry (LA-COLUMN-ROLES-1 P2): each Time, Measure and Category column,
     * in column order, unless it says {@code carried: false}, and never a link end; the first {@link #MAX_CARRIED} only, so
     * the index it builds can be rebuilt through {@code POST /inv/index/builds}. Empty for an entry without roles.
     * These become a link index's {@code attributeColumns} and a link read's {@code attrCols}.
     */
    static List<String> carriedColumns(Map<String, Object> content) {
        List<String> out = new java.util.ArrayList<>();
        if (!(content.get("columns") instanceof List<?> cols)) return out;
        Set<String> ends = new HashSet<>();
        if (content.get(DEFAULT_LINK_MAPPING) instanceof Map<?, ?> m)
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

    /** Column types that hold numbers: the registry's {@code number} and the engine's own spellings. */
    private static final Set<String> NUMERIC = Set.of("number", "numeric", "integer", "int", "bigint", "smallint", "tinyint",
            "hugeint", "ubigint", "uinteger", "usmallint", "utinyint", "double", "float", "real", "decimal", "long");

    /** A column type that holds numbers; an absent type is not checked (a Dataset whose columns are read at query time). */
    static boolean numeric(Object type) {
        if (type == null || String.valueOf(type).isBlank()) return true;
        String t = String.valueOf(type).trim().toLowerCase(Locale.ROOT);
        int paren = t.indexOf('(');
        return NUMERIC.contains(paren < 0 ? t : t.substring(0, paren).trim());
    }

    /** The entry's columns by lower-cased name; empty when it lists none. */
    private static Map<String, Map<?, ?>> columnsByName(Map<String, Object> content) {
        Map<String, Map<?, ?>> out = new java.util.HashMap<>();
        if (content.get("columns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && c.get("name") != null) out.put(String.valueOf(c.get("name")).trim().toLowerCase(Locale.ROOT), c);
        return out;
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
