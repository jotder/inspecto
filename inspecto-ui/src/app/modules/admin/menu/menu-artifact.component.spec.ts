import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ConditionGroup } from 'app/inspecto/query';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { Dashboard } from 'app/modules/admin/studio/dashboards/dashboard-types';
import { DashboardTileComponent } from 'app/modules/admin/studio/dashboards/dashboard-tile.component';
import { DashboardsService } from 'app/modules/admin/studio/dashboards/dashboards.service';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { DrillEvent, WidgetHostComponent } from 'app/modules/admin/studio/widgets/widget-host.component';
import { Widget } from 'app/modules/admin/studio/widgets/widget-types';
import { WidgetsService } from 'app/modules/admin/studio/widgets/widgets.service';
import { LinkViewWidgetComponent } from 'app/modules/admin/studio/link-analysis/link-view-widget.component';
import { MenuArtifactComponent } from './menu-artifact.component';

/** Stands in for the real tile (whose chart host jsdom can't create) and records what the viewer passes it. */
@Component({
    selector: 'app-dashboard-tile',
    standalone: true,
    template: '',
    changeDetection: ChangeDetectionStrategy.Eager,
})
class StubTileComponent {
    readonly widget = input<Widget>();
    readonly dataset = input<Dataset | undefined>(undefined);
    readonly filter = input<ConditionGroup | null>(null);
    readonly drill = output<DrillEvent>();
}
@Component({
    selector: 'app-widget-host',
    standalone: true,
    template: '',
    changeDetection: ChangeDetectionStrategy.Eager,
})
class StubWidgetHostComponent {
    readonly widgetId = input<string>();
}
@Component({
    selector: 'app-link-view-widget',
    standalone: true,
    template: '',
    changeDetection: ChangeDetectionStrategy.Eager,
})
class StubLinkViewWidgetComponent {
    readonly viewId = input<string>();
    readonly showDescription = input(false);
}

const DS: Dataset = {
    id: 'matches',
    name: 'matches',
    kind: 'virtual',
    sourceName: 'matches',
    columns: [{ name: 'STAGE', type: 'string', role: 'dimension' }],
    measures: [],
    calculated: [],
};
const WIDGET: Widget = {
    id: 'w1',
    name: 'Wins by stage',
    datasetId: 'matches',
    vizType: 'bar',
    controls: { x: [{ field: 'STAGE' }] },
};
const SAVED_FILTER: ConditionGroup = {
    kind: 'group',
    op: 'AND',
    items: [{ kind: 'condition', field: 'SEASON', operator: '=', value: '2024' }],
};

function create(dashboard: Dashboard | null, widgets: Widget[] = [WIDGET], datasets: Dataset[] = [DS]) {
    TestBed.configureTestingModule({
        imports: [MenuArtifactComponent],
        providers: [
            provideNoopAnimations(),
            { provide: DashboardsService, useValue: { get: () => of(dashboard) } },
            { provide: WidgetsService, useValue: { list: () => of(widgets) } },
            { provide: DatasetsService, useValue: { list: () => of(datasets) } },
            {
                provide: DatasetRowsService,
                useValue: {
                    rows: () =>
                        Promise.resolve({
                            rows: [{ STAGE: 'Final' }, { STAGE: 'Group' }],
                            columns: [],
                            truncated: false,
                        }),
                },
            },
        ],
    });
    TestBed.overrideComponent(MenuArtifactComponent, {
        remove: { imports: [DashboardTileComponent, WidgetHostComponent, LinkViewWidgetComponent] },
        add: { imports: [StubTileComponent, StubWidgetHostComponent, StubLinkViewWidgetComponent] },
    });
    const f = TestBed.createComponent(MenuArtifactComponent);
    if (dashboard) f.componentRef.setInput('binding', { kind: 'dashboard', componentId: dashboard.id });
    return f;
}

function dashboard(exposedFields: string[]): Dashboard {
    return { id: 'd1', name: 'd1', tiles: [{ widgetId: 'w1', span: 2 }], filter: SAVED_FILTER, exposedFields };
}

async function settle(f: ReturnType<typeof create>): Promise<void> {
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
}

function tile(f: ReturnType<typeof create>): StubTileComponent {
    return f.debugElement.query((d) => d.componentInstance instanceof StubTileComponent).componentInstance;
}

