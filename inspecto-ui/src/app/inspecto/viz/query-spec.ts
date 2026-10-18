import { ColumnMeta, compileWhere, quoteIdent } from 'app/inspecto/query';
import { runSql, SqlRunResult } from 'app/inspecto/data-table/sql/sql-run';
import MEASURE_CONTRACT from 'app/inspecto/contracts/measure-grammar.contract.json';
import { Aggregation, ChannelValue, QueryMeasure, QuerySpec, QueryWindow } from './viz-types';

/**
 * Compile/run a {@link QuerySpec}. This is the QuerySpec→SQL boundary — the single swap seam between offline
 * AlaSQL (now) and a backend DuckDB endpoint (later). Reuses the Query Core's `quoteIdent` + `compileWhere` so
 * filter semantics match the rule/query surfaces exactly.
 */

/** The fraction each fixed-name percentile asks for — the twin of `MeasureCompiler.PERCENTILES`. */
const PERCENTILES: Partial<Record<Aggregation, string>> = {
    p05: '0.05',
    p25: '0.25',
    p75: '0.75',
    p90: '0.9',
    p95: '0.95',
    p99: '0.99',
};

/**
 * The SQL aggregate for a measure channel's aggregation over a column (`count` ignores the column). The statistics
 * are spelled the DuckDB way (`QUANTILE_CONT`, `STDDEV_SAMP`, `VAR_SAMP`); the offline engine gets the same names
 * from `registerStatAggregates`. The median is `QUANTILE_CONT(x, 0.5)` rather than `MEDIAN(x)` because AlaSQL's own
 * `MEDIAN` takes the upper middle value of an even count, where DuckDB averages the pair — the same number either way.
 */
export function aggExpression(agg: Aggregation, field: string): string {
    const id = quoteIdent(field);
    const fraction = agg === 'median' ? '0.5' : PERCENTILES[agg];
    if (fraction) return `QUANTILE_CONT(${id}, ${fraction})`;
    switch (agg) {
        case 'stddev':
            return `STDDEV_SAMP(${id})`;
        case 'variance':
            return `VAR_SAMP(${id})`;
        case 'count':
            return 'COUNT(*)';
        case 'countDistinct':
            return `COUNT(DISTINCT ${id})`;
        case 'avg':
            return `AVG(${id})`;
        case 'min':
            return `MIN(${id})`;
        case 'max':
            return `MAX(${id})`;
        case 'sum':
        default:
            return `SUM(${id})`;
    }
}

/** A stable, identifier-safe measure id like `sum_duration_s` / `count`. */
export function measureId(agg: Aggregation, field: string): string {
    const base = agg === 'count' ? 'count' : `${agg}_${field}`;
    return base.replace(/[^A-Za-z0-9_]/g, '_');
}

/** Build a {@link QueryMeasure} from an aggregation + column. Stamps the structured `{agg, field}` origin
 *  so the server path (M2) can send validated identifiers instead of SQL text. */
export function buildMeasure(agg: Aggregation, field: string, label?: string): QueryMeasure {
    return {
        id: measureId(agg, field),
        expression: aggExpression(agg, field),
        label: label ?? `${agg}(${field})`,
        agg,
        ...(agg === 'count' ? {} : { field }),
    };
}

/** The id join of a ratio measure's column, `MeasureCompiler.Ratio#id` — contract-pinned. */
const RATIO_ID_SEPARATOR: string = MEASURE_CONTRACT.ratio.idSeparator;

/**
 * `recency(field)` (C1, D-AN12): whole days from the latest `field` in the window to the window's as-of date. Offline it
 * is the registered `RECENCY(col, 'asOf')` aggregate (`stat-aggregates.ts`); live it travels as `{agg: 'recency', field}`
 * and the server binds the as-of. Needs a {@link QueryWindow} on the spec, which the server refuses without.
 */
export function recencyMeasure(field: string, asOf: string, label?: string): QueryMeasure {
    return {
        id: `recency_${field}`.replace(/[^A-Za-z0-9_]/g, '_'),
        expression: `RECENCY(${quoteIdent(field)}, '${asOf}')`,
        label: label ?? `recency(${field})`,
        agg: 'recency',
        field,
    };
}

