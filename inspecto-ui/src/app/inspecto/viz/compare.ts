import { formatNumber, NumberFormat } from './number-format';
import type { QuerySpec, VizRenderOptions } from './viz-types';

/**
 * BI-INSIGHTS-1 I-1 — compare on any Widget. The server computes the comparison in the Widget's own `/bi/query`
 * statement (`compare` in the body) and returns, per Measure `m`, the columns `m__cmp` (the compared value), `m__delta`
 * and `m__pct`; this file maps a Widget's option to that wire fragment and reads the columns back. Framework-free.
 * The kinds and suffixes are pinned to the engine by `contracts/measure-grammar.contract.json` (`compare`).
 */
export type CompareKind = 'previous' | 'lastYear' | 'target' | 'average';

export const COMPARE_KINDS: readonly CompareKind[] = ['previous', 'lastYear', 'target', 'average'];

export const COMPARE_SUFFIX = { cmp: '__cmp', delta: '__delta', pct: '__pct' } as const;

/** What the baseline is called after "vs": "▲ Up 12.4 % (+15) vs previous period". */
export const COMPARE_LABEL: Record<CompareKind, string> = {
    previous: 'previous period',
    lastYear: 'same period last year',
    target: 'target',
    average: 'average',
};

/** The Visualization Types whose query carries a comparison (the cog dialog offers the option only for these). */
export const COMPARE_VIZ_TYPES = ['line', 'bar', 'area', 'kpi'] as const;

/**
 * The `compare` fragment of a Widget's query, or `null` for none. A target comparison needs a target — the Widget's
 * own (`options.kpi.target`) — and is dropped without one rather than sent to fail. A KPI-bound tile (`kpi.kpiId`)
 * reads its numbers from the KPI definition instead of a query: the KPI tile compares it with the definition's target.
 */
export function compareFragment(options: VizRenderOptions | undefined): QuerySpec['compare'] | null {
    const kind = options?.compare?.kind;
    if (!kind || !COMPARE_KINDS.includes(kind)) return null;
    if (kind !== 'target') return { kind };
    const target = options?.kpi?.target;
    return target != null && Number.isFinite(target) ? { kind, target } : null;
}

/** The comparison of one Measure in one result row: the compared value, the change and the % change (each may be absent). */
export interface CompareCell {
    cmp: number | null;
    delta: number | null;
    pct: number | null;
}

/** True when the rows carry the server's comparison columns for measure id `mId`. */
export function hasCompare(rows: Record<string, unknown>[], mId: string): boolean {
    return rows.some((r) => mId + COMPARE_SUFFIX.cmp in r);
}

/** The comparison cells of `mId` in `row`; an absent comparison stays `null`, never 0. */
export function compareCell(row: Record<string, unknown> | undefined, mId: string): CompareCell {
    const n = (k: string): number | null => {
        const v = row?.[mId + k];
        if (v == null || v === '') return null;
        const x = typeof v === 'number' ? v : Number(v);
        return Number.isFinite(x) ? x : null;
    };
    return { cmp: n(COMPARE_SUFFIX.cmp), delta: n(COMPARE_SUFFIX.delta), pct: n(COMPARE_SUFFIX.pct) };
}

/**
 * "▲ Up 12.4 % (+15) vs previous period" — the server's change in words and arrows, never colour alone (WCAG 1.4.1).
 * No compared value: "No previous period". A zero baseline has no %: the amount alone. A percent-styled value changes
 * in points.
 */
export function compareText(cell: CompareCell, kind: CompareKind, format?: NumberFormat): string {
    const vs = COMPARE_LABEL[kind];
    if (cell.cmp == null || cell.delta == null) return `No ${vs}`;
    if (cell.delta === 0) return `No change vs ${vs}`;
    const up = cell.delta > 0;
    const arrow = up ? '▲ Up' : '▼ Down';
    if (format?.style === 'percent')
        return `${arrow} ${formatNumber(Math.abs(cell.delta), { decimals: 1 })} pts vs ${vs}`;
    const pct = cell.pct == null ? '' : ` ${formatNumber(Math.abs(cell.pct), { decimals: 1 })} %`;
    const amount = formatNumber(Math.abs(cell.delta), { ...format, compact: true });
    return `${arrow}${pct} (${up ? '+' : '−'}${amount}) vs ${vs}`;
}
