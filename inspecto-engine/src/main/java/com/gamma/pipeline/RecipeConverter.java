package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.gamma.util.Values.putIfPresent;

/**
 * <b>The read-side converter (ELT amendment Phase 2 S4, §6 step 1):</b> projects a decoded canonical
 * {@code *_pipeline.toon} map into the linear recipe shape ({@code name/trigger/steps}). The inverse of
 * {@link RecipeCompiler} for the verbs it speaks; keys the recipe does not model ({@code dirs.errors},
 * {@code dirs.status_dir}, …) are deliberately NOT projected — the round trip preserves them by
 * compiling <b>over the original</b> ({@link RecipeCompiler#compile(Map, Map, boolean)}, lower's
 * ownership rule), never by widening the recipe vocabulary. The housekeeping keys DO project since
 * the Phase-4 Guarantees fold: {@code collector.duplicate} / {@code gap_detection} /
 * {@code duplicate_check}+{@code dirs.markers} / {@code dirs.quarantine} /
 * {@code retention_days} appear under {@code guarantees:} (§2.4).
 *
 * <p>Parity contract (the Phase-2 gate): for every fixture,
 * {@code compile(toRecipe(cfg), cfg, false)} equals {@code cfg} (modulo the always-written
 * {@code active} default). Lenient compile is the converter's mode — a projection must not delete
 * the sections whose owning verb the recipe cannot speak yet.
 */
@PublicApi(since = "4.0.0")
public final class RecipeConverter {

    private RecipeConverter() {}

    /** Collector keys that ride recipe syntax rather than passing verbatim: {@code connection} rides
     *  the ref spelling; {@code gap_detection} and {@code duplicate} are Guarantees (Phase 4 fold).
     *  The dataset-entry pair ({@code connector: dataset} + {@code dataset}, S3c-2) collapses back to
     *  the authored {@code dataset: datasets/<id>} ref spelling — conditionally, in the collect block
     *  itself, so any other connector's keys keep the verbatim pass-through. */
    private static final Set<String> COLLECT_SPECIAL = Set.of("connection", "gap_detection", "duplicate");

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

        List<Object> steps = new ArrayList<>();

        // ── collect ──
        Map<String, Object> collect = new LinkedHashMap<>();
        if (collector.get("connection") != null)
            collect.put("connection", "connections/" + collector.get("connection"));
        boolean datasetEntry = "dataset".equals(collector.get("connector")) && collector.get("dataset") != null;
        if (datasetEntry) collect.put("dataset", "datasets/" + collector.get("dataset"));
        for (Map.Entry<String, Object> e : collector.entrySet())
            if (!COLLECT_SPECIAL.contains(e.getKey())
                    && !(datasetEntry && ("connector".equals(e.getKey()) || "dataset".equals(e.getKey()))))
                collect.put(e.getKey(), e.getValue());
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

        // ── sink(s) ── built BEFORE the chain, because a route step's branches carry the
        // destinations. The output:/dirs shorthand is the first destination (it carries backup/temp +
        // the sink-owned write tuning, which plural entries never do); further sinks: entries follow.
        Map<String, Object> sink = new LinkedHashMap<>();
        putIfPresent(sink, "format", output.get("format"));
        putIfPresent(sink, "compression", output.get("compression"));
        putIfPresent(sink, "ducklake", output.get("ducklake"));
        putIfPresent(sink, "database", dirs.get("database"));
        putIfPresent(sink, "backup", dirs.get("backup"));
        putIfPresent(sink, "temp", dirs.get("temp"));
        // sink-owned processing keys (write tuning): a present sink node owns them wholesale in the
        // lowering, so the projection must carry them or a round trip deletes them.
        for (String k : new String[]{"threads", "duckdb_threads"})
            putIfPresent(sink, k, processing.get(k));
        // Consignment grouping: the nested processing.batch map, owned wholesale by the sink in the
        // lowering since G3 (consignment-chain-plan.md) — carry it or a round trip deletes it. The
        // legacy flat spellings were write-only and heal into the nested shape, same as the editor.
        if (processing.get("batch") instanceof Map<?, ?> b) {
            sink.put("batch", b);
        } else {
            Map<String, Object> batch = new LinkedHashMap<>();
            putIfPresent(batch, "max_files", processing.get("batch_max_files"));
            putIfPresent(batch, "max_bytes", processing.get("batch_max_bytes"));
            if (!batch.isEmpty()) sink.put("batch", batch);
        }

