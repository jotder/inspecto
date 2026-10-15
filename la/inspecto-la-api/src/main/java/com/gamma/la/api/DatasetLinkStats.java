package com.gamma.la.api;

import com.gamma.entitystore.EntityTypes;
import com.gamma.la.core.DatasetProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Dataset-query fallback of the Links section (plan D-EP4, {@code "from":"dataset"}): the links come from every link mapping
 * the Space knows (saved views, index mappings) over a bound Dataset where one end is a column bound to the type. Links are
 * folded to distinct {@code (a, b, kind)} as the projection folds them; an Entity's degree is its distinct neighbours over
 * every such mapping, self-links excluded. Every end is its own type's normalised key (an untyped end its value), so one
 * Entity in two mappings is one Entity. Bounded by {@code entity_profile.timeout_s} like every profile read.
 */
final class DatasetLinkStats implements LinkStatsSource {

    private static final String L = EntityProfiles.LINKS;
    private static final String FOLDED = "(SELECT DISTINCT a, b, kind, a_typed, b_typed FROM " + L + " WHERE a <> '' AND b <> '' AND a <> b)";
    private static final String DEGREE = "(SELECT x, COUNT(DISTINCT o) AS d FROM (SELECT a AS x, b AS o FROM " + FOLDED
            + " AS e WHERE a_typed UNION ALL SELECT b AS x, a AS o FROM " + FOLDED + " AS e WHERE b_typed) AS en GROUP BY x)";

    /** The union over the type's link mappings, or null when the type is no link end of a readable mapping. */
    static String relation(EntityProfiles.Frame f) {
        List<String> parts = new ArrayList<>();
        for (EntityTypeBindings.LinkMapping m : f.resolved.mappings()) {
            String ds = m.dataset();
            if (!f.datasets().contains(ds) || f.relationSql.get(ds) == null) continue;
            String src = f.column(ds, m.source()), dst = f.column(ds, m.target());
            if (src == null || dst == null) continue;
            boolean at = bound(f, ds, src), bt = bound(f, ds, dst);
            if (!at && !bt) continue;
            String kind = m.kind() != null && f.column(ds, m.kind()) != null
                    ? "CAST(" + EntityProfiles.q(f.column(ds, m.kind())) + " AS VARCHAR)" : "CAST(NULL AS VARCHAR)";
            parts.add("SELECT " + end(f, ds, src) + " AS a, " + end(f, ds, dst) + " AS b, " + kind + " AS kind, " + at + " AS a_typed, " + bt
                    + " AS b_typed FROM (" + f.relationSql.get(ds) + ") AS __r WHERE " + EntityProfiles.q(src) + " IS NOT NULL AND "
                    + EntityProfiles.q(dst) + " IS NOT NULL");
        }
        return parts.isEmpty() ? null : String.join(" UNION ALL ", parts);
    }

    private static boolean bound(EntityProfiles.Frame f, String ds, String col) {
        for (EntityTypeBindings.Binding b : f.usable) if (b.dataset().equals(ds) && b.column().equalsIgnoreCase(col)) return true;
        return false;
    }

    private static String end(EntityProfiles.Frame f, String ds, String col) {
        EntityTypes.EntityType t = f.resolved.typeOf(ds, col);
        String c = EntityProfiles.q(col);
        return t == null ? "CAST(" + c + " AS VARCHAR)" : EntityKeySql.key(t.normaliser(), c);
    }

    @Override
    public Map<String, Object> typeLinks(EntityProfiles.Frame f) throws Exception {
        String rel = relation(f);
        if (rel == null) return null;
        DatasetProvider.Result kinds = f.run(L, rel, "SELECT kind, COUNT(*) AS edges FROM " + FOLDED + " AS e WHERE a_typed OR b_typed GROUP BY kind "
                + "ORDER BY edges DESC, kind", f.lim.topN(), List.of());
        int hub = f.lim.hubThreshold();
        Map<String, Object> d = f.run(L, rel, "SELECT quantile_disc(d, 0.5) AS p50, quantile_disc(d, 0.9) AS p90, quantile_disc(d, 0.99) AS p99, "
                + "MAX(d) AS mx, COUNT(*) FILTER (WHERE d > " + hub + ") AS hubs, COUNT(*) FILTER (WHERE d = 1) AS single, COUNT(*) AS n FROM "
                + DEGREE + " AS g", 1, List.of()).rows().get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", "dataset");
        out.put("kinds", kinds(kinds, "edges"));
        Map<String, Object> degree = new LinkedHashMap<>();
        boolean any = ColumnProfile.num(d.get("n")) > 0;
        degree.put("p50", any ? ColumnProfile.num(d.get("p50")) : null);
        degree.put("p90", any ? ColumnProfile.num(d.get("p90")) : null);
        degree.put("p99", any ? ColumnProfile.num(d.get("p99")) : null);
        degree.put("max", any ? ColumnProfile.num(d.get("mx")) : null);
        out.put("degree", degree);
        out.put("entities", ColumnProfile.num(d.get("n")));
        out.put("hubThreshold", hub);
        out.put("hubs", ColumnProfile.num(d.get("hubs")));
        out.put("singleLink", ColumnProfile.num(d.get("single")));
        out.put("truncated", kinds.truncated());
        out.put("assessed", true);
        out.put("reason", null);
        return out;
    }

    @Override
    public Map<String, Object> entityLinks(EntityProfiles.Frame f, String key) throws Exception {
        String rel = relation(f);
        if (rel == null) return null;
        DatasetProvider.Result kinds = f.run(L, rel, "SELECT kind, COUNT(*) AS n FROM " + FOLDED + " AS e WHERE (a_typed AND a = ?) OR (b_typed AND b = ?) "
                + "GROUP BY kind ORDER BY n DESC, kind", f.lim.topN(), List.of(key, key));
        Map<String, Object> d = f.run(L, rel, "SELECT COALESCE(MAX(d) FILTER (WHERE x = ?), 0) AS deg, COUNT(*) AS n FROM " + DEGREE + " AS g",
                1, List.of(key)).rows().get(0);
        long degree = ColumnProfile.num(d.get("deg")), n = ColumnProfile.num(d.get("n"));
        Map<String, Object> rank = f.run(L, rel, "SELECT COUNT(*) FILTER (WHERE d < " + degree + ") AS lt, COUNT(*) FILTER (WHERE d = "
                + degree + ") AS eq FROM " + DEGREE + " AS g", 1, List.of()).rows().get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("degree", degree);
        out.put("kinds", kinds(kinds, "count"));
        // mid-rank: where this degree sits among the type's linked Entities (an Entity with no link has no place: null)
        out.put("percentile", n == 0 || degree == 0 ? null
                : Math.round(100.0 * (ColumnProfile.num(rank.get("lt")) + 0.5 * ColumnProfile.num(rank.get("eq"))) / n));
        out.put("from", "dataset");
        out.put("truncated", kinds.truncated());
        out.put("assessed", true);
        out.put("reason", null);
        return out;
    }

    private static List<Map<String, Object>> kinds(DatasetProvider.Result r, String countKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : r.rows()) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("kind", row.get("kind") == null ? null : String.valueOf(row.get("kind")));
            k.put(countKey, ColumnProfile.num(row.get(countKey.equals("edges") ? "edges" : "n")));
            out.add(k);
        }
        return out;
    }
}
