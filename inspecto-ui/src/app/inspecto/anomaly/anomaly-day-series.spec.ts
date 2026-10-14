import { describe, expect, it } from 'vitest';
import { AnomalyDaySeries } from 'app/inspecto/api/anomaly-models.service';
import { dayChartData, dayChartLabel, dayChartOptions, flatAxis } from './anomaly-day-series';

const SERIES: AnomalyDaySeries = {
    points: [
        { day: '2026-09-28', value: 100 },
        { day: '2026-09-29', value: null },
        { day: '2026-09-30', value: 3000 },
    ],
    median: 100,
    low: 40,
    high: 160,
    bandSpreads: 3,
};

describe('anomaly day series', () => {
    it('charts observed against the median and the band, one label per day, a gap where a day has no value', () => {
        const d = dayChartData(SERIES)!;
        expect(d.labels).toEqual(['2026-09-28', '2026-09-29', '2026-09-30']);
        const [observed, median, high, low] = d.datasets;
        expect(observed.data).toEqual([100, null, 3000]);
        expect(median.data).toEqual([100, 100, 100]);
        expect(high.data).toEqual([160, 160, 160]);
        expect(low.data).toEqual([40, 40, 40]);
    });

    it('draws only the observed line while the baseline is insufficient', () => {
        const d = dayChartData({ ...SERIES, median: null, low: null, high: null })!;
        expect(d.datasets).toHaveLength(1);
    });

    it('has nothing to plot without a value', () => {
        expect(dayChartData(undefined)).toBeNull();
        expect(dayChartData({ ...SERIES, points: [{ day: '2026-09-30', value: null }] })).toBeNull();
    });

    it('words the scored day against the usual range for the text alternative', () => {
        const t = dayChartLabel('Data (MB)', SERIES);
        expect(t).toContain('3 days to 2026-09-30');
        expect(t).toContain('scored day 3,000');
        expect(t).toContain('usual range 40 to 160');
        expect(dayChartLabel('x', { ...SERIES, median: null })).toContain('no usual range yet');
    });

    it('pads the axis around a constant series, and leaves a varying one to the data', () => {
        const flat: AnomalyDaySeries = {
            points: [
                { day: '2026-09-29', value: 5 },
                { day: '2026-09-30', value: 5 },
            ],
            median: 5,
            low: 5,
            high: 5,
            bandSpreads: 3,
        };
        expect(flatAxis(flat)).toEqual({ min: 4, max: 6 });
        expect(
            flatAxis({
                ...flat,
                median: 500,
                low: 500,
                high: 500,
                points: flat.points.map((p) => ({ ...p, value: 500 })),
            }),
        ).toEqual({ min: 450, max: 550 });
        expect(flatAxis(SERIES)).toBeNull();
        expect((dayChartOptions(flat).scales as { y: { min: number } }).y.min).toBe(4);
        expect((dayChartOptions(SERIES).scales as { y: { min?: number } }).y.min).toBeUndefined();
    });
});
