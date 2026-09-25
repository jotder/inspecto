import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { DatasetResultService } from 'app/inspecto/viz/dataset-result.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { Widget } from './widget-types';
import { WidgetsService } from './widgets.service';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { DrillEvent, WidgetHostComponent } from './widget-host.component';

const DS: Dataset = {
    id: 'cdr_sample',
    name: 'cdr_sample',
    kind: 'virtual',
    sourceName: 'cdr',
    columns: [{ name: 'duration_s', type: 'number', role: 'measure' }],
    measures: [],
    calculated: [],
};
const WIDGET: Widget = {
    id: 'total_dur',
    name: 'Total duration',
    datasetId: 'cdr_sample',
    vizType: 'kpi',
    controls: { value: [{ field: 'duration_s', agg: 'sum' }] },
};

function create(providers: unknown[] = []) {
    TestBed.configureTestingModule({
        imports: [WidgetHostComponent],
        providers: [
            provideNoopAnimations(),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            ...providers,
        ],
    });
    return TestBed.createComponent(WidgetHostComponent);
}

describe('WidgetHostComponent', () => {
    // No plugin side-effect import here, so seed the (guarded) builtins — order-independent under the
    // shared per-worker registry.
    beforeEach(() => registerBuiltinViz());

    it('pre-loaded mode: resolves the plugin from an already-supplied widget/dataset, no fetch', () => {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
        ]);
        fixture.componentRef.setInput('widget', WIDGET);
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        expect(fixture.componentInstance.resolvedWidget()).toBe(WIDGET);
        expect(fixture.componentInstance.plugin()?.meta.type).toBe('kpi');
    });

    it('self-fetch mode: fetches the widget by id, then its dataset', () => {
        const fixture = create([
            { provide: WidgetsService, useValue: { get: () => of(WIDGET) } },
            { provide: DatasetsService, useValue: { get: () => of(DS) } },
        ]);
        fixture.componentRef.setInput('widgetId', 'total_dur');
        fixture.detectChanges();
        expect(fixture.componentInstance.resolvedWidget()).toEqual(WIDGET);
        expect(fixture.componentInstance.resolvedDataset()).toEqual(DS);
    });

    it('renders the empty (loading) state with no a11y violations', async () => {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
        ]);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('canExport is true for a chartjs-rendered widget, false for KPI (its component escape hatch)', () => {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
        ]);
        fixture.componentRef.setInput('widget', { ...WIDGET, vizType: 'bar' });
        fixture.componentRef.setInput('dataset', DS);
        expect(fixture.componentInstance.canExport()).toBe(true);
        fixture.componentRef.setInput('widget', WIDGET); // vizType: 'kpi'
        expect(fixture.componentInstance.canExport()).toBe(false);
    });

    it('view-bound widget: no dataset fetch, no query — renders the saved-view arm', () => {
        const viewWidget: Widget = {
            id: 'w',
            name: 'Dhaka map',
            datasetId: '',
            vizType: 'geo-map',
            controls: {},
            viewId: 'dhaka-network',
        };
        let datasetFetched = false;
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            {
                provide: DatasetsService,
                useValue: {
                    get: () => {
                        datasetFetched = true;
                        return of(DS);
                    },
                },
            },
        ]);
        fixture.componentRef.setInput('widget', viewWidget);
        fixture.detectChanges();
        expect(fixture.componentInstance.viewBound()).toBe(true);
        expect(fixture.componentInstance.canExport()).toBe(false);
        expect(datasetFetched).toBe(false);
        expect(fixture.nativeElement.querySelector('inspecto-viz-render')).toBeTruthy();
    });

    it('shows the "access revoked" empty-state when a shared-bound dataset no longer resolves', async () => {
        const shared: Dataset = { ...DS, kind: 'physical', physicalRef: 'shared/analytics-hub/fx_rates_daily' };
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            { provide: DatasetResultService, useValue: { run: () => Promise.resolve({ ok: false, rows: [] }) } },
        ]);
        fixture.componentRef.setInput('widget', { ...WIDGET, vizType: 'bar' });
        fixture.componentRef.setInput('dataset', shared);
        fixture.detectChanges();
        await new Promise((r) => setTimeout(r)); // let the run effect's promise settle
        fixture.detectChanges();
        expect(fixture.componentInstance.showRevoked()).toBe(true);
        expect(fixture.nativeElement.querySelector('inspecto-empty-state')).toBeTruthy();
    });

    it('does not show the revoked state for a local dataset even if a run fails', async () => {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            { provide: DatasetResultService, useValue: { run: () => Promise.resolve({ ok: false, rows: [] }) } },
        ]);
        fixture.componentRef.setInput('widget', { ...WIDGET, vizType: 'bar' });
        fixture.componentRef.setInput('dataset', DS); // local (no shared physicalRef)
        fixture.detectChanges();
        await new Promise((r) => setTimeout(r));
        fixture.detectChanges();
        expect(fixture.componentInstance.showRevoked()).toBe(false);
    });

    it('resolves a category click to the widget’s x-channel field and emits a drill event', () => {
        const barWidget: Widget = {
            ...WIDGET,
            vizType: 'bar',
            controls: { x: [{ field: 'tariff' }], y: [{ field: 'duration_s', agg: 'sum' }] },
        };
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
        ]);
        fixture.componentRef.setInput('widget', barWidget);
        fixture.componentRef.setInput('dataset', DS);
        let emitted: { field: string; value: string } | undefined;
        fixture.componentInstance.drill.subscribe((v) => (emitted = v));
        fixture.componentInstance.onCategoryClick('premium');
        expect(emitted).toEqual({ field: 'tariff', value: 'premium' });
    });

    it('resolves a heatmap cell click to TWO pairs — the rows field and the columns field', () => {
        const heatWidget: Widget = {
            ...WIDGET,
            vizType: 'heatmap',
            controls: {
                rows: [{ field: 'control' }],
                columns: [{ field: 'event_date' }],
                value: [{ field: 'breaks', agg: 'sum' }],
            },
        };
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
        ]);
        fixture.componentRef.setInput('widget', heatWidget);
        fixture.componentRef.setInput('dataset', DS);
        let emitted: DrillEvent | undefined;
        fixture.componentInstance.drill.subscribe((v) => (emitted = v));
        fixture.componentInstance.onCellClick({ row: 'RA-C02', column: '2025-10-01' });
        expect(emitted).toEqual({
            field: 'control',
            value: 'RA-C02',
            and: [{ field: 'event_date', value: '2025-10-01' }],
        });
        // A category click is not a heatmap seam: with no x/series channel it drills on nothing.
        emitted = undefined;
        fixture.componentInstance.onCategoryClick('RA-C02');
        expect(emitted).toBeUndefined();
    });

    it('treemap: a group cell drills on the group field alone, a subgroup cell on subgroup AND its group', () => {
        const treemapWidget: Widget = {
            ...WIDGET,
            vizType: 'treemap',
            controls: {
                group: [{ field: 'typology' }],
                subgroup: [{ field: 'channel' }],
                value: [{ field: 'loss', agg: 'sum' }],
            },
        };
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
        ]);
        fixture.componentRef.setInput('widget', treemapWidget);
        fixture.componentRef.setInput('dataset', DS);
        const emitted: DrillEvent[] = [];
        fixture.componentInstance.drill.subscribe((v) => emitted.push(v));
        fixture.componentInstance.onChannelClick({ channel: 'group', value: 'SIM box' });
        fixture.componentInstance.onChannelClick({ channel: 'subgroup', value: 'Online', group: 'SIM box' });
        // A blank parent group is still a value: the pair is '', not dropped.
        fixture.componentInstance.onChannelClick({ channel: 'subgroup', value: 'Dealer', group: '' });
        expect(emitted).toEqual([
            { field: 'typology', value: 'SIM box' },
            { field: 'channel', value: 'Online', and: [{ field: 'typology', value: 'SIM box' }] },
            { field: 'channel', value: 'Dealer', and: [{ field: 'typology', value: '' }] },
        ]);
        expect(emitted[0].and).toBeUndefined();
    });

    it('the tile header shows the widget title and subtitle', () => {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            { provide: DatasetResultService, useValue: { run: () => new Promise(() => undefined) } },
        ]);
        fixture.componentRef.setInput('widget', {
            ...WIDGET,
            options: { title: 'Exposure', subtitle: 'SAR, last 30 days' },
        });
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('inspecto-tile-card h2')?.textContent?.trim()).toBe('Exposure');
        expect(el.querySelector('inspecto-tile-card header p')?.textContent?.trim()).toBe('SAR, last 30 days');
    });

    it('while the query runs the tile shows a skeleton shaped like the Widget type, then the render', async () => {
        let resolve!: (v: unknown) => void;
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            { provide: DatasetResultService, useValue: { run: () => new Promise((r) => (resolve = r)) } },
        ]);
        fixture.componentRef.setInput('widget', WIDGET); // kpi
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[data-testid="tile-skeleton"]')?.getAttribute('data-shape')).toBe('kpi');
        expect(el.querySelector('inspecto-viz-render')).toBeNull();
        await expectNoA11yViolations(el);

        resolve({ ok: true, rows: [{ sum_duration_s: 5 }] });
        await fixture.whenStable();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="tile-skeleton"]')).toBeNull();
        expect(el.querySelector('inspecto-viz-render')).toBeTruthy();
    });

    it('a successful run with no rows shows the compact empty line, not an empty chart', async () => {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            { provide: DatasetResultService, useValue: { run: () => Promise.resolve({ ok: true, rows: [] }) } },
        ]);
        fixture.componentRef.setInput('widget', { ...WIDGET, vizType: 'bar' });
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[data-testid="tile-empty"]')?.textContent).toContain('No data for this selection');
        expect(el.querySelector('inspecto-viz-render')).toBeNull();
        expect(el.querySelector('inspecto-empty-state')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('the KPI size cycle lives in the tile action set and drives the KPI (which drops its own button)', async () => {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            {
                provide: DatasetResultService,
                useValue: { run: () => Promise.resolve({ ok: true, rows: [{ sum_duration_s: 5 }] }) },
            },
        ]);
        fixture.componentRef.setInput('widget', WIDGET);
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const size = el.querySelector(
            '[data-testid="tile-actions"] button[aria-label^="KPI size"]',
        ) as HTMLButtonElement;
        expect(size.getAttribute('aria-label')).toContain('standard');
        expect(el.querySelectorAll('inspecto-kpi button').length).toBe(0);
        size.click();
        fixture.detectChanges();
        expect(size.getAttribute('aria-label')).toContain('max');
        expect(el.querySelector('[data-testid="kpi-value"]')?.className).toContain('text-6xl');
        await expectNoA11yViolations(el);
    });

    it('a chart tile offers Export as PNG in its action set once rendered', async () => {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            {
                provide: DatasetResultService,
                useValue: { run: () => Promise.resolve({ ok: true, rows: [{ x: 'a', y: 1 }] }) },
            },
        ]);
        fixture.componentRef.setInput('widget', { ...WIDGET, vizType: 'bar' });
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const btn = (fixture.nativeElement as HTMLElement).querySelector(
            '[data-testid="tile-actions"] button[aria-label="Export as PNG"]',
        );
        expect(btn?.querySelector('mat-icon')?.getAttribute('svgIcon')).toBe('heroicons_outline:arrow-down-tray');
    });

    it('a throttled run shows the explained Rate limited state with Retry — not an empty chart', async () => {
        let calls = 0;
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            {
                provide: DatasetResultService,
                useValue: {
                    run: () => {
                        calls++;
                        return Promise.resolve(
                            calls === 1
                                ? { ok: false, rows: [], throttled: true, error: 'Rate limited' }
                                : { ok: true, rows: [{ sum_duration_s: 5 }] },
                        );
                    },
                },
            },
        ]);
        fixture.componentRef.setInput('widget', WIDGET);
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const alert = fixture.nativeElement.querySelector('inspecto-alert');
        expect(alert?.textContent).toContain('Rate limited');
        expect(fixture.nativeElement.querySelector('inspecto-viz-render')).toBeNull();
        expect(fixture.nativeElement.querySelector('[data-testid="tile-empty"]')).toBeNull();
        expect(fixture.nativeElement.querySelector('[data-testid="tile-skeleton"]')).toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);

        (alert.querySelector('button') as HTMLButtonElement).click();
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(calls).toBe(2);
        expect(fixture.nativeElement.querySelector('inspecto-alert')).toBeNull();
        expect(fixture.nativeElement.querySelector('inspecto-viz-render')).toBeTruthy();
    });
});
