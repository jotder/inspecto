package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The <b>editable</b> lift/lower pair (W5, plan U-A): the graph editor's round-trip over the
 * canonical {@code *_pipeline.toon}. Unlike {@link PipelineCompiler#toConfigMap} (the Phase-1
 * parity gate, which consumes the <em>typed</em> records a {@link PipelineLift} carries), this pair
 * speaks the <b>config-file vocabulary end to end</b>: node config values are the raw map sections
 * exactly as the file spells them, so the HTTP boundary never sees a Java record and every section
 * travels <b>verbatim</b> — including keys the graph does not model ({@code description},
 * {@code produces}, {@code dirs.status_dir}, …), which {@link #lower} preserves from the existing
 * file rather than dropping.
 *
 * <p><b>Ownership rule:</b> a node that is present in the graph owns its config section wholesale
 * (cleared field ⇒ deleted key); a node kind that is absent has its section removed in strict mode
 * (the graph is the truth for an {@code active} pipeline) and left untouched in lenient mode (an
 * inactive draft may simply not have authored it yet). {@code enrichment} nodes are ignored by
 * {@link #lower} — their truth is the registered {@code *_enrich.toon} companion (W4b), never a
 * mirror in the pipeline file.
 */
@PublicApi(since = "4.7.0")
public final class PipelineEditable {

    private PipelineEditable() {}

    // ── refusal codes (stable; the UI renders them next to the offending node) ──────
    public static final String UNSUPPORTED_NODE = "UNSUPPORTED_NODE";
    public static final String MULTI_SINK = "MULTI_SINK";
    public static final String NO_ACQUISITION = "NO_ACQUISITION";
    public static final String NO_PARSER = "NO_PARSER";
    public static final String NO_PERSISTENT_SINK = "NO_PERSISTENT_SINK";
    public static final String PARSER_NO_SCHEMA = "PARSER_NO_SCHEMA";

    /** Node types the flat config has a home for; everything else refuses with UNSUPPORTED_NODE. */
    private static final Set<String> LOWERABLE = Set.of(
            BuiltinNodeType.ACQUISITION.type(), BuiltinNodeType.PARSER.type(), BuiltinNodeType.GAP.type(),
            BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type(),
            BuiltinNodeType.TRANSFORM_DEDUP.type(),   // record-grain dedup → processing.dedup (ELT P2)
            BuiltinNodeType.TRANSFORM_ROUTE.type(),   // route: block — authoring-only until the executor lands
            BuiltinNodeType.TRANSFORM_FILTER.type(), BuiltinNodeType.TRANSFORM_MAP.type(),
            BuiltinNodeType.SINK_PERSISTENT.type(), BuiltinNodeType.ENRICHMENT.type());

    /**
     * Whether a save can lower this node type back to the flat config. The palette reads this so a
     * type the editor cannot persist is greyed out up front, instead of the user discovering it at
     * Save via an {@link #UNSUPPORTED_NODE} refusal.
     */
    public static boolean isLowerable(String type) {
        return LOWERABLE.contains(type);
    }

    /**
     * Collector-block keys owned by the gap node, not the acquisition node. {@code duplicate} and
     * {@code incremental} left this set 2026-08-04 when the fingerprint-dedup node was folded into
     * acquisition — they execute in the poll cycle, so acquisition is where they are authored.
     */
    private static final Set<String> NOT_ACQ_OWNED = Set.of("gap_detection");

    /**
     * Parser-owned <b>processing</b> keys (schema resolution + the parse frontend). The parser also
     * owns the top-level {@code parsing:} block, which is NOT listed here because it is not a
     * {@code processing:} key — it is carried verbatim under its own node-config key.
     */
    private static final Set<String> PARSER_OWNED = Set.of(
            "csv_settings", "schema_file", "mapping_file", "schemas", "segments", "ingester", "ingester_config");

    /** The registry-reference prefix a Grammar-bound parser node carries on {@code use:}. */
    static final String GRAMMAR_REF_PREFIX = "grammar/";

    /** Sink-owned processing keys (batch/write tuning carried on the persistent sink node). */
    private static final Set<String> SINK_PROC_OWNED = Set.of(
            "threads", "duckdb_threads", "batch_max_files", "batch_max_bytes");

    // ═════════════════════════════ editable lift ═════════════════════════════

    /**
     * Lift {@code cfg} for topology via {@link PipelineLift}, then swap each node's config for the
     * <b>verbatim raw-map section</b> it owns (from {@code raw}, the decoded file). The result is a
     * {@code .toon}/JSON-friendly {@code {name, active, nodes[], edges[]}} map — the codec shape the
     * editor already speaks — with no typed records inside.
     */
    public static Map<String, Object> toMap(PipelineConfig cfg, Map<String, Object> raw) {
        PipelineGraph g = PipelineLift.lift(cfg);
        Map<String, Object> collector = section(raw, collectorKey(raw));
        Map<String, Object> dirs = section(raw, "dirs");
        Map<String, Object> output = section(raw, "output");
        Map<String, Object> processing = section(raw, "processing");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", g.name());
        m.put("active", g.active());

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (PipelineNode n : g.nodes()) {
            Map<String, Object> nm = new LinkedHashMap<>();
            nm.put("id", n.id());
            nm.put("type", n.type());
            if (n.hasName()) nm.put("name", n.name());
            if (n.description() != null && !n.description().isBlank()) nm.put("description", n.description());
            if (n.hasUse()) nm.put("use", n.use());
            else if (BuiltinNodeType.PARSER.type().equals(n.type())
                    && section(raw, "parsing").get("grammar") instanceof String ref)
                // A parser bound to a reusable Grammar presents that binding as use:, mirroring
                // connection/ on acquisition. Never clobbers an existing use: — a plugin parser's
                // synthesized ingester/<fqcn> binding is a different thing and stays.
                nm.put("use", ref);
            Map<String, Object> c = editableConfig(n, raw, collector, dirs, output, processing);
            if (!c.isEmpty()) nm.put("config", c);
            nodes.add(nm);
        }
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

    /** The raw-vocabulary config section a lifted node owns (verbatim from the file). */
    private static Map<String, Object> editableConfig(PipelineNode n, Map<String, Object> raw,
                                                      Map<String, Object> collector, Map<String, Object> dirs,
                                                      Map<String, Object> output, Map<String, Object> processing) {
        Map<String, Object> c = new LinkedHashMap<>();
        String t = n.type();
        if (BuiltinNodeType.ACQUISITION.type().equals(t)) {
            for (Map.Entry<String, Object> e : collector.entrySet())
                if (!NOT_ACQ_OWNED.contains(e.getKey())) c.put(e.getKey(), e.getValue());
            // connection is carried on use: ("connection/<name>"), never mirrored in config
            c.remove("connection");
            putIfPresent(c, "poll", dirs.get("poll"));
            putIfPresent(c, "trigger", raw.get("trigger"));
            putIfPresent(c, "file_pattern", processing.get("file_pattern"));
        } else if (BuiltinNodeType.PARSER.type().equals(t)) {
            for (String k : PARSER_OWNED) putIfPresent(c, k, processing.get(k));
            // The unified top-level parsing: block is parser-owned too, carried VERBATIM under its own
            // key rather than flattened into the legacy ones. It is not a second spelling the editor may
            // ignore: PipelineConfigParser overlays parsing: OVER processing.csv_settings, so a parser
            // node that could not see it would edit the losing key and the operator's change would be
            // silently masked by the block Onboarding wrote.
            putIfPresent(c, "parsing", raw.get("parsing"));
            // parsing.grammar is carried on use: instead — the same edit-time presentation the
            // acquisition node gives connection/ — so the editor shows a bound Grammar as a binding,
            // not as a free-text key the operator could corrupt.
            if (c.get("parsing") instanceof Map<?, ?> pb && pb.get("grammar") != null) {
                Map<String, Object> stripped = section(raw, "parsing");
                stripped.remove("grammar");
                if (stripped.isEmpty()) c.remove("parsing");
                else c.put("parsing", stripped);
            }
        } else if (BuiltinNodeType.GAP.type().equals(t)) {
            if (collector.get("gap_detection") instanceof Map<?, ?> gd)
                for (Map.Entry<?, ?> e : gd.entrySet())
                    if (!"enabled".equals(e.getKey())) c.put(String.valueOf(e.getKey()), e.getValue());
        } else if (BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type().equals(t)) {
            if (processing.get("duplicate_check") instanceof Map<?, ?> dc) {
                putIfPresent(c, "marker_extension", dc.get("marker_extension"));
                putIfPresent(c, "retention_days", dc.get("retention_days"));
            }
            putIfPresent(c, "markers_dir", dirs.get("markers"));
        } else if (BuiltinNodeType.SINK_PERSISTENT.type().equals(t)) {
            if (isQuarantine(n)) {
                putIfPresent(c, "dir", dirs.get("quarantine"));
            } else {
                putIfPresent(c, PipelineStores.CONFIG_STORE, n.cfg(PipelineStores.CONFIG_STORE));
                putIfPresent(c, "table", n.cfg("table"));
                putIfPresent(c, "format", output.get("format"));
                putIfPresent(c, "compression", output.get("compression"));
                putIfPresent(c, "ducklake", output.get("ducklake"));
                putIfPresent(c, "database", dirs.get("database"));
                putIfPresent(c, "backup", dirs.get("backup"));
                putIfPresent(c, "temp", dirs.get("temp"));
                for (String k : SINK_PROC_OWNED) putIfPresent(c, k, processing.get(k));
            }
        } else {
            // filter / map: the lifted config is already plain (derived views; lower ignores map,
            // and merges filter's row-filter keys back into csv_settings)
            c.putAll(n.config());
        }
        return c;
    }

    // ═════════════════════════════ editable lower ═════════════════════════════

    /**
     * Lower {@code g} (node configs in raw-map vocabulary) back over {@code existing} (the decoded
     * file, or an empty map for a brand-new pipeline). Present nodes replace their sections
     * wholesale; keys the graph does not model are preserved. {@code strict} (an {@code active}
     * save, or a create) additionally requires completeness and removes sections whose owning node
     * kind is absent; lenient (an inactive draft) leaves those untouched.
     *
     * @throws PipelineCompileException with every named {@link PipelineCompileException.Refusal}
     *         when the graph cannot be represented as a flat config
     */
    public static Map<String, Object> lower(PipelineGraph g, Map<String, Object> existing, boolean strict) {
        List<PipelineCompileException.Refusal> refusals = new ArrayList<>();

        PipelineNode acq = null, parser = null, gap = null, marker = null;
        PipelineNode recordDedup = null, routeNode = null;
        PipelineNode primarySink = null, quarantineSink = null;
        List<PipelineNode> filters = new ArrayList<>();
        // Distinct output destinations keyed by database dir (order-preserving). One ⇒ the single
        // output:/dirs.database shorthand; more than one ⇒ a plural sinks: block (slice 4).
        LinkedHashMap<String, PipelineNode> destByDatabase = new LinkedHashMap<>();
        for (PipelineNode n : g.nodes()) {
            String t = n.type();
            if (!LOWERABLE.contains(t)) {
                refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_NODE, n.id(),
                        "the flat pipeline config has no home for a '" + t + "' node"));
                continue;
            }
            if (BuiltinNodeType.ACQUISITION.type().equals(t)) acq = n;
            else if (BuiltinNodeType.PARSER.type().equals(t)) parser = n;
            else if (BuiltinNodeType.GAP.type().equals(t)) gap = n;
            else if (BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type().equals(t)) marker = n;
            else if (BuiltinNodeType.TRANSFORM_DEDUP.type().equals(t)) recordDedup = n;
            else if (BuiltinNodeType.TRANSFORM_ROUTE.type().equals(t)) routeNode = n;
            else if (BuiltinNodeType.TRANSFORM_FILTER.type().equals(t)) filters.add(n);
            else if (BuiltinNodeType.SINK_PERSISTENT.type().equals(t)) {
                if (isQuarantine(n)) {
                    quarantineSink = n;
                } else {
                    if (n.cfg("database") != null) destByDatabase.putIfAbsent(String.valueOf(n.cfg("database")), n);
                    if (primarySink == null || (primarySink.cfg("database") == null && n.cfg("database") != null))
                        primarySink = n;
                }
            }
            // transform.map + enrichment: derived / companion-persisted — nothing to lower
        }
        // >1 distinct database is no longer a refusal — it lowers to a plural sinks: block (slice 4).
        // Row-routing to distinct destinations can't reach here: a transform.route/derive node is not
        // LOWERABLE, so it already fails UNSUPPORTED_NODE above; every sink here is a data/schema-dispatch
        // fan-out, which sinks: (replicate-per-destination) represents faithfully.

        if (strict) {
            if (acq == null) refusals.add(new PipelineCompileException.Refusal(NO_ACQUISITION, null,
                    "an active pipeline needs an acquisition node"));
            if (parser == null) refusals.add(new PipelineCompileException.Refusal(NO_PARSER, null,
                    "an active pipeline needs a parser node"));
            if (primarySink == null || primarySink.cfg("database") == null)
                refusals.add(new PipelineCompileException.Refusal(NO_PERSISTENT_SINK, null,
                        "an active pipeline needs a persistent sink with a database dir"));
            final PipelineNode p = parser;
            boolean grammarBound = p != null && p.use() != null && p.use().startsWith(GRAMMAR_REF_PREFIX);
            if (p != null && !grammarBound && p.cfg("parsing") == null
                    && PARSER_OWNED.stream().noneMatch(k -> p.cfg(k) != null))
                refusals.add(new PipelineCompileException.Refusal(PARSER_NO_SCHEMA, p.id(),
                        "the parser names no Grammar / parsing: block / schema_file / schemas / segments"));
        }
        if (!refusals.isEmpty()) throw new PipelineCompileException(refusals);

        Map<String, Object> out = deepCopy(existing);
        // the lift carries the parser-NORMALISED identity (lower-case); keep the file's original
        // spelling when it is the same name, so the round-trip stays verbatim
        if (!(out.get("name") instanceof String n && n.equalsIgnoreCase(g.name()))) out.put("name", g.name());
        out.put("active", g.active());
        String colKey = collectorKey(out);
        Map<String, Object> collector = getOrNew(out, colKey);
        Map<String, Object> dirs = getOrNew(out, "dirs");
        Map<String, Object> output = getOrNew(out, "output");
        Map<String, Object> processing = getOrNew(out, "processing");

        if (acq != null) {
            collector.keySet().removeIf(k -> !NOT_ACQ_OWNED.contains(k));
            for (Map.Entry<String, Object> e : acq.config().entrySet())
                if (!Set.of("poll", "trigger", "file_pattern").contains(e.getKey()))
                    collector.put(e.getKey(), e.getValue());
            collector.remove("connection");
            if (acq.use() != null && acq.use().startsWith("connection/"))
                collector.put("connection", acq.use().substring("connection/".length()));
            replaceOrRemove(dirs, "poll", acq.cfg("poll"));
            replaceOrRemove(out, "trigger", acq.cfg("trigger"));
            replaceOrRemove(processing, "file_pattern", acq.cfg("file_pattern"));
        }
        overlayOwned(collector, "gap_detection", gap == null ? null : gapSection(gap), strict);

        // record-grain dedup → processing.dedup ({keys, order_by} — the QUALIFY the engine applies)
        if (recordDedup != null) {
            Map<String, Object> dd = new LinkedHashMap<>();
            putIfPresent(dd, "keys", recordDedup.cfg("keys"));
            putIfPresent(dd, "order_by", recordDedup.cfg("order_by"));
            processing.put("dedup", dd);
        } else if (strict) {
            processing.remove("dedup");
        }

        // route: block — node config verbatim, each branch stamped with the destination database its
        // route:<key> edge feeds, so the flat file (which has no edges) keeps the branch↔sink pairing.
        if (routeNode != null) {
            out.put("route", routeSection(g, routeNode));
        } else if (strict) {
            out.remove("route");
        }

        if (marker != null) {
            Map<String, Object> dc = new LinkedHashMap<>();
            dc.put("enabled", true);
            putIfPresent(dc, "marker_extension", marker.cfg("marker_extension"));
            putIfPresent(dc, "retention_days", marker.cfg("retention_days"));
            processing.put("duplicate_check", dc);
            replaceOrRemove(dirs, "markers", marker.cfg("markers_dir"));
        } else if (strict) {
            processing.remove("duplicate_check");
            dirs.remove("markers");
        }

        if (parser != null) {
            processing.keySet().removeAll(PARSER_OWNED);
            for (String k : PARSER_OWNED) putIfPresent(processing, k, parser.cfg(k));
            // …and the top-level parsing: block it owns (see editableConfig). Absent from the node ⇒
            // absent from the file, but only in strict mode: a partial merge must not drop a block it
            // was never given.
            overlayOwned(out, "parsing", parser.cfg("parsing"), strict);
            // A Grammar-bound parser carries the reusable component on use: — the presentation half,
            // exactly like connection/ on acquisition. It lowers into parsing.grammar, which the engine
            // resolves to the registry file. Without this the binding rides the graph model and is
            // silently dropped on the way to disk.
            Map<String, Object> parsingBlock = section(out, "parsing");
            if (parser.use() != null && parser.use().startsWith(GRAMMAR_REF_PREFIX))
                parsingBlock.put("grammar", parser.use());
            else if (strict) parsingBlock.remove("grammar");   // unbound in the editor ⇒ unbound on disk
            if (!parsingBlock.isEmpty()) out.put("parsing", parsingBlock);
            else out.remove("parsing");
            if (!filters.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> csv = (Map<String, Object>)
                        processing.computeIfAbsent("csv_settings", k -> new LinkedHashMap<String, Object>());
                for (PipelineNode f : filters) csv.putAll(f.config());
            }
        }

        if (primarySink != null) {
            output.clear();
            putIfPresent(output, "format", primarySink.cfg("format"));
            putIfPresent(output, "compression", primarySink.cfg("compression"));
            putIfPresent(output, "ducklake", primarySink.cfg("ducklake"));
            replaceOrRemove(dirs, "database", primarySink.cfg("database"));
            replaceOrRemove(dirs, "backup", primarySink.cfg("backup"));
            replaceOrRemove(dirs, "temp", primarySink.cfg("temp"));
            for (String k : SINK_PROC_OWNED) replaceOrRemove(processing, k, primarySink.cfg(k));
        }
        // Multi-destination: emit a plural sinks: list of the distinct destinations (the single output:/
        // dirs.database above stays the shorthand + parser fallback, consistent with the first destination).
        // One destination ⇒ no sinks: block, so a single-output pipeline round-trips verbatim.
        if (destByDatabase.size() > 1) {
            List<Map<String, Object>> sinks = new ArrayList<>();
            for (PipelineNode s : destByDatabase.values()) {
                Map<String, Object> sink = new LinkedHashMap<>();
                sink.put("database", s.cfg("database"));
                putIfPresent(sink, "format", s.cfg("format"));
                putIfPresent(sink, "compression", s.cfg("compression"));
                putIfPresent(sink, "ducklake", s.cfg("ducklake"));
                sinks.add(sink);
            }
            out.put("sinks", sinks);
        } else {
            out.remove("sinks");
        }
        // dirs.quarantine: replaced when a quarantine node exists, otherwise PRESERVED even in strict
        // mode — the lift only models a quarantine node for selector/segments pipelines, so a
        // single-schema pipeline's quarantine dir has no owning node and must not be dropped.
        if (quarantineSink != null) replaceOrRemove(dirs, "quarantine", quarantineSink.cfg("dir"));

        if (collector.isEmpty()) out.remove(colKey);
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * The {@code route:} section for {@code routeNode}: its config deep-copied, with each branch entry's
     * {@code database} stamped from the sink its {@code route:<key>} edge feeds (edges don't survive the
     * flat file, the stamped database is what {@code PipelineLift} pairs branches back with).
     */
    private static Map<String, Object> routeSection(PipelineGraph g, PipelineNode routeNode) {
        Map<String, Object> rc = deepCopy(routeNode.config());
        if (!(rc.get("branches") instanceof List<?> branches)) return rc;
        Map<String, PipelineNode> byId = new LinkedHashMap<>();
        for (PipelineNode n : g.nodes()) byId.put(n.id(), n);
        for (PipelineEdge e : g.edges()) {
            if (!e.from().equals(routeNode.id()) || !PipelineRel.isRoute(e.rel())) continue;
            String key = PipelineRel.routeKey(e.rel());
            PipelineNode sink = byId.get(e.to());
            if (sink == null || sink.cfg("database") == null) continue;
            for (Object b : branches)
                if (b instanceof Map<?, ?> m && key.equals(String.valueOf(m.get("key")))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) m;
                    mm.put("database", sink.cfg("database"));
                }
        }
        return rc;
    }

    /** Legacy files may spell the block {@code source:}; write back whichever key the file uses. */
    private static String collectorKey(Map<String, Object> raw) {
        return raw.containsKey("source") && !raw.containsKey("collector") ? "source" : "collector";
    }

    /** A quarantine sink is a persistent sink writing unmatched FILES to a dir, not batches to a database. */
    private static boolean isQuarantine(PipelineNode n) {
        return n.cfg("dir") != null && n.cfg("database") == null;
    }

    private static Map<String, Object> gapSection(PipelineNode gap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.putAll(gap.config());
        return m;
    }

    /** Present node ⇒ section replaced; absent ⇒ removed in strict mode, untouched in lenient. */
    private static void overlayOwned(Map<String, Object> section, String key, Object value, boolean strict) {
        if (value != null) section.put(key, value);
        else if (strict) section.remove(key);
    }

    /** The owning node IS present: its config is the whole truth — a missing value deletes the key. */
    private static void replaceOrRemove(Map<String, Object> section, String key, Object value) {
        if (value != null) section.put(key, value);
        else section.remove(key);
    }

    private static Map<String, Object> section(Map<String, Object> raw, String key) {
        if (raw.get(key) instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
            return copy;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getOrNew(Map<String, Object> out, String key) {
        Object v = out.get(key);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        Map<String, Object> fresh = new LinkedHashMap<>();
        out.put(key, fresh);
        return fresh;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) copy.put(e.getKey(), deepCopyValue(e.getValue()));
        return copy;
    }

    private static Object deepCopyValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) copy.put(String.valueOf(e.getKey()), deepCopyValue(e.getValue()));
            return copy;
        }
        if (v instanceof List<?> l) {
            List<Object> copy = new ArrayList<>();
            for (Object o : l) copy.add(deepCopyValue(o));
            return copy;
        }
        return v;
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object v) {
        if (v != null) m.put(key, v);
    }
}
