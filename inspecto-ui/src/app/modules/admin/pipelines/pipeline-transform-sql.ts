/**
 * The SQL transformer v1 Simple-mode generator (`sql-transform-v1-plan.md` B3) — a **pure** function,
 * no Angular, no I/O: `fields[] → SELECT`. This is the ONE place the five verbs compile to SQL, so the
 * Simple grid and its unit tests both go through it rather than each re-deriving an expression.
 *
 * <p>D5 pins exactly five verbs — a sixth is a product decision, not a dev convenience. D6 pins
 * `TRY_CAST`, never bare `CAST` (the forgiving semantic the cast-failure audit's WARNING finding on
 * `transform.sql` already assumes, B5). This module never parses SQL back into fields — v1 is
 * forward-only generation (D6/D4).
 */

/** The five plain-language verbs a Fields-grid row may apply (D5) — offering a sixth is a product call. */
export type SqlFieldVerb = 'keep' | 'trim' | 'upper' | 'cast' | 'formula';

/**
 * One row of the Simple grid / one output column. `from` is the source column for every verb except
 * `formula`, where the field has no source (rendered as "— calculated —"). `castType` is read only for
 * `cast`; `formula` is read only for `formula`.
 */
export interface SqlField {
    /** Stable row identity for grid tracking — NOT the output position (that is derived, see `#`). */
    id: string;
    /** The output column name — becomes the `AS <name>` alias. */
    name: string;
    /** The source column this row reads, or `''` for a `formula` row ("— calculated —"). */
    from: string;
    verb: SqlFieldVerb;
    /** The DuckDB type for `verb: 'cast'` (e.g. `BIGINT`, `VARCHAR`, `DATE`). Ignored otherwise. */
    castType?: string;
    /** The verbatim SQL expression for `verb: 'formula'`. Ignored otherwise. */
    formula?: string;
}

/**
 * The SQL expression a field's verb compiles to, unaliased. `TRY_CAST` — never bare `CAST` — is the
 * deliberately forgiving semantic pinned by the plan (D6): a bad cell nulls one cell instead of killing
 * the whole batch, matching the audit's cast-failure denominator.
 */
export function exprFor(field: SqlField): string {
    const from = field.from;
    switch (field.verb) {
        case 'keep':
            return from;
        case 'trim':
            return `TRIM(${from})`;
        case 'upper':
            return `UPPER(${from})`;
        case 'cast':
            return `TRY_CAST(${from} AS ${(field.castType || 'VARCHAR').trim()})`;
        case 'formula':
            return (field.formula ?? '').trim() || 'NULL';
    }
}

/**
 * `fields[] → SELECT`. Every row emits `<expr> AS <name>`; the input relation is always the fixed alias
 * `input` (B1's convention — the engine rewrites it to the real relation at execution). An empty
 * `fields[]` still produces a syntactically valid (if pointless) `SELECT * FROM input`, so a freshly
 * created step with no upstream columns yet never shows broken SQL.
 */
export function generateSql(fields: readonly SqlField[]): string {
    if (fields.length === 0) return 'SELECT * FROM input';
    const cols = fields.map((f) => `  ${exprFor(f)} AS ${f.name}`).join(',\n');
    return `SELECT\n${cols}\nFROM input`;
}

/** Seed for a NEW `transform.sql` step: one `keep` row per upstream column, in upstream order. */
export function seedFields(upstreamColumns: readonly string[]): SqlField[] {
    return upstreamColumns.map((col, i) => ({
        id: `seed-${i}-${col}`,
        name: col,
        from: col,
        verb: 'keep' as const,
    }));
}

/** A short, source-agnostic id for a newly added row (calculated field, or a restored left-out one). */
export function newFieldId(): string {
    return `f-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}
