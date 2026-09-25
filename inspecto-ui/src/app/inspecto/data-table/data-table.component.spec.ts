import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { describe, expect, it, vi } from 'vitest';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DataTableComponent, DataTableTier } from './data-table.component';

async function create(tier: DataTableTier = 'standard') {
    TestBed.configureTestingModule({
        imports: [DataTableComponent],
        providers: [
            provideNoopAnimations(),
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    await TestBed.compileComponents(); // the component has a @defer block (the SQL editor)
    const f = TestBed.createComponent(DataTableComponent);
    f.componentRef.setInput('tier', tier);
    f.componentRef.setInput('rows', [
        { id: 1, name: 'alpha' },
        { id: 2, name: 'beta' },
    ]);
    f.detectChanges();
    return f;
}

describe('DataTableComponent', () => {
    it('mini = grid only (no toolbar capabilities)', async () => {
        const c = (await create('mini')).componentInstance;
        expect(c.showSearch()).toBe(false);
        expect(c.showExport()).toBe(false);
        expect(c.showColumns()).toBe(false);
        expect(c.canQuery()).toBe(false);
        expect(c.hasToolbar()).toBe(false);
        expect(c.gridColumns().map((x) => x.field)).toEqual(['id', 'name']);
    });

    it('row-derived columns are headed by the REAL column name, wide enough not to break mid-word', async () => {
        const f = await create('pro');
        f.componentRef.setInput('rows', [{ order_id: 1, order_date: '2026-09-25' }]);
        f.detectChanges();
        const cols = f.componentInstance.gridColumns();
        // ag-Grid humanises a bare `field` ("Order_date"), and the shared wrapHeaderText broke it at the
        // 110px min width ("Order_da te") — the Parsed grid never showed the names the Grammar produced.
        expect(cols.map((c) => c.headerName)).toEqual(['order_id', 'order_date']);
        for (const c of cols) expect(c.minWidth).toBeGreaterThanOrEqual(String(c.field).length * 7.5 + 72);
        // A pro-tier SQL run's result columns are row-derived too.
        f.componentInstance.proResult.set([{ total_amount: 3 }]);
        expect(f.componentInstance.gridColumns().map((c) => c.headerName)).toEqual(['total_amount']);
    });

    it('standard = search + columns + export (no SQL editor)', async () => {
        const c = (await create('standard')).componentInstance;
        expect(c.showSearch()).toBe(true);
        expect(c.showColumns()).toBe(true);
        expect(c.showExport()).toBe(true);
        expect(c.canQuery()).toBe(false);
        expect(c.showSave()).toBe(false);
    });

    it('pro = SQL editor (canQuery) + a generated SELECT; grid shows source rows until a run', async () => {
        const c = (await create('pro')).componentInstance;
        expect(c.canQuery()).toBe(true);
        expect(c.generatedSql()).toContain('SELECT');
        expect(c.generatedSql()).toContain('FROM');
        expect(c.proResult()).toBeNull();
        expect(c.displayRows().length).toBe(2);
        // both panels are hidden by default; each toggles independently
        expect(c.sqlOpen()).toBe(false);
        expect(c.filterOpen()).toBe(false);
        c.toggleSql();
        expect(c.sqlOpen()).toBe(true);
        c.toggleFilter();
        expect(c.filterOpen()).toBe(true);
    });

    it('onRunSqlBackend clears the client-run overlay and emits the SQL for the host', async () => {
        const c = (await create('pro')).componentInstance;
        c.proResult.set([{ id: 9 }]); // a prior client-run (AlaSQL) overlay
        let emitted: string | undefined;
        c.runOnServer.subscribe((s: string) => (emitted = s));
        c.onRunSqlBackend('SELECT * FROM "data"');
        expect(c.proResult()).toBeNull(); // overlay cleared so the host's fresh rows show
        expect(emitted).toBe('SELECT * FROM "data"');
    });

    it('proMax = pro + save-as-rule always available', async () => {
        const c = (await create('proMax')).componentInstance;
        expect(c.canQuery()).toBe(true);
        expect(c.showSave()).toBe(true);
    });

    it('serverPage: the honest "Load more" strip renders only while hasMore, and emits loadMore (R6b)', async () => {
        const f = await create('standard');
        f.componentRef.setInput('serverPage', true);
        f.componentRef.setInput('hasMore', true);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        expect(el.textContent).toContain('there may be more on the server');

        let emitted = false;
        f.componentInstance.loadMore.subscribe(() => (emitted = true));
        Array.from(el.querySelectorAll('button'))
            .find((b) => b.textContent?.includes('Load more'))
            ?.click();
        expect(emitted).toBe(true);

        f.componentRef.setInput('hasMore', false);
        f.detectChanges();
        expect(el.textContent).not.toContain('there may be more on the server');
    });

    /**
     * ⚠ Hosts derive the next page's offset from `rows.length`, so a second click before the first
     * page lands re-requests the SAME offset and the host appends it twice — duplicate rows, on
     * exactly the slow backend that makes someone click again. Gated on `loading`, which every
     * serverPage host binds and clears on error too, so a FAILED page stays retryable (a private
     * latch cleared by incoming rows would have disabled the button for ever on that path).
     */
    it('serverPage: Load more cannot be double-fired while the page is in flight', async () => {
        const f = await create('standard');
        f.componentRef.setInput('serverPage', true);
        f.componentRef.setInput('hasMore', true);
        f.componentRef.setInput('loading', true);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        const btn = Array.from(el.querySelectorAll('button')).find((b) =>
            b.textContent?.includes('Load more'),
        ) as HTMLButtonElement;

        let emissions = 0;
        f.componentInstance.loadMore.subscribe(() => emissions++);
        expect(btn.disabled).toBe(true);
        f.componentInstance.requestMore(); // the method is the gate, not just the disabled attribute
        expect(emissions).toBe(0);

        f.componentRef.setInput('loading', false); // the page landed (or the fetch failed and cleared)
        f.detectChanges();
        expect(btn.disabled).toBe(false);
        f.componentInstance.requestMore();
        expect(emissions).toBe(1);
    });

    it('the column chooser drives the SQL projection', async () => {
        const c = (await create('pro')).componentInstance;
        expect(c.generatedSql()).toContain('SELECT *');
        c.onChosen(['name']);
        expect(c.generatedSql()).toContain('SELECT "name"');
        expect(c.generatedSql()).not.toContain('"id"');
    });

    it('columnMeta overrides pure value-inference for the Pro filter/SQL builder', async () => {
        const f = await create('pro');
        f.componentRef.setInput('columnMeta', [
            { name: 'id', type: 'string' as const },
            { name: 'name', type: 'string' as const },
        ]);
        f.detectChanges();
        expect(f.componentInstance.columnsCache()).toEqual([
            { name: 'id', type: 'string' },
            { name: 'name', type: 'string' },
        ]);
    });

    it('initialModel seeds the Pro projection + filter exactly once', async () => {
        const f = await create('pro');
        f.componentRef.setInput('initialModel', {
            projection: ['name'],
            where: {
                kind: 'group' as const,
                op: 'AND' as const,
                items: [{ kind: 'condition' as const, field: 'name', operator: '=' as const, value: 'beta' }],
            },
            sqlOverride: null,
        });
        f.detectChanges();
        const c = f.componentInstance;
        expect(c.chosen()).toEqual(['name']);
        expect(c.where().items).toEqual([{ kind: 'condition', field: 'name', operator: '=', value: 'beta' }]);

        // Seeding is one-time: a later change to `initialModel` (e.g. the host resetting a form) is ignored.
        c.onChosen(['id']);
        f.componentRef.setInput('initialModel', {
            projection: '*',
            where: { kind: 'group', op: 'AND', items: [] },
            sqlOverride: null,
        });
        f.detectChanges();
        expect(c.chosen()).toEqual(['id']);
    });

    it('queryModelChange emits the current model + SQL whenever the projection/filter changes', async () => {
        const f = await create('pro');
        const c = f.componentInstance;
        const emitted: string[] = [];
        c.queryModelChange.subscribe((e) => emitted.push(e.sql));
        c.onChosen(['name']);
        f.detectChanges(); // flush the emit effect
        expect(emitted.at(-1)).toContain('SELECT "name"');
    });

    it('appends an actions column when rowActions are supplied', async () => {
        const f = await create('mini');
        f.componentRef.setInput('rowActions', [{ icon: 'heroicons_outline:eye', hint: 'View', onClick: () => {} }]);
        f.detectChanges();
        expect(f.componentInstance.gridColumns().some((col) => col.colId === 'actions')).toBe(true);
    });

    it('pins the actions column to the right only when pinActions is set', async () => {
        const f = await create('mini');
        f.componentRef.setInput('rowActions', [{ icon: 'heroicons_outline:eye', hint: 'View', onClick: () => {} }]);
        f.detectChanges();
        const actions = () => f.componentInstance.gridColumns().find((col) => col.colId === 'actions');
        expect(actions()?.pinned).toBeUndefined();
        f.componentRef.setInput('pinActions', true);
        f.detectChanges();
        expect(actions()?.pinned).toBe('right');
    });

    it('capability overrides win over the tier preset', async () => {
        const f = await create('mini');
        f.componentRef.setInput('exportable', true);
        f.detectChanges();
        expect(f.componentInstance.showExport()).toBe(true);
    });

    it('preserves explicit cellRenderer / valueFormatter / headerName across a SQL run', async () => {
        // Regression: a badge `cellRenderer` (e.g. statusBadgeHtml) must survive the pro-tier AlaSQL
        // re-materialization instead of being rebuilt as a bare column and rendering an empty cell.
        const badge = (p: ICellRendererParams) => `<span>${p.value}</span>`;
        const fmtWhen = (p: { value: unknown }) => `t:${p.value}`;
        const columns: ColDef[] = [
            { field: 'severity', headerName: 'Severity', cellRenderer: badge },
            { field: 'epochMillis', headerName: 'When', valueFormatter: fmtWhen },
        ];
        const f = await create('pro');
        f.componentRef.setInput('columns', columns);
        f.detectChanges();
        const c = f.componentInstance;

        // Simulate a successful Run whose result carries the two mapped fields + one result-only field.
        c.proResult.set([{ severity: 'critical', epochMillis: 1, extra: 'x' }]);
        f.detectChanges();

        const cols = c.gridColumns();
        const sev = cols.find((x) => x.field === 'severity')!;
        const when = cols.find((x) => x.field === 'epochMillis')!;
        const extra = cols.find((x) => x.field === 'extra')!;
        expect(sev.cellRenderer).toBe(badge); // badge renderer preserved
        expect(when.valueFormatter).toBe(fmtWhen);
        expect(when.headerName).toBe('When');
        expect(extra.cellRenderer).toBeUndefined(); // result-only field falls back to a bare column
    });

    it('refresh() force-refreshes every column so non-actions cell renderers materialize', async () => {
        // Regression: refresh must not scope to `['actions']` — a badge cellRenderer column would then
        // stay empty on ag-grid's initial render (the bug on /alerts, /events, …).
        vi.useFakeTimers();
        try {
            const c = (await create('pro')).componentInstance;
            const refreshCells = vi.fn();
            c.refresh({ api: { isDestroyed: () => false, refreshCells, getDisplayedRowCount: () => 2 } as never });
            vi.runAllTimers();
            expect(refreshCells).toHaveBeenCalledTimes(1);
            const arg = refreshCells.mock.calls[0][0];
            expect(arg.force).toBe(true);
            expect(arg.columns).toBeUndefined(); // all columns, not just 'actions'
        } finally {
            vi.useRealTimers();
        }
    });

    it('has no a11y violations (standard)', async () => {
        const f = await create('standard');
        await expectNoA11yViolations(f.nativeElement);
    });

    it('persists toolbar state under stateKey and restores it in a fresh instance', async () => {
        const storage = 'inspecto.grid.default.spec-table';
        localStorage.removeItem(storage);
        try {
            const f = await create('standard');
            f.componentRef.setInput('stateKey', 'spec-table');
            f.detectChanges();
            const c = f.componentInstance;
            c.search.set('alp');
            c.onChosen(['name']);
            f.detectChanges(); // flush the persist effect
            expect(JSON.parse(localStorage.getItem(storage)!)).toMatchObject({
                search: 'alp',
                chosen: ['name'],
            });

            // A fresh instance with the same key restores search (box open) + chosen projection.
            const f2 = TestBed.createComponent(DataTableComponent);
            f2.componentRef.setInput('stateKey', 'spec-table');
            f2.detectChanges();
            expect(f2.componentInstance.search()).toBe('alp');
            expect(f2.componentInstance.searchOpen()).toBe(true);
            expect(f2.componentInstance.chosen()).toEqual(['name']);
        } finally {
            localStorage.removeItem(storage);
        }
    });

    it('resetLayout returns to defaults and drops the persisted column layout', async () => {
        const storage = 'inspecto.grid.default.spec-reset';
        localStorage.setItem(
            storage,
            JSON.stringify({ search: 'x', chosen: ['id'], columns: [{ colId: 'id', width: 300 }] }),
        );
        try {
            const f = await create('standard');
            f.componentRef.setInput('stateKey', 'spec-reset');
            f.detectChanges();
            const c = f.componentInstance;
            expect(c.search()).toBe('x'); // restored
            c.resetLayout();
            f.detectChanges();
            expect(c.search()).toBe('');
            expect(c.searchOpen()).toBe(false);
            expect(c.chosen()).toBeNull();
            const after = JSON.parse(localStorage.getItem(storage) ?? '{}');
            expect(after.columns).toBeUndefined(); // layout gone; only default toolbar state re-saved
        } finally {
            localStorage.removeItem(storage);
        }
    });

    it('without a stateKey nothing is written to localStorage', async () => {
        const before = Object.keys(localStorage).filter((k) => k.startsWith('inspecto.grid.'));
        const c = (await create('standard')).componentInstance;
        c.search.set('zzz');
        const after = Object.keys(localStorage).filter((k) => k.startsWith('inspecto.grid.'));
        expect(after).toEqual(before);
    });

    // ── document-level keyboard layer (review R3) ─────────────────────────────────
    /** Stateful GridApi double for the nav focus/selection calls the keyboard layer makes. */
    function navApi(rows: Record<string, unknown>[]) {
        let focused: number | null = null;
        const selected = new Set<number>();
        return {
            api: {
                getDisplayedRowCount: () => rows.length,
                getAllDisplayedColumns: () => [{ getColId: () => 'id' }],
                getFocusedCell: () => (focused == null ? null : { rowIndex: focused }),
                ensureIndexVisible: () => undefined,
                setFocusedCell: (i: number) => (focused = i),
                getDisplayedRowAtIndex: (i: number) => ({
                    data: rows[i],
                    isSelected: () => selected.has(i),
                    setSelected: (v: boolean) => void (v ? selected.add(i) : selected.delete(i)),
                }),
            } as never,
            focusedIndex: () => focused,
            selectedIndexes: () => [...selected],
        };
    }

    /** jsdom has no layout, so the shortcut visibility check needs a stubbed offsetParent. */
    function makeVisible(el: HTMLElement): void {
        Object.defineProperty(el, 'offsetParent', { get: () => document.body });
    }

    function pressKey(key: string, target: EventTarget = document): void {
        target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
    }

    it('keyNav: j/k move the focused row (clamped), Enter opens it, x toggles its selection', async () => {
        const rows = [
            { id: 1, name: 'alpha' },
            { id: 2, name: 'beta' },
        ];
        const f = await create('standard');
        f.componentRef.setInput('keyNav', true);
        f.detectChanges();
        makeVisible(f.nativeElement as HTMLElement);
        const c = f.componentInstance;
        const grid = navApi(rows);
        c.onGridReady(grid);

        pressKey('j');
        expect(grid.focusedIndex()).toBe(0); // first press lands on the first row
        pressKey('j');
        expect(grid.focusedIndex()).toBe(1);
        pressKey('j');
        expect(grid.focusedIndex()).toBe(1); // clamped at the last row
        pressKey('k');
        expect(grid.focusedIndex()).toBe(0);

        let opened: Record<string, unknown> | undefined;
        c.rowClick.subscribe((r: Record<string, unknown>) => (opened = r));
        pressKey('Enter');
        expect(opened).toEqual(rows[0]);

        pressKey('x');
        expect(grid.selectedIndexes()).toEqual([0]);
        pressKey('x');
        expect(grid.selectedIndexes()).toEqual([]);
    });

    it('keyNav stays inert while typing, while a dialog is open, and when not opted in', async () => {
        const f = await create('standard');
        makeVisible(f.nativeElement as HTMLElement);
        const c = f.componentInstance;
        const grid = navApi([{ id: 1 }]);
        c.onGridReady(grid);

        pressKey('j'); // keyNav not opted in
        expect(grid.focusedIndex()).toBeNull();

        f.componentRef.setInput('keyNav', true);
        f.detectChanges();
        const input = document.createElement('input');
        document.body.appendChild(input);
        try {
            pressKey('j', input); // typing in a field — exempt
            expect(grid.focusedIndex()).toBeNull();
        } finally {
            input.remove();
        }

        const overlay = document.createElement('div');
        overlay.className = 'cdk-overlay-container';
        const button = document.createElement('button');
        overlay.appendChild(button);
        document.body.appendChild(overlay);
        try {
            pressKey('j', button); // focus inside an open dialog — exempt
            expect(grid.focusedIndex()).toBeNull();
        } finally {
            overlay.remove();
        }
    });

    it("'/' opens and targets the quick filter of the visible searchable table", async () => {
        const f = await create('standard');
        makeVisible(f.nativeElement as HTMLElement);
        const c = f.componentInstance;
        expect(c.searchOpen()).toBe(false);
        pressKey('/');
        expect(c.searchOpen()).toBe(true);
    });

    // `refresh()` schedules a deferred `refreshAllCells`, so a count-only mock still needs the members
    // that timer touches — otherwise the callback throws after the test and vitest exits non-zero.
    const apiWith = (rowCount: number) =>
        ({ isDestroyed: () => false, refreshCells: vi.fn(), getDisplayedRowCount: () => rowCount }) as never;

    // Audit F2 (WCAG 4.1.3 Status Messages). Assert the RENDERED live region, never just the computed:
    // a getter returning the right string while nothing reaches the DOM is the failure mode this guards.
    it('announces the displayed row count in a polite live region', async () => {
        const f = await create('standard');
        const c = f.componentInstance;
        const region = (f.nativeElement as HTMLElement).querySelector('[role="status"][aria-live="polite"]');
        expect(region).not.toBeNull();

        // nothing announced before the grid has reported a count
        expect(region!.textContent?.trim()).toBe('');

        c.refresh({ api: apiWith(2) });
        f.detectChanges();
        expect(region!.textContent?.trim()).toBe('2 rows');

        // singular, and the filtered wording once a quick filter is active
        c.refresh({ api: apiWith(1) });
        f.detectChanges();
        expect(region!.textContent?.trim()).toBe('1 row');

        c.search.set('alpha');
        c.onFilterChanged({ api: apiWith(1) });
        f.detectChanges();
        expect(region!.textContent?.trim()).toBe('1 matching row');

        c.onFilterChanged({ api: apiWith(0) });
        f.detectChanges();
        expect(region!.textContent?.trim()).toBe('No matching rows');
    });

    it('announces nothing while loading, so a mid-fetch 0 is never read out', async () => {
        const f = await create('standard');
        const c = f.componentInstance;
        c.onFilterChanged({ api: apiWith(0) });
        f.componentRef.setInput('loading', true);
        f.detectChanges();
        const region = (f.nativeElement as HTMLElement).querySelector('[role="status"][aria-live="polite"]');
        expect(region!.textContent?.trim()).toBe('');

        f.componentRef.setInput('loading', false);
        f.detectChanges();
        expect(region!.textContent?.trim()).toBe('No rows');
    });

    it('suppresses the horizontal scrollbar only while the grid is empty (EMPTY-GRID-HSCROLL-1)', async () => {
        const f = await create('standard');
        expect(f.componentInstance.suppressHScroll()).toBe(false);
        f.componentRef.setInput('rows', []);
        f.detectChanges();
        expect(f.componentInstance.suppressHScroll()).toBe(true);
    });

    describe('sizing + pagination', () => {
        const grid = (f: { debugElement: import('@angular/core').DebugElement }) =>
            f.debugElement.query(By.directive(AgGridAngular));
        const many = (n: number) => Array.from({ length: n }, (_, i) => ({ id: i, name: 'r' + i }));

        it('pages 10 rows by default, and the selector offers that size (no ag-Grid #94/#95 warning)', async () => {
            const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
            try {
                const f = await create('mini');
                f.componentRef.setInput('rows', many(30));
                f.detectChanges();
                const g = grid(f).componentInstance as AgGridAngular;
                expect(g.paginationPageSize).toBe(10);
                expect(g.paginationPageSizeSelector).toEqual([10, 25, 50, 100]);
                const pageSizeWarnings = warn.mock.calls
                    .flat()
                    .filter((m) => /#9[45]\b|paginationPageSize/.test(String(m)));
                expect(pageSizeWarnings).toEqual([]);
            } finally {
                warn.mockRestore();
            }
        });

        it("merges a host's page size that the default list lacks into the selector, in order", async () => {
            const f = await create('mini');
            f.componentRef.setInput('pageSize', 20);
            f.detectChanges();
            expect(f.componentInstance.pageSizeOptions()).toEqual([10, 20, 25, 50, 100]);
            expect((grid(f).componentInstance as AgGridAngular).paginationPageSizeSelector).toEqual([
                10, 20, 25, 50, 100,
            ]);
            f.componentRef.setInput('pageSize', 50); // already listed ⇒ not duplicated
            f.detectChanges();
            expect(f.componentInstance.pageSizeOptions()).toEqual([10, 25, 50, 100]);
        });

        it('fits its rows by default (autoHeight, no inline height)', async () => {
            const f = await create('mini');
            const el = grid(f);
            expect((el.componentInstance as AgGridAngular).domLayout).toBe('autoHeight');
            expect((el.nativeElement as HTMLElement).style.height).toBe('');
        });

        it('keeps a fixed box only when the host asks for a height', async () => {
            const f = await create('mini');
            f.componentRef.setInput('height', '15rem');
            f.detectChanges();
            const el = grid(f);
            expect((el.componentInstance as AgGridAngular).domLayout).toBe('normal');
            expect((el.nativeElement as HTMLElement).style.height).toBe('15rem');
        });

        it('hides the pager while every row fits on one page, shows it once rows overflow', async () => {
            const f = await create('mini'); // 2 rows
            expect((grid(f).componentInstance as AgGridAngular).suppressPaginationPanel).toBe(true);
            f.componentRef.setInput('rows', many(11));
            f.detectChanges();
            expect((grid(f).componentInstance as AgGridAngular).suppressPaginationPanel).toBe(false);
        });

        it('an empty table is a compact empty state, not a grid; loading keeps the grid (overlay)', async () => {
            const f = await create('mini');
            f.componentRef.setInput('rows', []);
            f.componentRef.setInput('noRowsTitle', 'No calls yet');
            f.componentRef.setInput('noRowsHint', 'Rows appear once the Collector delivers.');
            f.detectChanges();
            const host = f.nativeElement as HTMLElement;
            expect(grid(f)).toBeNull();
            const empty = host.querySelector('inspecto-empty-state');
            expect(empty?.textContent).toContain('No calls yet');
            expect(empty?.textContent).toContain('Rows appear once the Collector delivers.');
            expect(f.componentInstance.countAnnouncement()).toBe('No rows');
            await expectNoA11yViolations(host);

            f.componentRef.setInput('loading', true); // a fetch in flight: the grid's loading overlay, not "empty"
            f.detectChanges();
            expect(grid(f)).not.toBeNull();
            expect(host.querySelector('inspecto-empty-state')).toBeNull();
        });

        describe('row-count caption (one-page grid, no pager)', () => {
            const captionEl = (f: { nativeElement: HTMLElement }) =>
                f.nativeElement.querySelector('ag-grid-angular + div.text-xs') as HTMLElement | null;

            it('says "N rows" / "1 row" under a one-page grid', async () => {
                const f = await create('mini');
                f.componentRef.setInput('rows', many(3));
                f.detectChanges();
                expect(captionEl(f)?.textContent?.trim()).toBe('3 rows');
                f.componentRef.setInput('rows', many(1));
                f.detectChanges();
                expect(captionEl(f)?.textContent?.trim()).toBe('1 row');
            });

            it('states the DISPLAYED count once a quick filter narrows the rows ("2 of 9 rows")', async () => {
                const f = await create('mini');
                const c = f.componentInstance;
                f.componentRef.setInput('rows', many(9));
                c.search.set('r1');
                c.onFilterChanged({ api: apiWith(2) });
                f.detectChanges();
                expect(captionEl(f)?.textContent?.trim()).toBe('2 of 9 rows');
            });

            it('is absent when the pager shows, when empty, while loading, and under "Load more"', async () => {
                const f = await create('mini');
                f.componentRef.setInput('rows', many(11)); // pager visible — its own summary counts
                f.detectChanges();
                expect(captionEl(f)).toBeNull();

                f.componentRef.setInput('rows', []); // the empty state speaks instead
                f.detectChanges();
                expect(captionEl(f)).toBeNull();

                f.componentRef.setInput('loading', true); // grid mounted with its overlay, no count yet
                f.detectChanges();
                expect(captionEl(f)).toBeNull();

                f.componentRef.setInput('loading', false);
                f.componentRef.setInput('rows', many(3));
                f.componentRef.setInput('serverPage', true);
                f.componentRef.setInput('hasMore', true); // the strip already says "Showing 3 …"
                f.detectChanges();
                expect(captionEl(f)).toBeNull();
            });

            it('is plain text, not a second live region, and axe-clean', async () => {
                const f = await create('mini');
                const el = captionEl(f)!;
                expect(el.textContent?.trim()).toBe('2 rows');
                expect(el.getAttribute('aria-live')).toBeNull();
                expect(el.getAttribute('role')).toBeNull();
                // outside ag-Grid's own internals, exactly one polite region (the sr-only status) — the
                // caption does not double-announce
                const ours = Array.from(
                    (f.nativeElement as HTMLElement).querySelectorAll('[aria-live="polite"]'),
                ).filter((n) => !n.closest('ag-grid-angular'));
                expect(ours.length).toBe(1);
                expect(ours[0].classList.contains('sr-only')).toBe(true);
                await expectNoA11yViolations(f.nativeElement);
            });
        });

        it('has no a11y violations (short table, fits its rows)', async () => {
            const f = await create('mini');
            await expectNoA11yViolations(f.nativeElement);
        });
    });
});
