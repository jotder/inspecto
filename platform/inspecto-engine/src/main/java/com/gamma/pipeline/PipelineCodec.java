package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.gamma.util.Values.str;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serialises a {@link PipelineGraph} to / from a plain {@code Map} for authored {@code *_flow.toon} persistence
 * (doc §7.1 / §14 T19) — the inverse pair used by the pipeline CRUD endpoints + {@link PipelineStore}. Unlike the
 * read-only {@link PipelineProjection} (a UI-shaped, structural-only view), this is a <b>lossless</b> round-trip
 * of the authoring IR: every node's {@code id}/{@code type}/{@code name}/{@code description}/{@code use} and
 * its full local {@code config}, plus every relationship-typed edge.
 *
 * <p>Authored-pipeline config is plain nested maps (not the live typed {@code PipelineConfig} records the legacy
 * lift carries), so the round-trip is a straightforward map copy. Pure functions — no engine, no I/O.
 *
 * <p><b>Read alias (X5-a, 4.0.0).</b> Decoding maps every edge's {@code rel} through {@link PipelineRel#canonical},
 * so a graph saved before 4.0.0 with {@code dropped} / {@code invalid} / {@code duplicate} / {@code unmatched}
 * loads, validates and runs, and {@link #toMap} writes the {@code reject:<reason>} spelling the next time it is
 * saved. An edge naming a retired outcome relation ({@code success}, {@code failure}, {@code on_commit}) is
 * dropped: nothing ever produced one, so the walk never followed it. {@link #decode} reports each dropped edge
 * as a {@link PipelineValidator#RETIRED_RELATION} warning; {@link #fromMap} logs it. No file is rewritten in place.
 */
@PublicApi(since = "4.0.0")
public final class PipelineCodec {

    private static final Logger log = LoggerFactory.getLogger(PipelineCodec.class);

    /** The outcome relations retired in 4.0.0 (X5-a, decision D5): declared, never produced by anything. */
    private static final Set<String> RETIRED_RELS = Set.of("success", "failure", "on_commit");

    private PipelineCodec() {}

    /**
     * A decoded graph plus the {@link PipelineValidator.Severity#WARNING} issues decoding raised — one
     * {@link PipelineValidator#RETIRED_RELATION} per authored edge that named a retired relation and was dropped.
     */
    public record Decoded(PipelineGraph graph, List<PipelineValidator.Issue> warnings) {
        public Decoded {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** A {@link PipelineGraph} as a {@code .toon}-friendly map: {@code {name, active, nodes[], edges[]}}. */
    public static Map<String, Object> toMap(PipelineGraph g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", g.name());
        m.put("active", g.active());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (PipelineNode n : g.nodes()) nodes.add(nodeToMap(n));
        m.put("nodes", nodes);
        List<Map<String, Object>> edges = new ArrayList<>();
        for (PipelineEdge e : g.edges()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("from", e.from());
            em.put("rel", e.rel());
            em.put("to", e.to());
            edges.add(em);
        }
        m.put("edges", edges);
        return m;
    }

    private static Map<String, Object> nodeToMap(PipelineNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("type", n.type());
        if (n.hasName()) m.put("name", n.name());
        if (n.description() != null && !n.description().isBlank()) m.put("description", n.description());
        if (n.hasUse()) m.put("use", n.use());
        if (!n.config().isEmpty()) m.put("config", n.config());
        return m;
    }

    /**
     * Parse a decoded {@code *_flow.toon} map into a {@link PipelineGraph}. {@code name} is required; {@code active}
     * defaults to {@code false} (opt-in, matching pipelines); {@code nodes}/{@code edges} default to empty. Each
     * node requires {@code id}+{@code type}; each edge requires {@code from}+{@code to} ({@code rel} defaults to
     * {@code data}). Throws {@link IllegalArgumentException} on a malformed shape. Legacy relation spellings are
     * read through {@link PipelineRel#canonical}; an edge naming a retired relation is dropped and logged (the
     * class note) — {@link #decode} returns those warnings instead.
     */
    public static PipelineGraph fromMap(Map<String, Object> raw) {
        Decoded d = decode(raw);
        for (PipelineValidator.Issue w : d.warnings()) log.warn("pipeline '{}': {}", d.graph().name(), w.message());
        return d.graph();
    }

    /** As {@link #fromMap}, returning the retired-relation warnings with the graph rather than logging them. */
    @SuppressWarnings("unchecked")
    public static Decoded decode(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("pipeline definition is required");
        String name = str(raw.get("name"));
        if (name == null || name.isBlank()) throw new IllegalArgumentException("pipeline 'name' is required");
        boolean active = toBool(raw.get("active"));

        List<PipelineNode> nodes = new ArrayList<>();
        for (Object o : asList(raw.get("nodes"))) {
            if (!(o instanceof Map<?, ?> nm)) throw new IllegalArgumentException("each node must be a map");
            nodes.add(nodeFromMap((Map<String, Object>) nm));
        }
        List<PipelineEdge> edges = new ArrayList<>();
        List<PipelineValidator.Issue> warnings = new ArrayList<>();
        for (Object o : asList(raw.get("edges"))) {
            if (!(o instanceof Map<?, ?> em)) throw new IllegalArgumentException("each edge must be a map");
            Map<String, Object> e = (Map<String, Object>) em;
            String from = str(e.get("from"));
            String to = str(e.get("to"));
            if (from == null || to == null)
                throw new IllegalArgumentException("each edge requires 'from' and 'to'");
            String rel = str(e.get("rel"));
            if (rel != null && RETIRED_RELS.contains(rel)) {
                warnings.add(new PipelineValidator.Issue(PipelineValidator.Severity.WARNING,
                        PipelineValidator.RETIRED_RELATION, "Edge '" + rel + "' from '" + from + "' to '" + to
                                + "' was dropped: the '" + rel + "' relation was retired in 4.0.0 (nothing produced "
                                + "it; a Consignment's outcome is a pipeline.batch.* Signal)."));
                continue;
            }
            edges.add(new PipelineEdge(from, PipelineRel.canonical(rel), to));
        }
        return new Decoded(new PipelineGraph(name, active, nodes, edges), warnings);
    }

    @SuppressWarnings("unchecked")
    public static PipelineNode nodeFromMap(Map<String, Object> nm) {
        String id = str(nm.get("id"));
        String type = str(nm.get("type"));
        if (id == null || id.isBlank() || type == null || type.isBlank())
            throw new IllegalArgumentException("each node requires 'id' and 'type'");
        Map<String, Object> config = nm.get("config") instanceof Map<?, ?> cm
                ? (Map<String, Object>) cm : Map.of();
        return new PipelineNode(id, type, str(nm.get("name")), str(nm.get("description")), config, str(nm.get("use")));
    }

    /** One edge; its {@code rel} is read through {@link PipelineRel#canonical} (a retired one is left for the validator to refuse). */
    @SuppressWarnings("unchecked")
    public static PipelineEdge edgeFromMap(Map<String, Object> em) {
        String from = str(em.get("from"));
        String to = str(em.get("to"));
        if (from == null || to == null) throw new IllegalArgumentException("an edge requires 'from' and 'to'");
        return new PipelineEdge(from, PipelineRel.canonical(str(em.get("rel"))), to);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static List<?> asList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return l;
        throw new IllegalArgumentException("expected a list, got " + v.getClass().getSimpleName());
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean b) return b;
        return v != null && "true".equalsIgnoreCase(v.toString().trim());
    }
}
