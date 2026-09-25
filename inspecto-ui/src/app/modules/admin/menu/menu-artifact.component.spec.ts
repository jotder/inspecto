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

function create(dashboard: Dashboard | null) {
    TestBed.configureTestingModule({
        imports: [MenuArtifactComponent],
        providers: [
            provideNoopAnimations(),
            { provide: DashboardsService, useValue: { get: () => of(dashboard) } },
            { provide: WidgetsService, useValue: { list: () => of([WIDGET]) } },
            { provide: DatasetsService, useValue: { list: () => of([DS]) } },
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
        remove: { imports: [DashboardTileComponent, WidgetHostComponent] },
        add: { imports: [StubTileComponent, StubWidgetHostComponent] },
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
});
