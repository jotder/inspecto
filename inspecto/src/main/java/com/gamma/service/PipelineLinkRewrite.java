package com.gamma.service;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.LinkCensus;
import com.gamma.pipeline.LinkGraph;
import com.gamma.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The one rewrite of the links that name a renamed Pipeline (LC-2): every census link with
 * {@code onRename: REWRITE} whose target is a Pipeline or its store. Used by the Pipeline rename route
 * ({@code PipelineRenameRoutes}) and by the Space lock's opt-in hand-rename rewrite (SE-2a, {@link SpaceLock}) — there
 * is no second rewrite path.
 */
public final class PipelineLinkRewrite {

    private static final Logger log = LoggerFactory.getLogger(PipelineLinkRewrite.class);

    private PipelineLinkRewrite() {}

    /** Sees a registry component before and after its rewrite; throws to stop it. */
    @FunctionalInterface
    public interface ComponentCheck {
        void check(String kind, Map<String, Object> before, Map<String, Object> after);
    }

    /** One file or registry component holding renamed links, with the links that name the old id. */
    public record Holder(String kind, Path file, String name, Set<LinkCensus.Link> links) {}

    /**
     * Rewrite (or, with {@code planned}, only list) every holder of a kind {@code kinds} accepts. Holders come
     * in graph order — Enrichments, Jobs, then Expectations, Decision Rules, Datasets, Alert Rules — and a
     * registry component is written through its store, a config file atomically in place. {@code componentCheck}
     * sees each registry component before and after its rewrite and may throw to stop it (the Decision Rule guard).
     * Best-effort per file. Returns the count of holders rewritten.
     */
    public static int rewrite(Path writeRoot, String oldId, String newId, Predicate<String> kinds, List<String> planned,
                              ComponentCheck componentCheck) {
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Map<String, ComponentRegistry.Component>> listed = new HashMap<>();
        int count = 0;
        for (Holder h : holders(writeRoot, oldId, kinds)) {
            if (!ComponentStore.WRITABLE_TYPES.contains(h.kind())) {
                try {
                    Map<String, Object> raw = ConfigLoader.filesystem().decode(h.file().toString());
                    Map<String, Object> out = rewritten(h, raw, oldId, newId);
                    if (out == raw) continue;   // changed on disk since the graph read it
                    if (planned != null) {
                        planned.add(h.kind() + " '" + h.file().getFileName() + "'");
                        continue;
                    }
                    // temp-file prefix as before: .enr- / .job-
                    AtomicFiles.write(h.file(), ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8),
                            "." + h.kind().substring(0, 3) + "-");
                    count++;
                } catch (Exception ex) {
                    log.warn("[PIPELINE-RENAME] skipping unreadable {} {}: {}", h.kind(), h.file(), ex.getMessage());
                }
                continue;
            }
            ComponentRegistry.Component c = listed.computeIfAbsent(h.kind(), k -> {
                Map<String, ComponentRegistry.Component> byName = new HashMap<>();
                for (ComponentRegistry.Component x : store.list(k)) byName.putIfAbsent(x.name(), x);
                return byName;
            }).get(h.name());
            if (c == null) continue;
            Map<String, Object> updated = rewritten(h, c.content(), oldId, newId);
            if (updated == c.content()) continue;
            componentCheck.check(h.kind(), c.content(), updated);
            if (planned != null) {
                planned.add(h.kind() + " '" + c.name() + "'");
                continue;
            }
            try {
                store.write(h.kind(), c.name(), updated);
                count++;
            } catch (IOException ex) {
                log.warn("[PIPELINE-RENAME] could not rewrite {} '{}': {}", h.kind(), c.name(), ex.getMessage());
            }
        }
        return count;
    }

    /**
     * The holders of a renamed link naming {@code oldId}, in graph order. An Expectation / Decision Rule
     * {@code target} counts only when it names a Pipeline ({@code targetType} {@code pipeline}, the default).
     */
    public static List<Holder> holders(Path writeRoot, String oldId, Predicate<String> kinds) {
        return holders(LinkGraph.of(writeRoot, ReservedConfigPaths::reserved), oldId, kinds);
    }

    /** {@link #holders(Path, String, Predicate)} over a graph already built. */
    public static List<Holder> holders(LinkGraph graph, String oldId, Predicate<String> kinds) {
        Set<LinkCensus.Link> renamed = Set.copyOf(LinkCensus.renamedWithPipeline());
        Map<List<Object>, Holder> out = new LinkedHashMap<>();
        for (LinkGraph.Edge e : graph.edges()) {
            if (!renamed.contains(e.link()) || !e.toKind().equals(e.link().toKind()) || !kinds.test(e.fromKind())
                    || !names(e.link(), e.value(), oldId)) continue;
            out.computeIfAbsent(List.of(e.fromKind(), String.valueOf(e.file()), e.fromName()),
                    k -> new Holder(e.fromKind(), e.file(), e.fromName(), new HashSet<>())).links().add(e.link());
        }
        return List.copyOf(out.values());
    }

    /**
     * Whether one link value names {@code oldId}, case-insensitively after trimming: a store reference by its
     * first path segment ({@code physicalRef}, as {@code DataSourceBundleResolver.datasetReadsStore} reads it),
     * an Alert Rule's {@code onPipeline} also with a space read as {@code _} ({@code AlertService}). A SQL text
     * never selects its holder — it follows the holder's {@code sourceName} ({@link #rewritten}).
     */
    static boolean names(LinkCensus.Link l, String value, String oldId) {
        // An Enrichment trigger is compared as written, as the rename always did (a padded value is left alone,
        // though the runtime and impact trim it — a separate defect, not LC-2's to change).
        String v = l.keyPath().equals("triggers.on_pipeline") ? value : value.trim();
        if (l.type() == LinkCensus.Type.SQL_RELATION) return false;
        if (l.storePath()) return !"null".equals(v) && l.named(v).equalsIgnoreCase(oldId);
        return v.equalsIgnoreCase(oldId)
                || l.fromKind().equals("alert-rule") && v.replace(' ', '_').equalsIgnoreCase(oldId);
    }

    /** One link value with {@code oldId} replaced, or {@code null} when it does not name it. A comma list
     *  ({@link LinkCensus.Type#ID_LIST}) keeps every other member and its spacing as written. */
    private static String renamedValue(LinkCensus.Link l, String value, String oldId, String newId) {
        if (l.type() == LinkCensus.Type.ID_LIST && value.contains(",")) {
            String[] parts = value.split(",", -1);
            boolean hit = false;
            for (int i = 0; i < parts.length; i++) {
                if (!names(l, parts[i], oldId)) continue;
                String p = parts[i];
                int lead = p.length() - p.stripLeading().length(), trail = p.length() - p.stripTrailing().length();
                parts[i] = p.substring(0, lead) + newId + p.substring(p.length() - trail);
                hit = true;
            }
            return hit ? String.join(",", parts) : null;
        }
        if (!names(l, value, oldId)) return null;
        String v = value.trim();
        int slash = v.indexOf('/');
        return l.storePath() && slash >= 0 ? newId + v.substring(slash) : newId;
    }

    /**
     * {@code doc} with the holder's links rewritten (a copy; {@code doc} itself when nothing changed). A Job's
     * keys are read inside its {@code job:} section when it has one ({@link LinkGraph#jobSection}).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> rewritten(Holder h, Map<String, Object> doc, String oldId, String newId) {
        Map<String, Object> out = doc;
        boolean nested = h.kind().equals("job") && doc.get("job") instanceof Map<?, ?>;
        for (LinkCensus.Link l : h.links()) {
            String path = (nested ? "job." : "") + l.keyPath();
            out = (Map<String, Object>) rewriteAt(out, path.split("\\."), 0, v -> renamedValue(l, v, oldId, newId));
        }
        // VIRTUAL-DATASET-SQL-RENAME-1: a virtual Dataset's SQL FROM names the store its sourceName names; rewrite
        // it via the DuckDB parse tree (unparseable SQL is left as is and keeps failing closed at read).
        Object sn = doc.get("sourceName");
        if (h.kind().equals("dataset") && doc.get("sql") != null && !Objects.equals(sn, out.get("sourceName")))
            out.put("sql", com.gamma.sql.SqlGuard.renameBaseTable(
                    String.valueOf(doc.get("sql")), String.valueOf(sn).trim(), newId));
        return out;
    }

    /**
     * {@code node} with {@code fn} applied to the scalar at {@code segs[i..]} (census key-path syntax: a map key, or
     * {@code *} for each value of a map). Copies only the maps on a changed path; returns {@code node} itself when
     * {@code fn} changed nothing ({@code fn} answers {@code null} for "not this value").
     */
    private static Object rewriteAt(Object node, String[] segs, int i, UnaryOperator<String> fn) {
        if (i == segs.length) {
            if (node == null || node instanceof Map<?, ?> || node instanceof List<?>) return node;
            String v = fn.apply(String.valueOf(node));
            return v == null ? node : v;
        }
        if (!(node instanceof Map<?, ?> map)) return node;
        Map<String, Object> copy = null;
        for (Map.Entry<?, ?> en : map.entrySet()) {
            String key = String.valueOf(en.getKey());
            if (!segs[i].equals("*") && !segs[i].equals(key)) continue;
            Object v = rewriteAt(en.getValue(), segs, i + 1, fn);
            if (v == en.getValue()) continue;
            if (copy == null) {
                copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> c : map.entrySet()) copy.put(String.valueOf(c.getKey()), c.getValue());
            }
            copy.put(key, v);
        }
        return copy == null ? node : copy;
    }
}
