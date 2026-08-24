import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dataset } from './dataset-types';
import { DatasetsService } from './datasets.service';
import { DatasetEditorComponent } from './dataset-editor.component';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';

/** The rows seam, stubbed: this space's stores, and one page of whichever is picked. */
function seam(names = ['cdr', 'orders']) {
    return {
        stores: vi.fn(() => Promise.resolve({ names })),
        rows: vi.fn(() =>
            Promise.resolve({
                rows: [{ msisdn: '8801700000001', duration_s: 12 }],
                columns: [
                    { name: 'msisdn', type: 'string' },
                    { name: 'duration_s', type: 'number' },
                ],
                truncated: false,
            }),
        ),
    };
}

function create(
    save = vi.fn((d: Dataset) => of(d)),
    list: Dataset[] = [],
    existing: Dataset | null = null,
    rowsSeam: unknown = seam(),
) {
    TestBed.configureTestingModule({
        imports: [DatasetEditorComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: DatasetsService, useValue: { get: () => of(existing), list: () => of(list), save } },
            { provide: DatasetRowsService, useValue: rowsSeam },
            {
                provide: ToastrService,
                useValue: { warning: () => undefined, success: () => undefined, error: () => undefined },
            },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    return TestBed.createComponent(DatasetEditorComponent);
}

describe('DatasetEditorComponent', () => {
    it('starts in create mode on the space‘s first real store, with columns inferred from its page', async () => {
        const fixture = create();
        fixture.detectChanges();
        // Zoneless CD: the store list + default pick resolve via promise microtasks that
        // whenStable() cannot see — poll for the default source, then assert.
        await vi.waitFor(() => expect(fixture.componentInstance.form.controls.sourceName.value).toBe('cdr'));
        const c = fixture.componentInstance;
        expect(c.editing()).toBe(false);
        expect(c.isVirtual()).toBe(true);
        // The picker offers what /db/catalog lists — never a hardcoded sample table.
        expect(c.sourceNames()).toEqual(['cdr', 'orders']);
        expect(c.form.controls.sourceName.value).toBe('cdr');
        expect(c.columns().length).toBeGreaterThan(0);
        // duration_s is numeric & non-id → measure
        expect(c.columns().find((x) => x.name === 'duration_s')?.role).toBe('measure');
    });

    it('says the catalog could not be read rather than showing an empty picker as "no stores"', async () => {
        const failing = { ...seam(), stores: vi.fn(() => Promise.resolve({ names: [], error: 'Backend down' })) };
        const fixture = create(
            vi.fn((d: Dataset) => of(d)),
            [],
            null,
            failing,
        );
        fixture.detectChanges();
        await fixture.whenStable();
        expect(fixture.componentInstance.sourceNames()).toEqual([]);
        expect(fixture.componentInstance.storesError()).toBe('Backend down');
    });

    it('switching kind to physical hides the query panel', () => {
        const fixture = create();
        fixture.detectChanges();
        fixture.componentInstance.form.controls.kind.setValue('physical');
        expect(fixture.componentInstance.isVirtual()).toBe(false);
    });

    it('saves a valid dataset and navigates back to the list', async () => {
        const save = vi.fn((d: Dataset) => of(d));
        const fixture = create(save);
        fixture.detectChanges();
        // Zoneless CD: wait for the store list to land before the default source is picked.
        await vi.waitFor(() => expect(fixture.componentInstance.sourceNames().length).toBeGreaterThan(0));
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.componentInstance.form.controls.name.setValue('cdr_view');
        fixture.componentInstance.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({ id: 'cdr_view', kind: 'virtual', sourceName: 'cdr' }),
            { update: false }, // create mode — edits go through PUT (the backend 409s a re-create)
        );
        expect(nav).toHaveBeenCalledWith(['/catalog/datasets']);
    });

    it('does not save when the name is empty', () => {
        const save = vi.fn((d: Dataset) => of(d));
        const fixture = create(save);
        fixture.detectChanges();
        fixture.componentInstance.save();
        expect(save).not.toHaveBeenCalled();
    });

    it('blocks save on a duplicate id (case-insensitive) per the product-wide rule', () => {
        const save = vi.fn((d: Dataset) => of(d));
        const existing = {
            id: 'cdr_view',
            name: 'cdr_view',
            kind: 'virtual',
            sourceName: 'cdr',
            columns: [],
            measures: [],
        } as Dataset;
        const fixture = create(save, [existing]);
        fixture.detectChanges(); // ngOnInit loads the list + attaches the unique validator
        fixture.componentInstance.form.controls.name.setValue('CDR_View');
        fixture.componentInstance.save();
        expect(save).not.toHaveBeenCalled();
        expect(fixture.componentInstance.form.controls.name.hasError('duplicate')).toBe(true);
    });

    it('keeps a saved dataset‘s own store in the picker even when the catalog no longer lists it', async () => {
        // A go-live-registered dataset names its store, which is not a sample source. A mat-select
        // whose value is missing from its options renders empty — that reads as "no source chosen".
        const live = {
            id: 'orders_feed',
            name: 'orders_feed',
            kind: 'physical',
            sourceName: 'orders_feed',
            physicalRef: 'orders_feed',
            columns: [],
            measures: [],
            calculated: [],
        } as unknown as Dataset;
        // Its store is NOT in the catalog any more (renamed, or registered outside the data root).
        const unreadable = {
            ...seam(),
            rows: vi.fn(() =>
                Promise.resolve({ rows: [], columns: [], truncated: false, error: 'no store "orders_feed"' }),
            ),
        };
        const fixture = create(
            vi.fn((d: Dataset) => of(d)),
            [],
            live,
            unreadable,
        );
        fixture.componentInstance.id = 'orders_feed';
        fixture.detectChanges();
        // Zoneless CD: the seeded dataset + merged store list land on promise microtasks that
        // whenStable() cannot see — poll for the merged picker, then assert.
        const c = fixture.componentInstance;
        await vi.waitFor(() => expect(c.sourceNames()).toContain('orders_feed'));
        expect(c.sourceNames()).toContain('orders_feed');
        // And it says WHY there is no preview, in the store's own words — not a generic hint.
        expect(c.previewProblem()).toBe('no store "orders_feed"');
    });

    it('a readable store is not flagged as unpreviewable', async () => {
        const fixture = create();
        fixture.detectChanges();
        await fixture.whenStable();
        expect(fixture.componentInstance.previewProblem()).toBeNull();
    });

    // This editor embeds the query panel + an ag-Grid preview, making it the heaviest a11y
    // fixture in the suite. axe finishes in ~1-2s in isolation, but under full-suite multi-worker
    // CPU contention it can occasionally cross vitest's 5s default and time out (never a real
    // violation — those throw immediately). Give it explicit headroom.
    it('renders with no a11y violations', async () => {
        const fixture = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    }, 15_000);
});
