import { formatNumber, NumberFormat } from './number-format';
import type { ResolvedMarker } from './chart-markers';

/**
 * BI-INSIGHTS-1 I-11 / I-7 — the forecast band and anomaly markers on a time-series Widget. The server computes both from
 * the Widget's own `/bi/query` result (`overlay` in the body, D-BI7) and returns, per Measure `m`, the columns
 * `m__fc` / `m__fc_lo` / `m__fc_hi` on new rows after the last bucket, and `m__expected` / `m__z` on every scored row,
 * plus a summary (`overlays[]`) with the backtest or the reason there is none. This file maps the Widget's option to
 * that wire fragment, reads the columns back, turns them into Chart Markers (drawn by the one Chart Markers plugin,
 * D-BI9) and words the caption. Framework-free. The suffixes are pinned to the engine by
 * `contracts/measure-grammar.contract.json` (`overlay`).
 */

/** The stored option (`options.overlay`). `sensitivity` 1 (only the most extreme) … 5 (the most points). */
export interface OverlayOptions {
    forecast?: { horizon?: number; level?: number };
    anomaly?: { sensitivity?: number };
}

/** The Visualization Types the overlays draw on: one value axis over a time axis. */
export const OVERLAY_VIZ_TYPES = ['line', 'bar', 'area'] as const;

export const OVERLAY_SUFFIX = {
    fc: '__fc',
    fcLo: '__fc_lo',
    fcHi: '__fc_hi',
    expected: '__expected',
    z: '__z',
} as const;

/** The |z| that counts as an anomaly at each sensitivity, 1 … 5. */
export const SENSITIVITY_Z: readonly number[] = [5, 4, 3.5, 3, 2.5];
export const DEFAULT_SENSITIVITY = 3;
export const DEFAULT_LEVEL = 0.8;

/** The |z| threshold of a sensitivity (clamped to 1 … 5; absent = the default). */
export function anomalyThreshold(sensitivity: number | undefined): number {
    const s = Math.round(Math.min(5, Math.max(1, sensitivity ?? DEFAULT_SENSITIVITY)));
    return SENSITIVITY_Z[s - 1];
}

/** The wire fragment `{forecast?, anomaly?}` of a Widget's options, or `null` for none. The sensitivity stays here. */
export function overlayFragment(options: { overlay?: OverlayOptions } | undefined): OverlayWire | null {
    const o = options?.overlay;
    if (!o?.forecast && !o?.anomaly) return null;
    return {
        ...(o.forecast
            ? {
                  forecast: {
                      ...(o.forecast.horizon != null ? { horizon: o.forecast.horizon } : {}),
                      ...(o.forecast.level != null ? { level: o.forecast.level } : {}),
                  },
              }
            : {}),
        ...(o.anomaly ? { anomaly: {} } : {}),
    };
}

export interface OverlayWire {
    forecast?: { horizon?: number; level?: number };
    anomaly?: Record<string, never>;
    field?: string;
}

/** One entry of the response's `overlays[]` (`ChartOverlays` summary). */
export interface OverlaySummary {
    overlay: 'forecast' | 'anomaly';
    of: string;
    series: Record<string, unknown>;
    reason?: string;
    method?: string;
    period?: number;
    periodSource?: 'detected' | 'calendar';
    level?: number;
    horizon?: number;
    points?: number;
    seasonality?: string;
    scored?: number;
    backtest?: {
        origins?: number;
        horizon?: number;
        pairs?: number;
        mape?: number | null;
        smape?: number;
        mae?: number;
        coverage?: number | null;
        reason?: string;
    };
}

/** The overlay of one Measure in one row; every part may be absent (never 0). */
export interface OverlayCell {
    fc: number | null;
    lo: number | null;
    hi: number | null;
    expected: number | null;
    z: number | null;
}

/** True when the rows carry the server's overlay columns for measure id `mId`. */
export function hasOverlay(rows: Record<string, unknown>[], mId: string): boolean {
    return rows.some((r) => mId + OVERLAY_SUFFIX.fc in r || mId + OVERLAY_SUFFIX.z in r);
}

export function overlayCell(row: Record<string, unknown> | undefined, mId: string): OverlayCell {
    const n = (k: string): number | null => {
        const v = row?.[mId + k];
        if (v == null || v === '') return null;
        const x = typeof v === 'number' ? v : Number(v);
        return Number.isFinite(x) ? x : null;
    };
    return {
        fc: n(OVERLAY_SUFFIX.fc),
        lo: n(OVERLAY_SUFFIX.fcLo),
        hi: n(OVERLAY_SUFFIX.fcHi),
        expected: n(OVERLAY_SUFFIX.expected),
        z: n(OVERLAY_SUFFIX.z),
    };
}

/** One drawn series with its overlay cells (`VizSeries.overlay`, aligned with its data). */
export interface OverlaidSeries {
    label: string;
    data: readonly number[];
    overlay?: readonly OverlayCell[];
}

/**
 * The overlays as Chart Markers: per series, the forecast band (`interval`, its dashed point forecast drawn with it)
 * and the points whose |z| reaches the sensitivity's threshold (`points`). Each carries its words for the text
 * alternative. A series with no forecast cell draws no band — never a guessed one.
 */
