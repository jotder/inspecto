import { describe, expect, it } from 'vitest';
import { DateTime } from 'luxon';
import { fmtBytes, fmtDateTime, fmtInt, fmtPercent, fmtWhen } from './format';
import { FmtPercentPipe, FmtWhenPipe } from './pipes';

describe('format', () => {
    it('fmtInt rounds and adds thousands separators', () => {
        expect(fmtInt(1234.6)).toBe((1235).toLocaleString());
        expect(fmtInt(0)).toBe('0');
    });

    it('fmtBytes scales B → KB/MB', () => {
        expect(fmtBytes(512)).toBe('512 B');
        expect(fmtBytes(1024)).toBe('1.0 KB');
        expect(fmtBytes(1536)).toBe('1.5 KB');
        expect(fmtBytes(1048576)).toBe('1.0 MB');
    });

    it('fmtPercent renders a ratio as a 1-decimal percentage', () => {
        expect(fmtPercent(0.0123)).toBe('1.2%');
        expect(fmtPercent(0)).toBe('0.0%');
    });

    it('fmtDateTime handles empty / invalid / epoch-falsy', () => {
        expect(fmtDateTime(null)).toBe('');
        expect(fmtDateTime('')).toBe('');
        expect(fmtDateTime('not-a-date')).toBe('not-a-date');
        expect(fmtDateTime(0)).toBe(''); // 0 is falsy → '' (preserves the original grid guard)
    });

    describe('fmtWhen', () => {
        const now = new Date('2026-08-24T12:00:00Z');

        it('returns "" for falsy and raw string for unparseable (fmtDateTime contract)', () => {
            expect(fmtWhen(null)).toBe('');
            expect(fmtWhen('')).toBe('');
            expect(fmtWhen(undefined)).toBe('');
            expect(fmtWhen('not-a-date')).toBe('not-a-date');
        });

        it('is relative within ±24h — minutes and hours, past and future', () => {
            expect(fmtWhen(new Date('2026-08-24T11:57:00Z'), now)).toBe('3m ago');
            expect(fmtWhen(new Date('2026-08-24T10:00:00Z').getTime(), now)).toBe('2h ago');
            expect(fmtWhen('2026-08-24T14:00:00Z', now)).toBe('in 2h');
            // Sub-minute gaps clamp to 1m rather than "0m ago".
            expect(fmtWhen('2026-08-24T11:59:30Z', now)).toBe('1m ago');
        });

        it('is absolute beyond ±24h', () => {
            expect(fmtWhen('2026-08-01T12:00:00Z', now)).toBe(DateTime.fromISO('2026-08-01T12:00:00Z').toLocaleString());
        });
    });
});

describe('FmtPercentPipe', () => {
    const pipe = new FmtPercentPipe();

    it('formats a ratio and guards null/undefined', () => {
        expect(pipe.transform(0.0123)).toBe('1.2%');
        expect(pipe.transform(null)).toBe('—');
        expect(pipe.transform(undefined)).toBe('—');
    });
});

describe('FmtWhenPipe', () => {
    const pipe = new FmtWhenPipe();

    it('renders an em-dash for null/undefined and delegates otherwise', () => {
        expect(pipe.transform(null)).toBe('—');
        expect(pipe.transform(undefined)).toBe('—');
        expect(pipe.transform('2026-08-20T12:00:00Z')).toBe(
            DateTime.fromISO('2026-08-20T12:00:00Z').toLocaleString(),
        );
    });
});

