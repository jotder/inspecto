package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lifts a legacy {@link PipelineConfig} into the {@link PipelineGraph} IR — an <b>internal</b>
 * representation only, never a file rewrite. The lift is faithful and <b>lossless</b>: typed
 * sub-records (the {@code source} sub-blocks, the {@link com.gamma.etl.CsvSettings} record, the
 * {@link SchemaSelector}, raw schema maps) are carried verbatim as node {@code config} values, so a
 * later compile-back (T5) can reproduce today's execution exactly. Phase 1 stops at the IR — nothing
 * here changes runtime behaviour.
 *
 * <h3>Shapes (doc §15 capability inventory)</h3>
 * <ul>
 *   <li><b>single schema</b> → linear {@code acq → [dedup] → parse → [filter] → map → sink}.</li>
 *   <li><b>selector</b> (multi-schema {@code schemas[]}) → one {@code parser} dispatcher emitting
 *       {@code route:<table>} branches to per-schema {@code map → sink}, plus {@code unmatched →
 *       quarantine}. The {@link SchemaSelector} (with its column-count / file_pattern priority) is
 *       carried on the parser node, so the route metadata (G3) is preserved.</li>
 *   <li><b>segments</b> (plugin ingester) → a plugin {@code parser} ({@code use: ingester/<fqcn>})
 *       emitting {@code route:<segment>} branches (G5).</li>
 * </ul>
 *
 * <p>Control wiring implied by existing flags: {@code gap_detection} → a {@code gap} node via a
 * {@code gap} edge (G7); {@code post_action} is carried on the {@code acquisition} node as a
 * success-side finalizer (G8), <em>not</em> a {@code failure} edge. Content-fingerprint dedup
 * ({@code collector.duplicate}/{@code incremental}) is carried ON the acquisition node — it executes
 * inside the {@code CollectorProcessor} poll cycle, so a separate graph node misrepresented where the
 * check runs and had no runtime of its own (folded 2026-08-04; only the marker subsystem keeps a
 * node, G2). Dead top-level keys ({@code version}/{@code search}/…) are dropped (F1).
 */
@PublicApi(since = "4.0.0")
public final class PipelineLift {

    private PipelineLift() {}

    // ── stable node ids ──────────────────────────────────────────────────────────
    static final String ACQ               = "acq";
    /** No longer emitted (P5-a moved the keys onto {@link #ACQ}); still the id a legacy graph carries. */
    static final String DEDUP_MARKER      = "dedup_marker";
    static final String PARSE             = "parse";
    static final String QUARANTINE        = "quarantine";
    static final String GAP               = "gap";

