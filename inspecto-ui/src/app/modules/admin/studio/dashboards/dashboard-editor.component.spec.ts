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

const DS: Dataset = {
    id: 'cdr_sample', name: 'cdr_sample', kind: 'virtual', sourceName: 'cdr',
    columns: [{ name: 'tariff', type: 'string', role: 'dimension' }, { name: 'duration_s', type: 'number', role: 'measure' }],
    measures: [],
    calculated: [],
};
const WIDGET: Widget = { id: 'bar1', name: 'Bar 1', datasetId: 'cdr_sample', vizType: 'bar', controls: { x: [{ field: 'tariff' }], y: [{ field: 'duration_s', agg: 'sum' }] } };

function create(
    save = vi.fn((d: Dashboard) => of(d)),
    existing: Dashboard[] = [],
    widgets: Partial<WidgetsService> = {},
) {
    TestBed.configureTestingModule({
        imports: [DashboardEditorComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: WidgetsService, useValue: { list: () => of([WIDGET]), ...widgets } },
            { provide: DatasetsService, useValue: { list: () => of([DS]) } },
            { provide: DashboardsService, useValue: { get: () => of(null), list: () => of(existing), save } },
            { provide: ToastrService, useValue: { warning: () => undefined, success: () => undefined, error: () => undefined } },
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
        const fixture = create(vi.fn((d: Dashboard) => of(d)), [], { save: widgetSave } as Partial<WidgetsService>);
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
        expect(c.tiles()).toEqual([{ widgetId: 'bar1', span: 1 }]);
        c.toggleSpan(0);
        expect(c.tiles()[0].span).toBe(2);
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
            expect.objectContaining({ id: 'cdr_overview', tiles: [{ widgetId: 'bar1', span: 1 }] }),
            { update: false }, // create mode — edits go through PUT (the backend 409s a re-create)
        );
        expect(nav).toHaveBeenCalledWith(['/studio/dashboards']);
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
});
