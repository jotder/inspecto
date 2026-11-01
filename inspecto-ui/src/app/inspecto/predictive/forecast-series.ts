import { ChartData, ChartOptions } from 'chart.js';
import { ForecastPoint } from 'app/inspecto/api/predictions.service';
import { CHART_CATEGORICAL, CHART_SERIES } from 'app/inspecto/theme/chart-tokens';
import { formatNumber } from 'app/inspecto/viz/number-format';

/**
 * The forecast chart (PREDICTIVE-SURFACE-1): per future bucket the point forecast with its stored interval as a band,
 * the same shape as the anomaly day chart. Framework-free apart from the Chart.js data TYPE.
 */

/** The muted slate of the categorical ramp: the interval recedes behind the forecast line. */
const REFERENCE = CHART_CATEGORICAL[7];
const LOWER = 'Lower bound';

const finite = (v: number | null | undefined): v is number => v != null && Number.isFinite(v);

/** The plottable points (status ok, a finite forecast), in step order. */
export function okPoints(points: ForecastPoint[] | undefined): ForecastPoint[] {
    return (points ?? []).filter((p) => p.status === 'ok' && finite(p.forecast));
}

/** Chart.js data for a forecast, or `null` when there is nothing to plot (insufficient or empty). */
export function forecastChartData(points: ForecastPoint[] | undefined): ChartData<'line'> | null {
    const ok = okPoints(points);
    if (!ok.length) return null;
    const day = (b: string | null) => (b ?? '').slice(0, 10);
    return {
        labels: ok.map((p) => day(p.bucket)),
        datasets: [
            {
                label: 'Forecast',
                data: ok.map((p) => p.forecast),
                borderColor: CHART_SERIES.primary,
                backgroundColor: CHART_SERIES.primary,
                pointRadius: 2,
                order: 0,
            },
            {
                label: 'Interval',
                data: ok.map((p) => (finite(p.upper) ? p.upper : null)),
                borderColor: REFERENCE,
                backgroundColor: REFERENCE + '33',
                borderWidth: 1,
                pointRadius: 0,
                fill: '+1',
                order: 1,
            },
            {
                label: LOWER,
                data: ok.map((p) => (finite(p.lower) ? p.lower : null)),
                borderColor: REFERENCE,
                borderWidth: 1,
                pointRadius: 0,
                order: 1,
            },
        ],
    };
}

/** Chart options: legend on, the lower edge left out of it (it only closes the fill). */
export function forecastChartOptions(): ChartOptions<'line'> {
    return {
        plugins: { legend: { display: true, labels: { filter: (item) => item.text !== LOWER } } },
        scales: { y: { ticks: { callback: (v) => formatNumber(Number(v), { compact: true }) } } },
    } as ChartOptions<'line'>;
}

/** The chart's text alternative: the span, first and last forecast with their interval. */
export function forecastChartLabel(model: string, points: ForecastPoint[] | undefined): string {
    const ok = okPoints(points);
    if (!ok.length) return `${model}: not enough history to forecast`;
    const at = (p: ForecastPoint) =>
        `${formatNumber(p.forecast!)} (${finite(p.lower) ? formatNumber(p.lower) : '—'} to ${
            finite(p.upper) ? formatNumber(p.upper) : '—'
        })`;
    const first = ok[0];
    const last = ok[ok.length - 1];
    return `${model}, ${ok.length} buckets ahead: ${at(first)} on ${first.bucket?.slice(0, 10)}, ${at(last)} on ${last.bucket?.slice(0, 10)}`;
}
