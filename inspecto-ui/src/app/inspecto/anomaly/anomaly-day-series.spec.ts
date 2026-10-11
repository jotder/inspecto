import { describe, expect, it } from 'vitest';
import { AnomalyDaySeries } from 'app/inspecto/api/anomaly-models.service';
import { dayChartData, dayChartLabel } from './anomaly-day-series';

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
});
