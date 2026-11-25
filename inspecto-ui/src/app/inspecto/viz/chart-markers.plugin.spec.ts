import { describe, expect, it } from 'vitest';
import { color } from 'chart.js/helpers';
import { CHART_MARKER_BAND_ALPHA, CHART_MARKER_LINE, CHART_TONE } from 'app/inspecto/theme/chart-tokens';
import { ResolvedMarker } from './chart-markers';
import {
    chartMarkersPlugin,
    drawBands,
    drawIntervals,
    drawLines,
    drawPoints,
    markerColor,
    markerLimits,
} from './chart-markers.plugin';

/** A canvas context that records what was drawn (jsdom has no 2D context). */
function fakeCtx() {
    const calls: { op: string; args: unknown[]; fill?: string; stroke?: string; dash?: number[] }[] = [];
    let dash: number[] = [];
    const ctx = {
        fillStyle: '',
        strokeStyle: '',
        lineWidth: 1,
        font: '',
        textAlign: '',
        textBaseline: '',
        save: () => undefined,
        restore: () => undefined,
        beginPath: () => undefined,
        setLineDash: (d: number[]) => (dash = d),
        moveTo: (...args: unknown[]) => calls.push({ op: 'moveTo', args }),
        lineTo: (...args: unknown[]) => calls.push({ op: 'lineTo', args }),
        stroke: () => calls.push({ op: 'stroke', args: [], stroke: ctx.strokeStyle, dash }),
        fillRect: (...args: unknown[]) => calls.push({ op: 'fillRect', args, fill: ctx.fillStyle }),
        fillText: (...args: unknown[]) => calls.push({ op: 'fillText', args, fill: ctx.fillStyle }),
        rect: () => undefined,
        clip: () => calls.push({ op: 'clip', args: [] }),
        closePath: () => undefined,
        fill: () => calls.push({ op: 'fill', args: [], fill: ctx.fillStyle }),
        arc: (...args: unknown[]) => calls.push({ op: 'arc', args, stroke: ctx.strokeStyle }),
    };
    return { ctx, calls };
}

/** A 0–100 value axis drawn from pixel 200 (value 0) up to pixel 0 (value 100), plot area x 10–310. */
function fakeChart() {
    const { ctx, calls } = fakeCtx();
    const chart = {
        ctx: ctx as unknown as CanvasRenderingContext2D,
        chartArea: { top: 0, bottom: 200, left: 10, right: 310, width: 300, height: 200 },
        scales: {
            y: { getPixelForValue: (v: number) => 200 - v * 2 },
            x: { getPixelForValue: (i: number) => 10 + i * 30 },
        },
    };
    return { chart: chart as never, calls };
}

const line = (value: number, tone?: 'info'): ResolvedMarker => ({
    type: 'line',
    value,
    text: `Line ${value}`,
    dashed: !tone,
    ...(tone ? { tone } : {}),
});

