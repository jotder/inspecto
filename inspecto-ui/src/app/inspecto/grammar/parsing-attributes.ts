import { AttributeSpec } from 'app/inspecto/component-model';
import { timeZoneOptions } from 'app/inspecto/schema/time-zones';

/**
 * The Parsing stage's frontend catalog + per-frontend schema-form specs, flat-keyed (`__` path
 * separator, see `onboarding-config-utils`) over the Stage-1 `parsing:` TOON block. Deliberately
 * only the FOUR **built-in** frontends. Plugin parsers are NOT specced here: their options form is the
 * SERVED `grammarSchema` from `GET /parsers` (`fieldSpecsToAttributes`), and since W2/U-E (2026-07-31) the
 * Parsing stage authors them — the old "TOON-managed, read-only" guard is gone (it had become a lockout;
 * see `parsing-pane.component`).
 *
 *
 * Key shape mirrors `PipelineConfigParser.mergeParsing`: shared csv-settings keys live under
 * `delimited.*` (that block IS csv_settings under its canonical name — it applies to every
 * line-based frontend, e.g. `delimited.has_header` also drives the fixed-width header skip);
 * per-frontend sub-blocks are `fixedwidth.*`, `json.*`, `text_regex.*`.
 */
export type ParsingFrontend = 'delimited' | 'fixedwidth' | 'json' | 'text_regex' | 'xlsx';

/**
 * The Grammar editor's tab shell for the delimited frontend (delimited-grammar-properties plan §4.1).
 * A spec set whose specs name ≥ 2 distinct `tab` ids renders as a `mat-tab-group` — one schema-form
 * per tab; sets without tabs (every other frontend + served plugins) render flat, byte-identical to
 * before. Order here is the tab order.
 */
/**
 * The six `read_csv` error-handling knobs — ONE declaration, three consumers.
 *
 * <p>They live under `delimited.*` because that block IS `csv_settings` under its canonical name, and
 * `PipelineConfigParser.mergeParsing` FLATTENS it into the shared settings every line-based frontend
 * reads. ⛔ Do NOT mirror these as `fixedwidth__*` / `text_regex__*`: those roots are copied through as
 * NESTED sub-blocks, so such a key would be written, validated, saved — and never read. Dead config.
 *
 * <p>⚠ `null_padding`'s ENGINE default inverts by frontend (`DuckDbCsvIngester.errorOptions`'s
 * `nullPaddingDefault`): `false` on the delimited path, `true` on every line-reader path
 * (fixed-width / json-lines / text-regex), where a short final line is normal and padding it is the
 * point. Hence the parameter — the help text must state the default the operator will actually get.
 */
function sharedRobustnessAttributes(padsShortRowsByDefault: boolean): AttributeSpec[] {
    return [
        {
            key: 'delimited__ignore_errors',
            label: 'Rows that cannot be read go to the review bin',
            type: 'boolean',
            tier: 'optional',
            default: true,
            help: 'A row that cannot be read is set aside in the review bin and the file carries on. Off stops the whole batch at the first bad row.',
            section: 'robustness',
        },
        {
            key: 'delimited__null_padding',
            label: 'Fill missing columns with empty',
            type: 'boolean',
            tier: 'optional',
            default: padsShortRowsByDefault,
            help: padsShortRowsByDefault
                ? 'Keep a row that has fewer columns than expected, leaving the rest empty, instead of sending it to the review bin. ON by default here — a short final line is normal for a line reader.'
                : 'Keep a row that has fewer columns than expected, leaving the rest empty, instead of sending it to the review bin. Off for delimited by default.',
            section: 'robustness',
        },
        {
            key: 'delimited__store_rejects',
            label: 'Keep rejected rows for review',
            type: 'boolean',
            tier: 'optional',
            default: true,
            help: 'Rejected rows are written to errors/<base>_errors.csv so they can be reviewed. Off keeps nothing — which matters because rejected rows carry raw source data.',
            section: 'robustness',
        },
        {
            key: 'delimited__rejects_table',
            label: 'Review bin table',
            type: 'string',
            tier: 'advanced',
            default: 'reject_errors',
            help: 'Table holding each rejected row. Letters, digits and underscore only.',
            section: 'robustness',
        },
        {
            key: 'delimited__rejects_scan',
            label: 'Review bin scan table',
            type: 'string',
            tier: 'advanced',
            default: 'reject_scans',
            help: 'Table holding one summary per scanned file. Letters, digits and underscore only.',
            section: 'robustness',
        },
        {
            key: 'delimited__rejects_limit',
            label: 'Stop keeping bad rows after',
            type: 'number',
            tier: 'advanced',
            min: 0,
            help: 'Most rejected rows kept per file. Blank or 0 = keep them all.',
            section: 'robustness',
        },
    ];
}

