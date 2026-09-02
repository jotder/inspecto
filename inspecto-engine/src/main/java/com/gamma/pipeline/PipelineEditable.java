package com.gamma.pipeline;

import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import static com.gamma.util.Values.putIfPresent;

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
@PublicApi(since = "4.0.0")
public final class PipelineEditable {

    private PipelineEditable() {}

    // ── refusal codes (stable; the UI renders them next to the offending node) ──────
    public static final String UNSUPPORTED_NODE = "UNSUPPORTED_NODE";
    // ⚠ MULTI_SINK / MULTI_JOIN / MULTI_DEDUP / MULTI_ROUTE / MULTI_SUMMARIZE are all gone. MULTI_JOIN /
    // MULTI_DEDUP / MULTI_ROUTE / MULTI_SUMMARIZE went in the multiplicity plan's slice A3; MULTI_SINK
    // stopped firing when `sinks:` became a plural block (sinks-config-format slice 4) and lingered as an
    // unreachable constant until the pipeline spec's Wave 0 deleted it — a constant no code path can emit
    // reads to the UI, and to the next author, as a refusal that still exists.
    // None was ever the destination — they made a silent discard VISIBLE while the flat file still had
    // exactly one slot per kind (`2cf7005e`, after `6e4d4be0` measured the loss). The file can now hold an
    // ordered `steps:` chain, so the thing they were protecting no longer exists, and each code went in the
    // same change that widened the format — never before it, which would have restored the silent discard
    // it replaced.
    public static final String NO_ACQUISITION = "NO_ACQUISITION";
    public static final String NO_PARSER = "NO_PARSER";
    public static final String NO_PERSISTENT_SINK = "NO_PERSISTENT_SINK";
    public static final String PARSER_NO_SCHEMA = "PARSER_NO_SCHEMA";
    public static final String UNSUPPORTED_BINDING = "UNSUPPORTED_BINDING";
    /** A map node carries a key that is neither lift-derived nor executable — see {@link #MAP_AUTHORED}. */
    public static final String UNSUPPORTED_MAP_KEY = "UNSUPPORTED_MAP_KEY";
    /** An authored {@code columns} list alongside an explicitly declared {@code processing.mapping_file}. */
    public static final String MAPPING_CONFLICT = "MAPPING_CONFLICT";
    /** Two map nodes whose authored config has drifted apart — one {@code processing.map} cannot hold both. */
    public static final String MULTI_MAP_CONFIG = "MULTI_MAP_CONFIG";
    /**
     * Two parser-family nodes in one graph. The flat file has exactly one parse slot; before the parser
     * became a family (B6, {@code parser.delimited}) a second node was last-one-wins, a silent discard
     * exactly like the losses the MULTI_* codes above once refused — with two palette icons it is now
     * an authorable state, so it refuses by name.
     */
    public static final String MULTI_PARSER = "MULTI_PARSER";
    /** A {@code parser.delimited} node whose {@code parsing.frontend} names a DIFFERENT frontend. */
    public static final String PARSER_FRONTEND_MISMATCH = "PARSER_FRONTEND_MISMATCH";
    /**
     * A route branch's flattened sub-chain has a shape {@code route.branches[].steps[]} cannot hold
     * (MIDBRANCH-1): a chain node that fans out, a chain ending anywhere but a persistent sink, or a
     * nested {@code transform.route}. The flat file stores a branch chain as an ordered LIST between
     * the {@code route:<key>} edge and that branch's one sink — anything else refuses by name here
     * rather than lowering a topology the next lift cannot reproduce.
     */
    public static final String UNSUPPORTED_BRANCH_STEP = "UNSUPPORTED_BRANCH_STEP";

    /**
     * The map-node config keys an author owns — they lower to {@code processing.map} verbatim and lift
     * back. ⚠ Load-bearing: this set must equal what {@code RowShaper} actually <b>executes</b>, not what
     * the (free-form) map dialog can type, and {@code MapNodeKeyContractTest} pins the two together.
     * A new executable key joins this set in the same change that makes it executable.
     */
    static final Set<String> MAP_AUTHORED = Set.of("columns", "rules");

    /**
     * Map-node config keys that are DERIVED, not authored: they are put on the node by the read side and
     * are never lowered. {@code schema} is the legacy config's schema map, carried wholesale by
     * {@link PipelineLift} — ⛔ lowering it would write a derived block back as authored config, and
     * refusing it would refuse every existing pipeline's save. {@code csv} is the parser's settings,
     * moved within the map node's reach by {@code PipelineDryRun} so a dry-run graph that comes back
     * round does not refuse.
     */
    static final Set<String> MAP_DERIVED = Set.of("schema", "csv");

    /** Node types the flat config has a home for; everything else refuses with UNSUPPORTED_NODE. */
    private static final Set<String> LOWERABLE = Set.of(
            BuiltinNodeType.ACQUISITION.type(), BuiltinNodeType.PARSER.type(),
            BuiltinNodeType.PARSER_DELIMITED.type(), BuiltinNodeType.PARSER_FIXEDWIDTH.type(),
            BuiltinNodeType.PARSER_ASN1.type(),
            BuiltinNodeType.PARSER_JSON.type(), BuiltinNodeType.PARSER_TEXT_REGEX.type(),
            BuiltinNodeType.PARSER_XLSX.type(),
            BuiltinNodeType.PARSER_PLUGIN.type(),
            BuiltinNodeType.GAP.type(),
            // read-compat only since P5-a: never emitted, still accepted (see PipelineLift.markerHome)
            BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type(),
            BuiltinNodeType.TRANSFORM_DEDUP.type(),   // record-grain dedup → processing.dedup (ELT P2)
            BuiltinNodeType.TRANSFORM_ROUTE.type(),   // route: block — authoring-only until the executor lands
            BuiltinNodeType.TRANSFORM_SUMMARIZE.type(), // group-by rollup → processing.summarize (ELT P3), authoring-only
            BuiltinNodeType.TRANSFORM_JOIN.type(),      // reference join → processing.join (ELT P3 S2), authoring-only
            BuiltinNodeType.TRANSFORM_FILTER.type(), BuiltinNodeType.TRANSFORM_MAP.type(),
            BuiltinNodeType.SINK_PERSISTENT.type(), BuiltinNodeType.ENRICHMENT.type());

    /**
     * Node type → the {@code steps:} kind it lowers to: the five kinds the flat file's transform chain
     * can hold. {@code transform.map} is deliberately absent — the lift emits it as the schema
     * projection between parser and sink, so it never enters the chain, and giving it a {@code steps:}
     * entry would change <b>when {@code steps:} is emitted at all</b> (AUTHOR-1's ⛔).
     *
     * <p>⚠ Do not read that as "a map node is never author-configurable" — an earlier version of this
     * comment did, and it is not true: {@code RowShaper.columnsOf} honours an authored {@code columns}
     * list and {@code mappingSchemaOf} honours authored {@code rules}, and the flat file has a home for
     * an authored mapping in {@code processing.mapping_file}. What the map node has no home for is a
     * {@code use:} component ref — see {@link #unhomedBinding}.
     */
    private static final Map<String, String> STEP_KIND = Map.of(
            BuiltinNodeType.TRANSFORM_FILTER.type(),    PipelineConfig.Step.FILTER,
            BuiltinNodeType.TRANSFORM_JOIN.type(),      PipelineConfig.Step.JOIN,
            BuiltinNodeType.TRANSFORM_DEDUP.type(),     PipelineConfig.Step.DEDUP,
            BuiltinNodeType.TRANSFORM_SUMMARIZE.type(), PipelineConfig.Step.SUMMARIZE,
            BuiltinNodeType.TRANSFORM_ROUTE.type(),     PipelineConfig.Step.ROUTE);

