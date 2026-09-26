import { describe, expect, it } from 'vitest';
import { statusBadgeClasses } from 'app/inspecto/components/status-badge.component';
import {
    cellPaint,
    divergingLevel,
    heatDomain,
    pivotHeatmap,
    rampColor,
    sequentialLevel,
    statusCellTone,
    worstCell,
} from './heatmap';
import { HEATMAP_MAX_CELLS, HEATMAP_PLUGIN } from './plugins';
import { allViz } from './viz-registry';

describe('heatmap — pivot', () => {
    const rows = [
        { control: 'RA-C10', day: '2026-09-02', v: 5 },
        { control: 'RA-C02', day: '2026-09-01', v: 1 },
        { control: 'RA-C02', day: '2026-09-02', v: 2 },
        { control: 'RA-C10', day: '2026-09-01', v: '3' },
    ];

    it('pivots rows x columns, sorted by label, numeric strings as numbers', () => {
        const m = pivotHeatmap(rows, 'control', 'day', 'v');
        expect(m.rows).toEqual(['RA-C02', 'RA-C10']);
        expect(m.columns).toEqual(['2026-09-01', '2026-09-02']);
        expect(m.cells).toEqual([
            [1, 2],
            [3, 5],
        ]);
    });

    it('a pair with no source row is an empty cell (null), never a zero', () => {
        const m = pivotHeatmap(
            [...rows.slice(0, 2), { control: 'RA-C99', day: '2026-09-03', v: 0 }],
            'control',
            'day',
            'v',
        );
        expect(m.columns).toEqual(['2026-09-01', '2026-09-02', '2026-09-03']);
        expect(m.cells[0]).toEqual([1, null, null]); // RA-C02 only has 09-01
        expect(m.cells[2]).toEqual([null, null, 0]); // a measured zero stays 0
    });

    it('sorts numeric-aware (2 before 10) and keeps status words as text', () => {
        const m = pivotHeatmap(
            [
                { h: '10', d: 'Mon', s: 'Fail' },
                { h: '2', d: 'Mon', s: 'Pass' },
            ],
            'h',
            'd',
            's',
        );
        expect(m.rows).toEqual(['2', '10']);
        expect(m.cells).toEqual([['Pass'], ['Fail']]);
    });

    it('domain spans the numbers only; none when the matrix holds none', () => {
        expect(heatDomain(pivotHeatmap(rows, 'control', 'day', 'v'))).toEqual({ min: 1, max: 5 });
        expect(heatDomain({ rows: ['a'], columns: ['b'], cells: [['Pass']] })).toBeNull();
    });
});

describe('heatmap — colour scales (pure)', () => {
    it('sequential level runs 0 → 1 across the domain, clamped; a flat domain is strong', () => {
        expect(sequentialLevel(1, 1, 5)).toBe(0);
        expect(sequentialLevel(3, 1, 5)).toBe(0.5);
        expect(sequentialLevel(5, 1, 5)).toBe(1);
        expect(sequentialLevel(9, 1, 5)).toBe(1);
        expect(sequentialLevel(4, 4, 4)).toBe(1);
    });

    it('diverging level is signed around the midpoint with one shared span', () => {
        expect(divergingLevel(0, -10, 5, 0)).toBe(0);
        expect(divergingLevel(-10, -10, 5, 0)).toBe(-1);
        expect(divergingLevel(5, -10, 5, 0)).toBe(0.5); // same span both sides: 5 is half as far as -10
        expect(divergingLevel(110, 90, 110, 100)).toBe(1);
    });

    it('ramp colours are --gamma-* tokens, light at the low end, strong at the high end', () => {
        expect(rampColor('primary', 0)).toBe('rgba(var(--gamma-primary-rgb), 0.08)');
        expect(rampColor('primary', 1)).toBe('rgba(var(--gamma-primary-rgb), 0.92)');
        const low = cellPaint(1, 'sequential', { min: 1, max: 5 });
        const high = cellPaint(5, 'sequential', { min: 1, max: 5 });
        expect(low).toEqual({ background: rampColor('primary', 0), classes: '', strong: false });
        expect(high.strong).toBe(true);
        expect(high.classes).toBe('text-on-primary'); // on-colour ink over a strong fill
    });

    it('diverging paints below the midpoint in the warn hue, above in primary, the midpoint blank', () => {
        const d = { min: -10, max: 10 };
        expect(cellPaint(-10, 'diverging', d).background).toBe(rampColor('warn', 1));
        expect(cellPaint(-10, 'diverging', d).classes).toBe('text-on-warn');
        expect(cellPaint(5, 'diverging', d).background).toBe(rampColor('primary', 0.5));
        expect(cellPaint(0, 'diverging', d).background).toBeNull();
        expect(cellPaint(98, 'diverging', { min: 95, max: 102 }, { midpoint: 99 }).background).toBe(
            rampColor('warn', 0.25),
        );
    });

    it('an empty cell is never painted, whatever the scale', () => {
        for (const scale of ['sequential', 'diverging', 'status'] as const)
            expect(cellPaint(null, scale, { min: 0, max: 1 })).toEqual({
                background: null,
                classes: '',
                strong: false,
            });
    });
});