describe('chart marker overlay', () => {
    it('widens the value axis to every marker and finite band edge', () => {
        const ms: ResolvedMarker[] = [line(120), { type: 'band', from: -5, to: null, tone: 'error', text: 'b' }];
        expect(markerLimits(ms, 0, 100)).toEqual({ min: -5, max: 120 });
        expect(markerLimits([], 0, 100)).toEqual({ min: 0, max: 100 });
    });

    it('afterDataLimits widens the y axis only (a Combo right axis keeps its own range)', () => {
        const plugin = chartMarkersPlugin([line(150)], 'grey', 'Inter');
        const y = { id: 'y', min: 0, max: 100 };
        const y2 = { id: 'y2', min: 0, max: 1 };
        const hook = plugin.afterDataLimits as (c: unknown, a: unknown) => void;
        hook({}, { scale: y });
        hook({}, { scale: y2 });
        expect(y).toEqual({ id: 'y', min: 0, max: 150 });
        expect(y2).toEqual({ id: 'y2', min: 0, max: 1 });
    });

    it('draws a line across the plot area with its words — dashed neutral, solid toned', () => {
        const { chart, calls } = fakeChart();
        drawLines(chart, [line(50), line(80, 'info')], 'grey', 'Inter');
        const strokes = calls.filter((c) => c.op === 'stroke');
        expect(strokes.map((s) => s.stroke)).toEqual([CHART_MARKER_LINE, CHART_TONE.info]);
        expect(strokes.map((s) => s.dash)).toEqual([[6, 4], []]);
        expect(calls.filter((c) => c.op === 'moveTo')[0].args).toEqual([10, 100]);
        const texts = calls.filter((c) => c.op === 'fillText');
        expect(texts.map((t) => t.args[0])).toEqual(['Line 50', 'Line 80']);
        expect(texts.every((t) => t.fill === 'grey')).toBe(true);
    });

    it('puts a label under its line when another label already takes the space above', () => {
        const { chart, calls } = fakeChart();
        // 60 → pixel 80, 62 → pixel 76: the second label above would overlap the first.
        drawLines(chart, [line(60), line(62)], 'grey', 'Inter');
        const tops = calls.filter((c) => c.op === 'fillText').map((t) => t.args[2]);
        expect(tops).toEqual([80 - 3 - 13, 76 + 3]);
    });

    it('stacks a label clear of the others when both sides of its line are taken', () => {
        const { chart, calls } = fakeChart();
        // 60 → pixel 80 (label 64–77); 63.5 → pixel 73: above (57–70) and below (76–89) both overlap it.
        drawLines(chart, [line(60), line(63.5)], 'grey', 'Inter');
        const tops = calls.filter((c) => c.op === 'fillText').map((t) => t.args[2]);
        expect(tops).toEqual([64, 64 - 13]);
    });

    it('puts a label under its line at the top edge', () => {
        const { chart, calls } = fakeChart();
        drawLines(chart, [line(99)], 'grey', 'Inter');
        expect(calls.find((c) => c.op === 'fillText')?.args[2]).toBe(2 + 3);
    });

    it('skips a line outside the plot area', () => {
        const { chart, calls } = fakeChart();
        drawLines(chart, [line(500)], 'grey', 'Inter');
        expect(calls).toEqual([]);
    });

    it('shades a band in its tone and clamps an open side to the edge', () => {
        const { chart, calls } = fakeChart();
        drawBands(
            chart,
            [{ type: 'band', from: 90, to: null, tone: 'success', text: 'Green from 90' }],
            'grey',
            'Inter',
        );
        const rect = calls.find((c) => c.op === 'fillRect')!;
        expect(rect.args).toEqual([10, 0, 300, 20]);
        expect(rect.fill).toBe(color(CHART_TONE.success).alpha(CHART_MARKER_BAND_ALPHA).rgbString());
        expect(calls.find((c) => c.op === 'fillText')?.args[0]).toBe('Green from 90');
    });

    it('takes every colour from the chart tokens', () => {
        expect(markerColor(line(1))).toBe(CHART_MARKER_LINE);
        expect(markerColor({ type: 'band', from: 1, to: 2, tone: 'warning', text: '' })).toBe(CHART_TONE.warning);
    });

    it('BI-INSIGHTS-1 I-11: fills a forecast interval behind the data and dashes its point forecast over it', () => {
        const interval: ResolvedMarker = {
            type: 'interval',
            text: 'Forecast',
            points: [
                { index: 3, value: 50, lower: 40, upper: 60 },
                { index: 4, value: 55, lower: 42, upper: 70 },
            ],
        };
        expect(markerLimits([interval], 45, 50)).toEqual({ min: 40, max: 70 });
        const back = fakeChart();
        drawIntervals(back.chart, [interval], false);
        expect(back.calls.find((c) => c.op === 'clip')).toBeTruthy();
        // upper edge left to right, then lower edge right to left: one closed polygon
        expect(back.calls.filter((c) => c.op === 'moveTo' || c.op === 'lineTo').map((c) => c.args)).toEqual([
            [100, 80],
            [130, 60],
            [130, 116],
            [100, 120],
        ]);
        expect(back.calls.find((c) => c.op === 'fill')?.fill).toBe(
            color(CHART_TONE.info)
                .alpha(CHART_MARKER_BAND_ALPHA * 2)
                .rgbString(),
        );
        const front = fakeChart();
        drawIntervals(front.chart, [interval], true);
        const stroke = front.calls.find((c) => c.op === 'stroke')!;
        expect(stroke.dash).toEqual([6, 4]);
        expect(front.calls.filter((c) => c.op === 'arc')).toHaveLength(2);
    });

    it('BI-INSIGHTS-1 I-7: rings each flagged value in its tone', () => {
        const { chart, calls } = fakeChart();
        drawPoints(chart, [{ type: 'points', tone: 'error', text: 'Unusual', points: [{ index: 2, value: 90 }] }]);
        const ring = calls.find((c) => c.op === 'arc')!;
        expect(ring.args.slice(0, 3)).toEqual([70, 20, 7]);
        expect(ring.stroke).toBe(CHART_TONE.error);
    });
});
