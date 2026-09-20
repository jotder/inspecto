import { describe, expect, it } from 'vitest';
import { G6GraphData } from './graph-types';
import { attrColumns, isNumericColumn, isTemporalColumn, workingSetStats } from './working-set-stats';

const FULL: G6GraphData = {
    nodes: ['a', 'b', 'c'].map((id) => ({ id, data: { label: id, kind: 'entity' } })),
    edges: [
        {
            id: 'ab',
            source: 'a',
            target: 'b',
            data: { kind: 'wire', count: 3, attrs: { amount: '1,500', booked_at: '2026-01-04', channel: 'wire' } },
        },
        {
            id: 'bc',
            source: 'b',
            target: 'c',
            data: { kind: 'wire', count: 2, attrs: { amount: '500', booked_at: '2026-03-31', channel: 'crypto' } },
        },
    ] as unknown as G6GraphData['edges'],
};

describe('workingSetStats', () => {
    it('detects numeric and temporal columns and ignores categorical ones', () => {
        expect(attrColumns(FULL)).toEqual(['amount', 'booked_at', 'channel']);
        expect(isNumericColumn(FULL, 'amount')).toBe(true);
        expect(isNumericColumn(FULL, 'channel')).toBe(false);
        expect(isTemporalColumn(FULL, 'booked_at')).toBe(true);
        expect(isTemporalColumn(FULL, 'amount')).toBe(false);
    });

    it('reports shown / loaded, folded rows, a measure sum and the time span', () => {
        const shown: G6GraphData = { nodes: FULL.nodes.slice(0, 2), edges: FULL.edges.slice(0, 1) };
        const stats = workingSetStats(shown, FULL);
        expect(stats.map((s) => [s.label, s.value])).toEqual([
            ['Nodes', '2 / 3'],
            ['Links', '1 / 2'],
            ['Folded rows', '3'],
            ['Σ amount', '1.5K'],
            ['booked_at', '2026-01-04 → 2026-01-04'],
        ]);
    });

    it('collapses the ratio when everything is shown, and honours profile labels and columns', () => {
        const stats = workingSetStats(FULL, FULL, {
            labels: { nodes: 'Accounts', links: 'Transfers' },
            measureColumns: ['amount'],
            timeColumn: 'booked_at',
        });
        expect(stats[0]).toMatchObject({ label: 'Accounts', value: '3' });
        expect(stats[1]).toMatchObject({ label: 'Transfers', value: '2' });
        expect(stats.find((s) => s.label === 'Σ amount')?.value).toBe('2K');
        expect(stats.find((s) => s.label === 'booked_at')?.value).toBe('2026-01-04 → 2026-03-31');
    });

    it('is empty without a loaded graph', () => {
        expect(workingSetStats(null, null)).toEqual([]);
    });
});
