import { describe, expect, it } from 'vitest';
import type { LaDataset } from '@inspecto/link-analysis/la-host';
import { detailContains, detailLines, nodeReferences, nodesNotMatching, referencesFor } from './reference-details';

/** stores: an Add data Reference of stores; sales: an ordinary Dataset; half: a Reference with no identifying column. */
const stores: LaDataset = {
    id: 'stores',
    name: 'Stores',
    sourceName: 'stores/database',
    kind: 'reference',
    identifyingColumn: 'store_id',
    primaryEntityType: 'store',
    columns: [
        { name: 'store_id', type: 'string' },
        { name: 'region', type: 'string' },
    ],
};
const sales: LaDataset = { id: 'sales', name: 'sales', sourceName: 'sales/database', primaryEntityType: 'store' };
const half: LaDataset = { ...stores, id: 'half', identifyingColumn: undefined };

describe('reference-details (ONBOARDING-FIRST-RUN-1 C6a step 4)', () => {
    it('a Reference is a Dataset of kind reference with an identifying column and its Entity Type', () => {
        expect(nodeReferences([stores, sales, half])).toEqual([
            { dataset: 'stores', keyCol: 'store_id', name: 'Stores', entityType: 'store', columns: ['region'] },
        ]);
        const refs = nodeReferences([stores]);
        expect(referencesFor(refs, 'store').map((r) => r.dataset)).toEqual(['stores']);
        expect(referencesFor(refs, 'msisdn')).toEqual([]);
        expect(referencesFor(refs, undefined)).toEqual([]);
    });

    it('a row shows as lines without its key; a blank is a dash', () => {
        expect(detailLines({ store_id: 'S1', region: 'North', manager: null }, 'store_id')).toEqual([
            { name: 'region', value: 'North' },
            { name: 'manager', value: '—' },
        ]);
        expect(detailLines(undefined, 'store_id')).toEqual([]);
    });

    it('the filter keeps the nodes whose detail contains the text (any case); no row means hidden', () => {
        expect(detailContains('North-East', ' north ')).toBe(true);
        expect(detailContains(null, 'north')).toBe(false);
        expect(detailContains('South', '')).toBe(true);
        const rows = new Map<string, Record<string, unknown>>([
            ['S1', { region: 'North' }],
            ['S2', { region: 'South' }],
        ]);
        const nodes = [
            { id: 'n1', key: 'S1' },
            { id: 'n2', key: 'S2' },
            { id: 'n3', key: 'S9' },
        ];
        expect(nodesNotMatching(nodes, rows, 'region', 'north')).toEqual(['n2', 'n3']);
        // a cut read: a node with no row may lie beyond the cut, so it stays
        expect(nodesNotMatching(nodes, rows, 'region', 'north', false)).toEqual(['n2']);
    });
});
