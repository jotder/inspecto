import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { dateAxisLabels, fullDateLabel } from '../date-labels';
import { CellPaint, HeatmapMatrix, HeatmapOptions, HeatmapValue, cellPaint, heatDomain, rampColor } from '../heatmap';
import { formatNumber, NumberFormat } from '../number-format';
import { Better } from '../target-status';

/** The heatmap's input: the pivoted matrix plus the channel names its caption and corner header read. */
export type HeatmapData = HeatmapMatrix & { rowLabel: string; columnLabel: string; valueLabel: string };

/** A clicked cell — the raw row and column values (not the display labels), for the drill-down seam. */
export interface HeatmapCellClick {
    row: string;
    column: string;
}

/** From this many columns the cells shrink to swatches and their value moves to the accessible name + tooltip. */
const COMPACT_FROM = 13;

interface Cell {
    column: string;
    empty: boolean;
    text: string;
    title: string;
    aria: string;
    paint: CellPaint;
}

/**
 * The `heatmap` Visualization Type's renderer — a real `<table>` (column headers, a sticky row header, a caption that
 * summarises the data) whose cells are coloured on the Widget's scale (`heatmap.ts`). Every coloured cell is a button:
 * its accessible name leads with the printed value, then the row and column, so colour is never the only signal; a
 * wide matrix scrolls sideways inside the tile instead of squashing. The tile card around it owns the frame.
 */
@Component({
    selector: 'inspecto-heatmap',
    standalone: true,
    imports: [StatusBadgeComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'flex min-h-0 max-h-80 flex-col gap-2' },
    template: `
        <div class="min-h-0 flex-1 overflow-auto" role="region" tabindex="0" [attr.aria-label]="regionLabel()">
            <table class="w-full border-separate border-spacing-0.5 text-xs tabular-nums">
                <caption class="sr-only">
                    {{
                        summary()
                    }}
                </caption>
                <thead>
                    <tr>
                        <th
                            scope="col"
                            class="bg-card text-secondary sticky left-0 top-0 z-20 px-2 py-1 text-left font-medium"
                        >
                            {{ data().rowLabel }}
                        </th>
                        @for (c of columnHeaders(); track $index) {
                            <th
                                scope="col"
                                class="bg-card text-secondary sticky top-0 z-10 whitespace-nowrap px-1 py-1 text-center font-medium"
                                [attr.title]="c.title"
                            >
                                {{ c.text }}
                            </th>
                        }
                    </tr>
                </thead>
                <tbody>
                    @for (r of grid(); track $index) {
                        <tr>
                            <th
                                scope="row"
                                class="bg-card sticky left-0 z-10 max-w-48 truncate whitespace-nowrap px-2 py-1 text-left font-medium"
                                [attr.title]="r.title"
                            >
                                {{ r.text }}
                            </th>
                            @for (cell of r.cells; track $index) {
                                <td class="p-0">
                                    @if (cell.empty) {
                                        <span class="sr-only">{{ cell.aria }}</span>
                                    } @else {
                                        <button
                                            type="button"
                                            class="block w-full rounded px-1 text-center font-medium focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                                            [class]="cell.paint.classes + ' ' + cellSize()"
                                            [style.background]="cell.paint.background"
                                            [attr.title]="cell.title"
                                            [attr.aria-label]="cell.aria"
                                            [attr.data-testid]="'heat-cell'"
                                            (click)="cellClick.emit({ row: r.raw, column: cell.column })"
                                        >
                                            <span [class.sr-only]="compact()">{{ cell.text }}</span>
                                        </button>
                                    }
                                </td>
                            }
                        </tr>
                    }
                </tbody>
            </table>
        </div>
        <div class="text-secondary flex flex-wrap items-center gap-2 text-xs" data-testid="heat-legend">
            @switch (scale()) {
                @case ('status') {
                    @for (w of statusWords(); track w) {
                        <inspecto-status-badge [value]="w" variant="dot" />
                    }
                    @if (targetText(); as t) {
                        <span>{{ t }}</span>
                    }
                }
                @default {
                    @if (legend(); as l) {
                        <span class="tabular-nums">{{ l.low }}</span>
                        <span aria-hidden="true" class="h-2 w-24 rounded-full" [style.background]="l.gradient"></span>
                        <span class="tabular-nums">{{ l.high }}</span>
                        @if (l.mid) {
                            <span>· midpoint {{ l.mid }}</span>
                        }
                    }
                }
            }
        </div>
    `,
})
export class HeatmapComponent {
    readonly data = input.required<HeatmapData>();
    /** `options.heatmap` — the scale and (diverging) its midpoint. */
    readonly options = input<HeatmapOptions | undefined>(undefined);
    /** `options.format` — how each value is printed. */
    readonly format = input<NumberFormat | undefined>(undefined);
    /** `options.kpi` — under the `status` scale a numeric cell is judged against it. */
    readonly target = input<number | undefined>(undefined);
    readonly better = input<Better>('higher');

