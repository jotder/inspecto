package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The recipe compiler (ELT amendment Phase 2, §2.1/§4):</b> compiles the linear authoring shape —
 * {@code name / trigger / steps:[…]} where each step is one verb — onto the existing engine primitives,
 * by building a {@link PipelineGraph} (one node per Step, chained in list order) and handing it to
 * {@link PipelineEditable#lower}, so every existing refusal and completeness gate applies unchanged.
 * The result is the canonical {@code *_pipeline.toon} map; discovery stays suffix-based (grounding
 * finding 2: compile-at-authoring, the registry learns no second format).
 *
 * <p><b>Verb coverage:</b> {@code collect / parse / map / dedup / route / summarize / transform.filter
 * / sink} — every linear-chain verb except the Signal-bus unification's own. {@code map} folds into
 * the parser node (schema/mapping resolution is parser-owned in the flat config); {@code summarize} is
 * compile-only (Phase 3 S1 — {@code MaterializeTask} stays the runtime until a recipe-driven executor
 * lands, same posture as {@code route}'s arming gate). {@code transform.join} compiles per D-4
 * ({@code transform: {join: references/x, on: k}} → {@code processing.join}), compile-only too —
 * the join model executes post-commit via {@code EnrichmentEngine}, never in the linear ingest path
 * yet. Not yet compilable, refused with named codes rather than silently dropped:
 * {@code transform.derive} (Phase 3 S3+) and unknown {@code guarantees:} keys.
 *
 * <p><b>The Guarantees fold (Phase 4, §2.4):</b> the top-level {@code guarantees:} block compiles
 * onto the existing housekeeping homes, all of which are live executable config (no arming gate):
 * {@code file_dedup} → {@code collector.duplicate} ({@code fingerprint} is the recipe spelling of
 * mode {@code checksum}), {@code gap_watch} → {@code collector.gap_detection}, {@code markers} →
 * {@code processing.duplicate_check} + {@code dirs.markers}, {@code quarantine} →
 * {@code dirs.quarantine}, {@code retention} → {@code processing.retention_days}. The overlay is
 * applied AFTER {@link PipelineEditable#lower}, because lower's ownership rule clears these
 * sections when their owning node kind is absent from the compiled graph. {@code backup} is
 * deliberately NOT a guarantee key — the sink step already owns the backup dir
 * ({@code sink: {backup: …}}), and one concept gets one spelling.
 */
@PublicApi(since = "5.1.0")
public final class RecipeCompiler {

    private RecipeCompiler() {}

    // ── refusal codes (stable, rendered beside the offending step) ──────────────
    public static final String UNSUPPORTED_STEP = "UNSUPPORTED_STEP";
    public static final String MALFORMED_STEP = "MALFORMED_STEP";
    public static final String MAP_WITHOUT_PARSE = "MAP_WITHOUT_PARSE";
    public static final String GUARANTEES_NOT_LOWERABLE = "GUARANTEES_NOT_LOWERABLE";

    /**
     * Compile {@code recipe} to the canonical pipeline-config map. {@code active} defaults to
     * {@code true}; an active recipe compiles strictly (completeness enforced by
     * {@link PipelineEditable#lower}), an inactive draft leniently.
     *
     * @throws PipelineCompileException carrying every named refusal when the recipe cannot be
     *         represented — never a partial compile
     */
    public static Map<String, Object> compile(Map<String, Object> recipe) {
        boolean active = !(recipe.get("active") instanceof Boolean b) || b;
        return compile(recipe, Map.of(), active);
    }

    /**
     * Compile over {@code existing} (a decoded pipeline config): keys the recipe does not model —
     * {@code dirs.errors}, {@code duplicate_check}, {@code gap_detection}, … — are preserved by
     * {@link PipelineEditable#lower}'s ownership rule instead of dropped. This is the converter's
     * round-trip half ({@link RecipeConverter}); {@code strict} is the caller's completeness choice
     * (lenient preserves sections whose owning verb the recipe never speaks).
     */
    public static Map<String, Object> compile(Map<String, Object> recipe, Map<String, Object> existing,
                                              boolean strict) {
        List<PipelineCompileException.Refusal> refusals = new ArrayList<>();

        String name = str(recipe.get("name"));
        if (name == null || name.isBlank())
            refusals.add(new PipelineCompileException.Refusal(MALFORMED_STEP, null, "a recipe needs a name"));
        boolean active = !(recipe.get("active") instanceof Boolean b) || b;

        Map<String, Object> guarantees = recipe.get("guarantees") instanceof Map<?, ?> g
                ? configOf(g) : new LinkedHashMap<>();
        validateGuarantees(guarantees, refusals);

        List<PipelineNode> nodes = new ArrayList<>();
        List<PipelineNode> branchSinks = new ArrayList<>();
        List<PipelineEdge> routeEdges = new ArrayList<>();
        PipelineNode parser = null;
        Map<String, Object> mapStep = null;
        String mapStepId = null;
        boolean routeSeen = false;

        int i = 0;
        for (Object rawStep : steps(recipe, refusals)) {
            i++;
            if (!(rawStep instanceof Map<?, ?> m) || m.size() != 1) {
                refusals.add(new PipelineCompileException.Refusal(MALFORMED_STEP, "step-" + i,
                        "each step is a single-verb map, e.g. `- parse: {…}`"));
                continue;
            }
            Map.Entry<?, ?> e = m.entrySet().iterator().next();
            String verb = String.valueOf(e.getKey());
            Map<String, Object> cfg = configOf(e.getValue());
            String id = verb + "-" + i;

            if (routeSeen) {
                refusals.add(new PipelineCompileException.Refusal(MALFORMED_STEP, id,
                        "route ends the trunk (§2.6) — steps after it belong inside its branches"));
                continue;
            }
            switch (verb) {
                case "collect" -> nodes.add(collect(id, cfg, recipe.get("trigger")));
                case "parse" -> {
                    parser = parse(id, cfg);
                    nodes.add(parser);
                }
                case "map" -> {
                    mapStep = cfg;
                    mapStepId = id;
                }
                case "transform" -> transform(id, cfg, nodes, refusals);
                case "sink" -> nodes.add(sink(id, cfg));
                case "dedup" -> nodes.add(dedup(id, cfg, refusals));
                case "route" -> {
                    route(id, cfg, nodes, branchSinks, routeEdges, refusals);
                    routeSeen = true;
                }
                case "summarize" -> nodes.add(summarize(id, cfg, refusals));
                default -> refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_STEP, id,
                        "unknown step verb '" + verb + "'"));
            }
        }

        // map folds into the parser node: schema/mapping resolution is parser-owned in the flat config.
        if (mapStep != null) {
            if (parser == null) {
                refusals.add(new PipelineCompileException.Refusal(MAP_WITHOUT_PARSE, mapStepId,
                        "map needs a parse step to fold into — the flat config resolves schemas at the parser"));
            } else {
                Map<String, Object> pc = new LinkedHashMap<>(parser.config());
                putRef(pc, "schema_file", mapStep.get("schema"), "schemas/", "schema/");
                putRef(pc, "mapping_file", mapStep.get("mapping"), "mappings/", "mapping/");
                for (Map.Entry<String, Object> other : mapStep.entrySet())
                    if (!"schema".equals(other.getKey()) && !"mapping".equals(other.getKey()))
                        refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_STEP, mapStepId,
                                "map does not understand '" + other.getKey() + "' (only schema / mapping)"));
                PipelineNode folded = new PipelineNode(parser.id(), parser.type(), pc, parser.use());
                nodes.set(nodes.indexOf(parser), folded);
            }
        }
        if (!refusals.isEmpty()) throw new PipelineCompileException(refusals);

        List<PipelineEdge> edges = new ArrayList<>();
        for (int n = 1; n < nodes.size(); n++)
            edges.add(new PipelineEdge(nodes.get(n - 1).id(), "out", nodes.get(n).id()));
        nodes.addAll(branchSinks);
        edges.addAll(routeEdges);

        Map<String, Object> out =
                PipelineEditable.lower(new PipelineGraph(name, active, nodes, edges), existing, strict);
        applyGuarantees(out, guarantees);
        return out;
    }

    /** The guarantee keys this compiler folds (§2.4); anything else refuses loudly, never drops. */
    private static final java.util.Set<String> GUARANTEE_KEYS = java.util.Set.of(
            "file_dedup", "gap_watch", "markers", "quarantine", "retention");

    private static void validateGuarantees(Map<String, Object> g,
                                           List<PipelineCompileException.Refusal> refusals) {
        for (Map.Entry<String, Object> e : g.entrySet()) {
            String k = e.getKey();
            if ("backup".equals(k)) {
                refusals.add(new PipelineCompileException.Refusal(GUARANTEES_NOT_LOWERABLE, null,
                        "backup rides the sink step (sink: {backup: <dir>}), not guarantees:"));
            } else if (!GUARANTEE_KEYS.contains(k)) {
                refusals.add(new PipelineCompileException.Refusal(GUARANTEES_NOT_LOWERABLE, null,
                        "unknown guarantee '" + k + "' (only "
                                + "file_dedup / gap_watch / markers / quarantine / retention fold)"));
            } else if ("file_dedup".equals(k) && "marker".equals(String.valueOf(e.getValue()))) {
                refusals.add(new PipelineCompileException.Refusal(GUARANTEES_NOT_LOWERABLE, null,
                        "marker-file housekeeping is the markers: guarantee — "
                                + "file_dedup speaks path / metadata / fingerprint / etag"));
            } else if (("gap_watch".equals(k) || "markers".equals(k)) && !(e.getValue() instanceof Map)) {
                refusals.add(new PipelineCompileException.Refusal(GUARANTEES_NOT_LOWERABLE, null,
                        k + " is a map guarantee, e.g. " + ("markers".equals(k)
                                ? "markers: {dir: …, marker_extension: …}"
                                : "gap_watch: {enabled: true, sequence: …}")));
            }
        }
    }

    /**
     * Overlay the validated {@code guarantees:} keys onto their flat homes. Runs after
     * {@link PipelineEditable#lower} because lower's ownership rule clears these sections when the
     * owning node kind is absent from the compiled graph. Overlay-only: absent guarantee keys leave
     * the existing config's sections untouched (the converter round-trip relies on this).
     */
    private static void applyGuarantees(Map<String, Object> out, Map<String, Object> g) {
        if (g.isEmpty()) return;
        Object fd = g.get("file_dedup");
        if (fd != null) {
            Map<String, Object> dup = fd instanceof Map<?, ?> m ? configOf(m)
                    : new LinkedHashMap<>(Map.of("mode",
                            "fingerprint".equals(fd.toString()) ? "checksum" : fd.toString()));
            getOrNew(out, out.containsKey("source") && !out.containsKey("collector")
                    ? "source" : "collector").put("duplicate", dup);
        }
        if (g.get("gap_watch") instanceof Map<?, ?> gw)
            getOrNew(out, out.containsKey("source") && !out.containsKey("collector")
                    ? "source" : "collector").put("gap_detection", configOf(gw));
        if (g.get("markers") instanceof Map<?, ?> mk) {
            Map<String, Object> markers = configOf(mk);
            Object dir = markers.remove("dir");
            if (dir != null) getOrNew(out, "dirs").put("markers", dir);
            Map<String, Object> dc = new LinkedHashMap<>();
            dc.put("enabled", true);
            dc.putAll(markers);
            getOrNew(out, "processing").put("duplicate_check", dc);
        }
        if (g.get("quarantine") != null)
            getOrNew(out, "dirs").put("quarantine", g.get("quarantine"));
        if (g.get("retention") != null)
            getOrNew(out, "processing").put("retention_days", g.get("retention"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getOrNew(Map<String, Object> out, String key) {
        Object v = out.get(key);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        Map<String, Object> fresh = new LinkedHashMap<>();
        out.put(key, fresh);
        return fresh;
    }

    /** Processing keys the parser node owns in the flat config ({@code PipelineEditable.PARSER_OWNED});
     *  a parse step spelling one of these carries it on the node directly, not inside {@code parsing:}. */
    private static final java.util.Set<String> PARSE_PROCESSING_KEYS = java.util.Set.of(
            "csv_settings", "schema_file", "mapping_file", "schemas", "segments", "ingester", "ingester_config");

    // ── per-verb node builders ───────────────────────────────────────────────────

    /** {@code collect} → acquisition node; {@code connection:} rides {@code use:}, {@code files:} is
     *  the file pattern, and the recipe's top-level {@code trigger:} travels on this node (the entry
     *  Step carries the schedule — §2.7). */
    private static PipelineNode collect(String id, Map<String, Object> cfg, Object trigger) {
        Map<String, Object> c = new LinkedHashMap<>(cfg);
        String use = takeRef(c, "connection", "connections/", "connection/");
        Object files = c.remove("files");
        if (files != null) c.put("file_pattern", files);
        if (trigger != null) c.put("trigger", trigger);
        return new PipelineNode(id, BuiltinNodeType.ACQUISITION.type(), c, use);
    }

    /** {@code parse} → parser node; {@code grammar:} rides {@code use:}, parser-owned processing keys
     *  ({@code csv_settings}, {@code schemas}, {@code ingester}, …) land on the node directly, and every
     *  other key travels verbatim inside the {@code parsing:} block the parser node owns. */
    private static PipelineNode parse(String id, Map<String, Object> cfg) {
        Map<String, Object> c = new LinkedHashMap<>(cfg);
        String use = takeRef(c, "grammar", "grammars/", "grammar/");
        Map<String, Object> node = new LinkedHashMap<>();
        for (String k : PARSE_PROCESSING_KEYS) {
            Object v = c.remove(k);
            if (v != null) node.put(k, v);
        }
        if (!c.isEmpty()) node.put("parsing", c);
        return new PipelineNode(id, BuiltinNodeType.PARSER.type(), node, use);
    }

    /** {@code transform: {filter: <predicate>}} → transform.filter node ({@code csv_settings.where});
     *  {@code transform: {join: references/x, on: k}} → transform.join node ({@code processing.join},
     *  D-4's one-verb join — {@code on} rides the join, it is not a verb of its own). */
    private static void transform(String id, Map<String, Object> cfg, List<PipelineNode> nodes,
                                  List<PipelineCompileException.Refusal> refusals) {
        Map<String, Object> c = new LinkedHashMap<>(cfg);
        Object join = c.remove("join");
        Object on = c.remove("on");
        if (join != null) {
            Map<String, Object> node = new LinkedHashMap<>();
            String s = join.toString().trim();
            node.put("reference", s.startsWith("references/")
                    ? "reference/" + s.substring("references/".length()) : s);
            if (on != null) node.put("on", on instanceof List<?> l ? l : List.of(on.toString()));
            else refusals.add(new PipelineCompileException.Refusal(MALFORMED_STEP, id,
                    "transform.join needs on: — the join-key column(s)"));
            // a step carrying both join and filter compiles to two chained nodes — distinct ids
            nodes.add(PipelineNode.of(c.containsKey("filter") ? id + "-join" : id,
                    BuiltinNodeType.TRANSFORM_JOIN.type(), node));
        } else if (on != null) {
            refusals.add(new PipelineCompileException.Refusal(MALFORMED_STEP, id,
                    "on: only makes sense next to join: (transform: {join: references/x, on: k})"));
        }
        for (Map.Entry<String, Object> e : c.entrySet()) {
            if ("filter".equals(e.getKey())) {
                nodes.add(PipelineNode.of(id, BuiltinNodeType.TRANSFORM_FILTER.type(),
                        Map.of("where", e.getValue())));
            } else {
                refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_STEP, id,
                        "transform." + e.getKey() + " is not lowerable yet (only filter / join compile)"));
            }
        }
    }

    /** {@code sink} → persistent sink node (keys pass verbatim: table/format/compression/database/…). */
    private static PipelineNode sink(String id, Map<String, Object> cfg) {
        return PipelineNode.of(id, BuiltinNodeType.SINK_PERSISTENT.type(), new LinkedHashMap<>(cfg));
    }

    /** {@code dedup: {key: […], order_by: …}} → the record-grain dedup node ({@code processing.dedup}).
     *  {@code keep:} other than {@code first} is refused — the winner is {@code order_by}'s job. */
    private static PipelineNode dedup(String id, Map<String, Object> cfg,
                                      List<PipelineCompileException.Refusal> refusals) {
        Map<String, Object> c = new LinkedHashMap<>(cfg);
        Object keys = c.remove("key");
        if (keys == null) keys = c.remove("keys");
        Object keep = c.remove("keep");
        if (keep != null && !"first".equalsIgnoreCase(String.valueOf(keep)))
            refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_STEP, id,
                    "dedup keep: '" + keep + "' — only 'first' compiles; pick the winner with order_by"));
        Object orderBy = c.remove("order_by");
        if (keys == null)
            refusals.add(new PipelineCompileException.Refusal(MALFORMED_STEP, id,
                    "dedup needs a key: […] list (the business-key columns)"));
        for (String other : c.keySet())
            refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_STEP, id,
                    "dedup does not understand '" + other + "' (only key / keep / order_by)"));
        Map<String, Object> node = new LinkedHashMap<>();
        if (keys != null) node.put("keys", keys);
        if (orderBy != null) node.put("order_by", orderBy);
        return PipelineNode.of(id, BuiltinNodeType.TRANSFORM_DEDUP.type(), node);
    }

    /** {@code summarize: {group_by: […], measures: […]}} → the group-by rollup node
     *  ({@code processing.summarize}). Compile-only for now — {@code MaterializeTask} stays the
     *  runtime until a recipe-driven executor lands (ELT amendment Phase 3). */
    private static PipelineNode summarize(String id, Map<String, Object> cfg,
                                          List<PipelineCompileException.Refusal> refusals) {
        Map<String, Object> c = new LinkedHashMap<>(cfg);
        Object groupBy = c.remove("group_by");
        Object measures = c.remove("measures");
        if (measures == null)
            refusals.add(new PipelineCompileException.Refusal(MALFORMED_STEP, id,
                    "summarize needs a non-empty measures: […] list"));
        for (String other : c.keySet())
            refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_STEP, id,
                    "summarize does not understand '" + other + "' (only group_by / measures)"));
        Map<String, Object> node = new LinkedHashMap<>();
        if (groupBy != null) node.put("group_by", groupBy);
        if (measures != null) node.put("measures", measures);
        return PipelineNode.of(id, BuiltinNodeType.TRANSFORM_SUMMARIZE.type(), node);
    }

    /**
     * {@code route:} — the one user-visible branching construct (§2.6). Named branches, each a linear
     * sub-chain; <b>v1 restriction:</b> a branch's steps must be exactly one {@code sink} (mid-branch
     * transforms land with the branch-aware executor). Compiles to a {@code transform.route} node
     * (RowShaper's shape: {@code mode} / {@code branches:[{key,where}]} / top-level {@code default})
     * plus one sink node per branch fed by a {@code route:<key>} edge. Armed as of the
     * branch-aware-executor arming plan S3 (2026-08-26): an active {@code route:} pipeline
     * EXECUTES, subject to {@code prepare()}'s fail-closed validations — chiefly that a branch
     * is marked {@code default: true} (an armed route with no default is refused, because an
     * unmatched row would be silently dropped) and every branch's sink pairs by database.
     */
    private static void route(String id, Map<String, Object> cfg, List<PipelineNode> nodes,
                              List<PipelineNode> branchSinks, List<PipelineEdge> routeEdges,
                              List<PipelineCompileException.Refusal> refusals) {
        String mode = cfg.get("mode") == null ? "case" : String.valueOf(cfg.get("mode"));
        if (!(cfg.get("branches") instanceof Map<?, ?> branches) || branches.isEmpty()) {
            refusals.add(new PipelineCompileException.Refusal(MALFORMED_STEP, id,
                    "route needs a non-empty branches: map of named branches"));
            return;
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        String defaultKey = null;
        for (Map.Entry<?, ?> br : branches.entrySet()) {
            String key = String.valueOf(br.getKey());
            Map<String, Object> bc = configOf(br.getValue());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);
            if (bc.get("when") != null) entry.put("where", bc.get("when"));
            if (Boolean.TRUE.equals(bc.get("default"))) defaultKey = key;
            entries.add(entry);

            Object steps = bc.get("steps");
            if (!(steps instanceof List<?> l) || l.size() != 1
                    || !(l.get(0) instanceof Map<?, ?> s) || s.size() != 1
                    || !"sink".equals(String.valueOf(s.entrySet().iterator().next().getKey()))) {
                refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_STEP, id + ":" + key,
                        "a route branch compiles as exactly one sink step for now — "
                                + "mid-branch transforms land with the branch-aware executor"));
                continue;
            }
            Map<String, Object> sinkCfg = configOf(((Map<?, ?>) l.get(0)).values().iterator().next());
            PipelineNode sinkNode = sink("sink-" + key, sinkCfg);
            branchSinks.add(sinkNode);
            routeEdges.add(new PipelineEdge(id, PipelineRel.route(key), sinkNode.id()));
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("mode", mode);
        node.put("branches", entries);
        if (defaultKey != null) node.put("default", defaultKey);
        nodes.add(PipelineNode.of(id, BuiltinNodeType.TRANSFORM_ROUTE.type(), node));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Object> steps(Map<String, Object> recipe,
                                      List<PipelineCompileException.Refusal> refusals) {
        if (recipe.get("steps") instanceof List<?> l && !l.isEmpty()) return (List<Object>) l;
        refusals.add(new PipelineCompileException.Refusal(MALFORMED_STEP, null,
                "a recipe needs a non-empty steps: list"));
        return List.of();
    }

    private static Map<String, Object> configOf(Object v) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m)
            for (Map.Entry<?, ?> e : m.entrySet()) c.put(String.valueOf(e.getKey()), e.getValue());
        return c;
    }

    /** Remove {@code key} and return it as a {@code use:} reference, normalising the recipe's
     *  plural-directory spelling ({@code connections/x}) to the registry's singular ({@code connection/x}). */
    private static String takeRef(Map<String, Object> cfg, String key, String plural, String singular) {
        Object v = cfg.remove(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.startsWith(plural) ? singular + s.substring(plural.length())
                : s.startsWith(singular) ? s : singular + s;
    }

    /** Store a schema/mapping reference under {@code key}, normalising plural→singular registry refs;
     *  a plain path (contains a dot or slash beyond the prefix) travels verbatim. */
    private static void putRef(Map<String, Object> cfg, String key, Object v, String plural, String singular) {
        if (v == null) return;
        String s = v.toString().trim();
        if (s.startsWith(plural)) s = singular + s.substring(plural.length());
        cfg.put(key, s);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
