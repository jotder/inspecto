import {
    AfterViewInit,
    ChangeDetectionStrategy,
    Component,
    ElementRef,
    OnDestroy,
    computed,
    input,
    signal,
    viewChild,
} from '@angular/core';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { formatNumber, NumberFormat } from '../number-format';
import { seriesColors } from '../series-colors';
import { buildTreemap, hasHeader, layoutTreemap, TREEMAP_OTHER, TreemapRow, TreemapTile } from '../treemap-layout';

/** Which channel a clicked cell's value belongs to: a group (level 1) or a subgroup (level 2). */
export type TreemapChannel = 'group' | 'subgroup';

/** A drawn cell: its layout plus what the template needs — fill, full text, and which labels fit. */
interface TreemapCell extends TreemapTile {
    fill: string;
    text: string;
    valueText: string;
    shareText: string;
    showName: boolean;
    showValue: boolean;
    header: boolean;
}

/** How many items the text alternative names. */
const SUMMARY_TOP = 5;

/**
 * The `treemap` Visualization Type's renderer — mounted by `viz-render` through `NgComponentOutlet`, inside the tile
 * card (no card of its own). Absolutely-positioned buttons over a squarified layout (`treemap-layout.ts`):
 *
 * - colour by level-1 group through `seriesColors()` (status tones first, then stable by name); a subgroup takes a
 *   tint of its group, mixed toward the card colour so it reads in both themes; "Other" is neutral;
 * - a label (name, value in the widget's format, share of the total) only where it fits, on a card-coloured chip so
 *   it keeps its contrast over any fill; the full text is always the button's accessible name and tooltip;
 * - every cell is a keyboard-focusable button with a two-tone focus ring, and a click reports the cell's channel and
 *   raw value through `select` (the drill seam — "Other" is not a value and never reports); a subgroup click also
 *   reports its parent group's raw value, so the host can select exactly that rectangle;
 * - a text alternative names the total, the largest items and how many items were left out at zero or below;
 * - the layout follows the container through a `ResizeObserver` (dashboard span, side-pane collapse).
 */
