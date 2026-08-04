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
export type ParsingFrontend = 'delimited' | 'fixedwidth' | 'json' | 'text_regex';

export const PARSING_FRONTENDS: { id: ParsingFrontend; label: string; hint: string }[] = [
    { id: 'delimited', label: 'Delimited', hint: 'CSV / TSV / pipe — one record per line, split by a delimiter' },
    { id: 'fixedwidth', label: 'Fixed width', hint: 'Positional slices carved from each line' },
    { id: 'json', label: 'JSON', hint: 'NDJSON (one object per line) or a JSON array document' },
    { id: 'text_regex', label: 'Text / regex', hint: 'Named capture groups over matching lines' },
];

export function parsingAttributesFor(frontend: ParsingFrontend): AttributeSpec[] {
    switch (frontend) {
        case 'delimited':
            return [
                { key: 'delimited__delimiter', label: 'Delimiter', type: 'string', tier: 'required', required: false, default: ',', placeholder: ',' },
                { key: 'delimited__has_header', label: 'First line is a header', type: 'boolean', tier: 'required', required: false, default: true },
                { key: 'delimited__skip_header_lines', label: 'Skip leading lines', type: 'number', tier: 'optional', min: 0, help: 'Banner/preamble lines before the data (and header).' },
                { key: 'delimited__null_strings', label: 'Null strings', type: 'string', tier: 'advanced', placeholder: 'NULL,N/A', help: 'Values read as NULL; comma-separate multiple.' },
                { key: 'encoding', label: 'Encoding', type: 'string', tier: 'advanced', placeholder: 'UTF-8' },
                { key: 'compression', label: 'Input compression', type: 'string', tier: 'advanced', placeholder: 'gzip' },
            ];
        case 'fixedwidth':
            return [
                { key: 'delimited__has_header', label: 'First line is a header', type: 'boolean', tier: 'required', required: false, default: true, help: 'Header/banner line to skip before the records.' },
                { key: 'fixedwidth__min_record_length', label: 'Minimum record length', type: 'number', tier: 'optional', min: 0, help: 'Shorter lines (footers, blanks) are dropped. Blank = the widest field end.' },
                {
                    key: 'fixedwidth__trim', label: 'Trim fields', type: 'select', tier: 'optional', default: 'BOTH',
                    options: [
                        { value: 'BOTH', label: 'Both sides' },
                        { value: 'LEFT', label: 'Left' },
                        { value: 'RIGHT', label: 'Right' },
                        { value: 'NONE', label: 'None' },
                    ],
                },
                { key: 'encoding', label: 'Encoding', type: 'string', tier: 'advanced', placeholder: 'UTF-8' },
                { key: 'compression', label: 'Input compression', type: 'string', tier: 'advanced', placeholder: 'gzip' },
            ];
        case 'json':
            return [
                {
                    key: 'json__format', label: 'Document shape', type: 'select', tier: 'required', required: false, default: 'newline',
                    options: [
                        { value: 'newline', label: 'NDJSON — one object per line' },
                        { value: 'array', label: 'One JSON array of records' },
                        { value: 'auto', label: 'Auto-detect' },
                    ],
                },
                {
                    key: 'json__records_path', label: 'Records path', type: 'string', tier: 'optional',
                    default: '$', placeholder: 'payload.records',
                    // Hidden for NDJSON on purpose: `parseJson` HARD-FAILS a nested path under
                    // `format: newline` (each line is already a record, so there is no enclosing
                    // document to walk). Offering the field there would author a config the parser
                    // rejects at load. `$` = the document's top level IS the array.
                    dependsOn: { key: 'json__format', notEquals: 'newline' },
                    help: 'Dotted path to the array holding the records — same notation as a field selector. Blank or "$" = the whole document.',
                },
                { key: 'delimited__skip_header_lines', label: 'Skip leading lines', type: 'number', tier: 'advanced', min: 0 },
                { key: 'compression', label: 'Input compression', type: 'string', tier: 'advanced', placeholder: 'gzip' },
            ];
        case 'text_regex':
            return [
                {
                    key: 'text_regex__pattern', label: 'Pattern', type: 'string', tier: 'required',
                    placeholder: '(?P<level>[A-Z]+) (?P<msg>.+)',
                    help: 'At least one named capture group — group names become the columns. Non-matching lines are dropped.',
                },
                { key: 'delimited__skip_header_lines', label: 'Skip leading lines', type: 'number', tier: 'advanced', min: 0 },
                { key: 'encoding', label: 'Encoding', type: 'string', tier: 'advanced', placeholder: 'UTF-8' },
            ];
    }
}
