/**
 * The statistical Measure aggregates (D-AN5) for the OFFLINE engine, spelled the DuckDB way so one compiled
 * expression (`query-spec.ts#aggExpression`) means the same thing offline and live: `QUANTILE_CONT(x, p)`
 * (continuous, interpolated between neighbours — D-AN3), `STDDEV_SAMP(x)` and `VAR_SAMP(x)` (sample, n - 1).
 * SQL null rules: a null or non-numeric value is ignored, and an aggregate over no values — or a sample spread
 * over one — is NULL. `measure-grammar.contract.json`'s `statistics` goldens hold both engines to the same numbers.
 */

/** Continuous quantile of `xs` at `p` ∈ [0, 1]: linear interpolation at position (n - 1)·p of the sorted values. */
export function quantileCont(xs: readonly number[], p: number): number | null {
    if (!xs.length) return null;
    const sorted = [...xs].sort((a, b) => a - b);
    const h = (sorted.length - 1) * p;
    const lo = Math.floor(h);
    const hi = Math.min(lo + 1, sorted.length - 1);
    return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo]);
}

/** Sample variance (n - 1); NULL below two values, as DuckDB's `var_samp`. */
export function varSamp(xs: readonly number[]): number | null {
    if (xs.length < 2) return null;
    const mean = xs.reduce((a, b) => a + b, 0) / xs.length;
    return xs.reduce((a, x) => a + (x - mean) ** 2, 0) / (xs.length - 1);
}

function numeric(v: unknown): number | null {
    if (v === null || v === undefined || v === '') return null;
    const n = typeof v === 'number' ? v : Number(v);
    return Number.isFinite(n) ? n : null;
}

interface Acc {
    xs: number[];
    p?: number;
}

/**
 * Register the aggregates on the AlaSQL engine. AlaSQL calls a custom aggregate per row with stage 1 (first row of a
 * group) or 2 (each later row) and the accumulator, then once with stage 3 to finalize; a two-argument aggregate gets
 * its second argument before the accumulator, but NOT at stage 3 — so the fraction travels in the accumulator.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function registerStatAggregates(alasql: any): void {
    const collect = (v: unknown, acc: Acc | undefined, stage: number, p?: number): Acc => {
        const a = stage === 1 || !acc ? { xs: [], p } : acc;
        const n = numeric(v);
        if (n !== null) a.xs.push(n);
        return a;
    };
    alasql.aggr.QUANTILE_CONT = (v: unknown, p: unknown, acc: Acc | undefined, stage: number) =>
        stage === 3 ? (acc ? quantileCont(acc.xs, acc.p ?? 0.5) : null) : collect(v, acc, stage, Number(p));
    alasql.aggr.VAR_SAMP = (v: unknown, acc: Acc | undefined, stage: number) =>
        stage === 3 ? (acc ? varSamp(acc.xs) : null) : collect(v, acc, stage);
    alasql.aggr.STDDEV_SAMP = (v: unknown, acc: Acc | undefined, stage: number) => {
        if (stage !== 3) return collect(v, acc, stage);
        const variance = acc ? varSamp(acc.xs) : null;
        return variance === null ? null : Math.sqrt(variance);
    };
}
