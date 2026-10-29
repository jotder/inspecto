import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NodeKind } from '@inspecto/core/api';
import { G6GraphData } from '@inspecto/core/graph';
import { SUPER_NODE_KIND } from '@inspecto/link-analysis/graph/graph-analysis';
import { activeDomainProfileId, domainProfile, termLabel } from '@inspecto/link-analysis/graph/domain-profile';
import { WorkingSetStat } from '@inspecto/link-analysis/graph/working-set-stats';
import { entityTypeIcon, nodeColor } from '@inspecto/core/graph/catalog-graph';
import { baseEdgeKind } from '@inspecto/core/graph/graph-view.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';

/** One swatch row of the legend. */
export interface LegendItem {
    kind: string;
    color: string;
    count: number;
}

/**
 * The legend's node rows for a drawn graph — kind → canvas colour → count, most common first. ONE derivation for
 * the studio and the saved-view widget (R3-04), so a view reads the same wherever it is shown. The colour is the
 * view's own per-kind override, else the kind's default canvas colour — what `<inspecto-graph-view>` paints.
 */
export function legendItemsFor(g: G6GraphData | null, nodeColors: Record<string, string> = {}): LegendItem[] {
    if (!g) return [];
    const counts = new Map<string, number>();
    const stamped = new Map<string, string>(); // a source-stamped node colour (LA-08 category) per kind
    // A super-node is a stand-in, not an entity: counting it as a kind would misstate how many of
    // that kind the analyst is looking at, and it has no real kind to be counted under anyway.
    for (const n of g.nodes) {
        if (n.data.kind === SUPER_NODE_KIND) continue;
        counts.set(n.data.kind, (counts.get(n.data.kind) ?? 0) + 1);
        if (n.data.color && !stamped.has(n.data.kind)) stamped.set(n.data.kind, n.data.color);
    }
    return [...counts.entries()]
        .sort((a, b) => b[1] - a[1])
        .map(([kind, count]) => ({
            kind,
            count,
            color: nodeColors[kind] ?? stamped.get(kind) ?? nodeColor(kind as NodeKind),
        }));
}

/** The link kinds present, without their folded ` · N` count suffix, sorted. */
export function legendEdgeKindsFor(g: G6GraphData | null): string[] {
    return g ? [...new Set(g.edges.map((e) => baseEdgeKind(e.data.kind)))].sort() : [];
}

/** Toggle one link kind in the hidden set (a new array, sorted — the order never depends on click order). */
export function toggleHiddenKind(hidden: readonly string[], kind: string): string[] {
    return hidden.includes(kind) ? hidden.filter((k) => k !== kind) : [...hidden, kind].sort();
}

/**
 * The graph without the links of the hidden kinds — a client-side view filter, the nodes all stay. Any other field of
 * the graph (a Working Set's `omittedLinks`, `truncated`) is kept; nothing hidden returns the same object.
 */
export function hideLinkKinds<G extends G6GraphData>(g: G | null, hidden: readonly string[]): G | null {
    if (!g || !hidden.length) return g;
    const off = new Set(hidden);
    return { ...g, edges: g.edges.filter((e) => !off.has(baseEdgeKind(e.data.kind))) };
}

/** The two canvas overlays whose open state a browser remembers. */
export type CanvasOverlay = 'legend' | 'workingSet';

/**
 * Below this canvas width the legend and Working set overlays START collapsed to their pills (LA-ONBOARD-FIXES-1,
 * F-11): at a laptop's ~800 px canvas the two open boxes (up to 320 + 256 px wide) covered most of the graph.
 */
export const OVERLAY_COLLAPSE_BELOW_PX = 1000;

const OVERLAY_OPEN_KEY = 'inspecto.la.overlayOpen.';

/** The user's own earlier open/close of an overlay in this browser; null when they never chose (or storage is blocked). */
export function storedOverlayOpen(which: CanvasOverlay): boolean | null {
    try {
        const v = localStorage.getItem(OVERLAY_OPEN_KEY + which);
        return v === 'true' || v === 'false' ? v === 'true' : null;
    } catch {
        return null;
    }
}

/** Remember an explicit open/close for this browser (a saved view or the width rule is never remembered). */
export function rememberOverlayOpen(which: CanvasOverlay, open: boolean): void {
    try {
        localStorage.setItem(OVERLAY_OPEN_KEY + which, String(open));
    } catch {
        // private window / blocked storage: the width rule decides again next time
    }
}