/**
 * A ratio of two aggregates, `num / den` — NULL when the denominator is 0 or empty, as the server's
 * `NULLIF(den, 0)`. Not an `{agg, field}` pair: it keeps the Measure grammar's shape (D10) and the Risk factors'
 * no-free-arithmetic rule (D11) intact by being its own wire list, `ratios`.
 */
export function ratioMeasure(num: QueryMeasure, den: QueryMeasure, label?: string): QueryMeasure {
    return {
        id: `${num.id}${RATIO_ID_SEPARATOR}${den.id}`,
        expression: `CASE WHEN (${den.expression}) IS NULL OR (${den.expression}) = 0 THEN NULL ELSE (${num.expression}) / (${den.expression}) END`,
        label: label ?? `${num.label} / ${den.label}`,
        ratio: { num, den },
    };
}

/**
 * The offline WHERE terms of a {@link QueryWindow}: ISO dates compare as text, so the column's first ten characters
 * (`YYYY-MM-DD`) are held to `<= asOf` and `> asOf − days`. The server compares `CAST(field AS DATE)` — the same days.
 */
export function windowTerms(w: QueryWindow): string[] {
    const date = `SUBSTRING(${quoteIdent(w.field)}, 1, 10)`;
    const terms = [`${date} <= '${w.asOf}'`];
    if (w.days != null) terms.push(`${date} > '${isoDaysBefore(w.asOf, w.days)}'`);
    return terms;
}

/** `asOf` (YYYY-MM-DD) minus `days` whole days, as YYYY-MM-DD — calendar arithmetic in UTC, no zone or DST. */
export function isoDaysBefore(asOf: string, days: number): string {
    const [y, m, d] = asOf.split('-').map(Number);
    return new Date(Date.UTC(y, m - 1, d - days)).toISOString().slice(0, 10);
}

/** The measure a channel value contributes: a named measure's expression verbatim, else `agg(field)`. */
export function channelMeasure(cv: ChannelValue): QueryMeasure {
    if (cv.expression) return { id: channelMeasureId(cv), expression: cv.expression, label: cv.field };
    return buildMeasure(cv.agg ?? 'sum', cv.field);
}

/** The result-column id {@link channelMeasure} selects as — what transformProps reads back. */
export function channelMeasureId(cv: ChannelValue): string {
    if (cv.expression) return cv.field.replace(/[^A-Za-z0-9_]/g, '_');
    return measureId(cv.agg ?? 'sum', cv.field);
}

/**
 * Compile the spec to a DuckDB-style SELECT. With measures it aggregates (GROUP BY the dimensions); without
 * measures it projects the group-by columns raw. `cols` types the WHERE literals (defaults to untyped).
 */
export function compileSpec(spec: QuerySpec, cols: ColumnMeta[] = []): string {
    const dims = spec.groupBy.map(quoteIdent);
    const measureSel = spec.measures.map((m) => `${m.expression} AS ${quoteIdent(m.id)}`);
    const selectList = [...dims, ...measureSel];
    const select = selectList.length ? selectList.join(', ') : '*';
    const from = quoteIdent(spec.sourceName);

    let sql = `SELECT ${select}\nFROM ${from}`;
    const terms: string[] = [];
    const where = spec.filters ? compileWhere(spec.filters, cols) : '';
    if (where) terms.push(spec.window ? `(${where})` : where);
    if (spec.window) terms.push(...windowTerms(spec.window));
    if (terms.length) sql += `\nWHERE ${terms.join(' AND ')}`;
    if (spec.measures.length && spec.groupBy.length) sql += `\nGROUP BY ${dims.join(', ')}`;
    if (spec.orderBy?.length) {
        const ob = spec.orderBy.map((o) => `${quoteIdent(o.field)} ${o.dir.toUpperCase()}`).join(', ');
        sql += `\nORDER BY ${ob}`;
    }
    if (spec.limit != null) sql += `\nLIMIT ${spec.limit}`;
    return sql;
}

/** Compile + run the spec over the supplied rows via offline AlaSQL. */
export function runSpec(
    spec: QuerySpec,
    rows: Record<string, unknown>[],
    cols: ColumnMeta[] = [],
): Promise<SqlRunResult> {
    return runSql(compileSpec(spec, cols), spec.sourceName, rows);
}
