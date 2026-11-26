import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { isolateViz, registerViz } from './viz-registry';
import { recommend, autoAssignChannels, compareSuggestion, suggestOverlays } from './show-me';
import { BAR_PLUGIN, BUBBLE_PLUGIN, KPI_PLUGIN, BUILTIN_VIZ_PLUGINS, PIE_PLUGIN, TABLE_PLUGIN } from './plugins';
import { channelMeasureId } from './query-spec';
import { ControlSpec, VizField, VizPlugin, VizRenderOptions } from './viz-types';

function plugin(type: string, fit: VizPlugin['meta']['fit'], controls: ControlSpec[]): VizPlugin {
    return {
        meta: { type, label: type, icon: 'x', fit },
        controls,
        buildQuery: (_v, ctx) => ({ datasetId: ctx.datasetId, sourceName: ctx.sourceName, groupBy: [], measures: [] }),
        transformProps: () => ({ labels: [], series: [] }),
        render: { kind: 'chartjs', chartType: type },
    };
}

const LINE = plugin('line', { minMeasure: 1, temporal: true }, [
    { channel: 'x', label: 'Time', acceptRoles: ['temporal', 'dimension'] },
    { channel: 'y', label: 'Measure', acceptRoles: ['measure'], isMeasure: true },
]);
const BAR = plugin('bar', { minMeasure: 1, minDim: 1 }, [
    { channel: 'x', label: 'Category', acceptRoles: ['dimension'] },
    { channel: 'y', label: 'Measure', acceptRoles: ['measure'], isMeasure: true },
]);
const PIE = plugin('pie', { minMeasure: 1, minDim: 1, maxDim: 1, maxCardinality: 8 }, [
    { channel: 'x', label: 'Slice by', acceptRoles: ['dimension'] },
    { channel: 'y', label: 'Measure', acceptRoles: ['measure'], isMeasure: true },
]);
const TABLE = plugin('table', {}, []);

const TEMPORAL_FIELDS: VizField[] = [
    { name: 'event_time', type: 'date', role: 'temporal' },
    { name: 'duration_s', type: 'number', role: 'measure' },
];
const CATEGORICAL_FIELDS: VizField[] = [
    { name: 'tariff', type: 'string', role: 'dimension' },
    { name: 'duration_s', type: 'number', role: 'measure' },
];

describe('recommend', () => {
    let restoreViz: () => void;
    beforeEach(() => {
        restoreViz = isolateViz(); // start from just these fakes, and put the shared (per-worker) registry back after
        registerViz(LINE);
        registerViz(BAR);
        registerViz(PIE);
        registerViz(TABLE);
    });
    afterEach(() => restoreViz());

    it('ranks line first when a temporal field is present', () => {
        const ranked = recommend(TEMPORAL_FIELDS).map((p) => p.meta.type);
        expect(ranked[0]).toBe('line');
        expect(ranked).toContain('table');
    });

    it('disqualifies line (needs temporal) for purely categorical fields', () => {
        const ranked = recommend(CATEGORICAL_FIELDS).map((p) => p.meta.type);
        expect(ranked).not.toContain('line');
        expect(ranked).toContain('bar');
    });

    it('prefers pie for a low-cardinality dimension', () => {
        const fields: VizField[] = [{ ...CATEGORICAL_FIELDS[0], cardinality: 4 }, CATEGORICAL_FIELDS[1]];
        const ranked = recommend(fields).map((p) => p.meta.type);
        expect(ranked[0]).toBe('pie');
    });

    it('demotes pie for a high-cardinality dimension without disqualifying it', () => {
        const fields: VizField[] = [{ ...CATEGORICAL_FIELDS[0], cardinality: 500 }, CATEGORICAL_FIELDS[1]];
        const ranked = recommend(fields).map((p) => p.meta.type);
        expect(ranked[0]).not.toBe('pie'); // no longer the top pick once it has too many slices
        expect(ranked).toContain('pie'); // but still offered — maxCardinality penalises, never disqualifies
    });
});