/** An overlay's starting state: the remembered choice wins; otherwise open only on a canvas at least the threshold wide. */
export function overlayStartsOpen(stored: boolean | null, canvasWidth: number): boolean {
    return stored ?? canvasWidth >= OVERLAY_COLLAPSE_BELOW_PX;
}

/**
 * **Link Analysis — canvas legend** (presentational). Node kinds with their canvas colour and count, plus the
 * link kinds present. Minimises to a pill so the graph gets the space; the host owns the open state so the
 * View toolbox and a saved view can drive it.
 */
@Component({
    selector: 'inspecto-link-analysis-legend',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule, ChipComponent],
    host: { class: 'block' },
    styles: `
        /* WCAG 2.5.8: the chip inside is 20 px high, the pressable target must be at least 24 px */
        .link-kind {
            display: inline-flex;
            align-items: center;
            min-height: 24px;
            min-width: 24px;
        }
        .link-kind:focus-visible {
            outline: 2px solid currentColor;
            outline-offset: 2px;
        }
    `,
    template: `
        @if (open()) {
            <div class="max-w-xs rounded-lg border p-2 text-xs shadow-sm" style="background: var(--gamma-bg-card)">
                <div class="flex items-center gap-1">
                    <span class="font-semibold">Legend</span>
                    <button
                        mat-icon-button
                        class="ml-auto !h-6 !w-6 !p-0"
                        (click)="openChange.emit(false)"
                        matTooltip="Minimise the legend"
                        aria-label="Minimise the legend"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:minus"></mat-icon>
                    </button>
                </div>
                <ul class="mt-1 flex flex-col gap-0.5" aria-label="Node kinds">
                    @for (i of items(); track i.kind) {
                        <li class="flex items-center gap-2">
                            @if (glyphOf(i); as glyph) {
                                <img class="h-3.5 w-3.5 shrink-0" [src]="glyph" alt="" data-testid="legend-glyph" />
                            } @else {
                                <span
                                    class="inline-block h-2.5 w-2.5 shrink-0 rounded-full"
                                    [style.background]="i.color"
                                    aria-hidden="true"
                                ></span>
                            }
                            <span>{{ nodeLabel(i.kind) }}</span>
                            <span class="text-secondary ml-auto tabular-nums">{{ i.count }}</span>
                        </li>
                    }
                </ul>
                @if (highConnectivity()) {
                    <!-- supernode suppression: the canvas rings each flagged entity with a wide halo -->
                    <div class="mt-1 flex items-center gap-2 border-t pt-1" data-testid="legend-hubs">
                        <span
                            class="inline-block h-3 w-3 shrink-0 rounded-full border-2"
                            style="border-color: var(--gamma-text-secondary); box-shadow: 0 0 0 3px var(--gamma-border)"
                            aria-hidden="true"
                        ></span>
                        <span>Ringed: high connectivity — shown, not expanded</span>
                        <span class="text-secondary ml-auto tabular-nums">{{ highConnectivity() }}</span>
                    </div>
                }
                @if (unexpanded()) {
                    <!-- LA-UNEXPANDED-MARKER-1: a Working Set entity no expand has followed yet is drawn with a dotted outline -->
                    <div class="mt-1 flex items-center gap-2" data-testid="legend-unexpanded">
                        <span
                            class="inline-block h-3 w-3 shrink-0 rounded-full border-2 border-dotted"
                            style="border-color: var(--gamma-text-secondary)"
                            aria-hidden="true"
                        ></span>
                        <span>Dotted outline: not expanded yet</span>
                        <span class="text-secondary ml-auto tabular-nums">{{ unexpanded() }}</span>
                    </div>
                    <div class="mt-1 flex flex-wrap gap-1" data-testid="legend-unexpanded-aid">
                        <button
                            type="button"
                            class="rounded-full border px-2 py-0.5 text-[11px]"
                            style="border-color: var(--gamma-border)"
                            data-testid="next-unexpanded"
                            (click)="nextUnexpanded.emit()"
                        >
                            Next unexpanded
                        </button>
                        <button
                            type="button"
                            class="rounded-full border px-2 py-0.5 text-[11px]"
                            style="border-color: var(--gamma-border)"
                            data-testid="only-unexpanded"
                            [attr.aria-pressed]="onlyUnexpanded()"
                            [style.background]="onlyUnexpanded() ? 'var(--gamma-border)' : null"
                            (click)="onlyUnexpandedChange.emit(!onlyUnexpanded())"
                        >
                            Only unexpanded
                        </button>
                    </div>
                }
                @if (edgeKinds().length) {
                    <!-- one toggle per link kind: pressed = drawn; unpressing hides that kind's links on the canvas -->
                    <ul class="m-0 mt-1 flex list-none flex-wrap gap-1 border-t p-0 pt-1" aria-label="Link kinds">
                        @for (k of edgeKinds(); track k) {
                            <li>
                                <button
                                    type="button"
                                    class="link-kind rounded-full"
                                    [attr.aria-pressed]="!hiddenEdgeKinds().includes(k)"
                                    [attr.aria-label]="
                                        (hiddenEdgeKinds().includes(k) ? 'Show ' : 'Hide ') + linkLabel(k) + ' links'
                                    "
                                    [attr.data-kind]="k"
                                    (click)="edgeKindToggle.emit(k)"
                                >
                                    <inspecto-chip
                                        [variant]="hiddenEdgeKinds().includes(k) ? 'outline' : 'soft'"
                                        [tone]="hiddenEdgeKinds().includes(k) ? 'neutral' : 'primary'"
                                    >
                                        <span
                                            class="inline-block h-2 w-3 shrink-0 rounded-sm"
                                            [style.background]="edgeColors()[k] ?? 'var(--gamma-text-secondary)'"
                                            [style.opacity]="hiddenEdgeKinds().includes(k) ? 0.4 : 1"
                                            aria-hidden="true"
                                        ></span>
                                        <span [class.line-through]="hiddenEdgeKinds().includes(k)">{{
                                            linkLabel(k)
                                        }}</span>
                                    </inspecto-chip>
                                </button>
                            </li>
                        }
                    </ul>
                    <!-- LA-UNEXPANDED-MARKER-1 decision: a hide here is the drawing only, never what an expand reads or the log records -->
                    <div class="text-secondary mt-1 text-[11px]" data-testid="legend-kinds-note">
                        Hiding only changes the drawing. Expand still follows hidden link kinds.
                    </div>
                }
                @if (hint()) {
                    <div class="text-secondary mt-1">{{ hint() }}</div>
                }
            </div>
        } @else {
            <button
                type="button"
                class="rounded-full border px-2.5 py-0.5 text-xs font-semibold shadow-sm"
                style="background: var(--gamma-bg-card)"
                (click)="openChange.emit(true)"
                aria-expanded="false"
                aria-label="Show the legend"
            >
                Legend
            </button>
        }
    `,
})
export class LinkAnalysisLegendComponent {
    /**
     * The Entity Types registry's label for a node kind, else what the active Industry pack calls it
     * (`entity_type.<kind>`), else the kind itself. Display only.
     */
    protected nodeLabel(kind: string): string {
        return this.typeLabels()[kind] || termLabel(domainProfile(activeDomainProfileId()), `entity_type.${kind}`, kind);
    }
    /** The Entity Type's canvas glyph in the kind's colour (the icon the node is drawn with); undefined = a plain swatch. */
    protected glyphOf(i: LegendItem): string | undefined {
        return entityTypeIcon(i.kind, i.color);
    }
    /** Entity Type id → registry label (`GET /inv/entity-types`); absent = not loaded. */
    readonly typeLabels = input<Record<string, string>>({});
    /** What the active Industry pack calls a link kind (`link_kind.<kind>`), else the kind (or "unlabelled"). Display only. */
    protected linkLabel(kind: string): string {
        return termLabel(domainProfile(activeDomainProfileId()), `link_kind.${kind}`, kind || 'unlabelled');
    }
    readonly items = input<LegendItem[]>([]);
    readonly edgeKinds = input<string[]>([]);
    /** Link kinds hidden on the canvas (their chip shows unpressed). */
    readonly hiddenEdgeKinds = input<string[]>([]);
    /** The view's per-kind link colours — the swatch; a kind without one shows the default link ink. */
    readonly edgeColors = input<Record<string, string>>({});
    /** A link-kind chip was pressed: the host toggles that kind's links on the canvas. */
    readonly edgeKindToggle = output<string>();
    readonly hint = input('');
    /** How many drawn entities an Investigation flagged high connectivity; 0 hides the legend row. */
    readonly highConnectivity = input(0);
    /** How many drawn Working Set entities no expand has followed yet; 0 hides the legend row. */
    readonly unexpanded = input(0);
    /** The "Only unexpanded" switch is on: the canvas draws just the unexpanded frontier. */
    readonly onlyUnexpanded = input(false);
    readonly onlyUnexpandedChange = output<boolean>();
    /** "Next unexpanded": the host picks the next unexpanded node, so Expand one hop acts on it. */
    readonly nextUnexpanded = output<void>();
    readonly open = input(true);
    readonly openChange = output<boolean>();
}

