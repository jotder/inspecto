import { describe, expect, it } from 'vitest';
import { ForecastPoint } from 'app/inspecto/api/predictions.service';
import { forecastChartData, forecastChartLabel, forecastChartOptions, okPoints } from './forecast-series';

const P = (step: number, forecast: number, lower: number, upper: number): ForecastPoint => ({
    status: 'ok',
    step,
    bucket: `2026-07-0${step} 00:00:00`,
    forecast,
    lower,
    upper,
    backtestMae: 1.5,
});
const INSUFFICIENT: ForecastPoint = {
    status: 'insufficient',
    step: null,
    bucket: null,
    forecast: null,
    lower: null,
    upper: null,
};

describe('forecast series', () => {
    it('charts the forecast with the interval as a band, one label per bucket day', () => {
        const d = forecastChartData([P(1, 10, 8, 12), P(2, 11, 9, 13)])!;
        expect(d.labels).toEqual(['2026-07-01', '2026-07-02']);
        const [forecast, upper, lower] = d.datasets;
        expect(forecast.data).toEqual([10, 11]);
        expect(upper.data).toEqual([12, 13]);
        expect(lower.data).toEqual([8, 9]);
        expect((upper as { fill?: unknown }).fill).toBe('+1');
    });

    it('has nothing to plot for an insufficient series — never a line of zeros', () => {
        expect(forecastChartData([INSUFFICIENT])).toBeNull();
        expect(forecastChartData(undefined)).toBeNull();
        expect(okPoints([INSUFFICIENT])).toEqual([]);
        expect(forecastChartLabel('demand', [INSUFFICIENT])).toContain('not enough history');
    });

    it('describes the span in words and leaves the lower edge out of the legend', () => {
        const label = forecastChartLabel('demand', [P(1, 10, 8, 12), P(2, 11, 9, 13)]);
        expect(label).toContain('demand, 2 buckets ahead');
        expect(label).toContain('2026-07-02');
        const filter = (forecastChartOptions().plugins!.legend!.labels as { filter: (i: { text: string }) => boolean })
            .filter;
        expect(filter({ text: 'Lower bound' })).toBe(false);
        expect(filter({ text: 'Forecast' })).toBe(true);
    });
});
