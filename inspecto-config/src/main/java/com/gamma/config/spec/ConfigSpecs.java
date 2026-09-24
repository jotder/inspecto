package com.gamma.config.spec;

import com.gamma.api.PublicApi;

import java.util.List;
import java.util.Map;

import static com.gamma.config.spec.RawConfig.at;
import static com.gamma.config.spec.RawConfig.boolOr;
import static com.gamma.config.spec.RawConfig.intOr;
import static com.gamma.config.spec.RawConfig.present;
import static com.gamma.config.spec.RawConfig.str;

/**
 * The authored {@link ConfigSpec}s for every configuration type the system loads.
 *
 * <p>Each spec encodes — as data — the rules that today live implicitly in the {@code load} methods
 * ({@code PipelineConfig}, {@code EnrichmentConfig}, {@code JobConfig}) and in {@code ConfigValidator}.
 * The cross-field predicates are deliberately copied from those sources so the declarative spec and
 * the imperative loaders agree; the matching unit tests assert that equivalence on good/bad maps.
 *
 * <p>Fields are addressed by dotted path (see {@link RawConfig}); the paths match the nested
 * structure of the corresponding {@code .toon} file exactly.
 */
@PublicApi(since = "4.0.0")
public final class ConfigSpecs {

    private ConfigSpecs() {}

    /** A Collector duration as {@code PipelineConfigParser.toMillis} accepts it: bare seconds, or N s|m|h|d. */
    private static final String DURATION = "\\s*[+-]?\\d+\\s*[smhdSMHD]?\\s*";

    /** What {@code PipelineConfigParser.parseRate} accepts, minus its NaN/exponent/negative edge cases:
     *  a number, an optional binary unit, an optional per-second suffix. No {@code (?i)} — the pattern is
     *  also served to the SPA's JSON Schema, and a JS RegExp has no inline flags. */
    private static final String RATE = "\\s*\\d+(\\.\\d+)?\\s*([kKmMgG]?[bB])?\\s*(/[sS]|[pP][sS]|/[sS][eE][cC])?\\s*";

    /** Spec types in canonical order — also the set accepted by {@code GET /config/spec/{type}}. */
    public static final List<String> TYPES =
            List.of("pipeline", "enrichment", "job", "schema", "meta", "alert", "expectation",
                    "widget", "dashboard");

