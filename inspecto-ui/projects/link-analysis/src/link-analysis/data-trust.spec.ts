import { describe, expect, it } from 'vitest';
import { DatasetFilesPage } from '@inspecto/link-analysis/api/inv.service';
import { buildStrip, dayCells, daysWindow, formatDay, formatInstant, outcomeTone, splitMissing } from './data-trust';

const files = (over: Partial<NonNullable<DatasetFilesPage['summary']>> = {}): DatasetFilesPage =>
    ({
        dataset: 'calls',
        assessed: true,
        total: 7,
        offset: 0,
        limit: 25,
        items: [],
        readAt: 'x',
        summary: { files: 7, rows: 70, latestIngestedAt: 't', indexed: 6, waiting: 0, unknown: 0, problem: 0, ...over },
    }) as DatasetFilesPage;

describe('buildStrip', () => {
    it('reads as one line and is OK when nothing is wrong', () => {
        const s = buildStrip({
            dataAsOf: '2026-09-05T10:00:00Z',
            days: { expected: 7, covered: 7, missing: [] },
            index: { version: 3, stale: false },
            files: files(),
        });
        expect(s.text).toBe('Data as of 5 Sep 2026 · 7 of 7 days · index v3');
        expect(s.tone).toBe('success');
        expect(s.label).toBe('OK');
    });

    it('turns an otherwise-OK strip to warning when a day was corrected, counting each day once', () => {
        const s = buildStrip({
            dataAsOf: '2026-09-05T10:00:00Z',
            days: { expected: 7, covered: 7, missing: [] },
            index: { version: 3, stale: false },
            files: files(),
            correctedDays: ['2026-09-01', '2026-09-01', '2026-09-02'],
        });
        expect(s.text).toBe('Data as of 5 Sep 2026 · 7 of 7 days · 2 days corrected since you last looked · index v3');
        expect(s.tone).toBe('warning');
        expect(s.label).toBe('WARNING');
    });

    it('names a gap, a waiting File and warns', () => {
        const s = buildStrip({
            dataAsOf: '2026-09-05T10:00:00Z',
            days: { expected: 7, covered: 6, missing: ['2026-09-03'] },
            index: { version: 3, stale: false },
            files: files({ waiting: 1 }),
        });
        expect(s.text).toBe('Data as of 5 Sep 2026 · 6 of 7 days · 1 gap · index v3, 1 file waiting');
        expect(s.tone).toBe('warning');
    });

    it('never claims more than was measured: unread parts say so', () => {
        const s = buildStrip({ dataAsOf: null, days: null, index: null, files: null });
        expect(s.text).toBe('Data as-of date not known · days not assessed · no index');
        expect(s.tone).toBe('neutral');
        expect(s.label).toBe('UNKNOWN');
    });

    it('counts problem Files and a stale index', () => {
        const s = buildStrip({
            dataAsOf: '2026-09-05T00:00:00Z',
            days: { expected: 2, covered: 2, missing: [] },
            index: { version: 2, stale: true },
            files: files({ problem: 2 }),
        });
        expect(s.text).toContain('2 Files quarantined, partial or failed');
        expect(s.text).toContain('index v2 (stale)');
        expect(s.tone).toBe('warning');
    });

    it('an unassessed Files read adds no Files claim', () => {
        const f = { ...files({ waiting: 5 }), assessed: false };
        const s = buildStrip({ dataAsOf: null, days: null, index: { version: 1, stale: false }, files: f });
        expect(s.text).not.toContain('waiting');
    });
});