@Component({
    selector: 'inspecto-treemap',
    standalone: true,
    imports: [InspectoEmptyStateComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex h-full flex-col">
            <!-- The box always renders, so the ResizeObserver has one stable element to watch. -->
            <div
                #box
                class="relative min-h-0 flex-1 overflow-hidden rounded-md"
                [attr.role]="tree().groups.length ? 'group' : null"
                [attr.aria-roledescription]="tree().groups.length ? 'treemap' : null"
                [attr.aria-label]="tree().groups.length ? summary() : null"
                data-testid="treemap"
            >
                @for (c of cells(); track c.key) {
                    <button
                        type="button"
                        class="tm-cell absolute block overflow-hidden text-left"
                        [style.left.px]="c.x"
                        [style.top.px]="c.y"
                        [style.width.px]="c.w"
                        [style.height.px]="c.h"
                        [style.background]="c.fill"
                        [attr.aria-label]="c.text"
                        [attr.aria-disabled]="c.folded ? true : null"
                        [title]="c.text"
                        [attr.data-level]="c.level"
                        (click)="pick(c)"
                    >
                        @if (c.header) {
                            <span
                                class="tm-header text-default block truncate px-1 text-xs font-medium"
                                aria-hidden="true"
                            >
                                {{ label(c.name) }} · {{ c.valueText }}
                            </span>
                        } @else if (c.showName) {
                            <span
                                class="bg-card text-default m-1 inline-block max-w-full rounded px-1 text-xs leading-tight"
                                aria-hidden="true"
                            >
                                <span class="block truncate font-medium">{{ label(c.name) }}</span>
                                @if (c.showValue) {
                                    <span class="text-secondary block truncate tabular-nums"
                                        >{{ c.valueText }} · {{ c.shareText }}</span
                                    >
                                }
                            </span>
                        }
                    </button>
                }
                @if (!tree().groups.length) {
                    <inspecto-empty-state
                        class="block h-full"
                        icon="heroicons_outline:squares-2x2"
                        [message]="tree().excluded ? excludedText() : 'No data to chart yet.'"
                    />
                }
            </div>
            @if (tree().groups.length && tree().excluded) {
                <p class="text-secondary mt-1 text-xs" data-testid="treemap-excluded">{{ excludedText() }}</p>
            }
        </div>
    `,
    styles: [
        `
            :host {
                display: block;
                height: 100%;
            }
            /* The card colour between cells is the gutter. */
            .tm-cell {
                box-shadow: inset 0 0 0 1px var(--gamma-bg-card);
                border-radius: 4px;
                cursor: pointer;
            }
            .tm-cell[aria-disabled='true'] {
                cursor: default;
            }
            /* Two tones, so the ring shows on any fill in either theme. */
            .tm-cell:focus-visible {
                outline: 2px solid var(--gamma-text-default);
                outline-offset: -2px;
                box-shadow: inset 0 0 0 4px var(--gamma-bg-card);
                z-index: 1;
            }
            .tm-header {
                line-height: 16px;
            }
        `,
    ],
})
export class TreemapComponent implements AfterViewInit, OnDestroy {
    /** One row per group (and subgroup) with its value — the plugin's `props.treemap`. */
    readonly rows = input<TreemapRow[]>([]);
    readonly format = input<NumberFormat | undefined>(undefined);
    /** `options.treemap.limit` — groups drawn before the rest fold into "Other". */
    readonly limit = input<number | undefined>(undefined);
    /** Called with a clicked cell's channel and RAW value (a blank stays ''), for drill-down; a subgroup click also
     *  passes its parent group's raw value. */
    readonly select = input<((channel: TreemapChannel, value: string, group?: string) => void) | undefined>(undefined);

    private readonly box = viewChild<ElementRef<HTMLElement>>('box');
    /** The container's size, from the ResizeObserver (0 until measured — nothing is laid out in a 0 box). */
    readonly size = signal<{ w: number; h: number }>({ w: 0, h: 0 });
    private resizeObserver: ResizeObserver | null = null;

    readonly tree = computed(() => buildTreemap(this.rows(), this.limit()));

    readonly cells = computed<TreemapCell[]>(() => {
        const tree = this.tree();
        const { w, h } = this.size();
        const fmt = this.format();
        const names = tree.groups.filter((g) => !g.folded).map((g) => g.name);
        const colors = new Map(seriesColors(names).map((c, i) => [names[i], c]));
        return layoutTreemap(tree, w, h).map((t) => {
            const base = colors.get(t.group);
            const valueText = formatNumber(t.value, fmt);
            const shareText = `${formatNumber(t.share * 100, { decimals: 1 })} %`;
            const header = t.frame && hasHeader(t);
            return {
                ...t,
                fill: fillFor(t, base),
                text: cellText(t, valueText, shareText),
                valueText,
                shareText,
                header,
                showName: !t.frame && t.w >= 56 && t.h >= 24,
                showValue: !t.frame && t.w >= 56 && t.h >= 40,
            };
        });
    });

    /** The text alternative: size, total, the largest items and what was left out. */
    readonly summary = computed(() => {
        const tree = this.tree();
        const fmt = this.format();
        const levels = tree.groups.some((g) => g.children.length) ? 'two levels' : 'one level';
        const items = tree.groups.flatMap((g) =>
            g.children.length
                ? g.children.map((c) => ({ name: `${label(c.name)} (${label(g.name)})`, value: c.value }))
                : [{ name: g.folded ? `${TREEMAP_OTHER} (${g.folded} more)` : label(g.name), value: g.value }],
        );
        const top = [...items]
            .sort((a, b) => b.value - a.value)
            .slice(0, SUMMARY_TOP)
            .map((i) => `${i.name} ${formatNumber(i.value, fmt)} (${share(i.value, tree.total)})`);
        const excluded = tree.excluded ? ` ${this.excludedText()}.` : '';
        return (
            `Treemap, ${levels}: ${tree.groups.length} groups totalling ${formatNumber(tree.total, fmt)}. ` +
            `Largest: ${top.join('; ')}.${excluded}`
        );
    });

    readonly excludedText = computed(() => {
        const n = this.tree().excluded;
        return n === 1 ? '1 item at zero or below is not shown' : `${n} items at zero or below are not shown`;
    });

    ngAfterViewInit(): void {
        this.measure();
        // Chart.js-host pattern: follow the container, not just the window. Guarded for jsdom (no ResizeObserver).
        const box = this.box()?.nativeElement;
        if (box && typeof ResizeObserver !== 'undefined') {
            this.resizeObserver = new ResizeObserver(() => this.measure());
            this.resizeObserver.observe(box);
        }
    }

    ngOnDestroy(): void {
        this.resizeObserver?.disconnect();
    }

    /** Read the box's current size into {@link size}; the layout recomputes from it. */
    measure(): void {
        const el = this.box()?.nativeElement;
        if (!el) return;
        const w = el.clientWidth;
        const h = el.clientHeight;
        const cur = this.size();
        if (cur.w !== w || cur.h !== h) this.size.set({ w, h });
    }

    pick(c: TreemapCell): void {
        if (c.folded) return;
        if (c.level === 2) this.select()?.('subgroup', c.name, c.group);
        else this.select()?.('group', c.name);
    }

    /** A blank value reads "(blank)" — never an unlabelled cell. */
    label(name: string): string {
        return label(name);
    }
}

function label(name: string): string {
    return name.trim() === '' ? '(blank)' : name;
}

function share(value: number, total: number): string {
    return `${formatNumber(total > 0 ? (value / total) * 100 : 0, { decimals: 1 })} %`;
}

/** Group colour for a leaf; a light tint behind a framed group's subgroups; darker-to-lighter tints of the group
 *  for its subgroups; a neutral for "Other". Mixed toward the card colour, so every tint follows the theme. */
function fillFor(t: TreemapTile, base: string | undefined): string {
    if (t.folded || !base) return 'color-mix(in srgb, var(--gamma-text-secondary) 30%, var(--gamma-bg-card))';
    if (t.frame) return `color-mix(in srgb, ${base} 22%, var(--gamma-bg-card))`;
    if (t.level === 1) return base;
    return `color-mix(in srgb, ${base} ${Math.max(40, 90 - t.index * 12)}%, var(--gamma-bg-card))`;
}

function cellText(t: TreemapTile, valueText: string, shareText: string): string {
    if (t.folded) return `${TREEMAP_OTHER} (${t.folded} more groups): ${valueText}, ${shareText} of total`;
    const name = t.level === 2 ? `${label(t.name)} in ${label(t.group)}` : label(t.name);
    return `${name}: ${valueText}, ${shareText} of total`;
}
