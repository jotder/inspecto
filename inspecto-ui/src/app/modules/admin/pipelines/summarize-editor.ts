import { MEASURE_AGGS, measureError } from './measure-grammar';

/**
 * Step workbench — the `transform.summarize` grouping surface's pure logic (S4c).
 *
 * <p>The node stores two flat string lists, `group_by` and `measures`, and that stays exactly true: this
 * module only turns a measure's **shorthand** (`count`, `sum(amount)`) into the two fields a table row
 * needs and back again. The grammar itself is NOT re-declared here — {@link MEASURE_AGGS} and
 * {@link measureError} come from `measure-grammar.ts`, which is pinned to the engine's `MeasureCompiler`
 * by `MeasureGrammarContractTest`. ⛔ Never hard-code the aggregate list in a template.
 *
 * <p>🔴 <b>Nothing an author wrote may be dropped.</b> A stored measure this parser cannot read
 * (hand-written TOON, or a grammar the engine grows before the UI does) is kept **verbatim** in
 * {@link MeasureRow.raw} and rendered as a text row with its own error, never silently discarded.
 * Round-tripping an unrecognised value unchanged is the difference between an editor and a data-loss bug.
 */

/** One row of the measures table. `raw` is set only for a value this module could not parse. */
export interface MeasureRow {
    agg: string;
    field: string;
    /** The stored text, kept when it did not parse — the row then edits this instead of `agg`/`field`. */
    raw?: string;
}

/** `count` takes no column; every other aggregate needs one. */
export function needsField(agg: string): boolean {
    return agg !== 'count';
}

/**
 * A stored measure list as table rows. `count` becomes `{agg:'count', field:''}`; `sum(amount)` becomes
 * `{agg:'sum', field:'amount'}`; anything else is carried as `raw`.
 *
 * <p>⚠ `count(x)` parses as an ordinary `count` WITH a field, because the engine accepts it
 * (`MeasureCompiler` compiles `count` to `COUNT(*)` and ignores the field). Rejecting it here would
 * refuse a value the server takes — the banned direction.
 */
export function parseMeasures(values: readonly string[] | undefined): MeasureRow[] {
    return (values ?? []).map((value) => {
        const m = String(value ?? '').trim();
        if (m === 'count') return { agg: 'count', field: '' };
        const open = m.indexOf('(');
        if (open > 0 && m.endsWith(')')) {
            const agg = m.slice(0, open);
            const field = m.slice(open + 1, -1);
            if (MEASURE_AGGS.includes(agg)) return { agg, field };
        }
        return { agg: '', field: '', raw: m };
    });
}

/** A row back to its stored shorthand. An unparsed row round-trips its original text byte for byte. */
export function formatMeasure(row: MeasureRow): string {
    if (row.raw !== undefined) return row.raw;
    if (!row.agg) return '';
    return needsField(row.agg) || row.field ? `${row.agg}(${row.field})` : row.agg;
}

/** The whole table as the stored list, blank rows dropped (an empty row is an unfinished edit). */
export function formatMeasures(rows: readonly MeasureRow[]): string[] {
    return rows.map(formatMeasure).filter((m) => m !== '');
}

/**
 * Why a row is rejected, or `null`. Runs the SAME {@link measureError} the list validator runs, over the
 * shorthand the row would store — so the structured table and a hand-typed list cannot disagree about
 * what is valid.
 */
export function measureRowError(row: MeasureRow): string | null {
    if (row.raw !== undefined) return measureError(row.raw);
    if (!row.agg) return null; // an empty new row is not an error until it is filled in
    if (needsField(row.agg) && !row.field.trim()) return `${row.agg} needs a column`;
    return measureError(formatMeasure(row));
}