    /** Lift a loaded {@link PipelineConfig} into a {@link PipelineGraph}. */
    public static PipelineGraph lift(PipelineConfig cfg) {
        List<PipelineNode> nodes = new ArrayList<>();
        List<PipelineEdge> edges = new ArrayList<>();

        // 1. acquisition (entry) + optional gap control edge
        nodes.add(acquisitionNode(cfg));
        if (cfg.collector().gapDetection().active()) {
            Map<String, Object> gap = new LinkedHashMap<>();
            put(gap, "sequence", cfg.collector().gapDetection().sequence());
            nodes.add(new PipelineNode(GAP, BuiltinNodeType.GAP.type(), "Gap detection", null, gap, null));
            edges.add(new PipelineEdge(ACQ, PipelineRel.GAP, GAP));
        }

        // 2. parser, fed directly by acq. Marker dedup used to sit between them as its own node; it
        //    now rides the acquisition node like the fingerprint policy already did (P5-a), because
        //    both are file-grain Guarantees the poll cycle applies before anything is parsed.
        nodes.add(parserNode(cfg));
        edges.add(PipelineEdge.data(ACQ, PARSE));

        // 3. branch on schema resolution (exactly one of the three is set)
        PipelineConfig.Schemas s = cfg.schemas();
        // either filtering moment surfaces as one Filter node (pre-parse lists and/or post-parse `where`)
        boolean rowFilters = cfg.csv().hasRowFilters() || cfg.csv().hasRowPredicate();

        if (s.selector() != null && s.selector().hasSchemas()) {
            int i = 0;
            for (SchemaSelector.Selection sel : s.selector().entries()) {
                String key = routeKey(sel.table(), i++);
                branch(nodes, edges, PipelineRel.route(key), key, sel.schema(), sel.table(), cfg, rowFilters);
            }
            addQuarantine(nodes, edges, cfg);
        } else if (s.segments() != null && !s.segments().isEmpty()) {
            for (Map.Entry<String, Map<String, Object>> e : s.segments().entrySet()) {
                String key = routeKey(e.getKey(), 0);
                branch(nodes, edges, PipelineRel.route(key), key, e.getValue(), e.getKey(), cfg, rowFilters);
            }
            addQuarantine(nodes, edges, cfg);
        } else {
            // single schema: one linear chain off the parser's data edge
            branch(nodes, edges, PipelineRel.DATA, null, s.single(), null, cfg, rowFilters);
        }

        // Phase 4 S4 (D-13): overlay the authored disable list onto the lifted nodes — the flat file's
        // ONE home for per-Step enabled: is processing.disabled_steps, and this is where it becomes the
        // node-level flag the canvas and the scratch executors (dry-run / run-to-here bypass) read.
        // The at-rest lane refuses to arm a non-empty list until park/drain ships (StepDisableArming).
        if (!cfg.disabledSteps().isEmpty()) {
            List<PipelineNode> overlaid = new ArrayList<>(nodes.size());
            for (PipelineNode n : nodes) {
                if (cfg.disabledSteps().contains(n.id())) {
                    Map<String, Object> c = new LinkedHashMap<>(n.config());
                    c.put("enabled", false);
                    overlaid.add(new PipelineNode(n.id(), n.type(), n.name(), n.description(), c, n.use()));
                } else {
                    overlaid.add(n);
                }
            }
            nodes = overlaid;
        }

        return new PipelineGraph(cfg.identity().pipelineName(), cfg.active(), nodes, edges);
    }

    /** Stable node ids of the at-rest Stage-2 lift ({@link #stageTwo}). */
    static final String STAGE2_SRC  = "src";
    static final String STAGE2_SINK = "sink";

    /** Filter-step keys that only execute pre-parse (raw {@code c<N>} columns) — impossible at rest. */
    private static final List<String> PRE_PARSE_FILTER_KEYS = List.of(
            "filter_target_column", "include_prefixes", "include_regex", "exclude_prefixes", "exclude_regex");

