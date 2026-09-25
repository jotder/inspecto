import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Widget } from '../widgets/widget-types';
import { WidgetsService } from '../widgets/widgets.service';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { Dashboard } from './dashboard-types';
import { DashboardsService } from './dashboards.service';
import { DashboardEditorComponent } from './dashboard-editor.component';
import { DatasetRowsService, RowSourceRef } from 'app/inspecto/viz/dataset-rows.service';

const DS: Dataset = {
    id: 'cdr_sample',
    name: 'cdr_sample',
    kind: 'virtual',
    sourceName: 'cdr',
    columns: [
        { name: 'tariff', type: 'string', role: 'dimension' },
        { name: 'duration_s', type: 'number', role: 'measure' },
    ],
    measures: [],
    calculated: [],
};
const WIDGET: Widget = {
    id: 'bar1',
    name: 'Bar 1',
    datasetId: 'cdr_sample',
    vizType: 'bar',
    controls: { x: [{ field: 'tariff' }], y: [{ field: 'duration_s', agg: 'sum' }] },
};

/** Records what the rows seam was asked for, and answers with one page. */
const rowsCalls: RowSourceRef[] = [];
function rowsStub(rows: Record<string, unknown>[] = [{ tariff: 'premium' }], truncated = false) {
    return {
        provide: DatasetRowsService,
        useValue: {
            rows: (ds: RowSourceRef) => {
                rowsCalls.push(ds);
                return Promise.resolve({ rows, columns: [], truncated });
            },
        },
    };
}

