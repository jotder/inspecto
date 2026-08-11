package com.gamma.pipeline;

import java.util.Set;

/**
 * The node types the lean core ships. Each value's {@link #type()} string is the canonical
 * discriminator used in {@link PipelineNode#type()} and in {@code *_flow.toon}; each carries a
 * {@link #category()} (palette grouping + role checks), a {@link #label()}/{@link #description()}
 * for the UI, and advisory {@code accepts}/{@code emits} sets for the lift and the (Phase-3) wiring
 * validator. Operator-defined {@code route:*} branches are flagged by {@link #emitsNamedRoutes()}.
 *
 * <p>Reflects the §15 capability inventory: the {@code transform.*} family includes the
 * index-anchored {@code transform.filter} (G1) and the marker dedup subtype (G2). The former
 * fingerprint dedup subtype was folded into the acquisition node 2026-08-04 — it executes in the
 * poll cycle and had no runtime as a transform; marker remains a genuine subsystem of its own.
 *
 * <p><b>Sink is a family</b> (doc §3.1, decided 2026-06-17): a sink is one node-type family with three
 * materialisation behaviours — {@link #SINK_PERSISTENT} (data rests as a Parquet file / DuckDB table),
 * {@link #SINK_MATERIALIZED} (a managed/temp table upserted per batch — an incremental rollup), and
 * {@link #SINK_VIEW} (a non-persistent logical store a downstream job / KPI / report / alert API binds
 * to). All three are {@link NodeCategory#SINK}, so they superimpose over a shared store uniformly
 * ({@link PipelineStores}); the kind is a node-level concern, not a pipeline-topology one.
 */
public enum BuiltinNodeType implements PipelineNodeType {

    // ── entry / acquisition (the collector role, §3.1) ───────────────────────────
    // The label is "Collect", not "Acquisition": GLOSSARY §2/§3 allows one word per concept, and the
    // category this node sits under already renders "Collector". "Source" is banned outright.
    ACQUISITION("acquisition", NodeCategory.SOURCE, "Collect",
            "Collects files (poll/listing); the pipeline entry.",
            Set.of(), Set.of(PipelineRel.DATA, PipelineRel.GAP, PipelineRel.FAILURE), false),
    ADAPTER("adapter", NodeCategory.SOURCE, "Adapter",
            "Windows a stream/push source into intermediate files (by time/count/size), then lands them.",
            Set.of(), Set.of(PipelineRel.DATA), false),