    /**
     * <b>A5-at-rest slice 1 (multiplicity plan "A5 RE-SCOPED", 2026-08-11).</b> Lift ONLY a flat config's
     * Stage-2 remainder into a runnable at-rest graph: a {@code source_store} seed reading the store this
     * pipeline's linear path lands → the ordered {@link PipelineConfig#steps() chain} → one
     * {@code sink.persistent} named by the authored top-level {@code output_store:} key. The ingest head
     * (acquisition/parse/map) is deliberately absent — Stage 1 already ran; this graph re-shapes its
     * output through {@code PipelineJobRunner} (slice 2 wires the job key).
     *
     * <p>Refusals — each is a chain this route would run <em>wrongly</em>, so it fails closed instead:
     * <ul>
     *   <li>no chain, or no {@code output_store:} — nothing to run / nowhere to write;</li>
     *   <li>a multi-schema pipeline (selector/segments) — it lands several stores, and one seed cannot
     *       pick among them;</li>
     *   <li>a {@code route} step — route's home is the <b>ingest</b> lane, where the branch-aware
     *       executor gives each branch key its own sink; one {@code output_store} cannot name them, so the
     *       refusal points the author at the lane where route <em>does</em> work rather than stating twice
     *       what it cannot do here;</li>
     *   <li>a legacy-projected {@code filter} step — the legacy filter is <b>pre-map</b> (fused
     *       {@code csv_settings}), so its predicate speaks raw-column vocabulary the landed store no
     *       longer has; an explicit {@code steps:} filter is post-map and fine — unless it carries a
     *       pre-parse key ({@code include_prefixes} …), which cannot execute at rest either.</li>
     * </ul>
     */
    public static PipelineGraph stageTwo(PipelineConfig cfg) {
        String name = cfg.identity().pipelineName();
        List<PipelineConfig.Step> steps = cfg.steps();
        if (steps.isEmpty())
            throw new IllegalArgumentException("pipeline '" + name + "' has no Stage-2 chain to lift");
        String out = cfg.outputStore();
        if (out == null)
            throw new IllegalArgumentException("pipeline '" + name + "' has a Stage-2 chain but no "
                    + "top-level 'output_store:' — the at-rest run needs an authored name for the store it writes");
        PipelineConfig.Schemas s = cfg.schemas();
        if ((s.selector() != null && s.selector().hasSchemas()) || (s.segments() != null && !s.segments().isEmpty()))
            throw new IllegalArgumentException("pipeline '" + name + "' is multi-schema — it lands several "
                    + "stores, and the at-rest Stage-2 lift cannot pick one seed among them");
        for (PipelineConfig.Step step : steps) {
            if (PipelineConfig.Step.ROUTE.equals(step.kind()))
                throw new IllegalArgumentException("pipeline '" + name + "' chains a 'route' step. Route "
                        + "runs on the INGEST lane, where the branch-aware executor gives each branch key its "
                        + "own sink; the at-rest lift has one output_store and so cannot name them. Activate "
                        + "the pipeline and let it route during ingest, or drop the route step from the "
                        + "at-rest chain");
            if (PipelineConfig.Step.FILTER.equals(step.kind())) {
                if (!cfg.hasExplicitSteps())
                    throw new IllegalArgumentException("pipeline '" + name + "' carries a legacy (pre-map) "
                            + "filter — its predicate speaks raw-column vocabulary the landed store no longer has");
                for (String k : PRE_PARSE_FILTER_KEYS)
                    if (step.config().containsKey(k))
                        throw new IllegalArgumentException("pipeline '" + name + "' chains a filter carrying "
                                + "pre-parse key '" + k + "', which cannot execute over the landed store");
            }
        }

        List<PipelineNode> nodes = new ArrayList<>();
        List<PipelineEdge> edges = new ArrayList<>();
        String landed = canonicalName(s.single(), name);
        nodes.add(new PipelineNode(STAGE2_SRC, BuiltinNodeType.ACQUISITION.type(), "Landed store",
                "Reads the store the linear path lands",
                Map.of(PipelineStores.CONFIG_SOURCE_STORE, landed), null));

        String upstream = STAGE2_SRC;
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            PipelineConfig.Step step = steps.get(i);
            String kind = step.kind();
            int nth = seen.merge(kind, 1, Integer::sum);
            String id = kind + (nth == 1 ? "" : "__s" + i);   // same id scheme as the ingest-headed lift
            nodes.add(new PipelineNode(id, "transform." + kind, stepLabel(kind), null,
                    new LinkedHashMap<>(step.config()), null));
            edges.add(PipelineEdge.data(upstream, id));
            upstream = id;
        }

        Map<String, Object> sinkCfg = new LinkedHashMap<>();
        sinkCfg.put(PipelineStores.CONFIG_STORE, out);
        nodes.add(new PipelineNode(STAGE2_SINK, BuiltinNodeType.SINK_PERSISTENT.type(), out,
                "Persistent store", sinkCfg, null));
        edges.add(PipelineEdge.data(upstream, STAGE2_SINK));

