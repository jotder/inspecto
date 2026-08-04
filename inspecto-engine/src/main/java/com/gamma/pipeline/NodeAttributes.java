package com.gamma.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-node-type config attribute tables, published on {@code GET /pipelines/node-types} (§3.1 of
 * {@code docs/superpower/vocabulary-and-config-contract-plan.md}). This is the SERVER half of the same
 * vocabulary {@code inspecto-ui/.../pipelines/node-attributes.ts} declares, and since §3.1 the served
 * copy is the source while the client table is its offline/mock fallback.
 *
 * <p>⚠ <b>These two tables must stay identical, and a test enforces it rather than a comment.</b>
 * {@code NodeAttributesContractTest} compares this table against the committed
 * {@code inspecto-ui/src/app/inspecto/mock/node-attributes.contract.json}, and the TS
 * {@code node-attributes.spec.ts} compares its own table against the SAME file — so neither side can
 * drift without one of the two suites failing. The JSON is not generated at build time on purpose: a
 * generated artifact would silently absorb a change on whichever side ran the generator.
 *
 * <p>⚠ <b>Every key here IS the engine's config key</b> — there is no case-conversion or mapping layer
 * anywhere in the stack, so a key that is not byte-identical to what the engine reads silently no-ops.
 * That is the D1–D9 failure mode; {@code NodeConfigNameContractTest} proves each key actually reaches its
 * engine field by driving it through the editor's real save path.
 *
 * <p>A node type absent from this table has no schema and falls back to the dialog's free-form key/value
 * editor. Types are left absent deliberately rather than guessed — the remaining {@code transform.*}
 * shapes are not specced, and a best-guess table that looks authoritative is what the plan was cleaning up.
 */
public final class NodeAttributes {

    private NodeAttributes() {}

    /**
     * The {@code collector:} block. Shared by BOTH authoring surfaces — the Pipelines editor's
     * {@code acquisition} node and Onboarding's Collection stage — because they author the same block.
     *
     * <p>⚠ {@code connector} is deliberately not a spec: it is derived (local inbox ⇒ {@code local},
     * otherwise the picked Connection's own connector) and injected at save time.
     * {@code CollectorConnectors.forConfig} dispatches on {@code collector.connector} and hands that
     * factory the profile named by {@code collector.connection} without checking they agree, so asking
     * for it invites a mismatch.
     */
    public static final List<NodeAttribute> COLLECTOR = List.of(
            NodeAttribute.of("connection", "Connection", "autocomplete", "required").required(false)
                    .help("Saved Connection profile — it carries the connector type (SFTP, Azure Blob, Kafka, Database)."),
            NodeAttribute.of("include", "Include patterns", "string", "optional")
                    .placeholder("*.csv, orders_*.txt")
                    .help("Glob/regex discovery patterns; comma-separate multiple. Blank = the pipeline file pattern."),
            NodeAttribute.of("discovery", "Discovery", "select", "optional").defaultValue("poll")
                    .options("poll", "Poll", "watch", "Watch (filesystem events)"),
            NodeAttribute.of("duplicate__mode", "Duplicate detection", "select", "optional").defaultValue("path")
                    .options("path", "By path", "metadata", "By metadata (size + mtime)",
                            "checksum", "By checksum", "etag", "By remote ETag")
                    .help("File-level duplicate policy — how a re-seen file is recognised."),
            NodeAttribute.of("post_action__on_success", "After success", "select", "optional").defaultValue("RETAIN")
                    .options("RETAIN", "Retain", "DELETE", "Delete", "MOVE", "Move to archive",
                            "RENAME", "Rename", "TAG", "Tag"),
            NodeAttribute.of("exclude", "Exclude patterns", "string", "advanced").placeholder("*.tmp"),
            NodeAttribute.of("recursive_depth", "Recursive depth", "number", "advanced").min(0)
                    .help("Blank = unbounded."),
            NodeAttribute.of("duplicate__on_change", "On changed duplicate", "select", "advanced")
                    .options("reprocess", "Reprocess", "skip", "Skip")
                    .help("What to do when a known file re-appears changed."),
            NodeAttribute.of("guarantee", "Delivery guarantee", "select", "advanced")
                    .options("BEST_EFFORT", "Best effort", "AT_LEAST_ONCE", "At least once",
                            "EXACTLY_ONCE", "Exactly once"),
            NodeAttribute.of("stability__window", "Stability window", "string", "advanced").placeholder("5s")
                    .help("Wait for a file to stop growing before collecting it."),
            NodeAttribute.of("post_action__archive_path", "Archive path", "string", "advanced")
                    .help("Target directory when \"After success\" is Move."));

