import { signal } from '@angular/core';
import { SessionService } from 'app/inspecto/auth/session.service';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { KpisService } from 'app/inspecto/api';
import { LensService } from 'app/inspecto/api/lens.service';
import { Router } from '@angular/router';
import { GammaConfigService } from '@gamma/services/config';
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { DatasetResultService } from 'app/inspecto/viz/dataset-result.service';
import { ShowMeService } from 'app/inspecto/viz/show-me.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { ToastrService } from 'ngx-toastr';
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

    it('a KPI tile bound to a KPI definition and NO Dataset renders the KPI without a Dataset (LIVEFIX2 #2)', async () => {
        const value = vi.fn(() => of(null as never));
        let datasetFetched = false;
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: { get: () => ((datasetFetched = true), of(DS)) } },
            { provide: KpisService, useValue: { value } },
        ]);
        fixture.componentRef.setInput('widget', {
            ...WIDGET,
            datasetId: '',
            controls: {},
            options: { kpi: { kpiId: 'leakage_found' } },
        });
        fixture.componentRef.setInput('asOf', '2026-10-09');
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(fixture.componentInstance.kpiBound()).toBe(true);
        expect(fixture.componentInstance.tileState()).toBe('ready');
        expect(datasetFetched).toBe(false);
        // I-12: a KPI with no picked range also asks for its projection (the 4th argument).
        expect(value).toHaveBeenCalledWith('leakage_found', '2026-10-09', undefined, true);
    });

    it("evaluates a kpiId-bound KPI at the host Dashboard's asOf day", async () => {
        const value = vi.fn(() => of(null as never));
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            {
                provide: DatasetResultService,
                useValue: { run: () => Promise.resolve({ ok: true, rows: [{ sum_duration_s: 5 }] }) },
            },
            { provide: KpisService, useValue: { value } },
        ]);
        fixture.componentRef.setInput('widget', { ...WIDGET, options: { kpi: { kpiId: 'refunds' } } });
        fixture.componentRef.setInput('dataset', DS);
        fixture.componentRef.setInput('asOf', '2026-08-13');
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(value).toHaveBeenCalledWith('refunds', '2026-08-13', undefined, true);
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

    it('a result the server cut at its row limit says so under the render, naming the row count', async () => {
        const rows = Array.from({ length: 1200 }, (_, i) => ({ x: `c${i}`, y: i }));
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            {
                provide: DatasetResultService,
                useValue: { run: () => Promise.resolve({ ok: true, rows, truncated: true }) },
            },
        ]);
        fixture.componentRef.setInput('widget', { ...WIDGET, vizType: 'bar' });
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[data-testid="tile-truncated"]')?.textContent?.trim()).toBe(
            'Showing the first 1,200 rows — the result was cut',
        );
        await expectNoA11yViolations(el);
    });

    it('a complete (untruncated) result shows no truncation note', async () => {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            {
                provide: DatasetResultService,
                useValue: { run: () => Promise.resolve({ ok: true, rows: [{ x: 'a', y: 1 }], truncated: false }) },
            },
        ]);
        fixture.componentRef.setInput('widget', { ...WIDGET, vizType: 'bar' });
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('inspecto-viz-render')).toBeTruthy();
        expect(el.querySelector('[data-testid="tile-truncated"]')).toBeNull();
        expect(el.textContent).not.toContain('the result was cut');
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

    // QUERY-BOUND-WIDGET-1
    it('a query-bound widget runs its spec over the saved Query (queryId reaches DatasetResultService)', async () => {
        const specs: { queryId?: string }[] = [];
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            {
                provide: DatasetResultService,
                useValue: {
                    run: (spec: { queryId?: string }) => {
                        specs.push(spec);
                        return Promise.resolve({ ok: true, rows: [{ sum_duration_s: 5 }] });
                    },
                },
            },
        ]);
        fixture.componentRef.setInput('widget', { ...WIDGET, queryId: 'long_calls' });
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        await fixture.whenStable();
        expect(specs[0].queryId).toBe('long_calls');
    });

    it('a query-bound widget whose query is gone shows the refusal, not an empty chart', async () => {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            {
                provide: DatasetResultService,
                useValue: { run: () => Promise.resolve({ ok: false, rows: [], error: "no query 'long_calls'" }) },
            },
        ]);
        fixture.componentRef.setInput('widget', { ...WIDGET, vizType: 'bar', queryId: 'long_calls' });
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const alert = fixture.nativeElement.querySelector('[data-testid="tile-query-error"]');
        expect(alert?.textContent).toContain("no query 'long_calls'");
        expect(fixture.nativeElement.querySelector('inspecto-viz-render')).toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    function withRun(result: unknown) {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            { provide: DatasetResultService, useValue: { run: () => Promise.resolve(result) } },
        ]);
        fixture.componentRef.setInput('widget', { ...WIDGET, vizType: 'bar' });
        fixture.componentRef.setInput('dataset', DS);
        return fixture;
    }

    it('any failed data run shows an error alert with the server message, not an empty chart', async () => {
        const fixture = withRun({ ok: false, rows: [], error: 'Binder Error: column amount not found' });
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const alert = fixture.nativeElement.querySelector('[data-testid="tile-run-error"]');
        expect(alert?.textContent).toContain('Data unavailable');
        expect(alert?.textContent).toContain('Binder Error: column amount not found');
        expect(fixture.nativeElement.querySelector('[data-testid="tile-query-error"]')).toBeNull();
        expect(fixture.nativeElement.querySelector('inspecto-viz-render')).toBeNull();
        expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('a genuinely empty result is the no-data state, not an error', async () => {
        const fixture = withRun({ ok: true, rows: [] });
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(fixture.componentInstance.runError()).toBeNull();
        expect(fixture.componentInstance.tileState()).toBe('empty');
        expect(fixture.nativeElement.querySelector('inspecto-alert')).toBeNull();
    });

    it('a query-bound failed run keeps the "Bound query unavailable" wording', async () => {
        const fixture = withRun({ ok: false, rows: [], error: 'refused' });
        fixture.componentRef.setInput('widget', { ...WIDGET, vizType: 'bar', queryId: 'q1' });
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const alert = fixture.nativeElement.querySelector('[data-testid="tile-query-error"]');
        expect(alert?.textContent).toContain('Bound query unavailable');
        expect(fixture.nativeElement.querySelector('[data-testid="tile-run-error"]')).toBeNull();
    });

    // ── BI-INSIGHTS-1 I-17: "Alert me…" drafts a KPI Alert Rule on the Alerts pane — a link, never a write ──
    function kpiTile(canAuthor: boolean) {
        const navigate = vi.fn(() => Promise.resolve(true));
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            { provide: KpisService, useValue: { value: () => of(null as never) } },
            { provide: LensService, useValue: { canAuthorAlertRules: () => canAuthor } },
            { provide: Router, useValue: { navigate } },
        ]);
        fixture.componentRef.setInput('widget', {
            ...WIDGET,
            datasetId: '',
            controls: {},
            options: { kpi: { kpiId: 'leakage_found' } },
        });
        fixture.detectChanges();
        return { fixture, navigate };
    }

    it('a KPI-bound tile offers "Alert me…", which opens the prefilled create on its value or its projection', async () => {
        const { fixture, navigate } = kpiTile(true);
        await fixture.whenStable();
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('[data-testid="kpi-alert-me"]')).not.toBeNull();
        fixture.componentInstance.alertMe('value');
        expect(navigate).toHaveBeenCalledWith(['/alerts'], { queryParams: { newRule: '1', kpi: 'leakage_found' } });
        fixture.componentInstance.alertMe('projection');
        expect(navigate).toHaveBeenLastCalledWith(['/alerts'], {
            queryParams: { newRule: '1', kpi: 'leakage_found', projection: 'value' },
        });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('offers no "Alert me…" to a user who may not author Alert Rules, nor on a tile with no KPI', async () => {
        const { fixture } = kpiTile(false);
        await fixture.whenStable();
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('[data-testid="kpi-alert-me"]')).toBeNull();
        expect(fixture.componentInstance.alertKpi()).toBe('');
    });

    // ── BI-INSIGHTS-1 I-20 on Dashboards: Show-Me from the tile's menu, applied only on a click ──
    function showMeTile(opts: { author?: boolean; confirm?: boolean } = {}) {
        const save = vi.fn((w: Widget) => of(w));
        const suggest = vi.fn(async () => [
            {
                id: 'compare-lastYear' as const,
                text: 'Over a year of history (60 points).',
                action: 'Compare with the same period last year',
                options: { compare: { kind: 'lastYear' as const } },
            },
        ]);
        const confirm = vi.fn(async () => opts.confirm ?? true);
        const fixture = create([
            { provide: WidgetsService, useValue: { save } },
            { provide: DatasetsService, useValue: {} },
            {
                provide: DatasetResultService,
                useValue: {
                    run: () => Promise.resolve({ ok: true, rows: [{ day: '2026-01-01', sum_duration_s: 1 }] }),
                },
            },
            { provide: ShowMeService, useValue: { suggest } },
            { provide: InspectoConfirmService, useValue: { confirm } },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
            {
                provide: LensService,
                useValue: { canAuthorWorkbench: () => opts.author ?? true, canAuthorAlertRules: () => false },
            },
        ]);
        fixture.componentRef.setInput('widget', {
            ...WIDGET,
            vizType: 'line',
            controls: { x: [{ field: 'day', grain: 'day' }], y: [{ field: 'duration_s', agg: 'sum' }] },
        });
        fixture.componentRef.setInput('dataset', DS);
        fixture.componentRef.setInput('showMe', true);
        fixture.detectChanges();
        return { fixture, save, suggest, confirm };
    }

    it('a Dashboard line tile offers Show-Me from the shared service, applied only on a click', async () => {
        const { fixture, save, suggest, confirm } = showMeTile();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(fixture.componentInstance.showMeOffered()).toBe(true);
        expect(suggest).not.toHaveBeenCalled(); // nothing read until the menu opens
        fixture.componentInstance.loadSuggestions();
        await fixture.whenStable();
        expect(suggest).toHaveBeenCalledWith(expect.objectContaining({ datasetId: 'cdr_sample', sourceName: 'cdr' }));
        const s = fixture.componentInstance.suggestions()![0];
        expect(save).not.toHaveBeenCalled();
        await fixture.componentInstance.applySuggestion(s);
        expect(confirm).toHaveBeenCalled();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ options: { compare: { kind: 'lastYear' } } }), {
            update: true,
        });
        expect(fixture.componentInstance.resolvedWidget()?.options).toEqual({ compare: { kind: 'lastYear' } });
    });

    it('saves nothing when the confirmation is declined', async () => {
        const { fixture, save } = showMeTile({ confirm: false });
        await fixture.whenStable();
        fixture.componentInstance.loadSuggestions();
        await fixture.whenStable();
        await fixture.componentInstance.applySuggestion(fixture.componentInstance.suggestions()![0]);
        expect(save).not.toHaveBeenCalled();
    });

    it('no Show-Me for a reader who may not save the Widget, nor off a Dashboard', async () => {
        const { fixture } = showMeTile({ author: false });
        await fixture.whenStable();
        fixture.detectChanges();
        expect(fixture.componentInstance.showMeOffered()).toBe(false);
        expect(fixture.nativeElement.querySelector('[data-testid="tile-show-me"]')).toBeNull();
    });
});

