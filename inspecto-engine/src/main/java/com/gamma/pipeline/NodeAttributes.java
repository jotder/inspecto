package com.gamma.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-node-type config attribute tables, published on {@code GET /pipelines/node-types}. This is the
 * SERVER half of the same vocabulary {@code inspecto-ui/.../pipelines/node-attributes.ts} declares, and the
 * served copy is the source while the client table is its offline/mock fallback. Current knowledge:
 * {@code docs/okf/frontend/features/pipelines.md}; decision record: §3.1 of the archived
 * {@code docs/archived-documents/plans-archive/vocabulary-and-config-contract-plan.md}.
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
 * editor. Types are left absent deliberately rather than guessed — {@code parser} is authored by the
 * Grammar editor and {@code transform.map} by the mapping-CSV surface (ELT UI plan S5), so neither
 * gets a scalar spec here; a best-guess table that looks authoritative is what the plan was cleaning up.
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
     * {@code sink.persistent} = the destination ({@code database}) plus the shared {@code output:} block.
     * The {@code database} dir is the one key {@code PipelineEditable.lower} HARD-requires on the primary
     * sink ({@code NO_PERSISTENT_SINK} refuses the save without it), so the dialog must ask it up front —
     * but {@code required(false)}: a quarantine sink is a {@code sink.persistent} too, and it sets
     * {@code dir}, never {@code database}, so form-level enforcement would block a legitimate node.
     */
    public static final List<NodeAttribute> SINK_PERSISTENT = sinkPersistent();

    private static List<NodeAttribute> sinkPersistent() {
        List<NodeAttribute> attrs = new ArrayList<>();
        attrs.add(NodeAttribute.of("database", "Database directory", "string", "required").required(false)
                .placeholder("data/<pipeline>/database")
                .help("Directory where committed batches land. The pipeline's primary sink must set this; a quarantine sink writes unmatched files to 'dir' instead."));
        attrs.addAll(OUTPUT);
        return List.copyOf(attrs);
    }

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

    /**
     * {@code transform.dedup} (record-grain, → {@code processing.dedup}) — the QUALIFY the engine
     * applies before the partitioned write ({@code BatchIngestStrategy}). Distinct from the
     * file-level duplicate Guarantees (marker / fingerprint), which are never Steps.
     * Keys proven by {@code NodeConfigNameContractTest} (keys / order_by reach {@code cfg.dedup()}).
     */
    public static final List<NodeAttribute> TRANSFORM_DEDUP = List.of(
            NodeAttribute.of("keys", "Dedup keys", "list", "required").placeholder("call_id")
                    .help("Rows sharing these column values are duplicates; the first (per \"Order by\") is kept."),
            NodeAttribute.of("order_by", "Order by", "string", "optional").placeholder("event_ts DESC")
                    .help("Which duplicate wins — SQL ordering over the typed columns; blank = input order."));

    /**
     * {@code transform.summarize} (→ {@code processing.summarize}) — the group-by rollup, authoring-only
     * until the branch-aware executor arms it. Keys proven by {@code NodeConfigNameContractTest}.
     */
    public static final List<NodeAttribute> TRANSFORM_SUMMARIZE = List.of(
            NodeAttribute.of("group_by", "Group by", "list", "required").placeholder("region")
                    .help("Grouping columns of the rollup."),
            NodeAttribute.of("measures", "Measures", "list", "required").placeholder("sum(amount)")
                    .help("Aggregate expressions computed per group."));

    /**
     * {@code transform.join} (→ {@code processing.join}, D-4) — the reference join, authoring-only.
     * {@code reference} names a registered Reference component ({@code reference/<id>}), so the UI
     * renders it as an autocomplete over the registry. Keys proven by {@code NodeConfigNameContractTest}.
     */
    public static final List<NodeAttribute> TRANSFORM_JOIN = List.of(
            NodeAttribute.of("reference", "Reference", "autocomplete", "required")
                    .placeholder("reference/rates")
                    .help("The registered Reference component joined onto the row set."),
            NodeAttribute.of("on", "Join keys", "list", "required").placeholder("currency")
                    .help("Column(s) equated between the rows and the Reference."));

    private static final Map<String, List<NodeAttribute>> BY_TYPE = byType();

    private static Map<String, List<NodeAttribute>> byType() {
        for (List<NodeAttribute> table : List.of(COLLECTOR, OUTPUT, SINK_PERSISTENT, TRANSFORM_FILTER,
                TRANSFORM_ROUTE, TRANSFORM_DEDUP, TRANSFORM_SUMMARIZE, TRANSFORM_JOIN))
            for (NodeAttribute a : table) a.validate();   // whole-spec checks, once the builders are done
        Map<String, List<NodeAttribute>> m = new LinkedHashMap<>();
        // The acquisition node authors the WHOLE collector block, duplicate__* included — fingerprint
        // dedup executes in the poll cycle, and its former graph node was removed 2026-08-04.
        m.put(BuiltinNodeType.ACQUISITION.type(), COLLECTOR);
        m.put(BuiltinNodeType.TRANSFORM_FILTER.type(), TRANSFORM_FILTER);
        m.put(BuiltinNodeType.TRANSFORM_ROUTE.type(), TRANSFORM_ROUTE);
        m.put(BuiltinNodeType.TRANSFORM_DEDUP.type(), TRANSFORM_DEDUP);
        m.put(BuiltinNodeType.TRANSFORM_SUMMARIZE.type(), TRANSFORM_SUMMARIZE);
        m.put(BuiltinNodeType.TRANSFORM_JOIN.type(), TRANSFORM_JOIN);
        m.put(BuiltinNodeType.SINK_PERSISTENT.type(), SINK_PERSISTENT);
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
