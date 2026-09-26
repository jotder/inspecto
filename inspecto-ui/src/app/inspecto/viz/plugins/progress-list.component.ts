import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CHART_TONE } from 'app/inspecto/theme/chart-tokens';
import { formatNumber, NumberFormat } from '../number-format';
import { Better } from '../target-status';
import { SortDir } from '../viz-types';
import { ProgressRow, rankProgress } from './progress-list.plugin';

/**
 * Progress list — the `progress-list` plugin's component escape hatch: an ordered list of labelled bars, each with
 * its value (in the widget's {@link NumberFormat}) at the right and a bar sized as a share of the largest value (or
 * `options.progress.max`). With `options.kpi.target` every bar carries a target tick and is toned good / bad by
 * `better`; the tick's position and each row's screen-reader text ("on target" / "below target") carry the same
 * meaning, so colour is never the only signal. A "+N more" footer says what the top-N trim left out.
 *
 * Given `select` (the render host's drill-down), each row is a button that emits its label. Bar tones come from
 * `CHART_TONE`, the chart palette's status colours, so a list agrees with the Gauge and the charts beside it.
 */
@Component({
    selector: 'inspecto-progress-list',
    standalone: true,
    imports: [NgTemplateOutlet],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex min-w-0 flex-col">
            <ol class="flex flex-col gap-1" [attr.aria-label]="listLabel()" data-testid="progress-list">
                @for (r of model().rows; track $index) {
                    <li class="min-w-0">
                        @if (select(); as emit) {
                            <button
                                type="button"
                                class="hover:bg-hover block w-full rounded px-1 py-1 text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2"
                                (click)="emit(r.label)"
                            >
                                <ng-container *ngTemplateOutlet="row; context: { $implicit: r }" />
                            </button>
                        } @else {
                            <div class="px-1 py-1">
                                <ng-container *ngTemplateOutlet="row; context: { $implicit: r }" />
                            </div>
                        }
                    </li>
                }
            </ol>
            @if (footer(); as f) {
                <p class="text-secondary mt-2 px-1 text-xs" data-testid="progress-footer">{{ f }}</p>
            }
        </div>

        <ng-template #row let-r>
            <span class="flex min-w-0 items-baseline justify-between gap-2 text-sm">
                <span class="truncate" [title]="shown(r.label)">{{ shown(r.label) }}</span>
                <span class="shrink-0 font-semibold tabular-nums" data-testid="progress-value">{{ fmt(r.value) }}</span>
            </span>
            <span class="bg-hover relative mt-1 block h-2 rounded-full" aria-hidden="true">
                <span
                    class="block h-full rounded-full"
                    [class.bg-primary]="r.met == null"
                    [style.width.%]="r.pct"
                    [style.background]="toneColor(r)"
                    data-testid="progress-bar"
                ></span>
                @if (r.targetPct != null) {
                    <span
                        class="progress-target absolute -top-0.5 block h-3 w-0.5 -translate-x-1/2 rounded"
                        [style.left.%]="r.targetPct"
                        data-testid="progress-target"
                    ></span>
                }
            </span>
            <span class="sr-only">{{ rowNote(r) }}</span>
        </ng-template>
    `,
    styles: [
        `
            .progress-target {
                background: var(--gamma-text-default);
            }
        `,
    ],
})
export class ProgressListComponent {
    readonly labels = input<string[]>([]);
    readonly values = input<number[]>([]);
    readonly format = input<NumberFormat | undefined>(undefined);
    readonly target = input<number | undefined>(undefined);
    readonly better = input<Better>('higher');
    /** Rows shown before "+N more" (`options.progress.limit`, else `options.limit`, else 10). */
    readonly limit = input<number | undefined>(undefined);
    readonly sort = input<SortDir | undefined>(undefined);
    /** The value a full bar stands for (`options.progress.max`); default the largest value shown. */
    readonly max = input<number | undefined>(undefined);
    /** The drill-down: called with a row's label when it is clicked. Absent ⇒ rows are not interactive. */
    readonly select = input<((label: string) => void) | undefined>(undefined);

    readonly model = computed(() =>
        rankProgress(this.labels(), this.values(), {
            limit: this.limit(),
            sort: this.sort(),
            max: this.max(),
            target: this.target(),
            better: this.better(),
        }),
    );

    readonly listLabel = computed(() => {
        const m = this.model();
        const order = this.sort() === 'asc' ? 'smallest' : 'largest';
        return `${m.rows.length} of ${m.rows.length + m.more}, ${order} first`;
    });

    /** "Target 95.0 % — 7 of 10 on target · +4 more": the target in words, and what the trim left out. */
    readonly footer = computed(() => {
        const m = this.model();
        const parts: string[] = [];
        const t = this.target();
        if (t != null && Number.isFinite(t)) {
            const met = m.rows.filter((r) => r.met).length;
            parts.push(`Target ${this.fmt(t)} — ${met} of ${m.rows.length} on target`);
        }
        if (m.more > 0) parts.push(`+${m.more} more`);
        return parts.join(' · ');
    });

    fmt(n: number): string {
        return formatNumber(n, this.format());
    }

    shown(label: string): string {
        return label.trim() === '' ? '(blank)' : label;
    }

    /** A toned bar's fill; `null` leaves the neutral `bg-primary`. */
    toneColor(r: ProgressRow): string | null {
        return r.met == null ? null : r.met ? CHART_TONE.success : CHART_TONE.error;
    }

    /**
     * What the bar shows, for a screen reader, and where it stands against the target. The bar is relative — to
     * `options.progress.max` when set, else to the largest item — never a share of the total, so the words say which.
     */
    rowNote(r: ProgressRow): string {
        const m = this.model();
        const share = `, ${Math.round(r.pct)} % of ${m.maxSet ? this.fmt(m.max) : 'the largest item'}`;
        if (r.met == null) return share;
        const where = r.met ? 'on target' : this.better() === 'higher' ? 'below target' : 'above target';
        return `${share}, ${where}`;
    }
}
