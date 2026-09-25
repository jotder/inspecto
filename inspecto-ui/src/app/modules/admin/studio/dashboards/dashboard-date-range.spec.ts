import { describe, expect, it } from 'vitest';
import { asRangeSelection, isIsoDay, rangeCondition, resolveRange, spanLabel, todayIso } from './dashboard-date-range';

describe('dashboard date range (UIE-5 d)', () => {
    it('counts the last N days back from the anchor, both ends inclusive, across month and year ends', () => {
        expect(resolveRange('last-7-days', '2026-09-24')).toEqual({ from: '2026-09-18', to: '2026-09-24' });
        expect(resolveRange('last-30-days', '2026-09-24')).toEqual({ from: '2026-08-26', to: '2026-09-24' });
        expect(resolveRange('last-90-days', '2026-09-24')).toEqual({ from: '2026-06-27', to: '2026-09-24' });
        expect(resolveRange('last-7-days', '2026-01-03')).toEqual({ from: '2025-12-28', to: '2026-01-03' });
        expect(resolveRange('last-30-days', '2024-03-01')).toEqual({ from: '2024-02-01', to: '2024-03-01' });
    });

    it('month, quarter and year to date start on the first day of the anchor’s calendar period', () => {
        expect(resolveRange('month-to-date', '2026-09-24')).toEqual({ from: '2026-09-01', to: '2026-09-24' });
        expect(resolveRange('month-to-date', '2026-09-01')).toEqual({ from: '2026-09-01', to: '2026-09-01' });
        expect(resolveRange('year-to-date', '2026-09-24')).toEqual({ from: '2026-01-01', to: '2026-09-24' });
        expect(resolveRange('year-to-date', '2026-01-01')).toEqual({ from: '2026-01-01', to: '2026-01-01' });
        expect(resolveRange('year-to-date', '2026-12-31')).toEqual({ from: '2026-01-01', to: '2026-12-31' });
    });

    it('quarter to date is calendar-correct at every quarter boundary', () => {
        const qtd = (d: string) => resolveRange('quarter-to-date', d)!.from;
        expect(qtd('2026-01-01')).toBe('2026-01-01');
        expect(qtd('2026-03-31')).toBe('2026-01-01');
        expect(qtd('2026-04-01')).toBe('2026-04-01');
        expect(qtd('2026-06-30')).toBe('2026-04-01');
        expect(qtd('2026-07-01')).toBe('2026-07-01');
        expect(qtd('2026-09-30')).toBe('2026-07-01');
        expect(qtd('2026-10-01')).toBe('2026-10-01');
        expect(qtd('2026-12-31')).toBe('2026-10-01');
    });

    it('last 12 months is the day after anchor − 12 months, clamped to the month’s length', () => {
        expect(resolveRange('last-12-months', '2026-09-24')).toEqual({ from: '2025-09-25', to: '2026-09-24' });
        expect(resolveRange('last-12-months', '2026-12-31')).toEqual({ from: '2026-01-01', to: '2026-12-31' });
        // 29 Feb 2024 − 12 months = 28 Feb 2023 (clamped, as LocalDate.minusMonths does), + 1 day.
        expect(resolveRange('last-12-months', '2024-02-29')).toEqual({ from: '2023-03-01', to: '2024-02-29' });
        expect(resolveRange('last-12-months', '2025-02-28')).toEqual({ from: '2024-02-29', to: '2025-02-28' });
    });

    it('a custom span is itself; an invalid selection or anchor resolves to no range', () => {
        expect(resolveRange({ from: '2026-01-05', to: '2026-02-10' }, '2026-09-24')).toEqual({
            from: '2026-01-05',
            to: '2026-02-10',
        });
        expect(resolveRange(null, '2026-09-24')).toBeNull();
        expect(resolveRange('last-week' as never, '2026-09-24')).toBeNull();
        expect(resolveRange({ from: '2026-02-10', to: '2026-01-05' }, '2026-09-24')).toBeNull();
        expect(resolveRange('last-7-days', '2026-02-30')).toBeNull();
    });

    it('reads a stored defaultRange tolerantly: a known preset or a valid {from,to} only', () => {
        expect(asRangeSelection('quarter-to-date')).toBe('quarter-to-date');
        expect(asRangeSelection({ from: '2026-01-01', to: '2026-01-01', extra: 1 })).toEqual({
            from: '2026-01-01',
            to: '2026-01-01',
        });
        expect(asRangeSelection('')).toBeNull();
        expect(asRangeSelection('custom')).toBeNull();
        expect(asRangeSelection({ from: '2026-01-01' })).toBeNull();
        expect(asRangeSelection({ from: '2026-13-01', to: '2026-12-01' })).toBeNull();
        expect(asRangeSelection(7)).toBeNull();
        expect(isIsoDay('2024-02-29')).toBe(true);
        expect(isIsoDay('2026-02-29')).toBe(false);
    });

    it('today is the viewer’s wall-clock day, not the UTC one', () => {
        // 00:30 local on 25 Sep is still 25 Sep, whatever the zone offset.
        expect(todayIso(new Date(2026, 8, 25, 0, 30))).toBe('2026-09-25');
        expect(todayIso(new Date(2026, 8, 25, 23, 59))).toBe('2026-09-25');
    });

    it('the per-tile condition is half-open — inclusive of every instant of the last day', () => {
        expect(rangeCondition('event_date', { from: '2026-09-01', to: '2026-09-30' })).toEqual({
            kind: 'group',
            op: 'AND',
            items: [
                { kind: 'condition', field: 'event_date', operator: '>=', value: '2026-09-01' },
                { kind: 'condition', field: 'event_date', operator: '<', value: '2026-10-01' },
            ],
        });
        expect(rangeCondition('d', { from: '2026-12-01', to: '2026-12-31' }).items[1]).toMatchObject({
            value: '2027-01-01',
        });
    });

    it('labels a span compactly', () => {
        expect(spanLabel({ from: '2026-09-01', to: '2026-09-24' })).toBe('1 Sep – 24 Sep 2026');
        expect(spanLabel({ from: '2025-09-25', to: '2026-09-24' })).toBe('25 Sep 2025 – 24 Sep 2026');
    });
});
