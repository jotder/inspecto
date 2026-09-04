package com.gamma.etl;

import com.gamma.api.PublicApi;
import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Immutable configuration object for one ETL pipeline run.
 *
 * <p>Configuration is grouped into six nested records by concern, reached through
 * accessor methods: {@link #identity()}, {@link #dirs()}, {@link #processing()},
 * {@link #csv()}, {@link #output()}, {@link #schemas()}. For example the output
 * database directory is {@code cfg.dirs().database()} and the batch-concurrency cap
 * is {@code cfg.processing().threads()}.
 *
 * <p>(Prior to 2.0 these were ~30 flat {@code public final} fields, e.g.
 * {@code cfg.databaseDir}. The nested grouping is the one breaking API change of
 * 2.0; pipeline {@code .toon} configs and on-disk output are unchanged.)
 *
 * <p>Instances are created via the {@link #load(String)} static factory, which
 * validates the config, resolves schemas, and computes the run timestamp. The
 * object is safe for concurrent read access by all worker threads once
 * {@code load()} returns.
 */
@PublicApi(since = "1.0.0")
public final class PipelineConfig {

    // ── nested config groups ───────────────────────────────────────────────────

    /** Pipeline identity: original name, normalised name, and the run timestamp. */
    @PublicApi(since = "2.0.0")
    public record Identity(String name, String pipelineName, String runTimestamp) {}

    /**
     * All filesystem paths for the run. {@code statusFilePath}/{@code batchesFilePath}/
     * {@code lineageFilePath}/{@code manifestsDir}/{@code unpackFilePath} are {@code null} when
     * status is disabled.
     */
    @PublicApi(since = "2.0.0")
    public record Dirs(String poll, String database, String backup, String temp,
                       String errors, String quarantine, String markers, String logDir,
                       String statusFilePath, String batchesFilePath, String lineageFilePath,
                       String manifestsDir, String commitLogPath, String unpackFilePath) {}

    /**
     * Execution controls. {@code threads} caps concurrent batches (semaphore permits
     * over a virtual-thread executor; default 4); {@code duckdbThreads} caps each batch
     * connection's DuckDB parallelism via {@code PRAGMA threads}. The default {@code 0}
     * <em>auto-derives</em> {@code max(1, cores / threads)} so concurrent batches divide the
     * cores instead of each grabbing all of them (avoiding CPU oversubscription); {@code -1}
     * opts out and leaves DuckDB's per-core default; a positive value is used verbatim. A single
     * batch ({@code threads <= 1}) always gets all cores. See
     * {@link com.gamma.util.DuckDbUtil#effectiveWorkerThreads}.
     *
     * <p>{@code largeFileBytes} drives the streaming plugin engine's per-batch mode pick: a batch
     * whose largest member is {@code >= largeFileBytes} runs in bounded <em>generation mode</em>
     * (huge single files), otherwise <em>union mode</em> (many small files packed → one transform/
     * write). {@code <= 0} forces union mode always. {@code flushRecords} is the per-generation row
     * budget used in generation mode.
     */
    @PublicApi(since = "2.0.0")
    public record Processing(int threads, int duckdbThreads, String filePattern,
                             int batchMaxFiles, long batchMaxBytes, String batchOrder,
                             boolean duplicateCheckEnabled, String markerExtension,
                             int retentionDays, long largeFileBytes, long flushRecords,
                             int priority) {}

    /**
     * Delimited-text parse settings. {@code engine} is {@code "auto"}/{@code "duckdb"}/
     * {@code "java"} — {@code auto} uses DuckDB's native reader for clean configs and the
     * Java parser otherwise (see {@link DuckDbCsvIngester#usesDuckDb}).
     *
     * <p>These settings may be authored inline under {@code processing.csv_settings} <em>or</em>
     * in a separate reusable grammar file referenced by {@code processing.grammar}; when both are
     * present the inline keys override the grammar file (see {@link #resolveGrammar}).
     *
     * <p>Fields added in 4.1 (all optional, defaults preserve prior behaviour):
     * {@code encoding}/{@code inputCompression}/{@code strictMode}/{@code nullStrings} pass through to
     * DuckDB {@code read_csv}; {@code includePrefixes}/{@code includeRegex}/{@code excludePrefixes}/
     * {@code excludeRegex} (anchored on {@code filterTargetColumn}, a 0-based selector index) compile
     * to a row-filter {@code WHERE} clause on the native path and an in-loop filter on the Java path.
     * {@code strictMode} is {@code null} when unset (⇒ DuckDB default).
     *
     * <p>Dialect characters (5.2): {@code quote}/{@code escape}/{@code comment} are single-character
     * strings, {@code null} when unset (⇒ engine defaults: quote {@code "} / escape = the quote char,
     * i.e. RFC-4180 doubling / no comment). They
     * pass through to DuckDB {@code read_csv} and to the univocity fallback identically, so the two
     * engines cannot diverge on quoting.
     *
     * <p><b>Two distinct filtering moments</b> — do not conflate them. The {@code includePrefixes}/
     * {@code includeRegex}/{@code excludePrefixes}/{@code excludeRegex} lists are <em>pre-parse</em>:
     * they match one raw physical column ({@code c<filterTargetColumn>}) inside the {@code read_csv}
     * SELECT, before any field is named or typed ({@link DuckDbCsvIngester#filterWhere}). {@code where}
     * is <em>post-parse</em>: a SQL predicate over the mapped, typed target columns, applied by
     * {@link DataTransformer#materialize}. A predicate like {@code amount > 0} is only expressible as
     * the latter; a regex over an unparsed column is only expressible as the former. Hence
     * {@link #hasRowFilters()} and {@link #hasRowPredicate()} are separate — {@code where} is valid on
     * frontends that have no {@code c<N>} columns at all (json / text_regex), where the pre-parse lists
     * are rejected outright ({@code ConfigValidator}).
     */
    @PublicApi(since = "2.0.0")
    public record CsvSettings(String delimiter, String quote, String escape, String comment,
                              int skipHeaderLines, int skipJunkLines,
                              int skipTailLines, int skipTailCols, boolean hasHeader,
                              String engine, List<String> dateFormats, List<String> tsFormats,
                              String encoding, String inputCompression, Boolean strictMode,
                              List<String> nullStrings,
                              List<String> includePrefixes, List<String> includeRegex,
                              List<String> excludePrefixes, List<String> excludeRegex,
                              int filterTargetColumn, String where,
                              Boolean ignoreErrors, Boolean nullPadding, Rejects rejects,
                              String sourceTimezone) {

        /**
         * The pre-robustness arity, kept so callers built before the error-handling knobs existed still
         * compile — same contract as {@link Output}'s pre-B4 overload. Every knob {@code null} means
         * "engine default", which is exactly what those callers used to get.
         */
        public CsvSettings(String delimiter, String quote, String escape, String comment,
                           int skipHeaderLines, int skipJunkLines,
                           int skipTailLines, int skipTailCols, boolean hasHeader,
                           String engine, List<String> dateFormats, List<String> tsFormats,
                           String encoding, String inputCompression, Boolean strictMode,
                           List<String> nullStrings,
                           List<String> includePrefixes, List<String> includeRegex,
                           List<String> excludePrefixes, List<String> excludeRegex,
                           int filterTargetColumn, String where) {
            this(delimiter, quote, escape, comment, skipHeaderLines, skipJunkLines,
                    skipTailLines, skipTailCols, hasHeader, engine, dateFormats, tsFormats,
                    encoding, inputCompression, strictMode, nullStrings,
                    includePrefixes, includeRegex, excludePrefixes, excludeRegex,
                    filterTargetColumn, where, null, null, Rejects.DEFAULTS);
        }

        /**
         * The pre-source-timezone arity. {@code null} means "no source zone declared", which is the
         * wall-clock default every pipeline had before the key existed — so a caller built against
         * the older shape keeps compiling AND keeps its exact behaviour.
         */
        public CsvSettings(String delimiter, String quote, String escape, String comment,
                           int skipHeaderLines, int skipJunkLines,
                           int skipTailLines, int skipTailCols, boolean hasHeader,
                           String engine, List<String> dateFormats, List<String> tsFormats,
                           String encoding, String inputCompression, Boolean strictMode,
                           List<String> nullStrings,
                           List<String> includePrefixes, List<String> includeRegex,
                           List<String> excludePrefixes, List<String> excludeRegex,
                           int filterTargetColumn, String where,
                           Boolean ignoreErrors, Boolean nullPadding, Rejects rejects) {
            this(delimiter, quote, escape, comment, skipHeaderLines, skipJunkLines,
                    skipTailLines, skipTailCols, hasHeader, engine, dateFormats, tsFormats,
                    encoding, inputCompression, strictMode, nullStrings,
                    includePrefixes, includeRegex, excludePrefixes, excludeRegex,
                    filterTargetColumn, where, ignoreErrors, nullPadding, rejects, null);
        }

        /** Never null — an absent {@code rejects} block reads as {@link Rejects#DEFAULTS}. */
        public Rejects rejects() {
            return rejects == null ? Rejects.DEFAULTS : rejects;
        }

        /**
         * Reject capture: DuckDB's {@code store_rejects} plus the table names and per-file cap it
         * writes under. Every field is nullable = "leave the engine's own default", so an existing
         * config emits byte-identical SQL.
         *
         * <p>⚠ {@link #table} and {@link #scan} are interpolated as SQL <em>identifiers</em> when the
         * reject rows are drained, which a bound parameter cannot express — so
         * {@link #isLegalName} is the fail-closed gate, applied at config load
         * ({@code PipelineConfigParser}), not here. Never widen it.
         */
        @PublicApi(since = "2.0.0")
        public record Rejects(Boolean store, String table, String scan, Integer limit) {

            /** All-default: store as the engine already did, under DuckDB's own table names. */
            public static final Rejects DEFAULTS = new Rejects(null, null, null, null);

            /** DuckDB's own reject table names, used whenever the config names none. */
            public static final String DEFAULT_TABLE = "reject_errors";
            public static final String DEFAULT_SCAN = "reject_scans";

            /** The errors table to read drained rejects from — the configured name, else DuckDB's. */
            public String tableOrDefault() {
                return table == null || table.isBlank() ? DEFAULT_TABLE : table;
            }

            /** The scans table to join against — the configured name, else DuckDB's. */
            public String scanOrDefault() {
                return scan == null || scan.isBlank() ? DEFAULT_SCAN : scan;
            }

            /**
             * A bare SQL identifier: letters, digits and underscore, not starting with a digit. The
             * drain path interpolates these names directly, so anything else is refused at load
             * rather than reaching a statement.
             */
            public static boolean isLegalName(String name) {
                if (name == null || name.isBlank()) return true;   // absent = use the default
                if (name.length() > 128) return false;
                if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') return false;
                for (int i = 1; i < name.length(); i++) {
                    char ch = name.charAt(i);
                    if (!Character.isLetterOrDigit(ch) && ch != '_') return false;
                }
                return true;
            }
        }

        /** Whether any of the <em>pre-parse</em> row-filter lists is non-empty. */
        public boolean hasRowFilters() {
            return !includePrefixes.isEmpty() || !includeRegex.isEmpty()
                || !excludePrefixes.isEmpty() || !excludeRegex.isEmpty();
        }

        /** Whether a <em>post-parse</em> SQL row predicate ({@code where}) is declared. */
        public boolean hasRowPredicate() {
            return where != null && !where.isBlank();
        }

        /**
         * Default settings carrying only the two format lists — for compiling mapping rules against a
         * graph that did NOT come from a parsed {@link PipelineConfig}. A graph decoded from JSON keeps
         * its {@code csv} block as a plain map (config travels verbatim), so this rebuilds the only part
         * {@link DataTransformer#dataColumns} reads off it. Every other component takes the same default
         * the builder would; do not use this where the real parse settings matter.
         */
        public static CsvSettings ofFormats(List<String> dateFormats, List<String> tsFormats) {
            return new CsvSettings(",", null, null, null, 0, 0, 0, 0, true, "auto",
                    List.copyOf(dateFormats), List.copyOf(tsFormats),
                    null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), 0, null);
        }
    }

    /** Output format/compression and the optional {@code output.ducklake} map ({@code null} if absent). */
    @PublicApi(since = "2.0.0")
    public record Output(String format, String compression, Map<String, Object> duckLake,
                         String filenameColumn) {
        /** The pre-B4 shape — no filename column. */
        public Output(String format, String compression, Map<String, Object> duckLake) {
            this(format, compression, duckLake, null);
        }
    }

    /**
     * One output <b>destination</b>: the write-root {@code database} dir plus the {@code format}/
     * {@code compression}/{@code duckLake} tuple that {@link Output} carries — i.e. everything a single
     * write site consumes ({@code cfg.dirs().database()} for <em>where</em>, {@code cfg.output()} for
     * <em>how</em>). A pipeline may declare several via a top-level {@code sinks:} list; when {@code sinks:}
     * is absent, {@link #sinks()} synthesises a one-element list from {@code dirs.database} + {@code output:}
     * (the single-destination shorthand), so the accessor is never empty. {@code duckLake} is {@code null}
     * when the destination has no DuckLake block.
     *
     * <p><b>Multi-destination is not yet executable</b> — a config whose {@code sinks:} names more than one
     * destination is constructible and liftable (so the editor can author it and {@code PipelineLift} can
     * fan it out), but is refused when loaded for execution ({@link #prepare()}) until the branch-aware
     * executor is wired (Stage A step 3, see {@code docs/superpower/sinks-config-format-plan.md}).
     */
    @PublicApi(since = "4.0.0")
    public record Sink(String database, String format, String compression, Map<String, Object> duckLake,
                       String filenameColumn) {
        /** The pre-B4 shape — no filename column. */
        public Sink(String database, String format, String compression, Map<String, Object> duckLake) {
            this(database, format, compression, duckLake, null);
        }
    }

    /**
     * One entry of the ordered {@code steps:} transform chain — <b>the flat file's answer to "how many,
     * and in what order"</b> (multiplicity plan Part A, option (b)).
     *
     * <p>Before this existed the file held at most one {@code processing.dedup}, one {@code route:}, and
     * so on, and the <em>order</em> of the chain was not in the file at all: {@code PipelineLift} emitted
     * a hard-coded {@code filter → join → dedup → summarize → route}. With one node per kind a constant
     * order is indistinguishable from a stored one, which is why nothing noticed. At two it stops being —
     * an authored {@code dedup → summarize → dedup} cannot be expressed by per-kind blocks however they
     * are keyed, so the sequence itself has to be the representation.
     *
     * <p><b>Order is list position, full stop.</b> No {@code after:} key, no index — a second ordering
     * channel is a second source of truth, and the two disagree the first time someone hand-edits the file.
     *
     * @param kind   one of {@link #FILTER}, {@link #JOIN}, {@link #DEDUP}, {@link #SUMMARIZE},
     *               {@link #ROUTE}, {@link #SQL} — the node type's own word, minus its {@code transform.}
     *               prefix, so one concept keeps one name across the graph, the file and the palette.
     * @param config the step's own keys, verbatim — the same shape the corresponding legacy block held.
     */
    public record Step(String kind, Map<String, Object> config) {

        /** Post-parse row predicate. Legacy spelling: {@code processing.csv_settings.where}. */
        public static final String FILTER = "filter";
        /** Reference join. Legacy spelling: {@code processing.join}. */
        public static final String JOIN = "join";
        /** ⚠ Record-grain <b>distinct</b> dedup ({@code processing.dedup}) — <b>not</b> file/content
         *  fingerprint dedup, which is a property of the Collect node and is singular by construction. */
        public static final String DEDUP = "dedup";
        /** Group-by rollup. Legacy spelling: {@code processing.summarize}. */
        public static final String SUMMARIZE = "summarize";
        /** Branch tree. Legacy spelling: the top-level {@code route:} block. */
        public static final String ROUTE = "route";
        /**
         * One author {@code SELECT} over the typed input ({@code transform.sql}). Keys: {@code sql}
         * (required) and, when Simple-authored, an opaque {@code fields[]} list the engine never reads.
         * ⚠ No legacy singular spelling — a chain holding one always takes the {@code steps:} form.
         */
        public static final String SQL = "sql";

        /** Every kind a {@code steps:} entry may name, in the order the legacy projection emits them
         *  ({@link #SQL} last: it has no legacy projection at all). */
        public static final List<String> KINDS = List.of(FILTER, JOIN, DEDUP, SUMMARIZE, ROUTE, SQL);

        public Step {
            config = (config == null) ? Map.of() : Map.copyOf(config);
        }

        /**
         * The per-branch {@code steps[]} sub-chain of one {@code route:} branch entry (MIDBRANCH-1,
         * R3) — the SAME single-key-map vocabulary as the top-level {@code steps:} list, typed.
         * Lenient by design: the parser refuses a malformed entry at load
         * ({@code PipelineConfigParser.parseStepEntries}), so a non-conforming element here (a
         * hand-mangled map) is skipped rather than thrown — every caller of this accessor
         * (lift / arming) sees only what the load gate admitted.
         *
         * @param branchEntry one entry of {@code route.branches[]}; absent/non-list {@code steps} ⇒ empty
         */
        @SuppressWarnings("unchecked")
        public static List<Step> branchSteps(Map<?, ?> branchEntry) {
            if (branchEntry == null || !(branchEntry.get("steps") instanceof List<?> raw)) return List.of();
            List<Step> out = new ArrayList<>();
            for (Object entry : raw) {
                if (!(entry instanceof Map<?, ?> sm) || sm.size() != 1) continue;
                Map.Entry<?, ?> only = sm.entrySet().iterator().next();
                if (only.getValue() != null && !(only.getValue() instanceof Map<?, ?>)) continue;
                out.add(new Step(String.valueOf(only.getKey()).trim(),
                        (Map<String, Object>) only.getValue()));
            }
            return List.copyOf(out);
        }
    }

    /**
     * Schema resolution — at most one of {@code selector} (multi-schema {@code schemas[]}),
     * {@code single} (legacy {@code schema_file}), or {@code segments} (plugin path) is
     * non-null; all three are {@code null} for a schema-less <em>draft</em> (v5.1.0 — allowed
     * only while {@code active: false}; arming without a schema is rejected at parse).
     * {@code ingesterClass} is the plugin FQCN ({@code null} for built-in CSV);
     * {@code ingesterConfig} is the plugin's free-form settings map (empty, never null).
     */
    @PublicApi(since = "2.0.0")
    public record Schemas(SchemaSelector selector, Map<String, Object> single,
                          LinkedHashMap<String, Map<String, Object>> segments,
                          String ingesterClass, Map<String, Object> ingesterConfig) {}

    /**
     * Optional DuckDB engine-resource controls (additive, 3.10.0). All {@code null}/blank ⇒
     * DuckDB defaults — fully backward-compatible. Parsed from {@code processing.duckdb}.
     *
     * <p>{@code tempDirectory} relocates the per-batch temp database <em>and</em> DuckDB's spill
     * scratch. The engine defaults it to {@code dirs.temp} (on the data volume) rather than the
     * JVM/system temp dir ({@code /tmp}), so a huge file's scratch never lands on a small
     * {@code /tmp}. {@code memoryLimit} and {@code maxTempDirectorySize} accept DuckDB size
     * strings (e.g. {@code "16GB"}); the latter caps spill so a runaway query fails fast instead
     * of filling the disk.
     */
    @PublicApi(since = "3.10.0")
    public record DuckDbSettings(String memoryLimit, String tempDirectory,
                                 String maxTempDirectorySize) {}

    /**
     * Optional large-file auto-chunking (additive, 3.10.0). Parsed from {@code processing.chunking}.
     *
     * <p>When a single input file exceeds {@code maxFileBytes}, the CSV ingester streams it into
     * bounded chunks of ~{@code targetChunkBytes} (defaulting to {@code maxFileBytes} when unset),
     * so peak scratch stays bounded per chunk and chunks process concurrently — instead of
     * materialising one multi-hundred-GB unit. {@code maxFileBytes <= 0} disables chunking.
     *
     * <p><b>On by default since BACKLOG D12</b> at 8 GiB — chosen far above any routine input so
     * normal workloads never change shape; the threshold exists for pathological single files. It
     * is the only bound on such a file today, because D11's companion per-instance
     * {@code memory_limit} default was deliberately NOT shipped (operator call 2026-07-25), so an
     * uncapped run still sees DuckDB's own ~80%-of-RAM default.
     */
    @PublicApi(since = "3.10.0")
    public record Chunking(long maxFileBytes, long targetChunkBytes) {
        /** Effective per-chunk target, defaulting to the threshold when unset. */
        public long effectiveChunkBytes() {
            return targetChunkBytes > 0 ? targetChunkBytes : maxFileBytes;
        }
        /** Whether chunking is enabled for a file of {@code fileBytes}. */
        public boolean appliesTo(long fileBytes) {
            return maxFileBytes > 0 && fileBytes > maxFileBytes;
        }
    }

    /**
     * Optional per-pipeline intake admission-control override (T15 follow-up, additive). Parsed from
     * {@code processing.intake}; {@code null} (block absent) means the process-wide {@code -Dingest.*}
     * thresholds apply whole, so every existing config is byte-identical in behaviour.
     *
     * <p>Each field is independently optional — an unset field inherits its global counterpart — so an
     * operator can cap one noisy flow while the fleet stays unbounded ({@code max_files_per_cycle: N}),
     * exempt one flow from a fleet-wide cap ({@code max_files_per_cycle: 0}), or pin one flow's cap hard
     * ({@code adaptive: false}) without touching any {@code -D}. This record carries only what the author
     * <b>stated</b>; merging with the globals happens at the {@code IntakeGovernor} call site
     * ({@code CollectorProcessor}), never here — the config module does not know the runtime defaults.
     */
    @PublicApi(since = "4.0.0")
    public record Intake(Integer maxFilesPerCycle, Integer minFilesPerCycle, Boolean adaptive) {
        public Intake {
            if (maxFilesPerCycle != null && maxFilesPerCycle < 0)
                throw new IllegalArgumentException("processing.intake.max_files_per_cycle must be >= 0 (0 = unbounded)");
            if (minFilesPerCycle != null && minFilesPerCycle < 1)
                throw new IllegalArgumentException("processing.intake.min_files_per_cycle must be >= 1");
        }
    }

    /**
     * The Collector's unpack stage ({@code processing.unpack}) — pluggable decompression of inbox
     * files before the Consignment is planned. Null when the block is absent, which means
     * {@link #defaults()}: the stage is ON (it only acts on files a plugin claims AND the chosen
     * engine lane cannot read itself, so an inbox of plain CSV is untouched either way).
     *
     * <p>Every cap is fail-closed and enforced DURING expansion — a decompressor is a bomb vector by
     * construction, so a breach fails the expansion whole and the original takes the normal failure
     * path. {@code threads} bounds the stage's OWN pool: unpack runs before batch planning, so
     * borrowing the batch semaphore would serialize it behind ingest for no reason.
     */
    @PublicApi(since = "4.0.0")
    public record Unpack(boolean enabled, int maxEntries, long maxEntryBytes, long maxTotalBytes,
                         double maxRatio, int depth, int threads, List<String> dataExtensions) {

        public Unpack {
            // §6 Q4: at most ONE of these is stripped from a name to form its logical key, so a
            // deployment can narrow the list — or set it EMPTY to opt out of extension-insensitive
            // identity altogether. Empty is therefore VALID and meaningful, never "unset".
            dataExtensions = dataExtensions == null
                    ? com.gamma.etl.unpack.LogicalNames.DEFAULT_DATA_EXTENSIONS
                    : dataExtensions.stream()
                            .map(e -> e == null ? "" : e.trim().toLowerCase(java.util.Locale.ROOT))
                            .filter(e -> !e.isBlank())
                            .map(e -> e.startsWith(".") ? e : "." + e)
                            .distinct()
                            .toList();
            if (maxEntries < 1)    throw new IllegalArgumentException("processing.unpack.max_entries must be >= 1");
            if (maxEntryBytes < 1) throw new IllegalArgumentException("processing.unpack.max_entry_bytes must be >= 1");
            if (maxTotalBytes < 1) throw new IllegalArgumentException("processing.unpack.max_total_bytes must be >= 1");
            if (maxRatio < 0)      throw new IllegalArgumentException("processing.unpack.max_ratio must be >= 0 (0 = no ratio check)");
            if (depth != 1)        throw new IllegalArgumentException(
                    "processing.unpack.depth must be 1 — nested archives are not expanded (a zip-of-zips is a "
                    + "bomb vector; recursion is a deliberate future opt-in, see the unpack-stage plan)");
            if (threads < 1)       throw new IllegalArgumentException("processing.unpack.threads must be >= 1");
        }

        /** The absent-block posture: on, with the shipped caps, single-threaded expansion. */
        public static Unpack defaults() {
            // ⛔ dataExtensions is READ from LogicalNames, never restated here — one declaration.
            return new Unpack(true, 10_000, 8L << 30, 32L << 30, 10_000d, 1, 1,
                    com.gamma.etl.unpack.LogicalNames.DEFAULT_DATA_EXTENSIONS);
        }
    }

    /**
     * Fixed-width parsing frontend (additive, 4.1). Non-null only when the resolved grammar/
     * {@code csv_settings} sets {@code frontend: fixedwidth}; {@code null} for the default delimited
     * frontend (so every existing pipeline is unaffected).
     *
     * <p>Each record is carved into positional {@link Slice slices}. <b>Slice index {@code i} feeds the
     * schema field whose {@code selector} is {@code i}</b> — exactly the delimited path's
     * {@code c<selector>} column model — so the event {@code _schema.toon} (names/types/mapping/
     * partitions) is authored identically to a CSV source; only the tokenisation lives here.
     *
     * <ul>
     *   <li>{@code binary == false} (record: line) → DuckDB-native {@code read_csv}+{@code substring}
     *       ingest, reusing the whole CSV streaming/union/chunk path (see {@link DuckDbCsvIngester}).</li>
     *   <li>{@code binary == true} (record: bytes) → the {@code com.gamma.ingester.FixedWidthRecordIngester}
     *       plugin, wired via {@code processing.ingester} (it reads its layout from {@code ingester_config});
     *       this record is then unused.</li>
     * </ul>
     */
    @PublicApi(since = "4.0.0")
    public record FixedWidth(boolean binary, int recordLength, Trim trim,
                             int minRecordLength, List<Slice> slices) {
        /** One positional field: {@code start} (0-based), {@code length} (chars for text, bytes for binary), optional {@code name}. */
        public record Slice(String name, int start, int length) {}
        /** Field whitespace trimming applied at projection time (default {@link #BOTH}). */
        public enum Trim { NONE, LEFT, RIGHT, BOTH }
    }

    /**
     * JSON / NDJSON parsing frontend (additive, 4.8). Non-null only when the resolved parsing
     * settings set {@code frontend: json}; {@code null} otherwise (so every existing pipeline is
     * unaffected).
     *
     * <p>The frontend compiles to DuckDB {@code read_ndjson} ({@code format: newline}, the default)
     * or {@code read_json} ({@code format: array | auto}). Each schema field lands as a VARCHAR
     * column keyed by {@code raw.fields[].selector} — for this frontend the selector is the
     * <b>top-level JSON key</b>, not a column index — so the typing/mapping/partition/lineage
     * backend runs unchanged. Nested values: select the wrapping key and carve with an {@code EXPR}
     * mapping rule ({@code json_extract_string(...)}).
     *
     * @param format      {@code newline} (NDJSON, one object per line) | {@code array} | {@code auto}
     * @param recordsPath JSONPath to the record array; only {@code "$"} (the default) is supported
     */
    @PublicApi(since = "4.0.0")
    public record Json(String format, String recordsPath, int maximumObjectSize, boolean ignoreErrors) {
        /** The 4.8 shape — no reader tuning (multiformat J1 added the two knobs additively). */
        public Json(String format, String recordsPath) { this(format, recordsPath, 0, false); }
        /** Whether the input is newline-delimited (one JSON object per physical line). */
        public boolean newlineDelimited() { return "newline".equals(format); }
    }

    /**
     * MS Excel parsing frontend (additive, multiformat-parser-lanes plan X1). Non-null only when the
     * resolved parsing settings set {@code frontend: xlsx} (alias {@code excel}); {@code null}
     * otherwise, so every existing pipeline is unaffected.
     *
     * <p>Compiles to DuckDB {@code read_xlsx(...)} (the {@code excel} extension —
     * {@link ExcelExtension} loads it fail-closed). The ingest read always stamps
     * {@code all_varchar=true}: like every other frontend, raw columns land as VARCHAR and typing is
     * the mapping's concern, which is also why {@code empty_as_varchar} is not an option here. Each
     * schema field lands keyed by {@code raw.fields[].selector} — for this frontend the selector is
     * the <b>sheet column name</b> as {@code read_xlsx} yields it (the header cell when
     * {@code header: true}, else DuckDB's positional letters {@code A, B, C…}).
     *
     * <p>Every option below is a named parameter {@code read_xlsx} actually reads (probed against
     * duckdb_jdbc 1.5.2.1 via {@code duckdb_functions()} — there is deliberately no {@code columns}
     * option, because {@code read_xlsx} has none).
     *
     * @param sheet          sheet NAME to read; null = the extension's default (the first sheet)
     * @param range          A1-style cell range ({@code A1:F100} or a single anchor {@code B2}); null = whole sheet
     * @param header         first row of the range is a header row (default true)
     * @param stopAtEmpty    stop reading at the first fully empty row (default false; forced true by
     *                       the extension when no explicit range is given — its own documented rule)
     * @param ignoreErrors   replace unrepresentable cells with NULL instead of failing (default false)
     * @param normalizeNames normalize header names to lower_snake identifiers (default false)
     */
    @PublicApi(since = "4.0.0")
    public record Xlsx(String sheet, String range, boolean header, boolean stopAtEmpty,
                       boolean ignoreErrors, boolean normalizeNames) {}

    /**
     * Parquet parsing frontend (additive, ELT Phase 3 S3c-1). Non-null only when the resolved
     * parsing settings set {@code frontend: parquet}; {@code null} otherwise, so every existing
     * pipeline is unaffected.
     *
     * <p>Compiles to DuckDB {@code read_parquet(...)} — built into DuckDB, no extension load
     * (unlike {@link Xlsx}). Parquet is internally compressed, so the {@code Compression} wrapper
     * never applies. The file self-describes its columns with real types; the lane still lands
     * every projected column as VARCHAR ({@code CAST(col AS VARCHAR)} per selector — probed against
     * duckdb_jdbc 1.5.2.1: {@code read_parquet} returns typed columns, and {@code read_parquet} has
     * no {@code all_varchar} option) because, like every other frontend, typing is the mapping's
     * concern. Each schema field lands keyed by {@code raw.fields[].selector} — the parquet
     * <b>column name</b>.
     *
     * <p>One option, probed real: {@code hive_partitioning} exposes {@code key=value} directory
     * levels (the layout {@code PartitionWriter} itself emits — {@code year=/month=/day=}) as
     * selectable columns, which consuming a partitioned store genuinely needs. Every other
     * {@code read_parquet} parameter ({@code file_row_number}, …) stays refused — add one only
     * against a concrete need, never speculatively.
     *
     * @param hivePartitioning expose hive {@code key=value} directory levels as columns (default false)
     */
    @PublicApi(since = "4.0.0")
    public record Parquet(boolean hivePartitioning) {}

    /**
     * Text/regex parsing frontend (additive, 4.8; block records additive, 4.9). Non-null only when
     * the resolved parsing settings set {@code frontend: text_regex}; {@code null} otherwise.
     *
     * <p>Default ({@code recordSplit == "\n"}): each physical line is read intact as a single
     * VARCHAR column (the fixed-width {@code read_csv} single-column trick), lines matching
     * {@code pattern} are kept, and each named capture group becomes a VARCHAR column.
     *
     * <p>Block mode ({@code recordSplit} any other delimiter, e.g. {@code "\n\n"} for
     * {@code blank_line}): the whole file is read as text and split into records on the literal
     * delimiter, so a record may span multiple physical lines; {@code pattern} is then matched
     * against each record's full (trimmed) text with {@code .} matching newlines, letting a single
     * capture group span what were previously separate lines.
     *
     * <p>Either way, a schema field's {@code raw.fields[].selector} names the capture group that
     * feeds it, so the typing/mapping/partition/lineage backend runs unchanged. Non-matching
     * lines/records are dropped (like fixed-width short lines).
     *
     * @param recordSplit record separator; {@code "\n"} (one record per line) is the default;
     *                    {@code "blank_line"} is normalised to {@code "\n\n"}; any other literal
     *                    string is used as-is as the block delimiter
     * @param pattern     the RE2 regex with at least one named capture group, normalised to the
     *                    {@code (?P<name>...)} spelling DuckDB accepts
     * @param groupNames  the named capture groups in declaration order (⇒ DuckDB name_list order)
     */
    @PublicApi(since = "4.0.0")
    public record TextRegex(String recordSplit, String pattern, List<String> groupNames) {
        public TextRegex {
            groupNames = List.copyOf(groupNames);
        }
    }

    /**
     * Data-acquisition source binding (Data Acquisition roadmap Phase A; additive). <b>Never null</b> — a
     * pipeline with no {@code source:} block defaults to the local filesystem reading {@code dirs.poll} with
     * {@code includes = [processing.file_pattern]}, no excludes and unbounded depth: exactly the legacy scan.
     *
     * <p>{@code connector} selects the {@link com.gamma.acquire.CollectorConnector} ({@code "local"} built-in;
     * other schemes via the optional connector module). {@code includes}/{@code excludes} are glob/regex
     * patterns (see {@link com.gamma.acquire.DiscoveryContext}); {@code recursiveDepth} of {@code -1} is
     * unbounded.
     */
    @PublicApi(since = "4.0.0")
    public record Collector(String id, String connector, List<String> includes,
                         List<String> excludes, int recursiveDepth, Stability stability, String connection,
                         String dataset,
                         Duplicate duplicate, Guarantee guarantee, GapDetection gapDetection,
                         Fetch fetch, Retry retry, CircuitBreaker circuitBreaker, PostActionConfig postAction,
                         Incremental incremental, String discovery) {
        public Collector {
            includes = List.copyOf(includes);
            excludes = List.copyOf(excludes);
            // ACQ-6: how new files are noticed — interval "poll" (default) or filesystem-event "watch"
            // (local sources only; the poll loop stays on as the backstop either way).
            discovery = (discovery == null || discovery.isBlank()) ? "poll" : discovery.trim().toLowerCase();
            if (stability == null) stability = Stability.DISABLED;
            if (duplicate == null) duplicate = Duplicate.PATH_DEFAULT;
            if (guarantee == null) guarantee = Guarantee.BEST_EFFORT;
            if (gapDetection == null) gapDetection = GapDetection.DISABLED;
            if (fetch == null) fetch = Fetch.DEFAULT;
            if (retry == null) retry = Retry.DISABLED;
            if (circuitBreaker == null) circuitBreaker = CircuitBreaker.DISABLED;
            if (postAction == null) postAction = PostActionConfig.RETAIN;
            if (incremental == null) incremental = Incremental.DISABLED;
        }

        /** A reusable connection-profile id this source binds to ({@code source.connection}), or {@code null}
         *  for the local filesystem. Resolved against the service's {@code *_connection.toon} registry. */
        public boolean hasConnection() { return connection != null && !connection.isBlank(); }

        /** The Dataset this source consumes ({@code source.dataset}, ELT P3 S3c-2 — set with
         *  {@code connector: dataset}), or {@code null} for every file-shaped source. */
        public boolean hasDataset() { return dataset != null && !dataset.isBlank(); }
    }

    /**
     * Collection-guarantee level for a source (Data Acquisition roadmap Phase D; additive, {@code source.guarantee:}).
     * The teeth live in machinery that already exists: the fsync'd {@link CommitLog} gives idempotent replay
     * after a crash, and the Phase-C fingerprint ledger ({@code source.duplicate.mode != path}) skips an
     * already-processed file. So this knob is <b>declarative</b> — {@link #AT_LEAST_ONCE}/{@link #EXACTLY_ONCE}
     * {@linkplain #requiresLedger() require a ledger} to hold; the engine logs a warning if a stronger guarantee
     * is declared over path-only (marker) dedup, and behaves as best-effort + commit-log replay in that case.
     */
    @PublicApi(since = "4.0.0")
    public enum Guarantee {
        /** Today's behaviour: markers + commit log, no fingerprint ledger required. */ BEST_EFFORT,
        /** Every file is processed at least once (ledger-backed; safe to re-fetch). */  AT_LEAST_ONCE,
        /** Logical exactly-once: the ledger marks processed only after the batch commits. */ EXACTLY_ONCE;

        public static Guarantee from(String s) {
            if (s == null || s.isBlank()) return BEST_EFFORT;
            return switch (s.trim().toUpperCase()) {
                case "AT_LEAST_ONCE", "AT-LEAST-ONCE" -> AT_LEAST_ONCE;
                case "EXACTLY_ONCE", "EXACTLY-ONCE"   -> EXACTLY_ONCE;
                default -> BEST_EFFORT;
            };
        }
        /** Whether this guarantee needs the fingerprint ledger (content-based dedup) to actually hold. */
        public boolean requiresLedger() { return this != BEST_EFFORT; }
    }

    /**
     * Sequence-gap detection for a source (Data Acquisition roadmap Phase D; additive, {@code source.gap_detection:}).
     * When {@link #active()} the engine, after discovery, checks the observed file names against the
     * {@link #sequence} strftime-style template (e.g. {@code "CDR_{yyyyMMddHH}"}) and emits an
     * {@link com.gamma.event.EventType#SEQUENCE_GAP} event per missing key — so "no file silently missed" is a
     * recorded, queryable operational fact. See {@link com.gamma.acquire.GapDetector}.
     *
     * <p>{@link #DISABLED} (no {@code source.gap_detection:} block) ⇒ no series check (the legacy behaviour).
     */
    @PublicApi(since = "4.0.0")
    public record GapDetection(boolean enabled, String sequence) {
        /** No gap detection — the legacy behaviour. */
        public static final GapDetection DISABLED = new GapDetection(false, null);
        /** Whether gap detection should run (enabled and given a non-blank sequence template). */
        public boolean active() { return enabled && sequence != null && !sequence.isBlank(); }
    }

    /**
     * Duplicate-detection + change policy for a source (Data Acquisition roadmap Phase C; additive,
     * {@code source.duplicate:}). {@code mode} selects how a re-seen path is judged — {@code path} (default =
     * today's {@code MarkerManager} sentinel), {@code metadata} (name+size+mtime), {@code checksum}
     * ({@code algorithm} ∈ MD5/SHA256/CRC32, computed at processing time), or {@code etag} (ACQ-7: the
     * connector-supplied listing etag/object version, falling back to metadata when the connector has neither
     * — pre-fetch-capable, so an unchanged remote object is skipped without a download). {@code on_change}
     * chooses what
     * happens when a known path's content changed: {@code ignore}/{@code reprocess}/{@code alert}/
     * {@code archive_old_version}. Parsed into {@link com.gamma.acquire.DuplicatePolicy} enums by the engine.
     *
     * <p>{@link #PATH_DEFAULT} (no {@code source.duplicate:} block) reproduces today's behaviour exactly.
     */
    @PublicApi(since = "4.0.0")
    public record Duplicate(String mode, String algorithm, String onChange) {
        /** Path-keyed dedup via marker sentinels — the legacy default. */
        public static final Duplicate PATH_DEFAULT = new Duplicate("path", "SHA256", "reprocess");
        public Duplicate {
            mode      = (mode == null || mode.isBlank()) ? "path" : mode.trim().toLowerCase();
            algorithm = (algorithm == null || algorithm.isBlank()) ? "SHA256" : algorithm.trim();
            onChange  = (onChange == null || onChange.isBlank()) ? "reprocess" : onChange.trim().toLowerCase();
        }
        /** Whether content-based dedup (a fingerprint ledger) is in effect (vs. the path-only default). */
        public boolean contentBased() { return !"path".equals(mode); }
    }

    /**
     * Incremental discovery / high-watermark for a source (Data Acquisition roadmap Phase C4; additive,
     * {@code source.incremental:}). When {@link #enabled()} the engine drops any discovered candidate whose
     * modification time is <em>strictly older</em> than the source's <b>high-watermark</b> — the greatest
     * {@code last_modified} of any file the {@linkplain com.gamma.acquire.AcquisitionLedger fingerprint ledger}
     * has already recorded for this source — so a re-scan only re-examines the recent frontier instead of
     * re-LIST'ing/re-fetching (remote) or re-stat'ing the deep history (local).
     *
     * <p>The watermark is <em>derived</em> from the ledger (max recorded {@code last_modified}), so this knob
     * only has effect alongside a content-based {@code source.duplicate} mode (metadata/checksum) — with the
     * path-only default the ledger is empty and the filter no-ops. It is an optimisation for monotonic-arrival
     * sources (timestamps that only increase, e.g. {@code CDR_<ts>} feeds); a file re-uploaded <em>below</em>
     * the watermark is intentionally skipped, so leave it off if you must catch arbitrarily back-dated
     * re-uploads. The frontier ({@code == watermark}) is never blindly skipped — it passes through to the
     * ledger for exact dedup.
     *
     * <p>{@link #DISABLED} (no {@code source.incremental:} block) ⇒ the full discovery listing (legacy behaviour).
     */
    @PublicApi(since = "4.0.0")
    public record Incremental(String watermark) {
        /** The high-watermark dimension; {@code last_modified} is the only one implemented (etag/version future). */
        public static final String LAST_MODIFIED = "last_modified";
        /** No incremental filtering — the legacy full-listing behaviour. */
        public static final Incremental DISABLED = new Incremental(null);
        public Incremental {
            watermark = (watermark == null || watermark.isBlank()) ? null : watermark.trim().toLowerCase();
        }
        /** Whether incremental high-watermark filtering should run. */
        public boolean enabled() { return LAST_MODIFIED.equals(watermark); }
    }

    /**
     * Readiness / stability detection for a source (Data Acquisition roadmap Phase B; additive,
     * {@code source.stability:}). When {@link #enabled} the engine holds a discovered file back until it has
     * stopped changing — {@link com.gamma.acquire.StabilityGate} releases it only once it has been quiescent
     * for {@link #windowMillis} and seen at the same size on {@link #sizeChecks} consecutive cycles — so a
     * half-written file is never ingested. A connector that knows readiness natively (or a {@link #readyMarker}
     * sentinel on the local connector) short-circuits this. {@code excludeTempFiles} merges
     * {@link #DEFAULT_TEMP_PATTERNS} (or {@code tempPatterns}) into the discovery excludes.
     *
     * <p>{@link #DISABLED} (no {@code source.stability:} block) is the legacy behaviour: a matched file is a
     * candidate immediately and nothing is stat'd for stability.
     */
    @PublicApi(since = "4.0.0")
    public record Stability(boolean enabled, long windowMillis, int sizeChecks,
                            String readyMarker, boolean excludeTempFiles, List<String> tempPatterns) {
        /** Temp / in-flight patterns excluded by default when stability gating is on (filename globs). */
        public static final List<String> DEFAULT_TEMP_PATTERNS =
                List.of("*.tmp", "*.partial", "*.filepart", ".~lock.*");
        /** No stability gating — the legacy "process a matched file at once" behaviour. */
        public static final Stability DISABLED =
                new Stability(false, 0L, 0, null, false, List.of());
        public Stability {
            tempPatterns = List.copyOf(tempPatterns);
        }
    }

    /**
     * Retrieval tuning for a remote source (Data Acquisition roadmap Phase E/F; additive, {@code source.fetch:}).
     * {@code parallelFetch} > 1 fetches ready files concurrently over a pool of independent connector sessions
     * (each connector instance holds one non-thread-safe session, so concurrency = a pool, not shared reuse);
     * {@code rateLimitBytesPerSec} > 0 throttles aggregate transfer via a token bucket. {@code mode} is advisory
     * (the file-based batch path needs a local copy, so a remote source always stages).
     *
     * <p>{@link #DEFAULT} (no block) ⇒ sequential, unthrottled — exactly the Phase-E behaviour.
     */
    @PublicApi(since = "4.0.0")
    public record Fetch(String mode, String stagingDir, int parallelFetch, long rateLimitBytesPerSec) {
        public static final Fetch DEFAULT = new Fetch("STAGE", null, 1, 0L);
        public Fetch {
            mode = (mode == null || mode.isBlank()) ? "STAGE" : mode.trim().toUpperCase();
            if (parallelFetch < 1) parallelFetch = 1;
            if (rateLimitBytesPerSec < 0) rateLimitBytesPerSec = 0L;
        }
        /** Whether more than one file should be fetched at a time (needs a connector-session pool). */
        public boolean parallel() { return parallelFetch > 1; }
        /** Whether aggregate transfer is rate-limited. */
        public boolean rateLimited() { return rateLimitBytesPerSec > 0; }
    }

    /**
     * Retry/backoff policy for transient acquisition faults (Data Acquisition roadmap Phase F; additive,
     * {@code source.retry:}). Wraps connectivity-sensitive operations (discover, per-file fetch); {@code count}
     * is the number of <em>retries</em> after the first attempt, {@code backoff} ∈ {@code EXPONENTIAL|LINEAR|FIXED}
     * with full jitter, bounded by {@code initialDelay}…{@code maxDelay}. Realised by
     * {@link com.gamma.acquire.retry.RetryPolicy}.
     *
     * <p>{@link #DISABLED} (no block, {@code count == 0}) ⇒ a single attempt — exactly today's behaviour.
     */
    @PublicApi(since = "4.0.0")
    public record Retry(int count, String backoff, long initialDelayMillis, long maxDelayMillis) {
        public static final Retry DISABLED = new Retry(0, "EXPONENTIAL", 1_000L, 60_000L);
        public Retry {
            if (count < 0) count = 0;
            backoff = (backoff == null || backoff.isBlank()) ? "EXPONENTIAL" : backoff.trim().toUpperCase();
            if (initialDelayMillis <= 0) initialDelayMillis = 1_000L;
            if (maxDelayMillis < initialDelayMillis) maxDelayMillis = initialDelayMillis;
        }
        /** Whether any retry is configured (vs. a single attempt). */
        public boolean enabled() { return count > 0; }
    }

    /**
     * Per-source circuit breaker (Data Acquisition roadmap Phase F; additive, {@code source.circuit_breaker:}).
     * After {@code failureThreshold} consecutive connectivity failures the source is tripped OPEN and skipped for
     * {@code cooldownMillis} (then a single HALF_OPEN trial) instead of hammering a dead endpoint. Realised by the
     * process-wide {@link com.gamma.acquire.CircuitBreaker#shared()}.
     *
     * <p>{@link #DISABLED} (no block) ⇒ never trips — exactly today's behaviour.
     */
    @PublicApi(since = "4.0.0")
    public record CircuitBreaker(boolean enabled, int failureThreshold, long cooldownMillis) {
        public static final CircuitBreaker DISABLED = new CircuitBreaker(false, 5, 300_000L);
        public CircuitBreaker {
            if (failureThreshold < 1) failureThreshold = 1;
            if (cooldownMillis < 0) cooldownMillis = 0L;
        }
    }

    /**
     * Source-side post-processing action applied after a fetched file is integrity-validated and staged
     * (Data Acquisition roadmap Phase F; additive, {@code source.post_action:}). {@code onSuccess} ∈
     * {@code RETAIN|DELETE|MOVE|RENAME|TAG} (validated against the connector's
     * {@link com.gamma.acquire.CollectorConnector.Capability capabilities}); {@code onUnsupported} ∈
     * {@code FAIL|WARN_AND_CONTINUE|IGNORE} decides what happens when the connector can't perform it.
     * {@code archivePath} (a {@code yyyy/MM/dd}-style template) is used by {@code MOVE}; {@code tags} by {@code TAG}.
     *
     * <p>{@link #RETAIN} (no block) leaves the source untouched — exactly today's behaviour.
     */
    @PublicApi(since = "4.0.0")
    public record PostActionConfig(String onSuccess, String archivePath, Map<String, String> tags,
                                   String onUnsupported) {
        public static final PostActionConfig RETAIN =
                new PostActionConfig("RETAIN", null, Map.of(), "WARN_AND_CONTINUE");
        public PostActionConfig {
            onSuccess     = (onSuccess == null || onSuccess.isBlank()) ? "RETAIN" : onSuccess.trim().toUpperCase();
            onUnsupported = (onUnsupported == null || onUnsupported.isBlank())
                    ? "WARN_AND_CONTINUE" : onUnsupported.trim().toUpperCase();
            tags = (tags == null) ? Map.of() : Map.copyOf(tags);
        }
        /** Whether a non-RETAIN finalization should run on the source-side file after success. */
        public boolean active() { return !"RETAIN".equals(onSuccess); }
    }

    /**
     * What this pipeline's output registers as in the Catalog ({@code produces:} top-level key,
     * v5.1.0; absent ⇒ {@link #STREAM} — exactly the prior behaviour). A {@code reference}
     * pipeline's partitioned output is a <b>Reference Dataset</b> (dimension/lookup data origin)
     * rather than an event/fact Stream: the catalog registers it standalone (id
     * {@code ref:<pipeline>}) and Stage-2 enrichments may bind it by name
     * ({@code references.<name>.ref:}) instead of a raw path.
     */
    @PublicApi(since = "4.0.0")
    public enum Produces {
        /** Event/fact data origin — the default; the catalog registers a Stream. */
        STREAM,
        /** Dimension/lookup data origin — the catalog registers a standalone Reference Dataset. */
        REFERENCE;

        /** Parse the {@code produces:} value; blank/absent ⇒ {@link #STREAM}, anything else must match. */
        public static Produces from(String s) {
            if (s == null || s.isBlank()) return STREAM;
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "STREAM"    -> STREAM;
                case "REFERENCE" -> REFERENCE;
                default -> throw new IllegalArgumentException(
                        "produces must be 'stream' or 'reference', got: '" + s + "'");
            };
        }
    }

    /**
     * How a {@code produces: reference} pipeline's Reference Dataset is loaded ({@code reference.load:},
     * Reference Phase-2; absent ⇒ {@link #REPLACE} = exactly today's behaviour). {@link #REPLACE}
     * rewrites the whole partition each run (v1 full-replace semantics); {@link #UPSERT} keeps the
     * latest version per declared {@code reference.key} (latest-version-wins); {@link #SCD2}
     * additionally preserves superseded versions as slowly-changing-dimension history. {@code UPSERT}
     * and {@code SCD2} require a non-empty {@code reference.key}. The engine mechanics land in later
     * phases (P1/P2); P0 only carries and validates the config.
     */
    @PublicApi(since = "4.0.0")
    public enum Load {
        /** Full-replace — the default; rewrites the partition each run (v1 semantics). */
        REPLACE,
        /** Latest-version-wins per {@code reference.key} (needs a key). */
        UPSERT,
        /** SCD-2 history: keeps superseded versions as well as the current one (needs a key). */
        SCD2;

        /** Parse the {@code reference.load:} value; blank/absent ⇒ {@link #REPLACE}, else must match. */
        public static Load from(String s) {
            if (s == null || s.isBlank()) return REPLACE;
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "REPLACE" -> REPLACE;
                case "UPSERT"  -> UPSERT;
                case "SCD2"    -> SCD2;
                default -> throw new IllegalArgumentException(
                        "reference.load must be 'replace', 'upsert' or 'scd2', got: '" + s + "'");
            };
        }

        /** Whether this load mode needs a declared {@code reference.key} (upsert/scd2 do). */
        public boolean requiresKey() { return this != REPLACE; }

        /**
         * Whether the produced store is the append-only versioned one (§2.1 system columns, one
         * version row per key per batch) rather than a full-replace snapshot. Both {@code upsert} and
         * {@code scd2} write it identically — they differ only in what is <em>readable</em>
         * ({@code scd2} additionally serves as-of history) and in what compaction retains.
         */
        public boolean versionedStore() { return this != REPLACE; }
    }

    /**
     * The optional {@code reference:} block on a {@code produces: reference} pipeline (Reference
     * Phase-2; additive). Declares the load semantics of the produced Reference Dataset. <b>Never
     * null</b> — absent ⇒ {@link #DEFAULT} (full-replace, no key, no refresh timer), i.e. today's
     * behaviour, so every existing pipeline parses and runs identically. The block is only meaningful
     * when {@code produces: reference}; on a Stream pipeline it is inert.
     *
     * @param key            declared identity columns (empty unless upsert/scd2); each must exist in
     *                       the pipeline schema (validated at parse when a schema is resolved)
     * @param load           {@link Load#REPLACE} (default) | {@link Load#UPSERT} | {@link Load#SCD2}
     * @param refreshSeconds {@code 0} = re-materialize on collect only (today); {@code >0} arms a
     *                       periodic compaction/re-materialize timer (Phase-3 — parsed/stored now)
     */
    @PublicApi(since = "4.0.0")
    public record Reference(List<String> key, Load load, int refreshSeconds) {
        /** Full-replace, no key, no refresh timer — exactly the pre-Phase-2 behaviour. */
        public static final Reference DEFAULT = new Reference(List.of(), Load.REPLACE, 0);
        public Reference {
            key = (key == null) ? List.of() : List.copyOf(key);
            if (load == null) load = Load.REPLACE;
            if (refreshSeconds < 0) refreshSeconds = 0;
        }
        /** Whether a periodic refresh/compaction timer should be armed (Phase-3). */
        public boolean refreshEnabled() { return refreshSeconds > 0; }
    }

    /**
     * Record-grain dedup ({@code processing.dedup}, ELT amendment §2.4: business-key dedup is a
     * <b>Step</b>, unlike file dedup which is a Guarantee). Compiles to a {@code ROW_NUMBER() OVER
     * (PARTITION BY keys ORDER BY orderBy)} QUALIFY between transform materialisation and the
     * partition write — the winner per key survives, duplicates are counted.
     *
     * @param keys    the business-key columns (target/mapped names); never empty
     * @param orderBy optional SQL order deciding the winner per key (blank ⇒ arbitrary/first seen —
     *                but REQUIRED once {@code scope} declares a window; the engine and the save gates
     *                both refuse the pair, see {@code DedupScope.refusal})
     * @param scope   optional {@code scope:} spelling — {@code consignment} (default) or
     *                {@code window(<ISO-8601 period>)} for cross-Consignment suppression (D-9). Carried
     *                verbatim: this module sits below the engine's {@code DedupScope} vocabulary, so
     *                the string is validated where it is read (RowShaper / the save-path findings).
     */
    @PublicApi(since = "4.0.0")
    public record Dedup(List<String> keys, String orderBy, String scope) {
        public Dedup {
            if (keys == null || keys.isEmpty())
                throw new IllegalArgumentException("processing.dedup needs a non-empty keys[] list");
            keys = List.copyOf(keys);
        }

        /** Pre-D-9 shape: no {@code scope:}, i.e. within-Consignment dedup. */
        public Dedup(List<String> keys, String orderBy) {
            this(keys, orderBy, null);
        }
    }

    /**
     * Group-by rollup ({@code processing.summarize}, ELT amendment §2.4/Phase 3: {@code summarize} is
     * a Step). {@code measures} reuses {@code MaterializeTask}'s compact shorthand ({@code count},
     * {@code sum(amount)}, …) so a compiled recipe is byte-compatible with the existing
     * {@code materialize} maintenance-task grammar once the two are wired together — this record is
     * authoring/round-trip only for now (see {@link #prepare()}): {@code MaterializeTask} stays the
     * runtime until Phase 3 wires a recipe-driven executor.
     *
     * @param groupBy  the group-by columns (may be empty when every row collapses into one summary row)
     * @param measures the measure shorthand list; never empty
     */
    @PublicApi(since = "4.0.0")
    public record Summarize(List<String> groupBy, List<String> measures) {
        public Summarize {
            if (measures == null || measures.isEmpty())
                throw new IllegalArgumentException("processing.summarize needs a non-empty measures[] list");
            groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
            measures = List.copyOf(measures);
        }
    }

    /**
     * The authored half of a {@code transform.map} node ({@code processing.map}) — the flat file's home
     * for a projection an operator typed into the map node's dialog, added so that
     * {@code PipelineEditable.lower} stops dropping it silently (AUTHOR-1 follow-on (a)).
     *
     * <p>⚠ Unlike {@code summarize}/{@code join}/{@code route}, this block is <b>executable today</b>:
     * {@code RowShaper.columnsOf} honours {@code columns} and {@code mappingSchemaOf} honours
     * {@code rules} on the graph executor that {@code PipelineJobRunner} already runs in production. So
     * {@link #prepare()} does <b>not</b> refuse it on an {@code active} pipeline — refusing would break
     * the very case it exists to serve.
     *
     * <p>⛔ It is deliberately <b>not</b> a {@link Step} kind: a map node sits between parser and sink in
     * every lifted graph, in both the legacy and the {@code steps:} spelling, so giving it a chain entry
     * would change when {@code steps:} is emitted at all and rewrite files that round-trip verbatim
     * today. For the same reason it is absent from the parser's {@code steps:}-exclusivity list — a
     * {@code steps:} file may carry {@code processing.map}.
     *
     * @param columns explicit projection entries ({@code [{name, expr}]}); empty when unauthored
     * @param rules   mapping-component rules, field types undeclared; empty when unauthored
     */
    @PublicApi(since = "4.0.0")
    public record MapConfig(List<Map<String, Object>> columns, List<Map<String, Object>> rules) {
        public MapConfig {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rules   = rules   == null ? List.of() : List.copyOf(rules);
            if (columns.isEmpty() && rules.isEmpty())
                throw new IllegalArgumentException("processing.map needs a non-empty columns[] or rules[] list");
        }

        /** True when neither half carries anything the executor would read. */
        public boolean isEmpty() { return columns.isEmpty() && rules.isEmpty(); }
    }

    /**
     * Reference join ({@code processing.join}, ELT amendment §2 D-4: the join is a {@code transform}
     * concern — {@code transform: {join: references/x, on: k}} in the recipe; no separate enrich verb).
     * {@code reference} names the join source: a {@code reference/<id>} registry ref (a
     * {@code produces: reference} pipeline, {@code EnrichmentConfig.Reference}'s {@code ref} variant)
     * or a verbatim path. Authoring/round-trip only for now (see {@link #prepare()}): the linear batch
     * path has no in-pipeline join executor — {@code EnrichmentEngine} runs the join model post-commit
     * off the companion {@code *_enrich.toon}, not off this section.
     *
     * @param reference the join source ({@code reference/<id>} or a path); never blank
     * @param on        the join-key columns (input-side names); never empty
     */
    @PublicApi(since = "4.0.0")
    public record Join(String reference, List<String> on) {
        public Join {
            if (reference == null || reference.isBlank())
                throw new IllegalArgumentException("processing.join needs a reference (reference/<id> or a path)");
            if (on == null || on.isEmpty())
                throw new IllegalArgumentException("processing.join needs a non-empty on[] key list");
            on = List.copyOf(on);
        }
    }

    // ── grouped state + accessors ──────────────────────────────────────────────

    private final Identity   identity;
    private final Dirs       dirs;
    private final Processing processing;
    private final CsvSettings csv;
    private final Output     output;
    private final List<Sink> sinks;
    private final List<Step> steps;
    private final boolean explicitSteps;
    private final Schemas    schemas;
    private final DuckDbSettings duckdb;
    private final Chunking       chunking;
    private final Intake         intake;
    private final Unpack         unpack;
    private final FixedWidth     fixedWidth;
    private final Json           json;
    private final Xlsx           xlsx;
    private final Parquet        parquet;
    private final TextRegex      textRegex;
    private final Collector      collector;

    /**
     * Whether this pipeline is activated for execution ({@code active:} top-level key, v4.7.0). Only
     * activated pipelines are run by the poll cycle / multi-source orchestrator; an inactive pipeline
     * is still parsed, indexed and queryable (it shows in {@code /runs}) but never executed.
     *
     * <p><b>Default is {@code false}</b> — a pipeline must opt in with {@code active: true}. This is a
     * deliberate fail-safe so a freshly-dropped or half-edited config never runs until explicitly armed.
     */
    private final boolean active;

    /**
     * Whether this is a non-runnable authoring <b>template</b> ({@code template:} top-level key, v5.4.0).
     * A template is a starting point for standing up a similar pipeline: it parses and validates like any
     * other config, but it is <b>never registered</b> — {@code SpaceBootstrap} skips it at boot and
     * {@link com.gamma.service.CollectorService#registerPipeline} refuses it. Because every run path
     * (trigger route, poll cycle, scheduler) goes through the run registry, not being in it makes a
     * template structurally unreachable rather than merely gated.
     *
     * <p>This is deliberately stronger than {@code active: false}: an inactive pipeline is still
     * registered and can still be run on demand via {@code POST /runs/{name}/trigger}.
     *
     * <p><b>Default is {@code false}</b>, so every pre-existing config is unaffected.
     */
    private final boolean template;

    /**
     * Free-text note on what this pipeline is for ({@code description:} top-level key). Purely a display
     * label — the list route surfaces it as the row subtitle and <b>nothing in the engine reads it</b>.
     * Modelled here only so it can be projected; before this it was an unmodelled passthrough that an
     * operator could type into the create dialog and then never see again.
     *
     * <p>Never null; empty when absent.
     */
    private final String description;

    /** What the output registers as in the Catalog ({@code produces:}, v5.1.0; default STREAM). */
    private final Produces produces;

    /**
     * The {@code reference:} block load semantics (Reference Phase-2; never null, {@link Reference#DEFAULT}
     * when absent). Only meaningful for a {@code produces: reference} pipeline; inert otherwise.
     */
    private final Reference reference;

    /**
     * The logical Catalog <b>Stream</b> this pipeline is a member of ({@code stream:}, Reference
     * Phase-2 / GLOSSARY §3; never null). Defaults to the pipeline's own name, preserving today's
     * strict 1:1 pipeline↔Stream mapping; several pipelines sharing one {@code stream:} name are
     * grouped under a single Stream in the catalog graph (P4). Normalised like the pipeline name
     * (lowercased, spaces→underscores) and validated as a SQL identifier.
     */
    private final String stream;

    /**
     * The resting store the <b>at-rest Stage-2 chain</b> writes (top-level {@code output_store:}), or
     * {@code null} when absent. Authored, never derived — the operator decision (2026-08-11, multiplicity
     * plan "A5 RE-SCOPED"): the input of that run is this pipeline's landed store, so its output needs its
     * own explicit name. Normalised and validated like {@code stream:} because it becomes a store
     * directory / catalog join key. Read by {@code PipelineLift.stageTwo}; the linear ingest path ignores
     * it entirely.
     */
    private final String outputStore;

    /**
     * The optional entry-node {@code trigger:} block (T13 / §3.6) verbatim, or {@code null} when absent.
     * Absent ⇒ the pipeline rides the default poll cycle exactly as before; present ⇒ the live loop
     * ({@link com.gamma.service.CollectorService}) classifies it via {@code com.gamma.pipeline.PipelineTrigger}
     * into {@code schedule}(every/cron) / {@code event} / {@code manual}. Carried onto the lifted
     * acquisition node so the flow projection and the live driver agree on the schedule.
     */
    private final Map<String, Object> trigger;

    /** Record-grain dedup ({@code processing.dedup}); {@code null} when absent. */
    private final Dedup dedup;

    /** The {@code route:} block verbatim; {@code null} when absent. See {@link #routeConfig()}. */
    private final Map<String, Object> route;

    /** Group-by rollup ({@code processing.summarize}); {@code null} when absent. */
    private final Summarize summarize;

    /** Reference join ({@code processing.join}); {@code null} when absent. */
    private final Join join;

    /** Authored map projection ({@code processing.map}); {@code null} when absent. */
    private final MapConfig mapConfig;

    /**
     * Step ids the author disabled ({@code processing.disabled_steps}, Phase 4 S4 / D-13) — the ONE
     * durable home for per-Step {@code enabled:}: the lift overlays {@code enabled: false} onto the
     * named nodes, so the canvas and the scratch paths (dry-run / run-to-here) see the bypass without
     * a per-node key in the flat file. Never null; empty ⇒ every step enabled. Arming with a non-empty
     * list is refused in {@link #prepare()} until park/drain semantics ship (S4b) — see
     * {@link StepDisableArming}.
     */
    private final List<String> disabledSteps;

    /**
     * Other config files this pipeline read at parse time (schema / grammar / segment {@code .toon}s),
     * as given in the file (not absolutised). Used by {@link com.gamma.service.ConfigRegistry} to detect
     * on-disk changes (mtime) and reload only when something actually changed. The pipeline file itself
     * is tracked separately by the registry (it owns that path). Never null.
     */
    private final List<Path> referencedFiles;

    /**
     * The {@code status_dir} to create in {@link #prepare()} ({@code null} when status is disabled or
     * a literal {@code status_file} was used). Holding it here keeps {@link #fromMap} free of the one
     * filesystem side-effect that {@code load} historically performed inline.
     */
    private final String statusDirToPrepare;

    public Identity   identity()   { return identity; }
    public Dirs       dirs()       { return dirs; }
    public Processing processing() { return processing; }
    public CsvSettings csv()       { return csv; }
    public Output     output()     { return output; }
    /**
     * Output destinations; never empty. A one-element list is the single-{@code output:} shorthand
     * ({@code dirs.database} + {@code output:}); a longer list comes from an explicit {@code sinks:} block.
     */
    public List<Sink> sinks()      { return sinks; }
    /**
     * The ordered transform chain — an explicit {@code steps:} list, else the legacy singular blocks
     * projected into {@code PipelineLift}'s order. Never {@code null}; empty when the pipeline has no
     * transforms at all.
     *
     * <p>⚠ <b>Nothing executes from this yet</b> — the flat batch path still reads {@link #dedup()} and
     * {@code csv.rowWhere()} directly, and the graph-native path walks the graph. This is the reader half
     * (plan slice A2); {@code lower()} emits {@code steps:} since A3 and execution routes in A5. Until
     * then an explicit {@code steps:} file is authoring-only — and {@link #prepare()} <b>refuses to arm
     * one</b>, because a chain nothing reads is a pipeline that runs the wrong thing in silence.
     */
    public List<Step> steps()      { return steps; }
    /**
     * Whether the chain came from an explicit {@code steps:} block rather than the legacy projection.
     *
     * <p>⚠ <b>The two are not interchangeable, which is why this exists.</b> {@link #steps()} is always
     * populated, so it cannot answer "did the author write a sequence?" — and three places need that
     * answer, for the same underlying reason: the legacy blocks are what the flat path actually reads.
     * {@code PipelineLift} walks an explicit chain but keeps its proven hard-coded emission for legacy
     * files; {@link #prepare()} refuses to arm an explicit chain; and {@code PipelineEditable.lower()}
     * only writes {@code steps:} for a graph the singular keys cannot hold.
     */
    public boolean hasExplicitSteps() { return explicitSteps; }
    public Schemas    schemas()    { return schemas; }
    /** Optional DuckDB resource controls; never null (fields may be null ⇒ DuckDB defaults). */
    public DuckDbSettings duckdb()   { return duckdb; }
    /** Optional large-file chunking config; never null ({@code maxFileBytes <= 0} ⇒ disabled). */
    public Chunking       chunking() { return chunking; }
    /** Per-pipeline intake admission-control override, or {@code null} = inherit the {@code -D} globals. */
    public Intake         intake()   { return intake; }
    /** Never null — an absent {@code processing.unpack} block reads as {@link Unpack#defaults()}. */
    public Unpack         unpack()   { return unpack == null ? Unpack.defaults() : unpack; }
    /** Fixed-width frontend config, or {@code null} for the default delimited frontend. */
    public FixedWidth     fixedWidth() { return fixedWidth; }
    /** JSON/NDJSON frontend config, or {@code null} unless {@code frontend: json}. */
    public Json           json()       { return json; }
    /** MS Excel frontend config, or {@code null} unless {@code frontend: xlsx}. */
    public Xlsx           xlsx()       { return xlsx; }
    /** Parquet frontend config, or {@code null} unless {@code frontend: parquet}. */
    public Parquet        parquet()    { return parquet; }
    /** Text/regex frontend config, or {@code null} unless {@code frontend: text_regex}. */
    public TextRegex      textRegex()  { return textRegex; }
    /** Data-acquisition source binding; never null (defaults to local-FS over {@code dirs.poll}). */
    public Collector      collector()  { return collector; }
    /** Whether this pipeline is activated for execution ({@code active:}, default {@code false}). */
    public boolean        active()     { return active; }
    /** Whether this is a non-runnable authoring template ({@code template:}, default {@code false}). */
    public boolean        template()   { return template; }
    /** Free-text note on what this pipeline is for ({@code description:}); {@code ""} when absent. */
    public String         description() { return description; }
    /** What the output registers as in the Catalog ({@code produces:}, default {@link Produces#STREAM}). */
    public Produces       produces()   { return produces; }
    /** Whether this pipeline's output is a Reference Dataset ({@code produces: reference}). */
    public boolean producesReference() { return produces == Produces.REFERENCE; }
    /** The {@code reference:} load semantics; never null ({@link Reference#DEFAULT} when absent). */
    public Reference       reference()  { return reference; }
    /** The logical Catalog Stream this pipeline belongs to ({@code stream:}, default = pipeline name). */
    public String          stream()     { return stream; }
    /** The authored at-rest Stage-2 output store ({@code output_store:}), or {@code null} when absent. */
    public String          outputStore(){ return outputStore; }
    /** The raw entry-node {@code trigger:} block (T13), or {@code null} when absent (⇒ default poll). */
    public Map<String, Object> triggerConfig() { return trigger; }
    /** Record-grain dedup ({@code processing.dedup}), or {@code null} when absent. */
    public Dedup dedup() { return dedup; }

    /** Step ids disabled by {@code processing.disabled_steps} (S4/D-13); never null, empty ⇒ all enabled. */
    public List<String> disabledSteps() { return disabledSteps; }
    /**
     * The top-level {@code route:} block <b>verbatim</b>, or {@code null} when absent. Authoring/
     * round-trip only for now: {@link #prepare()} refuses an {@code active} pipeline carrying it, because
     * the linear batch path cannot execute a branch tree — arming lands with the branch-aware executor.
     */
    public Map<String, Object> routeConfig() { return route; }
    /**
     * Group-by rollup ({@code processing.summarize}), or {@code null} when absent. Authoring/round-trip
     * only for now: {@link #prepare()} refuses an {@code active} pipeline carrying it, because
     * {@code MaterializeTask} (the only executor of this measure grammar) runs over a Dataset relation,
     * not the linear batch path — arming lands once a recipe-driven executor is wired (Phase 3).
     */
    public Summarize summarize() { return summarize; }
    /**
     * Reference join ({@code processing.join}), or {@code null} when absent. Authoring/round-trip only:
     * {@link #prepare()} refuses an {@code active} pipeline carrying it — the linear batch path has no
     * in-pipeline join executor ({@code EnrichmentEngine} is post-commit, companion-file-driven), so
     * arming would be dead config. Same posture as {@code route}/{@code summarize}.
     */
    public Join join() { return join; }
    /**
     * Authored map projection ({@code processing.map}), or {@code null} when absent. ⚠ Unlike its
     * neighbours this one <b>executes</b> — {@code PipelineLift} puts it on the map node and
     * {@code RowShaper} reads it — so {@link #prepare()} deliberately does not refuse it on an active
     * pipeline. See {@link MapConfig}.
     */
    public MapConfig mapConfig() { return mapConfig; }
    /** The schema/grammar/segment files this config referenced at parse time (for change-watching). */
    public List<Path>     referencedFiles() { return referencedFiles; }

    // ── constructor — package-private; populated by PipelineConfigParser, use load() ──

    PipelineConfig(Builder b) {
        this.identity = new Identity(b.name, b.pipelineName, b.runTimestamp);
        this.dirs = new Dirs(b.pollDir, b.databaseDir, b.backupDir, b.tempDir, b.errorsDir,
                b.quarantineDir, b.markersDir, b.logDir, b.statusFilePath,
                b.batchesFilePath, b.lineageFilePath, b.manifestsDir, b.commitLogPath,
                b.unpackFilePath);
        this.processing = new Processing(b.threads, b.duckdbThreads, b.filePattern,
                b.batchMaxFiles, b.batchMaxBytes, b.batchOrder, b.duplicateCheckEnabled,
                b.markerExtension, b.retentionDays, b.largeFileBytes, b.flushRecords,
                b.priority);
        this.csv = new CsvSettings(b.delimiter, b.quote, b.escape, b.comment,
                b.skipHeaderLines, b.skipJunkLines,
                b.skipTailLines, b.skipTailCols, b.hasHeader, b.csvEngine,
                Collections.unmodifiableList(b.dateFormats),
                Collections.unmodifiableList(b.tsFormats),
                b.encoding, b.inputCompression, b.strictMode,
                Collections.unmodifiableList(b.nullStrings),
                Collections.unmodifiableList(b.includePrefixes),
                Collections.unmodifiableList(b.includeRegex),
                Collections.unmodifiableList(b.excludePrefixes),
                Collections.unmodifiableList(b.excludeRegex),
                b.filterTargetColumn, b.rowWhere,
                b.ignoreErrors, b.nullPadding,
                new CsvSettings.Rejects(b.storeRejects, b.rejectsTable, b.rejectsScan, b.rejectsLimit),
                b.sourceTimezone);
        this.output = new Output(b.outputFormat, b.compression, b.duckLakeCfg, b.filenameColumn);
        this.sinks = resolveSinks(b.sinks, this.output, b.databaseDir);
        this.steps = resolveSteps(b.steps, b.rowWhere, b.join, b.dedup, b.summarize, b.route);
        this.explicitSteps = b.steps != null && !b.steps.isEmpty();
        this.schemas = new Schemas(b.schemaSelector, b.singleSchema, b.segmentSchemas,
                b.ingesterClass,
                b.ingesterConfig != null
                        ? Collections.unmodifiableMap(b.ingesterConfig)
                        : Collections.emptyMap());
        this.duckdb   = new DuckDbSettings(b.duckMemoryLimit, b.duckTempDirectory, b.duckMaxTempSize);
        this.chunking = new Chunking(b.chunkMaxFileBytes, b.chunkTargetBytes);
        this.intake = b.intake;
        this.unpack = b.unpack;
        this.fixedWidth = b.fixedWidth;
        this.json = b.json;
        this.xlsx = b.xlsx;
        this.parquet = b.parquet;
        this.textRegex = b.textRegex;
        this.collector = b.collector;
        this.statusDirToPrepare = b.statusDirToPrepare;
        this.active = b.active;
        this.template = b.template;
        this.description = b.description;
        this.produces = b.produces;
        this.reference = b.reference;
        this.stream = b.stream;
        this.outputStore = b.outputStore;
        this.trigger = b.trigger;
        this.dedup = b.dedup;
        this.route = b.route;
        this.summarize = b.summarize;
        this.join = b.join;
        this.mapConfig = b.mapConfig;
        this.disabledSteps = List.copyOf(b.disabledSteps);
        this.referencedFiles = List.copyOf(b.referencedFiles);
    }

    /**
     * Copy constructor used by {@link #forNewRun()} — clones every parsed group verbatim but stamps a
     * fresh {@code runTimestamp} and recomputes the run-timestamped status/batch/lineage/manifest paths
     * (the persistent commit log is left as-is). Performs <b>no disk I/O</b>: schemas, grammar and dirs
     * are reused from {@code src}, so re-running a cached config each cycle costs only a few string ops.
     */
    private PipelineConfig(PipelineConfig src, String runTimestamp) {
        this(src,
             new Identity(src.identity.name(), src.identity.pipelineName(), runTimestamp),
             runTimestampedDirs(src, runTimestamp),
             src.sinks);
    }

    /**
     * The run-timestamped status/batch/lineage/manifest paths for {@link #forNewRun()}. Returns the
     * source dirs unchanged for a literal {@code status_file} (or when status is disabled) — nothing
     * is run-timestamped in that case. The commit log is persistent and never re-stamped.
     */
    private static Dirs runTimestampedDirs(PipelineConfig src, String runTimestamp) {
        Dirs d = src.dirs;
        if (src.statusDirToPrepare == null || src.statusDirToPrepare.isBlank()) return d;
        String pn = src.identity.pipelineName();
        String statusFile = Paths.get(src.statusDirToPrepare,
                pn + "_status_" + runTimestamp + ".csv").toString();
        Path parent = Paths.get(statusFile).toAbsolutePath().getParent();
        return new Dirs(d.poll(), d.database(), d.backup(), d.temp(), d.errors(),
                d.quarantine(), d.markers(), d.logDir(),
                statusFile,
                parent.resolve(pn + "_batches_" + runTimestamp + ".csv").toString(),
                parent.resolve(pn + "_lineage_" + runTimestamp + ".csv").toString(),
                parent.resolve("manifests").toString(),
                d.commitLogPath(),    // persistent — never run-timestamped
                // The unpack ledger is a RUN fact (one row per archive per run), so it is
                // run-timestamped like its three siblings — see UnpackLedger's class comment.
                parent.resolve(pn + "_unpack_" + runTimestamp + ".csv").toString());
    }

    /**
     * Clone {@code src} verbatim except for its identity, dirs and sinks — the shared body behind
     * {@link #forNewRun()} and {@link #forScratchRun(Path)}. Performs <b>no disk I/O</b>: schemas,
     * grammar and every parsed group are reused by reference.
     */
    private PipelineConfig(PipelineConfig src, Identity identity, Dirs dirs, List<Sink> sinks) {
        this.identity = identity;
        this.dirs = dirs;
        this.sinks = sinks;
        this.processing = src.processing;
        this.csv = src.csv;
        this.output = src.output;
        this.steps = src.steps;   // the projection is order-sensitive, never re-derive it here
        this.explicitSteps = src.explicitSteps;
        this.schemas = src.schemas;
        this.duckdb = src.duckdb;
        this.chunking = src.chunking;
        this.intake = src.intake;
        this.unpack = src.unpack;
        this.fixedWidth = src.fixedWidth;
        this.json = src.json;
        this.xlsx = src.xlsx;
        this.parquet = src.parquet;
        this.textRegex = src.textRegex;
        this.collector = src.collector;
        this.statusDirToPrepare = src.statusDirToPrepare;
        this.active = src.active;
        this.template = src.template;
        this.description = src.description;
        this.produces = src.produces;
        this.reference = src.reference;
        this.stream = src.stream;
        this.outputStore = src.outputStore;
        this.trigger = src.trigger;
        this.dedup = src.dedup;
        this.route = src.route;
        this.summarize = src.summarize;
        this.join = src.join;
        this.mapConfig = src.mapConfig;
        this.disabledSteps = src.disabledSteps;
        this.referencedFiles = src.referencedFiles;
    }

    /**
     * Return a copy of this config stamped with a fresh run timestamp (and the run-timestamped status
     * paths recomputed). Lets the orchestrator re-run a <em>cached</em> config every poll cycle — giving
     * each cycle its own status/batch/lineage CSVs — without re-parsing the file or re-reading schemas.
     * The status directory already exists (created by the original {@link #load}/{@link #prepare}), so
     * no directory creation is needed.
     */
    public PipelineConfig forNewRun() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return new PipelineConfig(this, ts);
    }

    /**
     * Return a copy of this config with <b>every filesystem destination re-rooted under
     * {@code scratchRoot}</b> — the containment half of a bounded test run over real inbox files
     * (Build→Test→Run Step 5a). Parsing logic, schemas, grammar and transforms are reused verbatim,
     * so a test run exercises the real ingest code rather than a parallel imitation of it.
     *
     * <p>The ingest half touches exactly five dirs — {@code poll}, {@code database}, {@code errors},
     * {@code quarantine}, {@code temp} — plus each {@link Sink#database()} on a multi-destination
     * fan-out. All are re-rooted here. ⚠ Re-rooting {@code poll} is <b>not</b> cosmetic: a test run
     * must be handed <em>copies</em> staged under {@code scratchRoot/poll}, because
     * {@link QuarantineManager#quarantine} does a {@code Files.move} of the <em>source</em> file for an
     * unreadable/mismatched/empty member. Point this at the real inbox and testing a malformed file
     * would delete it from the user's inbox.
     *
     * <p>The commit-half destinations ({@code backup}, {@code markers}, the status/batches/lineage CSVs,
     * manifests, the commit log) are set to {@code null} — <b>defence in depth</b>. A test run reaches
     * them only by calling {@code ConsignmentIngestor.commit}/{@code writeAudit}, which it must never do; if
     * some future caller does anyway, {@code null} disables the writes rather than letting them land in
     * production state. {@code backup == null} in particular is what makes {@code ConsignmentIngestor}'s
     * source-file backup a no-op.
     *
     * <p>⚠ This does <b>not</b> contain the destinations that are resolved from JVM system properties
     * or per-space registries rather than from a config — the acquisition ledger, the consignment output
     * registry, file stages, Signals and provenance. Those are avoided <em>by not calling</em> the three
     * statements that reach them; see the plan's Step 5 section. Config redirection alone is not enough.
     */
    public PipelineConfig forScratchRun(Path scratchRoot) {
        Path root = scratchRoot.toAbsolutePath().normalize();
        Dirs d = new Dirs(
                root.resolve("poll").toString(),
                root.resolve("database").toString(),
                null,                                    // backup: no source-file move
                root.resolve("temp").toString(),
                root.resolve("errors").toString(),
                root.resolve("quarantine").toString(),
                null,                                    // markers: no dedup marker writes
                root.resolve("logs").toString(),
                null, null, null, null, null, null);     // status/batches/lineage/manifests/commit-log/unpack
        List<Sink> scratchSinks = new ArrayList<>();
        for (int i = 0; i < sinks.size(); i++) {
            Sink s = sinks.get(i);
            // Distinct subdir per destination so a fan-out's outputs stay distinguishable in the preview.
            String dbDir = (sinks.size() == 1)
                    ? d.database() : Paths.get(d.database(), "sink" + i).toString();
            scratchSinks.add(new Sink(dbDir, s.format(), s.compression(), Map.of(),  // duckLake: never register
                    s.filenameColumn()));
        }
        return new PipelineConfig(this, identity, d, List.copyOf(scratchSinks));
    }

    /**
     * Resolve the output destinations: an explicit {@code sinks:} list when present, otherwise the
     * one-element single-{@code output:} shorthand ({@code dirs.database} + {@code output:}). The result is
     * never empty. A multi-destination config is <em>constructible and liftable</em> here (the graph editor
     * and {@link com.gamma.pipeline.PipelineLift} must be able to represent it); it is refused only when
     * loaded for <em>execution</em> — see {@link #prepare()}.
     */
    private static List<Sink> resolveSinks(List<Sink> declared, Output output, String database) {
        return (declared == null || declared.isEmpty())
                ? List.of(new Sink(database, output.format(), output.compression(), output.duckLake(),
                        output.filenameColumn()))
                : List.copyOf(declared);
    }

    /**
     * Resolve the transform chain: an explicit {@code steps:} list when present, otherwise the legacy
     * singular blocks <b>projected into the order {@link com.gamma.pipeline.PipelineLift} builds them in</b>.
     *
     * <p>⚠ <b>That projection order is the whole risk of this change, not the new reader.</b> Every existing
     * config reaches the chain through this method, so an order that disagrees with the lift by one position
     * silently reorders someone's pipeline the next time it is saved — which is precisely the loss this work
     * exists to stop, reintroduced by the fix for it. The order below is
     * {@code filter → join → dedup → summarize → route} because that is the sequence
     * {@code PipelineLift.branch} wires ({@code PipelineLift.java:172-238}), and
     * {@code PipelineConfigStepsTest} cross-checks the two rather than trusting this comment.
     *
     * <p>The two spellings are <b>mutually exclusive</b>, refused in the parser rather than merged: there
     * is no non-arbitrary position at which a legacy block would join an authored sequence, and picking one
     * silently is the failure mode this record was introduced to remove.
     */
    private static List<Step> resolveSteps(List<Step> declared, String rowWhere, Join join, Dedup dedup,
                                           Summarize summarize, Map<String, Object> route) {
        if (declared != null && !declared.isEmpty()) return List.copyOf(declared);

        List<Step> out = new ArrayList<>();
        if (rowWhere != null && !rowWhere.isBlank())
            out.add(new Step(Step.FILTER, Map.of("where", rowWhere)));
        if (join != null)
            out.add(new Step(Step.JOIN, cfg("reference", join.reference(), "on", join.on())));
        if (dedup != null)
            out.add(new Step(Step.DEDUP, cfg("keys", dedup.keys(), "order_by", dedup.orderBy(),
                    "scope", dedup.scope())));
        if (summarize != null)
            out.add(new Step(Step.SUMMARIZE,
                    cfg("group_by", summarize.groupBy(), "measures", summarize.measures())));
        if (route != null)
            out.add(new Step(Step.ROUTE, route));
        return List.copyOf(out);
    }

    /** A two-entry config map that drops null values — a step carries only the keys its block had. */
    private static Map<String, Object> cfg(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (v1 != null) m.put(k1, v1);
        if (v2 != null) m.put(k2, v2);
        return m;
    }

    private static Map<String, Object> cfg(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> m = cfg(k1, v1, k2, v2);
        if (v3 != null) m.put(k3, v3);
        return m;
    }

    // ── static factory ────────────────────────────────────────────────────────

    /**
     * Parse the pipeline {@code .toon} file at {@code configPath}, validate all
     * directories, load schema(s), and return an immutable {@code PipelineConfig}.
     *
     * <p>Equivalent to {@code fromMap(ToonHelper.load(configPath)).prepare()} — it decodes the file,
     * builds the (pure) config, then performs the one filesystem side-effect (creating the status
     * directory). Splitting those steps lets a draft be parsed/validated from memory with no I/O via
     * {@link #fromMap(Map)}.
     *
     * <p>A <em>relative</em> schema reference resolves against this config file's own directory first and the
     * working directory second, so a space tree can be relocated/renamed/imported without rewriting the
     * references inside it while every existing working-directory-relative config keeps loading unchanged.
     *
     * @param configPath filesystem path to the pipeline {@code .toon} file
     * @throws FileNotFoundException if the pipeline or any referenced schema file is missing
     * @throws IOException           on any I/O or parse failure
     * @throws IllegalArgumentException if any managed directory is nested inside the poll dir
     */
    public static PipelineConfig load(String configPath) throws IOException {
        // The config's own directory is the base for relative schema references (W1b): passing it needs no
        // caller change and no space-root threading, because a loaded config always HAS a directory.
        java.nio.file.Path here = java.nio.file.Paths.get(configPath).toAbsolutePath().getParent();
        PipelineConfig cfg = PipelineConfigParser.parse(ToonHelper.load(configPath), configPath, here);
        cfg.prepare();
        return cfg;
    }

    /**
     * Build an immutable {@code PipelineConfig} from an already-decoded config map — a <b>pure</b>
     * parse: it resolves and validates schemas and directories but performs no directory creation
     * (call {@link #prepare()} for that). This is the entry point for validating a draft that has
     * never been written to disk.
     *
     * @throws IOException if a referenced schema file is missing or unreadable
     * @throws IllegalArgumentException on any structural/validation problem
     */
    public static PipelineConfig fromMap(Map<String, Object> raw) throws IOException {
        return PipelineConfigParser.parse(raw, "<config>");
    }

    /**
     * Create the run's status directory — the single filesystem side-effect formerly performed
     * inline during {@code load}. A no-op when status is disabled or a literal {@code status_file}
     * was configured. Idempotent and safe to call more than once.
     *
     * <p>This is the <b>execution gate</b>: every runnable config reaches the engine through
     * {@link #load(String)} → {@code prepare()} ({@code ConfigRegistry}, {@code CollectorService},
     * {@code CollectorProcessor}), whereas the editor / lift / draft-validation paths use the pure
     * {@link #fromMap(Map)}. So refusing an unsupported config here catches a hand-edited {@code .toon} too,
     * while still letting the graph editor and {@link com.gamma.pipeline.PipelineLift} represent it.
     * Multi-destination {@code sinks:} ingest is wired; the one combination not yet supported is a
     * <b>versioned reference store</b> ({@code reference.load: upsert|scd2}) with more than one destination —
     * its single version history is ill-defined across destinations — so that is refused here.
     */
    public void prepare() throws IOException {
        if (sinks.size() > 1 && produces == Produces.REFERENCE && reference.load().versionedStore()) {
            throw new IllegalStateException(
                    "a versioned reference store (reference.load upsert/scd2) writes a single version history; "
                            + "combining it with multiple sinks: destinations is not supported "
                            + "(see docs/superpower/sinks-config-format-plan.md)");
        }
        // route: ARMS (branch-aware-executor arming plan S3, 2026-08-26): ConsignmentGraphRunner is wired
        // at the writeAndTrace choke point, so an active route: pipeline executes its branch tree.
        // Arming stays FAIL-CLOSED on every shape that would drop rows silently — the exact
        // silent-discard class this gate existed to prevent; each refusal names its fix.
        if (active && route != null) {
            // The rules themselves live in RouteArming, because the SAVE path needs the same list
            // while the operator is still authoring (ConfigRoutes.routeArmingFindings) and two
            // copies of a rule set drift. Registration is all-or-nothing, so the first refusal
            // throws; the save path reports the whole list instead.
            java.util.List<String> sinkDbs = new java.util.ArrayList<>();
            for (Sink d : sinks) sinkDbs.add(d.database());
            boolean multiSchema = schemas.selector() != null
                    || (schemas.segments() != null && !schemas.segments().isEmpty());
            List<String> refusals = RouteArming.refusals(route, sinkDbs, multiSchema);
            if (!refusals.isEmpty()) throw new IllegalStateException(refusals.get(0));
        }
        // processing.disabled_steps (Phase 4 S4 / D-13): only an armed route: pipeline's branch
        // sinks may be disabled — they PARK at rest (S4b); everything else refuses by name. One rule
        // set, shared with the save path, in StepDisableArming. Scratch paths keep the bypass.
        if (active) {
            java.util.List<String> allSinkDbs = new java.util.ArrayList<>();
            for (Sink d : sinks) allSinkDbs.add(d.database());
            List<String> stepRefusals = StepDisableArming.refusals(disabledSteps,
                    StepDisableArming.parkableSinkIds(route, allSinkDbs),
                    dirs.backup() != null && !dirs.backup().isBlank());
            if (!stepRefusals.isEmpty()) throw new IllegalStateException(stepRefusals.get(0));
        }
        // The three Stage-2 blocks below (summarize / dedup / join) arm ONLY when output_store: is
        // authored (A5-at-rest, 2026-08-11): the file itself then declares that its chain executes as
        // a flow job over the landed store (PipelineLift.stageTwo via a pipeline_config: job), so the
        // linear path running the pure EL is the intended split, not a silent skip. Without
        // output_store: the keys have no execution route and arming stays refused, exactly as before.
        if (active && summarize != null && outputStore == null) {
            throw new IllegalStateException(
                    "processing.summarize does not execute on the linear ingest path — author a top-level "
                            + "output_store: and run the chain at rest (pipeline_config: pipeline job), keep "
                            + "the pipeline inactive (active: false), or remove the summarize block");
        }
        // processing.dedup joins them 2026-08-11 (operator decision): record-grain dedup is a TRANSFORM
        // concern, so in ELT terms it belongs in the T and not the EL. It DID execute on this path — a
        // ROW_NUMBER QUALIFY in ConsignmentIngestStrategy, the one cross-record operation in the multiplexer —
        // and was removed so Stage-1 stays per-record work plus routing.
        // ⚠ This refusal is the whole point of that removal. Deleting the executor and leaving the key
        // parsing would mean a pipeline that arms, runs, writes — and silently keeps every duplicate it
        // was configured to fold. That is strictly worse than never having had the feature, and it is the
        // same silent-discard shape the multiplicity work exists to remove.
        if (active && dedup != null && outputStore == null) {
            throw new IllegalStateException(
                    "processing.dedup is a Stage-2 (transform) concern and no longer executes on the "
                            + "linear ingest path — author a top-level output_store: and run it at rest "
                            + "(pipeline_config: pipeline job), keep the pipeline inactive (active: false), "
                            + "or remove the dedup block");
        }
        // processing.join (Phase 3 S2) too: the executor exists (RowShaper.join, 2026-08-11), but the
        // linear ingest path this prepare() arms has no ReferenceResolver to feed it — the at-rest
        // route carries one (A5 slice 5), so the same output_store: condition applies.
        if (active && join != null && outputStore == null) {
            throw new IllegalStateException(
                    "processing.join does not execute on the linear ingest path — author a top-level "
                            + "output_store: and run it at rest (pipeline_config: pipeline job), keep the "
                            + "pipeline inactive (active: false), or remove the join block");
        }
        // ⚠ An explicit steps: chain arms NOTHING today, and the three guards above cannot catch it.
        // They test the TYPED fields (route/summarize/join), which a steps: file never populates — the
        // parser refuses the two spellings together, so `route`, `summarize` and `join` are all null no
        // matter what the chain says. Without this guard a steps: pipeline carrying a summarize would
        // sail past every check and run on the linear path, which reads dedup()/csv.rowWhere() and would
        // silently apply neither. That is exactly the failure the multiplicity plan exists to remove,
        // relocated one layer down: the config saves, loads, arms — and runs the wrong pipeline quietly.
        // Same fail-safe posture as route:/summarize/join above; lifted in plan slice A5, which routes a
        // steps: pipeline to the graph executor that can actually walk it.
        if (active && explicitSteps) {
            if (outputStore == null) {
                throw new IllegalStateException(
                        "steps: does not execute on the linear ingest path — author a top-level "
                                + "output_store: and run the chain at rest (pipeline_config: pipeline job), "
                                + "or keep the pipeline inactive (active: false)");
            }
            // the at-rest route (PipelineLift.stageTwo) refuses a route step — one output_store cannot
            // name N branches — so arming here would only defer that refusal to the job's first run.
            // ⚠ The message names route's HOME (the top-level route: block, driven by the branch-aware
            // ingest executor) rather than listing the two lanes that cannot run it: an author who is told
            // only what is refused has to guess whether route works at all, and it does.
            if (steps.stream().anyMatch(s -> Step.ROUTE.equals(s.kind()))) {
                throw new IllegalStateException(
                        "steps: chains a 'route' step, but route runs on the INGEST lane — author it as "
                                + "the top-level route: block, where the branch-aware executor gives each "
                                + "branch key its own sink. A steps: chain runs at rest against one "
                                + "output_store, which cannot name N branches");
            }
        }
        if (statusDirToPrepare != null && !statusDirToPrepare.isBlank()) {
            Files.createDirectories(Paths.get(statusDirToPrepare));
        }
    }

    // ── builder — package-private mutable accumulator; populated by PipelineConfigParser ──

    static final class Builder {
        String name          = "";
        String pipelineName  = "";
        String runTimestamp  = "";
        boolean active       = false;   // opt-in: a pipeline runs only with `active: true`
        boolean template     = false;   // `template: true` ⇒ never registered, so never runnable
        String  description  = "";      // free-text label only; no engine behaviour keys off it
        Produces produces    = Produces.STREAM;   // catalog product; `produces: reference` ⇒ Reference Dataset
        Reference reference  = Reference.DEFAULT;  // `reference:` block; full-replace/no-key when absent
        String   stream;                           // logical Catalog Stream; parser defaults it to pipelineName
        String   outputStore;                      // at-rest Stage-2 output store (output_store:); null = absent
        Map<String, Object> trigger = null;   // optional entry-node trigger: block (T13); null ⇒ default poll
        Dedup dedup = null;                   // record-grain dedup (processing.dedup); null ⇒ none
        Map<String, Object> route = null;     // route: block verbatim; null ⇒ linear pipeline
        Summarize summarize = null;           // group-by rollup (processing.summarize); null ⇒ none
        Join join = null;                     // reference join (processing.join); null ⇒ none
        MapConfig mapConfig = null;           // authored map projection (processing.map); null ⇒ none
        List<String> disabledSteps = List.of();   // processing.disabled_steps (S4/D-13); empty ⇒ all enabled
        final List<Path> referencedFiles = new ArrayList<>();   // schema/grammar/segment files read at parse
        String pollDir       = "";
        String databaseDir   = "";
        String backupDir;
        String tempDir;
        String errorsDir     = "";
        String quarantineDir = "";
        String markersDir;
        String logDir;
        String statusFilePath;
        String statusDirToPrepare;
        int    threads       = 4;
        int    duckdbThreads = 0;
        String filePattern   = "glob:**/*.{csv,csv.gz}";
        int    batchMaxFiles   = 1;
        long   batchMaxBytes   = Long.MAX_VALUE;
        String batchOrder      = "mtime";         // ConsignmentPlanner.Order — arrival order (operator 2026-08-12); name = opt-in
        int    priority        = 1;               // ConcurrencyBroker share weight 1..3 (Part B); 1 = baseline
        long   largeFileBytes  = 268_435_456L;   // 256 MB: streaming plugin generation-mode threshold
        long   flushRecords    = 5_000_000L;      // streaming plugin generation row budget
        String duckMemoryLimit;
        String duckTempDirectory;
        String duckMaxTempSize;
        // 8 GiB: on by default (BACKLOG D12). Deliberately far above any routine input so normal
        // workloads never change shape — the threshold exists for pathological single files only.
        // Set processing.chunking.max_file_bytes: 0 to disable.
        long   chunkMaxFileBytes = 8_589_934_592L;
        long   chunkTargetBytes  = 0;
        Intake intake            = null;   // absent block = inherit the -Dingest.* globals whole
        Unpack unpack            = null;   // absent block = Unpack.defaults() (stage on, shipped caps)
        String batchesFilePath;
        String lineageFilePath;
        String manifestsDir;
        String commitLogPath;
        String unpackFilePath;
        boolean duplicateCheckEnabled = false;
        String  markerExtension       = ".processed";
        int     retentionDays         = 90;
        String       delimiter       = ",";
        String       quote;             // single char; null ⇒ engine default (")
        String       escape;            // single char; null ⇒ engine default (")
        String       comment;           // single char; null ⇒ no comment handling
        int          skipHeaderLines = 0;
        int          skipJunkLines   = 0;
        int          skipTailLines   = 0;
        int          skipTailCols    = 0;
        boolean      hasHeader       = true;
        String       csvEngine       = "auto";
        List<String> dateFormats     = new ArrayList<>();
        List<String> tsFormats       = new ArrayList<>();
        String       sourceTimezone;
        String       encoding;
        String       inputCompression;
        Boolean      strictMode;
        List<String> nullStrings     = new ArrayList<>();
        List<String> includePrefixes = new ArrayList<>();
        List<String> includeRegex    = new ArrayList<>();
        List<String> excludePrefixes = new ArrayList<>();
        List<String> excludeRegex    = new ArrayList<>();
        int          filterTargetColumn = 0;
        String       rowWhere;          // post-parse SQL predicate (csv_settings.where); null ⇒ no filter
        // Error handling. Every one is null ⇒ "leave the engine's own default", so a config that
        // declares none emits the exact same read_csv SQL as before these knobs existed.
        Boolean      ignoreErrors;
        Boolean      nullPadding;
        Boolean      storeRejects;
        String       rejectsTable;
        String       rejectsScan;
        Integer      rejectsLimit;
        String       filenameColumn;    // output.filename_column (B4); null ⇒ no lineage column in rows
        FixedWidth   fixedWidth;          // null ⇒ delimited frontend (the default)
        Json         json;                // null unless frontend: json
        Xlsx         xlsx;                // null unless frontend: xlsx
        Parquet      parquet;             // null unless frontend: parquet
        TextRegex    textRegex;           // null unless frontend: text_regex
        Collector    collector;          // the parsed collector: block (parser always sets it)
        String outputFormat  = "CSV";
        String compression;
        Map<String, Object> duckLakeCfg;
        /** Explicit {@code sinks:} destinations; empty ⇒ synthesise the single-{@code output:} shorthand. */
        List<Sink> sinks = new ArrayList<>();
        List<Step> steps = new ArrayList<>();   // explicit `steps:` only; empty ⇒ project from the legacy blocks
        SchemaSelector      schemaSelector;
        Map<String, Object> singleSchema;
        String ingesterClass;
        LinkedHashMap<String, Map<String, Object>> segmentSchemas;
        Map<String, Object> ingesterConfig;
    }
}
