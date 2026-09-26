import { ChangeDetectionStrategy, Component, computed, effect, inject, input } from '@angular/core';
import { tileBasis } from 'app/inspecto/viz/dashboard-grid';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { of, switchMap } from 'rxjs';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { MenuBinding } from 'app/inspecto/menu';
// Canonical render hosts (self-fetch by id, lazy-load heavy deps via the viz registry) — the same ones
// dashboard tiles use. They live under studio/ but are render components meant to be embedded.
import { Dashboard } from 'app/modules/admin/studio/dashboards/dashboard-types';
import { DashboardsService } from 'app/modules/admin/studio/dashboards/dashboards.service';
import { DashboardDateRangeComponent } from 'app/modules/admin/studio/dashboards/dashboard-date-range.component';
import { DashboardFilterBarComponent } from 'app/modules/admin/studio/dashboards/dashboard-filter-bar.component';
import { DashboardHeaderComponent } from 'app/modules/admin/studio/dashboards/dashboard-header.component';
import { DashboardTileComponent } from 'app/modules/admin/studio/dashboards/dashboard-tile.component';
import { DashboardViewStore } from 'app/modules/admin/studio/dashboards/dashboard-view.store';
import { GeoViewWidgetComponent } from 'app/modules/admin/studio/geo-map/geo-view-widget.component';
import { LinkViewWidgetComponent } from 'app/modules/admin/studio/link-analysis/link-view-widget.component';
import { WidgetHostComponent } from 'app/modules/admin/studio/widgets/widget-host.component';
import 'app/modules/admin/studio/widgets/widget.kind'; // side-effect: register viz plugins + geo/link view loaders

/**
 * Presentational renderer for a Menu item's {@link MenuBinding} — the shared render surface for both the
 * dynamic `/w/:nodeId` host and the Menu Builder's live preview. A Widget / saved view renders through its
 * canonical by-id host; a Dashboard lays its tiles out as widget hosts with the same viewer behaviour as the
 * Dashboard editor (quick-filter bar, saved filter as the base cross-filter, click-a-category drill) through the
 * shared {@link DashboardViewStore}. The viewer's filter is transient — never saved. No binding → the shared
 * empty state.
 */
@Component({
    selector: 'app-menu-artifact',
    standalone: true,
    imports: [
        InspectoEmptyStateComponent,
        WidgetHostComponent,
        GeoViewWidgetComponent,
        LinkViewWidgetComponent,
        DashboardDateRangeComponent,
        DashboardFilterBarComponent,
        DashboardHeaderComponent,
        DashboardTileComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    providers: [DashboardViewStore],
    template: `
        @if (binding(); as b) {
            @switch (b.kind) {
                @case ('widget') {
                    <app-widget-host class="block" [widgetId]="b.componentId" />
                }
                @case ('geo-map-view') {
                    <div class="h-[70vh] min-h-0"><app-geo-view-widget [viewId]="b.componentId" /></div>
                }
                @case ('link-analysis-view') {
                    <div class="h-[70vh] min-h-0">
                        <app-link-view-widget [viewId]="b.componentId" [showDescription]="true" />
                    </div>
                }
                @case ('dashboard') {
                    @if (dashboard(); as d) {
                        <app-dashboard-header class="mb-4 block empty:hidden" [header]="d" />
                        @if (view.exposedFields().length || view.dateField()) {
                            <div class="bg-card mb-4 flex flex-wrap items-center gap-4 rounded-2xl p-4 shadow">
                                @if (view.dateField()) {
                                    <!-- UIE-5 (d): the viewer's date range — transient, seeded from the default. -->
                                    <app-dashboard-date-range
                                        [selection]="view.range()"
                                        [anchor]="view.anchor()"
                                        (selectionChange)="view.range.set($event)"
                                    />
                                }
                                @if (view.exposedFields().length) {
                                    <app-dashboard-filter-bar
                                        [fields]="view.exposedFields()"
                                        [values]="view.exposedValues()"
                                        [filter]="view.filter()"
                                        (drillToggle)="view.onDrill($event)"
                                    />
                                }
                            </div>
                        }
                        <div class="flex flex-wrap gap-4">
                            @for (tile of view.tiles(); track $index) {
                                <div class="min-w-0" [style.flex-basis]="tileBasis(tile.span)">
                                    @if (view.widgetOf(tile); as widget) {
                                        @if (view.isViewBound(widget)) {
                                            <app-dashboard-tile [widget]="widget" />
                                        } @else if (view.datasetOf(tile); as dataset) {
                                            <app-dashboard-tile
                                                [widget]="widget"
                                                [dataset]="dataset"
                                                [filter]="view.filterFor(dataset)"
                                                (drill)="view.onDrill($event)"
                                            />
                                        } @else {
                                            <app-widget-host [widgetId]="tile.widgetId" />
                                        }
                                    } @else {
                                        <!-- Lookups not (yet) resolved: the by-id host still renders the tile, unfiltered. -->
                                        <app-widget-host [widgetId]="tile.widgetId" />
                                    }
                                </div>
                            }
                        </div>
                    } @else {
                        <div class="text-secondary flex h-40 items-center justify-center text-sm">Loading…</div>
                    }
                }
            }
        } @else {
            <inspecto-empty-state
                icon="heroicons_outline:queue-list"
                title="Nothing linked yet"
                [message]="emptyMessage()"
            />
        }
    `,
})
export class MenuArtifactComponent {
    /** UIE-3: a tile's width on the four-column grid. */
    readonly tileBasis = tileBasis;

    private readonly dashboards = inject(DashboardsService);
    readonly view = inject(DashboardViewStore);
    private lookupsLoaded = false;

    readonly binding = input<MenuBinding | undefined>(undefined);
    readonly emptyMessage = input('This item isn’t linked to a report yet.');

    /** Fetch the Dashboard (tiles) only when the binding is a dashboard. */
    readonly dashboard = toSignal<Dashboard | null>(
        toObservable(computed(() => this.binding())).pipe(
            switchMap((b) => (b?.kind === 'dashboard' ? this.dashboards.get(b.componentId) : of(null))),
        ),
        { initialValue: null },
    );

    constructor() {
        // A (new) Dashboard resets the viewer state to its saved tiles / filter / exposed fields. The widget +
        // dataset lookups are fetched once, and only when a Dashboard is actually shown.
        effect(() => {
            const d = this.dashboard();
            if (!d) return;
            if (!this.lookupsLoaded) {
                this.lookupsLoaded = true;
                this.view.loadLookups();
            }
            this.view.seed(d);
        });
    }
}
