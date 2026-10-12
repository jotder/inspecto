import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EmbedView, EmbedViewService, layoutEmbedGraph, EmbedGraph } from './embed-view.service';

/**
 * `/embed/inv/:token` - the read-only viewer of ONE shared Investigation, for someone with no login (LA-EMBED-VIEW-1).
 * Outside the guarded shell: it never redirects to sign-in. The token is read from the path into memory only; it is never
 * logged, stored or echoed. EVERY failure (bad, expired, revoked, disabled, offline) shows the same neutral page, because
 * the server's answer is deliberately no oracle. Ids are shown exactly as returned (already masked by the Space).
 */
@Component({
    selector: 'la-app-embed-view-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <main class="mx-auto flex w-full max-w-4xl flex-col gap-6 p-6" aria-live="polite">
            @switch (state()) {
                @case ('loading') {
                    <p class="text-secondary m-0">Loading...</p>
                }
                @case ('unavailable') {
                    <section aria-labelledby="embed-na" class="flex flex-col gap-2">
                        <h1 id="embed-na" class="text-title m-0 font-semibold">This link is not available</h1>
                        <p class="text-secondary m-0">The link may have expired or been withdrawn.</p>
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
                                Read-only view at step {{ v.head.step }}. This link expires {{ v.link.expiresAt }}.
                            </p>
                        </header>
                        @if (graph(); as g) {
                            <section aria-labelledby="embed-graph">
                                <h2 id="embed-graph" class="m-0 text-lg font-semibold">Graph</h2>
                                <svg
                                    role="img"
                                    [attr.viewBox]="'0 0 ' + g.size + ' ' + g.size"
                                    class="w-full max-w-xl"
                                    aria-label="Entity and link graph, read-only"
                                >
                                    @for (e of g.edges; track $index) {
                                        <line [attr.x1]="e.x1" [attr.y1]="e.y1" [attr.x2]="e.x2" [attr.y2]="e.y2" stroke="currentColor" opacity="0.4" />
                                    }
                                    @for (n of g.nodes; track n.id) {
                                        <circle [attr.cx]="n.x" [attr.cy]="n.y" r="6" fill="currentColor" />
                                        <text [attr.x]="n.x" [attr.y]="n.y - 10" text-anchor="middle" font-size="10" fill="currentColor">{{ n.id }}</text>
                                    }
                                </svg>
                            </section>
                        }
                        <section aria-labelledby="embed-ents">
                            <h2 id="embed-ents" class="m-0 text-lg font-semibold">
                                Entities ({{ v.workingSet.entities.length }})
                            </h2>
                            <ul class="m-0 list-none p-0">
                                @for (e of v.workingSet.entities; track e.entityId) {
                                    <li>
                                        {{ e.entityId }} <span class="text-secondary">{{ e.type }}</span>
                                    </li>
                                }
                            </ul>
                        </section>
                        <section aria-labelledby="embed-links">
                            <h2 id="embed-links" class="m-0 text-lg font-semibold">
                                Links ({{ v.workingSet.links.length }})
                            </h2>
                            <ul class="m-0 list-none p-0">
                                @for (l of v.workingSet.links; track $index) {
                                    <li>{{ l.source }} - {{ l.kind }} - {{ l.target }} ({{ l.count ?? 1 }})</li>
                                }
                            </ul>
                        </section>
                        <section aria-labelledby="embed-refs">
                            <h2 id="embed-refs" class="m-0 text-lg font-semibold">References ({{ v.references.length }})</h2>
                            <ul class="m-0 list-none p-0">
                                @for (r of v.references; track $index) {
                                    <li>{{ refText(r) }}</li>
                                }
                            </ul>
                        </section>
                        @if (v.annotations.length) {
                            <section aria-labelledby="embed-notes">
                                <h2 id="embed-notes" class="m-0 text-lg font-semibold">Notes</h2>
                                <ul class="m-0 list-none p-0">
                                    @for (n of v.annotations; track $index) {
                                        <li>{{ n.note }}</li>
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
    protected readonly state = signal<'loading' | 'ready' | 'unavailable'>('loading');
    protected readonly view = signal<EmbedView | null>(null);

    protected readonly graph = computed<EmbedGraph | null>(() => {
        const v = this.view();
        return v && v.workingSet.entities.length ? layoutEmbedGraph(v.workingSet) : null;
    });

    /** A reference is shown as returned (already masked): its title/label/note/id, else compact JSON. */
    protected refText(r: unknown): string {
        if (typeof r === 'string') return r;
        const o = (r ?? {}) as Record<string, unknown>;
        const t = o['title'] ?? o['label'] ?? o['note'] ?? o['id'];
        return typeof t === 'string' ? t : JSON.stringify(r);
    }

    private readonly svc = inject(EmbedViewService);
    private readonly destroyRef = inject(DestroyRef);

    constructor() {
        effect(() => {
            const t = this.token();
            this.state.set('loading');
            this.view.set(null);
            this.svc
                .view(t)
                .pipe(takeUntilDestroyed(this.destroyRef))
                .subscribe({
                    next: (v) => {
                        this.view.set(v);
                        this.state.set('ready');
                    },
                    error: () => this.state.set('unavailable'),
                });
        });
    }
}
