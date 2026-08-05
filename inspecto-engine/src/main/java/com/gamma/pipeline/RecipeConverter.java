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

        // ── transform ──
        if (where != null) steps.add(step("transform", new LinkedHashMap<>(Map.of("filter", where))));

        // ── sink(s) ── First step = the output:/dirs shorthand (it carries backup/temp, which the plural
        // entries never do). A plural sinks: block adds one step per FURTHER destination — the shorthand
        // is "consistent with the first destination" by the lowering contract, so re-lowering rebuilds
        // the same sinks: list from the distinct databases.
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
        steps.add(step("sink", sink));
        if (config.get("sinks") instanceof List<?> sinks) {
            for (Object s : sinks)
                if (s instanceof Map<?, ?> m && !String.valueOf(m.get("database")).equals(dirs.get("database"))) {
                    Map<String, Object> extra = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) extra.put(String.valueOf(e.getKey()), e.getValue());
                    steps.add(step("sink", extra));
                }
        }

        recipe.put("steps", steps);
        return recipe;
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
