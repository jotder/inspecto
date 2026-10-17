import { ActivatedRoute } from '@angular/router';
import { describe, expect, it } from 'vitest';
import {
    bars,
    calendar,
    calendarRows,
    count,
    entitiesBase,
    freshness,
    linkAnalysisFrom,
    notAssessedText,
    notHere,
    percent,
} from './entity-profile-view';

const route = (...levels: string[][]) =>
    ({
        snapshot: { pathFromRoot: levels.map((segs) => ({ url: segs.map((path) => ({ path })) })) },
    }) as unknown as ActivatedRoute;

describe('entity-profile-view', () => {
    it('says "not assessed" with the reason, never a number', () => {
        expect(notAssessedText({ assessed: false, reason: 'not computed yet' })).toBe('Not assessed: not computed yet');
        expect(notAssessedText({ assessed: false, reason: null })).toBe('Not assessed');
        expect(notAssessedText({ assessed: false, reason: 'x' }, true)).toBe('not assessed: x');
        expect(count(null)).toBe('—');
        expect(count(undefined)).toBe('—');
        expect(count(0)).toBe('0');
        expect(count(20546)).toBe('20,546');
    });

    it('formats a 0..1 share as a percent with one decimal', () => {
        expect(percent(0.999)).toBe('99.9 %');
        expect(percent(0)).toBe('0 %');
        expect(percent(null)).toBe('—');
    });

    it('never rounds a share short of all up to 100 %', () => {
        expect(percent(0.9995)).toBe('99.9 %');
        expect(percent(0.99999)).toBe('99.9 %');
        expect(percent(1)).toBe('100 %');
    });

    it('reads freshness from data-as-of: not assessed, not known, fresh, stale', () => {
        const now = new Date('2026-10-15T12:00:00Z');
        expect(freshness('2026-10-15T00:00:00Z', false, now)).toEqual({ value: 'UNKNOWN', label: 'Not assessed' });
        expect(freshness(null, true, now)).toEqual({ value: 'UNKNOWN', label: 'As-of not known' });
        expect(freshness('2026-10-14T10:00:00Z', true, now)).toEqual({ value: 'OK', label: 'Fresh' });
        expect(freshness('2026-10-09T10:36:47Z', true, now)).toEqual({ value: 'STALE', label: 'Stale' });
    });

    it('draws bars relative to the largest item and says so in words', () => {
        const b = bars([
            { label: '12', rows: 80 },
            { label: '11', rows: 20 },
        ]);
        expect(b.map((x) => x.pct)).toEqual([100, 25]);
        expect(b[1].srText).toBe('20 rows, 25 % of the largest item');
        expect(bars([{ label: 'x', rows: 0 }])[0].pct).toBe(0);
    });

    it('lays days out Monday-first by week; a missing day says missing, an unread one is not a zero', () => {
        // 2026-10-07 is a Wednesday.
        const weeks = calendar(
            [
                { day: '2026-10-07', rows: 10, distinct: 4 },
                { day: '2026-10-09', rows: 30 },
            ],
            ['2026-10-08'],
        );
        expect(weeks).toHaveLength(1);
        expect(weeks[0].start).toBe('2026-10-05');
        const [mon, tue, wed, thu, fri, sat] = weeks[0].days;
        expect(mon).toBeNull();
        expect(tue).toBeNull();
        expect(wed!.text).toBe('2026-10-07: 10 rows, 4 distinct');
        expect(wed!.background).toContain('var(--gamma-primary-rgb)');
        expect(thu!.missing).toBe(true);
        expect(thu!.text).toBe('2026-10-08: missing');
        expect(thu!.background).toBeNull();
        expect(fri!.text).toBe('2026-10-09: 30 rows');
        expect(sat).toBeNull();
        expect(calendarRows(weeks).map((r) => r.weekday)).toEqual(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']);
        expect(calendar([])).toEqual([]);
    });

    it('marks a day between read days that the read never named as not read', () => {
        const weeks = calendar([
            { day: '2026-10-05', rows: 1 },
            { day: '2026-10-07', rows: 1 },
        ]);
        expect(weeks[0].days[1]!.rows).toBeNull();
        expect(weeks[0].days[1]!.text).toBe('2026-10-06: not read');
    });

    it('lists what is not here: no time column, no as-of, unassessed and unreturned sections', () => {
        const gaps = notHere({
            bindings: [{ dataset: 'calls', column: 'a', timeColumn: null, assessed: false, reason: 'timeout' }],
            volume: { assessed: false, reason: 'over the limit' },
            time: { assessed: true, reason: null, dataAsOf: [{ dataset: 'calls', dataAsOf: null }] },
            lineage: [{ dataset: 'calls', assessed: true, reason: null }],
            shapes: { assessed: true, reason: null },
            links: { assessed: true, reason: null },
        });
        expect(gaps).toContain('calls.a has no time column: it has no timeline.');
        expect(gaps).toContain('calls.a: not assessed: timeout.');
        expect(gaps).toContain('calls has no data-as-of (no freshness column).');
        expect(gaps).toContain('Volume and quality: not assessed: over the limit.');
        expect(gaps).toContain('Relationships: not assessed: not returned by the server.');
        expect(gaps.some((g) => g.startsWith('Lineage:'))).toBe(false);
        expect(notHere({ bindings: [] })).toContain('No Dataset column is bound to this Entity Type.');
    });

    it('reads the Entities base from the route in both hosts, and finds Link Analysis beside it', () => {
        expect(entitiesBase(route([], ['entities'], []), 0)).toEqual(['/', 'entities']);
        expect(entitiesBase(route([], ['studio'], ['entities'], ['msisdn']), 1)).toEqual(['/', 'studio', 'entities']);
        expect(entitiesBase(route([], ['entities'], ['msisdn', 'masked:ab']), 2)).toEqual(['/', 'entities']);
        expect(linkAnalysisFrom(['/', 'studio', 'entities'])).toEqual(['/', 'studio', 'link-analysis']);
        expect(linkAnalysisFrom(['/', 'entities'])).toEqual(['/', 'link-analysis']);
    });
});
