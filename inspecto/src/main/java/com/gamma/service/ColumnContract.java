package com.gamma.service;

import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.SchemaCompatibility;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.LinkGraph;
import com.gamma.util.MappingCsv;
import com.gamma.util.StructureCsv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * SE-3a — the column contract, report-only ({@code docs/okf/product/module/config/spec-column-contract.md}): the
 * column set a Schema publishes ({@code mapping.fields}, name and type), the classing of a change between two
 * sets, and the consumers a breaking change breaks — read from the link census column links (LC-5,
 * {@link LinkGraph#columnConsumers}). No second column model: a consumer is exactly a RESOLVED census column link
 * whose origin is the Schema file.
 *
 * <p>Nothing here refuses. Kill switch {@code -Dcolumn.contract=off} (default {@code report}).
 */
public final class ColumnContract {

    /** {@code off} disables the lock section and the save warning; anything else (default {@code report}) is on. */
    public static final String PROPERTY = "column.contract";

    /** The save warning's finding code. */
    public static final String WARN_COLUMN_BREAKS = "WARN_COLUMN_BREAKS";

    /**
     * SE-3b kill switch for the opt-in refusal only: {@code off} ignores every Space's {@code columns: refuse-breaking},
     * so a breaking Schema save warns as under SE-3a (the warning-only rollback); anything else (default) honours it.
     */
    public static final String REFUSAL_PROPERTY = "column.contract.refusal";

    private ColumnContract() {}

    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "report").trim());
    }

    /** One published column; {@code type} is upper-case, {@code ""} when the Schema does not say. */
    public record Col(String name, String type) {}

    /**
     * One change between a recorded and a current set. {@code to} is the new name of a rename; the types are the
     * before / after types of a type change (and the common type of a rename).
     */
    public record Change(String change, String column, String to, String typeBefore, String typeAfter) {
        public boolean breaking() {
            return BREAKING.contains(change);
        }
    }

    public static final String ADDED = "added", REMOVED = "removed", RENAMED = "renamed",
            TYPE_NARROWED = "type-narrowed", TYPE_WIDENED = "type-widened";
    private static final Set<String> BREAKING = Set.of(REMOVED, RENAMED, TYPE_NARROWED);

    // ── the published set ───────────────────────────────────────────────────────────────────

    /**
     * The column set a Schema document publishes, in declaration order, or {@code null} when it cannot be known
     * from the document: no {@code mapping.fields} list, a field without a name, or {@code mapping.rules} (a
     * rule-built mapping, as {@code ColumnSets} keeps unknown). A field's type is its own {@code type}, else, for
     * {@code fn: keep} (or no {@code fn}), the type of the {@code raw.fields} entry it reads.
     */
    public static List<Col> columns(Map<?, ?> schema) {
        if (schema == null || !(schema.get("mapping") instanceof Map<?, ?> mapping)) return null;
        if (mapping.get("rules") instanceof List<?> rules && !rules.isEmpty()) return null;
        if (!(mapping.get("fields") instanceof List<?> fields) || fields.isEmpty()) return null;
        Map<String, String> rawTypes = new HashMap<>();
        if (schema.get("raw") instanceof Map<?, ?> raw && raw.get("fields") instanceof List<?> rf)
            for (Object o : rf)
                if (o instanceof Map<?, ?> m && !blank(m.get("name")))
                    rawTypes.put(String.valueOf(m.get("name")).trim().toLowerCase(Locale.ROOT), type(m.get("type")));
        List<Col> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : fields) {
            if (!(o instanceof Map<?, ?> m) || blank(m.get("name"))) return null;
            String name = String.valueOf(m.get("name")).trim();
            if (!seen.add(name.toLowerCase(Locale.ROOT))) continue;
            String t = type(m.get("type"));
            if (t.isEmpty() && (blank(m.get("fn")) || "keep".equalsIgnoreCase(String.valueOf(m.get("fn")).trim()))
                    && !blank(m.get("from")))
                t = rawTypes.getOrDefault(String.valueOf(m.get("from")).trim().toLowerCase(Locale.ROOT), "");
            out.add(new Col(name, t));
        }
        return out;
    }

    /** The set a Schema file publishes as stored (its {@code _structure.csv} merged); {@code null} when unknown
     *  (unreadable, or a sibling {@code _mapping.csv}). */
    public static List<Col> columns(Path schemaFile) {
        if (Files.exists(MappingCsv.siblingFor(schemaFile))) return null;
        Map<String, Object> doc = stored(schemaFile);
        return doc == null ? null : columns(doc);
    }

    /** A stored Schema with its {@code _structure.csv} fields merged into {@code raw.fields}; {@code null} when unreadable. */
    public static Map<String, Object> stored(Path schemaFile) {
        if (schemaFile == null || !Files.isRegularFile(schemaFile)) return null;
        try {
            Map<String, Object> doc = ConfigLoader.filesystem().decode(schemaFile.toString());
            if (doc == null) return null;
            Path csv = StructureCsv.siblingFor(schemaFile);
            if (Files.exists(csv)) {
                Map<String, Object> raw = doc.get("raw") instanceof Map<?, ?> r ? new LinkedHashMap<>(castMap(r)) : new LinkedHashMap<>();
                raw.put("fields", StructureCsv.parse(Files.readString(csv, StandardCharsets.UTF_8), csv.toString()));
                doc = new LinkedHashMap<>(doc);
                doc.put("raw", raw);
            }
            return doc;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Every Schema file of a config root, config-relative path → file: the suffix-named ones and {@code registry/schemas/}. */
    public static Map<String, Path> schemaFiles(Path root) {
        Map<String, Path> out = new TreeMap<>();
        for (Path p : LinkGraph.configFiles(root, "_schema.toon", ReservedConfigPaths::reserved))
            out.put(rel(root, p), p);
        for (ComponentRegistry.Component c : ComponentRegistry.scan(root.resolve("registry")).ofType("schema"))
            if (c.path() != null && Files.isRegularFile(c.path())) {
                String rel = rel(root, c.path());
                if (!ReservedConfigPaths.reserved(rel)) out.putIfAbsent(rel, c.path());
            }
        return out;
    }

    // ── classing ────────────────────────────────────────────────────────────────────────────

    /**
     * The changes from {@code before} to {@code after}, removals / renames / type changes first, then additions.
     * Names match case-insensitively (DuckDB binds every consumer so). <b>Rename heuristic:</b> a removed column
     * and an added column at the <i>same position</i> in {@code mapping.fields} with the <i>same type</i> (both
     * unknown counts as the same) are one rename; each added column pairs at most once. A type change on a kept
     * column is {@code type-widened} when {@link SchemaCompatibility#isWidening} allows it (the BACKWARD lattice the
     * Schema save gate already uses: INTEGER → BIGINT/DOUBLE, BIGINT → DOUBLE, DATE → TIMESTAMP, anything →
     * VARCHAR), else {@code type-narrowed}. A column whose type is unknown on either side has no type change.
     */
    public static List<Change> classify(List<Col> before, List<Col> after) {
        Map<String, Integer> afterIdx = index(after), beforeIdx = index(before);
        Map<Integer, Col> addedAt = new LinkedHashMap<>();
        for (int j = 0; j < after.size(); j++)
            if (!beforeIdx.containsKey(key(after.get(j)))) addedAt.put(j, after.get(j));
        List<Change> out = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            Col b = before.get(i);
            Integer j = afterIdx.get(key(b));
            if (j == null) {
                Col a = addedAt.get(i);
                if (a != null && a.type().equalsIgnoreCase(b.type())) {
                    addedAt.remove(i);
                    out.add(new Change(RENAMED, b.name(), a.name(), b.type(), a.type()));
                } else {
                    out.add(new Change(REMOVED, b.name(), null, b.type(), null));
                }
                continue;
            }
            Col a = after.get(j);
            if (!b.type().isEmpty() && !a.type().isEmpty() && !b.type().equals(a.type()))
                out.add(new Change(SchemaCompatibility.isWidening(b.type(), a.type()) ? TYPE_WIDENED : TYPE_NARROWED,
                        b.name(), null, b.type(), a.type()));
        }
        for (Col a : addedAt.values()) out.add(new Change(ADDED, a.name(), null, null, a.type()));
        return out;
    }

    // ── consumers ───────────────────────────────────────────────────────────────────────────

    /** The consumers of one column of {@code schemaFile} in {@code graph}: its RESOLVED census column links. */
    public static List<Map<String, Object>> consumers(LinkGraph graph, Path root, Path schemaFile, String column) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LinkGraph.Edge e : graph.columnConsumers(schemaFile, column)) out.add(consumer(root, e));
        return out;
    }

    /** Column links the parser could not read whose value is {@code column} (the relation was not attributable). */
    public static List<Map<String, Object>> notAnalysed(LinkGraph graph, Path root, String column) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LinkGraph.Edge e : graph.columnEdges())
            if (e.status() == LinkGraph.Status.NOT_ANALYSED && e.value().trim().equalsIgnoreCase(column.trim()))
                out.add(consumer(root, e));
        return out;
    }

    static Map<String, Object> consumer(Path root, LinkGraph.Edge e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fromKind", e.fromKind());
        m.put("fromName", e.fromName());
        m.put("from", e.file() == null || root == null ? null : rel(root, e.file()));
        m.put("key", e.key());
        m.put("value", e.value());
        return m;
    }

    /** Whether the consumer still holds the same column link in {@code graph} (it was not edited or removed). */
    static boolean stillPresent(LinkGraph graph, Path root, Map<?, ?> c) {
        for (LinkGraph.Edge e : graph.columnEdges()) {
            if (!e.fromKind().equals(c.get("fromKind")) || !e.fromName().equals(c.get("fromName"))
                    || !e.key().equals(c.get("key"))) continue;
            String from = e.file() == null ? null : rel(root, e.file());
            if (java.util.Objects.equals(from, c.get("from"))
                    && e.value().trim().equalsIgnoreCase(String.valueOf(c.get("value")).trim())) return true;
        }
        return false;
    }

    // ── the lock section ────────────────────────────────────────────────────────────────────

    /** What one computation adds to the lock ({@code entries}, under {@code columns}) and to the report. */
    record Section(List<Map<String, Object>> entries, Map<String, Object> report) {}

    /**
     * The lock's {@code columns} entries and the report's {@code columnChanges} for one computation. Per Schema
     * whose set is known: its {@code columns}, the {@code consumers} of each column (lower-cased name → census
     * column links) and its {@code changes}. Changes are classed against the previous lock's recorded set; an
     * unchanged set carries its previous changes forward (consumers that were since fixed drop out); a Schema new
     * to the lock, or a previous lock with no {@code columns} at all, is a baseline with no changes.
     *
     * @param prevColumns    the previous lock's {@code columns}, or {@code null} when it has none
     * @param outsidePaths   paths the same computation marked as changed outside the server
     */
    static Section lockSection(Path root, LinkGraph graph, Object prevColumns, Set<String> outsidePaths, String nowIso,
                               int cap) {
        Map<String, Map<?, ?>> prevByPath = new HashMap<>();
        if (prevColumns instanceof List<?> l)
            for (Object o : l) if (o instanceof Map<?, ?> m && m.get("path") != null) prevByPath.put(String.valueOf(m.get("path")), m);
        List<Map<String, Object>> entries = new ArrayList<>(), items = new ArrayList<>();
        int unknown = 0, breaking = 0;
        for (Map.Entry<String, Path> s : schemaFiles(root).entrySet()) {
            List<Col> cur = columns(s.getValue());
            if (cur == null) {
                unknown++;
                continue;
            }
            Map<String, Object> consumers = new TreeMap<>();
            for (Col c : cur) {
                List<Map<String, Object>> used = consumers(graph, root, s.getValue(), c.name());
                if (!used.isEmpty()) consumers.put(c.name().toLowerCase(Locale.ROOT), used);
            }
            Map<?, ?> prev = prevByPath.get(s.getKey());
            List<Col> prevSet = prev == null ? null : fromJson(prev.get("columns"));
            List<Map<String, Object>> changes = new ArrayList<>();
            if (prevSet != null && prevSet.equals(cur)) {
                if (prev.get("changes") instanceof List<?> pc)
                    for (Object o : pc) if (o instanceof Map<?, ?> m) changes.add(refiltered(graph, root, m));
            } else if (prevSet != null) {
                Map<?, ?> prevConsumers = prev.get("consumers") instanceof Map<?, ?> pm ? pm : Map.of();
                for (Change c : classify(prevSet, cur)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("change", c.change());
                    m.put("column", c.column());
                    if (c.to() != null) m.put("to", c.to());
                    if (c.typeBefore() != null) m.put("typeBefore", c.typeBefore());
                    if (c.typeAfter() != null) m.put("typeAfter", c.typeAfter());
                    m.put("breaking", c.breaking());
                    m.put("at", nowIso);
                    m.put("outside", outsidePaths.contains(s.getKey()));
                    List<Object> broken = new ArrayList<>();
                    if (c.breaking() && prevConsumers.get(c.column().toLowerCase(Locale.ROOT)) instanceof List<?> was)
                        for (Object w : was) if (w instanceof Map<?, ?> wm && stillPresent(graph, root, wm)) broken.add(wm);
                    m.put("consumers", broken);
                    changes.add(m);
                }
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("path", s.getKey());
            e.put("columns", toJson(cur));
            e.put("consumers", consumers);
            e.put("changes", changes);
            entries.add(e);
            for (Map<String, Object> c : changes) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("schema", s.getKey());
                item.putAll(c);
                if (Boolean.TRUE.equals(c.get("breaking"))) {
                    breaking++;
                    item.put("notAnalysed", notAnalysed(graph, root, String.valueOf(c.get("column"))));
                }
                items.add(item);
            }
        }
        List<Map<String, Object>> unread = new ArrayList<>();
        for (LinkGraph.Edge e : graph.columnEdges()) if (e.status() == LinkGraph.Status.NOT_ANALYSED) unread.add(consumer(root, e));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "ready");
        r.put("baseline", prevColumns instanceof List<?> ? "lock" : "none");
        r.put("schemas", entries.size());
        r.put("unknownSchemas", unknown);
        r.put("total", items.size());
        r.put("breaking", breaking);
        r.put("items", items.size() > cap ? List.copyOf(items.subList(0, cap)) : List.copyOf(items));
        Map<String, Object> na = new LinkedHashMap<>();
        na.put("total", unread.size());
        na.put("items", unread.size() > cap ? List.copyOf(unread.subList(0, cap)) : List.copyOf(unread));
        r.put("notAnalysed", na);
        return new Section(entries, r);
    }

    /** A carried-forward change whose consumers are re-checked against the current graph. */
    private static Map<String, Object> refiltered(LinkGraph graph, Path root, Map<?, ?> change) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : change.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
        List<Object> kept = new ArrayList<>();
        if (change.get("consumers") instanceof List<?> l)
            for (Object o : l) if (o instanceof Map<?, ?> c && stillPresent(graph, root, c)) kept.add(c);
        m.put("consumers", kept);
        return m;
    }

    // ── the save warning ────────────────────────────────────────────────────────────────────

    /**
     * The WARNING findings a Schema save gets (D-SE4 step 1: warn only, refuse nothing): one per breaking change
     * between the stored and the saved document, naming the consumers it breaks. {@code graph} is the Space as
     * stored, before the write. Empty when the switch is off, either set is unknown, or nothing breaks.
     */
    public static List<com.gamma.config.spec.Finding> saveWarnings(Path writeRoot, Path target,
                                                                   Map<?, ?> stored, Map<?, ?> saved) {
        List<com.gamma.config.spec.Finding> out = new ArrayList<>();
        for (Break b : breaks(writeRoot, target, stored, saved)) out.add(b.warning());
        return out;
    }

    /**
     * One breaking change of a Schema save with what it breaks: the RESOLVED consumers of the column as the Schema
     * published it, and the column links the parser could not attribute (never counted as broken).
     */
    public record Break(Change change, List<Map<String, Object>> consumers, List<Map<String, Object>> notAnalysed) {
        /** The finding text: the change, the consumers it breaks, the count of SQL references not analysed. */
        public String message() {
            List<String> names = new ArrayList<>();
            for (Map<String, Object> u : consumers) names.add(u.get("fromKind") + " '" + u.get("fromName") + "' (" + u.get("key") + ")");
            return describe(change) + "; "
                    + (names.isEmpty() ? "no consumer in this Space reads it" : "breaks " + consumers.size() + " consumer(s): " + String.join(", ", names))
                    + (notAnalysed.isEmpty() ? "" : "; " + notAnalysed.size() + " SQL reference(s) to it were not analysed");
        }

        /** The SE-3a WARNING finding (the save went ahead). */
        public com.gamma.config.spec.Finding warning() {
            return new com.gamma.config.spec.Finding(com.gamma.config.spec.Severity.WARNING,
                    "mapping.fields[" + change.column() + "]", message(), WARN_COLUMN_BREAKS,
                    "update the consumers listed, or keep the column; the save went ahead (report-only)");
        }
    }

    /**
     * The breaking changes between the stored and the saved Schema document, each with its consumers; {@code graph}
     * is the Space as stored, before the write. Empty when the switch is off, there is no stored version, the mapping
     * is rule-built (inline or a sibling {@code _mapping.csv}), either set is unknown, or nothing breaks.
     */
    public static List<Break> breaks(Path writeRoot, Path target, Map<?, ?> stored, Map<?, ?> saved) {
        if (!enabled() || stored == null || Files.exists(MappingCsv.siblingFor(target))) return List.of();
        List<Col> before = columns(stored), after = columns(saved);
        if (before == null || after == null) return List.of();
        List<Change> breaking = classify(before, after).stream().filter(Change::breaking).toList();
        if (breaking.isEmpty()) return List.of();
        LinkGraph graph = LinkGraph.of(writeRoot, ReservedConfigPaths::reserved);
        List<Break> out = new ArrayList<>();
        for (Change c : breaking)
            out.add(new Break(c, consumers(graph, writeRoot, target, c.column()), notAnalysed(graph, writeRoot, c.column())));
        return out;
    }

    static String describe(Change c) {
        return switch (c.change()) {
            case RENAMED -> "column '" + c.column() + "' renamed to '" + c.to() + "'";
            case TYPE_NARROWED -> "column '" + c.column() + "' type changed " + c.typeBefore() + " → " + c.typeAfter();
            default -> "column '" + c.column() + "' " + c.change();
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    static List<Map<String, Object>> toJson(List<Col> cols) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Col c : cols) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("type", c.type());
            out.add(m);
        }
        return out;
    }

    static List<Col> fromJson(Object o) {
        if (!(o instanceof List<?> l)) return null;
        List<Col> out = new ArrayList<>();
        for (Object e : l)
            if (e instanceof Map<?, ?> m && m.get("name") != null)
                out.add(new Col(String.valueOf(m.get("name")), m.get("type") == null ? "" : String.valueOf(m.get("type"))));
        return out;
    }

    private static Map<String, Integer> index(List<Col> cols) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < cols.size(); i++) m.putIfAbsent(key(cols.get(i)), i);
        return m;
    }

    private static String key(Col c) {
        return c.name().toLowerCase(Locale.ROOT);
    }

    private static String type(Object t) {
        return blank(t) ? "" : String.valueOf(t).trim().toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    static String rel(Path root, Path p) {
        return root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static boolean blank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }
}