describe('not delivered yet vs a real gap', () => {
    const fileDays = [
        { day: '2026-09-02', files: 1 },
        { day: '2026-09-04', files: 1 },
    ];

    it('splits missing days: inside the delivered span is a hole, outside is not delivered yet', () => {
        expect(splitMissing(['2026-09-01', '2026-09-03', '2026-09-05', '2026-09-06'], fileDays)).toEqual({
            holes: ['2026-09-03'],
            notDelivered: ['2026-09-01', '2026-09-05', '2026-09-06'],
        });
    });

    it('judges nothing without a delivered day: every missing day stays a hole', () => {
        expect(splitMissing(['2026-09-01'], [])).toEqual({ holes: ['2026-09-01'], notDelivered: [] });
        expect(splitMissing(['2026-09-01'], null)).toEqual({ holes: ['2026-09-01'], notDelivered: [] });
    });

    it('the strip counts only the hole as a gap and names the rest', () => {
        const s = buildStrip({
            dataAsOf: '2026-09-05T00:00:00Z',
            days: { expected: 6, covered: 2, missing: ['2026-09-01', '2026-09-03', '2026-09-05', '2026-09-06'] },
            index: null,
            files: { ...files(), days: [...fileDays.map((d) => ({ ...d, rows: 5 }))] },
        });
        expect(s.text).toContain('2 of 6 days · 1 gap · 3 not delivered yet');
        expect(s.tone).toBe('warning');
    });

    it('only days after the newest File: no gap, no warning', () => {
        const s = buildStrip({
            dataAsOf: '2026-09-05T00:00:00Z',
            days: { expected: 7, covered: 5, missing: ['2026-09-05', '2026-09-06'] },
            index: { version: 1, stale: false },
            files: { ...files(), days: [{ day: '2026-09-01', files: 1, rows: 5 }, { day: '2026-09-04', files: 1, rows: 5 }] },
        });
        expect(s.text).toContain('5 of 7 days · 2 not delivered yet');
        expect(s.text).not.toContain('gap');
        expect(s.tone).toBe('success');
    });

    it('a zero-row day outside the span reads pending, one inside reads missing', () => {
        const cells = dayCells(
            [
                { date: '2026-09-01', rows: 0 },
                { date: '2026-09-02', rows: 5 },
                { date: '2026-09-03', rows: 0 },
                { date: '2026-09-04', rows: 5 },
                { date: '2026-09-05', rows: 0 },
            ],
            fileDays,
        );
        expect(cells.map((c) => c.state)).toEqual(['pending', 'ok', 'missing', 'ok', 'pending']);
    });

    it('states an instant in UTC and refuses a zone-less one', () => {
        expect(formatInstant('2026-09-05T10:03:20Z')).toBe('5 Sep 2026 10:03 UTC');
        expect(formatInstant('2026-09-05T15:33:20+05:30')).toBe('5 Sep 2026 10:03 UTC');
        expect(formatInstant('2026-09-05 10:03:20')).toBe('');
        expect(formatInstant(null)).toBe('');
    });
});

describe('dayCells', () => {
    it('tells a gap (no File) from a zero-row day (a File arrived)', () => {
        const cells = dayCells(
            [
                { date: '2026-09-01', rows: 10 },
                { date: '2026-09-02', rows: 0 },
                { date: '2026-09-03', rows: 0 },
            ],
            [
                { day: '2026-09-01', files: 1 },
                { day: '2026-09-03', files: 1 },
            ],
        );
        expect(cells.map((c) => c.state)).toEqual(['ok', 'missing', 'empty']);
    });
});

describe('helpers', () => {
    it('formats a day in UTC and tolerates junk', () => {
        expect(formatDay('2026-09-05T23:59:59Z')).toBe('5 Sep 2026');
        // a DATE column is "covered through its end": the instant is the NEXT midnight, the day shown is the last one with data
        expect(formatDay('2026-09-05T00:00:00Z')).toBe('4 Sep 2026');
        expect(formatDay('2026-10-01T00:00:00.000Z')).toBe('30 Sep 2026');
        expect(formatDay(null)).toBe('');
        expect(formatDay('nope')).toBe('');
    });

    it('maps outcomes to a badge tone', () => {
        expect(outcomeTone('SUCCESS')).toBe('SUCCESS');
        expect(outcomeTone('PARTIAL')).toBe('WARNING');
        expect(outcomeTone('QUARANTINED')).toBe('WARNING');
        expect(outcomeTone('FAILED')).toBe('ERROR');
    });

    it('spans the Files days as a half-open window, or none when no day is known', () => {
        expect(daysWindow([{ day: '2026-09-03' }, { day: '2026-09-01' }, { day: '2026-09-30' }])).toEqual({
            from: '2026-09-01T00:00:00Z',
            to: '2026-10-01T00:00:00Z',
        });
        expect(daysWindow([])).toBeNull();
        expect(daysWindow(undefined)).toBeNull();
        expect(daysWindow([{ day: 'junk' }])).toBeNull();
    });
});
