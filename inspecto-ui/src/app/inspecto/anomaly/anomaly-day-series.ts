import { ChartData, ChartOptions } from 'chart.js';
import { AnomalyDaySeries } from 'app/inspecto/api/anomaly-models.service';
import { CHART_CATEGORICAL, CHART_SERIES } from 'app/inspecto/theme/chart-tokens';
import { formatNumber } from 'app/inspecto/viz/number-format';

/**
 * The preview's day-by-day chart (design §13.1): per day the observed value, with the baseline's median and its usual
 * band as reference lines. The band is one level for the scored day's slot (with weekday seasonality, the median of the
 * same weekday), so it is a reference, not a per-day forecast. Framework-free apart from the Chart.js data TYPE.
 */

/** The muted slate of the categorical ramp: reference lines recede behind the observed line. */
const REFERENCE = CHART_CATEGORICAL[7];

const finite = (v: number | null | undefined): v is number => v != null && Number.isFinite(v);

/** Chart.js data for one feature's series, or `null` when it has no point with a value (nothing to plot). */
export function dayChartData(series: AnomalyDaySeries | undefined): ChartData<'line'> | null {
    if (!series || !series.points.some((p) => finite(p.value))) return null;
    const n = series.points.length;
    const flat = (v: number | null): (number | null)[] => series.points.map(() => (finite(v) ? v : null));
    const hasBand = finite(series.median);
    const last = (colour: string): string[] => series.points.map((_, i) => (i === n - 1 ? CHART_SERIES.error : colour));
    return {
        labels: series.points.map((p) => p.day),
        datasets: [
            {
                label: 'Observed',
                data: series.points.map((p) => (finite(p.value) ? p.value : null)),
                borderColor: CHART_SERIES.primary,
                backgroundColor: CHART_SERIES.primary,
                pointBackgroundColor: last(CHART_SERIES.primary),
                pointBorderColor: last(CHART_SERIES.primary),
                pointRadius: series.points.map((_, i) => (i === n - 1 ? 5 : 2)),
                pointStyle: series.points.map((_, i) => (i === n - 1 ? 'rectRot' : 'circle')),
                spanGaps: false,
                order: 0,
            },
            ...(hasBand
                ? [
                      {
                          label: 'Usual (median)',
                          data: flat(series.median),
                          borderColor: REFERENCE,
                          backgroundColor: REFERENCE,
                          borderDash: [6, 4],
                          pointRadius: 0,
                          order: 1,
                      },
                      {
                          label: `Usual range (±${series.bandSpreads} spreads)`,
                          data: flat(series.high),
                          borderColor: REFERENCE,
                          backgroundColor: REFERENCE + '33',
                          borderWidth: 1,
                          pointRadius: 0,
                          fill: '+1',
                          order: 2,
                      },
                      {
                          label: 'Lower usual range',
                          data: flat(series.low),
                          borderColor: REFERENCE,
                          borderWidth: 1,
                          pointRadius: 0,
                          order: 2,
                      },
                  ]
                : []),
        ],
    };
}

/** Chart options: legend on, the lower band edge left out of it (it only closes the fill), y from the data. */
export function dayChartOptions(): ChartOptions<'line'> {
    return {
        plugins: {
            legend: {
                display: true,
                labels: { filter: (item) => item.text !== 'Lower usual range' },
            },
        },
        scales: { y: { ticks: { callback: (v) => formatNumber(Number(v), { compact: true }) } } },
    } as ChartOptions<'line'>;
}

/** The chart's text alternative: the scored value against the usual range, in words. */
export function dayChartLabel(label: string, series: AnomalyDaySeries): string {
    const pts = series.points;
    const scored = pts[pts.length - 1];
    const fmt = (v: number | null): string => (finite(v) ? formatNumber(v) : 'no value');
    const days = `${pts.length} days to ${scored?.day ?? 'the scored day'}`;
    return finite(series.median) && finite(series.low) && finite(series.high)
        ? `${label}, ${days}: scored day ${fmt(scored?.value ?? null)} against a usual median ${fmt(series.median)}, ` +
              `usual range ${fmt(series.low)} to ${fmt(series.high)}`
        : `${label}, ${days}: scored day ${fmt(scored?.value ?? null)}; no usual range yet`;
}
