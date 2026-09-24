import { ParserPreview } from 'app/inspecto/api';

/**
 * AUTHORING-REDESIGN-1 (i) — the Parse pane's per-row "sample resolves to" line.
 *
 * The delimited preview serves `resolved`: what DuckDB's own dialect sniff (`sniff_csv`) makes of the
 * SAMPLE, keyed by the `parsing.delimited.*` option it describes — the file's answer, independent of the
 * grammar being authored. This maps it onto the delimited spec set's flat keys, formatted for a person
 * (a tab reads "tab", an absent quote "none"), so each property row can show it beside its own value.
 * Framework-free; an old server (no `resolved`), a tree preview or a non-delimited format yields `{}`.
 */
const SPEC_KEY: Record<string, string> = {
    delimiter: 'delimited__delimiter',
    quote: 'delimited__quote',
    escape: 'delimited__escape',
    comment: 'delimited__comment',
    has_header: 'delimited__has_header',
    skip_header_lines: 'delimited__skip_header_lines',
    date_format: 'delimited__date_formats',
    timestamp_format: 'delimited__timestamp_formats',
};

/** Characters that are invisible or ambiguous when printed bare. */
const NAMED: Record<string, string> = { '\t': 'tab', ' ': 'space' };

/** Options where an empty answer means the sniff found none (not "no answer"). */
const NONE_WHEN_EMPTY = new Set(['quote', 'escape', 'comment']);

export function sampleResolutions(preview: ParserPreview | null): Record<string, string> {
    const resolved = preview?.kind === 'table' ? preview.resolved : undefined;
    const out: Record<string, string> = {};
    if (!resolved) return out;
    for (const [option, raw] of Object.entries(resolved)) {
        const key = SPEC_KEY[option];
        if (!key || raw === null || raw === undefined) continue;
        const value = String(raw);
        if (value === '') {
            if (NONE_WHEN_EMPTY.has(option)) out[key] = 'none';
            continue; // e.g. no date format detected: say nothing rather than a blank line
        }
        if (option === 'has_header') out[key] = value === 'true' ? 'yes' : value === 'false' ? 'no' : value;
        else out[key] = NAMED[value] ?? value;
    }
    return out;
}
