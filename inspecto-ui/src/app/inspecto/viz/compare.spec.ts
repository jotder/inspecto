import { describe, expect, it } from 'vitest';
import MEASURE_CONTRACT from 'app/inspecto/contracts/measure-grammar.contract.json';
import { COMPARE_KINDS, COMPARE_SUFFIX, compareCell, compareFragment, compareText, hasCompare } from './compare';
import { biQueryBody } from './dataset-result.service';
import { buildValueQuery, buildXyQuery, transformValue, transformXy } from './plugins/plugin-helpers';
import { ControlValues } from './viz-types';

/** BI-INSIGHTS-1 I-1 — compare on any Widget: the option → the `/bi/query` fragment → the server's columns read back. */
describe('compare (I-1)', () => {
    it('takes its kinds and column suffixes from the contract the engine is pinned to', () => {
        // MeasureGrammarContractTest (Java) compares the same file with MeasureCompiler.COMPARE_KINDS / CMP / DELTA / PCT.
        expect(MEASURE_CONTRACT.compare.kinds).toEqual([...COMPARE_KINDS]);
        expect(MEASURE_CONTRACT.compare.suffixes).toEqual(COMPARE_SUFFIX);
    });

    it('maps the option to the wire fragment, and drops a target comparison with no target', () => {
        expect(compareFragment(undefined)).toBeNull();
        expect(compareFragment({ compare: { kind: 'previous' } })).toEqual({ kind: 'previous' });
        expect(compareFragment({ compare: { kind: 'target' } })).toBeNull();
        expect(compareFragment({ compare: { kind: 'target' }, kpi: { target: 50 } })).toEqual({
            kind: 'target',
            target: 50,
        });
    });

    const LINE: ControlValues = {
        x: [{ field: 'at', grain: 'month' }],
        y: [{ field: 'amount', agg: 'sum' }],
    };

    it('sends the comparison on the XY query body, and nothing without one', () => {
        const spec = buildXyQuery(LINE, {
            datasetId: 'sales',
            sourceName: 'sales',
            options: { compare: { kind: 'lastYear' } },
        });
        expect(biQueryBody(spec)?.compare).toEqual({ kind: 'lastYear' });
        const plain = buildXyQuery(LINE, { datasetId: 'sales', sourceName: 'sales' });
        expect('compare' in plain).toBe(false);
        expect(biQueryBody(plain)).not.toHaveProperty('compare');
    });

    it('drops a hidden-aggregate category order under a comparison (the server would refuse it)', () => {
        const spec = buildXyQuery(LINE, {
            datasetId: 'sales',
            sourceName: 'sales',
            options: { compare: { kind: 'previous' }, categoryOrder: { field: 'stage_order', dir: 'asc' } },
        });
        expect(spec.orderBy?.some((o) => o.agg)).toBe(false);
    });

    const ROWS = [
        { at: '2026-01', sum_amount: 10, sum_amount__cmp: null, sum_amount__delta: null, sum_amount__pct: null },
        { at: '2026-02', sum_amount: 20, sum_amount__cmp: 10, sum_amount__delta: 10, sum_amount__pct: 100 },
        { at: '2026-03', sum_amount: 15, sum_amount__cmp: 20, sum_amount__delta: -5, sum_amount__pct: -25 },
    ];

    it('draws the compared series with a gap where there is nothing to compare with, never 0', () => {
        const p = transformXy(ROWS, LINE);
        expect(p.series).toHaveLength(2);
        expect(p.series[1]['compareOf']).toBe(p.series[0].label);
        expect(Number.isNaN(p.series[1].data[0])).toBe(true);
        expect(p.series[1].data.slice(1)).toEqual([10, 20]);
        expect(p.series[0]['compare']).toEqual([
            { cmp: null, delta: null, pct: null },
            { cmp: 10, delta: 10, pct: 100 },
            { cmp: 20, delta: -5, pct: -25 },
        ]);
        expect(
            transformXy(
                ROWS.map(({ at, sum_amount }) => ({ at, sum_amount })),
                LINE,
            ).series,
        ).toHaveLength(1);
    });

    it('states the change and % in words, and an absent baseline as absent', () => {
        expect(compareText(compareCell(ROWS[1], 'sum_amount'), 'previous')).toBe(
            '▲ Up 100.0 % (+10) vs previous period',
        );
        expect(compareText(compareCell(ROWS[2], 'sum_amount'), 'lastYear')).toBe(
            '▼ Down 25.0 % (−5) vs same period last year',
        );
        expect(compareText(compareCell(ROWS[0], 'sum_amount'), 'previous')).toBe('No previous period');
        expect(compareText({ cmp: 0, delta: 4, pct: null }, 'target')).toBe('▲ Up (+4) vs target');
        expect(hasCompare(ROWS, 'sum_amount')).toBe(true);
        expect(hasCompare(ROWS, 'count')).toBe(false);
    });

    it('lets a KPI compare with its target in the same one-row query', () => {
        const kpi: ControlValues = { value: [{ field: 'amount', agg: 'sum' }] };
        const spec = buildValueQuery(kpi, {
            datasetId: 'sales',
            sourceName: 'sales',
            options: { compare: { kind: 'target' }, kpi: { target: 40 } },
        });
        expect(spec.compare).toEqual({ kind: 'target', target: 40 });
        // a KPI has no time axis or groups: the previous period has nothing to look along, so it is not sent
        expect(
            buildValueQuery(kpi, {
                datasetId: 'sales',
                sourceName: 'sales',
                options: { compare: { kind: 'previous' } },
            }).compare,
        ).toBeUndefined();
        expect(transformValue([{ sum_amount: 45, sum_amount__cmp: 40 }], kpi).compare).toBe(40);
        expect(transformValue([{ sum_amount: 45, sum_amount__cmp: null }], kpi).compare).toBeUndefined();
    });
});
