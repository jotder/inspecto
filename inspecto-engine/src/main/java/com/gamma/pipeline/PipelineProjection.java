package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only projection of the Flow IR into plain JSON-friendly maps for the UI (doc §6, T31). Three
 * shapes: a node-type {@link #catalog()} for the editor palette, a per-graph {@link #graph(PipelineGraph)}
 * for the G6 renderer, and a compact {@link #summary(PipelineGraph)} for the flows list.
 *
 * <p><b>Structural only.</b> A node carries its id / type / {@link NodeCategory category} / label +
 * the user {@code name}/{@code description} + edge relationships + store hints (a sink's produced
 * {@code store}, a consumer's {@code sourceStore}, the sink kind) — <em>not</em> the raw typed config
 * (which carries live {@code PipelineConfig} sub-records / {@code SchemaSelector} / schema maps). The
 * node inspector resolves effective config separately (T17). Keeping the projection structural avoids
 * dumping engine internals over the wire and keeps the payload small.
 *
 * <p>Pure functions over the IR — no engine, no I/O — so they unit-test without HTTP. Returned maps use
 * insertion order ({@link LinkedHashMap}) so the JSON field order is stable.
 */
@PublicApi(since = "4.0.0")
public final class PipelineProjection {

    private PipelineProjection() {}

    /** The node-type palette: every registered type's category / label / description / ports. */
    public static List<Map<String, Object>> catalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PipelineNodeType t : PipelineNodeTypes.catalog()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", t.type());
            m.put("category", t.category().name());
            m.put("label", t.label());
            m.put("description", t.description());
            m.put("accepts", List.copyOf(t.accepts()));
            m.put("emits", List.copyOf(t.emits()));
            m.put("emitsNamedRoutes", t.emitsNamedRoutes());
            // Save-ability, not runnability: the engine executes far more than the flat config can
            // round-trip. The palette greys these out rather than refusing at Save.
            m.put("lowerable", PipelineEditable.isLowerable(t.type()));
            // …and whether the palette may OFFER it. A retired-but-still-lowerable type (P5-a's
            // transform.dedup.marker) is exactly why these cannot be one flag: filtering the palette
            // on lowerability would force the choice between offering a node nothing should create
            // and refusing to save a graph that legitimately still carries one.
            m.put("authorable", PipelineEditable.isAuthorable(t.type()));
            // §3.1: the node's config vocabulary, published so it has ONE definition. Before this the
            // catalog carried no attribute specs at all and the keys lived only in the client table —
            // the root cause §1 traced every config-key defect (D1–D9) back to. Absent/empty ⇒ the type
            // has no schema and the dialog falls back to its free-form key/value editor.
            List<Map<String, Object>> attributes = new ArrayList<>();
            for (NodeAttribute a : NodeAttributes.forType(t.type())) attributes.add(a.toMap());
            m.put("attributes", attributes);
            out.add(m);
        }
        return out;
    }

    /**
     * The recipe-verb palette (ELT amendment §5, Phase 5): the verbs in pipeline order (see
     * {@link #RECIPE_VERBS} — a verb may be entered once per shape it authors, so {@code parse} appears
     * once per FORMAT and {@code transform} once per shape),
     * each carrying the node type it authors as plus that type's served attribute specs — the
     * server-published version of the verb table the UI carried as its documented interim
     * ({@code RECIPE_VERBS}). {@code map} authors a {@code transform.map} node in the GRAPH editor even
     * though the recipe compiler folds it into parse — the verb exists either way, only its persistence
     * home differs. Plugin-contributed node types (anything beyond the builtins) are appended after the
     * verbs, keyed by their own type, so a deployment's custom Steps show up without a UI release.
     */
    public static List<Map<String, Object>> stepCatalog() {
        Map<String, PipelineNodeType> byType = new LinkedHashMap<>();
        for (PipelineNodeType t : PipelineNodeTypes.catalog()) byType.put(t.type(), t);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] v : RECIPE_VERBS) out.add(stepEntry(v[0], v[1], byType.get(v[1])));
        Set<String> builtin = new LinkedHashSet<>();
        for (BuiltinNodeType b : BuiltinNodeType.values()) builtin.add(b.type());
        for (PipelineNodeType t : byType.values())
            if (!builtin.contains(t.type())) out.add(stepEntry(t.type(), t.type(), t));
        return out;
    }

    /**
     * The Step Processor catalog for {@code GET /pipelines/processor-catalog}: {@link ProcessorCatalog}
     * plus, per processor, {@code addable} — true only for a processor that maps onto a node type the
     * editor may author ({@link PipelineEditable#isAuthorable}). A PARTIAL processor mapped onto an
     * authorable node type is addable (it adds THAT node); a capability-mapped or PLANNED one is not,
     * and renders inactive in the palettes.
     */
    public static Map<String, Object> processorCatalog() {
        Map<String, Object> out = ProcessorCatalog.asMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> procs = (List<Map<String, Object>>) out.get("processors");
        for (Map<String, Object> m : procs) {
            Object nodeType = m.get("nodeType");
            m.put("addable", nodeType instanceof String t && PipelineEditable.isAuthorable(t));
        }
        return out;
    }

    /**
     * verb → the node type it authors as, in pipeline order (collect first, sink last).
     * <p>⚠ A verb may appear more than once: {@code transform} authors both {@code transform.filter} and
     * {@code transform.join}, because the recipe spells a join as {@code transform: {join: …}} — there is no
     * {@code join} case in {@link RecipeCompiler}'s verb switch, so publishing {@code verb: "join"} would
     * advertise a vocabulary the compiler refuses. The palette entry is therefore per SHAPE while the verb
     * stays the recipe's own word; {@code type} is the unique key of an entry, never {@code verb}.
     */
    private static final List<String[]> RECIPE_VERBS = List.of(
            new String[] {"collect", BuiltinNodeType.ACQUISITION.type()},
            // 🔴 One entry PER FORMAT, not one generic `parse` (pipeline spec gap 2, decision D3). The
            // generic BuiltinNodeType.PARSER is READ_COMPAT_ONLY, so `isAuthorable` already refuses it
            // and the canvas palette never offered it — but this catalogue published it anyway, which
            // meant the SAME vocabulary disagreed with itself across two served surfaces, and a recipe
            // author got an untyped Parse Step that had to be converted through a custody dialog.
            // The verb stays the recipe's own word (`parse`); `type` is what makes an entry unique.
            new String[] {"parse", BuiltinNodeType.PARSER_DELIMITED.type()},
            new String[] {"parse", BuiltinNodeType.PARSER_FIXEDWIDTH.type()},
            new String[] {"parse", BuiltinNodeType.PARSER_JSON.type()},
            new String[] {"parse", BuiltinNodeType.PARSER_TEXT_REGEX.type()},
            new String[] {"parse", BuiltinNodeType.PARSER_XLSX.type()},
            new String[] {"parse", BuiltinNodeType.PARSER_ASN1.type()},
            new String[] {"parse", BuiltinNodeType.PARSER_PLUGIN.type()},
            new String[] {"map", BuiltinNodeType.TRANSFORM_MAP.type()},
            new String[] {"dedup", BuiltinNodeType.TRANSFORM_DEDUP.type()},
            new String[] {"transform", BuiltinNodeType.TRANSFORM_FILTER.type()},
            new String[] {"transform", BuiltinNodeType.TRANSFORM_JOIN.type()},
            new String[] {"sql", BuiltinNodeType.TRANSFORM_SQL.type()},
            new String[] {"summarize", BuiltinNodeType.TRANSFORM_SUMMARIZE.type()},
            new String[] {"route", BuiltinNodeType.TRANSFORM_ROUTE.type()},
            new String[] {"sink", BuiltinNodeType.SINK_PERSISTENT.type()});

    private static Map<String, Object> stepEntry(String verb, String type, PipelineNodeType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verb", verb);
        m.put("type", type);
        m.put("category", t == null ? NodeCategory.TRANSFORM.name() : t.category().name());
        m.put("label", t == null ? type : t.label());
        m.put("description", t == null ? "" : t.description());
        m.put("lowerable", PipelineEditable.isLowerable(type));
        List<Map<String, Object>> attributes = new ArrayList<>();
        for (NodeAttribute a : NodeAttributes.forType(type)) attributes.add(a.toMap());
        m.put("attributes", attributes);
        return m;
    }

    /** A flow's full topology for the G6 renderer: nodes + relationship-typed edges + store endpoints. */
    public static Map<String, Object> graph(PipelineGraph g) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", g.name());
        out.put("active", g.active());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (PipelineNode n : g.nodes()) nodes.add(node(n));
        out.put("nodes", nodes);
        List<Map<String, Object>> edges = new ArrayList<>();
        for (PipelineEdge e : g.edges()) edges.add(edge(e));
        out.put("edges", edges);
        out.put("produces", List.copyOf(PipelineStores.produced(g)));
        out.put("consumes", List.copyOf(PipelineStores.consumed(g)));
        return out;
    }

    /**
     * <b>T24 — the combined pipeline+job topology.</b> Projects several flows into <em>one</em> graph where
     * a pipeline and the job(s)/enrichment(s) over its output meet at the <b>shared store</b> (§3.8): each
     * flow's nodes are emitted with their ids namespaced by flow ({@code <flow>/<node>}, so two flows that
     * both have an {@code acq} node don't collide), plus a synthetic <b>store node</b>
     * ({@code store:<name>}, category {@code STORE}) for every store any flow produces or consumes, wired
     * {@code producer-sink → store → consumer} — drawing the cross-flow {@code on_commit} producer→consumer
     * relationship through the table itself. The {@code links} list is the derived
     * {@link PipelineStores#superimpose(Collection) superimposition} (producer, store, consumer) for reference.
     *
     * <p>The join is derived from config alone (a sink's {@code store} ↔ a consumer's {@code source_store}),
     * so it needs no {@code on_pipeline} coupling; a pipeline with no consumer simply shows its
     * {@code sink → store} leaf.
     */
    public static Map<String, Object> combined(Collection<PipelineGraph> graphs) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> flows = new ArrayList<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> stores = new LinkedHashSet<>();   // every store produced or consumed → one synthetic node

        for (PipelineGraph g : graphs) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", g.name());
            f.put("active", g.active());
            flows.add(f);

            for (PipelineNode n : g.nodes()) {
                Map<String, Object> nm = node(n);
                nm.put("id", qualify(g.name(), n.id()));   // namespace to avoid cross-flow id collisions
                nm.put("pipeline", g.name());
                nm.put("flow", g.name());   // Tier 3 dual-emit: kept for callers still reading the pre-rename key
                nodes.add(nm);
            }
            for (PipelineEdge e : g.edges()) {
                Map<String, Object> em = edge(e);
                em.put("from", qualify(g.name(), e.from()));
                // on_commit's `to` names another flow, not a local node — keep it bare so it can resolve cross-flow
                em.put("to", g.byId().containsKey(e.to()) ? qualify(g.name(), e.to()) : e.to());
                em.put("pipeline", g.name());
                em.put("flow", g.name());   // Tier 3 dual-emit: kept for callers still reading the pre-rename key
                edges.add(em);
            }
            // producer edges: each producing sink → its store node
            for (PipelineStores.Produced p : PipelineStores.producedStores(g)) {
                stores.add(p.store());
                edges.add(storeEdge(qualify(g.name(), p.node()), storeId(p.store()), "produces", p.restsOnDisk()));
            }
            // consumer edges: store node → each node that reads it at rest
            for (PipelineNode n : g.nodes()) {
                Object src = n.cfg(PipelineStores.CONFIG_SOURCE_STORE);
                if (src != null && !src.toString().isBlank()) {
                    stores.add(src.toString());
                    edges.add(storeEdge(storeId(src.toString()), qualify(g.name(), n.id()), "consumes", true));
                }
            }
        }
        for (String s : stores) {
            Map<String, Object> sn = new LinkedHashMap<>();
            sn.put("id", storeId(s));
            sn.put("type", "store");
            sn.put("category", "STORE");
            sn.put("label", s);
            sn.put("store", s);
            nodes.add(sn);
        }
        List<Map<String, Object>> links = new ArrayList<>();
        for (PipelineStores.Link l : PipelineStores.superimpose(graphs)) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("producer", l.producer());
            lm.put("store", l.store());
            lm.put("consumer", l.consumer());
            links.add(lm);
        }
        out.put("pipelines", flows);
        out.put("flows", flows);   // Tier 3 dual-emit: kept for callers still reading the pre-rename key
        out.put("nodes", nodes);
        out.put("edges", edges);
        out.put("links", links);
        return out;
    }

    private static String qualify(String flow, String nodeId) {
        return flow + "/" + nodeId;
    }

    private static String storeId(String store) {
        return "store:" + store;
    }

    private static Map<String, Object> storeEdge(String from, String to, String rel, boolean restsOnDisk) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to", to);
        m.put("rel", rel);
        m.put("kind", "store");
        m.put("restsOnDisk", restsOnDisk);
        return m;
    }

    /** A compact entry for the flows list: name + gate + node/edge counts + store endpoints. */
    public static Map<String, Object> summary(PipelineGraph g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", g.name());
        m.put("active", g.active());
        m.put("nodeCount", g.nodes().size());
        m.put("edgeCount", g.edges().size());
        m.put("produces", List.copyOf(PipelineStores.produced(g)));
        m.put("consumes", List.copyOf(PipelineStores.consumed(g)));
        return m;
    }

    private static Map<String, Object> node(PipelineNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("type", n.type());
        m.put("category", PipelineNodeTypes.categoryOf(n.type()).map(Enum::name).orElse(NodeCategory.TRANSFORM.name()));
        m.put("label", PipelineNodeTypes.get(n.type()).map(PipelineNodeType::label).orElse(n.type()));
        if (n.hasName()) m.put("name", n.name());                              // user-given (may name a business object)
        if (n.description() != null && !n.description().isBlank()) m.put("description", n.description());
        if (n.hasUse()) m.put("use", n.use());
        // store hints for the viz — string only, never the raw typed config
        Object store = n.cfg(PipelineStores.CONFIG_STORE);
        if (store != null && !store.toString().isBlank()) m.put("store", store.toString());
        Object sourceStore = n.cfg(PipelineStores.CONFIG_SOURCE_STORE);
        if (sourceStore != null && !sourceStore.toString().isBlank()) m.put("sourceStore", sourceStore.toString());
        // sink subtype styles the node: persistent/materialized rest on disk, view is logical (no glyph)
        if (PipelineNodeTypes.isCategory(n.type(), NodeCategory.SINK)) {
            m.put("sinkKind", sinkKind(n.type()));
            m.put("restsOnDisk", !n.type().endsWith(".view"));
        }
        return m;
    }

    /** The sink subtype suffix ({@code sink.persistent} → {@code persistent}); the bare type otherwise. */
    private static String sinkKind(String type) {
        int dot = type.indexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    private static Map<String, Object> edge(PipelineEdge e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", e.from());
        m.put("to", e.to());
        m.put("rel", e.rel());
        m.put("kind", e.isData() ? "data" : PipelineRel.isRoute(e.rel()) ? "route" : "control");
        if (PipelineRel.isRoute(e.rel())) m.put("routeKey", PipelineRel.routeKey(e.rel()));
        return m;
    }
}