export const GRAMMAR_TABS: { id: string; label: string }[] = [
    { id: 'dialect', label: 'How the file is written' },
    { id: 'types', label: 'How values are understood' },
    { id: 'robustness', label: 'When a row looks wrong' },
];

/**
 * Built once: the zone vocabulary is ~418 entries and every frontend's Types tab offers the same one.
 * The blank entry names the engine's own no-key behaviour, so picking it writes nothing.
 */
const SOURCE_TIMEZONE_OPTIONS = timeZoneOptions('Wall clock, as written (default)');

/** Exactly one character — the dialect chars the engine validates fail-closed at load. */
const SINGLE_CHAR = '[\\s\\S]';

export const PARSING_FRONTENDS: { id: ParsingFrontend; label: string; hint: string }[] = [
    { id: 'delimited', label: 'Delimited', hint: 'CSV/TSV/Pipe — one record per line, split by a delimiter' },
    { id: 'fixedwidth', label: 'Fixed width', hint: 'Positional slices carved from each line' },
    { id: 'json', label: 'JSON', hint: 'NDJSON (one object per line) or a JSON array document' },
    { id: 'text_regex', label: 'Text / regex', hint: 'Named capture groups over matching lines' },
    { id: 'xlsx', label: 'Excel', hint: 'An .xlsx workbook sheet, read natively' }, //(DuckDB read_xlsx)
];

/**
 * The tab shell per frontend (multiformat X4): same ids as {@link GRAMMAR_TABS} — the editor keys
 * slots and badges on the id — with the first tab's label speaking the format's own language.
 * Formats without a tabbed spec set never reach this (the editor renders them flat).
 */
export function grammarTabsFor(frontend: string): { id: string; label: string }[] {
    if (frontend === 'xlsx') return [{ id: 'dialect', label: 'Sheet & range' }, ...GRAMMAR_TABS.slice(1)];
    if (frontend === 'json') return [{ id: 'dialect', label: 'Format & records' }, ...GRAMMAR_TABS.slice(1)];
    if (frontend === 'fixedwidth') return [{ id: 'dialect', label: 'Record layout' }, ...GRAMMAR_TABS.slice(1)];
    return GRAMMAR_TABS;
}