    /**
     * Whether a save can lower this node type back to the flat config. The palette reads this so a
     * type the editor cannot persist is greyed out up front, instead of the user discovering it at
     * Save via an {@link #UNSUPPORTED_NODE} refusal.
     */
    /** The {@code transform.} prefix a chain step's node type carries; its kind is what follows. */
    private static final String TRANSFORM_PREFIX = "transform.";

    /**
     * The {@code steps:} kind a node type lowers to — a built-in's mapped kind, or a <b>contributed</b>
     * node type's own suffix. {@code null} when the type is not a chain step at all.
     *
     * <p>A contributed type needs no entry in {@link #STEP_KIND}: the flat file's chain is a LIST, so a
     * kind it has never seen is representable, and the config travels verbatim
     * ({@code stepConfig}'s default arm). That is the whole reason plugin steps land on this spelling
     * and not on the singular blocks, which have a fixed key each.
     */
    static String stepKindOf(String type) {
        return stepKindOf(type, PipelineNodeTypes.get(type).orElse(null));
    }

    /**
     * As {@link #stepKindOf(String)}, with the descriptor supplied — the seam the test uses, because a
     * CONTRIBUTED descriptor cannot be registered in this build's test scope without entering the served
     * step catalog, which is a committed contract.
     *
     * <p>🔴 <b>CONTRIBUTED only, and that restriction is the load-bearing part.</b> Several built-ins are
     * registered, executable and deliberately NOT authorable — {@code transform.split} / {@code select} /
     * {@code derive} / {@code validate} / {@code merge} are absent from {@link #LOWERABLE} and from
     * {@code RECIPE_VERBS} by decision. Admitting every registered {@code transform.*} would silently
     * reverse all of those and change the two-types-differ invariant {@code PipelineProjectionTest} pins.
     * So a built-in keeps whatever {@link #LOWERABLE} already says about it, and only a type the core
     * does not ship gains the {@code steps:} home.
     */
    static String stepKindOf(String type, PipelineNodeType descriptor) {
        String builtin = STEP_KIND.get(type);
        if (builtin != null) return builtin;
        if (type == null || !type.startsWith(TRANSFORM_PREFIX)) return null;
        // ⚠ Registered AND not a built-in. An unregistered type must keep refusing with UNSUPPORTED_NODE
        // at save — the author is present at that moment, and a graph that saves and then cannot run is
        // exactly the descriptor-only trap the executor seam closed.
        if (descriptor == null || descriptor instanceof BuiltinNodeType) return null;
        return type.substring(TRANSFORM_PREFIX.length());
    }

    public static boolean isLowerable(String type) {
        // A contributed transform type lowers as a `steps:` entry, so the flat file DOES have a home for
        // it — which is what makes a plugin Step authorable rather than merely executable.
        return LOWERABLE.contains(type) || stepKindOf(type) != null;
    }

    /**
     * Types that still LOWER but must never be OFFERED for authoring. Read-compat and save-ability are
     * two different questions, and P5-a made them diverge for the first time: {@code
     * transform.dedup.marker} must keep lowering (an editor opened before the fold holds a graph
     * carrying one) while nothing should ever create another.
     *
     * <p>{@code parser} (the generic, unconfigured type) joined it 2026-08-20: every per-format
     * subtype now exists (delimited/fixedwidth/asn1/json/text_regex/xlsx/plugin), so authoring a new
     * generic node is a dead end the operator would only discover in the drawer/dialog. It still
     * lowers unchanged — the bare-legacy-delimited-implicit path and a dialog-bound
     * {@code use: grammar/<id>} node both still carry this type, and nothing here touches {@code
     * lower()} or the dialog's own routing.
     */
    private static final Set<String> READ_COMPAT_ONLY =
            Set.of(BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type(), BuiltinNodeType.PARSER.type());

    /**
     * Whether the palette may offer this type. ⚠ Not the same as {@link #isLowerable} — filtering the
     * palette on lowerability would force the choice between offering a retired node and refusing to
     * save a graph that legitimately still carries one.
     */
    public static boolean isAuthorable(String type) {
        return isLowerable(type) && !READ_COMPAT_ONLY.contains(type);
    }

    /**
     * Why this node's {@code use:} ref cannot be lowered, or {@code null} when it can.
     *
     * <p>⚠ The editor offers a component picker for <b>every</b> TRANSFORM and SINK node — its bind kind
     * is keyed on the node's category, not its type — so it can write {@code use: transform/<id>} onto a
     * map / filter / join / dedup / summarize / route node, and {@code use: sink/<id>} onto a sink. Until
     * 2026-08-14 {@link #lower} read {@code use:} for exactly the two kinds in {@link #USE_HOME} and
     * dropped every other one <b>silently</b>: the save returned {@code 200 written:true} while the
     * binding never reached the file (AUTHOR-1).
     *
     * <p>The binding is <b>refused, not preserved</b>, because no engine path resolves
     * {@code transform/<id>} or {@code sink/<id>} — keeping it would write a config that loads and then
     * does nothing. That is the exact objection that removed the registry {@code schema} kind in
     * unification W1, and it was only re-admitted once {@code PipelineConfigParser.resolveSchemaRef}
     * made such a ref executable ({@code ComponentStore.WRITABLE_TYPES}). A named refusal tells the
     * author at Save; a silent drop tells them nothing, and an inert file tells them at 3am.
     */
    private static String unhomedBinding(PipelineNode n) {
        if (!n.hasUse()) return null;
        if (isDerivedBinding(n.type(), n.use())) return null;
        List<String> homes = USE_HOME.get(n.type());
        if (homes != null && homes.stream().anyMatch(p -> n.use().startsWith(p))) return null;
        return "the flat pipeline config has no home for a '" + n.use() + "' binding on a '" + n.type()
                + "' node" + (homes == null
                ? " — this node kind carries its settings inline, not as a component reference"
                : "; it accepts " + String.join(" or ", homes));
    }

    /**
     * Collector-block keys owned by the gap node, not the acquisition node. {@code duplicate} and
     * {@code incremental} left this set 2026-08-04 when the fingerprint-dedup node was folded into
     * acquisition — they execute in the poll cycle, so acquisition is where they are authored.
     */
    private static final Set<String> NOT_ACQ_OWNED = Set.of("gap_detection");

    /**
     * Acquisition-node config keys that do <b>not</b> belong to the {@code collector:} block — each is
     * borrowed from another section of the file and written back there by {@code lower}. ⚠ A key homed
     * on this node without being listed here silently leaks into {@code collector:}, where nothing
     * reads it. The marker-dedup four joined 2026-08-04's fingerprint fold in P5-a.
     */
    private static final Set<String> ACQ_FOREIGN_KEYS = Set.of(
            "poll", "trigger", "file_pattern", "unpack",
            "duplicate_check", "marker_extension", "retention_days", "markers_dir");

    /**
     * Parser-owned <b>processing</b> keys (schema resolution + the parse frontend). The parser also
     * owns the top-level {@code parsing:} block, which is NOT listed here because it is not a
     * {@code processing:} key — it is carried verbatim under its own node-config key.
     */
    private static final Set<String> PARSER_OWNED = Set.of(
            "csv_settings", "schema_file", "mapping_file", "schemas", "segments", "ingester", "ingester_config");

    /** The registry-reference prefix a Grammar-bound parser node carries on {@code use:}. */
    static final String GRAMMAR_REF_PREFIX = "grammar/";

    /** The parser family: the generic parser plus every per-format subtype (B6). */
    static boolean isParserType(String t) {
        return BuiltinNodeType.PARSER.type().equals(t) || SUBTYPE_FRONTENDS.containsKey(t);
    }

