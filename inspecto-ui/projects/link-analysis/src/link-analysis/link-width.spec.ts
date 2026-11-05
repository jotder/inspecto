import { describe, expect, it } from 'vitest';
import type { G6GraphData, GraphSourceQuery } from '@inspecto/core/graph';
import {
    MEASURE_WEIGHT_CEILING,
    carriesLinkMeasure,
    linkWidthHint,
    sharedLinkMeasure,
    weighLinks,
    withLinkMeasure,
} from './link-width';

/** LA-COLUMN-ROLES-2 D-2: link width by the rows a link folds, or by its link mapping's primary Measure. */
const graph = (): G6GraphData => ({
    nodes: [],
    edges: [
        { id: 'big', source: 'a', target: 'b', data: { kind: 'paid', count: 2, total: 5000 } as never },
        { id: 'small', source: 'a', target: 'c', data: { kind: 'paid', count: 7, total: 5 } as never },
        { id: 'none', source: 'a', target: 'd', data: { kind: 'paid', count: 1, total: null } as never },
        { id: 'refund', source: 'a', target: 'e', data: { kind: 'paid', count: 1, total: -40 } as never },
        { id: 'hop', source: 'a', target: 'f', data: { kind: 'path' } },
    ],
});
const weights = (g: G6GraphData | null) =>
    Object.fromEntries(g!.edges.map((e) => [e.id, (e.data as { weight?: number }).weight]));

describe('weighLinks', () => {
    it('Count weighs every link by its row count, and leaves a link without one unweighted', () => {
        expect(weights(weighLinks(graph(), 'count'))).toEqual({ big: 2, small: 7, none: 1, refund: 1, hop: undefined });
    });

    it('the Measure weighs a link by its total relative to the largest; no number (or none above zero) is thinnest', () => {
        const w = weights(weighLinks(graph(), 'measure'));
        expect(w['big']).toBe(MEASURE_WEIGHT_CEILING);
        expect(w['small']).toBeCloseTo((MEASURE_WEIGHT_CEILING * 5) / 5000);
        expect(w['none']).toBe(0);
        expect(w['refund']).toBe(0);
        expect(w['hop']).toBe(0);
    });

    it('a canvas whose links hold no number draws them all thinnest, and no graph stays none', () => {
        const g: G6GraphData = {
            nodes: [],
            edges: [{ id: 'x', source: 'a', target: 'b', data: { kind: 'k', total: null } as never }],
        };
        expect(weights(weighLinks(g, 'measure'))).toEqual({ x: 0 });
        expect(weighLinks(null, 'measure')).toBeNull();
    });

    it('never mutates the graph it is given', () => {
        const g = graph();
        weighLinks(g, 'count');
        expect('weight' in g.edges[0].data).toBe(false);
    });
});

describe('sharedLinkMeasure', () => {
    const measures: Record<string, string | undefined> = {
        ledger: 'amount',
        wires: 'amount',
        calls: 'duration',
        plain: undefined,
    };
    const measureOf = (id: string) => measures[id];
    const edge = (dataset: string) => ({ dataset, sourceColumn: 's', targetColumn: 't' });

    it("is the loaded Dataset's primary Measure on a one-Dataset read", () => {
        const q: GraphSourceQuery = { projection: { datasetId: 'ledger', sourceCol: 's', targetCol: 't' } };
        expect(sharedLinkMeasure('entity-projection', q, measureOf)).toBe('amount');
    });

    it('is undefined when the Dataset names no primary Measure, or the source is not a link projection', () => {
        const plain: GraphSourceQuery = { projection: { datasetId: 'plain', sourceCol: 's', targetCol: 't' } };
        expect(sharedLinkMeasure('entity-projection', plain, measureOf)).toBeUndefined();
        expect(sharedLinkMeasure('lineage', plain, measureOf)).toBeUndefined();
        expect(sharedLinkMeasure('entity-projection', {}, measureOf)).toBeUndefined();
    });

    it('over several Datasets: only a Measure they all name alike', () => {
        const same: GraphSourceQuery = { multi: { nodes: [], edges: [edge('ledger'), edge('wires')] } };
        const mixed: GraphSourceQuery = { multi: { nodes: [], edges: [edge('ledger'), edge('calls')] } };
        const partly: GraphSourceQuery = { multi: { nodes: [], edges: [edge('ledger'), edge('plain')] } };
        expect(sharedLinkMeasure('entity-projection-multi', same, measureOf)).toBe('amount');
        expect(sharedLinkMeasure('entity-projection-multi', mixed, measureOf)).toBeUndefined();
        expect(sharedLinkMeasure('entity-projection-multi', partly, measureOf)).toBeUndefined();
    });
});

describe('withLinkMeasure / carriesLinkMeasure', () => {
    it('names the column on a projection (measureCol) and on every multi edge mapping (measureColumn)', () => {
        const single: GraphSourceQuery = { projection: { datasetId: 'ledger', sourceCol: 's', targetCol: 't' } };
        const multi: GraphSourceQuery = {
            multi: { nodes: [], edges: [{ dataset: 'ledger', sourceColumn: 's', targetColumn: 't' }] },
        };
        expect(withLinkMeasure(single, 'amount').projection?.measureCol).toBe('amount');
        expect(withLinkMeasure(multi, 'amount').multi?.edges[0].measureColumn).toBe('amount');
        expect(single.projection?.measureCol).toBeUndefined(); // the query it was given is untouched
        expect(carriesLinkMeasure(single, 'amount')).toBe(false);
        expect(carriesLinkMeasure(withLinkMeasure(single, 'amount'), 'amount')).toBe(true);
        expect(carriesLinkMeasure(withLinkMeasure(multi, 'amount'), 'amount')).toBe(true);
        expect(carriesLinkMeasure(withLinkMeasure(single, 'fee'), 'amount')).toBe(false);
    });
});

describe('linkWidthHint', () => {
    it('says what the width means in each mode', () => {
        expect(linkWidthHint('count', 'amount')).toBe('Thicker links fold more rows.');
        expect(linkWidthHint('measure', 'amount')).toContain('larger total of amount');
        expect(linkWidthHint('measure', undefined)).toBe('Thicker links fold more rows.');
    });
});
