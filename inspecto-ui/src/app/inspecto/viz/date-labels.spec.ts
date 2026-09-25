import { describe, expect, it } from 'vitest';
import { dateAxisLabels, fullDateLabel } from './date-labels';

describe('dateAxisLabels', () => {
    it('reads a month grain (every date on the 1st) as month + year', () => {
        expect(dateAxisLabels(['2025-10-01', '2025-11-01', '2025-12-01', '2026-01-01'])).toEqual([
            'Oct 2025',
            'Nov 2025',
            'Dec 2025',
            'Jan 2026',
        ]);
    });

    it('treats the offline YYYY-MM month bucket as a month', () => {
        expect(dateAxisLabels(['2025-10', '2025-11'])).toEqual(['Oct 2025', 'Nov 2025']);
    });

    it('reads daily dates as day + month, without the year inside one year', () => {
        expect(dateAxisLabels(['2025-09-01', '2025-09-21', '2025-09-22'])).toEqual(['1 Sep', '21 Sep', '22 Sep']);
    });

    it('adds the year when the dates span more than one year', () => {
        expect(dateAxisLabels(['2025-12-30', '2025-12-31', '2026-01-02'])).toEqual([
            '30 Dec 2025',
            '31 Dec 2025',
            '2 Jan 2026',
        ]);
    });

    it('accepts a time part: midnight stays a date, a clock time is shown', () => {
        expect(dateAxisLabels(['2025-10-01T00:00:00', '2025-11-01 00:00:00.000'])).toEqual(['Oct 2025', 'Nov 2025']);
        expect(dateAxisLabels(['2025-09-21T13:00:00Z', '2025-09-21T14:30:00'])).toEqual([
            '21 Sep 13:00',
            '21 Sep 14:30',
        ]);
    });

    it('keeps blank categories blank and still formats the dates around them', () => {
        expect(dateAxisLabels(['2025-09-21', '', '2025-09-22'])).toEqual(['21 Sep', '', '22 Sep']);
    });

    it('leaves a mixed or non-date axis unchanged', () => {
        expect(dateAxisLabels(['2025-10-01', 'North'])).toBeNull();
        expect(dateAxisLabels(['North', 'South'])).toBeNull();
        expect(dateAxisLabels(['2025', '2026'])).toBeNull(); // a bare year is a number, not a date
        expect(dateAxisLabels(['1759276800000'])).toBeNull(); // an epoch number is not recognised
        expect(dateAxisLabels(['2025-02-31'])).toBeNull(); // impossible date
        expect(dateAxisLabels([])).toBeNull();
        expect(dateAxisLabels([''])).toBeNull();
    });
});

describe('fullDateLabel', () => {
    it('spells a date in full for a tooltip', () => {
        expect(fullDateLabel('2025-10-01')).toBe('1 Oct 2025');
        expect(fullDateLabel('2025-09-21T13:05:00')).toBe('21 Sep 2025 13:05');
        expect(fullDateLabel('2025-10')).toBe('Oct 2025');
        expect(fullDateLabel('North')).toBeNull();
    });
});