describe('autoAssignChannels', () => {
    it('maps temporal→x and measure→y (with a default agg)', () => {
        const values = autoAssignChannels(LINE, TEMPORAL_FIELDS);
        expect(values.x?.[0].field).toBe('event_time');
        expect(values.y?.[0]).toEqual({ field: 'duration_s', agg: 'sum' });
    });

    it('maps dimension→x when there is no temporal field', () => {
        const values = autoAssignChannels(BAR, CATEGORICAL_FIELDS);
        expect(values.x?.[0].field).toBe('tariff');
        expect(values.y?.[0].field).toBe('duration_s');
    });

    // KPI-DEFAULT-COMPARE-1: a new KPI must not bind an unrelated second measure as its prior period.
    it('leaves a new KPI widget with no compare, so it renders no delta badge', () => {
        const fields: VizField[] = [
            { name: 'goals', type: 'number', role: 'measure' },
            { name: 'shots', type: 'number', role: 'measure' },
        ];
        const values = autoAssignChannels(KPI_PLUGIN, fields);
        expect(values.value?.[0]).toEqual({ field: 'goals', agg: 'sum' });
        expect(values.compare).toBeUndefined();
        const props = KPI_PLUGIN.transformProps(
            [{ [channelMeasureId({ field: 'goals' })]: 10, [channelMeasureId({ field: 'shots' })]: 40 }],
            values,
        );
        expect(props.compare).toBeUndefined();
    });

    it('keeps an explicitly saved KPI compare', () => {
        const saved = { value: [{ field: 'goals' }], compare: [{ field: 'prev' }] };
        expect(
            KPI_PLUGIN.transformProps(
                [{ [channelMeasureId({ field: 'goals' })]: 10, [channelMeasureId({ field: 'prev' })]: 8 }],
                saved,
            ).compare,
        ).toBe(8);
    });
});

/** The IPL `matches` Dataset from the 2026-09-25 builder pilot: 74 rows, a per-row MATCH_ID, a free-text DATE
 *  that is near-unique, a handful of low-cardinality team/venue dimensions and several numeric measures. */
const CRICKET_ROWS = 74;
const CRICKET_FIELDS: VizField[] = [
    { name: 'MATCH_ID', type: 'number', role: 'dimension', cardinality: 74 },
    { name: 'DATE', type: 'string', role: 'dimension', cardinality: 72 },
    { name: 'VENUE', type: 'string', role: 'dimension', cardinality: 13 },
    { name: 'TEAM1', type: 'string', role: 'dimension', cardinality: 10 },
    { name: 'TEAM2', type: 'string', role: 'dimension', cardinality: 10 },
    { name: 'TOSS_WINNER', type: 'string', role: 'dimension', cardinality: 10 },
    { name: 'TOSS_DECISION', type: 'string', role: 'dimension', cardinality: 2 },
    { name: 'STAGE', type: 'string', role: 'dimension', cardinality: 5 },
    { name: 'MATCH_WINNER', type: 'string', role: 'dimension', cardinality: 10 },
    { name: 'PLAYER_OF_THE_MATCH', type: 'string', role: 'dimension', cardinality: 60 },
    { name: 'FIRST_INGS_SCORE', type: 'number', role: 'measure' },
    { name: 'FIRST_INGS_WKTS', type: 'number', role: 'measure' },
    { name: 'SECOND_INGS_SCORE', type: 'number', role: 'measure' },
    { name: 'SECOND_INGS_WKTS', type: 'number', role: 'measure' },
    { name: 'HIGHSCORE', type: 'number', role: 'measure' },
];