    /** The {@link ConfigSpec} for {@code type}, or {@code null} if {@code type} is unknown. */
    public static ConfigSpec forType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.toLowerCase()) {
            case "pipeline"   -> pipeline();
            case "enrichment" -> enrichment();
            case "job"        -> job();
            case "schema"     -> schema();
            case "meta"       -> meta();
            case "alert"      -> alert();
            case "expectation" -> expectation();
            case "widget"     -> widget();
            case "dashboard"  -> dashboard();
            default           -> null;
        };
    }

    // ── pipeline ────────────────────────────────────────────────────────────────

    public static ConfigSpec pipeline() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("name", "Pipeline name", FieldType.STRING,
                        "Display name. When `id` is absent the identity is derived from this (lowercased, "
                                + "spaces underscored), which is why renaming a pipeline without an `id` is a "
                                + "migration rather than an edit. Set `id` and this becomes a free-text label."),
                // Splitting identity from display name: everything downstream keys on the identity
                // (`PipelineConfig.identity().pipelineName()` — ~140 call sites), so making identity
                // explicit is what allows `name` to change without moving files, dirs, the commit log,
                // the acquisition ledger's sourceId and the Catalog Stream. Optional and absent from every
                // existing config, so the derived path below stays byte-identical until an author opts in.
                new FieldSpec("id", "Pipeline id",
                        "Stable identity, immutable once created. Absent = derived from `name` (the legacy "
                                + "behaviour). Lowercase letters, digits and underscores.",
                        FieldType.STRING, false, null, List.of(), "[a-z0-9][a-z0-9_]*", null, null),
                FieldSpec.of("description", "Description", FieldType.STRING,
                        "What this pipeline is for — the subtitle on its list row. Free text, read by no "
                                + "engine code."),
                FieldSpec.enumField("produces", "Produces", List.of("stream", "reference"), "stream",
                        "What the output registers as in the Catalog: an event/fact Stream (default) or a "
                                + "Reference Dataset (dimension/lookup) that enrichments can bind by name."),
                FieldSpec.of("stream", "Stream", FieldType.STRING,
                        "Logical Catalog Stream this pipeline is a member of (GLOSSARY §3 grouping); default = "
                                + "the pipeline's own name (strict 1:1). Pipelines sharing one name group under a "
                                + "single Stream in the catalog graph."),
                FieldSpec.enumField("reference.load", "Reference load mode",
                        List.of("replace", "upsert", "scd2"), "replace",
                        "How a produces:reference dataset is loaded: replace (full-replace, today's default), "
                                + "upsert (latest-version-wins by reference.key) or scd2 (slowly-changing history). "
                                + "upsert/scd2 require reference.key."),
                FieldSpec.of("reference.key", "Reference key columns", FieldType.LIST,
                        "Declared identity columns for upsert/scd2 dedup + versioning; each must exist in the "
                                + "pipeline schema."),
                FieldSpec.withDefault("reference.refresh_seconds", "Reference refresh seconds", FieldType.INT, 0,
                        "0 = re-materialize on collect only (today); >0 arms a periodic compaction/re-materialize "
                                + "timer (Phase-3)."),
                FieldSpec.required("dirs.poll", "Poll directory", FieldType.FILEPATH,
                        "Directory watched for incoming files. All managed dirs must live outside it."),
                FieldSpec.required("dirs.database", "Output database directory", FieldType.FILEPATH,
                        "Root of the Stage-1 partitioned output."),
                FieldSpec.of("dirs.backup", "Backup directory", FieldType.FILEPATH,
                        "Where processed source files are moved after a successful run."),
                FieldSpec.of("dirs.temp", "Temp directory", FieldType.FILEPATH, "Scratch space for a run."),
                FieldSpec.of("dirs.status_dir", "Status directory", FieldType.FILEPATH,
                        "Directory for run audit CSVs; a timestamped status file is created here."),
                FieldSpec.withDefault("processing.threads", "Consignment concurrency", FieldType.INT,
                        Runtime.getRuntime().availableProcessors(),
                        "Concurrent batches (semaphore permits over a virtual-thread executor). Default = this "
                                + "host's logical cores; duckdb_threads=0 divides the cores among them."),
                FieldSpec.withDefault("processing.duckdb_threads", "DuckDB threads/batch", FieldType.INT, 0,
                        "Per-batch DuckDB parallelism (PRAGMA threads): 0 = auto (cores ÷ threads, avoids "
                                + "oversubscription), -1 = DuckDB default (all cores per batch), N = exactly N."),
                FieldSpec.withDefault("processing.file_pattern", "File pattern", FieldType.STRING,
                        "glob:**/*.{csv,csv.gz}", "Glob selecting source files under the poll dir."),
                FieldSpec.of("processing.schema_file", "Schema file", FieldType.FILEPATH,
                        "Path to the *_schema.toon holding field selectors/types/partition keys. A runnable "
                                + "pipeline needs this, a schemas[] dispatch list, or a plugin ingester."),
                // The Profile Step's only knob (transform.profile, 2026-09-15). Declared rather than
                // left to UNDECLARED_BLOCKS: an at-rest block nothing can SEE is exactly the gap-10
                // debt that list records, and a new block has no reason to be born into it.
                FieldSpec.of("processing.profile.columns", "Profile columns", FieldType.LIST,
                        "Columns to profile (per-column row/null/distinct counts and min/max). Empty "
                                + "or absent profiles every inbound column."),
                // The Webhook sink's block (sink.webhook, 2026-09-23). ⛔ No url/token field by decision: the
                // target is an https Connection an administrator onboarded, and the parser refuses both keys.
                FieldSpec.of("webhook.connection", "Webhook Connection", FieldType.STRING,
                        "Name of the https Connection the at-rest chain's rows are POSTed to as JSON; it carries "
                                + "the endpoint and the bearer-token reference. Professional/Enterprise only."),
                FieldSpec.withDefault("webhook.batch_size", "Webhook rows per request", FieldType.INT, 500,
                        "Rows per JSON POST, 1-10000."),
                FieldSpec.of("webhook.retry", "Webhook retry", FieldType.MAP,
                        "Per-request retry, the Collector's grammar: count, backoff (EXPONENTIAL/LINEAR/FIXED), "
                                + "initial_delay, max_delay. Every attempt carries the same Idempotency-Key. "
                                + "Absent = one attempt."),
                FieldSpec.of("processing.ingester", "Plugin ingester class", FieldType.STRING,
                        "FQCN of a plugin ingester; when set, processing.segments must be non-empty."),
                // The Stage-2 arming condition (pipeline spec gap 8). Undeclared until 2026-08-31, which
                // is why a steps:/dedup/summarize/join pipeline could only learn it needed this key by
                // being REFUSED at registration — the rule below moves that answer to authoring time.
                FieldSpec.of("output_store", "Stage-2 output store", FieldType.STRING,
                        "Names the store an at-rest chain writes. Required to ARM a pipeline carrying "
                                + "steps:, processing.dedup, processing.summarize, processing.profile, "
                                + "processing.join or webhook: — "
                                + "those execute at rest (a pipeline_config: job), never on the linear "
                                + "ingest path, and the chain needs an authored name for what it writes."),
                // ── grammar: one concept, two spellings — `parsing.grammar` is CANONICAL ────────
                // The parser has preferred `parsing.grammar` over `processing.grammar` since the parsing:
                // block became the design-of-record (PipelineConfigParser#resolveGrammarRef), but only the
                // LEGACY spelling was declared here — so the spec published the deprecated key and stayed
                // silent about the one the engine actually reads. Both are declared now; the legacy one
                // keeps working (a deployed config must not start failing) and earns a WARNING instead.
                FieldSpec.of("parsing.grammar", "Parse grammar file", FieldType.FILEPATH,
                        "CANONICAL. Path to a reusable *.grammar.toon holding the parse settings, or a registry "
                                + "reference (grammar/<id>) as a Grammar-bound parser node lowers to. Inline "
                                + "csv_settings keys override the grammar file."),
                FieldSpec.of("processing.grammar", "Delimited grammar file (deprecated)", FieldType.FILEPATH,
                        "DEPRECATED alias of parsing.grammar, read only for configs authored before the "
                                + "parsing: block existed. parsing.grammar wins when both are present; nothing "
                                + "writes this spelling any more."),
                FieldSpec.enumField("processing.csv_settings.engine", "CSV engine",
                        List.of("auto", "duckdb", "java"), "auto",
                        "auto uses DuckDB's native reader for clean configs and the Java parser otherwise."),
                FieldSpec.withDefault("processing.csv_settings.delimiter", "Delimiter", FieldType.STRING, ",",
                        "Field delimiter; a blank value silently falls back to ','."),
                FieldSpec.withDefault("processing.csv_settings.has_header", "Has header", FieldType.BOOL, true,
                        "Whether source files carry a header row."),
                FieldSpec.of("processing.csv_settings.quote", "Quote character", FieldType.STRING,
                        "Single character wrapping fields that contain the delimiter; blank = \" (engine default)."),
                FieldSpec.of("processing.csv_settings.escape", "Escape character", FieldType.STRING,
                        "Single character escaping a literal quote inside a quoted field; blank = the quote, doubled."),
                FieldSpec.of("processing.csv_settings.comment", "Comment character", FieldType.STRING,
                        "Lines starting with this single character are skipped; blank = no comment handling."),
                FieldSpec.of("processing.csv_settings.encoding", "Encoding", FieldType.STRING,
                        "Source charset for the native reader (e.g. utf-8, latin-1, utf-16); blank = utf-8."),
                FieldSpec.enumField("processing.csv_settings.compression", "Input compression",
                        List.of("auto", "gzip", "zstd", "none"), "auto",
                        "read_csv input compression; blank = auto-detect by extension. Refused fail-closed "
                                + "outside this set — archives and other forms (.zip/.tar/.Z/.bz2) are expanded "
                                + "by the collector's unpack stage by extension, no setting needed."),
                FieldSpec.of("processing.csv_settings.strict_mode", "Strict mode", FieldType.BOOL,
                        "DuckDB strict_mode; blank = DuckDB default (true). false tolerates quote/column drift."),
                FieldSpec.of("processing.csv_settings.null_strings", "Null strings", FieldType.LIST,
                        "Literal text values read as SQL NULL (read_csv nullstr), e.g. ['', 'NULL', 'NaN']."),
                FieldSpec.withDefault("processing.unpack.enabled", "Unpack compressed inputs", FieldType.BOOL, true,
                        "Expand compressed/archived inbox files (.gz/.bz2/.Z/.zip/.tar/.tar.gz) at the "
                                + "collector, before consignments are planned. Only acts on files a decompressor "
                                + "claims AND the parse engine cannot read itself, so a plain inbox is untouched."),
                FieldSpec.withDefault("processing.unpack.max_entries", "Max archive entries", FieldType.INT, 10000,
                        "Fail-closed cap on entries one archive may expand to."),
                FieldSpec.withDefault("processing.unpack.max_entry_bytes", "Max bytes per expanded file",
                        FieldType.INT, 8L << 30,
                        "Fail-closed cap on the decompressed size of any single output file."),
                FieldSpec.withDefault("processing.unpack.max_total_bytes", "Max bytes per source",
                        FieldType.INT, 32L << 30,
                        "Fail-closed cap on total decompressed bytes one source may expand to."),
                FieldSpec.withDefault("processing.unpack.max_ratio", "Max decompression ratio",
                        FieldType.INT, 10000,
                        "Fail-closed output/input ratio cap — the classic decompression-bomb tell; 0 disables it."),
                FieldSpec.withDefault("processing.unpack.threads", "Unpack threads", FieldType.INT, 1,
                        "Archives expanded concurrently (one archive per worker). Pure file I/O, no database "
                                + "connection — but it adds to the same core budget as processing.threads."),
                // ⛔ MIRROR: the engine's copy is LogicalNames.DEFAULT_DATA_EXTENSIONS, which this module
                // cannot import (inspecto-config sits BELOW inspecto-etl). LogicalNamesTest pins the two
                // equal — update both or the published default lies about what the engine does.
                FieldSpec.withDefault("processing.unpack.data_extensions", "Data extensions",
                        FieldType.LIST,
                        List.of(".csv", ".tsv", ".txt", ".json", ".jsonl", ".ndjson", ".xml"),
                        "Extensions treated as the DATA suffix when deriving a file's extension-insensitive "
                                + "identity — at most one is stripped, after any compression suffixes, so "
                                + "cdr.csv.gz, cdr.Z and bare cdr are ONE logical file for duplicate checking. "
                                + "⚠ The dual: two files whose names differ only by an extension on this list "
                                + "(report.csv / report.json) are also one logical file, and the second is "
                                + "skipped as a duplicate once a COMPRESSED spelling of that name has been "
                                + "processed. Set EMPTY to opt out entirely (verbatim names only)."),
                FieldSpec.of("processing.csv_settings.ignore_errors", "Skip unparseable rows", FieldType.BOOL,
                        "blank = true (the long-standing behaviour): a row read_csv cannot parse is dropped "
                                + "instead of failing the run. false makes the batch fail on the first bad row."),
                FieldSpec.of("processing.csv_settings.null_padding", "Pad short rows with NULLs", FieldType.BOOL,
                        "blank = the frontend default (false for delimited, true for the line-reader "
                                + "frontends). true keeps a row that ran out of columns, NULL-filling the rest."),
                FieldSpec.of("processing.csv_settings.store_rejects", "Capture rejected rows", FieldType.BOOL,
                        "blank = true: rejected rows are captured and drained to errors/<base>_errors.csv. "
                                + "false skips capture entirely — reject rows carry raw source data."),
                FieldSpec.of("processing.csv_settings.rejects_table", "Rejects table", FieldType.STRING,
                        "Name of the per-row reject table read_csv writes; blank = reject_errors. Must be a "
                                + "bare identifier."),
                FieldSpec.of("processing.csv_settings.rejects_scan", "Rejects scan table", FieldType.STRING,
                        "Name of the per-file reject scan table; blank = reject_scans. Must be a bare "
                                + "identifier."),
                FieldSpec.of("processing.csv_settings.rejects_limit", "Rejects limit", FieldType.INT,
                        "Max rejected rows stored per file; blank or 0 = unlimited."),
                FieldSpec.of("processing.csv_settings.include_prefixes", "Include prefixes", FieldType.LIST,
                        "Row allow-list: keep rows whose filter_target_column starts with any of these."),
                FieldSpec.of("processing.csv_settings.include_regex", "Include regex", FieldType.LIST,
                        "Row allow-list (regexp_matches): keep rows whose filter_target_column matches any."),
                FieldSpec.of("processing.csv_settings.exclude_prefixes", "Exclude prefixes", FieldType.LIST,
                        "Row deny-list: drop rows whose filter_target_column starts with any of these."),
                FieldSpec.of("processing.csv_settings.exclude_regex", "Exclude regex", FieldType.LIST,
                        "Row deny-list (regexp_matches): drop rows whose filter_target_column matches any."),
                FieldSpec.withDefault("processing.csv_settings.filter_target_column", "Filter target column",
                        FieldType.INT, 0, "0-based selector index the include/exclude filters apply to."),
                FieldSpec.of("processing.csv_settings.where", "Row predicate (post-parse)", FieldType.STRING,
                        "SQL predicate over the MAPPED, typed target columns, e.g. amount > 0 — applied "
                                + "after parsing. Different moment from include_*/exclude_*, which are "
                                + "regexes over one raw column before parsing. NULL rows are dropped."),
                // ── consignment caps: one concept, two spellings — `collector.consignment` is CANONICAL ──
                // Moved 2026-09-02 (CONSIGNMENT-HOME-1): the ConsignmentPlanner runs in the poll cycle, so
                // the Collector owns how files are cut into Consignments. The legacy spelling keeps
                // working (a deployed config must not start failing) and the editor heals it on save.
                FieldSpec.withDefault("collector.consignment.max_files", "Consignment max files", FieldType.INT, 1,
                        "CANONICAL. Files packed into one Consignment; raise above 1 for intra-consignment parallelism."),
                // ── remote-Collector resilience + throttle (PROCESSOR-RELEASE-READINESS-1 G4/G5, 2026-09-23) ──
                // PipelineConfigParser.parseCollector reads all of these; until now only the parser knew them,
                // so a bad duration or count surfaced at LOAD instead of at the save. The duration pattern is
                // exactly what the parser's toMillis accepts (a bare number = seconds, or N s|m|h|d).
                // ⚠ NO spec defaults here, deliberately: the config pane seeds spec defaults into the saved
                // file (config.component.ts toAttrSpecs), and a written circuit_breaker block ARMS the
                // breaker — the parser enables it on presence. Each default is stated in the description.
                new FieldSpec("collector.fetch.rate_limit", "Download rate limit",
                        "Remote Collectors only: cap on this pipeline's download bandwidth — 512KB/s, 10MB/s, "
                                + "1GB/s or a bare number of bytes/s. Blank = unlimited.",
                        FieldType.STRING, false, null, List.of(), RATE, null, null),
                FieldSpec.of("collector.retry.count", "Retries", FieldType.INT,
                        "Remote Collectors only: extra attempts for a failed listing or file download. Default 0 "
                                + "(one attempt)."),
                FieldSpec.enumField("collector.retry.backoff", "Retry backoff",
                        List.of("EXPONENTIAL", "LINEAR", "FIXED", "CONSTANT"), null,
                        "How the delay grows between retries (CONSTANT is an alias of FIXED); full-jittered and "
                                + "capped at max_delay. Default EXPONENTIAL."),
                new FieldSpec("collector.retry.initial_delay", "First retry delay",
                        "Delay before the first retry: a bare number of seconds or N s|m|h|d. Default 1s.",
                        FieldType.STRING, false, null, List.of(), DURATION, null, null),
                new FieldSpec("collector.retry.max_delay", "Longest retry delay",
                        "Cap on any one retry delay: a bare number of seconds or N s|m|h|d. Default 60s.",
                        FieldType.STRING, false, null, List.of(), DURATION, null, null),
                FieldSpec.of("collector.circuit_breaker.failure_threshold", "Circuit breaker threshold", FieldType.INT,
                        "Remote Collectors only: consecutive failed listings that trip the breaker, which then "
                                + "skips acquisition until the cooldown passes. The block's presence turns it on. "
                                + "Default 5."),
                new FieldSpec("collector.circuit_breaker.cooldown", "Circuit breaker cooldown",
                        "How long a tripped breaker skips acquisition before one trial listing: a bare number of "
                                + "seconds or N s|m|h|d. Default 5m.",
                        FieldType.STRING, false, null, List.of(), DURATION, null, null),
                // ── source-side post-action (PROCESSOR-RELEASE-READINESS-1, sink.archive, 2026-09-24) ──
                // RemoteAcquisitionHandler.resolvePostAction reads an unknown on_success as RETAIN with a
                // run-time log line only, so a typo silently archived nothing. No spec defaults, as above.
                FieldSpec.enumField("collector.post_action.on_success", "After success",
                        List.of("RETAIN", "DELETE", "MOVE", "RENAME", "TAG"), null,
                        "Remote Collectors only: what happens to the source-side original after a successful "
                                + "fetch. MOVE needs archive_path. Default RETAIN."),
                FieldSpec.of("collector.post_action.archive_path", "Archive path", FieldType.STRING,
                        "The MOVE target on the source; yyyy/yy/MM/dd/HH/mm/ss resolve against now."),
                FieldSpec.enumField("collector.post_action.on_unsupported", "When the connector cannot",
                        List.of("FAIL", "WARN_AND_CONTINUE", "IGNORE"), null,
                        "When the connector lacks the post-action's capability: FAIL stops the cycle, the "
                                + "others retain the file. Default WARN_AND_CONTINUE."),
                FieldSpec.withDefault("processing.batch.max_files", "Consignment max files (deprecated)", FieldType.INT, 1,
                        "DEPRECATED alias of collector.consignment.max_files, read only when the canonical block is "
                                + "absent; the editor rewrites it into collector.consignment on the next save."),
                FieldSpec.withDefault("processing.priority", "Priority", FieldType.INT, 1,
                        "Share weight (1-3) for this pipeline's Consignments when execution slots are "
                                + "contended: 3 gets ~3x the throughput share of 1. Shares, never "
                                + "precedence - a priority-1 pipeline always keeps making progress."),
                FieldSpec.of("processing.duckdb.temp_directory", "DuckDB scratch dir", FieldType.FILEPATH,
                        "Directory for the per-batch temp DB and DuckDB spill; defaults to dirs.temp (never the system /tmp). Point at the roomiest disk for very large files."),
                FieldSpec.of("processing.duckdb.memory_limit", "DuckDB memory limit", FieldType.STRING,
                        "RAM cap per worker connection (DuckDB size string, e.g. '16GB'); beyond it DuckDB spills to temp_directory. Blank = DuckDB default (~80% RAM)."),
                FieldSpec.of("processing.duckdb.max_temp_directory_size", "DuckDB spill cap", FieldType.STRING,
                        "Hard cap on spill size (e.g. '900GB') so a runaway query fails fast instead of filling the disk."),
                FieldSpec.withDefault("processing.chunking.max_file_bytes", "Auto-chunk threshold (bytes)", FieldType.LONG, 8_589_934_592L,
                        "Files larger than this are streamed in bounded chunks to cap scratch; 0 = disabled. Defaults to 8 GiB — high enough that only pathological single files chunk."),
                FieldSpec.of("processing.chunking.target_chunk_bytes", "Target chunk size (bytes)", FieldType.LONG,
                        "Approximate size of each chunk when chunking is active; defaults to the threshold."),
                // Per-pipeline intake admission-control override (T15 follow-up). Every key is optional and
                // inherits its -Dingest.* global when unset — the spec deliberately declares no defaults,
                // because "absent" must stay distinguishable from "stated" (an absent key inherits live).
                FieldSpec.of("processing.intake.max_files_per_cycle", "Intake cap (files/cycle)", FieldType.INT,
                        "This pipeline's admission cap, overriding -Dingest.maxFilesPerCycle; 0 = explicitly unbounded (exempts this pipeline from a fleet-wide cap). Unset = inherit the global."),
                FieldSpec.of("processing.intake.min_files_per_cycle", "Intake cap floor", FieldType.INT,
                        "Floor the adaptive controller may halve this pipeline's cap down to (>= 1). Unset = inherit -Dingest.minFilesPerCycle."),
                FieldSpec.of("processing.intake.adaptive", "Adaptive intake control", FieldType.BOOL,
                        "Whether cycle overrun adjusts this pipeline's cap; false pins it at the stated cap. Unset = inherit -Dingest.backpressure.adaptive."),
                FieldSpec.withDefault("processing.streaming.large_file_bytes", "Streaming generation-mode threshold (bytes)",
                        FieldType.LONG, 268_435_456L,
                        "Plugin-ingester batches whose largest member is >= this run in bounded generation mode (huge files); smaller batches use union mode (many small files packed → one transform/write). 0 = always union."),
                FieldSpec.withDefault("processing.streaming.flush_records", "Streaming generation row budget",
                        FieldType.LONG, 5_000_000L,
                        "Rows per generation flush in generation mode; bounds scratch per generation."),
                FieldSpec.enumField("output.format", "Output format",
                        List.of("CSV", "PARQUET"), "CSV", "Stage-1 output file format."),
                FieldSpec.of("output.compression", "Output compression", FieldType.STRING,
                        "Codec for the output (e.g. snappy); blank = format default."),
                FieldSpec.of("output.filename_column", "Source filename column", FieldType.STRING,
                        "Adds a column of this name carrying each row's source file (B4); "
                                + "blank = no column (lineage stays in the ledger only)."),
                // DuckLake registration (DuckLakeRegistrar.registerOne). Optional: catalog_url, data_path and
                // table become required only when enabled is true — ConfigSafetyValidator enforces that and
                // jails data_path, so none is FieldSpec.required here. A sinks[] entry's own ducklake block
                // overrides this one.
                FieldSpec.withDefault("output.ducklake.enabled", "Register in DuckLake", FieldType.BOOL, false,
                        "Register each written file into a DuckLake table after the write."),
                FieldSpec.of("output.ducklake.catalog_url", "DuckLake catalog URL", FieldType.STRING,
                        "The DuckLake catalog backend, attached as ducklake:<this>. Required when enabled."),
                FieldSpec.of("output.ducklake.data_path", "DuckLake data path", FieldType.FILEPATH,
                        "DuckLake DATA_PATH; resolved under the Space and path-jailed. Required when enabled."),
                FieldSpec.withDefault("output.ducklake.schema", "DuckLake schema", FieldType.STRING, "main",
                        "Catalog schema the table is registered in."),
                FieldSpec.of("output.ducklake.table", "DuckLake table", FieldType.STRING,
                        "Table the files are registered into. Required when enabled."),
                // The plural destination block (PipelineConfigParser.parseOutputAndSinks). A list of
                // OBJECTS, so its element shape is declared here and refused at save when malformed —
                // before, a sinks: map, a comma string or an entry with no database parsed as "no sinks"
                // or threw at config load. An entry key it omits inherits output:'s value, so no item
                // but database is required. The path jail + format/compression allow-list and the
                // enabled-ducklake requirements stay ConfigSafetyValidator.checkSink's.
                FieldSpec.listOf("sinks", "Destinations",
                        "Where the pipeline writes, one entry per destination; each writes the same rows. "
                                + "Absent = the single output: + dirs.database destination.",
                        List.of(
                                FieldSpec.required("database", "Database directory", FieldType.FILEPATH,
                                        "This destination's output root; resolved under the Space and path-jailed."),
                                new FieldSpec("format", "Output format",
                                        "This destination's file format; omitted = output.format.",
                                        FieldType.ENUM, false, null, List.of("CSV", "PARQUET"), null, "select", null),
                                FieldSpec.of("compression", "Output compression", FieldType.STRING,
                                        "This destination's codec; omitted = output.compression."),
                                FieldSpec.of("filename_column", "Source filename column", FieldType.STRING,
                                        "This destination's source-file column; omitted = output.filename_column."),
                                FieldSpec.of("ducklake", "DuckLake registration", FieldType.MAP,
                                        "This destination's own DuckLake block; omitted = output.ducklake."),
                                FieldSpec.of("ducklake.enabled", "Register in DuckLake", FieldType.BOOL,
                                        "Register this destination's files into a DuckLake table."),
                                FieldSpec.of("ducklake.catalog_url", "DuckLake catalog URL", FieldType.STRING,
                                        "Required when enabled."),
                                FieldSpec.of("ducklake.data_path", "DuckLake data path", FieldType.FILEPATH,
                                        "Required when enabled; path-jailed."),
                                FieldSpec.of("ducklake.schema", "DuckLake schema", FieldType.STRING,
                                        "Catalog schema; default main."),
                                FieldSpec.of("ducklake.table", "DuckLake table", FieldType.STRING,
                                        "Required when enabled.")))
        );

        int cores = Runtime.getRuntime().availableProcessors();
        List<CrossFieldRule> rules = List.of(
                // The parser refuses this combination too (that is what makes the guarantee hold for a
                // hand-edited file), but a load-time throw would surface as a config that silently vanished
                // from the read surface. Catching it here turns it into a 422 at the moment of authoring.
                new CrossFieldRule(
                        "template-cannot-be-active",
                        "template: true and active: true contradict — a template is a non-runnable authoring "
                                + "starting point; remove template: true to arm the pipeline for execution.",
                        Severity.ERROR,
                        List.of("template", "active"),
                        raw -> !(Boolean.parseBoolean(str(raw, "template"))
                                && Boolean.parseBoolean(str(raw, "active")))),
                // Both of these mirror a refusal the PARSER already makes at config load. The parser is
                // what makes the guarantee hold for a hand-edited file; catching them here turns a
                // load-time throw — which surfaces as a config that silently vanished from the read
                // surface — into a 422 at the moment of authoring. Same reasoning as
                // template-cannot-be-active above.
                //
                // ⚠ Filed as a real gap: the offline mock ALREADY refused a bad source_timezone on the
                // pipeline write while no Java route did, so the mock was ahead of the server rather
                // than mirroring it. This rule is what makes that mock behaviour true.
                // Pipeline spec Wave 1, gap 8 — output_store: arming was undiscoverable. FOUR refusals
                // in PipelineConfig.prepare() (summarize / dedup / join / steps) already say this, but
                // they fire at REGISTRATION: the author saves happily, activates, and only then learns
                // the pipeline cannot run. Declaring the requirement lets the UI show it and a save
                // return a field-anchored 422. ⚠ The predicate mirrors prepare()'s condition exactly,
                // `active` included — an inactive draft is allowed to be incomplete, and refusing one
                // here would break authoring a pipeline in progress.
                new CrossFieldRule(
                        "stage-two-blocks-require-output-store",
                        "An ACTIVE pipeline carrying steps:, processing.dedup, processing.summarize, "
                                + "processing.profile, processing.join or webhook: must declare a top-level output_store:. Those blocks "
                                + "execute at rest (a pipeline_config: job over the landed store), never "
                                + "on the linear ingest path, so without output_store: they have nowhere "
                                + "to write and the pipeline refuses to arm. Author output_store:, keep "
                                + "the pipeline inactive (active: false), or remove the block.",
                        Severity.ERROR,
                        List.of("output_store", "steps", "processing.dedup",
                                "processing.summarize", "processing.profile", "processing.join", "webhook"),
                        raw -> {
                            if (!Boolean.parseBoolean(str(raw, "active"))) return true;
                            boolean needs = false;
                            for (String block : List.of("steps", "processing.dedup",
                                                        "processing.summarize", "processing.profile",
                                                        "processing.join", "webhook"))
                                needs |= authored(raw, block);
                            return !needs || present(raw, "output_store");
                        }),
                // Pipeline spec Wave 0, item 4 — two spellings for one concept. A WARNING, never an
                // ERROR: the legacy key still READS, so refusing it would break deployed configs to make
                // a naming point. The finding is what turns "silently deprecated" into something an
                // author is told once, at the moment they save.
                new CrossFieldRule(
                        "parsing-grammar-is-canonical",
                        "processing.grammar is the deprecated spelling of parsing.grammar. It is still read, "
                                + "and parsing.grammar wins when both are set — but nothing writes it any "
                                + "more, so move the value to parsing.grammar to keep one spelling per "
                                + "concept.",
                        Severity.WARNING,
                        List.of("processing.grammar", "parsing.grammar"),
                        raw -> {
                            String legacy = str(raw, "processing.grammar");
                            return legacy == null || legacy.isBlank();
                        }),
                new CrossFieldRule(
                        "parsing-source-timezone-resolvable",
                        "parsing.source_timezone must be an IANA region id the query engine accepts — "
                                + "'Asia/Kolkata', 'Europe/Berlin', 'UTC'. A fixed offset (+05:30, Z) is "
                                + "not accepted: it cannot express daylight saving, and the engine "
                                + "rejects it outright.",
                        Severity.ERROR,
                        List.of("parsing.source_timezone"),
                        raw -> {
                            String tz = str(raw, "parsing.source_timezone");
                            // Absent is legal — no zone declared is the default, wall-clock behaviour.
                            return tz == null || tz.isBlank()
                                    || SourceZoneGrammar.zoneRefusal(tz, "x") == null;
                        }),
                new CrossFieldRule(
                        "parsing-formats-carry-no-zone-directive",
                        "parsing.delimited.date_formats / .timestamp_formats must not use the zone "
                                + "directives %z or %Z. The offset they parse is then re-rendered in the "
                                + "SERVER's zone, so the same file imports differently on different "
                                + "machines. Declare the zone instead — parsing.source_timezone, or "
                                + "raw.fields[].timezone / .timezone_column for one column.",
                        Severity.ERROR,
                        List.of("parsing.delimited.date_formats", "parsing.delimited.timestamp_formats"),
                        raw -> {
                            // ⚠ Only the delimited: block — a list authored at parsing: level is not read
                            // by mergeParsing at all, so refusing one there would 422 on something inert.
                            for (String key : List.of("parsing.delimited.date_formats",
                                                      "parsing.delimited.timestamp_formats")) {
                                if (!(at(raw, key) instanceof List<?> formats)) continue;
                                for (Object f : formats)
                                    if (f != null && SourceZoneGrammar.zoneDirectiveIn(String.valueOf(f)) != 0)
                                        return false;
                            }
                            return true;
                        }),
                new CrossFieldRule(
                        "plugin-ingester-requires-segments",
                        "processing.segments must be a non-empty map when processing.ingester is set.",
                        Severity.ERROR,
                        List.of("processing.ingester", "processing.segments"),
                        raw -> {
                            String ing = str(raw, "processing.ingester");
                            if (ing == null || ing.isBlank()) {
                                return true;
                            }
                            Object segs = at(raw, "processing.segments");
                            return segs instanceof Map<?, ?> m && !m.isEmpty();
                        }),
                new CrossFieldRule(
                        "reference-upsert-requires-key",
                        "reference.load=upsert|scd2 requires a non-empty reference.key (the identity columns "
                                + "to dedup/version on).",
                        Severity.ERROR,
                        List.of("reference.load", "reference.key"),
                        raw -> {
                            String load = str(raw, "reference.load");
                            if (load == null
                                    || !(load.equalsIgnoreCase("upsert") || load.equalsIgnoreCase("scd2"))) {
                                return true;   // replace/absent → no key required
                            }
                            Object key = at(raw, "reference.key");
                            if (key instanceof List<?> l) {
                                return !l.isEmpty();
                            }
                            return key != null && !key.toString().isBlank();
                        }),
                new CrossFieldRule(
                        "threads-x-duckdb-threads-oversubscription",
                        "processing.threads × processing.duckdb_threads should not exceed available cores ("
                                + cores + ") — concurrent batches may oversubscribe the CPU.",
                        Severity.WARNING,
                        List.of("processing.threads", "processing.duckdb_threads"),
                        raw -> {
                            int d = intOr(raw, "processing.duckdb_threads", 0);
                            if (d <= 0) {
                                return true;
                            }
                            int t = intOr(raw, "processing.threads", 4);
                            return (long) t * d <= cores;
                        }),
                new CrossFieldRule(
                        "duckdb-engine-x-skip-tail-columns",
                        "csv_settings.engine=duckdb with skip_tail_columns>0 — the native reader rejects "
                                + "over-wide rows rather than trimming them; use engine=java/auto to retain them.",
                        Severity.WARNING,
                        List.of("processing.csv_settings.engine", "processing.csv_settings.skip_tail_columns"),
                        raw -> {
                            boolean duckdb = "duckdb".equalsIgnoreCase(str(raw, "processing.csv_settings.engine"));
                            int skipTail = intOr(raw, "processing.csv_settings.skip_tail_columns", 0);
                            return !(duckdb && skipTail > 0);
                        }),
                new CrossFieldRule(
                        "threads-vs-batch-max-files",
                        "processing.threads>1 with batch.max_files=1 yields only file-level parallelism; "
                                + "raise batch.max_files for intra-batch packing.",
                        Severity.WARNING,
                        List.of("processing.threads", "processing.batch.max_files"),
                        raw -> {
                            int t = intOr(raw, "processing.threads", 4);
                            int maxFiles = intOr(raw, "processing.batch.max_files", 1);
                            return !(t > 1 && maxFiles == 1);
                        }),
                new CrossFieldRule(
                        "duplicate-check-retention",
                        "duplicate_check.enabled=true with retention_days<=0 deletes every marker on the next "
                                + "cleanup; set retention_days >= 1.",
                        Severity.WARNING,
                        List.of("processing.duplicate_check.enabled", "processing.duplicate_check.retention_days"),
                        raw -> {
                            boolean enabled = boolOr(raw, "processing.duplicate_check.enabled", false);
                            int retention = intOr(raw, "processing.duplicate_check.retention_days", 90);
                            return !(enabled && retention <= 0);
                        })
        );
        return new ConfigSpec("pipeline", fields, rules);
    }

    // ── enrichment ──────────────────────────────────────────────────────────────

    public static ConfigSpec enrichment() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("name", "Enrichment name", FieldType.STRING, "Stable id for the Stage-2 job."),
                FieldSpec.required("input.database", "Input database", FieldType.FILEPATH,
                        "Root of the Stage-1 Hive-partitioned output to read."),
                FieldSpec.enumField("input.format", "Input format", List.of("PARQUET", "CSV"), "PARQUET",
                        "Format of the Stage-1 output."),
                // Both partition keys are REQUIRED to mirror EnrichmentConfig.fromMap, which throws
                // when either is absent — optional here meant the write route's 422 gate never fired
                // and the config only failed at registration. An EMPTY list is legal (unpartitioned):
                // RawConfig.present is true for [], so required checks presence, not entries.
                FieldSpec.required("input.partitions", "Input partitions", FieldType.LIST,
                        "Hive partition columns present on the input; empty = unpartitioned."),
                FieldSpec.required("output.database", "Output database", FieldType.FILEPATH,
                        "Where enriched output is written."),
                FieldSpec.enumField("output.format", "Output format", List.of("PARQUET", "CSV"), "PARQUET",
                        "Enriched output format."),
                FieldSpec.of("output.compression", "Output compression", FieldType.STRING,
                        "Codec for the output (e.g. snappy)."),
                FieldSpec.required("output.partitions", "Output partitions", FieldType.LIST,
                        "Output grain; may differ from the input grain. Empty = unpartitioned."),
                FieldSpec.of("transform", "Transform SQL", FieldType.SQL,
                        "Inline SQL reading from the 'input' view and any references; or use transform_file."),
                FieldSpec.of("transform_file", "Transform SQL file", FieldType.FILEPATH,
                        "Path to a .sql file used when 'transform' is absent."),
                FieldSpec.of("triggers.on_pipeline", "Trigger pipeline", FieldType.STRING,
                        "Upstream pipeline/enrichment whose commit triggers an incremental recompute."),
                FieldSpec.of("triggers.schedule_seconds", "Schedule seconds", FieldType.LONG,
                        "Interval for a full completeness recompute; <=0 disables.")
        );
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "transform-or-transform-file",
                        "An enrichment needs either an inline 'transform' or a 'transform_file'.",
                        Severity.ERROR,
                        List.of("transform", "transform_file"),
                        raw -> present(raw, "transform") || present(raw, "transform_file"))
        );
        return new ConfigSpec("enrichment", fields, rules);
    }

    // ── job ───────────────────────────────────────────────────────────────────

    public static ConfigSpec job() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("job.name", "Job name", FieldType.STRING, "Unique job name."),
                FieldSpec.required("job.type", "Job type", FieldType.STRING,
                        "The Job Type id — an open registry key (built-ins: enrich, report, maintenance, "
                                + "pipeline; plus any module/pack-provided id). See GET /jobs/types."),
                new FieldSpec("job.cron", "Cron", "Calendar schedule (5 or 6 cron fields), or blank.",
                        FieldType.CRON, false, null, List.of(), null, "cron-editor", null),
                FieldSpec.of("job.on_pipeline", "Trigger pipeline", FieldType.STRING,
                        "Run when this upstream pipeline/job commits a batch."),
                FieldSpec.withDefault("job.enabled", "Enabled", FieldType.BOOL, true,
                        "Whether the scheduler arms this job.")
        );
        // ⚠ No "job.type must be one of enrich|report|maintenance" rule: the type is an OPEN registry
        // key (pipeline, plus module/pack-provided ids) and JobConfig.fromMap already refuses an unknown
        // one against the live registry. The stale closed list here would have 422'd every pipeline job
        // the moment POST /jobs started running the spec (JOB-SPEC-1, 2026-09-06).
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "cron-field-count",
                        "job.cron, when present, must have 5 or 6 whitespace-separated fields.",
                        Severity.ERROR,
                        List.of("job.cron"),
                        raw -> {
                            String cron = str(raw, "job.cron");
                            if (cron == null || cron.isBlank()) {
                                return true;
                            }
                            int n = cron.trim().split("\\s+").length;
                            return n == 5 || n == 6;
                        })
        );
        return new ConfigSpec("job", fields, rules);
    }

    // ── alert (v4.1, B5) ─────────────────────────────────────────────────────────

    /** The Alert Rule (an {@code alert-rule} component under {@code <write-root>/registry}) executed by the core alert engine (drafted by diagnose-and-alert). */
    public static ConfigSpec alert() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("alert.name", "Rule name", FieldType.STRING,
                        "Unique, kebab-case alert rule name."),
                // DUCKLE-C1: metric / threshold / window are required PER SHAPE, not per field - a
                // freshness rule takes none of them, a measure or Investigation rule takes a threshold
                // but no window. FieldSpec has no conditional-required (the expectation spec below hit
                // the same wall), so the requirement lives in the alert-*-by-shape rules at the end.
                FieldSpec.enumField("alert.metric", "Metric",
                        List.of("error_rate", "failed_batches", "rejected_files", "duration_ms"), null,
                        "The batches-ledger metric the rule watches. Required for a ledger rule."),
                FieldSpec.enumField("alert.comparator", "Comparator",
                        List.of("gt", "gte", "lt", "lte"), "gt", "How the value meets the threshold."),
                FieldSpec.of("alert.threshold", "Threshold", FieldType.STRING,
                        "Positive number; error_rate is a fraction in (0, 1]. Required unless the rule "
                                + "checks freshness (alert.maximumAge)."),
                FieldSpec.of("alert.window", "Window", FieldType.STRING,
                        "Ns/Nm/Nh/Nd elapsed time, or Nb = the last N batches (e.g. 1h, 30m, 20b). "
                                + "Required for a ledger rule; a Dataset, freshness or Investigation rule "
                                + "takes none."),
                FieldSpec.enumField("alert.severity", "Severity",
                        List.of("INFO", "WARNING", "CRITICAL"), "WARNING", "Operator-facing severity."),
                FieldSpec.of("alert.onPipeline", "Pipeline", FieldType.STRING,
                        "Restrict to one pipeline (display or normalized name); blank = every pipeline."),
                // DUCKLE-C1 freshness rule: authored with alert.dataset and NOTHING else from the
                // ledger-metric vocabulary. ⚠ Nd/Nh/Nm/Ns only — a batch (Nb) window is not a clock,
                // and freshness is the one check whose trigger is the passage of time.
                FieldSpec.of("alert.maximumAge", "Maximum age", FieldType.STRING,
                        "Dataset freshness limit: Ns/Nm/Nh/Nd since the Dataset last published "
                                + "(e.g. 6h, 1d). Requires alert.dataset; takes no metric, window or "
                                + "threshold. Absent = this rule does not check freshness."),
                // LA-23 Investigation rule: watches a Measure over an Investigation's sealed Working Set.
                // Authored ONLY through POST /inv/investigations/{id}/alert-rules (the generic alert
                // routes refuse this kind), declared here so the spec and AlertRule.fromMap agree.
                FieldSpec.of("alert.investigation", "Investigation", FieldType.STRING,
                        "The Investigation whose Working Set the rule watches. Requires alert.relation and "
                                + "alert.measure; takes no metric, window or dataset."),
                FieldSpec.enumField("alert.relation", "Working Set relation",
                        List.of("entities", "links", "excluded"), null,
                        "Which relation of the Investigation's Working Set the Measure is computed over.")
        );
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "alert-metric-by-shape",
                        "alert.metric is required for a ledger rule (one with no alert.dataset, "
                                + "alert.maximumAge or alert.investigation).",
                        Severity.ERROR,
                        List.of("alert.metric"),
                        raw -> !"ledger".equals(alertShape(raw)) || nonBlank(raw, "alert.metric")),
                new CrossFieldRule(
                        "alert-window-by-shape",
                        "alert.window is required for a ledger rule (one with no alert.dataset, "
                                + "alert.maximumAge or alert.investigation).",
                        Severity.ERROR,
                        List.of("alert.window"),
                        raw -> !"ledger".equals(alertShape(raw)) || nonBlank(raw, "alert.window")),
                new CrossFieldRule(
                        "alert-threshold-positive",
                        "alert.threshold must be a positive number (a freshness rule, alert.maximumAge, "
                                + "takes none).",
                        Severity.ERROR,
                        List.of("alert.threshold"),
                        raw -> {
                            if ("freshness".equals(alertShape(raw))) {
                                return true;
                            }
                            String t = str(raw, "alert.threshold");
                            if (t == null) {
                                return false;
                            }
                            try {
                                return Double.parseDouble(t.trim()) > 0;
                            } catch (NumberFormatException e) {
                                return false;
                            }
                        }),
                new CrossFieldRule(
                        "alert-window-shape",
                        "alert.window must match \\d+[smhdb] (e.g. 1h, 30m, 20b).",
                        Severity.ERROR,
                        List.of("alert.window"),
                        raw -> {
                            String w = str(raw, "alert.window");
                            return w == null || w.trim().toLowerCase().matches("\\d+[smhdb]");
                        })
        );
        return new ConfigSpec("alert", fields, rules);
    }

    /**
     * Which of {@code AlertRule}'s four shapes a raw {@code alert} block is, decided in the SAME order
     * the record's compact constructor decides it: investigation, then freshness, then measure, else
     * the historic ledger rule. The order matters - a freshness rule also carries
     * {@code alert.dataset}, so testing dataset first would call it a measure rule.
     */
    private static String alertShape(Map<String, Object> raw) {
        if (nonBlank(raw, "alert.investigation")) return "investigation";
        if (nonBlank(raw, "alert.maximumAge")) return "freshness";
        if (nonBlank(raw, "alert.dataset")) return "measure";
        return "ledger";
    }

    /** Present and not blank — {@code AlertRule} reads a blank value as absent, so the spec must too. */
    private static boolean nonBlank(Map<String, Object> raw, String path) {
        String v = str(raw, path);
        return v != null && !v.isBlank();
    }

    // ── expectation (ING-6) ──────────────────────────────────────────────────────

    /**
     * The authored data-quality {@code expectation} component evaluated by the Expectation engine.
     *
     * <p>🔴 <b>The {@code condition} kind (promoted 2026-07-18) is the reason three of these
     * declarations do not look like the other kinds'.</b> A {@code condition} expectation carries an
     * arbitrary {@code when} predicate tree that {@code ConditionSql} compiles straight to the
     * violation predicate, and therefore needs no {@code column} — the tree names its own field(s).
     * So {@code column} is declared OPTIONAL and its requirement moved into the
     * {@code column-needed-unless-condition} cross-field rule, beside the three kind rules that were
     * already there ({@link FieldSpec} has no conditional-required). {@code when} is a MAP for the
     * same reason {@code widget.controls} is: the spec model describes an open map's envelope, not
     * its inner tree.
     *
     * <p>Until then this spec contradicted {@code Expectation} in both directions — it listed four of
     * the five kinds the record accepts and declared {@code when} nowhere — so any caller that ran its
     * VALUE rules ({@code POST /validate}, {@code POST /config/write}; both take {@code type} from the
     * body) refused every condition expectation the engine happily evaluates. The {@code /expectations}
     * authoring route was unaffected only because its census runs the spec's field NAMES, not its rules.
     */
    public static ConfigSpec expectation() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("name", "Expectation name", FieldType.STRING,
                        "Unique name for the data-quality check."),
                FieldSpec.of("description", "Description", FieldType.STRING, "What this check asserts."),
                FieldSpec.enumField("targetType", "Target type", List.of("pipeline", "job"), "pipeline",
                        "Whether the target's at-rest data comes from a pipeline or a job."),
                FieldSpec.required("target", "Target", FieldType.STRING,
                        "Name of the pipeline/job whose at-rest Parquet is scanned."),
                FieldSpec.of("column", "Column", FieldType.STRING,
                        "The column the check applies to (required for every kind except condition, "
                                + "whose when tree names its own fields)."),
                FieldSpec.enumField("kind", "Kind",
                        List.of("non_null", "range", "regex", "referential", "condition"), "non_null",
                        "The data-quality constraint: not-null, numeric range, regex match, referential "
                                + "lookup, or an arbitrary condition tree."),
                FieldSpec.of("min", "Min", FieldType.STRING, "Range lower bound (range kind)."),
                FieldSpec.of("max", "Max", FieldType.STRING, "Range upper bound (range kind)."),
                FieldSpec.of("pattern", "Pattern", FieldType.STRING, "Regex the value must match (regex kind)."),
                FieldSpec.of("refDataset", "Reference dataset", FieldType.STRING,
                        "Lookup relation the value must exist in (referential kind)."),
                FieldSpec.of("refColumn", "Reference column", FieldType.STRING,
                        "Column in the reference dataset (referential kind)."),
                FieldSpec.of("when", "When", FieldType.MAP,
                        "The violation predicate tree (condition kind) — the same query-types shape a "
                                + "Decision Rule authors; compiled to SQL by ConditionSql."),
                FieldSpec.enumField("severity", "Severity", List.of("MINOR", "MAJOR", "CRITICAL"), "MAJOR",
                        "Severity of the Incident raised on failure."),
                FieldSpec.withDefault("enabled", "Enabled", FieldType.BOOL, true,
                        "Whether evaluate-all includes this expectation.")
        );
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "range-needs-a-bound",
                        "A range expectation needs at least one of min/max.",
                        Severity.ERROR,
                        List.of("kind", "min", "max"),
                        raw -> !"range".equalsIgnoreCase(str(raw, "kind"))
                                || present(raw, "min") || present(raw, "max")),
                new CrossFieldRule(
                        "regex-needs-a-pattern",
                        "A regex expectation needs a pattern.",
                        Severity.ERROR,
                        List.of("kind", "pattern"),
                        raw -> !"regex".equalsIgnoreCase(str(raw, "kind")) || present(raw, "pattern")),
                new CrossFieldRule(
                        "referential-needs-ref",
                        "A referential expectation needs refDataset and refColumn.",
                        Severity.ERROR,
                        List.of("kind", "refDataset", "refColumn"),
                        raw -> !"referential".equalsIgnoreCase(str(raw, "kind"))
                                || (present(raw, "refDataset") && present(raw, "refColumn"))),
                new CrossFieldRule(
                        "condition-needs-a-when",
                        "A condition expectation needs a 'when' condition tree.",
                        Severity.ERROR,
                        List.of("kind", "when"),
                        raw -> !"condition".equalsIgnoreCase(str(raw, "kind")) || present(raw, "when")),
                // The requirement a flat FieldSpec.required cannot express: every COLUMN-checking kind
                // needs one, and 'condition' — which checks a tree, not a column — must not be asked for
                // one. Mirrors Expectation's own constructor check.
                new CrossFieldRule(
                        "column-needed-unless-condition",
                        "Every expectation kind except 'condition' needs a column.",
                        Severity.ERROR,
                        // ⛔ `column` FIRST, and the order is load-bearing: CrossFieldRule anchors the
                        // finding on affectedPaths.get(0), and the field the author must supply is the
                        // column, not the kind. Anchoring on `kind` sends a form to highlight the field
                        // that is already correct — caught by InspectoToolsTest, which asserts these
                        // findings are anchored, on the FULL reactor and not by this module's own suite.
                        List.of("column", "kind"),
                        raw -> "condition".equalsIgnoreCase(str(raw, "kind")) || present(raw, "column"))
        );
        return new ConfigSpec("expectation", fields, rules);
    }

    // ── widget ────────────────────────────────────────────────────────────────────

    /** A saved visualization: a viz-plugin {@code vizType} + a dataset (or a saved view) + the
     *  field→channel {@code controls} the plugin compiles to a query. The {@code controls} shape is
     *  plugin-defined (an open map), so this spec validates the envelope, not the per-viz channel keys;
     *  the one hard rule is that a widget must bind a dataset or a view. Mirrors {@code widget-types.ts}. */
    public static ConfigSpec widget() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("vizType", "Visualization type", FieldType.STRING,
                        "The viz plugin type: kpi, bar, line, pie, table, geo-map, link-analysis, …"),
                FieldSpec.of("datasetId", "Dataset", FieldType.STRING,
                        "The dataset the widget queries (empty for a view-bound widget, which uses viewId)."),
                FieldSpec.of("viewId", "View", FieldType.STRING,
                        "A saved investigation view (geo-map/link-analysis) rendered instead of a dataset query — "
                                + "or, for a working-set widget, the Investigation whose Working Set it reads."),
                FieldSpec.of("workingSet", "Working Set binding", FieldType.MAP,
                        "A working-set widget's binding (LA-21): {relation: entities|links|excluded, mode: "
                                + "frozen|live, pin: {step, workingSetHash, pinnedAt}}. Frozen (the default) re-reads "
                                + "the relation at the pinned step; live re-reads the head and shows drift since the pin."),
                FieldSpec.of("queryId", "Query", FieldType.STRING,
                        "A saved query component that supplies the rows instead of the dataset's own columns."),
                FieldSpec.of("controls", "Field mapping", FieldType.MAP,
                        "The field→channel mapping the viz plugin compiles to a query (e.g. value / x / y / series)."),
                FieldSpec.of("options", "Advanced options", FieldType.MAP, "Caption/title and render options."),
                FieldSpec.of("tags", "Tags", FieldType.LIST, "Free-text tags for the widget gallery."),
                FieldSpec.of("description", "Description", FieldType.STRING, "Library-card subtitle.")
        );
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "binds-a-dataset-or-a-view",
                        "A widget must bind a dataset (datasetId) or a saved view (viewId).",
                        Severity.ERROR,
                        List.of("datasetId", "viewId"),
                        raw -> present(raw, "datasetId") || present(raw, "viewId")),
                // LA-21 / D-E6: the pin is what a Frozen tile renders and what a Live tile measures drift from, so a
                // binding without one — or in a mode the tile cannot state — is refused rather than drawn wrong.
                new CrossFieldRule(
                        "working-set-binding",
                        "A working-set binding names its Investigation (viewId), a relation (entities, links or "
                                + "excluded), a mode (frozen or live) and a pin {step, workingSetHash}.",
                        Severity.ERROR,
                        List.of("workingSet", "viewId"),
                        raw -> !present(raw, "workingSet") || (present(raw, "viewId")
                                && java.util.Set.of("entities", "links", "excluded").contains(str(raw, "workingSet.relation"))
                                && java.util.Set.of("frozen", "live").contains(str(raw, "workingSet.mode"))
                                && str(raw, "workingSet.pin.step") != null && str(raw, "workingSet.pin.step").matches("\\d+")
                                && present(raw, "workingSet.pin.workingSetHash")))
        );
        return new ConfigSpec("widget", fields, rules);
    }

    // ── dashboard ─────────────────────────────────────────────────────────────────

    /** A composite of saved widgets laid out in a grid, with an optional dashboard-level cross-filter
     *  injected into every tile's query. Each tile references a widget by id + a 1|2 column span; deep
     *  per-tile validation and widget-existence checks belong in the caller, not this envelope spec.
     *  Mirrors {@code dashboard-types.ts}. */
    public static ConfigSpec dashboard() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("tiles", "Tiles", FieldType.LIST,
                        "Placed widgets — each {widgetId, span:1|2}; array order is the layout order."),
                FieldSpec.of("filter", "Cross-filter", FieldType.MAP,
                        "A Query Core condition group injected into every tile's query."),
                FieldSpec.of("exposedFields", "Exposed filter fields", FieldType.LIST,
                        "Columns offered to viewers as quick filters (the dashboard filter bar).")
        );
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "at-least-one-tile",
                        "A dashboard needs at least one tile.",
                        Severity.ERROR,
                        List.of("tiles"),
                        raw -> {
                            Object tiles = at(raw, "tiles");
                            return !(tiles instanceof List<?> l) || !l.isEmpty();
                        })
        );
        return new ConfigSpec("dashboard", fields, rules);
    }

    // ── schema ──────────────────────────────────────────────────────────────────

    public static ConfigSpec schema() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("raw.name", "Schema name", FieldType.STRING, "Logical name of the raw source."),
                FieldSpec.enumField("raw.format", "Raw format", List.of("CSV", "PARQUET"), "CSV",
                        "Source file format."),
                FieldSpec.of("partitionKey", "Partition key", FieldType.STRING,
                        "Column whose value drives Hive partitioning (or use fields[].partitions)."),
                FieldSpec.of("raw.fields", "Field definitions", FieldType.LIST,
                        "Tabular array of {name,selector,type[,description,unit,classification]}."),
                FieldSpec.of("mapping.canonicalName", "Canonical name", FieldType.STRING,
                        "Canonical table name the raw source maps to.")
        );
        // Schema bodies are deeply validated by Identifiers.validateSchema at parse time; the spec
        // describes shape for UI/AI rather than re-implementing that structural validation.
        return new ConfigSpec("schema", fields, List.of());
    }


    // ── meta (KPI/report semantics) ──────────────────────────────────────────────

    public static ConfigSpec meta() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("name", "Semantics name", FieldType.STRING, "Name of this semantic model."),
                FieldSpec.of("version", "Version", FieldType.INT, "Schema version of the meta file."),
                FieldSpec.of("tables", "Table descriptions", FieldType.MAP,
                        "Map of table id → {description, grain}."),
                FieldSpec.of("kpis", "KPI catalog", FieldType.MAP,
                        "Map of KPI name → {definition, grain, inputs[], join_keys[]}."),
                FieldSpec.of("reports", "Report catalog", FieldType.MAP,
                        "Map of report name → {description, uses[]}."),
                FieldSpec.of("domain", "Domain notes", FieldType.MAP,
                        "{currency, timezone, notes[]} — cross-cutting domain context.")
        );
        return new ConfigSpec("meta", fields, List.of(
                // ⛔ Deliberately a RULE, not an ENUM of ZoneId.getAvailableZoneIds(): an enum field
                // generates a form control, and a timezone picker would imply the zone governs date
                // math — which it does not yet (BACKLOG §4 D6: PipelineScheduler and JobService both
                // still hardcode ZoneId.systemDefault()). A control that lies is worse than the gap.
                //
                // ⚠ The value is INERT today, so this is hardening ahead of the behaviour half rather
                // than a live bug fix: it costs nothing to author a resolvable zone now, and once a
                // zone does drive cron firing an unresolvable one becomes a runtime failure instead of
                // a save-time one. The trade accepted: a pre-existing meta file with a typo'd zone can
                // no longer be saved until it is corrected.
                new CrossFieldRule(
                        "domain-timezone-resolvable",
                        "domain.timezone must be a zone this JVM can resolve — an IANA id such as "
                                + "UTC or Asia/Kolkata, not an abbreviation like IST. Leave it unset "
                                + "rather than guessing.",
                        Severity.ERROR,
                        List.of("domain.timezone"),
                        raw -> {
                            String tz = str(raw, "domain.timezone");
                            if (tz == null || tz.isBlank()) return true;   // absent is legal
                            try {
                                java.time.ZoneId.of(tz);
                                return true;
                            } catch (java.time.DateTimeException e) {
                                return false;
                            }
                        })));
    }

    // ── link-analysis settings (a per-Space settings document, NOT a component type) ─────────────

    /** Entity masking modes for Link Analysis responses (LA-19, D-U6) — the FIRST is the default. */
    public static final List<String> LINK_ANALYSIS_MASKING_MODES = List.of("typed", "all", "none");

    /**
     * The keys of a Space's {@code link-analysis.toon} ({@code GET|PUT /settings/link-analysis}). ⚠ Deliberately
     * NOT in {@link #TYPES}: this is a per-Space preference document, not a registry component, so
     * {@code /config/spec/{type}} and the generic config write routes must not accept it. Every key is optional and
     * an absent key means "the shipped default" — never "unbounded" and never "off by accident".
     */
    public static ConfigSpec linkAnalysisSettings() {
        List<FieldSpec> fields = List.of(
                FieldSpec.of("projection_node_cap", "Projection node cap", FieldType.INT,
                        "Nodes admitted to one projection (1..100000); absent = the shipped default."),
                FieldSpec.of("analysis_node_cap", "Analysis node cap", FieldType.INT,
                        "Nodes above which the browser-side graph algorithms refuse; absent = the shipped default."),
                FieldSpec.of("suspicion_node_cap", "Suspicion score node cap", FieldType.INT,
                        "Nodes above which suspicion score alone refuses (D-S3); absent = the shipped default."),
                FieldSpec.enumField("masking_mode", "Entity masking", LINK_ANALYSIS_MASKING_MODES,
                        LINK_ANALYSIS_MASKING_MODES.get(0),
                        "Which entity ids Investigation responses mask (D-U6): typed = typed identifiers "
                                + "(MSISDN, IMSI, ACCOUNT), all = every entity id, none = nothing."),
                FieldSpec.of("four_eyes_budget_above", "Four-eyes budget threshold", FieldType.INT,
                        "An expand whose row budget exceeds this waits for a second person's approval (D-U7); "
                                + "absent = no budget threshold."),
                FieldSpec.of("four_eyes_fan_out_above", "Four-eyes fan-out threshold", FieldType.INT,
                        "An expand whose maxFanOut exceeds this, or is unbounded, waits for a second person's "
                                + "approval (D-U7); absent = no fan-out threshold.")
        );
        return new ConfigSpec("link-analysis-settings", fields, List.of());
    }

    /**
     * Whether {@code path} holds a block an author actually wrote. ⚠ Stricter than
     * {@link RawConfig#present}: an empty {@code steps: []} or {@code dedup: {}} is not an authored
     * block, and the engine's own arming checks agree — they test the PARSED field, which an empty
     * section leaves null. Treating empty as authored would refuse a pipeline the engine arms happily.
     */
    private static boolean authored(Map<String, Object> raw, String path) {
        Object v = at(raw, path);
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        if (v instanceof List<?> l) return !l.isEmpty();
        return present(raw, path);
    }
}
