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
 * <p><b>Verb coverage (this slice):</b> {@code collect / parse / map / transform.filter / sink} — the
 * linear file-entry chain. {@code map} folds into the parser node (schema/mapping resolution is
 * parser-owned in the flat config). Not yet compilable, refused with named codes rather than silently
 * dropped: {@code dedup} (record-grain QUALIFY lowering lands post-S3), {@code route} (no lowerable
 * route node yet), {@code summarize} (Phase 3's verb), a non-empty {@code guarantees:} block (the
 * Phase-4 fold), and {@code transform.join}/{@code transform.derive} (compile targets land with the
 * Signal-bus unification).
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

        if (recipe.get("guarantees") instanceof Map<?, ?> g && !g.isEmpty())
            refusals.add(new PipelineCompileException.Refusal(GUARANTEES_NOT_LOWERABLE, null,
                    "the guarantees: fold lands in Phase 4 — remove the block for now"));

        List<PipelineNode> nodes = new ArrayList<>();
        PipelineNode parser = null;
        Map<String, Object> mapStep = null;
        String mapStepId = null;

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
                case "dedup" -> refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_STEP, id,
                        "the record-grain dedup Step is not lowerable yet (its QUALIFY compile target lands after S3)"));
                case "route" -> refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_STEP, id,
                        "route compiles once a lowerable route node exists — author branching on the canvas for now"));
                case "summarize" -> refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_STEP, id,
                        "summarize is Phase 3's verb (table-entry collect + Signal bus)"));
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

        return PipelineEditable.lower(new PipelineGraph(name, active, nodes, edges), existing, strict);
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

    /** {@code transform: {filter: <predicate>}} → transform.filter node ({@code csv_settings.where}). */
    private static void transform(String id, Map<String, Object> cfg, List<PipelineNode> nodes,
                                  List<PipelineCompileException.Refusal> refusals) {
        for (Map.Entry<String, Object> e : cfg.entrySet()) {
            if ("filter".equals(e.getKey())) {
                nodes.add(PipelineNode.of(id, BuiltinNodeType.TRANSFORM_FILTER.type(),
                        Map.of("where", e.getValue())));
            } else {
                refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_STEP, id,
                        "transform." + e.getKey() + " is not lowerable yet (only filter compiles in this slice)"));
            }
        }
    }

    /** {@code sink} → persistent sink node (keys pass verbatim: table/format/compression/database/…). */
    private static PipelineNode sink(String id, Map<String, Object> cfg) {
        return PipelineNode.of(id, BuiltinNodeType.SINK_PERSISTENT.type(), new LinkedHashMap<>(cfg));
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