function create(
    save = vi.fn((d: Dashboard) => of(d)),
    existing: Dashboard[] = [],
    widgets: Partial<WidgetsService> = {},
    rowsProvider: unknown = rowsStub(),
    get: (id: string) => unknown = () => of(null),
) {
    TestBed.configureTestingModule({
        imports: [DashboardEditorComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            rowsProvider as never,
            { provide: WidgetsService, useValue: { list: () => of([WIDGET]), ...widgets } },
            { provide: DatasetsService, useValue: { list: () => of([DS]) } },
            { provide: DashboardsService, useValue: { get, list: () => of(existing), save } },
            {
                provide: ToastrService,
                useValue: { warning: () => undefined, success: () => undefined, error: () => undefined },
            },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    return TestBed.createComponent(DashboardEditorComponent);
}

// AGT-6a: this pane is `kpi_report_builder`'s host. The draft carries the dashboard in `config` and its
// widgets in `prerequisites`, which must be CREATED before the tiles referencing them are placed.
const KPI_DRAFT = {
    label: 'revenue_report',
    clean: true,
    findings: [],
    config: { tiles: [{ widgetId: 'revenue_report_kpi_1', span: 1 }] },
    prerequisites: [
        {
            label: 'revenue_report_kpi_1',
            clean: true,
            findings: [],
            config: {
                vizType: 'kpi',
                datasetId: 'cdr_sample',
                controls: { value: [{ agg: 'sum', field: 'duration_s' }] },
                options: { title: 'sum duration_s' },
            },
        },
    ],
};

describe('DashboardEditorComponent — kpi_report_builder host', () => {
    it('creates the prerequisite widgets first, then tiles them, leaving the dashboard unsaved', () => {
        const widgetSave = vi.fn((w: Widget) => of(w));
        const dashboardSave = vi.fn((d: Dashboard) => of(d));
        const fixture = create(dashboardSave, [], { save: widgetSave } as Partial<WidgetsService>);
        fixture.detectChanges();
        const c = fixture.componentInstance;

        c.applyKpiReport(KPI_DRAFT);

        // The widget was created through the pane's own validated route, with the SERVER's draft shape.
        expect(widgetSave).toHaveBeenCalledTimes(1);
        const [created, opts] = widgetSave.mock.calls[0] as unknown as [Widget, { update: boolean }];
        expect(created.id).toBe('revenue_report_kpi_1');
        expect(created.vizType).toBe('kpi');
        expect(created.datasetId).toBe('cdr_sample');
        expect(created.controls).toEqual({ value: [{ agg: 'sum', field: 'duration_s' }] });
        expect(opts).toEqual({ update: false });

        expect(c.tiles()).toEqual([{ widgetId: 'revenue_report_kpi_1', span: 1 }]);
        // Draft-only: the human still presses Save. The pane must NOT have persisted the dashboard.
        expect(dashboardSave).not.toHaveBeenCalled();
    });

    it('does NOT tile when a widget create fails — broken tiles are worse than no dashboard', () => {
        const widgetSave = vi.fn(() => {
            throw { status: 500 };
        });
        const fixture = create(
            vi.fn((d: Dashboard) => of(d)),
            [],
            { save: widgetSave } as Partial<WidgetsService>,
        );
        fixture.detectChanges();
        const c = fixture.componentInstance;

        c.applyKpiReport(KPI_DRAFT);

        expect(c.tiles()).toEqual([]);
        expect(c.saving()).toBe(false);
    });

    it('passes identity-only args so the model’s derived measures are not overwritten', () => {
        const fixture = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.kpiDataset.set('cdr_sample');
        c.form.controls.name.setValue('revenue_report');

        const args = c.aiKpiArgs();
        expect(args).toEqual({ dataset: 'cdr_sample', title: 'revenue_report' });
        // ⚠ pane args win over the model's — these must never be present.
        expect('measures' in args).toBe(false);
        expect('groupBy' in args).toBe(false);
    });
});

describe('DashboardEditorComponent', () => {
    it('adds, spans and removes tiles', () => {
        const c = create().componentInstance;
        c.addWidget('bar1');
        // UIE-3: a new tile starts at half of the four-column grid, and the width button cycles quarter → full.
        expect(c.tiles()).toEqual([{ widgetId: 'bar1', span: 2 }]);
        c.toggleSpan(0);
        expect(c.tiles()[0].span).toBe(3);
        c.toggleSpan(0);
        expect(c.tiles()[0].span).toBe(4);
        c.toggleSpan(0);
        expect(c.tiles()[0].span).toBe(1);
        c.removeTile(0);
        expect(c.tiles()).toHaveLength(0);
    });

    it('builds the cross-filter column union from the tiled widgets’ datasets', () => {
        const fixture = create();
        fixture.detectChanges(); // load widgets + datasets
        const c = fixture.componentInstance;
        c.addWidget('bar1');
        expect(c.filterColumns().map((col) => col.name)).toEqual(['tariff', 'duration_s']);
    });

    it('saves a dashboard with its tiles and navigates back', () => {
        const save = vi.fn((d: Dashboard) => of(d));
        const fixture = create(save);
        fixture.detectChanges();
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.componentInstance.form.controls.name.setValue('cdr_overview');
        fixture.componentInstance.addWidget('bar1');
        fixture.componentInstance.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({ id: 'cdr_overview', tiles: [{ widgetId: 'bar1', span: 2 }] }),
            { update: false }, // create mode — edits go through PUT (the backend 409s a re-create)
        );
        expect(nav).toHaveBeenCalledWith(['/studio/dashboards']);
    });

    it('UIE-5: round-trips the header — a loaded description / as-of / illustrative flag survives an edit + save', () => {
        const save = vi.fn((d: Dashboard) => of(d));
        const stored: Dashboard = {
            id: 'ra_board',
            name: 'ra_board',
            tiles: [{ widgetId: 'bar1', span: 2 }],
            filter: null,
            description: 'How much leaked?',
            asOf: '2026-09-23',
            illustrative: true,
        };
        const fixture = create(save, [], {}, rowsStub(), () => of(stored));
        fixture.componentInstance.id = 'ra_board';
        vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        fixture.detectChanges();
        const header = fixture.componentInstance.form.controls;
        expect(header.description.value).toBe('How much leaked?');
        expect(header.asOf.value).toBe('2026-09-23');
        expect(header.illustrative.value).toBe(true);
        // The authoring fields are on screen.
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('Description');
        expect(el.textContent).toContain('Illustrative data');

        header.description.setValue('  How much leaked, and how much came back?  ');
        fixture.componentInstance.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({
                id: 'ra_board',
                description: 'How much leaked, and how much came back?',
                asOf: '2026-09-23',
                illustrative: true,
            }),
            { update: true },
        );
    });

    it('UIE-5: blank header fields are not written, and a malformed as-of blocks save', () => {
        const save = vi.fn((d: Dashboard) => of(d));
        const fixture = create(save);
        fixture.detectChanges();
        vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        const c = fixture.componentInstance;
        c.form.controls.name.setValue('plain');
        c.addWidget('bar1');
        c.form.controls.asOf.setValue('23 Sep');
        c.save();
        expect(save).not.toHaveBeenCalled();
        expect(c.form.controls.asOf.hasError('pattern')).toBe(true);

        c.form.controls.asOf.setValue('');
        c.save();
        const saved = save.mock.calls[0][0] as Dashboard;
        expect('description' in saved || 'asOf' in saved || 'illustrative' in saved).toBe(false);
    });

    it('blocks save on a duplicate id (case-insensitive) per the product-wide rule', () => {
        const save = vi.fn((d: Dashboard) => of(d));
        const existing: Dashboard = { id: 'cdr_overview', name: 'cdr_overview', tiles: [], filter: null };
        const fixture = create(save, [existing]);
        fixture.detectChanges(); // loads widgets/datasets + the existing-ids list (create mode)
        fixture.componentInstance.form.controls.name.setValue('CDR_Overview');
        fixture.componentInstance.addWidget('bar1');
        fixture.componentInstance.save();
        expect(save).not.toHaveBeenCalled();
        expect(fixture.componentInstance.form.controls.name.hasError('duplicate')).toBe(true);
    });

    it('does not save with no tiles', () => {
        const save = vi.fn((d: Dashboard) => of(d));
        const fixture = create(save);
        fixture.detectChanges();
        fixture.componentInstance.form.controls.name.setValue('empty');
        fixture.componentInstance.save();
        expect(save).not.toHaveBeenCalled();
    });

    it('renders the empty editor with no a11y violations', async () => {
        const fixture = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('the drill-through asks the store for the tile rows WITH the cross-filter in the request', async () => {
        rowsCalls.length = 0;
        const fixture = create();
        const c = fixture.componentInstance;
        fixture.detectChanges();
        c.addWidget('bar1');
        c.onDrill({ field: 'tariff', value: 'premium' });
        c.drillTileIndex.set(0);
        fixture.detectChanges(); // effects run on change detection
        await fixture.whenStable();
        // The filter must travel in the request: filtering a page that was already cut is the wrong rows.
        const asked = rowsCalls.at(-1);
        expect(asked?.sourceName).toBe('cdr');
        expect(JSON.stringify(asked?.query)).toContain('premium');
        expect(c.drillView()?.rows).toEqual([{ tariff: 'premium' }]);
        expect(c.drillView()?.truncated).toBe(false);
    });

    it('the drill-through reports a truncated page, so the row count cannot read as the whole result', async () => {
        const fixture = create(
            vi.fn((d: Dashboard) => of(d)),
            [],
            {},
            rowsStub([{ tariff: 'premium' }], true),
        );
        const c = fixture.componentInstance;
        fixture.detectChanges();
        c.addWidget('bar1');
        c.drillTileIndex.set(0);
        fixture.detectChanges(); // effects run on change detection
        await fixture.whenStable();
        expect(c.drillView()?.truncated).toBe(true);
    });

    it('exposed-field value suggestions come from the store page, not a hardcoded sample table', async () => {
        rowsCalls.length = 0;
        const fixture = create(
            vi.fn((d: Dashboard) => of(d)),
            [],
            {},
            rowsStub([{ tariff: 'premium' }, { tariff: 'standard' }, { tariff: 'premium' }]),
        );
        const c = fixture.componentInstance;
        fixture.detectChanges();
        c.addWidget('bar1');
        c.exposedFields.set(['tariff']);
        fixture.detectChanges();
        await fixture.whenStable();
        expect(c.exposedValues()).toEqual({ tariff: ['premium', 'standard'] });
    });

    it('a drill click adds an equality condition to the cross-filter', () => {
        const c = create().componentInstance;
        c.onDrill({ field: 'tariff', value: 'premium' });
        expect(c.filter().items).toEqual([{ kind: 'condition', field: 'tariff', operator: '=', value: 'premium' }]);
    });

    it('clicking the same drill value again removes the condition (toggle)', () => {
        const c = create().componentInstance;
        c.onDrill({ field: 'tariff', value: 'premium' });
        c.onDrill({ field: 'tariff', value: 'premium' });
        expect(c.filter().items).toHaveLength(0);
    });

    it('drilling a different value keeps the first condition and adds a second', () => {
        const c = create().componentInstance;
        c.onDrill({ field: 'tariff', value: 'premium' });
        c.onDrill({ field: 'tariff', value: 'standard' });
        expect(c.filter().items).toHaveLength(2);
    });

    it('a heatmap-cell drill adds BOTH its row and column conditions, and a second click removes both', () => {
        const c = create().componentInstance;
        c.onDrill({ field: 'tariff', value: 'premium' }); // an unrelated single-field drill stays put
        const cell = { field: 'control', value: 'RA-C02', and: [{ field: 'event_date', value: '2025-10-01' }] };
        c.onDrill(cell);
        expect(c.filter().items).toEqual([
            { kind: 'condition', field: 'tariff', operator: '=', value: 'premium' },
            { kind: 'condition', field: 'control', operator: '=', value: 'RA-C02' },
            { kind: 'condition', field: 'event_date', operator: '=', value: '2025-10-01' },
        ]);
        c.onDrill(cell);
        expect(c.filter().items).toEqual([{ kind: 'condition', field: 'tariff', operator: '=', value: 'premium' }]);
    });

    it('a heatmap pair with one half already filtered adds only the missing half; the next click removes the pair', () => {
        const c = create().componentInstance;
        c.onDrill({ field: 'control', value: 'RA-C02' });
        const cell = { field: 'control', value: 'RA-C02', and: [{ field: 'event_date', value: '2025-10-01' }] };
        c.onDrill(cell);
        expect(c.filter().items).toHaveLength(2);
        c.onDrill(cell);
        expect(c.filter().items).toHaveLength(0);
    });

    it('clicking ANOTHER heatmap cell moves the selection: its pair replaces the previous one', () => {
        const c = create().componentInstance;
        c.onDrill({ field: 'tariff', value: 'premium' }); // other fields are untouched
        c.onDrill({ field: 'control', value: 'RA-C02', and: [{ field: 'event_date', value: '2025-10-01' }] });
        c.onDrill({ field: 'control', value: 'RA-C07', and: [{ field: 'event_date', value: '2025-10-02' }] });
        expect(c.filter().items).toEqual([
            { kind: 'condition', field: 'tariff', operator: '=', value: 'premium' },
            { kind: 'condition', field: 'control', operator: '=', value: 'RA-C07' },
            { kind: 'condition', field: 'event_date', operator: '=', value: '2025-10-02' },
        ]);
    });

    it('the drill-through of a heatmap-cell drill carries both conditions in the request', async () => {
        rowsCalls.length = 0;
        const fixture = create();
        const c = fixture.componentInstance;
        fixture.detectChanges();
        c.addWidget('bar1');
        c.onDrill({ field: 'tariff', value: 'premium', and: [{ field: 'region', value: 'North' }] });
        c.drillTileIndex.set(0);
        fixture.detectChanges();
        await fixture.whenStable();
        const query = JSON.stringify(rowsCalls.at(-1)?.query);
        expect(query).toContain('premium');
        expect(query).toContain('North');
    });
});