/**
 * **Link Analysis — working set** (presentational). What is on the canvas versus what was loaded, as stat
 * tiles derived by `workingSetStats` (shaped by the domain profile), plus the truncation signal — the
 * two-stage query loop's "are you looking at the whole answer" indicator (spec §3.7).
 */
@Component({
    selector: 'inspecto-link-analysis-working-set',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    host: { class: 'block' },
    template: `
        @if (open()) {
            <div
                class="w-64 max-w-full rounded-lg border p-2 text-xs shadow-sm"
                style="background: var(--gamma-bg-card)"
            >
                <div class="flex items-center gap-1">
                    <span class="font-semibold">Graph counts</span>
                    @if (truncated()) {
                        <span class="text-warn font-semibold" title="The server cut this result at its limit">
                            cut short
                        </span>
                    } @else {
                        <span class="text-secondary">{{ mode() }}</span>
                    }
                    <button
                        mat-icon-button
                        class="ml-auto !h-6 !w-6 !p-0"
                        (click)="openChange.emit(false)"
                        matTooltip="Minimise graph counts"
                        aria-label="Minimise graph counts"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:minus"></mat-icon>
                    </button>
                </div>
                @if (countsNote()) {
                    <p class="text-secondary m-0 mt-0.5 text-[11px] leading-snug" data-testid="working-set-counts">
                        {{ countsNote() }}
                    </p>
                }
                <dl class="mt-1 grid grid-cols-2 gap-1" aria-label="Graph counts">
                    @for (s of stats(); track s.label) {
                        <div class="rounded-md border px-2 py-1" [title]="s.hint ?? ''">
                            <dt class="text-secondary truncate text-[10px] font-semibold">{{ s.label }}</dt>
                            <dd class="m-0 truncate font-semibold tabular-nums">{{ s.value }}</dd>
                        </div>
                    }
                </dl>
                @if (splitIdentityCount(); as n) {
                    <p class="text-warn m-0 mt-1 text-[11px] leading-snug" [title]="splitIdentityHint()">
                        {{ n }} possible split {{ n === 1 ? 'identity' : 'identities' }}
                    </p>
                }
                @if (customizable()) {
                    <button type="button" class="text-primary mt-1 text-[11px] underline" (click)="customize.emit()">
                        Customise counts
                    </button>
                }
            </div>
        } @else {
            <button
                type="button"
                class="rounded-full border px-2.5 py-0.5 text-xs font-semibold shadow-sm"
                style="background: var(--gamma-bg-card)"
                (click)="openChange.emit(true)"
                aria-expanded="false"
                aria-label="Show graph counts"
            >
                Graph counts
                @if (countsNote()) {
                    <span class="text-secondary font-normal">· {{ countsOf() }}</span>
                }
                @if (truncated()) {
                    <span class="text-warn">· cut short</span>
                }
                @if (splitIdentityCount()) {
                    <span class="text-warn" [title]="splitIdentityHint()">· split identities</span>
                }
            </button>
        }
    `,
})
export class LinkAnalysisWorkingSetComponent {
    readonly stats = input<WorkingSetStat[]>([]);
    readonly truncated = input(false);
    /**
     * How many identities the value-projected id scheme appears to have SPLIT (D-S4). Reported, never
     * merged: a ranking computed over a divided identity space is wrong in a way no tile would show.
     */
    readonly splitIdentityCount = input(0);
    /** Names the colliding spellings, so the analyst can judge whether they are one entity or two. */
    readonly splitIdentityHint = input('');
    /** Short label for how the set was sampled, e.g. "top-N by count". */
    readonly mode = input('');
    readonly customizable = input(false);
    /** Which graph the tiles count (LA-STUDIO-UI-REORG-1 §1): `query`, or `query-under-investigation` while the canvas
     *  draws an Investigation's Working Set instead; '' = say nothing. */
    readonly countsGraph = input<'' | 'query' | 'query-under-investigation'>('');
    readonly countsOf = computed(() => (this.countsGraph() ? 'graph on screen' : ''));
    readonly countsNote = computed(() =>
        this.countsGraph() === 'query-under-investigation'
            ? 'Totals for the full graph. The canvas shows the Investigation’s Working Set (its counts are in the Investigation tab).'
            : this.countsGraph() === 'query'
              ? 'Totals for the graph on screen.'
              : '',
    );
    readonly open = input(true);
    readonly openChange = output<boolean>();
    readonly customize = output<void>();
}
