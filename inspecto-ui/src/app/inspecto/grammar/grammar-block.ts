/**
 * A stored **Grammar component's content** read as a `parsing:`-shaped block — the one place that
 * knows a Grammar component can be either of two shapes.
 *
 * A component is either the **legacy flat** `csv_settings`-style map (`{delimiter, has_header, …}` at
 * top level — how every pre-2026-08-04 component was written, and how the Components page still
 * writes one) or an **extracted `parsing:` block** (`{frontend, delimited: {…}}`). The engine already
 * resolves both, discriminating on a nested `delimited`/`plugin` root
 * (`PipelineConfigParser`); this is the UI's half of that same rule.
 *
 * ⚠ **Load-bearing.** `<inspecto-grammar-editor>` seeds its property sheet by flattening the block to
 * `delimited__delimiter`-style keys, so a legacy flat `{delimiter: '|'}` matches NO spec key and the
 * form falls back to its declared defaults — the stored `|` silently becomes `,`. Feeding raw
 * component content to the editor loses the operator's settings while looking like it worked.
 */

/** Keys that live under the `delimited:` sub-block but sat at top level in a legacy component. */
const CSV_SETTINGS_KEYS = ['delimiter', 'has_header', 'skip_header_lines', 'null_strings', 'quote', 'escape'];

/** The sub-block roots whose presence proves the content is already `parsing:`-shaped. */
const PARSING_ROOTS = ['delimited', 'fixedwidth', 'json', 'text_regex', 'plugin'];

/**
 * Whether the stored content is ALREADY the nested `parsing:` shape — i.e. whether a writer must put
 * the csv settings back under `delimited:` rather than at top level. Deliberately the same test as the
 * lift below (a sub-block root present as an object), so read and write agree on one rule; a bare
 * `frontend` key does not make content nested, because nothing has been rehomed.
 */
export function isNestedGrammarContent(content: Record<string, unknown>): boolean {
    return PARSING_ROOTS.some((r) => content?.[r] !== null && typeof content?.[r] === 'object');
}

/**
 * Normalise a Grammar component's stored content into a `parsing:`-shaped block the editor can seed
 * from. Legacy `parser_type` becomes `frontend` (no engine code ever read `parser_type`), and legacy
 * top-level csv settings move under `delimited:`. Already-nested content passes through untouched.
 */
export function grammarContentAsParsingBlock(content: Record<string, unknown>): Record<string, unknown> {
    const { parser_type: legacyType, ...block } = content ?? {};
    if (block['frontend'] === undefined && typeof legacyType === 'string') block['frontend'] = legacyType;

    // Already nested ⇒ nothing to lift. Only a flat component needs the csv settings rehomed.
    if (isNestedGrammarContent(block)) return block;

    const delimited: Record<string, unknown> = {};
    for (const k of CSV_SETTINGS_KEYS) {
        if (block[k] === undefined) continue;
        delimited[k] = block[k];
        delete block[k];
    }
    if (Object.keys(delimited).length > 0) block['delimited'] = delimited;
    return block;
}

/**
 * Sub-block roots that are a parser other than DSV — a `delimited:` reading of one is a fiction.
 * ⚠ DERIVED from `PARSING_ROOTS`, never restated: a new root added to one list but not the other would
 * silently read as delimited again, which is the exact failure this module exists to prevent.
 */
const NON_DELIMITED_ROOTS = PARSING_ROOTS.filter((r) => r !== 'delimited');

/**
 * What this Grammar is, when it is **not** plain delimited — a root sub-block name (`plugin`,
 * `fixedwidth`, …) or the declared frontend; `null` for a genuine DSV component.
 *
 * ⚠ Every DSV-only surface needs this: reading a top-level `delimiter` off an `asn1`/`json`/`xlsx`
 * component yields the DEFAULT `,` and reports it as comma-delimited, which is not a display quirk —
 * a form that then SAVES that reading replaces the stored parser (`PUT /components` is a replace).
 */
export function nonDelimitedGrammar(content: Record<string, unknown>): string | null {
    return nonDelimitedGrammarBlock(grammarContentAsParsingBlock(content));
}

/**
 * As {@link nonDelimitedGrammar}, for a caller that has ALREADY normalised. Prefer this whenever the
 * block is needed too — normalising twice for one answer costs two spreads and a key sweep per call,
 * and the summary/seed paths run per row per change detection.
 *
 * Reading `frontend` off the block rather than the raw content is not a shortcut: the lift folds a
 * legacy `parser_type` into `frontend`, so the block already carries whichever the component declared.
 */
export function nonDelimitedGrammarBlock(block: Record<string, unknown>): string | null {
    const root = NON_DELIMITED_ROOTS.find((r) => block[r] !== null && typeof block[r] === 'object');
    if (root) return root;
    if (block['segments'] !== undefined) return 'segments';
    if (grammarSeedsFrontend(block, 'delimited')) return null;
    return String(block['frontend']);
}

/**
 * The inverse of {@link grammarContentAsParsingBlock} — a block written back in the shape it was
 * stored in. Lives here, beside the lift, so the encode/decode pair cannot drift apart: an edit is not
 * a migration, so a legacy flat component stays flat and a nested one stays nested.
 */
export function grammarBlockAsContent(block: Record<string, unknown>, nested: boolean): Record<string, unknown> {
    if (nested) return { ...block };
    const { delimited, ...flat } = block;
    return { ...flat, ...(delimited as Record<string, unknown> | undefined) };
}

/** Every spelling that names each per-format frontend a node type can be locked to. */
const FRONTEND_ALIASES: Record<string, readonly string[]> = {
    delimited: ['delimited', 'csv', 'dsv'],
    fixedwidth: ['fixedwidth', 'fixed_width'],
    xlsx: ['xlsx', 'excel'],
};

/**
 * Whether this component can seed a node of the given **frontend** — the picker must not offer a
 * Grammar that names another one, because a per-format node's format IS its type and the save path
 * refuses a contradicting block with `PARSER_FRONTEND_MISMATCH`.
 *
 * ⚠ Deliberately NOT `normalizeFrontend`, which maps anything unrecognised to `delimited` — that
 * would offer an `xlsx`/`html` component as if it were delimited. A component qualifies only when it
 * declares the frontend explicitly, or — for delimited ONLY — declares nothing at all: an undeclared
 * component is the legacy flat shape, and delimited is the engine's implicit default. Offering such a
 * component as fixed-width would seed a slice table from a `{delimiter}` map.
 */
export function grammarSeedsFrontend(content: Record<string, unknown>, frontend: string): boolean {
    const declared = content?.['frontend'] ?? content?.['parser_type'];
    if (declared === undefined || declared === null || declared === '') return frontend === 'delimited';
    const f = String(declared).trim().toLowerCase();
    return (FRONTEND_ALIASES[frontend] ?? [frontend]).includes(f);
}