    /**
     * The {@code duplicate:} keys — declared on the fingerprint-dedup node, NOT on acquisition (D9).
     * {@code PipelineEditable.lower} overlays {@code duplicate:} from THIS node ({@code :255}) while
     * {@code NOT_ACQ_OWNED} ({@code :60}) strips it from the acquisition node, so a value typed on
     * acquisition was silently discarded on save.
     *
     * <p>⛔ Do not "fix" that by pruning {@link #COLLECTOR} — Onboarding authors the {@code collector:}
     * block whole and the keys are real there. The rule: <b>a shared attribute table is correct per
     * BLOCK, not per NODE.</b>
     */
    public static final List<NodeAttribute> DEDUP_FINGERPRINT =
            COLLECTOR.stream().filter(a -> a.key().startsWith("duplicate__")).toList();

    /** {@link #COLLECTOR} minus the keys the fingerprint-dedup node owns — a derivation, not a fork. */
    public static final List<NodeAttribute> ACQUISITION =
            COLLECTOR.stream().filter(a -> !DEDUP_FINGERPRINT.contains(a)).toList();

    /**
     * The {@code output:} block, shared by all three sink kinds and Onboarding's Dataset & Go-live stage —
     * the kind is the materialisation behaviour, not a different config shape. The {@code format} default
     * is the ENGINE's absent-key behaviour (CSV, the {@code PartitionWriter} default), not a UX suggestion:
     * a surface wanting to suggest Parquet seeds it via the form's initial values, never by forking this.
     */
    public static final List<NodeAttribute> OUTPUT = List.of(
            NodeAttribute.of("format", "Output format", "select", "required").required(false).defaultValue("CSV")
                    .options("CSV", "CSV", "PARQUET", "Parquet")
                    .help("Stage-1 output file format; absent = CSV (the engine default)."),
            NodeAttribute.of("compression", "Compression", "string", "optional").placeholder("snappy")
                    .help("Codec for the output (e.g. snappy / zstd / gzip); blank = format default."));

    /**
     * {@code transform.filter} has TWO filtering moments on the flat path and both are real (D7) — they
     * are NOT synonyms and must never be collapsed:
     * <ul>
     *   <li><b>post-parse</b> {@code where} — SQL over the mapped, typed columns
     *       ({@code DataTransformer.materialize});</li>
     *   <li><b>pre-parse</b> {@code include_*}/{@code exclude_*} — regex/prefix over ONE raw physical
     *       column inside {@code read_csv} ({@code DuckDbCsvIngester.filterWhere}), anchored on
     *       {@code filter_target_column}.</li>
     * </ul>
     * {@code amount > 0} is inexpressible as a pre-parse regex, and a regex over an unparsed column is
     * inexpressible as a predicate. Neither moment is individually mandatory (a node legitimately uses
     * only one), so both are visible with {@code required: false} rather than one forcing the choice.
     */
    public static final List<NodeAttribute> TRANSFORM_FILTER = List.of(
            NodeAttribute.of("where", "Row predicate (after parsing)", "string", "required").required(false)
                    .placeholder("amount > 0")
                    .help("SQL over the mapped, typed columns. Rows matching are kept; NULL results are dropped."),
            NodeAttribute.of("include_regex", "Include matching (before parsing)", "list", "optional")
                    .placeholder("^CALL")
                    .help("Keep rows whose target column matches any of these regexes, checked on the raw text before parsing."),
            NodeAttribute.of("exclude_regex", "Exclude matching (before parsing)", "list", "optional")
                    .placeholder("^TEST")
                    .help("Drop rows whose target column matches any of these regexes."),
            NodeAttribute.of("include_prefixes", "Include by prefix (before parsing)", "list", "optional")
                    .help("Keep rows whose target column starts with any of these."),
            NodeAttribute.of("exclude_prefixes", "Exclude by prefix (before parsing)", "list", "optional")
                    .help("Drop rows whose target column starts with any of these."),
            NodeAttribute.of("filter_target_column", "Target column index", "number", "advanced").min(0)
                    .help("Which raw column (0-based) the four before-parsing lists above match against. Ignored by the row predicate."));

