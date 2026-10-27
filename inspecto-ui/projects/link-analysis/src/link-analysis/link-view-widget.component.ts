import { NgComponentOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { GRAPH_LAYOUTS, GraphLayoutId } from '@inspecto/core/graph/graph-layouts';
import { GraphEmphasis } from '@inspecto/core/graph/graph-view.component';
import { isForest, searchNodes } from '@inspecto/link-analysis/graph/graph-analysis';
import { CANVAS_MODES, CanvasMode, canvasInteractionFor } from './canvas-mode';
import {
    WIDGET_ANALYSES,
    WidgetAnalysisId,
    runWidgetAnalysis,
    timeColumnsOf,
    widgetAnalysisBlocked,
    widgetGraph,
} from './link-view-widget-tools';
import { G6GraphData } from '@inspecto/core/graph';
import { LA_DASHBOARD_HEADER } from '@inspecto/link-analysis/la-host';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { GraphDisplayOptions, GraphViewComponent } from '@inspecto/core/graph/graph-view.component';
import { LinkAnalysisService, LinkAnalysisView, SAVED_VIEW_NOT_EVIDENCE } from './link-analysis.service';
import { GraphSourcesService } from './graph-sources';
import type { ProjectedGraph } from './entity-projection';
import { LaLimitsService } from './la-limits.service';
import { laLayoutCaps, layoutCapNoticeText, layoutOverCapReason } from './la-limits';
import {
    hideLinkKinds,
    legendEdgeKindsFor,
    legendItemsFor,
    LinkAnalysisLegendComponent,
    toggleHiddenKind,
} from './link-analysis-overlays.component';

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
        MatButtonModule,
        MatIconModule,
        MatMenuModule,
        MatTooltipModule,
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
                @if (truncated()) {
                    <inspecto-alert class="mb-2 block shrink-0" variant="warning" title="Truncated">
                        The projection hit the node cap — narrow the dataset or filter to see everything.
                    </inspecto-alert>
                }
                <div
                    class="flex shrink-0 flex-wrap items-center gap-0.5 pb-1"
                    role="toolbar"
                    aria-label="Graph tools"
                    data-testid="la-widget-toolbar"
                >
                    <input
                        type="search"
                        class="w-28 rounded border px-1 py-0.5 text-xs"
                        style="border-color: var(--gamma-border)"
                        placeholder="Search"
                        aria-label="Search entities"
                        [value]="searchText()"
                        (input)="searchText.set($any($event.target).value)"
                        (keydown.enter)="search()"
                    />
                    <button
                        mat-icon-button
                        type="button"
                        class="!h-7 !w-7 !p-1"
                        [matMenuTriggerFor]="kindMenu"
                        matTooltip="Filter node types"
                        aria-label="Filter node types"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:tag"></mat-icon>
                    </button>
                    <mat-menu #kindMenu="matMenu">
                        @for (k of nodeKinds(); track k) {
                            <button
                                mat-menu-item
                                type="button"
                                role="menuitemcheckbox"
                                [attr.aria-checked]="!hiddenKinds().includes(k)"
                                (click)="$event.stopPropagation(); toggleKind(k)"
                            >
                                <mat-icon
                                    class="icon-size-4"
                                    [svgIcon]="hiddenKinds().includes(k) ? 'heroicons_outline:eye-slash' : 'heroicons_outline:eye'"
                                ></mat-icon>
                                <span>{{ k }}</span>
                            </button>
                        }
                    </mat-menu>
                    <button
                        mat-icon-button
                        type="button"
                        class="!h-7 !w-7 !p-1"
                        [matMenuTriggerFor]="timeMenu"
                        [disabled]="!timeColumns().length"
                        [matTooltip]="timeColumns().length ? 'Filter by time' : 'No link holds a date'"
                        aria-label="Filter by time"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:clock"></mat-icon>
                    </button>
                    <mat-menu #timeMenu="matMenu">
                        <div
                            class="flex flex-col gap-1 px-3 py-2 text-xs"
                            role="group"
                            aria-label="Filter by time"
                            (click)="$event.stopPropagation()"
                            (keydown)="$event.key !== 'Escape' && $event.stopPropagation()"
                        >
                            <label class="flex flex-col gap-0.5">
                                <span>Date column</span>
                                <select
                                    class="rounded border px-1 py-0.5"
                                    style="border-color: var(--gamma-border)"
                                    [value]="timeColumn()"
                                    (change)="timeColumn.set($any($event.target).value)"
                                >
                                    <option value="">None</option>
                                    @for (c of timeColumns(); track c) {
                                        <option [value]="c">{{ c }}</option>
                                    }
                                </select>
                            </label>
                            <label class="flex flex-col gap-0.5">
                                <span>Links on or before</span>
                                <input
                                    type="date"
                                    class="rounded border px-1 py-0.5"
                                    style="border-color: var(--gamma-border)"
                                    (change)="setCutoff($any($event.target).value)"
                                />
                            </label>
                        </div>
                    </mat-menu>
                    <button
                        mat-icon-button
                        type="button"
                        class="!h-7 !w-7 !p-1"
                        [matMenuTriggerFor]="layoutMenu"
                        matTooltip="Layout"
                        aria-label="Layout"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:squares-2x2"></mat-icon>
                    </button>
                    <mat-menu #layoutMenu="matMenu">
                        @for (l of layouts; track l.id) {
                            <button
                                mat-menu-item
                                type="button"
                                [disabled]="!!layoutBlocked(l.id, l.tree)"
                                [matTooltip]="layoutBlocked(l.id, l.tree) ?? ''"
                                (click)="layoutPick.set(l.id)"
                            >
                                {{ l.label }}
                            </button>
                        }
                    </mat-menu>
                    <button
                        mat-icon-button
                        type="button"
                        class="!h-7 !w-7 !p-1"
                        (click)="graphView()?.fitView()"
                        matTooltip="Fit"
                        aria-label="Fit the graph"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrows-pointing-in"></mat-icon>
                    </button>
                    <span class="mx-1 h-4 border-l" aria-hidden="true"></span>
                    @for (m of canvasModes; track m.id) {
                        <button
                            mat-icon-button
                            type="button"
                            class="!h-7 !w-7 !p-1"
                            [class.bg-hover]="canvasMode() === m.id"
                            [attr.aria-pressed]="canvasMode() === m.id"
                            [matTooltip]="m.label + ': ' + m.hint"
                            [attr.aria-label]="m.label"
                            (click)="canvasMode.set(m.id)"
                        >
                            <mat-icon class="icon-size-4" [svgIcon]="m.icon"></mat-icon>
                        </button>
                    }
                    <span class="mx-1 h-4 border-l" aria-hidden="true"></span>
                    <button
                        mat-icon-button
                        type="button"
                        class="!h-7 !w-7 !p-1"
                        [disabled]="!selection().length"
                        (click)="hideSelected()"
                        matTooltip="Hide the selection (this visit only)"
                        aria-label="Hide the selection"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:eye-slash"></mat-icon>
                    </button>
                    @if (hiddenNodes().length) {
                        <button mat-button type="button" class="!h-7 !min-w-0 !px-1 text-xs" (click)="hiddenNodes.set([])">
                            Show hidden ({{ hiddenNodes().length }})
                        </button>
                    }
                    <button
                        mat-button
                        type="button"
                        class="!h-7 !min-w-0 !px-2 text-xs"
                        [matMenuTriggerFor]="analyseMenu"
                        data-testid="la-widget-analyse"
                    >
                        Analyse
                    </button>
                    <mat-menu #analyseMenu="matMenu">
                        @for (a of analyses; track a.id) {
                            <button
                                mat-menu-item
                                type="button"
                                [disabled]="!!analysisBlocked(a.id)"
                                [matTooltip]="analysisBlocked(a.id) ?? ''"
                                (click)="analyse(a.id)"
                            >
                                {{ a.label }}
                            </button>
                        }
                    </mat-menu>
                    @if (allowExport()) {
                        <button
                            mat-icon-button
                            type="button"
                            class="!h-7 !w-7 !p-1"
                            [matMenuTriggerFor]="exportMenu"
                            matTooltip="Export"
                            aria-label="Export"
                            data-testid="la-widget-export"
                        >
                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-down-tray"></mat-icon>
                        </button>
                        <mat-menu #exportMenu="matMenu">
                            <button mat-menu-item type="button" (click)="exportPng()">PNG image</button>
                            <button mat-menu-item type="button" (click)="exportSvg()">SVG image</button>
                        </mat-menu>
                    }
                </div>
                @if (resultSummary(); as summary) {
                    <p
                        class="text-secondary m-0 flex shrink-0 items-center gap-1 pb-1 text-xs"
                        role="status"
                        data-testid="la-widget-result"
                    >
                        <span>{{ summary }}</span>
                        <button mat-button type="button" class="!h-6 !min-w-0 !px-1 text-xs" (click)="clearResult()">
                            Clear
                        </button>
                    </p>
                }
                <div class="relative flex min-h-0 flex-auto flex-col">
                    <inspecto-graph-view
                        class="min-h-0 flex-auto"
                        [data]="drawn()"
                        [display]="canvasDisplay()"
                        [layout]="drawnLayout()"
                        [fill]="true"
                        [emphasis]="emphasis()"
                        [interaction]="interaction()"
                        [plugins]="plugins()"
                        [selection]="selection()"
                        (selectionChange)="selection.set($event)"
                        (nodeClick)="selection.set([$event])"
                    />
                    @if (details(); as d) {
                        <div
                            class="bg-card absolute bottom-2 right-2 z-10 max-w-64 rounded-lg border p-2 text-xs shadow-lg"
                            role="group"
                            aria-label="Entity details"
                            data-testid="la-widget-details"
                        >
                            <div class="flex items-start gap-1">
                                <span class="flex-auto break-all font-semibold">{{ d.label }}</span>
                                <button
                                    mat-icon-button
                                    type="button"
                                    class="!h-5 !w-5 !p-0"
                                    (click)="selection.set([])"
                                    aria-label="Close the details"
                                >
                                    <mat-icon class="icon-size-3" svgIcon="heroicons_outline:x-mark"></mat-icon>
                                </button>
                            </div>
                            <div class="text-secondary">{{ d.kind }} · {{ d.links }} link(s)</div>
                            @if (d.id !== d.label) {
                                <div class="text-secondary break-all">{{ d.id }}</div>
                            }
                        </div>
                    }
                    <inspecto-link-analysis-legend
                        class="absolute left-2 top-2 z-10"
                        [items]="legendItems()"
                        [edgeKinds]="edgeKinds()"
                        [hiddenEdgeKinds]="hiddenLinkKinds()"
                        [edgeColors]="view()?.display?.edgeColors ?? {}"
                        (edgeKindToggle)="hiddenLinkKinds.set(toggleHiddenKind(hiddenLinkKinds(), $event))"
                        [open]="legendOpen()"
                        (openChange)="legendOpen.set($event)"
                    />
                </div>
                <p class="text-secondary m-0 shrink-0 pt-1 text-[10px] leading-snug" [title]="savedViewNotice">
                    <span class="text-warn font-semibold">Not evidence</span> — re-projects live
                </p>
                @if (layoutCapNotice(); as notice) {
                    <p
                        class="text-secondary m-0 shrink-0 text-[10px] leading-snug"
                        role="status"
                        data-testid="la-layout-cap-notice"
                    >
                        {{ notice }}
                    </p>
                }
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
    /** The Space's Link Analysis limits (`projection_node_cap`, …), loaded before the projection runs; one GET per Space. */
    private laLimits = inject(LaLimitsService);
    protected readonly headerComponent = inject(LA_DASHBOARD_HEADER);

    /** The saved `link-analysis-view` id this widget renders (the widget's binding). */
    readonly viewId = input<string | undefined>(undefined);
    /** Show the view's description under the host's title — for a host that owns a page title (a Menu item). */
    readonly showDescription = input(false);
    /** The sharer allowed export on the Widget (`options.allowExport`); without it the tile offers none (§5.13). */
    readonly allowExport = input(false);

    // The read-only toolset (LA-STUDIO-UI-REORG-1 §5.13): session only, nothing is saved, recorded or sent.
    protected readonly graphView = viewChild(GraphViewComponent);
    protected readonly canvasModes = CANVAS_MODES;
    protected readonly layouts = GRAPH_LAYOUTS;
    protected readonly analyses = WIDGET_ANALYSES;
    readonly canvasMode = signal<CanvasMode>('pan');
    readonly interaction = computed(() => canvasInteractionFor(this.canvasMode()));
    readonly plugins = computed(() => ({
        fisheye: this.canvasMode() === 'fisheye',
        edgeFilterLens: this.canvasMode() === 'linkFilter',
    }));
    readonly searchText = signal('');
    readonly selection = signal<string[]>([]);
    readonly hiddenNodes = signal<string[]>([]);
    readonly hiddenKinds = signal<string[]>([]);
    readonly timeColumn = signal('');
    readonly timeCutoff = signal<number | null>(null);
    /** The viewer's layout pick; null keeps the saved one. */
    readonly layoutPick = signal<GraphLayoutId | null>(null);
    readonly emphasis = signal<GraphEmphasis | null>(null);
    readonly resultSummary = signal<string | null>(null);
    readonly nodeKinds = computed(() => [...new Set((this.data()?.nodes ?? []).map((n) => n.data.kind))].sort());
    readonly timeColumns = computed(() => timeColumnsOf(this.data()));
    /** One selected entity: its details pop-over. */
    readonly details = computed(() => {
        const sel = this.selection();
        const g = this.drawn();
        if (sel.length !== 1 || !g) return null;
        const n = g.nodes.find((x) => x.id === sel[0]);
        if (!n) return null;
        const links = g.edges.filter((e) => e.source === n.id || e.target === n.id).length;
        return { id: n.id, label: n.data.label, kind: n.data.kind, links };
    });

    readonly view = signal<LinkAnalysisView | null>(null);
    readonly data = signal<G6GraphData | null>(null);
    readonly error = signal<string | null>(null);
    /** The view fetch settled (found or not) — gates the not-found empty state vs the loading strip. */
    readonly loaded = signal(false);
    /** The legend is open unless the view saved it minimised; the viewer may toggle it (never saved). */
    readonly legendOpen = signal(true);
    readonly legendItems = computed(() => legendItemsFor(this.data(), this.view()?.display?.nodeColors));
    readonly edgeKinds = computed(() => legendEdgeKindsFor(this.data()));
    /** Link kinds the legend's chips hide (client-side, this tile only). */
    readonly hiddenLinkKinds = signal<string[]>([]);
    /** The saved display plus the layout node caps, so a saved Radial / Information density view never freezes the tile. */
    readonly canvasDisplay = computed<GraphDisplayOptions>(() => ({
        nodeLabels: true,
        edgeLabels: true,
        nodeColors: {},
        edgeColors: {},
        nodeShapes: {},
        edgePatterns: {},
        edgeSizes: {},
        ...this.view()?.display,
        layoutCaps: laLayoutCaps(),
    }));
    /** The saved layout, or null (the graph view's default) when it is over its node cap (UIR-D11). */
    readonly drawnLayout = computed(() => {
        const saved = this.layoutPick() ?? this.view()?.layout ?? null;
        return saved && layoutOverCapReason(saved, this.data()?.nodes.length ?? 0) ? null : saved;
    });
    /** Said when the saved layout is over its node cap and the tile draws the default layout instead. */
    readonly layoutCapNotice = computed(() =>
        layoutCapNoticeText(this.view()?.layout ?? null, this.data()?.nodes.length ?? 0),
    );
    readonly drawn = computed(() => {
        const d = this.data();
        if (!d) return null;
        const g = widgetGraph(d, {
            hiddenKinds: this.hiddenKinds(),
            hiddenNodes: this.hiddenNodes(),
            timeColumn: this.timeColumn(),
            timeCutoff: this.timeCutoff(),
        });
        return hideLinkKinds(g, this.hiddenLinkKinds());
    });
    /** The node cap cut the projection short — said in the Link Analysis page's own words. */
    readonly truncated = computed(() => !!(this.data() as ProjectedGraph | null)?.truncated);
    protected readonly toggleHiddenKind = toggleHiddenKind;

    /** Select every entity whose label or id holds the search text, and bring them into view. */
    search(): void {
        const g = this.drawn();
        if (!g) return;
        const hits = searchNodes(g, this.searchText());
        this.selection.set(hits);
        this.resultSummary.set(this.searchText().trim() ? `${hits.length} match(es).` : null);
        if (hits.length) this.graphView()?.centerOn(hits);
    }

    toggleKind(kind: string): void {
        this.hiddenKinds.update((ks) => (ks.includes(kind) ? ks.filter((k) => k !== kind) : [...ks, kind]));
    }

    /** `yyyy-mm-dd` → the end of that day, so links ON the day stay; blank clears. */
    setCutoff(value: string): void {
        const t = value ? Date.parse(value + 'T23:59:59.999') : NaN;
        this.timeCutoff.set(Number.isFinite(t) ? t : null);
    }

    hideSelected(): void {
        const sel = this.selection();
        this.hiddenNodes.update((h) => [...new Set([...h, ...sel])]);
        this.selection.set([]);
        this.clearResult();
    }

    layoutBlocked(id: GraphLayoutId, tree: boolean): string | null {
        const g = this.drawn();
        if (tree && g && !isForest(g)) return 'Needs a tree-shaped graph.';
        return layoutOverCapReason(id, g?.nodes.length ?? 0);
    }

    analysisBlocked(id: WidgetAnalysisId): string | null {
        const g = this.drawn();
        return g ? widgetAnalysisBlocked(id, g, this.selection()) : 'Nothing drawn.';
    }

    analyse(id: WidgetAnalysisId): void {
        const g = this.drawn();
        if (!g || this.analysisBlocked(id)) return;
        const r = runWidgetAnalysis(id, g, this.selection());
        this.emphasis.set(r.emphasis);
        this.resultSummary.set(r.summary);
    }

    clearResult(): void {
        this.emphasis.set(null);
        this.resultSummary.set(null);
    }

    async exportPng(): Promise<void> {
        if (!this.allowExport()) return;
        const url = await this.graphView()?.exportPng();
        if (url) this.download(url, 'png');
    }

    exportSvg(): void {
        if (!this.allowExport()) return;
        const svg = this.graphView()?.exportSvg();
        if (!svg) return;
        const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
        this.download(url, 'svg');
        URL.revokeObjectURL(url);
    }

    private download(href: string, ext: string): void {
        const a = document.createElement('a');
        a.href = href;
        a.download = `${this.viewId() ?? 'link-analysis'}.${ext}`;
        a.click();
    }
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
                    // the projection reads the Space's limits at call time, so they load first (fallbacks if that fails)
                    this.laLimits
                        .whenLoaded()
                        .then(() => source.query(view.query))
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