        return new PipelineGraph(name + "_stage2", cfg.active(), nodes, edges);
    }

    // ── node builders ──────────────────────────────────────────────────────────

    private static PipelineNode acquisitionNode(PipelineConfig cfg) {
        PipelineConfig.Collector src = cfg.collector();
        Map<String, Object> c = new LinkedHashMap<>();
        put(c, "connector", src.connector());
        put(c, "dataset", src.dataset());   // S3c-2: the dataset-entry source id, round-tripped verbatim
        put(c, "poll", cfg.dirs().poll());
        put(c, "id", src.id());
        if (!src.includes().isEmpty()) c.put("includes", src.includes());
        if (!src.excludes().isEmpty()) c.put("excludes", src.excludes());
        c.put("recursive_depth", src.recursiveDepth());
        c.put("discovery", src.discovery());   // W0: poll|watch — carried so the collector block round-trips
        // typed sub-records — never null (Source canonical ctor defaults them), carried verbatim
        c.put("stability", src.stability());
        c.put("duplicate", src.duplicate());   // fingerprint policy — executes in the poll cycle (2026-08-04 fold)
        c.put("incremental", src.incremental());
        c.put("guarantee", src.guarantee());
        c.put("fetch", src.fetch());
        c.put("retry", src.retry());
        c.put("circuit_breaker", src.circuitBreaker());
        c.put("post_action", src.postAction());   // success-side finalizer (G8)
        if (cfg.triggerConfig() != null) c.put("trigger", cfg.triggerConfig());   // T13: entry-node trigger (§3.6)
        // Marker dedup (P5-a): the file-grain marker Guarantee, homed here beside the fingerprint
        // policy it is a sibling of. `duplicate_check` is the AUTHORED on/off — presence of the
        // detail keys must never be the switch, or clearing a retention field would silently
        // disable dedup on the next save.
        if (cfg.processing().duplicateCheckEnabled()) {
            c.put("duplicate_check", true);
            put(c, "marker_extension", cfg.processing().markerExtension());
            c.put("retention_days", cfg.processing().retentionDays());
            put(c, "markers_dir", cfg.dirs().markers());
        }
        String use = src.hasConnection() ? "connection/" + src.connection() : null;
        return new PipelineNode(ACQ, BuiltinNodeType.ACQUISITION.type(),
                null, "Collector: " + src.connector(), c, use);
    }

    /**
     * <b>Which node carries marker dedup (P5-a).</b> The acquisition node authors it; a standalone
     * {@code transform.dedup.marker} node is still READ — never emitted — because an editor opened
     * before P5-a holds a lifted graph carrying one, and ignoring it would delete that operator's
     * dedup on their next save. ⚠ The acquisition toggle is authoritative whenever PRESENT, in both
     * directions: an explicit {@code false} must not fall through to a stale node and re-enable it.
     *
     * @return the node whose {@code marker_extension}/{@code retention_days}/{@code markers_dir} apply,
     *         or {@code null} when marker dedup is off.
     */
    static PipelineNode markerHome(PipelineNode acq, PipelineNode legacyMarker) {
        Object toggle = acq == null ? null : acq.cfg("duplicate_check");
        if (toggle == null) return legacyMarker;
        return Boolean.parseBoolean(String.valueOf(toggle)) ? acq : null;
    }

    private static PipelineNode parserNode(PipelineConfig cfg) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("csv", cfg.csv());                 // the whole CsvSettings record (delimiter/skips/formats/…)
        c.put("chunking", cfg.chunking());
        if (cfg.fixedWidth() != null) c.put("fixedwidth", cfg.fixedWidth());
        if (cfg.json() != null) c.put("json", cfg.json());
        if (cfg.textRegex() != null) c.put("text_regex", cfg.textRegex());
        PipelineConfig.Schemas s = cfg.schemas();
        if (s.selector() != null && s.selector().hasSchemas()) {
            c.put("selector", s.selector());     // carries column-count/file_pattern priority (G3)
        } else if (s.segments() != null && !s.segments().isEmpty()) {
            put(c, "ingester", s.ingesterClass());
            c.put("ingester_config", s.ingesterConfig());
            c.put("segments", s.segments());
        } else if (s.single() != null) {
            c.put("schema", s.single());         // raw.fields the parser tokenises against
        }
        String use = (s.ingesterClass() != null) ? "ingester/" + s.ingesterClass() : null;
        return new PipelineNode(PARSE, BuiltinNodeType.PARSER.type(), null, null, c, use);
    }

    /** Build one {@code [filter →] map → sink(s)} chain off {@code parse} via {@code rel}; {@code key} is null for
     *  single-schema. The {@code map} fans out to one {@code sink.persistent} node per
     *  {@link PipelineConfig#sinks() destination} — one for the single-{@code output:} shorthand (byte-for-byte
     *  as before), several when a {@code sinks:} list names multiple destinations. */
    private static void branch(List<PipelineNode> nodes, List<PipelineEdge> edges, String rel, String key,
                               Map<String, Object> schema, String table,
                               PipelineConfig cfg, boolean rowFilters) {
        String suffix = (key == null) ? "" : "_" + key;
        String mapUpstream = PARSE;
        String mapUpstreamRel = rel;

        if (rowFilters) {   // index-anchored CSV row-filter sits between parser and map (G1)
            String filterId = "filter" + suffix;
            nodes.add(new PipelineNode(filterId, BuiltinNodeType.TRANSFORM_FILTER.type(), "Row filter", null, filterConfig(cfg), null));
            edges.add(new PipelineEdge(PARSE, rel, filterId));
            mapUpstream = filterId;
            mapUpstreamRel = PipelineRel.DATA;
        }

        String mapId = "map" + suffix;
        Map<String, Object> mapCfg = new LinkedHashMap<>();
        if (schema != null) mapCfg.put("schema", schema);
        // The authored half (processing.map), so an operator's own projection survives a round-trip
        // instead of being dropped by lower. `schema` above stays lift-DERIVED — PipelineEditable.lower
        // drops that one and lowers only these two. One processing.map serves every branch's map node;
        // lower refuses a graph whose map nodes have drifted apart, since the file cannot express it.
        PipelineConfig.MapConfig authored = cfg.mapConfig();
        if (authored != null) {
            if (!authored.columns().isEmpty()) mapCfg.put("columns", authored.columns());
            if (!authored.rules().isEmpty())   mapCfg.put("rules",   authored.rules());
        }
        String mapName = (table != null && !table.isBlank()) ? "Map " + table : "Map";
        nodes.add(new PipelineNode(mapId, BuiltinNodeType.TRANSFORM_MAP.type(), mapName, null, mapCfg, null));
        edges.add(new PipelineEdge(mapUpstream, mapUpstreamRel, mapId));

        String sinkUpstream = mapId;
        String routeId = null;
        Map<String, Object> routeCfg = cfg.routeConfig();

        // ⚠ An explicit steps: chain is walked; a legacy file keeps the proven emission below.
        //
        // The two produce the same graph for every legacy file — PipelineConfig projects the singular
        // blocks into exactly this order, and PipelineStepsProjectionTest cross-checks that against the
        // emission below rather than against a constant. They are kept apart anyway, because the legacy
        // path is what every pipeline in existence lifts through: collapsing them would put an untested
        // rewrite under all of them to save a dozen lines.
        if (cfg.hasExplicitSteps()) {
            Map<String, Integer> seen = new LinkedHashMap<>();
            List<PipelineConfig.Step> steps = cfg.steps();
            for (int i = 0; i < steps.size(); i++) {
                PipelineConfig.Step step = steps.get(i);
                String kind = step.kind();
                // The first of a kind keeps the bare id the single-slot era gave it, so a chain that
                // happens to hold one of each lifts to byte-for-byte the same node ids as before;
                // repeats are distinguished by step position, which is stable across a round-trip.
                int nth = seen.merge(kind, 1, Integer::sum);
                String id = kind + suffix + (nth == 1 ? "" : "__s" + i);
                Map<String, Object> sc = new LinkedHashMap<>(step.config());
                nodes.add(new PipelineNode(id, "transform." + kind, stepLabel(kind), null,
 sc, null));
                edges.add(PipelineEdge.data(sinkUpstream, id));
                sinkUpstream = id;
                if (PipelineConfig.Step.ROUTE.equals(kind)) { routeId = id; routeCfg = sc; }
            }
            emitSinks(nodes, edges, suffix, schema, table, cfg, sinkUpstream, routeId, routeCfg);
            return;
        }

        // Reference join (processing.join) sits right after map — dedup/summarize downstream see the
        // enriched row set. Authoring-only (prepare() refuses arming), like route/summarize below.
        if (cfg.join() != null) {
            String joinId = "join" + suffix;
            Map<String, Object> jc = new LinkedHashMap<>();
            jc.put("reference", cfg.join().reference());
            jc.put("on", cfg.join().on());
            nodes.add(new PipelineNode(joinId, BuiltinNodeType.TRANSFORM_JOIN.type(),
                    null, null, jc, null));
            edges.add(PipelineEdge.data(sinkUpstream, joinId));
            sinkUpstream = joinId;
        }

        // Record-grain dedup (processing.dedup) sits between map and the sink(s) — exactly where the
        // engine applies its QUALIFY (ConsignmentIngestStrategy.writeAndTrace, before the partitioned write).
        if (cfg.dedup() != null) {
            String dedupId = "dedup" + suffix;
            Map<String, Object> dc = new LinkedHashMap<>();
            dc.put("keys", cfg.dedup().keys());
            put(dc, "order_by", cfg.dedup().orderBy());
            nodes.add(new PipelineNode(dedupId, BuiltinNodeType.TRANSFORM_DEDUP.type(),
                    null, null, dc, null));
            edges.add(PipelineEdge.data(sinkUpstream, dedupId));
            sinkUpstream = dedupId;
        }

        // Group-by rollup (processing.summarize) sits after dedup, before any route — authoring-only
        // (MaterializeTask stays the runtime until a recipe-driven executor lands, Phase 3).
        if (cfg.summarize() != null) {
            String summarizeId = "summarize" + suffix;
            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("group_by", cfg.summarize().groupBy());
            sc.put("measures", cfg.summarize().measures());
            nodes.add(new PipelineNode(summarizeId, BuiltinNodeType.TRANSFORM_SUMMARIZE.type(),
                    null, null, sc, null));
            edges.add(PipelineEdge.data(sinkUpstream, summarizeId));
            sinkUpstream = summarizeId;
        }

        // An authored route: block lifts as a transform.route node whose route:<key> edges feed the
        // sinks — branch↔sink pairing is by the branch's declared destination database. Authoring-only
        // for now (prepare() refuses arming), so this exists for the editor/recipe round-trip.
        if (routeCfg != null) {
            routeId = "route" + suffix;
            nodes.add(new PipelineNode(routeId, BuiltinNodeType.TRANSFORM_ROUTE.type(),
                    null, null, routeCfg, null));
            edges.add(PipelineEdge.data(sinkUpstream, routeId));
            sinkUpstream = routeId;
        }

        emitSinks(nodes, edges, suffix, schema, table, cfg, sinkUpstream, routeId, routeCfg);
    }

    /** The chain's tail: one {@code sink.persistent} per destination, fed by {@code sinkUpstream} (or by
     *  the route node's {@code route:<key>} edge when a branch names that destination's database). */
    private static void emitSinks(List<PipelineNode> nodes, List<PipelineEdge> edges, String suffix,
                                  Map<String, Object> schema, String table, PipelineConfig cfg,
                                  String sinkUpstream, String routeId, Map<String, Object> routeCfg) {
        // The declared data-store this sink produces — the join key a downstream job/enrichment matches
        // its source store against, so the topology superimposes from config/metadata (see PipelineStores).
        // Legacy pipelines only ever write a resting store, so the lift always emits sink.persistent;
        // sink.materialized / sink.view are authored-only (new capability, doc §3.1).
        String store = (table != null && !table.isBlank())
                ? table : canonicalName(schema, cfg.identity().pipelineName());
        // Fan out: one persistent sink per destination, each fed by a data edge off the map. A single
        // destination (the common case) keeps the id `sink<suffix>` so the graph is byte-for-byte unchanged.
        List<PipelineConfig.Sink> sinks = cfg.sinks();
        for (int d = 0; d < sinks.size(); d++) {
            String sinkId = "sink" + suffix + (sinks.size() == 1 ? "" : "__d" + d);
            Map<String, Object> sinkCfg = sinkConfig(sinks.get(d), cfg);
            put(sinkCfg, PipelineStores.CONFIG_STORE, store);
            put(sinkCfg, "table", table);
            if (schema != null) sinkCfg.put("schema", schema);   // partitions derived from it at compile-back
            // The sink's display name is the store it produces — typically a business object/concept (§3.1).
            nodes.add(new PipelineNode(sinkId, BuiltinNodeType.SINK_PERSISTENT.type(), store, "Persistent store", sinkCfg, null));
            String branchKey = routeId == null ? null : branchKeyForDatabase(routeCfg, sinks.get(d).database());
            if (branchKey == null) {
                edges.add(PipelineEdge.data(sinkUpstream, sinkId));
                continue;
            }
            // MIDBRANCH-1 (R3), the wiki's flattening pre-pass: a branch's steps[] sub-chain expands
            // here into ordinary transform nodes wired route:<key> → step₁ → … → branch sink, so the
            // executor and the graph consumers keep a flat DAG and never learn the concept.
            // PipelineEditable.lower reverses it (chain nodes between a route:<key> edge and that
            // branch's sink write back into the branch's steps[] in order). ⚠ The suffix grammar is
            // decided ONCE, here: `<kind><schema-suffix>__<key>` for the first of a kind in a branch,
            // `…__s<i>` appended for repeats (i = position in the branch chain) — the TS mirror
            // (pipeline-editable.ts) must emit byte-identical ids. A branch with no steps keeps the
            // pre-R3 direct edge, byte-for-byte.
            List<PipelineConfig.Step> branchSteps =
                    PipelineConfig.Step.branchSteps(branchEntryForKey(routeCfg, branchKey));
            String upstream = routeId;
            String rel = PipelineRel.route(branchKey);
            Map<String, Integer> seen = new LinkedHashMap<>();
            for (int i = 0; i < branchSteps.size(); i++) {
                PipelineConfig.Step step = branchSteps.get(i);
                String kind = step.kind();
                int nth = seen.merge(kind, 1, Integer::sum);
                String id = kind + suffix + "__" + branchKey + (nth == 1 ? "" : "__s" + i);
                nodes.add(new PipelineNode(id, "transform." + kind, stepLabel(kind), null,
                        new LinkedHashMap<>(step.config()), null));
                edges.add(new PipelineEdge(upstream, rel, id));
                upstream = id;
                rel = PipelineRel.DATA;
            }
            edges.add(new PipelineEdge(upstream, rel, sinkId));
        }
    }

    /** The {@code route.branches[]} entry whose {@code key} is {@code key}, or {@code null}. */
    private static Map<?, ?> branchEntryForKey(Map<String, Object> routeCfg, String key) {
        if (routeCfg == null || !(routeCfg.get("branches") instanceof List<?> branches)) return null;
        for (Object b : branches)
            if (b instanceof Map<?, ?> m && key.equals(String.valueOf(m.get("key")))) return m;
        return null;
    }

    /** A chain step's display name — {@code null} for every kind whose legacy emission gives it no name
     *  of its own (the type's own served label already captions the card; see {@code stepTitle} /
     *  {@code stepTypeLabel} in {@code pipeline-step-cards.component.ts}), except {@code filter}, whose
     *  legacy name ("Row filter") is more specific than its type's label ("Filter") and is kept. */
    private static String stepLabel(String kind) {
        return PipelineConfig.Step.FILTER.equals(kind) ? "Row filter" : null;
    }

    /** The route branch key whose declared {@code database} matches {@code database}, or {@code null}. */
    private static String branchKeyForDatabase(Map<String, Object> routeCfg, String database) {
        if (routeCfg == null || database == null) return null;
        List<RouteBranch> branches = RouteBranch.listFrom(routeCfg);
        if (branches == null) return null;
        for (RouteBranch b : branches)
            if (b != null && database.equals(b.database()) && b.key() != null) return b.key();
        return null;
    }

    private static void addQuarantine(List<PipelineNode> nodes, List<PipelineEdge> edges, PipelineConfig cfg) {
        Map<String, Object> c = new LinkedHashMap<>();
        put(c, "dir", cfg.dirs().quarantine());
        nodes.add(new PipelineNode(QUARANTINE, BuiltinNodeType.SINK_PERSISTENT.type(), "Quarantine", "Unmatched files", c, null));
        edges.add(new PipelineEdge(PARSE, PipelineRel.UNMATCHED, QUARANTINE));
    }

    /** Per-destination sink config: the {@link PipelineConfig.Sink}'s format/compression/ducklake/database plus
     *  the pipeline-wide backup/temp/batch/thread settings shared by every destination. For the single-{@code
     *  output:} shorthand ({@code sinks().get(0)}) this reproduces the former {@code sinkBaseConfig} exactly. */
    private static Map<String, Object> sinkConfig(PipelineConfig.Sink dest, PipelineConfig cfg) {
        Map<String, Object> c = new LinkedHashMap<>();
        put(c, "format", dest.format());
        put(c, "compression", dest.compression());
        if (dest.duckLake() != null) c.put("ducklake", dest.duckLake());
        put(c, "database", dest.database());
        put(c, "backup", cfg.dirs().backup());
        put(c, "temp", cfg.dirs().temp());
        c.put("batch_max_files", cfg.processing().batchMaxFiles());
        c.put("batch_max_bytes", cfg.processing().batchMaxBytes());
        c.put("threads", cfg.processing().threads());
        c.put("duckdb_threads", cfg.processing().duckdbThreads());
        c.put("duckdb", cfg.duckdb());
        return c;
    }

    private static Map<String, Object> filterConfig(PipelineConfig cfg) {
        var csv = cfg.csv();
        Map<String, Object> c = new LinkedHashMap<>();
        // only meaningful as the index the pre-parse lists anchor on — emitting it for a
        // predicate-only pipeline would have lower write a key the original file never had.
        if (csv.hasRowFilters()) c.put("filter_target_column", csv.filterTargetColumn());
        if (!csv.includePrefixes().isEmpty()) c.put("include_prefixes", csv.includePrefixes());
        if (!csv.includeRegex().isEmpty())    c.put("include_regex", csv.includeRegex());
        if (!csv.excludePrefixes().isEmpty()) c.put("exclude_prefixes", csv.excludePrefixes());
        if (!csv.excludeRegex().isEmpty())    c.put("exclude_regex", csv.excludeRegex());
        // post-parse predicate: surfaced on the same node so the editor can read AND re-save it
        // (lower copies this node's cfg wholesale back into csv_settings). Omitting it here would
        // silently drop an authored `where` the first time a pipeline is opened and saved.
        if (csv.hasRowPredicate())            c.put("where", csv.where());
        return c;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** The schema's {@code mapping.canonicalName} (the single-schema store name), or {@code fallback}. */
    private static String canonicalName(Map<String, Object> schema, String fallback) {
        if (schema != null && schema.get("mapping") instanceof Map<?, ?> mapping) {
            Object cn = mapping.get("canonicalName");
            if (cn != null && !cn.toString().isBlank()) return cn.toString();
        }
        return fallback;
    }

    /** Sanitise a table/segment name into a route-branch key; fall back to {@code schema_<i>}. */
    static String routeKey(String name, int i) {
        if (name == null || name.isBlank()) return "schema_" + i;
        String k = name.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return k.isEmpty() ? "schema_" + i : k;
    }

    /** Put a value only if non-null (config maps must not carry null values — {@link Map#copyOf}). */
    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) m.put(key, value);
    }
}