        List<Map<String, Object>> extraSinks = new ArrayList<>();
        if (config.get("sinks") instanceof List<?> sinks) {
            for (Object s : sinks)
                if (s instanceof Map<?, ?> m && !String.valueOf(m.get("database")).equals(dirs.get("database"))) {
                    Map<String, Object> extra = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) extra.put(String.valueOf(e.getKey()), e.getValue());
                    extraSinks.add(extra);
                }
        }

        // ── the transform chain ──
        boolean routed = false;
        if (config.get("steps") instanceof List<?> authored) {
            // ⚠ An explicit `steps:` list IS the authored sequence, and it is the ONLY place that
            // sequence lives: the parser refuses `steps:` beside a singular transform block, so
            // processing.dedup / join / summarize and the top-level route: are all absent by
            // construction. Reading them instead of the list projected an EMPTY chain — every step
            // dropped, in silence — which is precisely the loss the multiplicity plan exists to
            // remove, relocated into the projection.
            if (where != null) steps.add(step("transform", new LinkedHashMap<>(Map.of("filter", where))));
            for (Object raw : authored) {
                if (!(raw instanceof Map<?, ?> m) || m.size() != 1) {
                    steps.add(raw);   // malformed: compile() refuses it by name rather than dropping it
                    continue;
                }
                Map.Entry<?, ?> e = m.entrySet().iterator().next();
                String kind = String.valueOf(e.getKey());
                Map<String, Object> cfg = mapOf(e.getValue());
                switch (kind) {
                    case PipelineConfig.Step.FILTER -> steps.add(step("transform", filterStep(cfg)));
                    case PipelineConfig.Step.JOIN -> steps.add(step("transform", joinStep(cfg)));
                    case PipelineConfig.Step.DEDUP -> steps.add(step("dedup", dedupStep(cfg)));
                    case PipelineConfig.Step.SUMMARIZE -> steps.add(step("summarize", summarizeStep(cfg)));
                    case PipelineConfig.Step.ROUTE -> {
                        steps.add(step("route", routeStep(cfg, sink, extraSinks, dirs)));
                        routed = true;
                    }
                    // an unmodelled kind travels VERBATIM so compile() names it in an UNSUPPORTED_STEP
                    // refusal — a projection must never quietly shorten the chain
                    default -> steps.add(step(kind, cfg));
                }
            }
        } else {
            // ── the legacy singular blocks, in PipelineLift's constant order ──
            // (join first — enrich the row set, then filter; D-4's one-verb join)
            if (processing.get("join") instanceof Map<?, ?> jn)
                steps.add(step("transform", joinStep(mapOf(jn))));
            if (where != null) steps.add(step("transform", new LinkedHashMap<>(Map.of("filter", where))));
            // record-grain dedup: between the transform and the sink, where the engine applies its QUALIFY
            if (processing.get("dedup") instanceof Map<?, ?> dd)
                steps.add(step("dedup", dedupStep(mapOf(dd))));
            // group-by rollup: compile-only, ELT amendment Phase 3 S1
            if (processing.get("summarize") instanceof Map<?, ?> sm)
                steps.add(step("summarize", summarizeStep(mapOf(sm))));
        }

        // ── route / sink ── With a route: block, every destination lives INSIDE its branch instead
        // — the trunk ends at route (§2.6).
        if (!routed) {
            if (config.get("route") instanceof Map<?, ?> routeBlock) {
                steps.add(step("route", routeStep(routeBlock, sink, extraSinks, dirs)));
            } else {
                steps.add(step("sink", sink));
                for (Map<String, Object> extra : extraSinks) steps.add(step("sink", extra));
            }
        }

        recipe.put("steps", steps);

        // ── guarantees ── (the Phase-4 fold, §2.4: housekeeping projected under its declared names;
        // the compiler overlays these back onto the same homes, so the round trip is exact)
        Map<String, Object> guarantees = new LinkedHashMap<>();
        if (collector.get("duplicate") instanceof Map<?, ?> dup) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : dup.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
            guarantees.put("file_dedup", copy);
        }
        if (collector.get("gap_detection") instanceof Map<?, ?> gw) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : gw.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
            guarantees.put("gap_watch", copy);
        }
        if (processing.get("duplicate_check") instanceof Map<?, ?> dc
                && Boolean.TRUE.equals(dc.get("enabled"))) {
            Map<String, Object> markers = new LinkedHashMap<>();
            putIfPresent(markers, "dir", dirs.get("markers"));
            for (Map.Entry<?, ?> e : dc.entrySet())
                if (!"enabled".equals(e.getKey())) markers.put(String.valueOf(e.getKey()), e.getValue());
            guarantees.put("markers", markers);
        }
        putIfPresent(guarantees, "quarantine", dirs.get("quarantine"));
        putIfPresent(guarantees, "retention", processing.get("retention_days"));
        if (!guarantees.isEmpty()) recipe.put("guarantees", guarantees);

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

    // ── per-kind step configs ───────────────────────────────────────────
    // One builder per chain kind, shared by BOTH spellings: a singular block and an authored `steps:`
    // entry hold the same keys (PipelineEditable.stepConfig keeps the two one vocabulary), so one
    // projection serves both and they cannot drift.

    /** {@code filter: {where: X}} → {@code transform: {filter: X}}. Only {@code where} has a recipe
     *  spelling; any other key travels so {@code compile} refuses it by name instead of dropping it. */
    private static Map<String, Object> filterStep(Map<String, Object> cfg) {
        Map<String, Object> filter = new LinkedHashMap<>();
        putIfPresent(filter, "filter", cfg.get("where"));
        for (Map.Entry<String, Object> e : cfg.entrySet())
            if (!"where".equals(e.getKey())) filter.put(e.getKey(), e.getValue());
        return filter;
    }

    /** {@code join: {reference, on}} → {@code transform: {join: references/…, on}}. */
    private static Map<String, Object> joinStep(Map<String, Object> cfg) {
        Map<String, Object> join = new LinkedHashMap<>();
        putRef(join, "join", cfg.get("reference"), "reference/", "references/");
        putIfPresent(join, "on", cfg.get("on"));
        return join;
    }

    /** {@code dedup: {keys, order_by}} → the recipe's {@code key:} spelling. */
    private static Map<String, Object> dedupStep(Map<String, Object> cfg) {
        Map<String, Object> dedup = new LinkedHashMap<>();
        putIfPresent(dedup, "key", cfg.get("keys"));
        putIfPresent(dedup, "order_by", cfg.get("order_by"));
        return dedup;
    }

    /** {@code summarize: {group_by, measures}} → the recipe's step of the same name. */
    private static Map<String, Object> summarizeStep(Map<String, Object> cfg) {
        Map<String, Object> summarize = new LinkedHashMap<>();
        putIfPresent(summarize, "group_by", cfg.get("group_by"));
        putIfPresent(summarize, "measures", cfg.get("measures"));
        return summarize;
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
        return mapOf(raw.get(key));
    }

    /** A {@code String}-keyed shallow copy of a decoded map value ({@code {}} when it is not a map). */
    private static Map<String, Object> mapOf(Object v) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m)
            for (Map.Entry<?, ?> e : m.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
        return copy;
    }
}
