import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChartData } from 'chart.js';
import { Insight, InsightPreview } from 'app/inspecto/api/insights.service';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { bandBadge, sliceLabel } from 'app/inspecto/insights/insight-profile-form';
import { formatNumber } from 'app/inspecto/viz/number-format';
import { seriesColors } from 'app/inspecto/viz/series-colors';

/** The result of a replay (`POST /insights/preview`): Insights per period as bars, the total and the top Insights. */
@Component({
    selector: 'app-insight-preview',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoChartComponent, StatusBadgeComponent],
    template: `
        @let p = preview();
        <p class="mt-3 text-sm font-semibold" role="status" data-testid="preview-summary">{{ summary() }}</p>
        <div class="mt-2 h-48">
            <inspecto-chart type="bar" [data]="chart()" [ariaLabel]="chartLabel()" emptyMessage="No period to show." />
        </div>
        @if (p.skipped?.length) {
            <ul class="text-secondary mt-2 text-xs">
                @for (s of p.skipped; track s.dimension) {
                    <li>Skipped {{ s.dimension }}: {{ s.reason }}</li>
                }
            </ul>
        }
        @if (p.insights.length) {
            <h4 class="mt-3 text-sm font-semibold">Top Insights</h4>
            <ul class="mt-1 flex flex-col gap-2">
                @for (i of top(); track i.insight_id + (i.asOf ?? '')) {
                    <li class="text-sm">
                        <inspecto-status-badge [value]="band(i).tone" [label]="band(i).label" />
                        <span class="ml-2 font-semibold">{{ slice(i) }}</span>
                        <span class="text-secondary text-xs">
                            · {{ i.insight_type }} · {{ i.period }} · score {{ i.score }}</span
                        >
                        <p>{{ i.explanation?.sentence }}</p>
                    </li>
                }
            </ul>
        }
    `,
})
export class InsightPreviewComponent {
    readonly preview = input.required<InsightPreview>();
    readonly band = (i: Insight) => bandBadge(i.band);
    readonly slice = sliceLabel;

    readonly summary = computed(() => {
        const p = this.preview();
        const avg = formatNumber(p.perPeriodAverage, { decimals: 1 });
        return `You would have seen ${formatNumber(p.total)} Insight${p.total === 1 ? '' : 's'} in the last ${p.periods} periods (${avg} per period).`;
    });

    readonly top = computed(() => this.preview().insights.slice(0, 10));

    readonly chart = computed<ChartData>(() => {
        const rows = this.preview().perPeriod;
        return {
            labels: rows.map((r) => r.period),
            datasets: [
                { label: 'Insights', data: rows.map((r) => r.count), backgroundColor: seriesColors(['Insights'])[0] },
            ],
        };
    });

    readonly chartLabel = computed(
        () =>
            'Insights per period: ' +
            this.preview()
                .perPeriod.map((r) => `${r.period} ${r.count}`)
                .join(', '),
    );
}