export function parsingAttributesFor(frontend: ParsingFrontend): AttributeSpec[] {
    switch (frontend) {
        case 'delimited':
            // The full LIVE engine key set (delimited-grammar-properties plan §3), split across the
            // 3 GRAMMAR_TABS. Every key here is read by PipelineConfigParser — no dead knobs.
            return [
                // ── tab 1: Dialect / parsing ─────────────────────────────────────
                {
                    key: 'delimited__delimiter',
                    label: 'Column separator',
                    type: 'string',
                    tier: 'required',
                    required: false,
                    default: ',',
                    placeholder: ',',
                    section: 'dialect',
                },
                {
                    key: 'delimited__has_header',
                    label: 'First row is the header',
                    type: 'boolean',
                    tier: 'required',
                    required: false,
                    default: true,
                    section: 'dialect',
                },
                {
                    key: 'delimited__quote',
                    label: 'Text quote',
                    type: 'string',
                    tier: 'optional',
                    pattern: SINGLE_CHAR,
                    default: '"',
                    help: 'The single character wrapped around a value that contains the column separator.',
                    section: 'dialect',
                },
                {
                    key: 'delimited__escape',
                    label: 'Escape character',
                    type: 'string',
                    tier: 'optional',
                    pattern: SINGLE_CHAR,
                    help: 'The single character that marks a literal quote inside a quoted value (default: the quote itself, doubled).',
                    section: 'dialect',
                },
                {
                    key: 'delimited__comment',
                    label: 'Ignore lines starting with',
                    type: 'string',
                    tier: 'optional',
                    pattern: SINGLE_CHAR,
                    help: 'Lines starting with this single character are skipped, e.g. #. Empty = no line is ignored.',
                    section: 'dialect',
                },
                {
                    key: 'delimited__skip_header_lines',
                    label: 'Skip lines at the top',
                    type: 'number',
                    tier: 'optional',
                    min: 0,
                    default: 0,
                    help: 'Banner or preamble lines before the data (and the header).',
                    section: 'dialect',
                },
                {
                    key: 'delimited__skip_junk_lines',
                    label: 'Skip unreadable lines at the top (up to)',
                    type: 'number',
                    tier: 'optional',
                    min: -1,
                    default: 0,
                    help: 'How many unreadable lines to look past before the first row that parses; -1 = as many as it takes.',
                    section: 'dialect',
                },
                {
                    key: 'delimited__skip_tail_lines',
                    label: 'Skip lines at the bottom',
                    type: 'number',
                    tier: 'optional',
                    min: 0,
                    default: 0,
                    help: 'Footer lines dropped from the end of every file.',
                    section: 'dialect',
                },
                {
                    key: 'delimited__skip_tail_columns',
                    label: 'Drop extra columns on the right',
                    type: 'number',
                    tier: 'optional',
                    min: 0,
                    default: 0,
                    help: 'Columns removed from the right-hand end of every row (e.g. a trailing separator).',
                    section: 'dialect',
                },
                {
                    key: 'encoding',
                    label: 'Text encoding',
                    type: 'select',
                    tier: 'advanced',
                    default: 'utf-8',
                    options: [
                        { value: 'utf-8', label: 'UTF-8' },
                        { value: 'utf-16', label: 'UTF-16' },
                        { value: 'latin-1', label: 'Latin-1' },
                    ],
                    section: 'dialect',
                },
                // ── tab 2: Types & columns ───────────────────────────────────────
                {
                    key: 'delimited__date_formats',
                    label: 'Date formats to try',
                    type: 'list',
                    tier: 'optional',
                    help: 'Patterns a date may be written in, tried in order, e.g. %Y-%m-%d. Empty = any standard date form is accepted; listing patterns RESTRICTS parsing to exactly those.',
                    section: 'types',
                },
                {
                    key: 'delimited__timestamp_formats',
                    label: 'Timestamp formats to try',
                    type: 'list',
                    tier: 'optional',
                    help: 'Patterns a timestamp may be written in, tried in order, e.g. %Y-%m-%d %H:%M:%S. Empty = any standard timestamp form is accepted; listing patterns RESTRICTS parsing to exactly those.',
                    section: 'types',
                },
                {
                    // ⚠ No `default` — the standing rule: a spec default materializes into every
                    // value() and would mutate faithful copies of stored grammars. Blank is a NAMED
                    // choice ("wall clock, as written" IS the engine's behaviour with no key), and
                    // the editor drops a blank whose default is blank, so picking it writes nothing.
                    // ⚠ Parsing-LEVEL, not `delimited__`: the zone is a fact about the DATA, not
                    // about the delimited dialect, so it holds whatever the frontend is — the same
                    // shape as `encoding`/`compression`, the other two parsing-level scalars, and
                    // the same allow-list entry in PipelineConfigParser.mergeParsing.
                    key: 'source_timezone',
                    label: 'Source time zone',
                    type: 'select',
                    tier: 'optional',
                    options: SOURCE_TIMEZONE_OPTIONS,
                    help: 'The zone the timestamps in this data are written IN. Set it and values are stored as UTC, instead of being read in the server’s own zone. A column can override it.',
                    section: 'types',
                },
                {
                    key: 'delimited__null_strings',
                    label: 'Words that mean "no value"',
                    type: 'list',
                    tier: 'optional',
                    help: 'Text values read as "no value", e.g. NULL or N/A. Empty = only an empty cell means no value.',
                    section: 'types',
                },
                // ── tab 3: Robustness / error handling ───────────────────────────
                {
                    // ⚠ No `default`: a spec default materializes into every value() and would be
                    // WRITTEN into blocks the author never touched — mutating faithful copies of
                    // stored grammars (templates are copies, never bindings). Blank = engine default.
                    key: 'delimited__strict_mode',
                    label: 'Strict CSV rules (RFC-4180)',
                    type: 'boolean',
                    tier: 'optional',
                    help: 'Strict by default. Off tolerates stray quotes and uneven column counts in messy files.',
                    section: 'robustness',
                },
                {
                    // ⚠ Still NO `default` — the standing rule (a spec default materializes into
                    // every value() and would mutate faithful copies of stored grammars). Auto is
                    // shown as the selected choice by giving it the BLANK value: an unset engine key
                    // already means auto to the parser, and the editor drops a blank whose default is
                    // blank, so picking it writes nothing. Do not "fix" this into default: 'auto'.
                    key: 'delimited__engine',
                    label: 'Reader',
                    type: 'select',
                    tier: 'optional',
                    help: 'Automatic picks the fast reader for clean settings and the tolerant one otherwise.',
                    options: [
                        { value: '', label: 'Automatic' },
                        { value: 'duckdb', label: 'Fast reader (DuckDB)' },
                        { value: 'java', label: 'Tolerant reader (Java)' },
                    ],
                    section: 'robustness',
                },
                // Error handling (2026-08-23). Every one is tri-state: blank leaves the engine's own
                // default, which is what every existing config already gets — so adding these to a
                // stored grammar changes nothing until the author sets one.
                // The six shared read_csv error knobs (one declaration — see sharedRobustnessAttributes).
                ...sharedRobustnessAttributes(false),
                {
                    key: 'delimited__include_prefixes',
                    label: 'Keep only rows starting with',
                    type: 'list',
                    tier: 'advanced',
                    help: 'Keep only rows whose looked-at column starts with any of these.',
                    section: 'robustness',
                },
                {
                    key: 'delimited__include_regex',
                    label: 'Keep only rows matching',
                    type: 'list',
                    tier: 'advanced',
                    help: 'Keep only rows whose looked-at column matches any of these patterns.',
                    section: 'robustness',
                },
                {
                    key: 'delimited__exclude_prefixes',
                    label: 'Drop rows starting with',
                    type: 'list',
                    tier: 'advanced',
                    help: 'Drop rows whose looked-at column starts with any of these.',
                    section: 'robustness',
                },
                {
                    key: 'delimited__exclude_regex',
                    label: 'Drop rows matching',
                    type: 'list',
                    tier: 'advanced',
                    help: 'Drop rows whose looked-at column matches any of these patterns.',
                    section: 'robustness',
                },
                {
                    key: 'delimited__filter_target_column',
                    label: 'Column those row rules look at',
                    type: 'number',
                    tier: 'advanced',
                    min: 0,
                    default: 0,
                    help: 'The column (counted from 0) the keep/drop rules above read.',
                    section: 'robustness',
                },
                // Row filter (SQL) is NOT offered here (redesign D3): filtering is the separate
                // transform.filter Step. The lift/lower's csv.where shorthand still round-trips a
                // stored `where` untouched.
                // Input handling (ex "Files & metadata", dissolved by redesign D2/R5 — lives on Dialect).
                {
                    key: 'compression',
                    label: 'Compressed file',
                    type: 'select',
                    tier: 'optional',
                    default: 'auto',
                    options: [
                        { value: 'auto', label: 'Auto — detect by extension' },
                        { value: 'gzip', label: 'gzip' },
                        { value: 'zstd', label: 'zstd' },
                        { value: 'none', label: 'None' },
                    ],
                    help: 'Decompressed while reading. Archives (.zip, .tar, .Z) are not — unpack them into the inbox first (BACKLOG §4 tracks native support).',
                    section: 'dialect',
                },
            ];
        case 'fixedwidth':
            // Sectioned since multiformat F1: Record layout (+ the slice table the editor homes there),
            // Types & columns (transform-time patterns), Robustness.
            return [
                {
                    key: 'delimited__has_header',
                    label: 'First line is a header',
                    type: 'boolean',
                    tier: 'required',
                    required: false,
                    default: true,
                    help: 'Header/banner line to skip before the records.',
                    section: 'dialect',
                },
                {
                    key: 'delimited__date_formats',
                    label: 'Date formats to try',
                    type: 'list',
                    tier: 'optional',
                    help: 'Patterns a date may be written in, tried in order, e.g. %Y-%m-%d. Empty = any standard date form is accepted; listing patterns RESTRICTS parsing to exactly those.',
                    section: 'types',
                },
                {
                    key: 'delimited__timestamp_formats',
                    label: 'Timestamp formats to try',
                    type: 'list',
                    tier: 'optional',
                    help: 'Patterns a timestamp may be written in, tried in order, e.g. %Y-%m-%d %H:%M:%S. Empty = any standard timestamp form is accepted; listing patterns RESTRICTS parsing to exactly those.',
                    section: 'types',
                },
                {
                    // ⚠ No `default` — the standing rule: a spec default materializes into every
                    // value() and would mutate faithful copies of stored grammars. Blank is a NAMED
                    // choice ("wall clock, as written" IS the engine's behaviour with no key), and
                    // the editor drops a blank whose default is blank, so picking it writes nothing.
                    // ⚠ Parsing-LEVEL, not `delimited__`: the zone is a fact about the DATA, not
                    // about the delimited dialect, so it holds whatever the frontend is — the same
                    // shape as `encoding`/`compression`, the other two parsing-level scalars, and
                    // the same allow-list entry in PipelineConfigParser.mergeParsing.
                    key: 'source_timezone',
                    label: 'Source time zone',
                    type: 'select',
                    tier: 'optional',
                    options: SOURCE_TIMEZONE_OPTIONS,
                    help: 'The zone the timestamps in this data are written IN. Set it and values are stored as UTC, instead of being read in the server’s own zone. A column can override it.',
                    section: 'types',
                },
                {
                    key: 'fixedwidth__min_record_length',
                    label: 'Minimum record length',
                    type: 'number',
                    tier: 'optional',
                    min: 0,
                    help: 'Shorter lines (footers, blanks) are dropped. Blank = the widest field end.',
                    section: 'robustness',
                },
                // The shared read_csv error knobs apply here too: a fixed-width read IS a read_csv
                // over one VARCHAR 'line' column, so ignore_errors / store_rejects / the reject tables
                // all take effect. padsShortRowsByDefault=TRUE — the engine's own default on every
                // line-reader path (DuckDbCsvIngester.errorOptions), where a short final line is normal.
                ...sharedRobustnessAttributes(true),
                {
                    key: 'fixedwidth__trim',
                    label: 'Trim fields',
                    type: 'select',
                    tier: 'optional',
                    default: 'BOTH',
                    options: [
                        { value: 'BOTH', label: 'Both sides' },
                        { value: 'LEFT', label: 'Left' },
                        { value: 'RIGHT', label: 'Right' },
                        { value: 'NONE', label: 'None' },
                    ],
                    section: 'robustness',
                },
                {
                    key: 'encoding',
                    label: 'Encoding',
                    type: 'string',
                    tier: 'advanced',
                    placeholder: 'UTF-8',
                    section: 'dialect',
                },
                {
                    key: 'compression',
                    label: 'Input compression',
                    type: 'string',
                    tier: 'advanced',
                    placeholder: 'gzip',
                    section: 'dialect',
                },
            ];
        case 'json':
            return [
                {
                    key: 'json__format',
                    label: 'Document shape',
                    type: 'select',
                    tier: 'required',
                    required: false,
                    default: 'newline',
                    options: [
                        { value: 'newline', label: 'NDJSON — one object per line' },
                        { value: 'array', label: 'One JSON array of records' },
                        { value: 'auto', label: 'Auto-detect' },
                    ],
                    section: 'dialect',
                },
                {
                    key: 'json__records_path',
                    label: 'Records path',
                    type: 'string',
                    tier: 'optional',
                    default: '$',
                    placeholder: 'payload.records',
                    // Hidden for NDJSON on purpose: `parseJson` HARD-FAILS a nested path under
                    // `format: newline` (each line is already a record, so there is no enclosing
                    // document to walk). Offering the field there would author a config the parser
                    // rejects at load. `$` = the document's top level IS the array.
                    dependsOn: { key: 'json__format', notEquals: 'newline' },
                    help: 'Dotted path to the array holding the records — same notation as a field selector. Blank or "$" = the whole document.',
                    section: 'dialect',
                },
                {
                    key: 'delimited__skip_header_lines',
                    label: 'Skip leading lines',
                    type: 'number',
                    tier: 'advanced',
                    min: 0,
                    section: 'dialect',
                },
                {
                    key: 'delimited__date_formats',
                    label: 'Date formats to try',
                    type: 'list',
                    tier: 'optional',
                    help: 'Patterns a date may be written in, tried in order, e.g. %Y-%m-%d. Empty = any standard date form is accepted; listing patterns RESTRICTS parsing to exactly those.',
                    section: 'types',
                },
                {
                    key: 'delimited__timestamp_formats',
                    label: 'Timestamp formats to try',
                    type: 'list',
                    tier: 'optional',
                    help: 'Patterns a timestamp may be written in, tried in order, e.g. %Y-%m-%d %H:%M:%S. Empty = any standard timestamp form is accepted; listing patterns RESTRICTS parsing to exactly those.',
                    section: 'types',
                },
                {
                    // ⚠ No `default` — the standing rule: a spec default materializes into every
                    // value() and would mutate faithful copies of stored grammars. Blank is a NAMED
                    // choice ("wall clock, as written" IS the engine's behaviour with no key), and
                    // the editor drops a blank whose default is blank, so picking it writes nothing.
                    // ⚠ Parsing-LEVEL, not `delimited__`: the zone is a fact about the DATA, not
                    // about the delimited dialect, so it holds whatever the frontend is — the same
                    // shape as `encoding`/`compression`, the other two parsing-level scalars, and
                    // the same allow-list entry in PipelineConfigParser.mergeParsing.
                    key: 'source_timezone',
                    label: 'Source time zone',
                    type: 'select',
                    tier: 'optional',
                    options: SOURCE_TIMEZONE_OPTIONS,
                    help: 'The zone the timestamps in this data are written IN. Set it and values are stored as UTC, instead of being read in the server’s own zone. A column can override it.',
                    section: 'types',
                },
                // ── J1 reader knobs — read_json (array/auto) only; the load refuses them on NDJSON,
                // so the form hides them there rather than authoring a config the parser rejects.
                {
                    key: 'json__ignore_errors',
                    label: 'Keep malformed records as NULL rows',
                    type: 'boolean',
                    tier: 'optional',
                    // ⚠ Probed: format: array is a genuine JSON-array document, and DuckDB HARD-REJECTS
                    // ignore_errors for any shape but newline_delimited — the load refuses that
                    // combination outright (PipelineConfigParser). Auto-only, not "not newline": a
                    // config authored here can never hit that refusal.
                    dependsOn: { key: 'json__format', equals: 'auto' },
                    help: 'auto only: takes effect when the file’s content is itself line-delimited (DuckDB’s own sniff) — a malformed record then lands as an all-NULL row rather than failing the file. Not available under format: array, which DuckDB always rejects this option for.',
                    section: 'robustness',
                },
                {
                    key: 'json__maximum_object_size',
                    label: 'Maximum object size (bytes)',
                    type: 'number',
                    tier: 'advanced',
                    min: 0,
                    dependsOn: { key: 'json__format', notEquals: 'newline' },
                    help: 'Bound on a single document/record the reader will buffer. Blank = the engine default.',
                    section: 'robustness',
                },
                {
                    key: 'compression',
                    label: 'Input compression',
                    type: 'string',
                    tier: 'advanced',
                    placeholder: 'gzip',
                    section: 'dialect',
                },
            ];
        case 'xlsx':
            // The PROBED read_xlsx option set (multiformat plan §0) — every key is a named parameter
            // the extension reads; all_varchar is STAMPED by ingest, never an option, and read_xlsx
            // has NO columns/compression/encoding params (a workbook is its own container). The
            // date/timestamp formats are transform-time keys shared with every frontend.
            return [
                {
                    key: 'xlsx__sheet',
                    label: 'Sheet',
                    type: 'string',
                    tier: 'required',
                    required: false,
                    placeholder: 'first sheet',
                    help: "Sheet NAME to read. Blank = the workbook's first sheet.",
                    section: 'dialect',
                },
                {
                    key: 'xlsx__range',
                    label: 'Cell range',
                    type: 'string',
                    tier: 'optional',
                    pattern: '[A-Za-z]{1,3}[0-9]{1,7}(:[A-Za-z]{1,3}[0-9]{1,7})?',
                    placeholder: 'A1:F100',
                    help: 'A1-style anchor or span. Blank = the whole sheet.',
                    section: 'dialect',
                },
                {
                    key: 'xlsx__header',
                    label: 'First row is a header',
                    type: 'boolean',
                    tier: 'required',
                    required: false,
                    default: true,
                    help: 'Header cells name the columns; without one they are A, B, C…',
                    section: 'dialect',
                },
                {
                    key: 'xlsx__normalize_names',
                    label: 'Normalize header names',
                    type: 'boolean',
                    tier: 'optional',
                    help: 'Lower-snake identifiers from the header cells.',
                    section: 'dialect',
                },
                // ── tab 2: Types & columns (transform-time typing, shared keys) ──
                {
                    key: 'delimited__date_formats',
                    label: 'Date formats to try',
                    type: 'list',
                    tier: 'optional',
                    help: 'Patterns a date may be written in, tried in order, e.g. %Y-%m-%d. Empty = any standard date form is accepted; listing patterns RESTRICTS parsing to exactly those.',
                    section: 'types',
                },
                {
                    key: 'delimited__timestamp_formats',
                    label: 'Timestamp formats to try',
                    type: 'list',
                    tier: 'optional',
                    help: 'Patterns a timestamp may be written in, tried in order, e.g. %Y-%m-%d %H:%M:%S. Empty = any standard timestamp form is accepted; listing patterns RESTRICTS parsing to exactly those.',
                    section: 'types',
                },
                {
                    // ⚠ No `default` — the standing rule: a spec default materializes into every
                    // value() and would mutate faithful copies of stored grammars. Blank is a NAMED
                    // choice ("wall clock, as written" IS the engine's behaviour with no key), and
                    // the editor drops a blank whose default is blank, so picking it writes nothing.
                    // ⚠ Parsing-LEVEL, not `delimited__`: the zone is a fact about the DATA, not
                    // about the delimited dialect, so it holds whatever the frontend is — the same
                    // shape as `encoding`/`compression`, the other two parsing-level scalars, and
                    // the same allow-list entry in PipelineConfigParser.mergeParsing.
                    key: 'source_timezone',
                    label: 'Source time zone',
                    type: 'select',
                    tier: 'optional',
                    options: SOURCE_TIMEZONE_OPTIONS,
                    help: 'The zone the timestamps in this data are written IN. Set it and values are stored as UTC, instead of being read in the server’s own zone. A column can override it.',
                    section: 'types',
                },
                // ── tab 3: Robustness / error handling ───────────────────────────
                {
                    // ⚠ No default (the delimited rule): a spec default materializes into every
                    // value() and would mutate faithful copies of stored grammars.
                    key: 'xlsx__stop_at_empty',
                    label: 'Stop at first empty row',
                    type: 'boolean',
                    tier: 'optional',
                    help: 'Stop reading at the first fully empty row (the extension forces this on when no range is given).',
                    section: 'robustness',
                },
                {
                    key: 'xlsx__ignore_errors',
                    label: 'Ignore cell errors',
                    type: 'boolean',
                    tier: 'optional',
                    help: 'Unrepresentable cells land as NULL instead of failing the file.',
                    section: 'robustness',
                },
            ];
        case 'text_regex':
            return [
                {
                    key: 'text_regex__pattern',
                    label: 'Pattern',
                    type: 'string',
                    tier: 'required',
                    placeholder: '(?P<level>[A-Z]+) (?P<msg>.+)',
                    help: 'At least one named capture group — group names become the columns. Non-matching lines are dropped.',
                },
                {
                    key: 'delimited__skip_header_lines',
                    label: 'Skip leading lines',
                    type: 'number',
                    tier: 'advanced',
                    min: 0,
                },
                // Same line-reader lane as fixed-width (read_csv over one 'line' column), so the shared
                // error knobs take effect identically — padding on by default. ⚠ This set names only
                // ONE distinct tab id, so it still renders FLAT (the ≥2 rule); it tabs correctly the day
                // a second tab is introduced, rather than needing a second edit then.
                ...sharedRobustnessAttributes(true),
                { key: 'encoding', label: 'Encoding', type: 'string', tier: 'advanced', placeholder: 'UTF-8' },
            ];
    }
}
