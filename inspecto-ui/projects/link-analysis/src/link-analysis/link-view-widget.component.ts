import { NgComponentOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { G6GraphData } from '@inspecto/core/graph';
import { LA_DASHBOARD_HEADER } from '@inspecto/link-analysis/la-host';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { GraphViewComponent } from '@inspecto/core/graph/graph-view.component';
import { LinkAnalysisService, LinkAnalysisView, SAVED_VIEW_NOT_EVIDENCE } from './link-analysis.service';
import { GraphSourcesService } from './graph-sources';
import { legendEdgeKindsFor, legendItemsFor, LinkAnalysisLegendComponent } from './link-analysis-overlays.component';

/**
 * Read-only **Link analysis widget** host (Phase 4): renders a saved `link-analysis-view` Component on a
 * dashboard tile by re-running the view's own GraphSource query and feeding the shared
 * {@link GraphViewComponent} with the captured display options + layout. Loaded lazily through the viz
 * component-loader registry (`widget.kind`), so G6 never joins the eager dashboard bundle. The widget is a
 * viewer; investigating happens at `/studio/link-analysis`.
 *
 * R3-04: it draws the studio's legend (kind → colour → count, through the same `legendItemsFor`) open or
 * minimised as the view saved it (`view.legend`, default open, as the studio restores it), and — when the host
 * owns a page title, as a Menu item does — the view's `description` beneath it, in the Dashboard header's line.
 */
@Component({
    selector: 'app-link-view-widget',
    standalone: true,
    imports: [
        GraphViewComponent,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        LinkAnalysisLegendComponent,
        NgComponentOutlet,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex h-full min-h-0 flex-col">
            @if (showDescription() && header().description) {
                <div class="mb-2 block shrink-0">
                    <ng-container *ngComponentOutlet="headerComponent; inputs: { header: header() }" />
                </div>
            }
            @if (error(); as message) {
                <inspecto-alert class="block" variant="warning">{{ message }}</inspecto-alert>
            } @else if (data(); as d) {
                <div class="relative flex min-h-0 flex-auto flex-col">
                    <inspecto-graph-view
                        class="min-h-0 flex-auto"
                        [data]="d"
                        [display]="view()?.display ?? null"
                        [layout]="view()?.layout ?? null"
                        [fill]="true"
                    />
                    <inspecto-link-analysis-legend
                        class="absolute left-2 top-2 z-10"
                        [items]="legendItems()"
                        [edgeKinds]="edgeKinds()"
                        [open]="legendOpen()"
                        (openChange)="legendOpen.set($event)"
                    />
                </div>
                <p class="text-secondary m-0 shrink-0 pt-1 text-[10px] leading-snug" [title]="savedViewNotice">
                    <span class="text-warn font-semibold">Not evidence</span> — re-projects live
                </p>
            } @else if (loaded()) {
                <inspecto-empty-state
                    icon="heroicons_outline:share"
                    message="No saved Link-Analysis view bound to this widget."
                />
            } @else {
                <div class="text-secondary flex h-full items-center justify-center text-sm">Loading…</div>
            }
        </div>
    `,
})
export class LinkViewWidgetComponent {
    /** A tile reads as a fixed report; it is a live re-projection, and must say so (D-S1). */
    readonly savedViewNotice = SAVED_VIEW_NOT_EVIDENCE;
    private linkAnalysisApi = inject(LinkAnalysisService);
    private graphSources = inject(GraphSourcesService);
    protected readonly headerComponent = inject(LA_DASHBOARD_HEADER);

    /** The saved `link-analysis-view` id this widget renders (the widget's binding). */
    readonly viewId = input<string | undefined>(undefined);
    /** Show the view's description under the host's title — for a host that owns a page title (a Menu item). */
    readonly showDescription = input(false);

    readonly view = signal<LinkAnalysisView | null>(null);
    readonly data = signal<G6GraphData | null>(null);
    readonly error = signal<string | null>(null);
    /** The view fetch settled (found or not) — gates the not-found empty state vs the loading strip. */
    readonly loaded = signal(false);
    /** The legend is open unless the view saved it minimised; the viewer may toggle it (never saved). */
    readonly legendOpen = signal(true);
    readonly legendItems = computed(() => legendItemsFor(this.data(), this.view()?.display?.nodeColors));
    readonly edgeKinds = computed(() => legendEdgeKindsFor(this.data()));
    readonly header = computed(() => ({ description: this.view()?.description }));

    constructor() {
        effect(() => {
            const id = this.viewId();
            this.view.set(null);
            this.data.set(null);
            this.error.set(null);
            this.loaded.set(false);
            if (!id) {
                this.loaded.set(true);
                return;
            }
            this.linkAnalysisApi.get(id).subscribe({
                next: (view) => {
                    this.view.set(view);
                    this.legendOpen.set(view?.view?.legend ?? true);
                    this.loaded.set(true);
                    if (!view) return;
                    const source = this.graphSources.byId(view.sourceId);
                    if (!source) {
                        this.error.set(`Unknown graph source “${view.sourceId}”.`);
                        return;
                    }
                    source
                        .query(view.query)
                        .then((d) => this.data.set(d))
                        .catch((e: unknown) =>
                            this.error.set(e instanceof Error ? e.message : 'The view’s query failed.'),
                        );
                },
                error: () => {
                    this.loaded.set(true);
                    this.error.set(`Could not load the saved view “${id}”.`);
                },
            });
        });
    }
}
