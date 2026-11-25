import { describe, expect, it } from 'vitest';
import MEASURE_CONTRACT from 'app/inspecto/contracts/measure-grammar.contract.json';
import {
    anomalyThreshold,
    grainUnit,
    OVERLAY_SUFFIX,
    overlayCell,
    overlayFragment,
    overlayNote,
    OverlaySummary,
    resolveOverlays,
} from './chart-overlays';
import { biQueryBody } from './dataset-result.service';
import { buildXyQuery, transformXy } from './plugins/plugin-helpers';
import { ControlValues } from './viz-types';

/** BI-INSIGHTS-1 I-11 / I-7 — the option → the `/bi/query` fragment → the server's columns → Chart Markers and words. */
describe('chart overlays (I-11, I-7)', () => {
    it('reads its column suffixes from the contract the engine is pinned to', () => {
        // MeasureGrammarContractTest (Java) compares the same file with ChartOverlays.FC / FC_LO / FC_HI / EXPECTED / Z.
        expect(MEASURE_CONTRACT.overlay.suffixes).toEqual(OVERLAY_SUFFIX);
    });

    it('names the time bucket by the labels the compiler returns', () => {
        expect(grainUnit(['2026-01-05', '2026-01-06'])).toBe('day');
        expect(grainUnit(['2026-01-05', '2026-01-12'])).toBe('week');
        expect(grainUnit(['2026-01', '2026-02'])).toBe('month');
        expect(grainUnit(['North', 'South'])).toBe('bucket');
    });

    it('sends the forecast and a bare anomaly request; the sensitivity stays in the browser', () => {
        expect(overlayFragment(undefined)).toBeNull();
        expect(overlayFragment({ overlay: {} })).toBeNull();
        expect(overlayFragment({ overlay: { forecast: { level: 0.95 }, anomaly: { sensitivity: 5 } } })).toEqual({
            forecast: { level: 0.95 },
            anomaly: {},
        });
        expect(anomalyThreshold(undefined)).toBe(3.5);
        expect(anomalyThreshold(1)).toBe(5);
        expect(anomalyThreshold(9)).toBe(2.5);
    });

    const day: ControlValues = { x: [{ field: 'ts', grain: 'day' }], y: [{ field: 'amount', agg: 'sum' }] };
    const ctx = { datasetId: 'd', sourceName: 's', options: { overlay: { forecast: {} } } };

    it('asks the server only along a grained date, so a stale option never refuses a Widget', () => {
        const spec = buildXyQuery(day, ctx);
        expect(spec.overlay).toEqual({ forecast: {} });
        expect(biQueryBody(spec)?.overlay).toEqual({ forecast: {} });
        const plain = buildXyQuery({ ...day, x: [{ field: 'region' }] }, ctx);
        expect('overlay' in plain).toBe(false);
        expect(biQueryBody(plain)).not.toHaveProperty('overlay');
    });

    const rows = [
        { ts: '2026-01-01', sum_amount: 10, sum_amount__z: 0.2, sum_amount__expected: 10, sum_amount__fc: null },
        { ts: '2026-01-02', sum_amount: 90, sum_amount__z: 6.1, sum_amount__expected: 11, sum_amount__fc: null },
        { ts: '2026-01-03', sum_amount: null, sum_amount__fc: 12, sum_amount__fc_lo: 8, sum_amount__fc_hi: 16 },
    ];

    it('leaves a forecast day without an actual (NaN, never 0) and carries every cell on the series', () => {
        const p = transformXy(rows, day);
        expect(p.labels).toEqual(['2026-01-01', '2026-01-02', '2026-01-03']);
        expect(p.series[0].data[2]).toBeNaN();
        expect(p.series[0]['overlay']).toEqual(rows.map((r) => overlayCell(r, 'sum_amount')));
        expect(overlayCell(rows[0], 'sum_amount').fc).toBeNull();
    });

    it('turns the cells into a forecast interval and the flagged points at the chosen sensitivity', () => {
        const p = transformXy(rows, day);
        const series = [{ label: 'x', data: p.series[0].data, overlay: p.series[0]['overlay'] as never }];
        const opts = { overlay: { forecast: {}, anomaly: {} } };
        const marks = resolveOverlays(series, p.labels, opts, 3);
        expect(marks[0]).toEqual({
            type: 'interval',
            points: [{ index: 2, value: 12, lower: 8, upper: 16 }],
            text: 'Forecast 2026-01-03 to 2026-01-03, 80 % band 8–16',
        });
        expect(marks[1]).toMatchObject({ type: 'points', tone: 'error', points: [{ index: 1, value: 90 }] });
        expect(marks[1].text).toBe('Unusual value at 2026-01-02 (90, expected 11)');
        // sensitivity 1 asks for |z| >= 5 — 6.1 still counts; with the anomaly option off nothing is ringed
        expect(resolveOverlays(series, p.labels, opts, 1)).toHaveLength(2);
        expect(resolveOverlays(series, p.labels, { overlay: { forecast: {} } }, 5).map((m) => m.type)).toEqual([
            'interval',
        ]);
    });

    it('words the forecast, its tested accuracy and the flagged count — or why there is no forecast', () => {
        const ok: OverlaySummary = {
            overlay: 'forecast',
            of: 'sum_amount',
            series: {},
            period: 7,
            periodSource: 'detected',
            horizon: 7,
            level: 0.8,
            backtest: { origins: 22, mape: 4.12, smape: 4, coverage: 0.79 },
        };
        const an: OverlaySummary = {
            overlay: 'anomaly',
            of: 'sum_amount',
            series: {},
            seasonality: 'weekday',
            scored: 40,
        };
        expect(overlayNote([ok, an], 1, 3, 'day')).toEqual([
            'Forecast of the next 7 days, 80 % band (Holt-Winters, a 7-day season found in the data). Tested on this ' +
                'history from 22 starting points: average error 4.1 %, 79.0 % of actual values inside the band.',
            '1 unusual value at sensitivity 3 of 5 (at least 3.5 robust deviations from the same weekday).',
        ]);
        const none: OverlaySummary = {
            overlay: 'forecast',
            of: 'sum_amount',
            series: {},
            reason: 'a forecast needs two seasons (14 days of a 7-day season); the series has 9',
        };
        expect(overlayNote([none], 0, 3, 'day')).toEqual([
            'No forecast: a forecast needs two seasons (14 days of a 7-day season); the series has 9.',
        ]);
        expect(overlayNote(null, 0, 3)).toEqual([]);
    });
});
