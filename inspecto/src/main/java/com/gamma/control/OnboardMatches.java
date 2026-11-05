package com.gamma.control;

import com.gamma.access.ComponentAccess;
import com.gamma.entitystore.EntityTypes;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetRelation;
import com.gamma.pipeline.ViewStore;
import com.gamma.util.SqlIdent;
import com.gamma.util.DuckDbUtil;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * **Shared columns** of an Add data draft (ONBOARDING-FIRST-RUN-1 B5, slice C6a; console UC-4, H9): which existing Datasets
 * share a column with the new file, and how much of each matches - "`store_id` matches 98% of `sales.store_id`" - so Add
 * data can offer *Use as a lookup*. A pair is the same column name (ignoring case), or a file column proposed as the
 * Entity Type that a Dataset column holds (its {@code classification} resolved to the Space's Entity Type: one the type
 * claims, or its id). The share is the part of that Dataset column's distinct keys (over its first {@link #MAX_SCAN_ROWS}
 * rows) found among the file column's sampled values (the first {@link OnboardDraft#SAMPLE_ROWS} rows). At most
 * {@link #MAX_PAIRS} column pairs are counted per draft, so a draft stays quick.
 *
 * <p>Counts only: no value leaves. Only Datasets the person may read (R3), and never a column masked for them (a count
 * over a masked key would tell them which keys exist). Best effort: a Dataset that cannot be read is skipped, never an
 * error, so a match never stops a draft.
 */
final class OnboardMatches {

    /** At most this many Datasets are compared, and this many matches answered. */
    static final int MAX_DATASETS = 50;
    static final int MAX_MATCHES = 10;
    /** At most this many column pairs are counted per draft, and each over at most this many rows of its Dataset. */
    static final int MAX_PAIRS = 20;
    static final long MAX_SCAN_ROWS = 1_000_000;

    private OnboardMatches() {}

    /** The draft's {@code matches}: {@code [{column, dataset, datasetColumn, share, keys, found, by, entityType?, entityTypeLabel?, datasetKind?}]}, best first. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> find(HttpExchange ex, Path writeRoot, Path dataRoot, List<EntityShapes.Column> sampled,
                                          Map<String, Object> entityTypes, List<EntityTypes.EntityType> types) {
        Map<String, String> proposed = new LinkedHashMap<>();   // file column -> proposed Entity Type
        if (entityTypes.get("columns") instanceof List<?> rows)
            for (Object r : rows)
                if (r instanceof Map<?, ?> m && m.get("entityType") instanceof Map<?, ?> et && et.get("value") instanceof String v)
                    proposed.put(String.valueOf(m.get("column")), v);

        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        ViewStore views = new ViewStore(writeRoot.resolve("views"));
        boolean reveal = BiResultMasking.mayReveal(ex);
        List<Map<String, Object>> out = new ArrayList<>();
        List<ComponentRegistry.Component> datasets;
        try {
            datasets = store.list("dataset");
        } catch (Exception e) {
            return out;
        }
        int compared = 0, counted = 0;
        try {
            DuckDbUtil.loadDriver();
        } catch (ClassNotFoundException e) {
            return out;
        }
        try (Connection conn = DuckDbUtil.openInMemory(null, List.of(dataRoot), null)) {
            for (ComponentRegistry.Component d : datasets) {
                if (compared >= MAX_DATASETS || counted >= MAX_PAIRS) break;
                Map<String, Object> content = d.content();
                if (!ComponentAccess.canView(ex, content) || !(content.get("columns") instanceof List<?> cols)) continue;
                List<String[]> pairs = new ArrayList<>();   // {file column, dataset column, by, its Entity Type id or null}
                for (Object c : cols) {
                    if (!(c instanceof Map<?, ?> cm) || !(cm.get("name") instanceof String dc)) continue;
                    String type = typeOf(cm.get("classification") instanceof String s ? s : null, types);
                    for (EntityShapes.Column f : sampled) {
                        if (f.name().equalsIgnoreCase(dc)) pairs.add(new String[]{f.name(), dc, "name", type});
                        else if (type != null && type.equals(proposed.get(f.name())))
                            pairs.add(new String[]{f.name(), dc, "entityType", type});
                    }
                }
                if (pairs.isEmpty()) continue;
                compared++;
                String relation;
                BiResultMasking masking;
                try {
                    relation = DatasetRelation.relationSql(content, dataRoot, views);
                    masking = BiResultMasking.of(writeRoot, store, views, d.name(), content, reveal);
                } catch (RuntimeException unreadable) {
                    continue;
                }
                for (String[] p : pairs) {
                    if (masking.hidesFromCaller(p[1])) continue;
                    if (counted++ >= MAX_PAIRS) break;
                    long[] kf = share(conn, relation, p[1], values(sampled, p[0]));
                    if (kf == null || kf[0] == 0 || kf[1] == 0) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("column", p[0]);
                    m.put("dataset", d.name());
                    m.put("datasetColumn", p[1]);
                    m.put("share", Math.round(1000.0 * kf[1] / kf[0]) / 1000.0);
                    m.put("keys", kf[0]);
                    m.put("found", kf[1]);
                    m.put("by", p[2]);
                    if (p[3] != null) {
                        m.put("entityType", p[3]);
                        for (EntityTypes.EntityType t : types) if (t.id().equals(p[3])) m.put("entityTypeLabel", t.label());
                    }
                    if (content.get("kind") instanceof String k) m.put("datasetKind", k);
                    out.add(m);
                }
            }
        } catch (SQLException e) {
            return out;
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> -((Number) m.get("share")).doubleValue()));
        return out.size() > MAX_MATCHES ? new ArrayList<>(out.subList(0, MAX_MATCHES)) : out;
    }

    /** The Space's Entity Type a column {@code classification} names: one that claims it, else one whose id it is; null = none. */
    static String typeOf(String classification, List<EntityTypes.EntityType> types) {
        if (classification == null || classification.isBlank()) return null;
        EntityTypes.EntityType claimed = EntityShapes.typeClaiming(classification.trim(), types);
        if (claimed != null) return claimed.id();
        for (EntityTypes.EntityType t : types) if (t.id().equalsIgnoreCase(classification.trim())) return t.id();
        return null;
    }

    private static Set<String> values(List<EntityShapes.Column> sampled, String column) {
        Set<String> v = new LinkedHashSet<>();
        for (EntityShapes.Column c : sampled)
            if (c.name().equals(column)) for (String s : c.values()) if (s != null && !s.isBlank()) v.add(s.trim());
        return v;
    }

    /** {distinct keys of the Dataset column, how many of them are among {@code fileValues}}; null when it cannot be read. */
    private static long[] share(Connection conn, String relation, String column, Set<String> fileValues) {
        if (fileValues.isEmpty()) return null;
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TEMP TABLE onboard_match_keys (v VARCHAR)");
            try (PreparedStatement ins = conn.prepareStatement("INSERT INTO onboard_match_keys VALUES (?)")) {
                for (String v : fileValues) {
                    ins.setString(1, v);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            String k = "TRIM(CAST(" + SqlIdent.q(column) + " AS VARCHAR))";
            try (ResultSet rs = st.executeQuery("SELECT count(DISTINCT k), count(DISTINCT k) FILTER (WHERE k IN (SELECT v FROM "
                    + "onboard_match_keys)) FROM (SELECT " + k + " AS k FROM (" + relation + ") AS onboard_rel LIMIT " + MAX_SCAN_ROWS
                    + ") WHERE k IS NOT NULL AND k <> ''")) {
                return rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : null;
            }
        } catch (SQLException unreadable) {
            return null;
        }
    }
}