export function resolveOverlays(
    series: readonly OverlaidSeries[],
    labels: readonly string[],
    opts: { overlay?: OverlayOptions; format?: NumberFormat } | undefined,
    sensitivity: number | undefined,
    name: (label: string) => string = (l) => l,
): ResolvedMarker[] {
    const out: ResolvedMarker[] = [];
    const f = (n: number) => formatNumber(n, opts?.format);
    const many = series.filter((s) => s.overlay).length > 1;
    const prefix = (s: OverlaidSeries) => (many ? `${name(s.label)}: ` : '');
    const threshold = anomalyThreshold(sensitivity);
    const level = Math.round((opts?.overlay?.forecast?.level ?? DEFAULT_LEVEL) * 100);
    for (const s of series) {
        const cells = s.overlay;
        if (!cells) continue;
        if (opts?.overlay?.forecast) {
            const points: { index: number; value: number; lower: number; upper: number }[] = [];
            cells.forEach((c, i) => {
                if (c.fc != null && c.lo != null && c.hi != null)
                    points.push({ index: i, value: c.fc, lower: c.lo, upper: c.hi });
            });
            if (points.length) {
                const first = points[0];
                const last = points[points.length - 1];
                out.push({
                    type: 'interval',
                    points,
                    text:
                        `${prefix(s)}Forecast ${labels[first.index]} to ${labels[last.index]}, ${level} % band ` +
                        `${f(Math.min(...points.map((p) => p.lower)))}–${f(Math.max(...points.map((p) => p.upper)))}`,
                });
            }
        }
        if (opts?.overlay?.anomaly) {
            const points: { index: number; value: number }[] = [];
            cells.forEach((c, i) => {
                const v = s.data[i];
                if (c.z != null && Math.abs(c.z) >= threshold && Number.isFinite(v))
                    points.push({ index: i, value: v });
            });
            if (points.length)
                out.push({
                    type: 'points',
                    points,
                    tone: 'error',
                    text: `${prefix(s)}Unusual ${points.length === 1 ? 'value' : 'values'} at ${points
                        .map((p) => `${labels[p.index]} (${f(p.value)}, expected ${f(cells[p.index].expected ?? NaN)})`)
                        .join(', ')}`,
                });
        }
    }
    return out;
}

const pct = (n: number) => formatNumber(n, { decimals: 1 });

/**
 * The caption under the chart: what the forecast is, how well the same model did on this series' history, and how many
 * points are flagged at the chosen sensitivity — or why there is no forecast. One line per overlay, the first series
 * named when there are several.
 */
export function overlayNote(
    summary: readonly OverlaySummary[] | null | undefined,
    flagged: number,
    sensitivity: number | undefined,
    unit = 'bucket',
): string[] {
    if (!summary?.length) return [];
    const lines: string[] = [];
    const fc = summary.filter((s) => s.overlay === 'forecast');
    const series = (s: OverlaySummary) => {
        const v = Object.values(s.series ?? {});
        return v.length && fc.length > 1 ? `${v.join(' · ')}: ` : '';
    };
    for (const s of fc.slice(0, 3)) {
        if (s.reason) {
            lines.push(`${series(s)}No forecast: ${s.reason}.`);
            continue;
        }
        const season =
            s.periodSource === 'detected'
                ? `a ${s.period}-${unit} season found in the data`
                : `a ${s.period}-${unit} calendar season`;
        let text = `${series(s)}Forecast of the next ${s.horizon} ${s.horizon === 1 ? unit : unit + 's'}, ${Math.round((s.level ?? DEFAULT_LEVEL) * 100)} % band (Holt-Winters, ${season}).`;
        const b = s.backtest;
        if (b && !b.reason && b.origins) {
            const err = b.mape != null ? `average error ${pct(b.mape)} %` : `symmetric error ${pct(b.smape ?? NaN)} %`;
            const cover = b.coverage != null ? `, ${pct(b.coverage * 100)} % of actual values inside the band` : '';
            text += ` Tested on this history from ${b.origins} starting ${b.origins === 1 ? 'point' : 'points'}: ${err}${cover}.`;
        } else if (b?.reason) text += ` Not tested: ${b.reason}.`;
        lines.push(text);
    }
    const an = summary.filter((s) => s.overlay === 'anomaly');
    if (an.length) {
        const why = an.find((s) => s.reason && !s.scored);
        if (why && an.every((s) => !s.scored)) lines.push(`No anomaly markers: ${why.reason}.`);
        else {
            const basis = an[0].seasonality === 'weekday' ? 'the same weekday' : 'the other values shown';
            const s = Math.round(Math.min(5, Math.max(1, sensitivity ?? DEFAULT_SENSITIVITY)));
            lines.push(
                `${flagged === 0 ? 'No' : flagged} unusual ${flagged === 1 ? 'value' : 'values'} at sensitivity ${s} of 5 ` +
                    `(at least ${formatNumber(anomalyThreshold(s), { decimals: 1 })} robust deviations from ${basis}).`,
            );
        }
    }
    return lines;
}

/** What one bucket of the time axis is called in the caption: a day, a week (labels a week apart) or a month. */
export function grainUnit(labels: readonly string[]): string {
    if (labels.some((l) => /^\d{4}-\d{2}$/.test(l))) return 'month';
    const days = labels.filter((l) => /^\d{4}-\d{2}-\d{2}$/.test(l)).map((l) => Date.parse(l));
    if (days.length >= 2 && days[1] - days[0] >= 7 * 86_400_000) return 'week';
    return days.length ? 'day' : 'bucket';
}