    /** The drill-down seam: the clicked cell's raw row + column values. */
    readonly cellClick = output<HeatmapCellClick>();

    readonly scale = computed(() => this.options()?.scale ?? 'sequential');
    readonly compact = computed(() => this.data().columns.length >= COMPACT_FROM);
    /** A swatch when compact, else room for the printed value. */
    readonly cellSize = computed(() => (this.compact() ? 'h-7 min-w-7' : 'min-w-14 py-1.5'));
    private readonly domain = computed(() => heatDomain(this.data()));

    private readonly rowText = computed(() => shownLabels(this.data().rows));
    private readonly columnText = computed(() => shownLabels(this.data().columns));

    readonly columnHeaders = computed(() =>
        this.data().columns.map((c, i) => ({ text: this.columnText()[i], title: fullDateLabel(c) ?? blank(c) })),
    );

    readonly grid = computed(() => {
        const d = this.data();
        const scale = this.scale();
        const domain = this.domain();
        const paintOpts = { midpoint: this.options()?.midpoint, target: this.target(), better: this.better() };
        return d.rows.map((raw, r) => {
            const rowShown = this.rowText()[r];
            return {
                raw,
                text: rowShown,
                title: fullDateLabel(raw) ?? blank(raw),
                cells: d.columns.map((column, c): Cell => {
                    const v = d.cells[r]?.[c] ?? null;
                    const colShown = this.columnText()[c];
                    const where = `${d.rowLabel} ${rowShown}, ${d.columnLabel} ${colShown}`;
                    if (v == null)
                        return {
                            column,
                            empty: true,
                            text: '',
                            title: '',
                            aria: `No value — ${where}`,
                            paint: cellPaint(null, scale, domain),
                        };
                    const text = this.valueText(v);
                    return {
                        column,
                        empty: false,
                        text,
                        title: `${rowShown} · ${colShown}: ${text}`,
                        aria: `${text} — ${where}`,
                        paint: cellPaint(v, scale, domain, paintOpts),
                    };
                }),
            };
        });
    });

    /** The status words present, in a stable order — the `status` scale's legend. */
    readonly statusWords = computed(() => {
        const words = new Set<string>();
        for (const row of this.data().cells) for (const v of row) if (typeof v === 'string') words.add(v);
        return [...words].sort();
    });

    readonly targetText = computed(() => {
        const t = this.target();
        if (t == null || !Number.isFinite(t) || !this.domain()) return null;
        return `Target ${formatNumber(t, this.format())} — ${this.better() === 'lower' ? 'at or below' : 'at or above'} is on target`;
    });

    readonly legend = computed(() => {
        const d = this.domain();
        if (!d) return null;
        const fmt = (n: number): string => formatNumber(n, this.format());
        if (this.scale() === 'diverging') {
            const mid = this.options()?.midpoint ?? 0;
            return {
                low: fmt(d.min),
                high: fmt(d.max),
                mid: fmt(mid),
                gradient: `linear-gradient(to right, ${rampColor('warn', 1)}, transparent, ${rampColor('primary', 1)})`,
            };
        }
        return {
            low: fmt(d.min),
            high: fmt(d.max),
            mid: null,
            gradient: `linear-gradient(to right, ${rampColor('primary', 0)}, ${rampColor('primary', 1)})`,
        };
    });

    /** The scroll region's name — short; the table's caption carries the full summary. */
    readonly regionLabel = computed(() => `Heatmap of ${this.data().valueLabel}`);

    /** The text alternative: what is plotted, its size, and its range (or, for status words, how many of each). */
    readonly summary = computed(() => {
        const d = this.data();
        const head = `Heatmap of ${d.valueLabel} by ${d.rowLabel} and ${d.columnLabel}: ${d.rows.length} rows by ${d.columns.length} columns`;
        const dom = this.domain();
        const counts = new Map<string, number>();
        for (const row of d.cells)
            for (const v of row) if (typeof v === 'string') counts.set(v, (counts.get(v) ?? 0) + 1);
        const parts: string[] = [];
        if (dom)
            parts.push(
                `values from ${formatNumber(dom.min, this.format())} to ${formatNumber(dom.max, this.format())}`,
            );
        if (counts.size)
            parts.push(
                [...counts]
                    .sort(([a], [b]) => a.localeCompare(b))
                    .map(([w, n]) => `${n} ${w}`)
                    .join(', '),
            );
        return parts.length ? `${head}; ${parts.join('; ')}.` : `${head}.`;
    });

    private valueText(v: Exclude<HeatmapValue, null>): string {
        return typeof v === 'number' ? formatNumber(v, this.format()) : v;
    }
}

/** A blank label reads "(blank)", never an empty header. */
function blank(label: string): string {
    return label.trim() === '' ? '(blank)' : label;
}

/** Header text: ISO dates read like a calendar (`date-labels.ts`), anything else as written. */
function shownLabels(labels: readonly string[]): string[] {
    return (dateAxisLabels(labels) ?? labels).map(blank);
}
