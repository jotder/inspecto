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
@PublicApi(since = "4.3.0")
public final class PipelineLift {

    private PipelineLift() {}

    // ── stable node ids ──────────────────────────────────────────────────────────
    static final String ACQ               = "acq";
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

        // 2. dedup prefix (marker subsystem only — fingerprint dedup rides the acquisition node,
        //    where it actually executes), feeding the parser
        String upstream = ACQ;
        if (cfg.processing().duplicateCheckEnabled()) {
            nodes.add(dedupMarkerNode(cfg));
            edges.add(PipelineEdge.data(upstream, DEDUP_MARKER));
            upstream = DEDUP_MARKER;
        }

        // 3. parser, fed by the dedup prefix (or directly by acq)
        nodes.add(parserNode(cfg));
        edges.add(PipelineEdge.data(upstream, PARSE));

        // 4. branch on schema resolution (exactly one of the three is set)
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

        return new PipelineGraph(cfg.identity().pipelineName(), cfg.active(), nodes, edges);
    }

    // ── node builders ──────────────────────────────────────────────────────────

    private static PipelineNode acquisitionNode(PipelineConfig cfg) {
        PipelineConfig.Collector src = cfg.collector();
        Map<String, Object> c = new LinkedHashMap<>();
        put(c, "connector", src.connector());
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
        String use = src.hasConnection() ? "connection/" + src.connection() : null;
        return new PipelineNode(ACQ, BuiltinNodeType.ACQUISITION.type(),
                "Collect", "Collector: " + src.connector(), c, use);
    }

    private static PipelineNode dedupMarkerNode(PipelineConfig cfg) {
        Map<String, Object> c = new LinkedHashMap<>();
        put(c, "marker_extension", cfg.processing().markerExtension());
        c.put("retention_days", cfg.processing().retentionDays());
        put(c, "markers_dir", cfg.dirs().markers());
        return new PipelineNode(DEDUP_MARKER, BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type(),
                "Dedup (marker)", null, c, null);
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
        return new PipelineNode(PARSE, BuiltinNodeType.PARSER.type(), "Parser", null, c, use);
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
                nodes.add(new PipelineNode(id, "transform." + kind, stepLabel(kind), null, sc, null));
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
                    "Join", null, jc, null));
            edges.add(PipelineEdge.data(sinkUpstream, joinId));
            sinkUpstream = joinId;
        }

        // Record-grain dedup (processing.dedup) sits between map and the sink(s) — exactly where the
        // engine applies its QUALIFY (BatchIngestStrategy.writeAndTrace, before the partitioned write).
        if (cfg.dedup() != null) {
            String dedupId = "dedup" + suffix;
            Map<String, Object> dc = new LinkedHashMap<>();
            dc.put("keys", cfg.dedup().keys());
            put(dc, "order_by", cfg.dedup().orderBy());
            nodes.add(new PipelineNode(dedupId, BuiltinNodeType.TRANSFORM_DEDUP.type(),
                    "Dedup (record)", null, dc, null));
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
                    "Summarize", null, sc, null));
            edges.add(PipelineEdge.data(sinkUpstream, summarizeId));
            sinkUpstream = summarizeId;
        }

        // An authored route: block lifts as a transform.route node whose route:<key> edges feed the
        // sinks — branch↔sink pairing is by the branch's declared destination database. Authoring-only
        // for now (prepare() refuses arming), so this exists for the editor/recipe round-trip.
        if (routeCfg != null) {
            routeId = "route" + suffix;
            nodes.add(new PipelineNode(routeId, BuiltinNodeType.TRANSFORM_ROUTE.type(),
                    "Route", null, routeCfg, null));
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
            edges.add(branchKey != null
                    ? new PipelineEdge(routeId, PipelineRel.route(branchKey), sinkId)
                    : PipelineEdge.data(sinkUpstream, sinkId));
        }
    }

    /** A chain step's display name — the same label the legacy emission gives that kind, so a pipeline
     *  reads identically on the canvas whichever spelling its file uses. */
    private static String stepLabel(String kind) {
        return switch (kind) {
            case PipelineConfig.Step.FILTER    -> "Row filter";
            case PipelineConfig.Step.JOIN      -> "Join";
            case PipelineConfig.Step.DEDUP     -> "Dedup (record)";
            case PipelineConfig.Step.SUMMARIZE -> "Summarize";
            case PipelineConfig.Step.ROUTE     -> "Route";
            default -> kind;
        };
    }

    /** The route branch key whose declared {@code database} matches {@code database}, or {@code null}. */
    private static String branchKeyForDatabase(Map<String, Object> routeCfg, String database) {
        if (routeCfg == null || database == null || !(routeCfg.get("branches") instanceof List<?> branches))
            return null;
        for (Object b : branches)
            if (b instanceof Map<?, ?> m && database.equals(m.get("database")) && m.get("key") != null)
                return String.valueOf(m.get("key"));
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
