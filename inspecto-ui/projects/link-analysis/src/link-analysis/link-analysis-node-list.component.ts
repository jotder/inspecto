import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { G6GraphData } from '@inspecto/core/graph';
import { baseEdgeKind } from '@inspecto/core/graph/graph-view.component';

/** Nodes per page, and links shown per step under a selected node: a list never renders 20 000 rows. */
export const NODE_LIST_PAGE = 500;

/**
 * **Link Analysis — the graph as a list** (LA-A11Y-AUDIT-1, operator decision 2026-10-03): the text alternative
 * of the `<canvas>`. It reads the SAME graph the canvas draws (`canvasData()`, masked ids and all — nothing is
 * resolved or unmasked here) as a keyboard-navigable grid of nodes (label, kind, links) and, under the
 * selected node, a table of its links (direction, neighbour, kind, count).
 *
 * Keyboard (one tab stop, roving tabindex): ArrowUp/ArrowDown/Home/End move between rows; Enter or Space
 * selects the focused node, exactly as a canvas click does — the host handles `nodeSelect` with
 * `onNodeClick`, so the inspector / Investigation see the same selection.
 */
@Component({
    selector: 'inspecto-link-analysis-node-list',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2 text-sm" aria-label="Graph as a list">
            <p class="text-secondary m-0 text-xs tabular-nums" aria-live="polite" data-testid="node-list-status">
                @if (nodes().length) {
                    Showing {{ firstShown() }}–{{ lastShown() }} of {{ nodes().length.toLocaleString() }}
                    {{ nodes().length === 1 ? 'node' : 'nodes' }} ·
                }
                {{ (graph()?.edges?.length ?? 0).toLocaleString() }} links in the graph
            </p>
            @if (omittedLinks() > 0) {
                <p class="text-warn m-0 text-xs" role="status" data-testid="node-list-omitted">
                    {{ omittedLinks().toLocaleString() }} more links exist in the Working Set; they are over the render
                    limit, so neither the canvas nor this list has them.
                </p>
            }
            @if (nodes().length === 0) {
                <p class="m-0" data-testid="node-list-empty">There are no nodes to list.</p>
            } @else {
                <div class="flex items-center gap-2">
                    <button
                        type="button"
                        mat-stroked-button
                        [disabled]="pageIndex() === 0"
                        (click)="goToPage(pageIndex() - 1)"
                    >
                        Previous {{ pageSize() }}
                    </button>
                    <button
                        type="button"
                        mat-stroked-button
                        [disabled]="pageIndex() >= pageCount() - 1"
                        (click)="goToPage(pageIndex() + 1)"
                    >
                        Next {{ pageSize() }}
                    </button>
                    <span class="text-secondary text-xs tabular-nums">
                        Page {{ pageIndex() + 1 }} of {{ pageCount() }}
                    </span>
                </div>
                <table class="w-full border-collapse text-left" role="grid" aria-label="Nodes">
                    <thead>
                        <tr class="text-secondary text-xs uppercase">
                            <th scope="col" class="py-1 pr-2 font-semibold">Node</th>
                            <th scope="col" class="py-1 pr-2 font-semibold">Kind</th>
                            <th scope="col" class="py-1 font-semibold">Links</th>
                        </tr>
                    </thead>
                    <tbody>
                        @for (n of pageNodes(); track n.id; let i = $index) {
                            <tr
                                class="cursor-pointer border-t"
                                style="border-color: var(--gamma-border)"
                                [attr.data-index]="i"
                                [attr.data-node-id]="n.id"
                                [attr.tabindex]="i === focusIndex() ? 0 : -1"
                                [attr.aria-selected]="n.id === picked()"
                                [class.font-semibold]="n.id === picked()"
                                [style.boxShadow]="n.id === picked() ? 'inset 3px 0 0 var(--gamma-primary)' : null"
                                (click)="select(n.id, i)"
                                (keydown)="onKeydown($event)"
                            >
                                <td class="py-1 pr-2">{{ n.data.label }}</td>
                                <td class="py-1 pr-2">{{ n.data.kind }}</td>
                                <td class="py-1 tabular-nums">{{ degree().get(n.id) ?? 0 }}</td>
                            </tr>
                        }
                    </tbody>
                </table>
                @if (pickedNode(); as p) {
                    <h3 class="m-0 mt-2 text-sm font-semibold" data-testid="node-list-links-title">
                        Links of {{ p.data.label }}
                    </h3>
                    @if (pickedLinks().length === 0) {
                        <p class="m-0">This node has no links.</p>
                    } @else {
                        <table class="w-full border-collapse text-left" [attr.aria-label]="'Links of ' + p.data.label">
                            <thead>
                                <tr class="text-secondary text-xs uppercase">
                                    <th scope="col" class="py-1 pr-2 font-semibold">Direction</th>
                                    <th scope="col" class="py-1 pr-2 font-semibold">Neighbour</th>
                                    <th scope="col" class="py-1 pr-2 font-semibold">Kind</th>
                                    <th scope="col" class="py-1 font-semibold">Count</th>
                                </tr>
                            </thead>
                            <tbody>
                                @for (l of shownLinks(); track l.id) {
                                    <tr class="border-t" style="border-color: var(--gamma-border)">
                                        <td class="py-1 pr-2">{{ l.direction }}</td>
                                        <td class="py-1 pr-2" [attr.data-neighbour-id]="l.neighbourId">
                                            {{ l.neighbour }}
                                        </td>
                                        <td class="py-1 pr-2">{{ l.kind }}</td>
                                        <td class="py-1 tabular-nums">{{ l.count }}</td>
                                    </tr>
                                }
                            </tbody>
                        </table>
                        <div class="flex items-center gap-2">
                            <span class="text-secondary text-xs tabular-nums" data-testid="node-list-links-status">
                                Showing {{ shownLinks().length.toLocaleString() }} of
                                {{ pickedLinks().length.toLocaleString() }} links
                            </span>
                            @if (shownLinks().length < pickedLinks().length) {
                                <button type="button" mat-stroked-button (click)="showMoreLinks()">
                                    Show {{ pageSize() }} more links
                                </button>
                            }
                        </div>
                    }
                } @else {
                    <p class="text-secondary m-0 text-xs">Select a node (Enter) to list its links.</p>
                }
            }
        </section>
    `,
})
export class LinkAnalysisNodeListComponent {
    /** The graph the canvas draws (`canvasData()`). */
    readonly graph = input.required<G6GraphData | null>();
    /** Working Set links the canvas left off at its render ceiling — the list does not have them either. */
    readonly omittedLinks = input(0);
    readonly pageSize = input(NODE_LIST_PAGE);
    /** The analyst chose a node (Enter / Space / click) — the host handles it like a canvas click. */
    readonly nodeSelect = output<string>();

    readonly picked = signal<string | null>(null);
    readonly focusIndex = signal(0);
    readonly linkLimit = signal(0);
    private readonly page = signal(0);

    readonly nodes = computed(() => this.graph()?.nodes ?? []);
    readonly pageCount = computed(() => Math.max(1, Math.ceil(this.nodes().length / this.pageSize())));
    readonly pageIndex = computed(() => Math.min(this.page(), this.pageCount() - 1));
    readonly pageNodes = computed(() => {
        const start = this.pageIndex() * this.pageSize();
        return this.nodes().slice(start, start + this.pageSize());
    });
    readonly firstShown = computed(() => (this.pageIndex() * this.pageSize() + 1).toLocaleString());
    readonly lastShown = computed(() =>
        (this.pageIndex() * this.pageSize() + this.pageNodes().length).toLocaleString(),
    );

    private readonly labels = computed(() => new Map(this.nodes().map((n) => [n.id, n.data.label])));
    /** Links per node — a link counts once for each end that is that node. */
    readonly degree = computed(() => {
        const d = new Map<string, number>();
        for (const e of this.graph()?.edges ?? []) {
            d.set(e.source, (d.get(e.source) ?? 0) + 1);
            if (e.target !== e.source) d.set(e.target, (d.get(e.target) ?? 0) + 1);
        }
        return d;
    });

    readonly pickedNode = computed(() => this.nodes().find((n) => n.id === this.picked()) ?? null);
    readonly pickedLinks = computed(() => {
        const id = this.picked();
        if (id === null) return [];
        const labels = this.labels();
        return (this.graph()?.edges ?? [])
            .filter((e) => e.source === id || e.target === id)
            .map((e) => {
                const out = e.source === id;
                const neighbourId = out ? e.target : e.source;
                return {
                    id: e.id,
                    direction: out ? 'out' : 'in',
                    neighbourId,
                    neighbour: labels.get(neighbourId) ?? neighbourId,
                    kind: baseEdgeKind(e.data.kind),
                    count: (e.data as { count?: number }).count ?? 1,
                };
            });
    });
    readonly shownLinks = computed(() => this.pickedLinks().slice(0, this.linkLimit() || this.pageSize()));

    goToPage(p: number): void {
        this.page.set(Math.max(0, Math.min(p, this.pageCount() - 1)));
        this.focusIndex.set(0);
    }

    showMoreLinks(): void {
        this.linkLimit.set((this.linkLimit() || this.pageSize()) + this.pageSize());
    }

    select(id: string, index: number): void {
        this.picked.set(id);
        this.focusIndex.set(index);
        this.linkLimit.set(0);
        this.nodeSelect.emit(id);
    }

    onKeydown(ev: KeyboardEvent): void {
        const grid = (ev.currentTarget as HTMLElement).closest('tbody') as HTMLElement;
        const row = (ev.target as HTMLElement).closest<HTMLElement>('tr[data-index]');
        if (!row) return;
        const rows = Array.from(grid.querySelectorAll<HTMLElement>('tbody tr[data-index]'));
        const at = Number(row.dataset['index']);
        let to: number;
        switch (ev.key) {
            case 'ArrowDown':
                to = Math.min(at + 1, rows.length - 1);
                break;
            case 'ArrowUp':
                to = Math.max(at - 1, 0);
                break;
            case 'Home':
                to = 0;
                break;
            case 'End':
                to = rows.length - 1;
                break;
            case 'Enter':
            case ' ':
                ev.preventDefault();
                this.select(row.dataset['nodeId'] ?? '', at);
                return;
            default:
                return;
        }
        ev.preventDefault();
        this.focusIndex.set(to);
        rows[to]?.focus();
    }
}
