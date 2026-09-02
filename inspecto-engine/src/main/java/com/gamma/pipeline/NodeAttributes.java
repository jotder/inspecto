package com.gamma.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The per-node-type config attribute tables, published on {@code GET /pipelines/node-types}. This is the
 * SERVER half of the same vocabulary {@code inspecto-ui/.../pipelines/node-attributes.ts} declares, and the
 * served copy is the source while the client table is its offline/mock fallback. Current knowledge:
 * {@code docs/okf/frontend/features/pipelines.md}; decision record: §3.1 of the archived
 * {@code docs/archived-documents/plans-archive/vocabulary-and-config-contract-plan.md}.
 *
 * <p>⚠ <b>These two tables must stay identical, and a test enforces it rather than a comment.</b>
 * {@code NodeAttributesContractTest} compares this table against the committed
 * {@code inspecto-ui/src/app/inspecto/contracts/node-attributes.contract.json}, and the TS
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
            // The dataset entry (ELT P3 S3c-2 / UI-S7): `connector: dataset` + this id. Like `connection`
            // it is mode-owned — the shared collector surface shows exactly one of the two, and the
            // connector itself stays derived (dataset mode ⇒ `dataset`), never asked.
            NodeAttribute.of("dataset", "Dataset", "autocomplete", "required").required(false)
                    .help("Consume another Pipeline's Dataset: each acquire cycle copies its new parquet "
                            + "snapshots into this pipeline's inbox (the producer's files are never deleted). "
                            + "Dataset entry only — leave blank for file feeds."),
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
                    .help("Target directory when \"After success\" is Move."),
            // Fetch concurrency (collector.fetch.*, RemoteAcquisitionHandler): how many files ONE
            // pipeline's acquisition downloads at once — each worker gets its own connector session
            // from a bounded pool, so this also sizes the remote-session pool. Fleet-level
            // acquisition concurrency stays -Dacquire.maxConcurrent; this is within-pipeline.
            NodeAttribute.of("fetch__parallel_fetch", "Parallel downloads", "number", "advanced")
                    .min(1).max(64)
                    .help("Remote Collectors only — a local inbox has nothing to download (files are pushed in by the producer). Files this pipeline downloads concurrently in one acquisition, each on its own connector session. Blank = 1 (sequential). The next acquisition starts on the following acquire tick, so fetching stays continuous."),
            NodeAttribute.of("fetch__rate_limit", "Download rate limit", "string", "advanced")
                    .placeholder("10MB/s")
                    .help("Remote Collectors only. Cap this pipeline's download bandwidth — a rate like 512KB/s, 10MB/s, or a bare number (bytes/s). Blank = unlimited."),
            // Consignment formation (CONSIGNMENT-HOME-1, 2026-09-02): the ConsignmentPlanner caps, homed
            // on the Collector because that is where the plan runs — in the poll cycle, before any sink
            // exists. Declared consignment__* so the dialog's nestKeys lands them on the node's nested
            // `consignment` map, which lowers VERBATIM into collector.consignment: (the block the parser
            // reads first; the legacy processing.batch: spelling is dual-read and healed on save). ⚠ Never
            // spec a flat spelling — the flat batch_max_files was write-only for weeks (G3).
            NodeAttribute.of("consignment__max_files", "Max files per consignment", "number", "optional")
                    .group("Consignment")
                    .min(1)
                    .help("Pack inbox files into one consignment until this many files. Blank = 1 (each file is its own consignment)."),
            NodeAttribute.of("consignment__max_bytes", "Max bytes per consignment", "number", "optional")
                    .group("Consignment")
                    .min(1)
                    .help("Or until their summed size would exceed this many bytes. Whichever cap trips first ends the consignment; a single larger file forms a consignment of one."),
            NodeAttribute.of("consignment__order", "Consignment order", "select", "optional")
                    .group("Consignment")
                    .defaultValue("mtime")
                    .options("mtime", "By arrival (file time)", "name", "By name (path order)")
                    .help("How inbox files are ordered before packing. Arrival (file time) is the default; name order is the opt-in for feeds whose stamps are unreliable — a copy resets mtime."));

    /**
     * Marker dedup (file-grain, → {@code processing.duplicate_check} + {@code dirs.markers}) — the
     * marker-file duplicate Guarantee the LOCAL poll path applies. It had its own
     * {@code transform.dedup.marker} node until P5-a (2026-08-16) folded it onto acquisition, where
     * the fingerprint policy ({@code duplicate__*}) already lived: both are decided in the poll cycle,
     * before anything is parsed.
     *
     * <p>⚠ <b>Kept separate from {@link #COLLECTOR} on purpose.</b> That list IS the {@code collector:}
     * block and is rendered whole by Onboarding's Collection stage too; these four keys live in
     * {@code processing:}/{@code dirs:} and are only <em>borrowed</em> by the acquisition NODE
     * ({@code PipelineEditable.ACQ_FOREIGN_KEYS}), so folding them in would give that stage four
     * fields it would write to a block nothing reads them in.
     *
     * <p>⚠ {@code duplicate_check} is the authored on/off and must stay explicit: if the presence of a
     * detail key were the switch, clearing "retention" in the form would silently disable dedup on the
     * next save. Defaults mirror the parser's ({@code PipelineConfigParser}: {@code .processed} / 90).
     */
    /**
     * The entry-node schedule ({@code trigger:}, T13 — {@code PipelineTrigger}): each pipeline runs
     * on its OWN cadence, gated per tick by {@code PipelineScheduler.dueThisTick}. Like the marker
     * keys, these are NOT part of the {@code collector:} block — the {@code trigger:} map is a
     * top-level config section the acquisition node borrows (lift {@code PipelineEditable:426},
     * lower {@code :649}). Cron wins when both are stated ({@code PipelineTrigger.of}); the space's
     * poll tick is the resolution floor for {@code every}. Sub-keys with no spec here
     * ({@code type}, {@code coalesce}, {@code on}, {@code from}) survive a save untouched.
     */
    public static final List<NodeAttribute> TRIGGER = List.of(
            NodeAttribute.of("trigger__every", "Run every", "string", "optional")
                    .placeholder("30s")
                    .help("This pipeline's own poll cadence — 30s, 5m, 2h, 1d, or a bare number of seconds. Blank = every tick of the space's poll interval. The space poll interval is the resolution floor: a 30s pipeline needs the space tick at 30s or less."),
            NodeAttribute.of("trigger__cron", "Run on a cron schedule", "string", "optional")
                    .placeholder("0 0 2 * * *")
                    .help("Calendar cadence in the operations time zone (e.g. daily at 02:00). When both are stated, cron wins over 'Run every'."),
            // The event-trigger half (ELT P3 S3b / UI-S7): `{type: event, on: dataset, from: …}`.
            // `type` stays derived — the save writes `event` when `on` is picked (node-config-build),
            // because `on:` under a schedule type is config the trigger parser silently ignores.
            // `on: commit` (upstream Pipeline commit) stays authorable via Additional config only.
            NodeAttribute.of("trigger__on", "Run when", "select", "optional")
                    .options("", "Poll / schedule (the default)",
                            "dataset", "A Dataset is written")
                    .help("Event trigger: run when the watched source publishes, instead of on a cadence. "
                            + "'A Dataset is written' pairs naturally with a dataset-entry collector — the "
                            + "write Signal supplies the latency, the acquire cycle does the copy."),
            NodeAttribute.of("trigger__from", "Dataset to watch", "autocomplete", "optional").required(true)
                    .dependsOn("trigger__on", "dataset")
                    .placeholder("datasets/orders_rollup")
                    .help("The Dataset whose writes fire this pipeline — datasets/<id> (a bare id works too). "
                            + "Usually the same Dataset the collector consumes."),
            NodeAttribute.of("trigger__coalesce", "Coalesce window", "string", "advanced")
                    .dependsOn("trigger__on", "dataset")
                    .placeholder("30s")
                    .help("Debounce a burst of writes into one admitted run — 30s, 5m, or a bare number of "
                            + "seconds. Blank = run per write."));

    public static final List<NodeAttribute> MARKER_DEDUP = List.of(
            // ⚠ tier `required` + required(false) — the always-visible-but-optional idiom. As `optional`
            // the switch sat behind the form's disclosure, so the drawer's Duplicate handling group
            // rendered as a heading over "Optional settings (1)" and NOTHING else (caught in-preview,
            // invisible to a unit test asserting the heading). The group's whole point is that this
            // switch is visible; the three detail keys below it stay advanced.
            NodeAttribute.of("duplicate_check", "Marker dedup", "boolean", "required").required(false)
                    .help("Skip a file whose marker already exists beside it — the local poll path's re-processing guard."),
            NodeAttribute.of("marker_extension", "Marker extension", "string", "advanced")
                    .placeholder(".processed")
                    .help("Suffix of the per-file marker written beside a processed input; a file whose marker exists is skipped."),
            NodeAttribute.of("retention_days", "Marker retention (days)", "number", "advanced").min(1)
                    .placeholder("90")
                    .help("Stale markers older than this are cleaned up (MarkerManager); blank = the engine default of 90."),
            NodeAttribute.of("markers_dir", "Markers directory", "string", "advanced")
                    .help("Where marker files land (dirs.markers); blank = the space convention."));

    /**
     * The unpack stage ({@code processing.unpack:}, unpack-stage plan Phase 6) — compressed/archived
     * inbox files expanded at the Collector before Consignments are planned. Like {@link #MARKER_DEDUP}
     * these keys are BORROWED from {@code processing:} (the node carries the nested {@code unpack} map
     * wholesale, like the sink's {@code intake}), and kept out of {@link #COLLECTOR} for the same
     * reason: Onboarding's Collection stage renders that list whole.
     *
     * <p>⚠ {@code depth} is deliberately NOT published: {@code PipelineConfig.Unpack} refuses any value
     * but 1 by name (nested archives are a non-feature), so offering it would author a config that
     * cannot load. Defaults mirror {@code Unpack.defaults()} — stated in help text, never as spec
     * {@code defaultValue}s, so an untouched form writes no {@code unpack:} block at all.
     */
    public static final List<NodeAttribute> UNPACK = List.of(
            // Same always-visible-but-optional idiom as `duplicate_check`: the switch is the point of
            // the group; the caps below it stay advanced.
            NodeAttribute.of("unpack__enabled", "Unpack compressed inputs", "boolean", "required").required(false)
                    .help("Expand compressed/archived inbox files (.gz/.bz2/.Z/.zip/.tar/.tar.gz) at the "
                            + "collector, before consignments are planned. Blank = on."),
            NodeAttribute.of("unpack__max_entries", "Max archive entries", "number", "advanced").min(1)
                    .placeholder("10000")
                    .help("Fail-closed cap on entries one archive may expand to. Blank = 10000."),
            NodeAttribute.of("unpack__max_entry_bytes", "Max bytes per expanded file", "number", "advanced").min(1)
                    .help("Fail-closed cap on the decompressed size of any single output file. Blank = 8 GiB."),
            NodeAttribute.of("unpack__max_total_bytes", "Max bytes per source", "number", "advanced").min(1)
                    .help("Fail-closed cap on total decompressed bytes one source may expand to. Blank = 32 GiB."),
            NodeAttribute.of("unpack__max_ratio", "Max decompression ratio", "number", "advanced").min(0)
                    .placeholder("10000")
                    .help("Fail-closed output/input ratio cap — the decompression-bomb tell; 0 disables it. "
                            + "Blank = 10000."),
            NodeAttribute.of("unpack__threads", "Unpack threads", "number", "advanced").min(1)
                    .help("Archives expanded concurrently (one archive per worker). Pure file I/O, but it "
                            + "adds to the same core budget as processing.threads. Blank = 1."),
            NodeAttribute.of("unpack__data_extensions", "Data extensions", "list", "advanced")
                    .help("Extensions treated as the DATA suffix for extension-insensitive duplicate "
                            + "identity — cdr.csv.gz, cdr.Z and bare cdr are one logical file. ⚠ Two files "
                            + "differing only by an extension on this list are one logical file too; narrow "
                            + "the list rather than expecting both. Clearing the field reverts to the "
                            + "default; the explicit empty-list opt-out (data_extensions[0]:) is authored "
                            + "in the pipeline file. Blank = .csv .tsv .txt .json .jsonl .ndjson .xml"));

    /** The {@code acquisition} node's published spec: the {@code collector:} block it authors, plus
     *  the marker-dedup + unpack keys it borrows from {@code processing:}/{@code dirs:}. */
    public static final List<NodeAttribute> ACQUISITION =
            Stream.of(COLLECTOR, MARKER_DEDUP, UNPACK, TRIGGER).flatMap(List::stream).toList();

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
                    .help("Codec for the output (e.g. snappy / zstd / gzip); blank = format default."),
            NodeAttribute.of("filename_column", "Source filename column", "identifier", "advanced")
                    .placeholder("file_name")
                    .help("Adds a column of this name carrying each row’s source file. New pipelines default to file_name; blank = no column (lineage stays in the ledger only)."));

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
        // Consignment formation used to be specced HERE as batch__* (lowering to processing.batch:). It
        // moved to the collector block on 2026-09-02 — the ConsignmentPlanner runs in the poll cycle,
        // before any sink exists, and a two-sink pipeline has ONE policy. See COLLECTOR's "Consignment"
        // group and CONSIGNMENT-HOME-1 (BACKLOG).
        // Concurrency (scheduler-system-config plan Part B): priority is SINK_PROC_OWNED (a flat
        // processing key, like threads); the intake__* keys nest to the node's `intake` map, which
        // lowers to the processing.intake: block the IntakeGovernor reads.
        attrs.add(NodeAttribute.of("priority", "Priority", "number", "advanced")
                .min(1).max(3)
                .help("Share weight (1-3) for this pipeline's consignments when execution slots are contended: 3 gets ~3x the throughput share of 1. Shares, never precedence — a priority-1 pipeline always keeps making progress. Blank = 1."));
        attrs.add(NodeAttribute.of("intake__max_files_per_cycle", "Intake cap (files/cycle)", "number", "advanced")
                .min(0)
                .help("This pipeline's admission cap, overriding the -Dingest.maxFilesPerCycle global; 0 = explicitly unbounded (exempts this pipeline from a fleet-wide cap). Blank = inherit the global."));
        attrs.add(NodeAttribute.of("intake__min_files_per_cycle", "Intake cap floor", "number", "advanced")
                .min(1)
                .help("Floor the adaptive controller may halve this pipeline's cap down to. Blank = inherit -Dingest.minFilesPerCycle."));
        attrs.add(NodeAttribute.of("intake__adaptive", "Adaptive intake control", "boolean", "advanced")
                .help("Whether cycle overrun adjusts this pipeline's cap; off pins it at the stated cap. Blank = inherit -Dingest.backpressure.adaptive."));
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
     * {@code {key, where, database}} that actually does the routing — has no spec because the
     * {@code list} type is {@code string[]} and these are MAPS.
     *
     * <p>⛔ <b>Do not close that by adding a map-list spec type.</b> Branches already have a dedicated
     * authoring surface (the Recipe view's branch rows: add/remove the branch, name it, type its
     * {@code when} predicate), and two of the three keys are NOT author-owned — {@code database} is
     * stamped by {@code PipelineEditable.routeSection} from the sink each {@code route:<key>} edge
     * feeds, and {@code key} IS that edge's name. Speccing {@code branches} would make it form-OWNED,
     * and an owned leaf is replaced wholesale on save — which is exactly the branch-destruction bug
     * {@code RouteBranch} exists to prevent. (Corrected 2026-08-28: this comment used to say the
     * branches are edited "on the canvas edges", which sent readers looking at the wrong surface.)
     *
     * <p>{@code route_column} was removed (D2): it was read by nothing.
     */
    public static final List<NodeAttribute> TRANSFORM_ROUTE = List.of(
            NodeAttribute.of("mode", "Route mode", "select", "required").defaultValue("case")
                    .options("case", "case (exclusive)", "clone", "clone (fan-out)")
                    .help("Branch keys, predicates and destinations are edited on the branch rows in the Recipe view."));

    /**
     * {@code transform.dedup} (record-grain, → {@code processing.dedup}) — the QUALIFY the engine
     * applies before the partitioned write ({@code ConsignmentIngestStrategy}). Distinct from the
     * file-level duplicate Guarantees (marker / fingerprint), which are never Steps.
     * Keys proven by {@code NodeConfigNameContractTest} (keys / order_by reach {@code cfg.dedup()}).
     */
    public static final List<NodeAttribute> TRANSFORM_DEDUP = List.of(
            NodeAttribute.of("keys", "Dedup keys", "list", "required").placeholder("call_id")
                    .help("Rows sharing these column values are duplicates; the first (per \"Order by\") is kept."),
            NodeAttribute.of("order_by", "Order by", "string", "optional").placeholder("event_ts DESC")
                    .help("Which duplicate wins — SQL ordering over the typed columns; blank = input order. REQUIRED once Scope declares a window: a durable ledger must never record a non-deterministic winner."),
            NodeAttribute.of("scope", "Scope", "string", "advanced").placeholder("window(P4D)")
                    .help("How far back a key is a duplicate: blank/consignment = within this Consignment only; window(<ISO-8601 period>), e.g. window(P4D), also suppresses keys admitted by earlier Consignments inside that window via the durable dedup ledger (runs at rest)."));

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
        for (List<NodeAttribute> table : List.of(COLLECTOR, TRIGGER, MARKER_DEDUP, OUTPUT, SINK_PERSISTENT,
                TRANSFORM_FILTER, TRANSFORM_ROUTE, TRANSFORM_DEDUP, TRANSFORM_SUMMARIZE, TRANSFORM_JOIN))
            for (NodeAttribute a : table) a.validate();   // whole-spec checks, once the builders are done
        Map<String, List<NodeAttribute>> m = new LinkedHashMap<>();
        // The acquisition node authors the WHOLE collector block, duplicate__* included — fingerprint
        // dedup executes in the poll cycle, and its former graph node was removed 2026-08-04. Marker
        // dedup joined it in P5-a (2026-08-16), so transform.dedup.marker is unspecced again: it is
        // read-compat only, never authored, and a spec would invite editing a node nothing emits.
        m.put(BuiltinNodeType.ACQUISITION.type(), ACQUISITION);
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