describe('heatmap — status scale', () => {
    it('a status word takes the status badge tone classes (the RAG matrix)', () => {
        for (const w of ['Pass', 'Warning', 'Fail', 'Green', 'Amber', 'Red']) {
            const p = cellPaint(w, 'status', null);
            expect(p.classes).toBe(statusBadgeClasses(w));
            expect(p.background).toBeNull();
        }
        expect(cellPaint('Fail', 'status', null).tone).toBe('error');
        expect(cellPaint('Amber', 'status', null).tone).toBe('warning');
        expect(cellPaint('Pass', 'status', null).tone).toBe('success');
        expect(cellPaint('whatever', 'status', null).tone).toBe('neutral');
    });

    it('a number is judged against the target in either direction; no target = neutral', () => {
        expect(statusCellTone(99.2, 99)).toBe('success');
        expect(statusCellTone(98.1, 99)).toBe('error');
        expect(statusCellTone(3, 5, 'lower')).toBe('success');
        expect(statusCellTone(7, 5, 'lower')).toBe('error');
        expect(statusCellTone(7)).toBe('neutral');
        expect(cellPaint(98.1, 'status', { min: 0, max: 100 }, { target: 99 }).classes).toBe(
            statusBadgeClasses('FAIL'),
        );
    });
    it('a cell folds to its WORST status by tone severity, not alphabetically; numbers keep max / min', () => {
        expect(worstCell('Warning', 'Fail', 'max')).toBe('Fail');
        expect(worstCell('Amber', 'Red', 'min')).toBe('Red');
        expect(worstCell('Pass', 'whatever', 'max')).toBe('Pass');
        expect(worstCell(null, 'Pass', 'max')).toBe('Pass');
        expect(worstCell('Pass', null, 'min')).toBe('Pass');
        expect(worstCell(null, 'whatever', 'max')).toBe('whatever');
        expect(worstCell(null, 3, 'min')).toBe(3);
        // One tone, two words: the same answer whichever row came first.
        expect(worstCell('FAILED', 'Fail', 'max')).toBe(worstCell('Fail', 'FAILED', 'max'));
        expect(worstCell(3, 7, 'max')).toBe(7);
        expect(worstCell(3, 7, 'min')).toBe(3);
    });
});

