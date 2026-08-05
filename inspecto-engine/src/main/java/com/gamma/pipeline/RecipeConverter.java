package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The read-side converter (ELT amendment Phase 2 S4, §6 step 1):</b> projects a decoded canonical
 * {@code *_pipeline.toon} map into the linear recipe shape ({@code name/trigger/steps}). The inverse of
 * {@link RecipeCompiler} for the verbs it speaks; keys the recipe does not model ({@code dirs.errors},
 * {@code duplicate_check}, {@code gap_detection}, …) are deliberately NOT projected — the round trip
 * preserves them by compiling <b>over the original</b> ({@link RecipeCompiler#compile(Map, Map, boolean)},
 * lower's ownership rule), never by widening the recipe vocabulary.
 *
 * <p>Parity contract (the Phase-2 gate): for every fixture,
 * {@code compile(toRecipe(cfg), cfg, false)} equals {@code cfg} (modulo the always-written
 * {@code active} default). Lenient compile is the converter's mode — a projection must not delete the
 * sections whose owning verb the recipe cannot speak yet (file-dedup markers, gap watch → Phase 4).
 */
@PublicApi(since = "5.1.0")
public final class RecipeConverter {

    private RecipeConverter() {}

    /** Collector keys that ride recipe syntax rather than passing verbatim. */
    private static final Set<String> COLLECT_SPECIAL = Set.of("connection", "gap_detection");

    /** Project {@code config} (a decoded canonical pipeline map) into the recipe shape. */
    public static Map<String, Object> toRecipe(Map<String, Object> config) {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", config.get("name"));
        if (config.get("active") instanceof Boolean b) recipe.put("active", b);
        if (config.get("trigger") != null) recipe.put("trigger", config.get("trigger"));

        Map<String, Object> collector = section(config, config.containsKey("source")
                && !config.containsKey("collector") ? "source" : "collector");
        Map<String, Object> dirs = section(config, "dirs");
        Map<String, Object> processing = section(config, "processing");
        Map<String, Object> parsing = section(config, "parsing");
        Map<String, Object> output = section(config, "output");

        List<Map<String, Object>> steps = new ArrayList<>();

        // ── collect ──
        Map<String, Object> collect = new LinkedHashMap<>();
        if (collector.get("connection") != null)
            collect.put("connection", "connections/" + collector.get("connection"));
        for (Map.Entry<String, Object> e : collector.entrySet())
            if (!COLLECT_SPECIAL.contains(e.getKey())) collect.put(e.getKey(), e.getValue());
        putIfPresent(collect, "poll", dirs.get("poll"));
        putIfPresent(collect, "files", processing.get("file_pattern"));
        steps.add(step("collect", collect));

        // ── parse ── (grammar ref pluralised; parser-owned processing keys + parsing block verbatim)
        Map<String, Object> parse = new LinkedHashMap<>();
        if (parsing.get("grammar") instanceof String g)
            parse.put("grammar", g.startsWith("grammar/") ? "grammars/" + g.substring("grammar/".length()) : g);
        for (String k : new String[]{"csv_settings", "schemas", "segments", "ingester", "ingester_config"})
            putIfPresent(parse, k, processing.get(k));
        for (Map.Entry<String, Object> e : parsing.entrySet())
            if (!"grammar".equals(e.getKey())) parse.put(e.getKey(), e.getValue());
        // the post-parse row predicate projects as its own transform step, not a csv_settings key
        Object where = null;
        if (parse.get("csv_settings") instanceof Map<?, ?> csv && csv.get("where") != null) {
            Map<String, Object> stripped = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : csv.entrySet())
                if (!"where".equals(e.getKey())) stripped.put(String.valueOf(e.getKey()), e.getValue());
            where = csv.get("where");
            if (stripped.isEmpty()) parse.remove("csv_settings");
            else parse.put("csv_settings", stripped);
        }
        steps.add(step("parse", parse));

        // ── map ── (only the id/path-addressed single-schema shape projects; schemas[]/segments stay on parse)
        Map<String, Object> map = new LinkedHashMap<>();
        putRef(map, "schema", processing.get("schema_file"), "schema/", "schemas/");
        putRef(map, "mapping", processing.get("mapping_file"), "mapping/", "mappings/");
        if (!map.isEmpty()) steps.add(step("map", map));

        // ── transform ── (join first — enrich the row set, then filter; D-4's one-verb join)
        if (processing.get("join") instanceof Map<?, ?> jn) {
            Map<String, Object> join = new LinkedHashMap<>();
            putRef(join, "join", jn.get("reference"), "reference/", "references/");
            putIfPresent(join, "on", jn.get("on"));
            steps.add(step("transform", join));
        }
        if (where != null) steps.add(step("transform", new LinkedHashMap<>(Map.of("filter", where))));

        // ── dedup ── (record-grain: processing.dedup — between the transform and the sink, where the
        // engine applies its QUALIFY)
        if (processing.get("dedup") instanceof Map<?, ?> dd) {
            Map<String, Object> dedup = new LinkedHashMap<>();
            putIfPresent(dedup, "key", dd.get("keys"));
            putIfPresent(dedup, "order_by", dd.get("order_by"));
            steps.add(step("dedup", dedup));
        }

        // ── summarize ── (group-by rollup: processing.summarize — compile-only, ELT amendment Phase 3 S1)
        if (processing.get("summarize") instanceof Map<?, ?> sm) {
            Map<String, Object> summarize = new LinkedHashMap<>();
            putIfPresent(summarize, "group_by", sm.get("group_by"));
            putIfPresent(summarize, "measures", sm.get("measures"));
            steps.add(step("summarize", summarize));
        }

        // ── sink(s) / route ── The output:/dirs shorthand is the first destination (it carries
        // backup/temp + the sink-owned write tuning, which plural entries never do); further sinks:
        // entries follow. With a route: block, every destination lives INSIDE its branch instead —
        // the trunk ends at route (§2.6).
        Map<String, Object> sink = new LinkedHashMap<>();
        putIfPresent(sink, "format", output.get("format"));
        putIfPresent(sink, "compression", output.get("compression"));
        putIfPresent(sink, "ducklake", output.get("ducklake"));
        putIfPresent(sink, "database", dirs.get("database"));
        putIfPresent(sink, "backup", dirs.get("backup"));
        putIfPresent(sink, "temp", dirs.get("temp"));
        // sink-owned processing keys (write tuning): a present sink node owns them wholesale in the
        // lowering, so the projection must carry them or a round trip deletes them.
        for (String k : new String[]{"threads", "duckdb_threads", "batch_max_files", "batch_max_bytes"})
            putIfPresent(sink, k, processing.get(k));

        List<Map<String, Object>> extraSinks = new ArrayList<>();
        if (config.get("sinks") instanceof List<?> sinks) {
            for (Object s : sinks)
                if (s instanceof Map<?, ?> m && !String.valueOf(m.get("database")).equals(dirs.get("database"))) {
                    Map<String, Object> extra = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) extra.put(String.valueOf(e.getKey()), e.getValue());
                    extraSinks.add(extra);
                }
        }

        if (config.get("route") instanceof Map<?, ?> routeBlock) {
            steps.add(step("route", routeStep(routeBlock, sink, extraSinks, dirs)));
        } else {
            steps.add(step("sink", sink));
            for (Map<String, Object> extra : extraSinks) steps.add(step("sink", extra));
        }

        recipe.put("steps", steps);
        return recipe;
    }

    /**
     * The recipe {@code route:} step for a flat {@code route:} block: named branches with {@code when}/
     * {@code default} and one sink step each — the branch whose {@code database} is the shorthand
     * destination gets the full shorthand sink config; others get their {@code sinks:} entry.
     */
    private static Map<String, Object> routeStep(Map<?, ?> routeBlock, Map<String, Object> shorthandSink,
                                                 List<Map<String, Object>> extraSinks,
                                                 Map<String, Object> dirs) {
        Map<String, Object> route = new LinkedHashMap<>();
        putIfPresent(route, "mode", routeBlock.get("mode"));
        Object defaultKey = routeBlock.get("default");
        Map<String, Object> branches = new LinkedHashMap<>();
        if (routeBlock.get("branches") instanceof List<?> list) {
            for (Object b : list) {
                if (!(b instanceof Map<?, ?> m) || m.get("key") == null) continue;
                String key = String.valueOf(m.get("key"));
                Map<String, Object> branch = new LinkedHashMap<>();
                putIfPresent(branch, "when", m.get("where"));
                if (key.equals(defaultKey)) branch.put("default", true);
                Object db = m.get("database");
                Map<String, Object> branchSink = null;
                if (db != null && db.equals(dirs.get("database"))) branchSink = shorthandSink;
                else if (db != null)
                    for (Map<String, Object> extra : extraSinks)
                        if (db.equals(extra.get("database"))) branchSink = extra;
                branch.put("steps", List.of(step("sink",
                        branchSink != null ? branchSink : new LinkedHashMap<>())));
                branches.put(key, branch);
            }
        }
        route.put("branches", branches);
        return route;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static Map<String, Object> step(String verb, Map<String, Object> cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(verb, cfg);
        return m;
    }

    /** A registry singular ref pluralises for the recipe surface; a plain path travels verbatim. */
    private static void putRef(Map<String, Object> m, String key, Object v, String singular, String plural) {
        if (v == null) return;
        String s = v.toString();
        m.put(key, s.startsWith(singular) ? plural + s.substring(singular.length()) : s);
    }

    private static Map<String, Object> section(Map<String, Object> raw, String key) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (raw.get(key) instanceof Map<?, ?> m)
            for (Map.Entry<?, ?> e : m.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
        return copy;
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object v) {
        if (v != null) m.put(key, v);
    }
}
