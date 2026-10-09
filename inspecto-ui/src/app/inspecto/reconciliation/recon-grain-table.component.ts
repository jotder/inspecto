import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatTooltipModule } from '@angular/material/tooltip';
import { statusEdgeClasses, statusIconClasses, statusRowClasses } from 'app/inspecto/components/status-badge.component';
import { humanizeColumn } from 'app/inspecto/viz/column-label';
import {
    BAND_ICON,
    BAND_LABEL,
    bandStatusTone,
    comparedSides,
    fmtMeasure,
    fmtPct,
    measureLabel,
    RECON_PAGE_SIZES,
    ReconBand,
    ReconRunResult,
    rowBand,
    SideKey,
} from './recon-board';
import { DEFAULT_BANDS, ReconBands } from './reconciliation-types';

interface Cell {
    text: string;
    numeric: boolean;
}

interface GrainRowView {
    id: string;
    band: ReconBand;
    label: string;
    icon: string;
    rowClass: string;
    edgeClass: string;
    iconClass: string;
    keys: string[];
    keyMap: Record<string, string>;
    cells: Cell[];
}

/**
 * ONE page of a Reconciliation's grain rows (RECON-PERF-1, operator 2026-10-09) — the Board's and the dashboard
 * widget's table. Server-paged: the host owns the request and feeds each page in; `(pageChange)` asks for another.
 *
 * Each row is TINTED by its band (ok / warn / breach, and a key missing on a side) with the design-system status
 * tones, and — because colour must not be the only cue (WCAG 1.4.1) — also carries a thin coloured left edge, a
 * shape icon whose tooltip names the band, and the band name as visually-hidden text. There is deliberately NO
 * status/RAG text column (operator, 2026-10-09).
 */