describe('Show-Me over a cricket-shaped Dataset (identifier + near-unique columns)', () => {
    let restoreViz: () => void;
    beforeEach(() => {
        restoreViz = isolateViz();
        BUILTIN_VIZ_PLUGINS.forEach((p) => registerViz(p));
    });
    afterEach(() => restoreViz());

    it('recommends Bar first, ahead of Bubble, when a low-cardinality dimension + a measure exist', () => {
        const ranked = recommend(CRICKET_FIELDS).map((p) => p.meta.type);
        expect(ranked[0]).toBe('bar');
        expect(ranked.indexOf('bar')).toBeLessThan(ranked.indexOf('bubble'));
    });

    it('Bar takes a low-cardinality X, never the per-row MATCH_ID, and leaves Break down by empty', () => {
        const values = autoAssignChannels(BAR_PLUGIN, CRICKET_FIELDS, CRICKET_ROWS);
        expect(values.x?.[0].field).toBe('VENUE'); // the first dimension within the 30-category ceiling
        expect(values.y?.[0]).toEqual({ field: 'FIRST_INGS_SCORE', agg: 'sum' });
        expect(values.series).toBeUndefined(); // no DATE break-down, so no 70-entry legend
    });

    it('Pie slices by a dimension within its 8-slice ceiling', () => {
        const values = autoAssignChannels(PIE_PLUGIN, CRICKET_FIELDS, CRICKET_ROWS);
        expect(values.x?.[0].field).toBe('TOSS_DECISION');
    });

    it('Bubble labels by a real category, not MATCH_ID', () => {
        const values = autoAssignChannels(BUBBLE_PLUGIN, CRICKET_FIELDS, CRICKET_ROWS);
        expect(values.series?.[0].field).toBe('VENUE');
    });

    it('skips a near-unique column by row count even when it is not named like an id', () => {
        const fields: VizField[] = [
            { name: 'DATE', type: 'string', role: 'dimension', cardinality: 72 },
            { name: 'PLAYER_OF_THE_MATCH', type: 'string', role: 'dimension', cardinality: 60 },
            { name: 'FIRST_INGS_SCORE', type: 'number', role: 'measure' },
        ];
        // Nothing fits the ceiling: fall back to the lowest-cardinality dimension that is not unique-per-row.
        expect(autoAssignChannels(BAR_PLUGIN, fields, CRICKET_ROWS).x?.[0].field).toBe('PLAYER_OF_THE_MATCH');
    });

    it('the Table plugin still seeds a dimension (multi-field, optional) — just not the identifier', () => {
        const values = autoAssignChannels(TABLE_PLUGIN, CRICKET_FIELDS, CRICKET_ROWS);
        expect(values.x?.[0].field).toBe('VENUE');
    });
});

describe('suggestOverlays (BI-INSIGHTS-1 I-20)', () => {
    const LINE = { x: [{ field: 'day', grain: 'day' as const }], y: [{ field: 'amount', agg: 'sum' as const }] };
    const FORECAST = {
        overlay: 'forecast' as const,
        of: 'sum_amount',
        series: {},
        period: 7,
        periodSource: 'detected' as const,
        points: 56,
    };
    const ANOMALY = { overlay: 'anomaly' as const, of: 'sum_amount', series: {}, scored: 49 };
    const ROWS = [{ sum_amount__z: 3.2 }, { sum_amount__z: -4 }, { sum_amount__z: 1 }];

    it('words a detected weekly cycle and counts points past the default sensitivity (|z| ≥ 3.5)', () => {
        const out = suggestOverlays({
            vizType: 'line',
            controls: LINE,
            options: {},
            probe: [FORECAST, ANOMALY],
            probeRows: ROWS,
        });
        expect(out.map((s) => s.text)).toEqual([
            'Weekly seasonal pattern found over 56 days of history.',
            '1 unusual point against the usual level.',
        ]);
        expect(out[0].options).toEqual({ overlay: { forecast: {} } });
        expect(out[1].options).toEqual({ overlay: { anomaly: {} } });
    });

    it('is honest: no detected cycle, a refused forecast or no flagged point → no suggestion', () => {
        const probe = [
            { ...FORECAST, periodSource: 'calendar' as const },
            { ...FORECAST, of: 'x', reason: 'two seasons' },
            ANOMALY,
        ];
        expect(
            suggestOverlays({ vizType: 'line', controls: LINE, options: {}, probe, probeRows: [{ sum_amount__z: 1 }] }),
        ).toEqual([]);
        expect(suggestOverlays({ vizType: 'line', controls: LINE, options: {} })).toEqual([]);
    });

    it('never offers an overlay already on, nor on a chart the overlays cannot draw', () => {
        const probe = [FORECAST, ANOMALY];
        const on = { overlay: { forecast: {}, anomaly: {} } };
        expect(suggestOverlays({ vizType: 'line', controls: LINE, options: on, probe, probeRows: ROWS })).toEqual([]);
        expect(
            suggestOverlays({ vizType: 'line', controls: LINE, options: { stacked: true }, probe, probeRows: ROWS }),
        ).toEqual([]);
        expect(suggestOverlays({ vizType: 'pie', controls: LINE, options: {}, probe, probeRows: ROWS })).toEqual([]);
        const ungrained = { ...LINE, x: [{ field: 'day', grain: 'auto' as const }] };
        expect(suggestOverlays({ vizType: 'line', controls: ungrained, options: {}, probe, probeRows: ROWS })).toEqual(
            [],
        );
    });

    it('names the target the marker will draw: the bound KPI first, else the Widget own', () => {
        const both: VizRenderOptions = { kpi: { kpiId: 'revenue', target: 500 } };
        expect(suggestOverlays({ vizType: 'line', controls: {}, options: both, kpi: { target: 1200 } })[0].text).toBe(
            'This KPI has a target of 1,200.',
        );
        expect(suggestOverlays({ vizType: 'line', controls: {}, options: both, kpi: null })[0].text).toBe(
            'This Widget has a target of 500.',
        );
    });

    it('sends no overlay suggestion for a chart with no Measure (the server would refuse the probe)', () => {
        const noMeasure = { x: LINE.x };
        expect(
            suggestOverlays({
                vizType: 'line',
                controls: noMeasure,
                options: {},
                probe: [FORECAST, ANOMALY],
                probeRows: ROWS,
            }),
        ).toEqual([]);
    });

    it('offers the bound KPI target line and its bands, once each', () => {
        const options: VizRenderOptions = { kpi: { kpiId: 'revenue' } };
        const kpi = { target: 1200, bands: { green: 1000, amber: 800 } };
        const out = suggestOverlays({ vizType: 'bar', controls: {}, options, kpi });
        expect(out.map((s) => [s.id, s.text])).toEqual([
            ['target', 'This KPI has a target of 1,200.'],
            ['rag', 'This KPI has green, amber and red bands.'],
        ]);
        expect(out[1].options.markers).toEqual([{ kind: 'rag' }]);
        const done = { ...options, markers: [{ kind: 'target' as const }, { kind: 'rag' as const }] };
        expect(suggestOverlays({ vizType: 'bar', controls: {}, options: done, kpi })).toEqual([]);
        expect(suggestOverlays({ vizType: 'bar', controls: {}, options: {} })).toEqual([]); // no target, no KPI
    });
});

