import { describe, expect, it } from 'vitest';
import { CommitEvent, DisruptionEvent, stalePipelines, staleWidgets } from './stale-tiles';

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