@Component({
    selector: 'inspecto-recon-grain-table',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatPaginatorModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="overflow-x-auto">
            <table class="w-full border-collapse text-sm" [attr.aria-label]="caption()">
                <thead>
                    <tr class="text-secondary border-b text-left text-xs">
                        @for (h of headers(); track $index) {
                            <th
                                scope="col"
                                class="px-2 py-1.5 font-semibold"
                                [class.text-right]="h.numeric"
                                [title]="h.tooltip"
                            >
                                {{ h.label }}
                            </th>
                        }
                        @if (openable()) {
                            <th scope="col" class="px-2 py-1.5"><span class="sr-only">Actions</span></th>
                        }
                    </tr>
                </thead>
                <tbody>
                    @for (row of rows(); track row.id) {
                        <tr class="border-b {{ row.rowClass }}" [attr.data-band]="row.band">
                            <td class="px-2 py-1 {{ row.edgeClass }}">
                                <span class="inline-flex items-center gap-1.5">
                                    <mat-icon
                                        class="icon-size-4 {{ row.iconClass }}"
                                        [svgIcon]="row.icon"
                                        [matTooltip]="row.label"
                                        aria-hidden="true"
                                    ></mat-icon>
                                    <span class="sr-only">{{ row.label }}: </span>
                                    <span>{{ row.keys[0] }}</span>
                                </span>
                            </td>
                            @for (k of row.keys.slice(1); track $index) {
                                <td class="px-2 py-1">{{ k }}</td>
                            }
                            @for (cell of row.cells; track $index) {
                                <td class="px-2 py-1 font-mono tabular-nums" [class.text-right]="cell.numeric">
                                    {{ cell.text }}
                                </td>
                            }
                            @if (openable()) {
                                <td class="px-1 py-0.5 text-right">
                                    <button
                                        mat-icon-button
                                        type="button"
                                        [attr.aria-label]="'View breaks for ' + row.keys.join(' · ')"
                                        matTooltip="View breaks for this key"
                                        (click)="keyOpen.emit(row.keyMap)"
                                    >
                                        <mat-icon svgIcon="heroicons_outline:magnifying-glass"></mat-icon>
                                    </button>
                                </td>
                            }
                        </tr>
                    }
                </tbody>
            </table>
        </div>
        @if (result().page; as p) {
            <mat-paginator
                [length]="p.total"
                [pageSize]="p.limit"
                [pageIndex]="pageIndex()"
                [pageSizeOptions]="pageSizes"
                (page)="onPage($event)"
                aria-label="Reconciliation rows pages"
            />
        }
    `,
})
export class ReconGrainTableComponent {
    readonly result = input.required<ReconRunResult>();
    readonly bands = input<ReconBands | undefined>(undefined);
    /** Readable side names (`a`/`b`/`c` → label); the side letter when absent. */
    readonly sideLabels = input<Partial<Record<SideKey, string>>>({});
    /** Show each side's values beside the Δ% (the Board), or the Δ% alone (the compact widget). */
    readonly includeValues = input(true);
    /** Offer a per-row "view breaks for this key" action. */
    readonly openable = input(false);

    readonly pageChange = output<{ offset: number; limit: number }>();
    readonly keyOpen = output<Record<string, string>>();

    readonly pageSizes = [...RECON_PAGE_SIZES];

    readonly pageIndex = computed(() => {
        const p = this.result().page;
        return p && p.limit ? Math.floor(p.offset / p.limit) : 0;
    });

    readonly caption = computed(() => {
        const r = this.result();
        return `Reconciliation rows${r.day ? ' for ' + r.day : ''}`;
    });

    private label(s: SideKey): string {
        return this.sideLabels()[s] || s.toUpperCase();
    }

    readonly headers = computed(() => {
        const r = this.result();
        const sides = comparedSides(r);
        const multi = sides.length > 1;
        const out: { label: string; tooltip: string; numeric: boolean }[] = r.keyColumns.map((k) => ({
            label: humanizeColumn(k),
            tooltip: k,
            numeric: false,
        }));
        for (const m of r.measures) {
            const measure = measureLabel(m);
            if (this.includeValues())
                out.push({ label: `${this.label('a')} · ${measure}`, tooltip: `A · ${m}`, numeric: true });
            for (const s of sides) {
                if (this.includeValues())
                    out.push({
                        label: `${this.label(s)} · ${measure}`,
                        tooltip: `${s.toUpperCase()} · ${m}`,
                        numeric: true,
                    });
                out.push({
                    label: multi ? `Δ% ${this.label(s)} · ${measure}` : `Δ% ${measure}`,
                    tooltip: `Δ% of ${s.toUpperCase()} vs A · ${m}`,
                    numeric: true,
                });
            }
        }
        return out;
    });

    readonly rows = computed<GrainRowView[]>(() => {
        const r = this.result();
        const bands = this.bands() ?? DEFAULT_BANDS;
        const sides = comparedSides(r);
        return r.rows.map((row, i) => {
            const band = rowBand(row, r, bands);
            const tone = bandStatusTone(band);
            const keyMap: Record<string, string> = {};
            const keys = r.keyColumns.map((k) => {
                const v = row.key[k];
                keyMap[k] = v === null || v === undefined ? '' : String(v);
                return v === null || v === undefined ? '∅' : String(v);
            });
            const cells: Cell[] = [];
            for (const m of r.measures) {
                if (this.includeValues()) cells.push({ text: fmtMeasure(row.a?.[m]), numeric: true });
                for (const s of sides) {
                    if (this.includeValues()) cells.push({ text: fmtMeasure(row[s]?.[m]), numeric: true });
                    cells.push({ text: fmtPct(row.a?.[m], row[s]?.[m]), numeric: true });
                }
            }
            return {
                id: `${i}:${keys.join('|')}`,
                band,
                label: BAND_LABEL[band],
                icon: BAND_ICON[band],
                rowClass: statusRowClasses(tone),
                edgeClass: statusEdgeClasses(tone),
                iconClass: statusIconClasses(tone),
                keys,
                keyMap,
                cells,
            };
        });
    });

    onPage(e: PageEvent): void {
        this.pageChange.emit({ offset: e.pageIndex * e.pageSize, limit: e.pageSize });
    }
}
