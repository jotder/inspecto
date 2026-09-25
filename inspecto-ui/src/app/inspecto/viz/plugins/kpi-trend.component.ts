import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { dateAxisLabels } from '../date-labels';
import { kpiDelta, toneBadge } from '../kpi-delta';
import { formatNumber, NumberFormat } from '../number-format';
import { Better, targetStatus } from '../target-status';
import { sparkline, trendSummary } from './kpi-trend.plugin';

/** The sparkline's viewBox; the SVG stretches to the tile's width (`preserveAspectRatio="none"`). */
const W = 100;
const H = 32;

/**
 * KPI trend tile — the `kpi-trend` plugin's component escape hatch. The widget card carries the title; the tile
 * carries the latest value (in the widget's {@link NumberFormat}), its change against an earlier point in the KPI
 * tile's wording ({@link kpiDelta}), the target stated in words, and a sparkline of the whole series.
 *
 * The sparkline is plain SVG coloured by `--gamma-*` tokens, so it follows the light / dark scheme without a
 * redraw. Strokes are `non-scaling-stroke`: the box stretches to the tile width but lines stay 1.5 px, and the last
 * point — a zero-length round-capped line — stays a round dot. It is an `img` whose label states the range and the
 * latest point, so the trend is available as text.
 */
@Component({
    selector: 'inspecto-kpi-trend',
    standalone: true,
    imports: [StatusBadgeComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex h-full min-w-0 flex-col justify-between gap-1">
            <div class="flex min-w-0 items-baseline gap-2">
                <div
                    class="whitespace-nowrap text-3xl font-extrabold tabular-nums leading-none"
                    data-testid="kpi-trend-value"
                >
                    {{ display() }}
                </div>
                @if (lastLabel(); as l) {
                    <div class="text-secondary truncate text-xs" data-testid="kpi-trend-as-of">{{ l }}</div>
                }
            </div>
            @if (delta() || targetLine()) {
                <div class="flex flex-wrap gap-1">
                    @if (delta(); as d) {
                        <inspecto-status-badge data-testid="kpi-trend-delta" [value]="badge(d.tone)" [label]="d.text" />
                    }
                    @if (targetLine(); as t) {
                        <inspecto-status-badge
                            data-testid="kpi-trend-target"
                            [value]="t.met ? 'PASS' : 'FAIL'"
                            [label]="t.text"
                        />
                    }
                </div>
            }
            @if (spark(); as s) {
                <svg
                    class="block h-10 w-full overflow-visible"
                    [attr.viewBox]="viewBox"
                    preserveAspectRatio="none"
                    role="img"
                    [attr.aria-label]="summary()"
                    data-testid="kpi-trend-spark"
                >
                    <path [attr.d]="s.area" class="spark-area" />
                    @if (s.targetY != null) {
                        <line
                            class="spark-target"
                            x1="0"
                            [attr.x2]="width"
                            [attr.y1]="s.targetY"
                            [attr.y2]="s.targetY"
                            vector-effect="non-scaling-stroke"
                        />
                    }
                    <path [attr.d]="s.line" class="spark-line" vector-effect="non-scaling-stroke" />
                    <line
                        class="spark-dot"
                        [attr.x1]="s.last.x"
                        [attr.x2]="s.last.x"
                        [attr.y1]="s.last.y"
                        [attr.y2]="s.last.y"
                        vector-effect="non-scaling-stroke"
                    />
                </svg>
            }
        </div>
    `,
    styles: [
        `
            .spark-area {
                fill: var(--gamma-primary);
                fill-opacity: 0.12;
                stroke: none;
            }
            .spark-line {
                fill: none;
                stroke: var(--gamma-primary);
                stroke-width: 1.5px;
                stroke-linejoin: round;
                stroke-linecap: round;
            }
            .spark-target {
                stroke: var(--gamma-text-secondary);
                stroke-width: 1px;
                stroke-dasharray: 3 3;
            }
            .spark-dot {
                stroke: var(--gamma-primary);
                stroke-width: 6px;
                stroke-linecap: round;
            }
        `,
    ],
})
export class KpiTrendComponent {
    /** The ordered `x` values (oldest first) and the measure at each. */
    readonly labels = input<string[]>([]);
    readonly values = input<number[]>([]);
    readonly format = input<NumberFormat | undefined>(undefined);
    readonly target = input<number | undefined>(undefined);
    readonly better = input<Better>('higher');
    /** Compare the last point with the one this many steps earlier (`options.trend.compareBack`). */
    readonly compareBack = input<number | undefined>(undefined);

    readonly viewBox = `0 0 ${W} ${H}`;
    readonly width = W;

    readonly stats = computed(() => trendSummary(this.values(), this.compareBack() ?? 1));

    /** The labels as a reader sees them — ISO dates as `Sep 2026` / `21 Sep`. */
    private readonly shownLabels = computed(() => dateAxisLabels(this.labels()) ?? this.labels());

    readonly display = computed(() => formatNumber(this.stats()?.last ?? NaN, this.format()));

    readonly lastLabel = computed(() => {
        const s = this.stats();
        return s ? (this.shownLabels()[s.lastIndex] ?? '') : '';
    });

    /** "▲ Up 3.1 % (+12) vs Aug 2026" — the KPI tile's wording, against the compared point's label. */
    readonly delta = computed(() => {
        const s = this.stats();
        if (!s || s.prevIndex == null) return null;
        const vs = this.shownLabels()[s.prevIndex] || 'previous point';
        return kpiDelta(s.last, s.prev, this.better(), this.format(), vs);
    });

    readonly targetLine = computed(() => {
        const s = this.stats();
        return s ? targetStatus(s.last, this.target(), this.better(), this.format()) : null;
    });

    readonly spark = computed(() => sparkline(this.values(), W, H, this.target()));

    /** The sparkline's text alternative: range and latest point, in the widget's format. */
    readonly summary = computed(() => {
        const s = this.stats();
        if (!s) return '';
        const f = (n: number): string => formatNumber(n, this.format());
        const labels = this.shownLabels();
        const span = labels.length > 1 ? ` from ${labels[0]} to ${labels[s.lastIndex]}` : '';
        return `Trend of ${this.values().length} points${span}: low ${f(s.min)}, high ${f(s.max)}, latest ${f(s.last)}`;
    });

    readonly badge = toneBadge;
}
