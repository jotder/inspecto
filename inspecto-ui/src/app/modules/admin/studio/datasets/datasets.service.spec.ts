import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api';
import { DatasetsService } from './datasets.service';
import { buildDataset } from './dataset-types';

/** @param content override the single listed dataset's stored content (default: a virtual `d1`). */
function setup(content?: Record<string, unknown>) {
    const create = vi.fn((_t: string, c: Record<string, unknown>) =>
        of({ type: 'dataset', name: String(c['id']), ref: `dataset/${c['id']}`, content: c }),
    );
    const update = vi.fn((_t: string, id: string, c: Record<string, unknown>) =>
        of({ type: 'dataset', name: id, ref: `dataset/${id}`, content: c }),
    );
    const list = vi.fn(() =>
        of([
            {
                type: 'dataset',
                name: 'd1',
                ref: 'dataset/d1',
                content: content ?? { name: 'd1', kind: 'virtual', sourceName: 'cdr', columns: [], measures: [] },
            },
        ]),
    );
    TestBed.configureTestingModule({
        providers: [
            DatasetsService,
            { provide: ComponentsService, useValue: { create, update, list, remove: vi.fn(() => of(null)) } },
        ],
    });
    return { svc: TestBed.inject(DatasetsService), create, update, list };
}

describe('DatasetsService', () => {
    it('saves a dataset as a "dataset" registry component', () => {
        const { svc, create } = setup();
        let saved: { id: string } | undefined;
        svc.save(buildDataset('d1', 'virtual', 'cdr')).subscribe((d) => (saved = d));
        expect(create).toHaveBeenCalledWith(
            'dataset',
            expect.objectContaining({ id: 'd1', kind: 'virtual', sourceName: 'cdr' }),
        );
        expect(saved?.id).toBe('d1');
    });

    /**
     * ⚠ This assertion was INVERTED on 2026-08-31 (MOCK-GONE-1(b)), deliberately. It used to require a
     * blank `sourceName` here. The rule it was written to protect — "never a FABRICATED source", i.e.
     * the old `?? 'data'` that named a store nobody had — is unchanged and still pinned by the test
     * below. `physicalRef` is not a fabrication: go-live writes the landed store's NAME there and
     * writes no `sourceName` at all, so treating it as blank left every such dataset unreadable and
     * built `GET /db/table` with no `name`.
     */
    it("a dataset stored with only a physicalRef reads that store's name as its source", () => {
        // What go-live writes: a physical dataset over a real store, with no sourceName.
        const { svc } = setup({ name: 'orders_feed', kind: 'physical', physicalRef: 'orders_feed' });
        let datasets: { sourceName: string }[] = [];
        svc.list().subscribe((d) => (datasets = d));
        expect(datasets[0].sourceName).toBe('orders_feed');
    });

    it('with neither sourceName nor physicalRef the source stays blank — nothing is invented', () => {
        const { svc } = setup({ name: 'empty', kind: 'virtual', columns: [], measures: [] });
        let datasets: { sourceName: string }[] = [];
        svc.list().subscribe((d) => (datasets = d));
        expect(datasets[0].sourceName).toBe('');
    });

    it('a cross-space shared ref is NOT a local store name', () => {
        // `shared/<owner>/<item>` is resolved server-side; passing it to /db/table as a table would 404.
        const { svc } = setup({ name: 'bound', kind: 'physical', physicalRef: 'shared/partner/orders' });
        let datasets: { sourceName: string }[] = [];
        svc.list().subscribe((d) => (datasets = d));
        expect(datasets[0].sourceName).toBe('');
    });

    it('edits go through PUT — save with {update: true} never re-creates (the backend 409s that)', () => {
        const { svc, create, update } = setup();
        svc.save(buildDataset('d1', 'virtual', 'cdr'), { update: true }).subscribe();
        expect(create).not.toHaveBeenCalled();
        expect(update).toHaveBeenCalledWith(
            'dataset',
            'd1',
            expect.objectContaining({ kind: 'virtual', sourceName: 'cdr' }),
        );
    });

    it('lists datasets back from the registry', () => {
        const { svc } = setup();
        let datasets: { name: string; kind: string }[] = [];
        svc.list().subscribe((d) => (datasets = d));
        expect(datasets[0].name).toBe('d1');
        expect(datasets[0].kind).toBe('virtual');
    });
});