    /**
     * A per-format parser subtype → every {@code parsing.frontend} spelling that IS that format.
     * Fixed width has two accepted spellings ({@code PipelineConfigParser#parseFixedWidth} reads both),
     * so a node typed {@code parser.fixedwidth} must not be called a contradiction for carrying either;
     * the first entry is the canonical one {@link #lower} stamps back.
     */
    private static final Map<String, List<String>> SUBTYPE_FRONTENDS = Map.of(
            BuiltinNodeType.PARSER_DELIMITED.type(), List.of("delimited"),
            BuiltinNodeType.PARSER_XLSX.type(), List.of("xlsx", "excel"),
            BuiltinNodeType.PARSER_FIXEDWIDTH.type(), List.of("fixedwidth", "fixed_width"),
            BuiltinNodeType.PARSER_ASN1.type(), List.of("asn1"),
            BuiltinNodeType.PARSER_JSON.type(), List.of("json"),
            BuiltinNodeType.PARSER_TEXT_REGEX.type(), List.of("text_regex"),
            BuiltinNodeType.PARSER_PLUGIN.type(), List.of("plugin"));

    /** The node subtype a {@code parsing.frontend} value names, or {@code null} for none/unknown. */
    private static String subtypeForFrontend(String frontend) {
        String f = frontend.trim().toLowerCase();
        return SUBTYPE_FRONTENDS.entrySet().stream()
                .filter(e -> e.getValue().contains(f))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /**
     * Node type → the {@code use:} ref prefixes the flat config has a home for. Two kinds, three
     * prefixes: acquisition's {@code connection/} lands in the collector block, and the parser's
     * {@code grammar/} (an authored Grammar) or {@code ingester/} (a plugin parser's synthesized
     * binding) land in {@code parsing:}/{@code processing:}. Every other node type is absent, which is
     * the whole point — a type not listed here carries its settings <b>inline</b>, and a ref on it is
     * refused with {@link #UNSUPPORTED_BINDING} rather than dropped.
     */
    private static final Map<String, List<String>> USE_HOME = Map.of(
            BuiltinNodeType.ACQUISITION.type(), List.of("connection/"),
            BuiltinNodeType.PARSER.type(), List.of(GRAMMAR_REF_PREFIX, "ingester/"),
            // A built-in per-format subtype takes a Grammar but never ingester/ — a plugin ingester
            // binding on a node whose type SAYS its built-in format is a contradiction, refused rather
            // than half-honoured. (Binary fixed-width reaches FixedWidthRecordIngester through the plain
            // processing.ingester CLASS key, not a use: binding, so it needs no home here.)
            BuiltinNodeType.PARSER_DELIMITED.type(), List.of(GRAMMAR_REF_PREFIX),
            BuiltinNodeType.PARSER_FIXEDWIDTH.type(), List.of(GRAMMAR_REF_PREFIX),
            BuiltinNodeType.PARSER_ASN1.type(), List.of(GRAMMAR_REF_PREFIX),
            BuiltinNodeType.PARSER_JSON.type(), List.of(GRAMMAR_REF_PREFIX),
            BuiltinNodeType.PARSER_TEXT_REGEX.type(), List.of(GRAMMAR_REF_PREFIX),
            BuiltinNodeType.PARSER_XLSX.type(), List.of(GRAMMAR_REF_PREFIX),
            // The plugin subtype is the one exception: it IS the plain parser's plugin path, so it takes
            // ingester/ too — see DERIVED_USE below for why that ref is accepted but never authored.
            BuiltinNodeType.PARSER_PLUGIN.type(), List.of(GRAMMAR_REF_PREFIX, "ingester/"));

    /**
     * The node types this flat config has a {@code use:} home for — the authoritative half of the
     * cross-language bind-kind contract ({@code BindKindHomeContractTest}). Exposed rather than
     * re-derived because the UI's picker is keyed on a node's CATEGORY while these homes are keyed on
     * its TYPE; a picker offered for a category holding one homeless type buys the author a failed save,
     * which is precisely what AUTHOR-1(b) was.
     */
    static Set<String> typesWithUseHome() {
        return USE_HOME.keySet();
    }

    /**
     * Node type → the {@code use:} prefix that is DERIVED rather than authored, and is therefore dropped
     * in silence on purpose. Same distinction {@link #MAP_DERIVED} draws for config keys: a ref with no
     * home is a <b>loss</b> worth a refusal, a ref the read side put there is not.
     *
     * <p>An enrichment node's binding is written by the editor itself when it saves the companion
     * ({@code node-config.dialog.ts}, W4b) — the registered {@code *_enrich.toon} is the truth and the
     * ref merely points at it, which is why lower has "nothing to lower" for the kind (see the class
     * doc). ⚠ It was swept up by AUTHOR-1(b) on 2026-08-14 and refused for a day: since the editor puts
     * the ref on the node unconditionally, that made <b>every</b> pipeline holding an enrichment node
     * unsaveable. A binding the product writes is never an authoring mistake.
     *
     * <p>An ASN.1 node's {@code ingester/} ref is derived for the same reason from the other end: a
     * {@code frontend: asn1} file never authors an ingester — {@code PipelineConfigParser#asn1PluginBlock}
     * synthesizes the {@code Asn1RecordIngester} binding at load, and an explicit {@code plugin:} block
     * beside it is refused outright — so the class {@link PipelineLift} reads back and presents as
     * {@code use:} is the read side's own doing. Refusing it would make every ASN.1 pipeline unsaveable,
     * which is precisely the enrichment regression above, arrived at by a different route.
     *
     * <p>{@code parser.plugin} (P3d slice D) carries the identical reasoning one level down: it is the
     * plain parser's own {@code parsing.plugin.ingester}/{@code ingester_config}/{@code segments} path,
     * just with a dedicated type once the config says {@code frontend: plugin} explicitly, so
     * {@link PipelineLift} presents the same derived {@code ingester/<fqcn>} ref it always did for the
     * plain type — never authored, since the class comes from the config key, not a binding.
     *
     * <p>Plain {@code parser} carries the same {@code ingester/} ref for the legacy shape that predates
     * both subtypes: {@code processing.ingester} set with no {@code parsing.frontend} literal at all, so
     * the node never retypes ({@link #subtypeForFrontend} is explicit-only) yet {@link PipelineLift}
     * still synthesizes the ref from the class key unconditionally, same as the ASN.1/plugin cases above.
     */
    private static final Map<String, String> DERIVED_USE = Map.of(
            BuiltinNodeType.ENRICHMENT.type(), "enrichment/",
            BuiltinNodeType.PARSER.type(), "ingester/",
            BuiltinNodeType.PARSER_ASN1.type(), "ingester/",
            BuiltinNodeType.PARSER_PLUGIN.type(), "ingester/");

    /**
     * The derived-{@code use:} prefixes by node type — the authoritative half of the cross-language
     * contract ({@code BindKindHomeContractTest}), exposed for the same reason
     * {@link #typesWithUseHome()} is. ⚠ All three {@code ingester/} entries were added in three separate
     * sessions and each one missed the others, the last (plain {@code parser}, 2026-08-16) surfacing as a
     * validate/dry-run {@code UNKNOWN_USE_KIND} — the map is the kind that drifts silently, so its
     * CONTENTS are pinned, not merely its key set.
     */
    static Map<String, String> derivedUseByType() {
        return DERIVED_USE;
    }

    /**
     * Whether this node's {@code use:} ref is DERIVED rather than authored ({@link #DERIVED_USE}).
     *
     * <p>Shared with {@link PipelineValidator#checkWiring}, which asks the same question for a different
     * reason: such a ref does not name a {@code ComponentRegistry} kind at all (an enrichment companion
     * is registered through {@code POST /enrichment}, as {@code *_enrich.toon}), so the wiring check
     * would report {@code UNKNOWN_USE_KIND} for it. ⚠ Extracted rather than copied deliberately: the two
     * gates run in sequence on the save route — the validator first, the lower second — so a rule spelled
     * in only one of them is a rule that never takes effect.
     */
    static boolean isDerivedBinding(String type, String use) {
        String derived = DERIVED_USE.get(type);
        return derived != null && use != null && use.startsWith(derived);
    }

    /**
     * Sink-owned FLAT processing keys (write tuning carried on the persistent sink node). Consignment
     * formation is NOT here since 2026-09-02 (CONSIGNMENT-HOME-1): it is a collector-block key
     * ({@code collector.consignment:}), owned by the acquisition node — the planner runs in the poll
     * cycle, before any sink exists. The legacy {@code processing.batch:} map and the flat
     * {@code batch_max_files}/{@code batch_max_bytes} spellings (write-only, G3) are healed by lift and
     * removed by lower.
     */
    private static final Set<String> SINK_PROC_OWNED = Set.of("threads", "duckdb_threads", "priority");

    /** The sinks[]-entry keys the graph models (and therefore owns) — everything else in a
     *  pre-existing entry is preserved verbatim through a save, per lower()'s contract. */
    private static final Set<String> SINK_ENTRY_MODELED =
            Set.of("database", "format", "compression", "ducklake", "filename_column");

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
            // The per-format parser identity (B6): a file whose parsing: block NAMES its frontend
            // presents its parser as that subtype. Explicit only — delimited is also the parser's
            // implicit default, but retyping every bare legacy file would flip the node type of
            // everything deployed on a read; a file that never says the word keeps the plain type.
            // (Fixed width is never implicit, so for it "explicit" costs nothing — a fixed-width
            // config always says so, and both accepted spellings retype. Binary fixed-width retypes
            // too: the node TYPE spans the format, and only the DRAWER is text-only.)
            String type = n.type();
            if (BuiltinNodeType.PARSER.type().equals(type)
                    && section(raw, "parsing").get("frontend") instanceof String f
                    && subtypeForFrontend(f) != null)
                type = subtypeForFrontend(f);
            nm.put("type", type);
            if (n.hasName()) nm.put("name", n.name());
            if (n.description() != null && !n.description().isBlank()) nm.put("description", n.description());
            if (n.hasUse()) nm.put("use", n.use());
            else if (isParserType(n.type())
                    && section(raw, "parsing").get("grammar") instanceof String ref)
                // A parser bound to a reusable Grammar presents that binding as use:, mirroring
                // connection/ on acquisition. Never clobbers an existing use: — a plugin parser's
                // synthesized ingester/<fqcn> binding is a different thing and stays.
                nm.put("use", ref);
            Map<String, Object> c = editableConfig(n, raw, collector, dirs, output, processing);
            // per-Step enabled (Phase 4 S4): editableConfig rebuilds each node's config from the RAW
            // map by ownership rules, which drops the lift's disabled_steps overlay — carry the
            // node-level flag explicitly so the editor sees the same disabled state lower derives from.
            if (!n.enabled()) c.put("enabled", false);
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
            // Marker dedup, homed here since P5-a — same borrow-from-another-block shape as the three
            // keys above. `duplicate_check` carries only the enabled flag; the file keeps the nested map.
            // (the enabled test is PipelineConfigParser:241's, verbatim — the file may spell it either way)
            if (processing.get("duplicate_check") instanceof Map<?, ?> dc
                    && Boolean.parseBoolean(String.valueOf(dc.get("enabled")))) {
                c.put("duplicate_check", true);
                putIfPresent(c, "marker_extension", dc.get("marker_extension"));
                putIfPresent(c, "retention_days", dc.get("retention_days"));
                putIfPresent(c, "markers_dir", dirs.get("markers"));
            }
            // Unpack stage: the nested processing.unpack map, owned wholesale like the sink's intake
            // (its keys surface as the unpack__* specs). No legacy flat spellings to heal.
            if (processing.get("unpack") instanceof Map<?, ?> up) c.put("unpack", up);
            // Consignment formation (CONSIGNMENT-HOME-1): collector.consignment is a collector-block key,
            // so the loop above already carried it. A file still on the legacy processing.batch: map —
            // or the older flat spellings, written by the editor before G3 and read by nothing — heals
            // into the node's `consignment` map here and lands in the new home on the next save.
            if (!c.containsKey("consignment")) {
                if (processing.get("batch") instanceof Map<?, ?> b) {
                    c.put("consignment", b);
                } else {
                    Map<String, Object> legacy = new LinkedHashMap<>();
                    putIfPresent(legacy, "max_files", processing.get("batch_max_files"));
                    putIfPresent(legacy, "max_bytes", processing.get("batch_max_bytes"));
                    if (!legacy.isEmpty()) c.put("consignment", legacy);
                }
            }
        } else if (isParserType(t)) {
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
        } else if (BuiltinNodeType.SINK_PERSISTENT.type().equals(t)) {
            if (isQuarantine(n)) {
                putIfPresent(c, "dir", dirs.get("quarantine"));
            } else {
                putIfPresent(c, PipelineStores.CONFIG_STORE, n.cfg(PipelineStores.CONFIG_STORE));
                putIfPresent(c, "table", n.cfg("table"));
                // Destination keys: a file with a plural sinks: list gives EACH lifted sink node its
                // own entry's spelling (matched by database — the lift's join key), because the
                // output:/dirs.database shorthand is only the FIRST destination's. Stamping the
                // shorthand on every node made lower collapse destByDatabase to one entry and drop
                // the plural block on the next save. (Mirrors sinkDefs in pipeline-editable.ts.)
                Map<?, ?> entry = sinkEntryForDatabase(raw, n.cfg("database"));
                if (entry != null) {
                    putIfPresent(c, "format", entry.get("format"));
                    putIfPresent(c, "compression", entry.get("compression"));
                    putIfPresent(c, "ducklake", entry.get("ducklake"));
                    putIfPresent(c, "filename_column", entry.get("filename_column"));
                    putIfPresent(c, "database", entry.get("database"));
                } else {
                    putIfPresent(c, "format", output.get("format"));
                    putIfPresent(c, "compression", output.get("compression"));
                    putIfPresent(c, "ducklake", output.get("ducklake"));
                    putIfPresent(c, "filename_column", output.get("filename_column"));
                    putIfPresent(c, "database", dirs.get("database"));
                }
                putIfPresent(c, "backup", dirs.get("backup"));
                putIfPresent(c, "temp", dirs.get("temp"));
                for (String k : SINK_PROC_OWNED) putIfPresent(c, k, processing.get(k));
                // (Consignment formation left this node 2026-09-02 — it rides the acquisition node as
                // collector.consignment now; see editableConfig's ACQUISITION branch.)
                // Intake admission control: the nested processing.intake map, owned wholesale like
                // batch (its keys surface as the intake__* specs). No legacy flat spellings to heal.
                if (processing.get("intake") instanceof Map<?, ?> in) c.put("intake", in);
            }
        } else {
            // filter / map: the lifted config is already plain (derived views; lower ignores map,
            // and merges filter's row-filter keys back into csv_settings)
            c.putAll(n.config());
        }
        return c;
    }

    /** The raw top-level {@code sinks[]} entry whose {@code database} equals {@code database}, or
     *  {@code null} — absent list or no match means the single-{@code output:} shorthand applies. */
    private static Map<?, ?> sinkEntryForDatabase(Map<String, Object> raw, Object database) {
        if (database == null || !(raw.get("sinks") instanceof List<?> sinks)) return null;
        for (Object o : sinks)
            if (o instanceof Map<?, ?> m
                    && String.valueOf(database).equals(String.valueOf(m.get("database")))) return m;
        return null;
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
        PipelineNode primarySink = null, quarantineSink = null;
        // The transform chain in authored order — the five kinds the flat file can hold. Order is node
        // order, which is what the editor sends and what PipelineLift emits; the flat file has no edges,
        // so there is no topology to sort by at this point.
        List<PipelineNode> chain = new ArrayList<>();
        // Map nodes are NOT chain steps (see STEP_KIND's ⛔) — they are collected separately because the
        // flat file holds their authored half in processing.map, beside dedup/join/summarize.
        List<PipelineNode> mapNodes = new ArrayList<>();
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
            String unhomed = unhomedBinding(n);
            if (unhomed != null)
                refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_BINDING, n.id(), unhomed));
            if (BuiltinNodeType.ACQUISITION.type().equals(t)) acq = n;
            else if (isParserType(t)) {
                // One parse slot in the flat file. Last-one-wins predates the family, but with two
                // palette icons a second parser is now an authorable state — refuse, don't discard.
                if (parser != null) refusals.add(new PipelineCompileException.Refusal(MULTI_PARSER, n.id(),
                        "the flat pipeline config has one parse slot and '" + parser.id()
                                + "' already holds it"));
                else parser = n;
                // A subtype node whose own parsing: block names a DIFFERENT frontend is a contradiction
                // the file could only resolve by silently ignoring one of the two spellings. Compared
                // by SUBTYPE, not by string: fixed width answers to two spellings and neither
                // contradicts the other.
                if (SUBTYPE_FRONTENDS.containsKey(t)
                        && n.cfg("parsing") instanceof Map<?, ?> pb && pb.get("frontend") != null
                        && !t.equals(subtypeForFrontend(String.valueOf(pb.get("frontend")))))
                    refusals.add(new PipelineCompileException.Refusal(PARSER_FRONTEND_MISMATCH, n.id(),
                            "parsing.frontend '" + pb.get("frontend")
                                    + "' contradicts the node's own type '" + t + "'"));
            }
            else if (BuiltinNodeType.GAP.type().equals(t)) gap = n;
            else if (BuiltinNodeType.TRANSFORM_DEDUP_MARKER.type().equals(t)) marker = n;
            // The five chain kinds. Each used to claim a single slot and refuse a second (MULTI_*); they
            // now join an ordered chain, and how many there are stops being the question — see stepsOf.
            else if (stepKindOf(t) != null) chain.add(n);
            else if (BuiltinNodeType.SINK_PERSISTENT.type().equals(t)) {
                if (isQuarantine(n)) {
                    quarantineSink = n;
                } else {
                    if (n.cfg("database") != null) destByDatabase.putIfAbsent(String.valueOf(n.cfg("database")), n);
                    if (primarySink == null || (primarySink.cfg("database") == null && n.cfg("database") != null))
                        primarySink = n;
                }
            }
            else if (BuiltinNodeType.TRANSFORM_MAP.type().equals(t)) mapNodes.add(n);
            // enrichment: companion-persisted — nothing to lower
        }
        // MIDBRANCH-1 (R3): pull each route node's flattened branch chain OUT of the trunk chain —
        // those nodes lower into route.branches[].steps (routeSection), never into the trunk
        // spellings, or a mid-branch dedup would masquerade as processing.dedup and change which
        // lane executes it. Malformed branch shapes (fan-out, non-sink terminal, nested route)
        // refuse here by name, while the author is present.
        java.util.Set<String> branchChainIds = new java.util.LinkedHashSet<>();
        for (PipelineNode n : chain) {
            if (!BuiltinNodeType.TRANSFORM_ROUTE.type().equals(n.type())) continue;
            for (BranchChain bc : branchChains(g, n, refusals).values())
                for (PipelineNode c : bc.steps()) branchChainIds.add(c.id());
        }
        if (!branchChainIds.isEmpty()) chain.removeIf(n -> branchChainIds.contains(n.id()));

        // The authored half of the map nodes → processing.map. Until AUTHOR-1(a) this loop skipped
        // transform.map entirely, so anything typed into its dialog was answered `written: true` and
        // dropped. Refusals raised here, config returned for the emission below.
        Map<String, Object> mapAuthored = authoredMapConfig(mapNodes, parser, existing, refusals);
        // >1 distinct database is no longer a refusal — it lowers to a plural sinks: block (slice 4).
        // Row-routing to distinct destinations does NOT come through here: transform.route lowers to its
        // own route: key (with the branch↔sink pairing stamped from the edges), and transform.derive is
        // not LOWERABLE so it fails UNSUPPORTED_NODE above. Every sink reaching this map is therefore a
        // data/schema-dispatch fan-out, which sinks: (replicate-per-destination) represents faithfully.

        // ⚠ The chain is written in ONE of two spellings, never both — the parser refuses a file
        // carrying `steps:` next to a singular transform block, because there is no non-arbitrary
        // position at which the block would join the sequence. So the shape decides the whole emission
        // below: legacy-shaped chains keep the singular keys byte-for-byte (every pre-existing file
        // round-trips verbatim, which is the property that makes this change safe to ship), and only a
        // chain the singular keys CANNOT hold becomes a `steps:` list.
        //
        // A chain that is not legacy-shaped is, by construction, one that refused to save at all before
        // this slice — so nothing that runs today starts being written differently.
        //
        // ⚠ …with one exception, and it is lower()'s own ownership rule rather than a new policy: when
        // the file ALREADY says `steps:`, the spelling is the file's, not the graph's. Renormalising a
        // legacy-shaped chain back to the singular keys rewrites a hand-authored sequence into a
        // different spelling behind the author's back — and it is not a spelling-only difference:
        // PipelineConfig.hasExplicitSteps() decides whether PipelineLift walks the authored order or
        // its own constant one, and whether prepare() demands an output_store:. A file with no
        // `steps:` key is untouched by this, so every pre-existing file and every editor-authored
        // graph still takes the byte-for-byte legacy path above.
        // (…and only while there is still a chain to write: emptying the canvas of transforms leaves
        // no sequence to preserve, so that returns to the legacy path and the key goes.)
        boolean authoredSteps = existing.get("steps") instanceof List<?> l && !l.isEmpty()
                && !chain.isEmpty();
        boolean legacyShaped = isLegacyShaped(chain) && !authoredSteps;
        PipelineNode recordDedup   = legacyShaped ? first(chain, PipelineConfig.Step.DEDUP)     : null;
        PipelineNode routeNode     = legacyShaped ? first(chain, PipelineConfig.Step.ROUTE)     : null;
        PipelineNode summarizeNode = legacyShaped ? first(chain, PipelineConfig.Step.SUMMARIZE) : null;
        PipelineNode joinNode      = legacyShaped ? first(chain, PipelineConfig.Step.JOIN)      : null;
        // At most one under legacyShaped — two filters are precisely a case the singular
        // `csv_settings.where` cannot hold, and merging them with putAll is how the second one used to
        // disappear without a word (the loss MULTI_* refused for the other four kinds, still live here).
        List<PipelineNode> filters = legacyShaped ? all(chain, PipelineConfig.Step.FILTER) : List.of();

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
                if (!ACQ_FOREIGN_KEYS.contains(e.getKey()))
                    collector.put(e.getKey(), e.getValue());
            collector.remove("connection");
            if (acq.use() != null && acq.use().startsWith("connection/"))
                collector.put("connection", acq.use().substring("connection/".length()));
            replaceOrRemove(dirs, "poll", acq.cfg("poll"));
            replaceOrRemove(out, "trigger", acq.cfg("trigger"));
            replaceOrRemove(processing, "file_pattern", acq.cfg("file_pattern"));
            // Unpack lowers back as the nested processing.unpack: map the parser reads.
            replaceOrRemove(processing, "unpack", acq.cfg("unpack"));
            // Consignment formation lowered above INTO collector.consignment (a collector-block key, not
            // a foreign one). The legacy spellings go unconditionally — processing.batch: was the
            // pre-2026-09-02 home and the flat pair was read by nothing (G3); leaving any behind would
            // keep two spellings of one knob in one file, and the parser reads the canonical one first.
            processing.remove("batch");
            processing.remove("batch_max_files");
            processing.remove("batch_max_bytes");
        }
        overlayOwned(collector, "gap_detection", gap == null ? null : gapSection(gap), strict);

        // record-grain dedup → processing.dedup ({keys, order_by} — the QUALIFY the engine applies)
        if (recordDedup != null) {
            Map<String, Object> dd = new LinkedHashMap<>();
            putIfPresent(dd, "keys", recordDedup.cfg("keys"));
            putIfPresent(dd, "order_by", recordDedup.cfg("order_by"));
            putIfPresent(dd, "scope", recordDedup.cfg("scope"));   // D-9: window(<period>) | consignment
            processing.put("dedup", dd);
        } else if (strict) {
            processing.remove("dedup");
        }

        // per-Step enabled: → processing.disabled_steps (Phase 4 S4 / D-13). The node-level flag is
        // the editor's vocabulary (PipelineNode.enabled()); the flat file's ONE home is this id list —
        // derived from the whole graph here so no lowering branch needs `enabled` in its enumerated
        // key set (the two-mirror drift class this file has already paid for). Recomputed wholesale on
        // every lower, lenient included: the graph states every node's enabled state, so a re-enable
        // must clear its entry even on a draft save.
        List<String> disabledSteps = new ArrayList<>();
        for (PipelineNode n : g.nodes())
            if (!n.enabled()) disabledSteps.add(n.id());
        if (!disabledSteps.isEmpty()) processing.put("disabled_steps", disabledSteps);
        else processing.remove("disabled_steps");

        // route: block — node config verbatim, each branch stamped with the destination database its
        // route:<key> edge feeds, so the flat file (which has no edges) keeps the branch↔sink pairing.
        if (routeNode != null) {
            out.put("route", routeSection(g, routeNode));
        } else if (strict) {
            out.remove("route");
        }

        // reference join → processing.join ({reference, on}) — authoring-only (ELT P3 S2, D-4)
        if (joinNode != null) {
            Map<String, Object> jn = new LinkedHashMap<>();
            putIfPresent(jn, "reference", joinNode.cfg("reference"));
            putIfPresent(jn, "on", joinNode.cfg("on"));
            processing.put("join", jn);
        } else if (strict) {
            processing.remove("join");
        }

        // group-by rollup → processing.summarize ({group_by, measures}) — authoring-only (ELT P3)
        if (summarizeNode != null) {
            Map<String, Object> sm = new LinkedHashMap<>();
            putIfPresent(sm, "group_by", summarizeNode.cfg("group_by"));
            putIfPresent(sm, "measures", summarizeNode.cfg("measures"));
            processing.put("summarize", sm);
        } else if (strict) {
            processing.remove("summarize");
        }

        // authored map projection → processing.map ({columns, rules}). ⚠ Unlike its three neighbours
        // above this one EXECUTES: the graph executor RowShaper reads it on the next production run.
        if (mapAuthored != null) {
            processing.put("map", mapAuthored);
        } else if (strict) {
            processing.remove("map");
        }

        // Marker dedup → processing.duplicate_check + dirs.markers, homed on acquisition since P5-a
        // (see PipelineLift.markerHome for why a legacy marker node is still read).
        PipelineNode markerHome = PipelineLift.markerHome(acq, marker);
        if (markerHome != null) {
            Map<String, Object> dc = new LinkedHashMap<>();
            dc.put("enabled", true);
            putIfPresent(dc, "marker_extension", markerHome.cfg("marker_extension"));
            putIfPresent(dc, "retention_days", markerHome.cfg("retention_days"));
            processing.put("duplicate_check", dc);
            replaceOrRemove(dirs, "markers", markerHome.cfg("markers_dir"));
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
            // A per-format subtype node authored fresh from the palette carries no frontend key yet; the
            // file must say the word the type means, or a later read would lift it back as a plain
            // parser and the identity would quietly evaporate. A lifted node already carries it (that is
            // what made it this type), so the round-trip stays verbatim — including a `fixed_width`
            // spelling, which is left alone rather than canonicalised.
            List<String> frontends = SUBTYPE_FRONTENDS.get(parser.type());
            if (frontends != null && parsingBlock.get("frontend") == null)
                parsingBlock.put("frontend", frontends.getFirst());
            if (!parsingBlock.isEmpty()) out.put("parsing", parsingBlock);
            else out.remove("parsing");
            if (!filters.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> csv = (Map<String, Object>)
                        processing.computeIfAbsent("csv_settings", k -> new LinkedHashMap<String, Object>());
                for (PipelineNode f : filters) csv.putAll(f.config());
            }
        }

        // ── the ordered chain ──────────────────────────────────────────────────────
        if (legacyShaped) {
            // The singular keys said it all, and the file never said otherwise (a file that DID say
            // `steps:` keeps saying it — see authoredSteps). Removing the key here is what stops the
            // two spellings colliding on the next load.
            out.remove("steps");
        } else {
            out.put("steps", stepsOf(g, chain));
            // ⚠ Every singular transform key must go, in BOTH modes — this is not the usual
            // strict-only "the graph owns its section" rule. Leaving one behind writes a file that
            // refuses to load: the parser rejects steps: alongside a legacy block outright, so a
            // lenient save would hand back config that can never be read again.
            processing.remove("dedup");
            processing.remove("join");
            processing.remove("summarize");
            out.remove("route");
            // ⛔ processing.map is deliberately NOT removed here. It is not a chain step and the parser
            // does not refuse it beside steps: — a map node exists in both spellings, so removing it
            // would delete an authored projection every time a chain grew past the singular keys.
            // `where` is the legacy spelling of a filter step and lives INSIDE the parser's own
            // csv_settings block, so the parser node carries it back in verbatim and it survives the
            // removals above. The pre-parse list keys in that block are not chain steps (they anchor
            // the CSV reader on a column index) and stay exactly where they are.
            if (processing.get("csv_settings") instanceof Map<?, ?> cs) {
                Map<String, Object> stripped = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : cs.entrySet())
                    if (!"where".equals(e.getKey())) stripped.put(String.valueOf(e.getKey()), e.getValue());
                if (stripped.isEmpty()) processing.remove("csv_settings");
                else processing.put("csv_settings", stripped);
            }
        }

        if (primarySink != null) {
            output.clear();
            putIfPresent(output, "format", primarySink.cfg("format"));
            putIfPresent(output, "compression", primarySink.cfg("compression"));
            putIfPresent(output, "ducklake", primarySink.cfg("ducklake"));
            putIfPresent(output, "filename_column", primarySink.cfg("filename_column"));
            replaceOrRemove(dirs, "database", primarySink.cfg("database"));
            replaceOrRemove(dirs, "backup", primarySink.cfg("backup"));
            replaceOrRemove(dirs, "temp", primarySink.cfg("temp"));
            for (String k : SINK_PROC_OWNED) replaceOrRemove(processing, k, primarySink.cfg(k));
            // Intake admission control lowers as the nested processing.intake: map the parser reads.
            replaceOrRemove(processing, "intake", primarySink.cfg("intake"));
        }
        // Multi-destination: emit a plural sinks: list of the distinct destinations (the single output:/
        // dirs.database above stays the shorthand + parser fallback, consistent with the first destination).
        // One destination ⇒ no sinks: block, so a single-output pipeline round-trips verbatim.
        if (destByDatabase.size() > 1) {
            // Rebuilding the list must still honour lower()'s contract — keys the graph does not
            // model are preserved. A hand-authored sinks[].partitions is read by the at-rest job
            // lane (RecipeConverter copies extra sinks wholesale), so dropping it here would delete
            // honoured config on every editor save. Modeled keys stay graph-owned and are NOT
            // resurrected from the file; everything else carries over by destination database.
            Map<String, Map<?, ?>> priorByDatabase = new LinkedHashMap<>();
            if (out.get("sinks") instanceof List<?> prior)
                for (Object o : prior)
                    if (o instanceof Map<?, ?> pm && pm.get("database") != null)
                        priorByDatabase.putIfAbsent(String.valueOf(pm.get("database")), pm);
            List<Map<String, Object>> sinks = new ArrayList<>();
            for (PipelineNode s : destByDatabase.values()) {
                Map<String, Object> sink = new LinkedHashMap<>();
                sink.put("database", s.cfg("database"));
                putIfPresent(sink, "format", s.cfg("format"));
                putIfPresent(sink, "compression", s.cfg("compression"));
                putIfPresent(sink, "ducklake", s.cfg("ducklake"));
                putIfPresent(sink, "filename_column", s.cfg("filename_column"));
                Map<?, ?> prior = priorByDatabase.get(String.valueOf(s.cfg("database")));
                if (prior != null)
                    for (Map.Entry<?, ?> e : prior.entrySet())
                        if (!SINK_ENTRY_MODELED.contains(String.valueOf(e.getKey())))
                            sink.putIfAbsent(String.valueOf(e.getKey()), e.getValue());
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
        // A file with no output: block must not gain an empty one — getOrNew created the section
        // speculatively, and an unconfigured sink writes nothing into it (fixture round-trip gate).
        if (output.isEmpty()) out.remove("output");
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Whether {@code chain} is expressible in the legacy singular keys: at most one of each kind, and in
     * the order {@link PipelineLift} wires them ({@code filter → join → dedup → summarize → route}).
     *
     * <p>⚠ <b>Order is half the test, and it is the half that is easy to miss.</b> One dedup and one
     * summarize fit the singular keys whichever way round they are authored — but the flat file stores no
     * order at all, so on the next lift they come back in the lift's constant order. An authored
     * {@code summarize → dedup} that lowered to singular keys would come back reversed, silently. That is
     * a two-node pipeline losing its meaning, with nothing over-full about it.
     *
     * <p>Both halves are the single strictly-increasing test below: a repeated kind has the same position
     * as the one before it, so it fails {@code <=} exactly as an out-of-order kind does. A separate
     * "already seen this kind" set was written first and proved dead — deleting it turned no test red,
     * which is how it was caught.
     */
    private static boolean isLegacyShaped(List<PipelineNode> chain) {
        int previous = -1;
        for (PipelineNode n : chain) {
            // A CONTRIBUTED kind is not in KINDS, so indexOf gives -1 and the chain is not legacy-shaped
            // — which is correct and not incidental: the singular blocks have one fixed key each and
            // cannot hold a step type they have never heard of. Such a chain must take `steps:`.
            int position = PipelineConfig.Step.KINDS.indexOf(STEP_KIND.get(n.type()));
            if (position <= previous) return false;
            previous = position;
        }
        return true;
    }

    /** The ordered {@code steps:} list: one single-key {@code kind → config} map per chain node. */
    private static List<Map<String, Object>> stepsOf(PipelineGraph g, List<PipelineNode> chain) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (PipelineNode n : chain) {
            String kind = stepKindOf(n.type());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(kind, stepConfig(g, n, kind));
            steps.add(entry);
        }
        return steps;
    }

    /** A step's config in the same shape its legacy block held, so the two spellings stay one vocabulary. */
    private static Map<String, Object> stepConfig(PipelineGraph g, PipelineNode n, String kind) {
        Map<String, Object> c = new LinkedHashMap<>();
        switch (kind) {
            case PipelineConfig.Step.DEDUP -> {
                putIfPresent(c, "keys", n.cfg("keys"));
                putIfPresent(c, "order_by", n.cfg("order_by"));
                putIfPresent(c, "scope", n.cfg("scope"));   // D-9: window(<period>) | consignment
            }
            case PipelineConfig.Step.JOIN -> {
                putIfPresent(c, "reference", n.cfg("reference"));
                putIfPresent(c, "on", n.cfg("on"));
            }
            case PipelineConfig.Step.SUMMARIZE -> {
                putIfPresent(c, "group_by", n.cfg("group_by"));
                putIfPresent(c, "measures", n.cfg("measures"));
            }
            // route keeps the branch↔sink pairing stamped from the edges, exactly as route: does
            case PipelineConfig.Step.ROUTE -> c.putAll(routeSection(g, n));
            // filter: verbatim. Its keys are two different things fused into one node — the post-parse
            // `where` predicate, and the pre-parse include/exclude lists — and a chain step keeps both
            // so the round-trip is lossless. Only `where` has a legacy singular spelling.
            default -> c.putAll(deepCopy(n.config()));
        }
        return c;
    }

    /** The first chain node of {@code kind}, or {@code null}. */
    private static PipelineNode first(List<PipelineNode> chain, String kind) {
        for (PipelineNode n : chain) if (kind.equals(STEP_KIND.get(n.type()))) return n;
        return null;
    }

    /** Every chain node of {@code kind}, in order. */
    private static List<PipelineNode> all(List<PipelineNode> chain, String kind) {
        List<PipelineNode> out = new ArrayList<>();
        for (PipelineNode n : chain) if (kind.equals(STEP_KIND.get(n.type()))) out.add(n);
        return out;
    }

    /**
     * The {@code route:} section for {@code routeNode}: its config deep-copied, with each branch entry's
     * {@code database} stamped from the sink its {@code route:<key>} edge feeds (edges don't survive the
     * flat file, the stamped database is what {@code PipelineLift} pairs branches back with).
     */
    private static Map<String, Object> routeSection(PipelineGraph g, PipelineNode routeNode) {
        Map<String, Object> rc = deepCopy(routeNode.config());
        List<RouteBranch> branches = RouteBranch.listFrom(rc);
        if (branches == null) return rc;
        // MIDBRANCH-1: the branch↔sink pairing is by the chain's TERMINAL node, not the route edge's
        // direct target — with a steps[] sub-chain the route:<key> edge feeds the chain's first node
        // and the last node feeds the sink, so reading the edge target alone would stamp no database
        // and arming's pairing check would pass a branch whose rows land nowhere.
        Map<String, BranchChain> chains = branchChains(g, routeNode, null);
        Map<String, String> databaseByKey = new LinkedHashMap<>();
        for (Map.Entry<String, BranchChain> e : chains.entrySet()) {
            PipelineNode terminal = e.getValue().terminal();
            if (terminal != null && terminal.cfg("database") != null)
                databaseByKey.put(e.getKey(), String.valueOf(terminal.cfg("database")));
        }
        List<?> original = (List<?>) rc.get("branches");
        List<Object> lowered = new ArrayList<>(branches.size());
        for (int i = 0; i < branches.size(); i++) {
            RouteBranch b = branches.get(i);
            if (b == null) { lowered.add(original.get(i)); continue; }   // malformed entry: verbatim
            String database = b.key() == null ? null : databaseByKey.get(b.key());
            Map<String, Object> entry = (database != null ? b.withDatabase(database) : b).toMap();
            // The graph owns the branch's sub-chain: chain nodes between the route:<key> edge and the
            // terminal sink write back as this branch's steps[], in walk order, through the SAME
            // per-kind stepConfig builders the top-level steps: list uses (one grammar, no drift).
            // A branch wired straight to its sink carries no steps key — byte-identical to pre-R3.
            BranchChain bc = b.key() == null ? null : chains.get(b.key());
            if (bc != null && !bc.steps().isEmpty()) {
                List<Map<String, Object>> steps = new ArrayList<>();
                for (PipelineNode n : bc.steps()) {
                    String kind = stepKindOf(n.type());
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put(kind, stepConfig(g, n, kind));
                    steps.add(step);
                }
                entry.put("steps", steps);
            } else if (bc != null) {
                entry.remove("steps");   // the graph shows no chain — a stale steps key must not survive
            }
            lowered.add(entry);
        }
        rc.put("branches", lowered);
        return rc;
    }

    /** One branch's flattened sub-chain: the ordered chain-step nodes between the {@code route:<key>}
     *  edge and the branch's terminal node (empty for a direct-wired branch), plus that terminal —
     *  the sink whose {@code database} stamps the branch. {@code terminal} is {@code null} only for
     *  a shape that already raised a refusal (or a dangling edge). */
    record BranchChain(List<PipelineNode> steps, PipelineNode terminal) {}

    /**
     * Walk each {@code route:<key>} edge of {@code routeNode} through the flattened chain to that
     * branch's terminal (MIDBRANCH-1) — the reverse of {@code PipelineLift}'s branch expansion.
     * {@code refusals} (nullable) collects the shapes the flat file cannot hold, each as
     * {@link #UNSUPPORTED_BRANCH_STEP}: a chain node fanning out (a branch is a LIST), a chain ending
     * anywhere but a persistent sink, and a nested {@code transform.route}.
     */
    private static Map<String, BranchChain> branchChains(PipelineGraph g, PipelineNode routeNode,
                                                         List<PipelineCompileException.Refusal> refusals) {
        Map<String, PipelineNode> byId = new LinkedHashMap<>();
        for (PipelineNode n : g.nodes()) byId.put(n.id(), n);
        Map<String, BranchChain> out = new LinkedHashMap<>();
        for (PipelineEdge e : g.edges()) {
            if (!e.from().equals(routeNode.id()) || !PipelineRel.isRoute(e.rel())) continue;
            String key = PipelineRel.routeKey(e.rel());
            List<PipelineNode> steps = new ArrayList<>();
            PipelineNode cur = byId.get(e.to());
            boolean broken = false;
            while (cur != null && stepKindOf(cur.type()) != null) {
                if (BuiltinNodeType.TRANSFORM_ROUTE.type().equals(cur.type())) {
                    if (refusals != null) refusals.add(new PipelineCompileException.Refusal(
                            UNSUPPORTED_BRANCH_STEP, cur.id(),
                            "a route cannot nest inside branch '" + key + "'"));
                    broken = true;
                    break;
                }
                steps.add(cur);
                List<PipelineEdge> outbound = new ArrayList<>();
                for (PipelineEdge oe : g.edges())
                    if (oe.from().equals(cur.id()) && PipelineRel.DATA.equals(oe.rel())) outbound.add(oe);
                if (outbound.size() != 1) {
                    if (refusals != null) refusals.add(new PipelineCompileException.Refusal(
                            UNSUPPORTED_BRANCH_STEP, cur.id(),
                            "branch '" + key + "'s chain node has " + outbound.size()
                                    + " outgoing data edges — a branch chain is a LIST, each node "
                                    + "feeding exactly the next"));
                    broken = true;
                    break;
                }
                cur = byId.get(outbound.get(0).to());
            }
            if (!broken && !steps.isEmpty() && (cur == null
                    || !BuiltinNodeType.SINK_PERSISTENT.type().equals(cur.type()))) {
                if (refusals != null) refusals.add(new PipelineCompileException.Refusal(
                        UNSUPPORTED_BRANCH_STEP, cur == null ? null : cur.id(),
                        "branch '" + key + "'s chain must end at a persistent sink"
                                + (cur == null ? "" : " — found '" + cur.type() + "'")));
                broken = true;
            }
            out.put(key, new BranchChain(steps, broken ? null : cur));
        }
        return out;
    }

    /** Legacy files may spell the block {@code source:}; write back whichever key the file uses. */
    private static String collectorKey(Map<String, Object> raw) {
        return raw.containsKey("source") && !raw.containsKey("collector") ? "source" : "collector";
    }

    /**
     * The {@code processing.map} block to write for this graph's map nodes, or {@code null} when they
     * carry nothing authored. Raises three refusals into {@code refusals}:
     *
     * <ul>
     *   <li>{@link #UNSUPPORTED_MAP_KEY} — a key that is neither {@link #MAP_AUTHORED} nor
     *       {@link #MAP_DERIVED}. ⛔ A blanket "map has config ⇒ refuse" would refuse every existing
     *       pipeline, because the lift puts a derived {@code schema} on every map node.</li>
     *   <li>{@link #MAPPING_CONFLICT} — authored {@code columns} alongside an explicitly declared
     *       {@code processing.mapping_file}. {@code RowShaper.columnsOf} checks {@code columns} first and
     *       never consults the schema when it finds one, so the authored list would silently outrank a
     *       reference the operator declared on purpose. Refusing at authoring time makes the operator
     *       delete one, and needs no change to a live execution path.</li>
     *   <li>{@link #MULTI_MAP_CONFIG} — a multi-schema graph whose map nodes have drifted apart. One
     *       {@code processing.map} serves them all, so keeping the first would discard the rest
     *       silently — the loss this whole change exists to remove.</li>
     * </ul>
     */
    private static Map<String, Object> authoredMapConfig(List<PipelineNode> mapNodes, PipelineNode parser,
                                                         Map<String, Object> existing,
                                                         List<PipelineCompileException.Refusal> refusals) {
        Map<String, Object> authored = null;
        String authoredBy = null;
        for (PipelineNode n : mapNodes) {
            for (String k : n.config().keySet())
                if (!MAP_AUTHORED.contains(k) && !MAP_DERIVED.contains(k))
                    refusals.add(new PipelineCompileException.Refusal(UNSUPPORTED_MAP_KEY, n.id(),
                            "a map node has no home for '" + k + "' in the flat pipeline config; it accepts "
                                    + new TreeSet<>(MAP_AUTHORED)));
            Map<String, Object> mine = new LinkedHashMap<>();
            for (String k : List.of("columns", "rules")) putIfPresent(mine, k, n.cfg(k));
            if (mine.isEmpty()) continue;
            if (authored == null) {
                authored = mine;
                authoredBy = n.id();
            } else if (!authored.equals(mine)) {
                refusals.add(new PipelineCompileException.Refusal(MULTI_MAP_CONFIG, n.id(),
                        "map nodes '" + authoredBy + "' and '" + n.id() + "' carry different authored config, "
                                + "and the flat file has one processing.map for all of them"));
            }
        }
        if (authored != null && authored.containsKey("columns") && declaresMappingFile(parser, existing))
            refusals.add(new PipelineCompileException.Refusal(MAPPING_CONFLICT, authoredBy,
                    "an authored columns list would silently outrank the declared processing.mapping_file; "
                            + "keep one of the two"));
        return authored;
    }

    /**
     * Whether the file being written declares {@code processing.mapping_file}. The parser node owns the
     * key ({@link #PARSER_OWNED}) and its config is what gets written, so it decides — but a lenient save
     * of a graph with no parser node leaves the existing file's value in place, and that counts too.
     */
    private static boolean declaresMappingFile(PipelineNode parser, Map<String, Object> existing) {
        if (parser != null) return parser.cfg("mapping_file") != null;
        return existing.get("processing") instanceof Map<?, ?> p && p.get("mapping_file") != null;
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
}