    /**
     * {@code transform.route}: {@code mode} is the only scalar. {@code branches} — the list of
     * {@code {key, where}} that actually does the routing — has no spec because the {@code list} type is
     * {@code string[]} and these are MAPS; the named routes are authored on the canvas edges anyway.
     * {@code route_column} was removed (D2): it was read by nothing.
     */
    public static final List<NodeAttribute> TRANSFORM_ROUTE = List.of(
            NodeAttribute.of("mode", "Route mode", "select", "required").defaultValue("case")
                    .options("case", "case (exclusive)", "clone", "clone (fan-out)")
                    .help("Named routes and their predicates are edited on the canvas edges."));

    private static final Map<String, List<NodeAttribute>> BY_TYPE = byType();

    private static Map<String, List<NodeAttribute>> byType() {
        for (List<NodeAttribute> table : List.of(COLLECTOR, OUTPUT, TRANSFORM_FILTER, TRANSFORM_ROUTE))
            for (NodeAttribute a : table) a.validate();   // whole-spec checks, once the builders are done
        Map<String, List<NodeAttribute>> m = new LinkedHashMap<>();
        m.put(BuiltinNodeType.ACQUISITION.type(), ACQUISITION);
        m.put(BuiltinNodeType.TRANSFORM_DEDUP_FINGERPRINT.type(), DEDUP_FINGERPRINT);
        m.put(BuiltinNodeType.TRANSFORM_FILTER.type(), TRANSFORM_FILTER);
        m.put(BuiltinNodeType.TRANSFORM_ROUTE.type(), TRANSFORM_ROUTE);
        m.put(BuiltinNodeType.SINK_PERSISTENT.type(), OUTPUT);
        m.put(BuiltinNodeType.SINK_MATERIALIZED.type(), OUTPUT);
        m.put(BuiltinNodeType.SINK_VIEW.type(), OUTPUT);
        // NOT Map.copyOf: that returns an UNORDERED map, so the committed contract JSON would come out in a
        // different key order on a different JVM run and the drift test would fail at random.
        return java.util.Collections.unmodifiableMap(m);
    }

    /** The declared attributes for a node type, or an empty list when it has none (free-form only). */
    public static List<NodeAttribute> forType(String type) {
        return BY_TYPE.getOrDefault(type, List.of());
    }

    /** Every specced node type — the drift check's iteration order source. */
    public static List<String> speccedTypes() {
        return List.copyOf(BY_TYPE.keySet());
    }

    /** {@code type -> [attribute wire maps]}, the shape the catalog embeds and the contract JSON holds. */
    public static Map<String, Object> wireMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String type : BY_TYPE.keySet()) {
            List<Map<String, Object>> attrs = new ArrayList<>();
            for (NodeAttribute a : BY_TYPE.get(type)) attrs.add(a.toMap());
            out.put(type, attrs);
        }
        return out;
    }
}
