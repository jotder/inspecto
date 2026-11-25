import { formatNumber, NumberFormat } from './number-format';

/**
 * Chart Markers (BI-INSIGHTS-1 I-2): reference lines and target bands drawn over a Widget's chart. This file is the
 * framework-free core — the stored shape, the resolver that turns it into concrete lines and bands with their text,
 * and the text alternative. The Chart.js overlay that draws them is `chart-markers.plugin.ts` (D-BI9).
 *
 * Every marker carries words (its label, with its value), so a reader never has to decode a colour (WCAG 1.4.1).
 */

/** The Visualization Types a Chart Marker draws on: one vertical value axis over categories or time. */
export const MARKER_VIZ_TYPES: readonly string[] = ['line', 'bar', 'area', 'combo'];

/** A band's tone — the status tones, so a band reads the same meaning a status badge gives it. */
export type MarkerTone = 'success' | 'warning' | 'error' | 'info';

/**
 * One Chart Marker as a Widget stores it (`options.markers`).
 * - `constant`: a line at `value`.
 * - `target`: a line at the target — `value` when set, else the bound KPI's target (`options.kpi.kpiId`), else the
 *   Widget's own `options.kpi.target`.
 * - `average` / `percentile` (`p`, 1–99): a line at that statistic of the values drawn on the value axis.
 * - `band`: a shaded range `from`–`to` in a tone; an absent end runs to the edge of the chart.
 * - `rag`: the bound KPI's green / amber / red bands (`options.kpi.kpiId`); without them, nothing is drawn.
 * `label` replaces the default wording (the value is always added to a line's label).
 */
export type ChartMarker =
    | { kind: 'constant'; value: number; label?: string }
    | { kind: 'target'; value?: number; label?: string }
    | { kind: 'average'; label?: string }
    | { kind: 'percentile'; p: number; label?: string }
    | { kind: 'band'; from?: number; to?: number; tone: MarkerTone; label?: string }
    | { kind: 'rag'; label?: string };

export type ChartMarkerKind = ChartMarker['kind'];

/**
 * A marker ready to draw: a line at a value, or a band between two values (an open end = the chart's edge). The two
 * per-category kinds come from the server's overlays (BI-INSIGHTS-1 I-11 / I-7, `chart-overlays.ts`): an `interval`
 * (a forecast band and its dashed point forecast, by category index) and `points` (rings on the flagged values).
 */
export type ResolvedMarker =
    | { type: 'line'; value: number; text: string; tone?: MarkerTone; dashed: boolean }
    | { type: 'band'; from: number | null; to: number | null; text: string; tone: MarkerTone }
    | { type: 'interval'; points: { index: number; value: number; lower: number; upper: number }[]; text: string }
    | { type: 'points'; points: { index: number; value: number }[]; text: string; tone: MarkerTone };

/** The parts of a KPI definition a marker reads (`KpiDefinition` in `api/kpis.service.ts`). */
export interface MarkerKpi {
    title?: string;
    direction?: 'up' | 'down' | 'band';
    target?: number;
    bands?: { green: number | [number, number]; amber: number | [number, number] };
}

export interface MarkerContext {
    /** Every value drawn on the value axis — the population the statistics read. */
    values: readonly number[];
    /** The Widget's own target (`options.kpi.target`). */
    target?: number;
    /** The bound KPI's definition, once read. */
    kpi?: MarkerKpi | null;
    format?: NumberFormat;
}

/** The menu wording of each kind (the editor's picker). */
export const MARKER_KIND_LABEL: Readonly<Record<ChartMarkerKind, string>> = {
    constant: 'Constant line',
    target: 'Target line',
    average: 'Average line',
    percentile: 'Percentile line',
    band: 'Band',
    rag: 'KPI status bands (red, amber, green)',
};

export const MARKER_TONE_LABEL: Readonly<Record<MarkerTone, string>> = {
    success: 'Good (green)',
    warning: 'Watch (amber)',
    error: 'Bad (red)',
    info: 'Neutral (blue)',
};

const finite = (n: unknown): n is number => typeof n === 'number' && Number.isFinite(n);

/** The `p`th percentile (0–100) by linear interpolation between the closest ranks — DuckDB's `quantile_cont`. */
export function percentile(values: readonly number[], p: number): number | null {
    const xs = values.filter(finite).sort((a, b) => a - b);
    if (!xs.length || !finite(p)) return null;
    const rank = (Math.min(100, Math.max(0, p)) / 100) * (xs.length - 1);
    const lo = Math.floor(rank);
    const hi = Math.ceil(rank);
    return xs[lo] + (xs[hi] - xs[lo]) * (rank - lo);
}

export function average(values: readonly number[]): number | null {
    const xs = values.filter(finite);
    return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null;
}

/** "90th", "1st", "22nd", "13th". */
export function ordinal(n: number): string {
    const v = Math.round(n);
    const teen = v % 100 >= 11 && v % 100 <= 13;
    const suffix = teen ? 'th' : (['th', 'st', 'nd', 'rd'][v % 10] ?? 'th');
    return `${v}${suffix}`;
}

/**
 * The KPI's RAG zones as bands, by the server's own rule (`KpiDefinition.rag`): `up` is green at `>= green`, amber at
 * `>= amber`, else red; `down` mirrors it; `band` is green inside the green interval, amber inside the amber one, else
 * red. An open side is the chart's edge. `[]` when the KPI has no bands.
 */
