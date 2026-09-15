import { describe, expect, it } from 'vitest';
import { CommitEvent, DisruptionEvent, stalePipelines, staleStoresOf, staleWidgets } from './stale-tiles';

const gap = (pipeline: string | null, ts: number): DisruptionEvent => ({ type: 'SEQUENCE_GAP', pipeline, ts });
const quarantine = (pipeline: string, ts: number): DisruptionEvent => ({ type: 'FILE_QUARANTINED', pipeline, ts });
const commit = (pipeline: string, ts: number): CommitEvent => ({ pipeline, ts });

const PIPELINES = [
    { name: 'orders', produces: ['orders_store'] },
    { name: 'payments', produces: ['payments_store'] },
    { name: 'both', produces: ['orders_store', 'shared_store'] },
];
const DATASETS = [
    { id: 'ds_orders', sourceName: 'orders_store' },
    { id: 'ds_payments', sourceName: 'payments_store' },
    { id: 'ds_shared', sourceName: 'shared_store' },
];
const WIDGETS = [
    { id: 'w_orders', datasetId: 'ds_orders' },
    { id: 'w_payments', datasetId: 'ds_payments' },
    { id: 'w_shared', datasetId: 'ds_shared' },
    { id: 'w_view', datasetId: '' }, // a view-bound geo/link widget — reads no Dataset
];

describe('stalePipelines', () => {
    it('marks a pipeline whose gap has no later commit', () => {
        const stale = stalePipelines([gap('orders', 100)], []);
        expect([...stale.keys()]).toEqual(['orders']);
        expect(stale.get('orders')!.type).toBe('SEQUENCE_GAP');
        expect(stale.get('orders')!.reason).toContain('orders');
        expect(stale.get('orders')!.reason).toContain('sequence gap');
    });

    /**
     * "Clear the badge on the next successful run" is the row's own requirement, and it is not a separate
     * mechanism here — it falls out of the comparison, which is why there is no stored flag to leak.
     */
    it('clears once a later commit lands', () => {
        expect(stalePipelines([gap('orders', 100)], [commit('orders', 101)]).size).toBe(0);
    });

    it('does NOT clear on an earlier commit — the run that fixed it must come after', () => {
        expect(stalePipelines([gap('orders', 100)], [commit('orders', 99)]).size).toBe(1);
    });

    /** A tie counts as cleared; otherwise a badge could survive every subsequent run. */
    it('treats a commit at the same instant as clearing', () => {
        expect(stalePipelines([gap('orders', 100)], [commit('orders', 100)]).size).toBe(0);
    });

    it('another pipeline’s commit never clears this one', () => {
        const stale = stalePipelines([gap('orders', 100)], [commit('payments', 500)]);
        expect([...stale.keys()]).toEqual(['orders']);
    });

    it('keeps the most recent disruption when a pipeline has several', () => {
        const stale = stalePipelines([gap('orders', 100), quarantine('orders', 300)], []);
        expect(stale.get('orders')!.type).toBe('FILE_QUARANTINED');
        expect(stale.get('orders')!.at).toBe(300);
    });

    /** An old gap followed by a commit followed by a NEW gap is stale again. */
    it('re-marks after a commit when a fresh disruption follows it', () => {
        const stale = stalePipelines([gap('orders', 100), gap('orders', 300)], [commit('orders', 200)]);
        expect(stale.get('orders')!.at).toBe(300);
    });

    /** A service-wide event names no pipeline, so it invalidates no particular dataset. */
    it('ignores an event with no pipeline', () => {
        expect(stalePipelines([gap(null, 100)], []).size).toBe(0);
    });
});

