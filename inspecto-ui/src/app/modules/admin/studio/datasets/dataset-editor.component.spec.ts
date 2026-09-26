import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Router, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Dataset } from './dataset-types';
import { DatasetsService } from './datasets.service';
import { DatasetEditorComponent } from './dataset-editor.component';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { LensService } from 'app/inspecto/api';

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
    opts: { canOperateRuns?: boolean; materialize?: unknown; dialog?: unknown } = {},
) {
    // ⚠ LensService is STUBBED, not real: the header now gates Materialize on canOperateRuns(), and the
    // real service reads SessionService + a localStorage lens that leaks between specs.
    localStorage.removeItem('inspecto.currentLens');
    TestBed.configureTestingModule({
        imports: [DatasetEditorComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            {
                provide: LensService,
                // ⚠ Stub EVERY capability this fixture can reach, not just the one under test: replacing
                // LensService replaces it for the child components too, and `TransferMenuComponent` calls
                // canAuthorWorkbench() — a one-method stub took out 13 tests with
                // "canAuthorWorkbench is not a function", which reads like a child-component bug.
                useValue: {
                    canOperateRuns: () => opts.canOperateRuns !== false,
                    canAuthorWorkbench: () => true,
                    canOfferDatasets: () => true,
                },
            },
            {
                provide: DatasetsService,
                useValue: {
                    get: () => of(existing),
                    list: () => of(list),
                    save,
                    materialize:
                        opts.materialize ??
                        vi.fn(() => of({ runId: 'run-1', dataset: 'orders', target: 't', status: 'running' })),
                },
            },
            { provide: DatasetRowsService, useValue: rowsSeam },
            {
                provide: ToastrService,
                useValue: { warning: () => undefined, success: () => undefined, error: () => undefined },
            },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    // ⚠ MatDialog must be overridden HERE — after configureTestingModule, before the first injection.
    // Two rules collide otherwise: this editor renders an ag-Grid preview that injects the REAL
    // MatDialog, so a plain {provide: MatDialog} in the providers above is silently ignored; and
    // overrideProvider throws "test module has already been instantiated" once anything has been
    // injected, which is what happens if a test tries to override after createComponent.
    if (opts.dialog) TestBed.overrideProvider(MatDialog, { useValue: opts.dialog });
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

    it('a virtual dataset saves the SQL the panel shows as `sql` — the relation the server reads', async () => {
        const save = vi.fn((d: Dataset) => of(d));
        const fixture = create(save);
        fixture.detectChanges();
        await vi.waitFor(() => expect(fixture.componentInstance.sourceNames().length).toBeGreaterThan(0));
        vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        const c = fixture.componentInstance;
        const sql = `SELECT strptime(msisdn, '%Y') AS y FROM "cdr"`;
        c.onQueryChange({
            model: { projection: '*', where: { kind: 'group', op: 'AND', items: [] }, sqlOverride: sql },
            sql,
        });
        c.form.controls.name.setValue('cdr_years');
        c.save();
        expect(save.mock.calls[0][0]).toMatchObject({ kind: 'virtual', sourceName: 'cdr', sql });

        // a physical dataset carries no SQL, whatever the panel last showed
        save.mockClear();
        c.form.controls.kind.setValue('physical');
        c.save();
        expect(save.mock.calls[0][0].sql).toBeNull();
    });

    it('"Run on server" previews the SQL in DuckDB and re-tags the columns from its result', async () => {
        const rowsSeam = {
            ...seam(),
            sql: vi.fn(() =>
                Promise.resolve({
                    rows: [{ y: '2020-01-01' }],
                    columns: [{ name: 'y', type: 'date' }],
                    truncated: false,
                }),
            ),
        };
        const fixture = create(
            vi.fn((d: Dataset) => of(d)),
            [],
            null,
            rowsSeam,
        );
        fixture.detectChanges();
        await vi.waitFor(() => expect(fixture.componentInstance.form.controls.sourceName.value).toBe('cdr'));
        const c = fixture.componentInstance;
        await c.onRunOnServer(`SELECT strptime(msisdn, '%Y') AS y FROM "cdr"`);
        expect(rowsSeam.sql).toHaveBeenCalledWith('cdr', `SELECT strptime(msisdn, '%Y') AS y FROM "cdr"`);
        expect(c.querySource().rows).toEqual([{ y: '2020-01-01' }]);
        expect(c.columns()).toEqual([{ name: 'y', type: 'date', role: 'temporal' }]);
        expect(c.runError()).toBeNull();
    });

    it('a failed server run is explained on screen and leaves the columns alone', async () => {
        const rowsSeam = {
            ...seam(),
            sql: vi.fn(() =>
                Promise.resolve({ rows: [], columns: [], truncated: false, error: 'SQL failed the safety check' }),
            ),
        };
        const fixture = create(
            vi.fn((d: Dataset) => of(d)),
            [],
            null,
            rowsSeam,
        );
        fixture.detectChanges();
        await vi.waitFor(() => expect(fixture.componentInstance.form.controls.sourceName.value).toBe('cdr'));
        const c = fixture.componentInstance;
        const before = c.columns();
        await c.onRunOnServer(`SELECT * FROM read_csv('x')`);
        expect(c.runError()).toBe('SQL failed the safety check');
        expect(c.columns()).toEqual(before);
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('SQL failed the safety check');
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
        // A go-live-registered dataset names its store, which is not a sample source. The store must stay
        // among the picker's options so re-picking it stays possible after the catalog drops it.
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

    // ── Materialize (STUDIO-HALVES-1) ────────────────────────────────────────────

    /** A MatDialog whose open() resolves to `target` — `undefined` models Cancel/Esc/backdrop. */
    function dialogReturning(target: string | undefined) {
        return { open: () => ({ afterClosed: () => of(target) }) };
    }

    /** Edit mode over one saved dataset — the only mode the Materialize action exists in. */
    function editing(opts: { canOperateRuns?: boolean; materialize?: unknown; dialog?: unknown } = {}) {
        const live: Dataset = {
            id: 'orders',
            name: 'orders',
            kind: 'physical',
            sourceName: 'orders',
            query: null,
            physicalRef: 'orders',
            columns: [],
            measures: [],
            calculated: [],
            viz: null,
        };
        const fixture = create(
            vi.fn((d: Dataset) => of(d)),
            [live],
            live,
            seam(['orders']),
            opts,
        );
        fixture.componentInstance.id = 'orders';
        fixture.detectChanges();
        return fixture;
    }

    it('offers Materialize only to a lens that can operate runs', async () => {
        const denied = editing({ canOperateRuns: false });
        await vi.waitFor(() => expect(denied.componentInstance.editing()).toBe(true));
        denied.detectChanges();
        const labels = () =>
            Array.from(denied.nativeElement.querySelectorAll('button')).map(
                (b) => (b as HTMLElement).textContent ?? '',
            );
        // ⚠ Assert the RENDERED button, not the capability signal — the gate is in the template, so a
        // spec that only read canOperateRuns() would pass with the button unconditionally visible.
        expect(labels().some((t) => t.includes('Materialize'))).toBe(false);
        expect(labels().some((t) => t.includes('History'))).toBe(true);
    });

    it('materializes into the target the dialog returns, and reports the run id', async () => {
        const materialize = vi.fn(() =>
            of({ runId: 'run-77', dataset: 'orders', target: 'orders_by_region', status: 'running' }),
        );
        const fixture = editing({ materialize, dialog: dialogReturning('orders_by_region') });
        await vi.waitFor(() => expect(fixture.componentInstance.editing()).toBe(true));
        const toastr = TestBed.inject(ToastrService);
        const success = vi.spyOn(toastr, 'success');

        fixture.componentInstance.materialize();
        await vi.waitFor(() => expect(materialize).toHaveBeenCalledWith('orders', 'orders_by_region'));
        expect(success.mock.calls[0][0]).toContain('run-77');
        expect(fixture.componentInstance.materializing()).toBe(false);
    });

    it('a dismissed dialog materializes nothing', async () => {
        const materialize = vi.fn(() => of({ runId: 'x', dataset: 'orders', target: 't', status: 'running' }));
        const fixture = editing({ materialize, dialog: dialogReturning(undefined) });
        await vi.waitFor(() => expect(fixture.componentInstance.editing()).toBe(true));

        fixture.componentInstance.materialize();
        await fixture.whenStable();
        expect(materialize).not.toHaveBeenCalled();
    });

    it('names the 409 rather than showing a generic failure', async () => {
        const materialize = vi.fn(() => throwError(() => ({ status: 409 })));
        const fixture = editing({ materialize, dialog: dialogReturning('orders_by_region') });
        await vi.waitFor(() => expect(fixture.componentInstance.editing()).toBe(true));
        const toastr = TestBed.inject(ToastrService);
        const error = vi.spyOn(toastr, 'error');

        fixture.componentInstance.materialize();
        // 409 here is "that target is already being materialized" — NOT a stale-version conflict, so the
        // message must not tell the operator to reload to get someone else's version.
        await vi.waitFor(() => expect(error).toHaveBeenCalled());
        expect(error.mock.calls[0][0]).toContain('already running');
        expect(fixture.componentInstance.materializing()).toBe(false);
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
