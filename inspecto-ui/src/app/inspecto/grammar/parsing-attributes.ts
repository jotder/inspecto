import { AttributeSpec } from 'app/inspecto/component-model';

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
export const GRAMMAR_TABS: { id: string; label: string }[] = [
    { id: 'dialect', label: 'Dialect / parsing' },
    { id: 'types', label: 'Types & columns' },
    { id: 'robustness', label: 'Robustness / error handling' },
    { id: 'files', label: 'Files & metadata' },
];

/** Exactly one character — the dialect chars the engine validates fail-closed at load. */
const SINGLE_CHAR = '[\\s\\S]';

export const PARSING_FRONTENDS: { id: ParsingFrontend; label: string; hint: string }[] = [
    { id: 'delimited', label: 'Delimited', hint: 'CSV / TSV / pipe — one record per line, split by a delimiter' },
    { id: 'fixedwidth', label: 'Fixed width', hint: 'Positional slices carved from each line' },
    { id: 'json', label: 'JSON', hint: 'NDJSON (one object per line) or a JSON array document' },
    { id: 'text_regex', label: 'Text / regex', hint: 'Named capture groups over matching lines' },
    { id: 'xlsx', label: 'Excel', hint: 'An .xlsx workbook sheet, read natively (DuckDB read_xlsx)' },
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
            // 4 GRAMMAR_TABS. Every key here is read by PipelineConfigParser — no dead knobs.
            return [
                // ── tab 1: Dialect / parsing ─────────────────────────────────────
                {
                    key: 'delimited__delimiter',
                    label: 'Delimiter',
                    type: 'string',
                    tier: 'required',
                    required: false,
                    default: ',',
                    placeholder: ',',
                    tab: 'dialect',
                },
                {
                    key: 'delimited__has_header',
                    label: 'First line is a header',
                    type: 'boolean',
                    tier: 'required',
                    required: false,
                    default: true,
                    tab: 'dialect',
                },
                {
                    key: 'delimited__quote',
                    label: 'Quote character',
                    type: 'string',
                    tier: 'optional',
                    pattern: SINGLE_CHAR,
                    placeholder: '"',
                    help: 'Single character wrapping fields that contain the delimiter (default ").',
                    tab: 'dialect',
                },
                {
                    key: 'delimited__escape',
                    label: 'Escape character',
                    type: 'string',
                    tier: 'optional',
                    pattern: SINGLE_CHAR,
                    help: 'Single character escaping a literal quote inside a quoted field (default: the quote, doubled).',
                    tab: 'dialect',
                },
                {
                    key: 'delimited__comment',
                    label: 'Comment character',
                    type: 'string',
                    tier: 'optional',
                    pattern: SINGLE_CHAR,
                    placeholder: '#',
                    help: 'Lines starting with this single character are skipped (default: none).',
                    tab: 'dialect',
                },
                {
                    key: 'delimited__skip_header_lines',
                    label: 'Skip leading lines',
                    type: 'number',
                    tier: 'optional',
                    min: 0,
                    help: 'Banner/preamble lines before the data (and header).',
                    tab: 'dialect',
                },
                {
                    key: 'delimited__skip_junk_lines',
                    label: 'Skip junk lines (adaptive)',
                    type: 'number',
                    tier: 'optional',
                    min: -1,
                    help: 'Max preamble lines to probe past until a parseable data row; -1 = unlimited.',
                    tab: 'dialect',
                },
                {
                    key: 'delimited__skip_tail_lines',
                    label: 'Skip trailing lines',
                    type: 'number',
                    tier: 'optional',
                    min: 0,
                    help: 'Footer lines dropped from the end of each file.',
                    tab: 'dialect',
                },
                {
                    key: 'delimited__skip_tail_columns',
                    label: 'Skip trailing columns',
                    type: 'number',
                    tier: 'optional',
                    min: 0,
                    help: 'Phantom columns stripped from the right of each record.',
                    tab: 'dialect',
                },
                {
                    key: 'encoding',
                    label: 'Encoding',
                    type: 'select',
                    tier: 'advanced',
                    options: [
                        { value: 'utf-8', label: 'UTF-8 (default)' },
                        { value: 'utf-16', label: 'UTF-16' },
                        { value: 'latin-1', label: 'Latin-1' },
                    ],
                    tab: 'dialect',
                },
                // ── tab 2: Types & columns ───────────────────────────────────────
                {
                    key: 'delimited__date_formats',
                    label: 'Date formats',
                    type: 'list',
                    tier: 'optional',
                    placeholder: '%Y-%m-%d',
                    help: 'Accepted DATE parse patterns, tried in order.',
                    tab: 'types',
                },
                {
                    key: 'delimited__timestamp_formats',
                    label: 'Timestamp formats',
                    type: 'list',
                    tier: 'optional',
                    placeholder: '%Y-%m-%d %H:%M:%S',
                    help: 'Accepted TIMESTAMP parse patterns, tried in order.',
                    tab: 'types',
                },
                {
                    key: 'delimited__null_strings',
                    label: 'Null strings',
                    type: 'list',
                    tier: 'optional',
                    placeholder: 'NULL',
                    help: 'Literal text values read as NULL.',
                    tab: 'types',
                },
                // ── tab 3: Robustness / error handling ───────────────────────────
                {
                    // ⚠ No `default`: a spec default materializes into every value() and would be
                    // WRITTEN into blocks the author never touched — mutating faithful copies of
                    // stored grammars (templates are copies, never bindings). Blank = engine default.
                    key: 'delimited__strict_mode',
                    label: 'Strict mode (RFC-4180)',
                    type: 'boolean',
                    tier: 'optional',
                    help: 'Engine default is strict. Off tolerates quote/column drift in messy files.',
                    tab: 'robustness',
                },
                {
                    key: 'delimited__engine',
                    label: 'Parse engine',
                    type: 'select',
                    tier: 'optional',
                    help: 'Blank = auto.',
                    options: [
                        { value: 'auto', label: 'Auto — native for clean configs' },
                        { value: 'duckdb', label: 'DuckDB — native vectorized reader' },
                        { value: 'java', label: 'Java — fallback for messy files' },
                    ],
                    tab: 'robustness',
                },
                {
                    key: 'delimited__include_prefixes',
                    label: 'Include rows: prefixes',
                    type: 'list',
                    tier: 'advanced',
                    help: 'Keep only rows whose filter target column starts with any of these.',
                    tab: 'robustness',
                },
                {
                    key: 'delimited__include_regex',
                    label: 'Include rows: regex',
                    type: 'list',
                    tier: 'advanced',
                    help: 'Keep only rows whose filter target column matches any of these.',
                    tab: 'robustness',
                },
                {
                    key: 'delimited__exclude_prefixes',
                    label: 'Exclude rows: prefixes',
                    type: 'list',
                    tier: 'advanced',
                    help: 'Drop rows whose filter target column starts with any of these.',
                    tab: 'robustness',
                },
                {
                    key: 'delimited__exclude_regex',
                    label: 'Exclude rows: regex',
                    type: 'list',
                    tier: 'advanced',
                    help: 'Drop rows whose filter target column matches any of these.',
                    tab: 'robustness',
                },
                {
                    key: 'delimited__filter_target_column',
                    label: 'Filter target column',
                    type: 'number',
                    tier: 'advanced',
                    min: 0,
                    help: '0-based physical column the include/exclude filters apply to.',
                    tab: 'robustness',
                },
                {
                    key: 'delimited__where',
                    label: 'Row filter (SQL)',
                    type: 'multiline',
                    tier: 'optional',
                    placeholder: 'amount > 0',
                    help: 'Post-parse SQL predicate over the mapped, typed columns.',
                    tab: 'robustness',
                },
                // ── tab 4: Files & metadata ──────────────────────────────────────
                {
                    key: 'compression',
                    label: 'Input compression',
                    type: 'select',
                    tier: 'optional',
                    options: [
                        { value: 'auto', label: 'Auto — detect by extension' },
                        { value: 'gzip', label: 'gzip' },
                        { value: 'zstd', label: 'zstd' },
                        { value: 'none', label: 'None' },
                    ],
                    help: 'Decompressed inline at read.',
                    tab: 'files',
                },
            ];
        case 'fixedwidth':
            // Tabbed since multiformat F1: Record layout (+ the slice table the editor homes there),
            // Types & columns (transform-time patterns), Robustness, Files & metadata.
            return [
                {
                    key: 'delimited__has_header',
                    label: 'First line is a header',
                    type: 'boolean',
                    tier: 'required',
                    required: false,
                    default: true,
                    help: 'Header/banner line to skip before the records.',
                    tab: 'dialect',
                },
                {
                    key: 'delimited__date_formats',
                    label: 'Date formats',
                    type: 'list',
                    tier: 'optional',
                    placeholder: '%Y-%m-%d',
                    help: 'Accepted DATE parse patterns, tried in order.',
                    tab: 'types',
                },
                {
                    key: 'delimited__timestamp_formats',
                    label: 'Timestamp formats',
                    type: 'list',
                    tier: 'optional',
                    placeholder: '%Y-%m-%d %H:%M:%S',
                    help: 'Accepted TIMESTAMP parse patterns, tried in order.',
                    tab: 'types',
                },
                {
                    key: 'fixedwidth__min_record_length',
                    label: 'Minimum record length',
                    type: 'number',
                    tier: 'optional',
                    min: 0,
                    help: 'Shorter lines (footers, blanks) are dropped. Blank = the widest field end.',
                    tab: 'robustness',
                },
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
                    tab: 'robustness',
                },
                {
                    key: 'encoding',
                    label: 'Encoding',
                    type: 'string',
                    tier: 'advanced',
                    placeholder: 'UTF-8',
                    tab: 'files',
                },
                {
                    key: 'compression',
                    label: 'Input compression',
                    type: 'string',
                    tier: 'advanced',
                    placeholder: 'gzip',
                    tab: 'files',
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
                    tab: 'dialect',
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
                    tab: 'dialect',
                },
                {
                    key: 'delimited__skip_header_lines',
                    label: 'Skip leading lines',
                    type: 'number',
                    tier: 'advanced',
                    min: 0,
                    tab: 'dialect',
                },
                {
                    key: 'delimited__date_formats',
                    label: 'Date formats',
                    type: 'list',
                    tier: 'optional',
                    placeholder: '%Y-%m-%d',
                    help: 'Accepted DATE parse patterns, tried in order.',
                    tab: 'types',
                },
                {
                    key: 'delimited__timestamp_formats',
                    label: 'Timestamp formats',
                    type: 'list',
                    tier: 'optional',
                    placeholder: '%Y-%m-%d %H:%M:%S',
                    help: 'Accepted TIMESTAMP parse patterns, tried in order.',
                    tab: 'types',
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
                    tab: 'robustness',
                },
                {
                    key: 'json__maximum_object_size',
                    label: 'Maximum object size (bytes)',
                    type: 'number',
                    tier: 'advanced',
                    min: 0,
                    dependsOn: { key: 'json__format', notEquals: 'newline' },
                    help: 'Bound on a single document/record the reader will buffer. Blank = the engine default.',
                    tab: 'robustness',
                },
                {
                    key: 'compression',
                    label: 'Input compression',
                    type: 'string',
                    tier: 'advanced',
                    placeholder: 'gzip',
                    tab: 'files',
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
                    tab: 'dialect',
                },
                {
                    key: 'xlsx__range',
                    label: 'Cell range',
                    type: 'string',
                    tier: 'optional',
                    pattern: '[A-Za-z]{1,3}[0-9]{1,7}(:[A-Za-z]{1,3}[0-9]{1,7})?',
                    placeholder: 'A1:F100',
                    help: 'A1-style anchor or span. Blank = the whole sheet.',
                    tab: 'dialect',
                },
                {
                    key: 'xlsx__header',
                    label: 'First row is a header',
                    type: 'boolean',
                    tier: 'required',
                    required: false,
                    default: true,
                    help: 'Header cells name the columns; without one they are A, B, C…',
                    tab: 'dialect',
                },
                {
                    key: 'xlsx__normalize_names',
                    label: 'Normalize header names',
                    type: 'boolean',
                    tier: 'optional',
                    help: 'Lower-snake identifiers from the header cells.',
                    tab: 'dialect',
                },
                // ── tab 2: Types & columns (transform-time typing, shared keys) ──
                {
                    key: 'delimited__date_formats',
                    label: 'Date formats',
                    type: 'list',
                    tier: 'optional',
                    placeholder: '%Y-%m-%d',
                    help: 'Accepted DATE parse patterns, tried in order.',
                    tab: 'types',
                },
                {
                    key: 'delimited__timestamp_formats',
                    label: 'Timestamp formats',
                    type: 'list',
                    tier: 'optional',
                    placeholder: '%Y-%m-%d %H:%M:%S',
                    help: 'Accepted TIMESTAMP parse patterns, tried in order.',
                    tab: 'types',
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
                    tab: 'robustness',
                },
                {
                    key: 'xlsx__ignore_errors',
                    label: 'Ignore cell errors',
                    type: 'boolean',
                    tier: 'optional',
                    help: 'Unrepresentable cells land as NULL instead of failing the file.',
                    tab: 'robustness',
                },
                // tab 4 (Files & metadata) carries no xlsx option — the tab still renders: the
                // Collection pointer and the host-projected column-metadata grid live there.
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
                { key: 'encoding', label: 'Encoding', type: 'string', tier: 'advanced', placeholder: 'UTF-8' },
            ];
    }
}
