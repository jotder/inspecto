import { Location } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import type { G6GraphData } from '@inspecto/core/graph';
import { GraphDisplayOptions, GraphEmphasis, GraphViewComponent } from '@inspecto/core/graph/graph-view.component';
import { searchNodes } from '@inspecto/link-analysis/graph/graph-analysis';
import { CANVAS_MODES, CanvasMode, canvasInteractionFor, canvasModeForKey } from '@inspecto/link-analysis/link-analysis/canvas-mode';
import { laLimit } from '@inspecto/link-analysis/link-analysis/la-limits';
import {
    WIDGET_ANALYSES,
    WidgetAnalysisId,
    runWidgetAnalysis,
    widgetAnalysisBlocked,
    widgetGraph,
} from '@inspecto/link-analysis/link-analysis/link-view-widget-tools';
import { EmbedView, EmbedViewService, embedGraphData } from './embed-view.service';

const PAGE_SIZE = 25;

/** What the address bar shows once the link has been read: the token is the credential, so it must not stay in history or a screenshot. */
export const EMBED_SCRUBBED_PATH = '/embed/inv/shared';

/**
 * `/embed/inv/:token` - the read-only viewer of ONE shared Investigation, for someone with no login (LA-EMBED-SPA-1).
 * Outside the guarded shell and with no app chrome: it never redirects to sign-in and makes no call but the one read. The token is
 * read from the path into memory only; once the first answer is in, the address bar is rewritten without it (a reload therefore
 * needs the original link), and it is never logged, stored or echoed. A bad, expired or revoked link is deliberately the SAME page
 * (the server answers no differently, so as not to be an oracle); only "could not reach the server" is told apart, because it
 * says nothing about the link. Ids are shown exactly as returned (already masked by the Space and by the link).
 *
 * The graph is the shared G6 host with the dashboard Widget's read-only toolset (LA-STUDIO-UI-REORG-1 §5.13): search, Canvas
 * modes, type filter, Hide and the six browser analyses, all in this visit only. There is no Export: an embed grant carries no
 * export permission (its mint takes only ttl_hours, label, scope and masking_mode).
 */