describe('WidgetHostComponent — why Explain is off (INSIGHT-EXPLAIN-HIDDEN-REASON-1)', () => {
    beforeEach(() => registerBuiltinViz());

    function explainTile(opts: { author?: boolean; vizType?: string; insights?: boolean } = {}) {
        const fixture = create([
            { provide: WidgetsService, useValue: {} },
            { provide: DatasetsService, useValue: {} },
            {
                provide: DatasetResultService,
                useValue: {
                    run: () => Promise.resolve({ ok: true, rows: [{ day: '2026-01-01', sum_duration_s: 1 }] }),
                },
            },
            {
                provide: SessionService,
                useValue: {
                    features: signal({ insights: opts.insights ?? true }),
                    authMode: () => 'none',
                    capabilities: () => [],
                },
            },
            {
                provide: LensService,
                useValue: { canAuthorWorkbench: () => opts.author ?? true, canAuthorAlertRules: () => false },
            },
        ]);
        const line = opts.vizType !== 'kpi';
        fixture.componentRef.setInput('widget', {
            ...WIDGET,
            vizType: opts.vizType ?? 'line',
            controls: line
                ? { x: [{ field: 'day', grain: 'day' }], y: [{ field: 'duration_s', agg: 'sum' }] }
                : WIDGET.controls,
        });
        fixture.componentRef.setInput('dataset', DS);
        fixture.detectChanges();
        return fixture;
    }

    async function settle(fixture: ReturnType<typeof explainTile>) {
        await fixture.whenStable();
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('an author sees a disabled Explain with the reason on a time chart with no dimension column', async () => {
        const fixture = explainTile();
        const el = await settle(fixture);
        expect(fixture.componentInstance.explainable()).toBeNull();
        expect(fixture.componentInstance.explainReason()).toBe(
            'The Dataset has no dimension column to explain the change by.',
        );
        expect(el.querySelector('[data-testid="explain-change"]')).toBeNull();
        const wrap = el.querySelector('[data-testid="explain-change-reason"]')!;
        expect(wrap.getAttribute('aria-label')).toBe(
            'Explain this change is unavailable: The Dataset has no dimension column to explain the change by.',
        );
        expect(wrap.getAttribute('tabindex')).toBe('0');
        expect((el.querySelector('[data-testid="explain-change-off"]') as HTMLButtonElement).disabled).toBe(true);
        await expectNoA11yViolations(el);
    });

    it('a reader who cannot change the Widget sees nothing', async () => {
        const el = await settle(explainTile({ author: false }));
        expect(el.querySelector('[data-testid="explain-change-reason"]')).toBeNull();
    });

    it('stays hidden on a KPI tile', async () => {
        const kpi = explainTile({ vizType: 'kpi' });
        const el = await settle(kpi);
        expect(kpi.componentInstance.explainReason()).toBe('');
        expect(el.querySelector('[data-testid="explain-change-reason"]')).toBeNull();
    });
});
