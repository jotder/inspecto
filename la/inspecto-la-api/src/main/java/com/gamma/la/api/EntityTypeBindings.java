package com.gamma.la.api;

import com.gamma.access.ComponentAccess;
import com.gamma.entitystore.EntityTypes;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexStore;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The type-to-binding resolver (Entity profile plan §4.1, P1): where each Entity Type appears. An Entity Type binds to every
 * registered Dataset column whose {@code columns[].classification} it claims (trimmed, case-insensitive - the rule
 * {@link InvRoutes#columnTypes} and {@code EntityMasking} already apply). A binding's {@code role} is {@code source} /
 * {@code target} when the column is a link end of a mapping the Space knows - a saved Link Analysis view
 * ({@code query.projection}, {@code query.projections[]}, {@code query.multi.edges[]}), a published index mapping or the
 * Dataset's own {@code defaultLinkMapping} (LA-ONBOARD-1 OB-8) - else
 * {@code attribute}. Its {@code timeColumn} is the Dataset's {@code eventDate} column, else the time column of an index mapping
 * over the Dataset, else none.
 *
 * <p>R3: only Datasets (and saved views) the caller may view are read; a binding on any other is not there for this caller.
 * No query runs here - the registry, the saved views and the index manifests are configuration reads.
 */
final class EntityTypeBindings {

    private EntityTypeBindings() {}

    /**
     * One Dataset column bound to an Entity Type. {@code party} is the column's optional {@code columns[].party} (trimmed,
     * lower-case, null when absent): which party of the row the column describes, so a relationship pairs a served IMSI
     * with the served MSISDN and not with the other party's number. See {@link #sameParty}.
     */
    record Binding(String type, String dataset, String column, String role, String timeColumn, String party) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataset", dataset);
            m.put("column", column);
            m.put("role", role);
            if (party != null) m.put("party", party);
            return m;
        }
    }

    /**
     * Whether two columns of ONE row describe the same party, so pairing them states something about one Entity. A column
     * with no {@code party} pairs with every column (a Dataset that names no parties behaves as before); two named parties
     * pair only when equal.
     */
    static boolean sameParty(Binding a, Binding b) {
        return a.party() == null || b.party() == null || a.party().equals(b.party());
    }

    private static String party(Map<?, ?> column) {
        Object p = column.get("party");
        String s = p == null ? "" : String.valueOf(p).trim().toLowerCase(java.util.Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    /** A link mapping over one Dataset: its two link ends, and its kind and time columns when it names them. */
    record LinkMapping(String dataset, String source, String target, String kind, String time) {}

    /** Everything the profile needs about one Space's bindings, resolved once per request. */
    record Resolved(List<EntityTypes.EntityType> types, Map<String, List<Binding>> byType, List<LinkMapping> mappings,
                    Map<String, Map<String, Object>> datasets) {

        List<Binding> of(String typeId) {
            return byType.getOrDefault(typeId, List.of());
        }

        Optional<EntityTypes.EntityType> type(String id) {
            return types.stream().filter(t -> t.id().equals(id)).findFirst();
        }

        /** The Entity Type a column of {@code dataset} is bound to, or null. */
        EntityTypes.EntityType typeOf(String dataset, String column) {
            for (var e : byType.entrySet())
                for (Binding b : e.getValue())
                    if (b.dataset().equals(dataset) && b.column().equalsIgnoreCase(column)) return type(e.getKey()).orElse(null);
            return null;
        }
    }

    static Resolved resolve(HttpExchange ex, Path writeRoot, List<EntityTypes.EntityType> types) {
        DatasetProvider provider = DatasetProviders.require();
        Map<String, Map<String, Object>> datasets = new LinkedHashMap<>();
        for (DatasetProvider.Entry e : provider.datasets(writeRoot))
            if (ComponentAccess.canView(ex, e.content())) datasets.put(e.name(), e.content());

        List<LinkMapping> mappings = new ArrayList<>();
        for (DatasetProvider.Entry v : provider.linkAnalysisViews(writeRoot))
            if (ComponentAccess.canView(ex, v.content())) viewMappings(v.content(), mappings);
        for (String ds : datasets.keySet()) indexMappings(writeRoot, ds, mappings);
        for (var d : datasets.entrySet()) defaultMapping(d.getKey(), d.getValue(), mappings);
        mappings.removeIf(m -> !datasets.containsKey(m.dataset()));

        Map<String, List<Binding>> byType = new LinkedHashMap<>();
        for (EntityTypes.EntityType t : types) byType.put(t.id(), new ArrayList<>());
        for (var d : datasets.entrySet()) {
            if (!(d.getValue().get("columns") instanceof List<?> cols)) continue;
            for (Object o : cols) {
                if (!(o instanceof Map<?, ?> c) || c.get("name") == null || c.get("classification") == null) continue;
                String col = String.valueOf(c.get("name")), cls = String.valueOf(c.get("classification")).trim();
                types.stream().filter(t -> t.classifications().stream().anyMatch(x -> x.trim().equalsIgnoreCase(cls))).findFirst()
                        .ifPresent(t -> byType.get(t.id()).add(new Binding(t.id(), d.getKey(), col,
                                role(d.getKey(), col, mappings), timeColumn(d.getKey(), d.getValue(), mappings), party(c))));
            }
        }
        for (List<Binding> l : byType.values())
            l.sort(Comparator.comparing(Binding::dataset).thenComparing(Binding::column));
        return new Resolved(types, byType, mappings, datasets);
    }

    private static String role(String dataset, String column, List<LinkMapping> mappings) {
        boolean target = false;
        for (LinkMapping m : mappings) {
            if (!m.dataset().equals(dataset)) continue;
            if (column.equalsIgnoreCase(m.source())) return "source";
            if (column.equalsIgnoreCase(m.target())) target = true;
        }
        return target ? "target" : "attribute";
    }

    private static String timeColumn(String dataset, Map<String, Object> content, List<LinkMapping> mappings) {
        if (content.get("eventDate") instanceof String s && !s.isBlank()) return s.trim();
        for (LinkMapping m : mappings) if (m.dataset().equals(dataset) && m.time() != null) return m.time();
        return null;
    }

    /** The link ends a saved view names: {@code query.projection}, {@code query.projections[]} and {@code query.multi.edges[]}. */
    static void viewMappings(Map<String, Object> view, List<LinkMapping> out) {
        if (!(view.get("query") instanceof Map<?, ?> q)) return;
        List<Object> projections = new ArrayList<>();
        if (q.get("projection") != null) projections.add(q.get("projection"));
        if (q.get("projections") instanceof List<?> l) projections.addAll(l);
        for (Object p : projections)
            if (p instanceof Map<?, ?> m) add(out, m.get("datasetId"), m.get("sourceCol"), m.get("targetCol"), m.get("linkKindCol"));
        if (q.get("multi") instanceof Map<?, ?> multi && multi.get("edges") instanceof List<?> edges)
            for (Object e : edges)
                if (e instanceof Map<?, ?> m) add(out, m.get("dataset"), m.get("sourceColumn"), m.get("targetColumn"), null);
    }

    private static void add(List<LinkMapping> out, Object ds, Object src, Object dst, Object kind) {
        if (ds == null || src == null || dst == null) return;
        out.add(new LinkMapping(String.valueOf(ds), String.valueOf(src), String.valueOf(dst), kind == null ? null : String.valueOf(kind), null));
    }

    /**
     * LA-ONBOARD-1 OB-8 (F-9): the Dataset's own {@code defaultLinkMapping {from, to, kind?, time?}}, so its links are
     * assessed before anyone saves a view or builds an index. Skipped when a view or index already names the same two ends.
     */
    static void defaultMapping(String dataset, Map<String, Object> content, List<LinkMapping> out) {
        if (!(content.get("defaultLinkMapping") instanceof Map<?, ?> m) || !(m.get("from") instanceof String from)
                || !(m.get("to") instanceof String to) || from.isBlank() || to.isBlank()) return;
        for (LinkMapping x : out)
            if (x.dataset().equals(dataset) && x.source().equalsIgnoreCase(from.trim()) && x.target().equalsIgnoreCase(to.trim())) return;
        out.add(new LinkMapping(dataset, from.trim(), to.trim(), m.get("kind") instanceof String k && !k.isBlank() ? k.trim() : null,
                m.get("time") instanceof String t && !t.isBlank() ? t.trim() : null));
    }

    /** The mapping of every published index over {@code dataset} (an unreadable manifest is no mapping). */
    private static void indexMappings(Path writeRoot, String dataset, List<LinkMapping> out) {
        Path root = IndexRoutes.indexRoot(writeRoot);
        List<String> hashes;
        try {
            hashes = IndexStore.mappingHashes(root, dataset);
        } catch (IllegalArgumentException cannotNameADirectory) {
            return;
        }
        for (String hash : hashes) {
            Optional<Path> current = new IndexStore(root, dataset, hash).current();
            if (current.isEmpty()) continue;
            try {
                IndexManifest m = IndexManifest.read(current.get());
                out.add(new LinkMapping(dataset, m.mapping().srcColumn(), m.mapping().dstColumn(), m.mapping().kindColumn(),
                        m.mapping().timeColumn()));
            } catch (java.io.IOException | IllegalArgumentException unreadable) {
                // an unreadable manifest is no mapping
            }
        }
    }
}
