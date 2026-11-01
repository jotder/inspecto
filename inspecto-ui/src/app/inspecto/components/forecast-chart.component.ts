import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ForecastPoint } from '../api/predictions.service';
import { forecastChartData, forecastChartLabel, forecastChartOptions, okPoints } from '../predictive/forecast-series';
import { InspectoChartComponent } from './chart.component';

/**
 * The **forecast chart** (PREDICTIVE-SURFACE-1): one series' latest forecast with its stored interval as a band, and
 * the series' backtest error underneath (every point of a run carries the same). An `insufficient` series shows the
 * chart's empty state, never a line of zeros.
 */
@Component({
    selector: 'inspecto-forecast-chart',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [DecimalPipe, InspectoChartComponent],
    template: `
        <inspecto-chart
            type="line"
            [data]="data()"
            [options]="options"
            [ariaLabel]="label()"
            emptyMessage="Not enough history to forecast this series yet."
        />
        @if (backtest(); as b) {
            <p class="text-secondary mt-1 text-xs tabular-nums">
                Backtest: mean absolute error {{ b.backtestMae | number: '1.0-2' }}, sMAPE
                {{ b.backtestSmape | number: '1.0-1' }} %, relative to seasonal-naive
                {{ b.backtestRelMae | number: '1.0-2' }}
            </p>
        }
    `,
})
export class ForecastChartComponent {
    readonly model = input.required<string>();
    readonly points = input<ForecastPoint[]>([]);

    readonly options = forecastChartOptions();
    readonly data = computed(() => forecastChartData(this.points()));
    readonly label = computed(() => forecastChartLabel(this.model(), this.points()));
    readonly backtest = computed(() => okPoints(this.points())[0] ?? null);
}
