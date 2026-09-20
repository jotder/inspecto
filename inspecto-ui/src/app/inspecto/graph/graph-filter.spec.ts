import { describe, expect, it } from 'vitest';
import { ConditionGroup } from '../query/query-types';
import { G6GraphData } from './graph-types';
import {
    cloneGroup,
    edgeRows,
    endpointGroup,
    filterEdgesByPredicate,
    hasConditions,
    markStranded,
    predicateColumns,
} from './graph-filter';

const COLS = { sourceCol: 'payer_id', targetCol: 'payee_id' };
const G: G6GraphData = {
    nodes: [
        { id: 'entity:A', data: { label: 'A', kind: 'entity' } },
        { id: 'entity:B', data: { label: 'B', kind: 'entity' } },
        { id: 'entity:C', data: { label: 'C', kind: 'entity' } },
    ],
    edges: [
        { id: 'ab', source: 'entity:A', target: 'entity:B', data: { kind: 'wire', attrs: { amount: '500' } } },
        { id: 'bc', source: 'entity:B', target: 'entity:C', data: { kind: 'crypto', attrs: { amount: '20000' } } },
    ],
};
const where = (items: ConditionGroup['items'], op: 'AND' | 'OR' = 'AND'): ConditionGroup => ({
    kind: 'group',
    op,
    items,
});

describe('graph-filter (stage 1 of the two-stage loop)', () => {
    it('shapes an edge as a row with endpoint labels under the projection column names', () => {
        expect(edgeRows(G, COLS)[0]).toEqual({
            amount: '500',
            payer_id: 'A',
            payee_id: 'B',
            kind: 'wire',
            count: 1,
            __edge: 'ab',
        });
        expect(predicateColumns(G, COLS).map((c) => `${c.name}:${c.type}`)).toEqual([
            'payer_id:string',
            'payee_id:string',
            'amount:number',
            'kind:string',
            'count:number',
        ]);
    });

    it('an empty or half-built tree is no constraint; a complete one keeps matching edges and their nodes', () => {
        expect(hasConditions(where([]))).toBe(false);
        expect(hasConditions(where([{ kind: 'condition', field: 'amount', operator: '>=', value: '' }]))).toBe(false);
        expect(filterEdgesByPredicate(G, where([]), COLS)).toBe(G);

        const big = filterEdgesByPredicate(
            G,
            where([{ kind: 'condition', field: 'amount', operator: '>=', value: '10000' }]),
            COLS,
        );
        expect(big.edges.map((e) => e.id)).toEqual(['bc']);
        expect(big.nodes.map((n) => n.id)).toEqual(['entity:B', 'entity:C']);
    });

    it('translates a node filter into an OR over both endpoint columns, which matches either side', () => {
        const grp = endpointGroup('A', COLS);
        expect(grp).toEqual({
            kind: 'group',
            op: 'OR',
            items: [
                { kind: 'condition', field: 'payer_id', operator: '=', value: 'A' },
                { kind: 'condition', field: 'payee_id', operator: '=', value: 'A' },
            ],
        });
        expect(filterEdgesByPredicate(G, where([grp]), COLS).edges.map((e) => e.id)).toEqual(['ab']);
        expect(filterEdgesByPredicate(G, where([endpointGroup('B', COLS)]), COLS).edges.map((e) => e.id)).toEqual([
            'ab',
            'bc',
        ]);
    });

    it('marks nodes the pushed-down result excluded as stranded instead of dropping them, and un-marks returnees', () => {
        const next: G6GraphData = { nodes: [G.nodes[1], G.nodes[2]], edges: [G.edges[1]] };
        const merged = markStranded(G, next);
        expect(merged.nodes.map((n) => [n.id, !!n.data.missing])).toEqual([
            ['entity:B', false],
            ['entity:C', false],
            ['entity:A', true],
        ]);
        expect(merged.edges).toEqual([G.edges[1]]);
        // A stranded node survives a later local filter that matches none of its (zero) edges.
        const narrowed = filterEdgesByPredicate(
            merged,
            where([{ kind: 'condition', field: 'kind', operator: '=', value: 'crypto' }]),
            COLS,
        );
        expect(narrowed.nodes.some((n) => n.id === 'entity:A' && n.data.missing)).toBe(true);
        // Coming back clears the mark.
        expect(markStranded(merged, G).nodes.every((n) => !n.data.missing)).toBe(true);
    });

    it('cloneGroup detaches the editor-mutated tree', () => {
        const a = where([{ kind: 'condition', field: 'kind', operator: '=', value: 'wire' }]);
        const b = cloneGroup(a);
        (b.items[0] as { value?: string }).value = 'crypto';
        expect((a.items[0] as { value?: string }).value).toBe('wire');
    });
});
