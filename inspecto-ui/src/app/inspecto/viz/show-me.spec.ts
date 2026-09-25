import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { isolateViz, registerViz } from './viz-registry';
import { recommend, autoAssignChannels } from './show-me';
import { BAR_PLUGIN, BUBBLE_PLUGIN, BUILTIN_VIZ_PLUGINS, PIE_PLUGIN, TABLE_PLUGIN } from './plugins';
import { ControlSpec, VizField, VizPlugin } from './viz-types';

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