describe('compareSuggestion — Show-Me suggests a compare (BI-INSIGHTS-1 I-1)', () => {
    const LINE = { x: [{ field: 'day', grain: 'day' as const }], y: [{ field: 'amount', agg: 'sum' as const }] };
    const days = (from: string, n: number, step = 1) =>
        Array.from({ length: n }, (_, i) => ({
            day: new Date(Date.parse(from) + i * step * 86_400_000).toISOString().slice(0, 10),
            sum_amount: i,
        }));

    it('a year of history → the same period last year; less → the previous period from 3 buckets', () => {
        expect(compareSuggestion('line', LINE, {}, days('2025-01-01', 53, 7))).toBe('lastYear');
        expect(compareSuggestion('bar', LINE, {}, days('2026-01-01', 30))).toBe('previous');
        expect(compareSuggestion('line', LINE, {}, days('2026-01-01', 2))).toBeNull();
        expect(compareSuggestion('line', LINE, {}, [])).toBeNull();
        // 51 weeks: under a year, still the previous period.
        expect(compareSuggestion('line', LINE, {}, days('2025-01-01', 51, 7))).toBe('previous');
    });

    it('never with a comparison on, a break-down, a non-date axis or a type compare cannot draw', () => {
        const rows = days('2025-01-01', 60, 7);
        expect(compareSuggestion('line', LINE, { compare: { kind: 'average' } }, rows)).toBeNull();
        expect(compareSuggestion('line', { ...LINE, series: [{ field: 'region' }] }, {}, rows)).toBeNull();
        expect(
            compareSuggestion('line', { ...LINE, x: [{ field: 'day', grain: 'auto' as const }] }, {}, rows),
        ).toBeNull();
        expect(compareSuggestion('pie', LINE, {}, rows)).toBeNull();
        expect(compareSuggestion('line', LINE, { stacked: true }, rows)).toBeNull();
    });

    it('suggestOverlays words it and carries the option, applied only by the caller', () => {
        const out = suggestOverlays({ vizType: 'line', controls: LINE, options: {}, rows: days('2025-01-01', 60, 7) });
        expect(out).toHaveLength(1);
        expect(out[0].id).toBe('compare-lastYear');
        expect(out[0].action).toBe('Compare with the same period last year');
        expect(out[0].text).toContain('60 points');
        expect(out[0].options).toEqual({ compare: { kind: 'lastYear' } });
        const prev = suggestOverlays({
            vizType: 'line',
            controls: LINE,
            options: {},
            probeRows: days('2026-01-01', 10),
        });
        expect(prev[0].id).toBe('compare-previous');
        expect(prev[0].action).toBe('Compare with the previous period');
    });
});