describe('HEATMAP_PLUGIN', () => {
    const values = {
        rows: [{ field: 'control' }],
        columns: [{ field: 'event_date', grain: 'day' as const }],
        value: [{ field: 'breaks', agg: 'sum' as const }],
    };

    it('is registered as a built-in Visualization Type with rows / columns / value channels', () => {
        expect(allViz().map((p) => p.meta.type)).toContain('heatmap');
        expect(HEATMAP_PLUGIN.controls.map((c) => c.channel)).toEqual(['rows', 'columns', 'value']);
        expect(HEATMAP_PLUGIN.meta.fit).toEqual({ minDim: 2, maxDim: 2, minMeasure: 1, maxMeasure: 1 });
    });

    it('groups by both dimensions with one aggregate, carrying a picked grain', () => {
        const q = HEATMAP_PLUGIN.buildQuery(values, { datasetId: 'control_runs', sourceName: 'control_runs' });
        expect(q.groupBy).toEqual(['control', 'event_date']);
        expect(q.grains).toEqual({ event_date: 'day' });
        expect(q.measures.map((m) => m.id)).toEqual(['sum_breaks']);
    });

    it('asks for every cell in a stable order, never the server 500-row default (R2-01)', () => {
        const q = HEATMAP_PLUGIN.buildQuery(values, { datasetId: 'control_runs', sourceName: 'control_runs' });
        expect(q.limit).toBe(HEATMAP_MAX_CELLS);
        expect(HEATMAP_MAX_CELLS).toBeGreaterThan(500);
        expect(q.orderBy).toEqual([
            { field: 'control', dir: 'asc' },
            { field: 'event_date', dir: 'asc' },
        ]);
    });

    it('transforms result rows into the matrix with readable channel names', () => {
        const p = HEATMAP_PLUGIN.transformProps(
            [
                { control: 'B', event_date: '2026-09-02', sum_breaks: 4 },
                { control: 'A', event_date: '2026-09-01', sum_breaks: 1 },
            ],
            values,
        );
        expect(p.heatmap?.rows).toEqual(['A', 'B']);
        expect(p.heatmap?.cells).toEqual([
            [1, null],
            [null, 4],
        ]);
        expect(p.heatmap?.rowLabel).toBe('Control');
        expect(p.heatmap?.columnLabel).toBe('Event date');
        expect(p.labels).toEqual(['A', 'B']);
    });

    describe("a status column on the status scale shows each cell's WORST status", () => {
        const statusValues = (agg: 'max' | 'min') => ({ ...values, value: [{ field: 'status', agg }] });
        const ctx = {
            datasetId: 'control_runs',
            sourceName: 'control_runs',
            options: { heatmap: { scale: 'status' as const } },
        };

        it("queries each cell's distinct statuses (count), ordered, under the cell ceiling", () => {
            const q = HEATMAP_PLUGIN.buildQuery(statusValues('max'), ctx);
            expect(q.groupBy).toEqual(['control', 'event_date', 'status']);
            expect(q.grains).toEqual({ event_date: 'day' });
            expect(q.measures.map((m) => m.id)).toEqual(['count']);
            expect(q.orderBy?.map((o) => o.field)).toEqual(['control', 'event_date', 'status']);
            expect(q.limit).toBe(HEATMAP_MAX_CELLS);
        });

        it('Pass + Warning + Fail in one cell reads Fail (alphabetical max would say Warning)', () => {
            const rows = ['Fail', 'Pass', 'Warning'].map((status) => ({
                control: 'A',
                event_date: 'd1',
                status,
                count: 1,
            }));
            rows.push({ control: 'A', event_date: 'd2', status: 'Pass', count: 3 });
            const p = HEATMAP_PLUGIN.transformProps(rows, statusValues('max'));
            expect(p.heatmap?.cells).toEqual([['Fail', 'Pass']]);
        });

        it('RAG Green + Amber + Red reads Red under min too (alphabetical min would say Amber)', () => {
            const rows = ['Amber', 'Green', 'Red'].map((status) => ({
                control: 'A',
                event_date: 'd1',
                status,
                count: 1,
            }));
            expect(HEATMAP_PLUGIN.transformProps(rows, statusValues('min')).heatmap?.cells).toEqual([['Red']]);
        });

        it('a result at the ceiling blanks its last cell, which may be cut mid-way, rather than show a wrong worst', () => {
            const rows: Record<string, unknown>[] = Array.from({ length: HEATMAP_MAX_CELLS - 1 }, (_, i) => ({
                control: 'A',
                event_date: `d${i}`,
                status: 'Pass',
                count: 1,
            }));
            rows.push({ control: 'B', event_date: 'd0', status: 'Pass', count: 1 });
            const p = HEATMAP_PLUGIN.transformProps(rows, statusValues('max'));
            expect(p.heatmap?.rows).toEqual(['A']);
            expect(p.heatmap?.cells[0][0]).toBe('Pass');
        });

        it('a numeric heatmap, or max on another scale, queries exactly as before', () => {
            const plain = { groupBy: ['control', 'event_date'], limit: HEATMAP_MAX_CELLS };
            const sum = HEATMAP_PLUGIN.buildQuery(values, ctx);
            expect(sum).toMatchObject(plain);
            expect(sum.measures.map((m) => m.id)).toEqual(['sum_breaks']);
            expect(sum).toEqual(
                HEATMAP_PLUGIN.buildQuery(values, { datasetId: 'control_runs', sourceName: 'control_runs' }),
            );
            const seq = { ...ctx, options: { heatmap: { scale: 'sequential' as const } } };
            const max = HEATMAP_PLUGIN.buildQuery(statusValues('max'), seq);
            expect(max).toMatchObject(plain);
            expect(max.measures.map((m) => m.id)).toEqual(['max_status']);
            // …and its rows (the server's aggregate) pivot as before.
            const p = HEATMAP_PLUGIN.transformProps(
                [{ control: 'A', event_date: 'd1', max_status: 'Warning' }],
                statusValues('max'),
            );
            expect(p.heatmap?.cells).toEqual([['Warning']]);
        });
    });

    it('an incomplete mapping yields no matrix', () => {
        expect(HEATMAP_PLUGIN.transformProps([{ a: 1 }], { rows: [{ field: 'a' }] }).heatmap).toBeUndefined();
    });
});
