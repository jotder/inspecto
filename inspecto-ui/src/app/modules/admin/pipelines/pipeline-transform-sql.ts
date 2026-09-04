import { SqlFunction, quoteIdentifier, renderExpression, sqlFunction, usesSource } from './sql-functions';

/**
 * The Fields-grid model for a `transform.sql` Step and its `fields[] → SELECT` generator — a **pure**
 * module: no Angular, no I/O, so the grid and its unit tests both compile through the same one place.
 *
 * <p><b>History.</b> A five-verb version of this shipped 2026-09-03 and was deleted the next day
 * (`24171333`) when the Step went SQL-only. The operator reversed that on 2026-09-04 — a raw SQL box is
 * not an authoring surface for a non-technical user — and asked for the grid back with *real functions
 * and parameters*, which is what {@link SqlFunction} now models.
 *
 * <p><b>Round-trip.</b> `fields[]` is persisted BESIDE the generated `sql` in the node's config. The
 * engine reads only `sql` (the one declared attribute); `fields` rides the `steps:` chain opaquely, and
 * exists so reopening the Step rebuilds the grid exactly. That is what makes the grid safe without the
 * retired D6 "hand edit locks the grid" rule: nothing has to parse SQL back into rows.
 */

/** One output column of the Step. */
export interface SqlField {
    /** Stable row identity for grid tracking — NOT the output position, which is derived. */
    id: string;
    /** The output column name, i.e. the `AS <name>` alias. */
    name: string;
    /** The source column the function reads. Empty for a function whose template has no `{source}`. */
    from: string;
    /** A {@link SqlFunction} id, e.g. `text.trim`. */
    fn: string;
    /** Parameter values by parameter name. Absent keys fall back to the parameter's declared default. */
    args: Record<string, string>;
}

/** What the grid needs to render and validate one row, derived from the catalog. */
export interface CompiledField {
    readonly field: SqlField;
    readonly definition: SqlFunction | undefined;
    /** The unaliased SQL, when the row is complete. */
    readonly expr?: string;
    /** Why the row cannot compile yet — shown inline, and it blocks Apply. */
    readonly problem?: string;
}

/** Compile one row against the catalog. An unknown `fn` is reported, never silently dropped. */
export function compileField(field: SqlField): CompiledField {
    const definition = sqlFunction(field.fn);
    if (!definition) return { field, definition, problem: `This build has no function “${field.fn}”.` };
    if (!field.name.trim()) return { field, definition, problem: 'Give the field a name.' };
    const rendered = renderExpression(definition, field.from, field.args ?? {});
    return 'problem' in rendered
        ? { field, definition, problem: rendered.problem }
        : { field, definition, expr: rendered.expr };
}

export function compileFields(fields: readonly SqlField[]): CompiledField[] {
    return fields.map(compileField);
}

/**
 * `fields[] → SELECT`. Every complete row emits `<expr> AS <name>`; a row with a problem is SKIPPED so
 * the generated SQL always parses — the grid surfaces the problem and blocks Apply, so an incomplete row
 * can never be saved silently. The input relation is the fixed alias `input`, which the engine rewrites
 * to the real relation at execution.
 */
export function generateSql(fields: readonly SqlField[]): string {
    const cols = compileFields(fields)
        .filter((c): c is CompiledField & { expr: string } => typeof c.expr === 'string')
        .map((c) => `  ${c.expr} AS ${quoteIdentifier(c.field.name.trim())}`);
    if (!cols.length) return 'SELECT * FROM input';
    return `SELECT\n${cols.join(',\n')}\nFROM input`;
}

/** Seed for a NEW Step: one "Keep as it is" row per upstream column, in upstream order. */
export function seedFields(upstreamColumns: readonly string[]): SqlField[] {
    return upstreamColumns.map((col, i) => ({
        id: `seed-${i}-${col}`,
        name: col,
        from: col,
        fn: 'keep',
        args: {},
    }));
}

/** A short id for a row the author adds. */
export function newFieldId(): string {
    return `f-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * Seed a row's args from a function's declared defaults when the function changes, keeping any value the
 * author already typed for a parameter of the same name. Also clears `from` for a source-less function
 * so the grid shows "calculated" rather than a stale column.
 */
export function applyFunction(field: SqlField, fnId: string): SqlField {
    const definition = sqlFunction(fnId);
    const args: Record<string, string> = {};
    for (const param of definition?.params ?? []) {
        const kept = field.args?.[param.name];
        args[param.name] = kept !== undefined && kept !== '' ? kept : (param.default ?? '');
    }
    const keepsSource = definition ? usesSource(definition) : true;
    return { ...field, fn: fnId, args, from: keepsSource ? field.from : '' };
}

/**
 * Read a node's persisted `fields`, tolerating anything that is not the shape we wrote (a hand-edited
 * config, or a node last saved by the retired five-verb grid). Returns `null` when there is nothing
 * usable, so the caller falls back to seeding from the upstream columns.
 */
export function readFields(stored: unknown): SqlField[] | null {
    if (!Array.isArray(stored) || !stored.length) return null;
    const out: SqlField[] = [];
    for (const raw of stored) {
        if (!raw || typeof raw !== 'object') continue;
        const r = raw as Record<string, unknown>;
        const name = typeof r['name'] === 'string' ? r['name'] : '';
        if (!name) continue;
        out.push({
            id: typeof r['id'] === 'string' && r['id'] ? r['id'] : newFieldId(),
            name,
            from: typeof r['from'] === 'string' ? r['from'] : '',
            fn: typeof r['fn'] === 'string' && r['fn'] ? r['fn'] : legacyVerbToFn(r['verb']),
            args: readArgs(r['args'], r),
        });
    }
    return out.length ? out : null;
}

/** The retired 2026-09-03 grid stored a `verb`; map its five values onto catalog ids so those nodes open. */
function legacyVerbToFn(verb: unknown): string {
    switch (verb) {
        case 'trim':
            return 'text.trim';
        case 'upper':
            return 'text.upper';
        case 'cast':
            return 'convert.type';
        case 'formula':
            return 'custom';
        default:
            return 'keep';
    }
}

function readArgs(stored: unknown, row: Record<string, unknown>): Record<string, string> {
    const out: Record<string, string> = {};
    if (stored && typeof stored === 'object' && !Array.isArray(stored)) {
        for (const [k, v] of Object.entries(stored as Record<string, unknown>)) {
            if (typeof v === 'string') out[k] = v;
            else if (typeof v === 'number' || typeof v === 'boolean') out[k] = String(v);
        }
    }
    // Carry the retired grid's two parameter-ish keys onto their catalog parameter names.
    if (typeof row['castType'] === 'string' && out['type'] === undefined) out['type'] = row['castType'];
    if (typeof row['formula'] === 'string' && out['expression'] === undefined) out['expression'] = row['formula'];
    return out;
}
