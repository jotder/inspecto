import { describe, expect, it } from 'vitest';
import type { BiQueryBody } from 'app/inspecto/api/bi-query.service';
import { explainRequest } from './explain-request';

const COLS = [
    { name: 'ts', role: 'temporal' },
    { name: 'region', role: 'dimension' },
    { name: 'plan', role: 'dimension' },
    { name: 'msisdn', role: 'entity' },
    { name: 'note', role: 'dimension', hidden: true },
    { name: 'amount', role: 'measure' },
    { name: 'cost', role: 'measure' },
];

const BODY: BiQueryBody = {
    dataset: 'sales_ds',
    measures: [{ agg: 'sum', field: 'amount' }],
    groupBy: ['ts', 'plan'],
    grains: { ts: 'month' },
    filters: [{ field: 'region', op: '!=', value: 'X' }],
};

describe('explainRequest', () => {
    it('explains the last month by the Dataset dimensions, the grouped one first', () => {
        const out = explainRequest(BODY, COLS, '2026-02');
        expect('request' in out).toBe(true);
        if (!('request' in out)) return;
        expect(out.request).toEqual({
            dataset: 'sales_ds',
            field: 'ts',
            grain: 'month',
            at: '2026-02-01',
            dimensions: ['plan', 'region', 'msisdn'],
            related: [{ agg: 'sum', field: 'cost' }],
            filters: [{ field: 'region', op: '!=', value: 'X' }],
            measure: { agg: 'sum', field: 'amount' },
        });
    });

    it('takes a ratio when there is no plain Measure', () => {
        const out = explainRequest(
            { ...BODY, measures: undefined, ratios: [{ num: { agg: 'sum', field: 'amount' }, den: { agg: 'count' } }] },
            COLS,
            '2026-02-03',
        );
        expect('request' in out && out.request.ratio?.den).toEqual({ agg: 'count' });
        expect('request' in out && out.request.at).toBe('2026-02-03');
    });

    it('says why in words when a Widget cannot be explained', () => {
        expect(explainRequest({ ...BODY, grains: {} }, COLS, '2026-02')).toEqual({
            reason: 'Explain needs a chart over time: one date grouped by day, week or month.',
        });
        expect('reason' in explainRequest({ ...BODY, query: 'q1' }, COLS, '2026-02')).toBe(true);
        expect('reason' in explainRequest(BODY, COLS, undefined)).toBe(true);
        expect('reason' in explainRequest(null, COLS, '2026-02')).toBe(true);
        expect('reason' in explainRequest(BODY, [{ name: 'ts', role: 'temporal' }], '2026-02')).toBe(true);
    });
});
