import { Location } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { EmbedGraph, EmbedView, EmbedViewService, layoutEmbedGraph } from './embed-view.service';

const PAGE_SIZE = 25;
const LABEL_LIMIT = 80;
const EDGE_LIMIT = 3000;

/** What the address bar shows once the link has been read: the token is the credential, so it must not stay in history or a screenshot. */
export const EMBED_SCRUBBED_PATH = '/embed/inv/shared';

/**
 * `/embed/inv/:token` - the read-only viewer of ONE shared Investigation, for someone with no login (LA-EMBED-SPA-1).
 * Outside the guarded shell and with no app chrome: it never redirects to sign-in and makes no call but the one read. The token is
 * read from the path into memory only; once the first answer is in, the address bar is rewritten without it (a reload therefore
 * needs the original link), and it is never logged, stored or echoed. A bad, expired or revoked link is deliberately the SAME page
 * (the server answers no differently, so as not to be an oracle); only "could not reach the server" is told apart, because it
 * says nothing about the link. Ids are shown exactly as returned (already masked by the Space and by the link).
 */
@Component({
    selector: 'la-app-embed-view-page',
    standalone: true,
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
                            <div class="flex flex-wrap items-center gap-2">
                                <h2 id="embed-graph" class="m-0 mr-auto text-lg font-semibold">Graph</h2>
                                <button type="button" class="rounded border px-2 py-1" style="border-color: var(--gamma-border)" (click)="zoomBy(1.4)" aria-label="Zoom in">+</button>
                                <button type="button" class="rounded border px-2 py-1" style="border-color: var(--gamma-border)" (click)="zoomBy(1 / 1.4)" aria-label="Zoom out">-</button>
                                <button type="button" class="rounded border px-2 py-1" style="border-color: var(--gamma-border)" (click)="resetView()">Reset view</button>
                            </div>
                            @if (graph(); as g) {
                                <div
                                    tabindex="0"
                                    role="group"
                                    aria-label="Graph canvas, read-only. Use the arrow keys to move and the zoom buttons to zoom; the Working Set table below lists every entity."
                                    class="rounded border"
                                    style="border-color: var(--gamma-border); touch-action: none"
                                    (keydown)="onKey($event)"
                                >
                                    <svg
                                        [attr.viewBox]="viewBox()"
                                        class="block h-[28rem] w-full cursor-grab"
                                        aria-hidden="true"
                                        (pointerdown)="dragStart($event)"
                                        (pointermove)="dragMove($event)"
                                        (pointerup)="dragEnd()"
                                        (pointerleave)="dragEnd()"
                                    >
                                        @for (e of g.edges; track $index) {
                                            <line
                                                [attr.x1]="e.x1"
                                                [attr.y1]="e.y1"
                                                [attr.x2]="e.x2"
                                                [attr.y2]="e.y2"
                                                [attr.stroke]="touches(e) ? 'var(--gamma-primary)' : 'currentColor'"
                                                [attr.stroke-width]="touches(e) ? nodeR() / 2 : nodeR() / 5"
                                                [attr.opacity]="touches(e) ? 1 : 0.35"
                                            />
                                        }
                                        @for (n of g.nodes; track n.id) {
                                            <circle
                                                [attr.cx]="n.x"
                                                [attr.cy]="n.y"
                                                [attr.r]="n.id === selected() ? nodeR() * 1.6 : nodeR()"
                                                [attr.fill]="n.id === selected() ? 'var(--gamma-primary)' : 'currentColor'"
                                                (click)="select(n.id)"
                                            />
                                            @if (showLabel(g, n.id)) {
                                                <text
                                                    [attr.x]="n.x"
                                                    [attr.y]="n.y - nodeR() * 1.6"
                                                    text-anchor="middle"
                                                    [attr.font-size]="labelSize()"
                                                    fill="currentColor"
                                                >{{ n.id }}</text>
                                            }
                                        }
                                    </svg>
                                </div>
                                <p class="text-secondary m-0 text-xs">
                                    {{ g.nodes.length }} entities on {{ rings() }} ring(s) - the centre is the seed, each ring one hop
                                    further.
                                    @if (truncated()) {
                                        Only the first {{ edgeLimit }} links are drawn; the table lists every entity.
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
    protected readonly edgeLimit = EDGE_LIMIT;

    private readonly laidOut = computed<EmbedGraph | null>(() => {
        const v = this.view();
        return v && v.workingSet.entities.length ? layoutEmbedGraph(v.workingSet) : null;
    });
    protected readonly graph = computed<EmbedGraph | null>(() => {
        const g = this.laidOut();
        return g ? { ...g, edges: g.edges.slice(0, EDGE_LIMIT) } : null;
    });
    protected readonly truncated = computed(() => (this.laidOut()?.edges.length ?? 0) > EDGE_LIMIT);
    protected readonly rings = computed(() => new Set(this.graph()?.nodes.map((n) => n.hop)).size);
    protected readonly nodeR = computed(() => Math.max(3, (this.graph()?.extent ?? 200) / 70));
    protected readonly labelSize = computed(() => (this.graph()?.extent ?? 200) / 22);

    // view state: zoom and pan in drawing units, the selected entity, the table filter and page
    protected readonly zoom = signal(1);
    private readonly pan = signal({ x: 0, y: 0 });
    protected readonly selected = signal<string | null>(null);
    protected readonly filter = signal('');
    protected readonly page = signal(0);

    protected readonly viewBox = computed(() => {
        const side = ((this.graph()?.extent ?? 200) * 2) / this.zoom();
        const p = this.pan();
        return `${p.x - side / 2} ${p.y - side / 2} ${side} ${side}`;
    });

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

    private dragging: { x: number; y: number } | null = null;

    private readonly svc = inject(EmbedViewService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly location = inject(Location);
    private readonly title = inject(Title);

    constructor() {
        effect(() => {
            const t = this.token();
            this.state.set('loading');
            this.view.set(null);
            this.selected.set(null);
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

    protected zoomBy(f: number): void {
        this.zoom.update((z) => Math.min(12, Math.max(0.5, z * f)));
    }

    protected resetView(): void {
        this.zoom.set(1);
        this.pan.set({ x: 0, y: 0 });
    }

    protected onKey(ev: KeyboardEvent): void {
        const step = ((this.graph()?.extent ?? 200) * 2) / this.zoom() / 10;
        const moves: Record<string, [number, number]> = {
            ArrowLeft: [-step, 0],
            ArrowRight: [step, 0],
            ArrowUp: [0, -step],
            ArrowDown: [0, step],
        };
        const d = moves[ev.key];
        if (!d) return;
        ev.preventDefault();
        this.pan.update((p) => ({ x: p.x + d[0], y: p.y + d[1] }));
    }

    protected dragStart(ev: PointerEvent): void {
        this.dragging = { x: ev.clientX, y: ev.clientY };
    }

    protected dragMove(ev: PointerEvent): void {
        if (!this.dragging) return;
        const el = ev.currentTarget as SVGSVGElement;
        const unit = ((this.graph()?.extent ?? 200) * 2) / this.zoom() / Math.max(1, Math.min(el.clientWidth, el.clientHeight));
        const dx = (ev.clientX - this.dragging.x) * unit;
        const dy = (ev.clientY - this.dragging.y) * unit;
        this.dragging = { x: ev.clientX, y: ev.clientY };
        this.pan.update((p) => ({ x: p.x - dx, y: p.y - dy }));
    }

    protected dragEnd(): void {
        this.dragging = null;
    }

    protected select(id: string): void {
        this.selected.set(this.selected() === id ? null : id);
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

    protected touches(e: { source: string; target: string }): boolean {
        const s = this.selected();
        return s !== null && (e.source === s || e.target === s);
    }

    protected showLabel(g: EmbedGraph, id: string): boolean {
        const s = this.selected();
        return g.nodes.length <= LABEL_LIMIT || id === s || (s !== null && g.edges.some((e) => this.touches(e) && (e.source === id || e.target === id)));
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