describe('staleWidgets (the full chain)', () => {
    /** The row's acceptance: badge exactly the widgets bound to the affected data, and no others. */
    it('badges exactly the widgets bound to the stale store, and no others', () => {
        const stale = staleWidgets([gap('orders', 100)], [], PIPELINES, DATASETS, WIDGETS);
        expect([...stale.keys()]).toEqual(['w_orders']);
        expect(stale.get('w_orders')!.store).toBe('orders_store');
    });

    it('badges every widget when one pipeline produces several stores', () => {
        const stale = staleWidgets([gap('both', 100)], [], PIPELINES, DATASETS, WIDGETS);
        expect([...stale.keys()].sort()).toEqual(['w_orders', 'w_shared']);
    });

    /** A view-bound widget reads no Dataset, so no pipeline can invalidate it. */
    it('never badges a widget with no dataset binding', () => {
        const stale = staleWidgets(
            [gap('orders', 100), gap('payments', 100), gap('both', 100)],
            [],
            PIPELINES,
            DATASETS,
            WIDGETS,
        );
        expect(stale.has('w_view')).toBe(false);
    });

    it('badges nothing when every disruption has been cleared', () => {
        const stale = staleWidgets([gap('orders', 100)], [commit('orders', 200)], PIPELINES, DATASETS, WIDGETS);
        expect(stale.size).toBe(0);
    });

    /** Two stale pipelines feeding one store: the operator should see the most recent disruption. */
    it('keeps the most recent disruption when two stale pipelines feed one store', () => {
        const stale = staleWidgets([gap('orders', 100), quarantine('both', 400)], [], PIPELINES, DATASETS, WIDGETS);
        expect(stale.get('w_orders')!.at).toBe(400);
        expect(stale.get('w_orders')!.type).toBe('FILE_QUARANTINED');
    });

    it('badges nothing when the stale pipeline produces no store anyone reads', () => {
        const stale = staleWidgets(
            [gap('orphan', 100)],
            [],
            [...PIPELINES, { name: 'orphan', produces: ['nobody_reads_this'] }],
            DATASETS,
            WIDGETS,
        );
        expect(stale.size).toBe(0);
    });

    it('is inert with no disruptions at all — the common case must cost nothing', () => {
        expect(staleWidgets([], [commit('orders', 1)], PIPELINES, DATASETS, WIDGETS).size).toBe(0);
    });
});

/** STALE-TILES-PRECISION-1: store-level inputs decide per store; pipeline-level inputs still fall back. */
describe('staleStoresOf (store granularity)', () => {
    it('a dataset.write to ONE store clears that store and leaves the sibling stale', () => {
        // pipeline `both` produces orders_store + shared_store; only orders_store was rewritten after the gap
        const stale = staleWidgets(
            [gap('both', 100)],
            [{ pipeline: 'both', ts: 101, stores: ['orders_store'] }],
            PIPELINES,
            DATASETS,
            WIDGETS,
        );
        expect(stale.has('w_orders')).toBe(false);
        expect(stale.has('w_shared')).toBe(true);
        expect(stale.get('w_shared')!.store).toBe('shared_store');
    });

    it('a gap that names its stores marks only those', () => {
        const stores = staleStoresOf(
            [{ type: 'SEQUENCE_GAP', pipeline: 'both', ts: 100, stores: ['shared_store'] }],
            [],
            PIPELINES,
        );
        expect([...stores.keys()]).toEqual(['shared_store']);
    });

    it('a pipeline-level commit still clears every store the pipeline produces (regression)', () => {
        expect(staleWidgets([gap('both', 100)], [commit('both', 101)], PIPELINES, DATASETS, WIDGETS).size).toBe(0);
        // `orders` also produces orders_store, so its commit refreshes THAT store — only shared_store stays stale.
        // (Under the old pipeline-level anchor both of `both`'s stores stayed marked; that was the imprecision.)
        const after = staleWidgets([gap('both', 100)], [commit('orders', 101)], PIPELINES, DATASETS, WIDGETS);
        expect([...after.keys()]).toEqual(['w_shared']);
    });

    it('a store-level commit for another store does not clear anything', () => {
        const stale = staleStoresOf(
            [gap('orders', 100)],
            [{ pipeline: 'orders', ts: 101, stores: ['payments_store'] }],
            PIPELINES,
        );
        expect([...stale.keys()]).toEqual(['orders_store']);
    });
});
