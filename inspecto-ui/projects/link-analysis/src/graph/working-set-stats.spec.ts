import { describe, expect, it } from 'vitest';
import { G6GraphData } from '@inspecto/core/graph/graph-types';
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
            ['Total amount', '1.5K'],
            ['Time span', '2026-01-04 → 2026-01-04'],
        ]);
    });

    it('leaves folded rows out when each link is one row: it would repeat the links count under a second name', () => {
        const one = { ...FULL, edges: FULL.edges.map((e) => ({ ...e, data: { ...e.data, count: 1 } })) } as G6GraphData;
        const labels = workingSetStats(one, one, { labels: { links: 'Transfers', rows: 'Wallet transactions' } }).map((s) => s.label);
        expect(labels).toContain('Transfers');
        expect(labels).not.toContain('Wallet transactions');
    });

    it('collapses the ratio when everything is shown, and honours profile labels and columns', () => {
        const stats = workingSetStats(FULL, FULL, {
            labels: { nodes: 'Accounts', links: 'Transfers' },
            measureColumns: ['amount'],
            timeColumn: 'booked_at',
        });
        expect(stats[0]).toMatchObject({ label: 'Accounts', value: '3' });
        expect(stats[1]).toMatchObject({ label: 'Transfers', value: '2' });
        expect(stats.find((s) => s.label === 'Total amount')?.value).toBe('2K');
        expect(stats.find((s) => s.label === 'Time span')?.value).toBe('2026-01-04 → 2026-03-31');
    });

    it('never sums identifiers: long digit strings (IMEI, MSISDN) or leading-zero codes are not measures', () => {
        const ids: G6GraphData = {
            nodes: FULL.nodes,
            edges: [
                {
                    id: 'x',
                    source: 'a',
                    target: 'b',
                    data: { kind: 'sms', attrs: { via: '356938035643809', events: '4', code: '0042' } },
                },
                {
                    id: 'y',
                    source: 'b',
                    target: 'c',
                    data: { kind: 'sms', attrs: { via: '249912345678', events: '2', code: '17' } },
                },
            ] as unknown as G6GraphData['edges'],
        };
        expect(isNumericColumn(ids, 'via')).toBe(false);
        expect(isNumericColumn(ids, 'code')).toBe(false);
        expect(isNumericColumn(ids, 'events')).toBe(true);
        expect(workingSetStats(ids, ids).map((s) => s.label)).toEqual(['Nodes', 'Links', 'Total events']);
    });

    it('is empty without a loaded graph', () => {
        expect(workingSetStats(null, null)).toEqual([]);
    });
});
