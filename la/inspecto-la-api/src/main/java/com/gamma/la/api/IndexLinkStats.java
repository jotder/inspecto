package com.gamma.la.api;

import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The index-first source of the Links section (plan D-EP4, {@code "from":"index"}): the type's links come from the link statistics
 * a fresh index recorded at build ({@link IndexManifest#kindEdges()}, {@link IndexManifest#degreeHistogram()}), one Entity's from
 * that index's {@code out} table. The build folds links exactly as {@link DatasetLinkStats} does (normalised keys, distinct
 * {@code (a, b, kind)}, no self-links, degree = distinct neighbours), so the two sources agree on the same data.
 *
 * <p>It answers only when the index covers EVERYTHING the Dataset fallback would read: the type is a link end of exactly ONE
 * link mapping, both ends of it are bound to the type, and a published index over that Dataset has the same source, target and
 * kind columns, is not stale, and was keyed by this type and normaliser at both ends. Otherwise it returns null and
 * {@link DatasetLinkStats} answers. Edges with a NULL kind are not in {@code kindEdges}, so an index answer lists no NULL kind.
 */
final class IndexLinkStats implements LinkStatsSource {

    /** The fresh index that answers for the type, or null. */
    static IndexedRead.Selection select(EntityProfiles.Frame f) {
        Map<String, EntityTypeBindings.LinkMapping> touching = new LinkedHashMap<>();   // as DatasetLinkStats.relation walks them, deduplicated
        for (EntityTypeBindings.LinkMapping m : f.resolved.mappings()) {
            String ds = m.dataset();
            if (!f.datasets().contains(ds) || f.relationSql.get(ds) == null) continue;
            String src = f.column(ds, m.source()), dst = f.column(ds, m.target());
            if (src == null || dst == null || (!bound(f, ds, src) && !bound(f, ds, dst))) continue;
            String kind = m.kind() == null ? null : f.column(ds, m.kind());
            touching.putIfAbsent(String.join("\u0000", ds, lower(src), lower(dst), String.valueOf(lower(kind))),
                    new EntityTypeBindings.LinkMapping(ds, src, dst, kind, null));
        }
        if (touching.size() != 1) return null;
        EntityTypeBindings.LinkMapping m = touching.values().iterator().next();
        if (!bound(f, m.dataset(), m.source()) || !bound(f, m.dataset(), m.target())) return null;
        IndexedRead.Selection sel = IndexedRead.select(f.writeRoot, f.api.dataRoot(), f.relationSql.get(m.dataset()), m.dataset(),
                m.source(), m.target(), (im, utc) -> Objects.equals(lower(im.kindColumn()), lower(m.kind()))
                        ? IndexedRead.Fitted.ok(null) : IndexedRead.Fitted.no(IndexedRead.Reason.column_not_indexed));
        if (!sel.usable() || sel.stale()) return null;
        IndexManifest man = sel.manifest();
        IndexManifest.EndpointTypes et = man.endpointTypes();
        IndexManifest.EndpointType mine = new IndexManifest.EndpointType(f.type.id(), f.type.normaliser());
        if (et == null || !mine.equals(et.source()) || !mine.equals(et.target()) || man.degreeHistogram() == null || man.kindEdges() == null)
            return null;                                                     // built before the statistics, or keyed by another type
        return sel;
    }

    @Override
    public Map<String, Object> typeLinks(EntityProfiles.Frame f) {
        IndexedRead.Selection sel = select(f);
        if (sel == null) return null;
        IndexManifest man = sel.manifest();
        List<Map<String, Object>> kinds = new ArrayList<>();
        man.kindEdges().forEach((k, n) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("kind", k);
            e.put("edges", n);
            kinds.add(e);
        });
        int top = f.lim.topN(), hub = f.lim.hubThreshold();
        long entities = 0, hubs = 0;
        for (Map.Entry<Long, Long> e : man.degreeHistogram().entrySet()) {
            entities += e.getValue();
            if (e.getKey() > hub) hubs += e.getValue();                      // DatasetLinkStats: d > threshold
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", "index");
        out.put("kinds", kinds.size() > top ? kinds.subList(0, top) : kinds);
        Map<String, Object> degree = new LinkedHashMap<>();
        boolean any = entities > 0;
        degree.put("p50", any ? man.degree().p50() : null);
        degree.put("p90", any ? man.degree().p90() : null);
        degree.put("p99", any ? man.degree().p99() : null);
        degree.put("max", any ? man.degree().max() : null);
        out.put("degree", degree);
        out.put("entities", entities);
        out.put("hubThreshold", hub);
        out.put("hubs", hubs);
        out.put("singleLink", man.degreeHistogram().getOrDefault(1L, 0L));
        out.put("truncated", kinds.size() > top);
        out.put("indexVersion", sel.version());
        out.put("assessed", true);
        out.put("reason", null);
        return out;
    }

    @Override
    public Map<String, Object> entityLinks(EntityProfiles.Frame f, String key) throws Exception {
        IndexedRead.Selection sel = select(f);
        if (sel == null) return null;
        IndexManifest man = sel.manifest();
        String norm = f.type.normaliser();
        IndexReader.KeyLinks kl;
        try (IndexReader reader = IndexReader.borrow(sel.dir(), man, InvRoutes.traversalPolicy(f.writeRoot))) {
            kl = reader.keyLinks(EntityKeySql.key(norm, "src"), EntityKeySql.key(norm, "dst"), key);
        }
        List<Map<String, Object>> kinds = new ArrayList<>();
        kl.kinds().forEach((k, n) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("kind", k);
            e.put("count", n);
            kinds.add(e);
        });
        long n = 0, lt = 0, eq = 0, degree = kl.degree();
        for (Map.Entry<Long, Long> e : man.degreeHistogram().entrySet()) {
            n += e.getValue();
            if (e.getKey() < degree) lt += e.getValue();
            else if (e.getKey() == degree) eq += e.getValue();
        }
        int top = f.lim.topN();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("degree", degree);
        out.put("kinds", kinds.size() > top ? kinds.subList(0, top) : kinds);
        // mid-rank among the type's linked Entities, as DatasetLinkStats ranks it (no link = no place: null)
        out.put("percentile", n == 0 || degree == 0 ? null : Math.round(100.0 * (lt + 0.5 * eq) / n));
        out.put("from", "index");
        out.put("truncated", kinds.size() > top);
        out.put("indexVersion", sel.version());
        out.put("assessed", true);
        out.put("reason", null);
        return out;
    }

    private static boolean bound(EntityProfiles.Frame f, String ds, String col) {
        for (EntityTypeBindings.Binding b : f.usable) if (b.dataset().equals(ds) && b.column().equalsIgnoreCase(col)) return true;
        return false;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
