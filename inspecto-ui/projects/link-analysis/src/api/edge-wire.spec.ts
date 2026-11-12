import { describe, expect, it } from 'vitest';
import { decodeEdges, type EdgeWire } from './edge-wire';
import type { ProjectionResult } from './inv.service';

/** The pre-P10-1 object shape, as the server sent it for the same read: the parity reference. */
const OLD: ProjectionResult = {
    masking: { mode: 'typed', masked: true, basis: 'b' },
    rows: [
        { source: 'm:1', target: 'm:2', kind: 'voice', count: 7, total: 12.5, attrs: { via: 'sim', cell: null } },
        { source: 'm:2', target: 'm:3', kind: null, count: 1, total: null, attrs: { via: null, cell: 'c9' } },
    ],
    truncated: true,
    columnTypes: { a_msisdn: { id: 'msisdn', normaliser: 'digits' } } as unknown as ProjectionResult['columnTypes'],
    source: { kind: 'index', version: 3, stale: false },
};

/** The compact v2 body for the same read. */
const WIRE: EdgeWire = {
    masking: OLD.masking,
    columns: ['source', 'target', 'kind', 'count', 'total', 'via', 'cell'],
    attrCols: ['via', 'cell'],
    rows: [
        ['m:1', 'm:2', 'voice', 7, 12.5, 'sim', null],
        ['m:2', 'm:3', null, 1, null, null, 'c9'],
    ],
    truncated: true,
    columnTypes: OLD.columnTypes,
    source: OLD.source,
};

describe('decodeEdges (PERF-P10-1 compact edge wire format)', () => {
    it('decodes to exactly the old object shape (parity), keeping masking, truncated, columnTypes and source', () => {
        expect(decodeEdges(WIRE)).toEqual(OLD);
    });

    it('a read without a Measure or attribute columns has no total and no attrs key', () => {
        const got = decodeEdges({ columns: ['source', 'target', 'kind', 'count'], attrCols: [], rows: [['a', 'b', null, 2]], truncated: false });
        expect(got.rows).toEqual([{ source: 'a', target: 'b', kind: null, count: 2 }]);
        expect('total' in got.rows[0]).toBe(false);
        expect('attrs' in got.rows[0]).toBe(false);
        expect('columns' in got).toBe(false);
    });

    it('attribute columns follow count directly when there is no total', () => {
        const got = decodeEdges({ columns: ['source', 'target', 'kind', 'count', 'via'], attrCols: ['via'], rows: [['a', 'b', 'k', 1, 'x']], truncated: false });
        expect(got.rows[0]).toEqual({ source: 'a', target: 'b', kind: 'k', count: 1, attrs: { via: 'x' } });
    });

    it('an attribute column named total is an attribute, not the Measure total', () => {
        const got = decodeEdges({ columns: ['source', 'target', 'kind', 'count', 'total'], attrCols: ['total'], rows: [['a', 'b', null, 3, 'gold']], truncated: false });
        expect(got.rows[0]).toEqual({ source: 'a', target: 'b', kind: null, count: 3, attrs: { total: 'gold' } });
    });

    it('an empty read decodes to no rows', () => {
        expect(decodeEdges({ columns: ['source', 'target', 'kind', 'count'], attrCols: [], rows: [], truncated: false }).rows).toEqual([]);
    });
});