@Component({
    selector: 'la-app-embed-view-page',
    standalone: true,
    imports: [GraphViewComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    // The page sits outside the shell that paints the scheme background; without its own the text tokens were drawn on white (axe color-contrast 2.56:1 in dark).
    host: { class: 'block min-h-screen', style: 'background: var(--gamma-bg-default); color: var(--gamma-text-default)' },
    template: `
        <main class="mx-auto flex w-full max-w-5xl flex-col gap-4 p-4" aria-live="polite">
            @switch (state()) {
                @case ('loading') {
                    <p class="text-secondary m-0" role="status">Loading...</p>
                }
                @case ('unavailable') {
                    <section aria-labelledby="embed-na" class="flex flex-col gap-2">
                        <h1 id="embed-na" class="text-title m-0 font-semibold">This link is not available</h1>
                        <p class="text-secondary m-0">
                            It may have expired, been withdrawn, or be mistyped. Ask the person who shared it for a new link. If you
                            reloaded this page, open the original link again: the address bar no longer holds it.
                        </p>
                    </section>
                }
                @case ('unreachable') {
                    <section aria-labelledby="embed-off" class="flex flex-col gap-2">
                        <h1 id="embed-off" class="text-title m-0 font-semibold">Could not reach the server</h1>
                        <p class="text-secondary m-0">Check your connection, then open the link again in a moment.</p>
                    </section>
                }
                @case ('ready') {
                    @if (view(); as v) {
                        <header class="flex flex-col gap-1">
                            <h1 class="text-title m-0 font-semibold">{{ v.investigation.title || 'Investigation' }}</h1>
                            @if (v.investigation.purpose) {
                                <p class="text-secondary m-0">{{ v.investigation.purpose }}</p>
                            }
                            <p class="text-secondary m-0 text-sm">
                                Read-only view at step {{ v.head.step }}. {{ maskNote() }} {{ scopeNote() }} This link expires
                                {{ v.link.expiresAt }}.
                            </p>
                        </header>

                        <section aria-labelledby="embed-graph" class="flex flex-col gap-2">
                            <h2 id="embed-graph" class="m-0 text-lg font-semibold">Graph</h2>
                            @if (drawn(); as g) {
                                <div class="flex flex-wrap items-center gap-1 text-sm" role="toolbar" aria-label="Graph tools" data-testid="embed-toolbar">
                                    <button type="button" class="rounded border px-2 py-1" style="border-color: var(--gamma-border)" (click)="graphView()?.zoomBy(1.4)" aria-label="Zoom in">+</button>
                                    <button type="button" class="rounded border px-2 py-1" style="border-color: var(--gamma-border)" (click)="graphView()?.zoomBy(1 / 1.4)" aria-label="Zoom out">-</button>
                                    <button type="button" class="rounded border px-2 py-1" style="border-color: var(--gamma-border)" (click)="graphView()?.fitView()">Fit</button>
                                    <span class="mx-1 h-4 border-l" aria-hidden="true"></span>
                                    @for (m of canvasModes; track m.id) {
                                        <button
                                            type="button"
                                            class="rounded border px-2 py-1"
                                            style="border-color: var(--gamma-border)"
                                            [class.font-semibold]="canvasMode() === m.id"
                                            [attr.aria-pressed]="canvasMode() === m.id"
                                            [title]="m.label + ': ' + m.hint"
                                            (click)="canvasMode.set(m.id)"
                                        >
                                            {{ m.label }}
                                        </button>
                                    }
                                    <span class="mx-1 h-4 border-l" aria-hidden="true"></span>
                                    <button type="button" class="rounded border px-2 py-1" style="border-color: var(--gamma-border)" [disabled]="!selection().length" (click)="hideSelected()">Hide selection</button>
                                    @if (hiddenNodes().length) {
                                        <button type="button" class="rounded border px-2 py-1" style="border-color: var(--gamma-border)" (click)="hiddenNodes.set([])">Show hidden ({{ hiddenNodes().length }})</button>
                                    }
                                </div>
                                @if (nodeKinds().length > 1) {
                                    <fieldset class="m-0 flex flex-wrap items-center gap-3 border-0 p-0 text-sm" data-testid="embed-kinds">
                                        <legend class="float-left mr-2">Entity types</legend>
                                        @for (k of nodeKinds(); track k) {
                                            <label class="flex items-center gap-1">
                                                <input type="checkbox" [checked]="!hiddenKinds().includes(k)" (change)="toggleKind(k)" />
                                                <span>{{ k }}</span>
                                            </label>
                                        }
                                    </fieldset>
                                }
                                <div class="flex flex-wrap items-center gap-1 text-sm" role="group" aria-label="Analyse" data-testid="embed-analyse">
                                    <span class="mr-1">Analyse:</span>
                                    @for (a of analyses; track a.id) {
                                        <button
                                            type="button"
                                            class="rounded border px-2 py-1"
                                            style="border-color: var(--gamma-border)"
                                            [disabled]="!!analysisBlocked(a.id)"
                                            [title]="analysisBlocked(a.id) ?? ''"
                                            (click)="analyse(a.id)"
                                        >
                                            {{ a.label }}
                                        </button>
                                    }
                                </div>
                                @if (resultSummary(); as summary) {
                                    <p class="text-secondary m-0 flex items-center gap-1 text-sm" role="status" data-testid="embed-result">
                                        <span>{{ summary }}</span>
                                        <button type="button" class="rounded border px-2 py-0.5" style="border-color: var(--gamma-border)" (click)="clearResult()">Clear</button>
                                    </p>
                                }
                                <div
                                    tabindex="0"
                                    role="group"
                                    aria-label="Graph canvas, read-only. Arrow keys move, + and - zoom, L lasso, F fisheye, Escape back to pan; the Working Set table below lists every entity."
                                    class="h-[28rem] rounded border"
                                    style="border-color: var(--gamma-border)"
                                    (keydown)="onKey($event)"
                                >
                                    <inspecto-graph-view
                                        #gv
                                        class="block h-full"
                                        [data]="g"
                                        [display]="display()"
                                        [fill]="true"
                                        [emphasis]="emphasis()"
                                        [interaction]="interaction()"
                                        [plugins]="plugins()"
                                        [selection]="selection()"
                                        (selectionChange)="selection.set($event)"
                                        (nodeClick)="selection.set([$event])"
                                    />
                                </div>
                                <p class="text-secondary m-0 text-xs">
                                    {{ g.nodes.length }} entities and {{ g.edges.length }} links drawn.
                                    @if (truncated()) {
                                        Only the first {{ edgeLimit() }} links are drawn; the table lists every entity.
                                    }
                                </p>
                            } @else {
                                <p class="text-secondary m-0">The Working Set is empty.</p>
                            }
                        </section>

                        <section aria-labelledby="embed-ents" class="flex flex-col gap-2">
                            <h2 id="embed-ents" class="m-0 text-lg font-semibold">
                                Working Set ({{ matching().length }} of {{ v.workingSet.entities.length }} entities,
                                {{ v.workingSet.links.length }} links)
                            </h2>
                            <label class="flex items-center gap-2 text-sm">
                                <span>Find an entity</span>
                                <input
                                    type="search"
                                    class="rounded border px-1 py-1"
                                    style="border-color: var(--gamma-border)"
                                    [value]="filter()"
                                    (input)="setFilter($any($event.target).value)"
                                    (keydown.enter)="search()"
                                />
                            </label>
                            <table class="w-full text-left text-sm">
                                <caption class="sr-only">
                                    Entities of the shared Working Set
                                </caption>
                                <thead>
                                    <tr>
                                        <th scope="col">Entity</th>
                                        <th scope="col">Type</th>
                                        <th scope="col">Hop</th>
                                        <th scope="col">Links</th>
                                        <th scope="col"><span class="sr-only">Show on graph</span></th>
                                    </tr>
                                </thead>
                                <tbody>
                                    @for (e of pageRows(); track e.entityId) {
                                        <tr>
                                            <th scope="row" class="font-normal">{{ e.entityId }}</th>
                                            <td>{{ e.type }}</td>
                                            <td>{{ e.hop ?? 0 }}</td>
                                            <td>{{ degree(e.entityId) }}</td>
                                            <td>
                                                <button
                                                    type="button"
                                                    class="rounded border px-2 py-1"
                                                    style="border-color: var(--gamma-border)"
                                                    (click)="select(e.entityId)"
                                                    [attr.aria-pressed]="e.entityId === selected()"
                                                    [attr.aria-label]="'Highlight ' + e.entityId + ' on the graph'"
                                                >
                                                    Highlight
                                                </button>
                                            </td>
                                        </tr>
                                    }
                                </tbody>
                            </table>
                            @if (pageCount() > 1) {
                                <div class="flex items-center gap-2 text-sm">
                                    <button type="button" class="rounded border px-2 py-1" style="border-color: var(--gamma-border)" [disabled]="page() === 0" (click)="goto(page() - 1)">Previous</button>
                                    <span>Page {{ page() + 1 }} of {{ pageCount() }}</span>
                                    <button type="button" class="rounded border px-2 py-1" style="border-color: var(--gamma-border)" [disabled]="page() >= pageCount() - 1" (click)="goto(page() + 1)">Next</button>
                                </div>
                            }
                        </section>

                        @if (selected(); as sel) {
                            <section aria-labelledby="embed-sel" class="flex flex-col gap-1">
                                <h2 id="embed-sel" class="m-0 text-lg font-semibold">{{ sel }}</h2>
                                <ul class="m-0 list-none p-0">
                                    @for (l of linksOf(sel); track $index) {
                                        <li>{{ l.source }} - {{ l.kind }} - {{ l.target }} ({{ l.count ?? 1 }})</li>
                                    } @empty {
                                        <li class="text-secondary">No links.</li>
                                    }
                                </ul>
                                @for (n of notesOf(sel); track $index) {
                                    <p class="m-0 text-sm">Note: {{ n.note }}</p>
                                }
                            </section>
                        }

                        @if (v.link.scope !== 'working_set') {
                            <section aria-labelledby="embed-notes">
                                <h2 id="embed-notes" class="m-0 text-lg font-semibold">Notes ({{ v.annotations.length }})</h2>
                                <ul class="m-0 list-none p-0">
                                    @for (n of v.annotations; track $index) {
                                        <li>{{ n.note }}</li>
                                    }
                                </ul>
                            </section>
                        }
                        @if (v.link.scope === 'full' || v.link.scope === undefined) {
                            <section aria-labelledby="embed-refs">
                                <h2 id="embed-refs" class="m-0 text-lg font-semibold">References ({{ v.references.length }})</h2>
                                <ul class="m-0 list-none p-0">
                                    @for (r of v.references; track $index) {
                                        <li>{{ refText(r) }}</li>
                                    }
                                </ul>
                            </section>
                        }
                    }
                }
            }
        </main>
    `,
})
export class EmbedViewPageComponent {
    /** The route's `:token`, bound by `withComponentInputBinding`. */
    readonly token = input.required<string>();
    protected readonly state = signal<'loading' | 'ready' | 'unavailable' | 'unreachable'>('loading');
    protected readonly view = signal<EmbedView | null>(null);
    /**
     * `display.embed_edge_limit` / `display.embed_label_limit` (LA-CONFIG-LIMITS-1), read from the limits store and NOT
     * fetched: this page makes no call but its one read, and its holder has no account to read a Space's settings with.
     */
    protected readonly edgeLimit = computed(() => laLimit('display.embed_edge_limit'));
    private readonly labelLimit = computed(() => laLimit('display.embed_label_limit'));

    // The read-only toolset (LA-STUDIO-UI-REORG-1 §5.13): this visit only, nothing is saved, recorded or sent.
    protected readonly graphView = viewChild<GraphViewComponent>('gv');
    protected readonly canvasModes = CANVAS_MODES;
    protected readonly analyses = WIDGET_ANALYSES;
    readonly canvasMode = signal<CanvasMode>('pan');
    readonly interaction = computed(() => canvasInteractionFor(this.canvasMode()));
    readonly plugins = computed(() => ({
        fisheye: this.canvasMode() === 'fisheye',
        edgeFilterLens: this.canvasMode() === 'linkFilter',
    }));
    readonly selection = signal<string[]>([]);
    readonly hiddenNodes = signal<string[]>([]);
    readonly hiddenKinds = signal<string[]>([]);
    readonly emphasis = signal<GraphEmphasis | null>(null);
    readonly resultSummary = signal<string | null>(null);

    private readonly full = computed<G6GraphData | null>(() => {
        const v = this.view();
        return v && v.workingSet.entities.length ? embedGraphData(v.workingSet) : null;
    });
    readonly nodeKinds = computed(() => [...new Set((this.full()?.nodes ?? []).map((n) => n.data.kind))].sort());
    /** The Working Set after the edge limit and the viewer's type and Hide filters. */
    readonly drawn = computed<G6GraphData | null>(() => {
        const g = this.full();
        if (!g) return null;
        return widgetGraph(
            { ...g, edges: g.edges.slice(0, this.edgeLimit()) },
            { hiddenKinds: this.hiddenKinds(), hiddenNodes: this.hiddenNodes(), timeColumn: '', timeCutoff: null },
        );
    });
    protected readonly truncated = computed(() => (this.full()?.edges.length ?? 0) > this.edgeLimit());
    protected readonly display = computed<GraphDisplayOptions>(() => ({
        nodeLabels: (this.drawn()?.nodes.length ?? 0) <= this.labelLimit(),
        edgeLabels: false,
        nodeColors: {},
        edgeColors: {},
        nodeShapes: {},
        edgePatterns: {},
        edgeSizes: {},
    }));

    // the table filter and page
    protected readonly filter = signal('');
    protected readonly page = signal(0);
    /** One selected entity: its links and notes are listed under the table. */
    protected readonly selected = computed(() => (this.selection().length === 1 ? this.selection()[0] : null));

    protected readonly matching = computed(() => {
        const q = this.filter().trim().toLowerCase();
        const rows = this.view()?.workingSet.entities ?? [];
        return q ? rows.filter((e) => e.entityId.toLowerCase().includes(q) || (e.type ?? '').toLowerCase().includes(q)) : rows;
    });
    protected readonly pageCount = computed(() => Math.max(1, Math.ceil(this.matching().length / PAGE_SIZE)));
    protected readonly pageRows = computed(() => this.matching().slice(this.page() * PAGE_SIZE, (this.page() + 1) * PAGE_SIZE));
    private readonly degrees = computed(() => {
        const d = new Map<string, number>();
        for (const l of this.view()?.workingSet.links ?? []) {
            d.set(l.source, (d.get(l.source) ?? 0) + 1);
            d.set(l.target, (d.get(l.target) ?? 0) + 1);
        }
        return d;
    });

    protected readonly maskNote = computed(() => (this.view()?.masking?.mode === 'none' ? '' : 'Entity ids may be masked.'));
    protected readonly scopeNote = computed(() => {
        const s = this.view()?.link.scope;
        return s === 'working_set' ? 'This link shows entities and links only.' : s === 'with_notes' ? 'This link omits references.' : '';
    });

    private readonly svc = inject(EmbedViewService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly location = inject(Location);
    private readonly title = inject(Title);

    constructor() {
        effect(() => {
            const t = this.token();
            this.state.set('loading');
            this.view.set(null);
            this.selection.set([]);
            this.svc
                .view(t)
                .pipe(takeUntilDestroyed(this.destroyRef))
                .subscribe({
                    next: (v) => {
                        this.view.set(v);
                        this.state.set('ready');
                        this.title.setTitle((v.investigation.title || 'Investigation') + ' - shared view');
                        this.scrub(t);
                    },
                    error: (e: { status?: number } | null) => {
                        const unreachable = !e || e.status === 0 || (e.status ?? 0) >= 500;
                        this.state.set(unreachable ? 'unreachable' : 'unavailable');
                        this.title.setTitle('Shared view');
                        if (!unreachable) this.scrub(t); // an unreachable page may work on a reload, so it keeps its address
                    },
                });
        });
    }

    /** Replace the address-bar path with one that has no token (no navigation); the token stays only in this component's memory. */
    private scrub(token: string): void {
        const path = this.location.path();
        if (path.includes(token) || path.includes(encodeURIComponent(token))) this.location.replaceState(EMBED_SCRUBBED_PATH);
    }

    /** The keyboard equivalents of the pointer: arrows pan, + and - zoom, and the Canvas-mode keys (WCAG 2.1.1 / 2.5.7). */
    protected onKey(ev: KeyboardEvent): void {
        const moves: Record<string, [number, number]> = { ArrowLeft: [40, 0], ArrowRight: [-40, 0], ArrowUp: [0, 40], ArrowDown: [0, -40] };
        const d = moves[ev.key];
        const g = this.graphView();
        if (d) g?.panBy(d[0], d[1]);
        else if (ev.key === '+' || ev.key === '=') g?.zoomBy(1.2);
        else if (ev.key === '-') g?.zoomBy(1 / 1.2);
        else {
            const mode = canvasModeForKey(ev, this.canvasMode());
            if (!mode) return;
            this.canvasMode.set(mode);
        }
        ev.preventDefault();
    }

    /** Select every entity whose label or id holds the find text, and bring them into view. */
    protected search(): void {
        const g = this.drawn();
        if (!g) return;
        const hits = searchNodes(g, this.filter());
        this.selection.set(hits);
        this.resultSummary.set(this.filter().trim() ? `${hits.length} match(es) on the graph.` : null);
        if (hits.length) this.graphView()?.centerOn(hits);
    }

    protected select(id: string): void {
        const on = this.selected() !== id;
        this.selection.set(on ? [id] : []);
        if (on) this.graphView()?.centerOn([id]);
    }

    toggleKind(kind: string): void {
        this.hiddenKinds.update((ks) => (ks.includes(kind) ? ks.filter((k) => k !== kind) : [...ks, kind]));
    }

    hideSelected(): void {
        const sel = this.selection();
        this.hiddenNodes.update((h) => [...new Set([...h, ...sel])]);
        this.selection.set([]);
        this.clearResult();
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

    protected setFilter(v: string): void {
        this.filter.set(v);
        this.page.set(0);
    }

    protected goto(p: number): void {
        this.page.set(Math.min(Math.max(0, p), this.pageCount() - 1));
    }

    protected degree(id: string): number {
        return this.degrees().get(id) ?? 0;
    }

    protected linksOf(id: string) {
        return (this.view()?.workingSet.links ?? []).filter((l) => l.source === id || l.target === id).slice(0, 100);
    }

    protected notesOf(id: string) {
        return (this.view()?.annotations ?? []).filter((n) => (n as { entityId?: string }).entityId === id);
    }

    /** A reference is shown as returned (already masked): its title/label/note/id, else compact JSON. */
    protected refText(r: unknown): string {
        if (typeof r === 'string') return r;
        const o = (r ?? {}) as Record<string, unknown>;
        const t = o['title'] ?? o['label'] ?? o['note'] ?? o['id'];
        return typeof t === 'string' ? t : JSON.stringify(r);
    }
}
