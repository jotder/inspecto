import type { Chart, Plugin } from 'chart.js';
import { color } from 'chart.js/helpers';
import { CHART_MARKER_BAND_ALPHA, CHART_MARKER_LINE, CHART_TONE } from 'app/inspecto/theme/chart-tokens';
import { ResolvedMarker } from './chart-markers';

/**
 * The Chart Marker overlay (D-BI9: a Chart.js plugin behind the viz contract, not custom drawing outside it). One
 * inline plugin per chart, built from already-resolved markers (`chart-markers.ts`), so nothing passes through
 * Chart.js's option resolver. It
 * - widens the value axis `y` so every marker value and finite band edge is on the chart (`afterDataLimits`),
 * - shades bands BEHIND the data (`beforeDatasetsDraw`),
 * - draws lines OVER the data, each with its label in words (`afterDatasetsDraw`).
 * Colours: `CHART_TONE` / `CHART_MARKER_LINE` only (canvas cannot read CSS variables); label text in the theme's muted
 * text colour, handed in by the chart host.
 */
export const MARKER_PLUGIN_ID = 'inspectoMarkers';

export function markerColor(m: ResolvedMarker): string {
    return m.tone ? CHART_TONE[m.tone] : CHART_MARKER_LINE;
}

/** The axis range that keeps every marker on the chart: the data's range widened to each marker value. */
export function markerLimits(
    markers: readonly ResolvedMarker[],
    min: number,
    max: number,
): { min: number; max: number } {
    let lo = min;
    let hi = max;
    for (const m of markers) {
        const vs = m.type === 'line' ? [m.value] : [m.from, m.to];
        for (const v of vs) {
            if (v == null) continue;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
    }
    return { min: lo, max: hi };
}

type MarkerChart = Pick<Chart, 'chartArea' | 'ctx'> & {
    scales: Record<string, { id?: string; getPixelForValue(v: number): number } | undefined>;
};

/** The overlay for one chart. `textColor` is the theme's muted text (the tick colour); `font` its family. */
export function chartMarkersPlugin(markers: readonly ResolvedMarker[], textColor: string, font: string): Plugin {
    const bands = markers.filter((m) => m.type === 'band');
    const lines = markers.filter((m) => m.type === 'line');
    return {
        id: MARKER_PLUGIN_ID,
        afterDataLimits: (_chart, args) => {
            const scale = (args as unknown as { scale: { id: string; min: number; max: number } }).scale;
            if (scale.id !== 'y' || !markers.length) return;
            const { min, max } = markerLimits(markers, scale.min, scale.max);
            scale.min = min;
            scale.max = max;
        },
        beforeDatasetsDraw: (chart) => drawBands(chart as unknown as MarkerChart, bands, textColor, font),
        afterDatasetsDraw: (chart) => drawLines(chart as unknown as MarkerChart, lines, textColor, font),
    };
}

/** Shade each band across the plot area; its words sit at the band's top-left inside the area. */
export function drawBands(chart: MarkerChart, bands: readonly ResolvedMarker[], textColor: string, font: string): void {
    const y = chart.scales['y'];
    const area = chart.chartArea;
    if (!y || !area || !bands.length) return;
    const ctx = chart.ctx;
    ctx.save();
    ctx.font = `11px ${font}`;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'top';
    for (const b of bands) {
        if (b.type !== 'band') continue;
        const top = clamp(b.to == null ? area.top : y.getPixelForValue(b.to), area.top, area.bottom);
        const bottom = clamp(b.from == null ? area.bottom : y.getPixelForValue(b.from), area.top, area.bottom);
        if (bottom - top < 1) continue;
        ctx.fillStyle = color(markerColor(b)).alpha(CHART_MARKER_BAND_ALPHA).rgbString();
        ctx.fillRect(area.left, top, area.right - area.left, bottom - top);
        if (bottom - top >= 14) {
            ctx.fillStyle = textColor;
            ctx.fillText(b.text, area.left + 4, top + 2);
        }
    }
    ctx.restore();
}

/** Draw each line across the plot area (dashed for a statistic or constant, solid for a target) and its words just
 *  above it at the right end. */
export function drawLines(chart: MarkerChart, lines: readonly ResolvedMarker[], textColor: string, font: string): void {
    const y = chart.scales['y'];
    const area = chart.chartArea;
    if (!y || !area || !lines.length) return;
    const ctx = chart.ctx;
    ctx.save();
    ctx.font = `600 11px ${font}`;
    ctx.textAlign = 'right';
    ctx.textBaseline = 'bottom';
    // The vertical spans the labels already drawn take, so two close lines do not print one label over the other.
    const placed: [number, number][] = [];
    const free = (top: number) => placed.every(([t, b]) => top + LABEL_HEIGHT <= t || top >= b);
    for (const l of lines) {
        if (l.type !== 'line') continue;
        const py = y.getPixelForValue(l.value);
        if (py < area.top - 0.5 || py > area.bottom + 0.5) continue;
        ctx.strokeStyle = markerColor(l);
        ctx.lineWidth = l.dashed ? 1.5 : 2;
        ctx.setLineDash(l.dashed ? [6, 4] : []);
        ctx.beginPath();
        ctx.moveTo(area.left, py);
        ctx.lineTo(area.right, py);
        ctx.stroke();
        ctx.fillStyle = textColor;
        // Above the line, unless that is cut off by the top edge or taken by another label; then under it.
        const aboveTop = py - 3 - LABEL_HEIGHT;
        const belowTop = py + 3;
        const fitsAbove = aboveTop >= area.top;
        // Both sides taken (two lines a few pixels apart): stack the label clear of every label drawn so far.
        const stacked = Math.min(...placed.map(([t]) => t)) - LABEL_HEIGHT;
        const top =
            fitsAbove && free(aboveTop)
                ? aboveTop
                : free(belowTop)
                  ? belowTop
                  : stacked >= area.top
                    ? stacked
                    : Math.max(...placed.map(([, b]) => b));
        placed.push([top, top + LABEL_HEIGHT]);
        ctx.textBaseline = 'top';
        ctx.fillText(l.text, area.right - 4, top);
    }
    ctx.restore();
}

/** A line label's height in pixels (11 px text plus its leading). */
const LABEL_HEIGHT = 13;

function clamp(v: number, lo: number, hi: number): number {
    return Math.min(hi, Math.max(lo, v));
}