    // ── parse ────────────────────────────────────────────────────────────────────
    // A parser may be a plain reader (data) or a selector/segment dispatcher (named routes + unmatched).
    PARSER("parser", NodeCategory.PARSE, "Parser",
            "Reads a landed file into rows; may dispatch by schema/segment (route:*) with an unmatched branch.",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA, PipelineRel.UNMATCHED), true),

    // ── transform family (§3.4 + §15) ─────────────────────────────────────────────
    TRANSFORM_MAP("transform.map", NodeCategory.TRANSFORM, "Map",
            "Maps raw fields onto the canonical schema.",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA), false),
    TRANSFORM_FILTER("transform.filter", NodeCategory.TRANSFORM, "Filter",
            "Keeps/drops rows by predicate; index-anchored CSV row-filter (G1).",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA, PipelineRel.DROPPED), false),
    TRANSFORM_SELECT("transform.select", NodeCategory.TRANSFORM, "Select",
            "Projects a subset / reorder of columns.",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA), false),
    TRANSFORM_DERIVE("transform.derive", NodeCategory.TRANSFORM, "Derive",
            "Adds computed columns (SQL-expression registry).",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA), false),
    TRANSFORM_VALIDATE("transform.validate", NodeCategory.TRANSFORM, "Validate",
            "Splits rows into valid / invalid by rule.",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA, PipelineRel.INVALID), false),
    TRANSFORM_DEDUP_MARKER("transform.dedup.marker", NodeCategory.TRANSFORM, "Dedup (marker)",
            "File-level dedup via marker files (MarkerManager) — a distinct subsystem (G2).",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA, PipelineRel.DUPLICATE), false),
    // Record-grain dedup (ELT amendment §2.4: business-key dedup IS a Step, unlike file dedup).
    // RowShaper already executed the "transform.dedup" type string ad hoc; this constant makes it a
    // declared kind so it can join LOWERABLE (flat home: processing.dedup {keys, order_by}).
    TRANSFORM_DEDUP("transform.dedup", NodeCategory.TRANSFORM, "Dedup (record)",
            "Record-grain dedup by business key (QUALIFY); duplicates are a counted reject stream.",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA, PipelineRel.DUPLICATE), false),
    // transform.dedup.fingerprint was REMOVED 2026-08-04: content-fingerprint dedup executes inside
    // the CollectorProcessor poll cycle (ledgerFilter reads collector.duplicate), so the separate
    // node had no runtime of its own — the policy is authored on the acquisition node now.
    TRANSFORM_ROUTE("transform.route", NodeCategory.TRANSFORM, "Route",
            "Content-based routing into operator-defined branches (case / clone).",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA), true),
    // Reference join (ELT amendment D-4/Phase 3 S2: the join is a transform concern — no enrich verb).
    // Flat home: processing.join {reference, on}. Distinct from ENRICHMENT below: that node is the
    // companion-persisted post-commit stage (truth = *_enrich.toon, ignored by lower); this one IS
    // lowered, and is compile-only until an in-pipeline join executor lands.
    TRANSFORM_JOIN("transform.join", NodeCategory.TRANSFORM, "Join",
            "Joins against a Reference Dataset by key.",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA), false),
    // Group-by rollup (ELT amendment §2.4/Phase 3: summarize IS a Step). Flat home: processing.summarize
    // {group_by, measures} — measures reuse MaterializeTask's shorthand grammar (count, sum(amount), …).
    // Executes in RowShaper.summarize (2026-08-11) via MeasureCompiler — one measure grammar across
    // the summarize node, materialize jobs and BI queries. Arming a flat pipeline that carries it
    // stays gated by prepare(), unchanged.
    TRANSFORM_SUMMARIZE("transform.summarize", NodeCategory.TRANSFORM, "Summarize",
            "Group-by rollup with algebraically-composable measures.",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA), false),
    TRANSFORM_SPLIT("transform.split", NodeCategory.TRANSFORM, "Split",
            "Explodes one row into many (UNNEST).",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA), false),
    TRANSFORM_MERGE("transform.merge", NodeCategory.TRANSFORM, "Merge",
            "Joins / unions multiple inbound data edges (fan-in).",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.DATA), false),

    // ── enrich ─────────────────────────────────────────────────────────────────────
    ENRICHMENT("enrichment", NodeCategory.TRANSFORM, "Enrichment",
            "Joins against reference data (post-commit stage-2 join).",
            Set.of(PipelineRel.DATA, PipelineRel.ON_COMMIT), Set.of(PipelineRel.DATA, PipelineRel.ON_COMMIT), false),

    // ── sink family — where data may rest, materialise, or be exposed (§3.1) ────────
    SINK_PERSISTENT("sink.persistent", NodeCategory.SINK, "Sink (persistent)",
            "Writes the batch to a resting store — a Parquet file / DuckDB table.",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.SUCCESS, PipelineRel.FAILURE, PipelineRel.ON_COMMIT), false),
    SINK_MATERIALIZED("sink.materialized", NodeCategory.SINK, "Sink (materialized)",
            "Maintains a managed/temp table, upserted per batch — an incremental rollup / summary.",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.SUCCESS, PipelineRel.FAILURE, PipelineRel.ON_COMMIT), false),
    SINK_VIEW("sink.view", NodeCategory.SINK, "Sink (view)",
            "A non-persistent logical store; jobs / KPI / report / alert APIs bind to it by store name.",
            Set.of(PipelineRel.DATA), Set.of(PipelineRel.ON_COMMIT), false),

    // ── reporting / notification ────────────────────────────────────────────────────
    ALERT("alert", NodeCategory.CONTROL, "Alert",
            "Raises an alert from rule / gap / failure outcomes.",
            Set.of(PipelineRel.DATA, PipelineRel.GAP, PipelineRel.FAILURE), Set.of(), false),
    GAP("gap", NodeCategory.CONTROL, "Gap detection",
            "Reports sequence gaps as SEQUENCE_GAP events.",
            Set.of(PipelineRel.GAP), Set.of(), false),
    EVENT("event", NodeCategory.CONTROL, "Event",
            "Emits a notification / event.",
            Set.of(PipelineRel.DATA, PipelineRel.SUCCESS, PipelineRel.FAILURE, PipelineRel.GAP), Set.of(), false);

    private final String type;
    private final NodeCategory category;
    private final String label;
    private final String description;
    private final Set<String> accepts;
    private final Set<String> emits;
    private final boolean emitsNamedRoutes;

    BuiltinNodeType(String type, NodeCategory category, String label, String description,
                    Set<String> accepts, Set<String> emits, boolean emitsNamedRoutes) {
        this.type = type;
        this.category = category;
        this.label = label;
        this.description = description;
        this.accepts = accepts;
        this.emits = emits;
        this.emitsNamedRoutes = emitsNamedRoutes;
    }

    @Override public String type() { return type; }
    @Override public NodeCategory category() { return category; }
    @Override public String label() { return label; }
    @Override public String description() { return description; }
    @Override public Set<String> accepts() { return accepts; }
    @Override public Set<String> emits() { return emits; }
    @Override public boolean emitsNamedRoutes() { return emitsNamedRoutes; }
}