export function kpiBands(kpi: MarkerKpi | null | undefined, format?: NumberFormat): ResolvedMarker[] {
    const b = kpi?.bands;
    if (!b) return [];
    const f = (n: number) => formatNumber(n, format);
    const band = (from: number | null, to: number | null, tone: MarkerTone, word: string): ResolvedMarker => ({
        type: 'band',
        from,
        to,
        tone,
        text: `${word} ${from == null ? `below ${f(to!)}` : to == null ? `from ${f(from)}` : `${f(from)}–${f(to)}`}`,
    });
    if (kpi?.direction === 'band') {
        if (!Array.isArray(b.green) || !Array.isArray(b.amber)) return [];
        const [gLo, gHi] = b.green;
        const [aLo, aHi] = b.amber;
        const out: ResolvedMarker[] = [
            band(null, aLo, 'error', 'Red'),
            band(aLo, gLo, 'warning', 'Amber'),
            band(gLo, gHi, 'success', 'Green'),
            band(gHi, aHi, 'warning', 'Amber'),
            { ...band(aHi, null, 'error', 'Red'), text: `Red above ${f(aHi)}` },
        ];
        return out.filter(hasWidth);
    }
    if (!finite(b.green) || !finite(b.amber)) return [];
    if (kpi?.direction === 'down') {
        // Lower is better: green at or below `green`, amber up to `amber`, red above.
        return [
            { ...band(null, b.green, 'success', 'Green'), text: `Green at or below ${f(b.green)}` },
            band(b.green, b.amber, 'warning', 'Amber'),
            { ...band(b.amber, null, 'error', 'Red'), text: `Red above ${f(b.amber)}` },
        ].filter(hasWidth);
    }
    return [
        band(null, b.amber, 'error', 'Red'),
        band(b.amber, b.green, 'warning', 'Amber'),
        { ...band(b.green, null, 'success', 'Green'), text: `Green at or above ${f(b.green)}` },
    ].filter(hasWidth);
}

/** A zone with an open side, or whose ends differ (the server allows green = amber, leaving no amber zone). */
function hasWidth(m: ResolvedMarker): boolean {
    return m.type !== 'band' || m.from == null || m.to == null || m.from < m.to;
}

/**
 * The stored markers as concrete lines and bands, each with its words. A marker that cannot be placed (no values for a
 * statistic, no target anywhere, a KPI without bands, a band with no finite end) is dropped — never drawn at a guess.
 */
export function resolveMarkers(markers: readonly ChartMarker[] | undefined, ctx: MarkerContext): ResolvedMarker[] {
    const f = (n: number) => formatNumber(n, ctx.format);
    const out: ResolvedMarker[] = [];
    const line = (
        value: number | null,
        word: string,
        label: string | undefined,
        dashed: boolean,
        tone?: MarkerTone,
    ) => {
        if (value == null || !finite(value)) return;
        out.push({
            type: 'line',
            value,
            text: `${label?.trim() || word} ${f(value)}`,
            dashed,
            ...(tone ? { tone } : {}),
        });
    };
    for (const m of markers ?? []) {
        switch (m.kind) {
            case 'constant':
                line(m.value, 'Reference', m.label, true);
                break;
            case 'target': {
                const value = finite(m.value) ? m.value : finite(ctx.kpi?.target) ? ctx.kpi!.target! : ctx.target;
                line(value ?? null, 'Target', m.label, false, 'info');
                break;
            }
            case 'average':
                line(average(ctx.values), 'Average', m.label, true);
                break;
            case 'percentile':
                // A whole percentile only: its words ("90th") must name the value drawn.
                if (Number.isInteger(m.p) && m.p > 0 && m.p < 100)
                    line(percentile(ctx.values, m.p), `${ordinal(m.p)} percentile`, m.label, true);
                break;
            case 'band': {
                const from = finite(m.from) ? m.from : null;
                const to = finite(m.to) ? m.to : null;
                // No end, or both ends equal: a band of no width is nothing to draw or to read out.
                if ((from == null && to == null) || (from != null && from === to)) break;
                const [lo, hi] = from != null && to != null && from > to ? [to, from] : [from, to];
                const range = lo == null ? `below ${f(hi!)}` : hi == null ? `from ${f(lo)}` : `${f(lo)}–${f(hi)}`;
                out.push({
                    type: 'band',
                    from: lo,
                    to: hi,
                    tone: m.tone,
                    text: `${m.label?.trim() || 'Band'} ${range}`,
                });
                break;
            }
            case 'rag': {
                const bands = kpiBands(ctx.kpi, ctx.format);
                const name = m.label?.trim();
                out.push(...(name ? bands.map((b) => ({ ...b, text: `${name}: ${b.text}` })) : bands));
                break;
            }
        }
    }
    return out;
}

/** The markers as one sentence for the canvas's text alternative ("Markers: Target 99; Green from 95."). */
export function markerAltText(markers: readonly ResolvedMarker[]): string {
    return markers.length ? `Markers: ${markers.map((m) => m.text).join('; ')}.` : '';
}

/** Whether a stored marker needs the bound KPI's definition to resolve. */
export function needsKpi(markers: readonly ChartMarker[] | undefined): boolean {
    return (markers ?? []).some((m) => m.kind === 'rag' || (m.kind === 'target' && !finite(m.value)));
}