describe('MenuArtifactComponent', () => {
    // R3-04: the Menu item owns the page title, so the saved view's description belongs under it.
    it('asks a saved Link Analysis view for its description under the page title', () => {
        const f = create(null);
        f.componentRef.setInput('binding', { kind: 'link-analysis-view', componentId: 'fraud_entity_graph' });
        f.detectChanges();
        const w = f.debugElement.query((d) => d.componentInstance instanceof StubLinkViewWidgetComponent)
            .componentInstance as StubLinkViewWidgetComponent;
        expect(w.viewId()).toBe('fraud_entity_graph');
        expect(w.showDescription()).toBe(true);
    });

    it('renders the empty state (with a custom message) when there is no binding', async () => {
        const f = create(null);
        f.componentRef.setInput('emptyMessage', 'Pick a report to preview.');
        f.detectChanges();
        expect(f.nativeElement.textContent).toContain('Nothing linked yet');
        expect(f.nativeElement.textContent).toContain('Pick a report to preview.');
        await expectNoA11yViolations(f.nativeElement);
    });

    it('shows no quick-filter bar for a Dashboard without exposed fields, and passes its saved filter to tiles', async () => {
        const f = create(dashboard([]));
        await settle(f);
        expect(f.nativeElement.querySelector('app-dashboard-filter-bar')).toBeNull();
        expect(tile(f).filter()).toEqual(SAVED_FILTER);
        expect(tile(f).dataset()).toEqual(DS);
    });

    it('treats a stored empty filter (`filter:` → `{}`) as no filter, so tiles still get a valid group', async () => {
        const f = create({ ...dashboard([]), filter: {} as ConditionGroup });
        await settle(f);
        expect(tile(f).filter()).toEqual({ kind: 'group', op: 'AND', items: [] });
    });

    it('renders the quick-filter bar when fields are exposed, and a drill toggles the filter every tile gets', async () => {
        const f = create(dashboard(['STAGE']));
        await settle(f);
        const bar = f.nativeElement.querySelector('[role="group"][aria-label="Dashboard quick filters"]');
        expect(bar).not.toBeNull();
        expect(bar.textContent).toContain('STAGE');
        await expectNoA11yViolations(f.nativeElement);

        tile(f).drill.emit({ field: 'STAGE', value: 'Final' });
        f.detectChanges();
        expect(tile(f).filter()!.items).toEqual([
            ...SAVED_FILTER.items,
            { kind: 'condition', field: 'STAGE', operator: '=', value: 'Final' },
        ]);
        // The active drill shows as a removable chip in the bar.
        expect(f.nativeElement.querySelector('[aria-label="Remove filter STAGE = Final"]')).not.toBeNull();

        tile(f).drill.emit({ field: 'STAGE', value: 'Final' });
        f.detectChanges();
        expect(tile(f).filter()!.items).toEqual(SAVED_FILTER.items);
    });

    it('UIE-5: shows the Dashboard header — description, as-of date, Illustrative data chip — without a heading', async () => {
        const f = create({
            ...dashboard([]),
            description: 'Which stages decide the season?',
            asOf: '2026-09-23',
            illustrative: true,
        });
        await settle(f);
        const header: HTMLElement = f.nativeElement.querySelector('[data-testid="dashboard-header"]');
        expect(header).not.toBeNull();
        expect(header.textContent).toContain('Which stages decide the season?');
        expect(header.textContent).toContain('As of 23 Sep 2026');
        expect(header.querySelector('inspecto-chip')!.textContent).toContain('Illustrative data');
        // Operator 2026-09-25: a caution, so the warning tone (amber), with its explanation kept as a tooltip.
        expect(header.querySelector('inspecto-chip > span')!.className).toContain('text-amber-800');
        expect(header.querySelector('inspecto-chip [title]')!.getAttribute('title')).toContain('synthetic');
        // The host (menu item) owns the page's <h1>; the header adds no heading of its own.
        expect(header.querySelector('h1, h2, h3')).toBeNull();
        await expectNoA11yViolations(f.nativeElement);
    });

    it('UIE-5: renders no header for a Dashboard that declares none of it', async () => {
        const f = create(dashboard([]));
        await settle(f);
        expect(f.nativeElement.querySelector('[data-testid="dashboard-header"]')).toBeNull();
        expect(f.nativeElement.textContent).not.toContain('Illustrative data');
    });

    it('UIE-5 (d): a date field shows the range control, seeded from the default; only dated tiles get the range', async () => {
        const dated: Dataset = {
            ...DS,
            id: 'calls',
            columns: [...DS.columns, { name: 'event_date', type: 'date', role: 'temporal' }],
        };
        const datedWidget: Widget = { ...WIDGET, id: 'w2', datasetId: 'calls' };
        const f = create(
            {
                ...dashboard([]),
                tiles: [
                    { widgetId: 'w1', span: 2 },
                    { widgetId: 'w2', span: 2 },
                ],
                asOf: '2026-09-24',
                dateField: 'event_date',
                defaultRange: 'last-7-days',
            },
            [WIDGET, datedWidget],
            [DS, dated],
        );
        await settle(f);
        const control: HTMLElement = f.nativeElement.querySelector('app-dashboard-date-range');
        expect(control).not.toBeNull();
        expect(control.querySelector('[data-testid="date-range-span"]')!.textContent).toContain('18 Sep – 24 Sep 2026');
        await expectNoA11yViolations(f.nativeElement);

        const tiles = f.debugElement
            .queryAll((d) => d.componentInstance instanceof StubTileComponent)
            .map((d) => d.componentInstance as StubTileComponent);
        const byDataset = (id: string) => tiles.find((t) => t.dataset()?.id === id)!;
        expect(byDataset('matches').filter()).toEqual(SAVED_FILTER); // no event_date column → untouched
        expect(byDataset('calls').filter()!.items[1]).toEqual({
            kind: 'group',
            op: 'AND',
            items: [
                { kind: 'condition', field: 'event_date', operator: '>=', value: '2026-09-18' },
                { kind: 'condition', field: 'event_date', operator: '<', value: '2026-09-25' },
            ],
        });
    });

    it('UIE-5 (d): no date field → no range control', async () => {
        const f = create(dashboard([]));
        await settle(f);
        expect(f.nativeElement.querySelector('app-dashboard-date-range')).toBeNull();
    });
});
