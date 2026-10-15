import type { ActivatedRoute } from '@angular/router';
import { formatNumber } from '@inspecto/core/viz/number-format';
import { rampColor, sequentialLevel } from '@inspecto/core/viz/heatmap';
import type { Assessed } from '@inspecto/link-analysis/api/entity-profile.service';
import { formatDay } from '@inspecto/link-analysis/link-analysis/data-trust';

/**
 * The framework-free half of the Entities pages: the honest wording (an unassessed section says "not assessed" with
 * its reason, never a zero), the freshness badge, the relative bar lists and the per-day calendar.
 */

/** "Not assessed: <reason>", in the data-trust wording; the reason when the server gave one. `inline` = lower case, mid-sentence. */
export function notAssessedText(s: Assessed | null | undefined, inline = false): string {
    const reason = s?.reason?.trim();
    const head = inline ? 'not assessed' : 'Not assessed';
    return reason ? `${head}: ${reason}` : head;
}

/** A count in the shared number format; an em dash for an absent one (never a 0 nobody measured). */
export function count(n: number | null | undefined): string {
    return n == null || !Number.isFinite(n) ? '—' : formatNumber(n);
}

/** A 0..1 share as a percent with one decimal ("99.9 %"); an em dash when absent. */
export function percent(share: number | null | undefined): string {
    if (share == null || !Number.isFinite(share)) return '—';
    return `${formatNumber(Math.round(share * 1000) / 10)} %`;
}

/** The freshness badge: a status-badge value (its tone) and the word it shows (never colour alone). */
export interface Freshness {
    value: 'OK' | 'STALE' | 'UNKNOWN';
    label: string;
}

/** Data older than this many days reads as stale. */
export const STALE_AFTER_DAYS = 2;

export function freshness(dataAsOf: string | null | undefined, assessed: boolean, now: Date = new Date()): Freshness {
    if (!assessed) return { value: 'UNKNOWN', label: 'Not assessed' };
    const t = new Date(dataAsOf ?? '').getTime();
    if (!dataAsOf || Number.isNaN(t)) return { value: 'UNKNOWN', label: 'As-of not known' };
    const ageDays = (now.getTime() - t) / 86_400_000;
    return ageDays <= STALE_AFTER_DAYS ? { value: 'OK', label: 'Fresh' } : { value: 'STALE', label: 'Stale' };
}

/** "5 Sep 2026", or blank. */
export function day(iso: string | null | undefined): string {
    return formatDay(iso);
}

/** One bar of a relative bar list: the bar is a share of the LARGEST item, and its text says so. */
export interface Bar {
    label: string;
    rows: number;
    /** 0..100, relative to the largest item. */
    pct: number;
    srText: string;
}

export function bars(items: { label: string; rows: number }[]): Bar[] {
    const max = Math.max(0, ...items.map((i) => i.rows));
    return items.map((i) => {
        const pct = max > 0 ? Math.round((i.rows / max) * 100) : 0;
        return { label: i.label, rows: i.rows, pct, srText: `${count(i.rows)} rows, ${pct} % of the largest item` };
    });
}

/** One cell of the per-day calendar; `null` pads the first and last week. */
export interface CalendarCell {
    date: string;
    rows: number | null;
    distinct: number | null;
    missing: boolean;
    background: string | null;
    text: string;
}

export interface CalendarWeek {
    /** The Monday that starts the week (ISO date). */
    start: string;
    days: (CalendarCell | null)[];
}

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const;
export const CALENDAR_WEEKDAYS = WEEKDAYS;

function iso(d: Date): string {
    return d.toISOString().slice(0, 10);
}

/**
 * The per-day calendar: one column per week (Monday first, UTC), one row per weekday, from the first to the last day
 * the read names. A day the read lists as missing says so; a day it does not name at all is a gap with no rows known
 * (rows `null`), not a zero.
 */
export function calendar(
    perDay: { day: string; rows: number; distinct?: number }[],
    missingDays: string[] = [],
): CalendarWeek[] {
    const known = new Map(perDay.map((d) => [d.day, d]));
    const missing = new Set(missingDays);
    const all = [...known.keys(), ...missing].filter((d) => /^\d{4}-\d{2}-\d{2}$/.test(d)).sort();
    if (!all.length) return [];
    const values = perDay.map((d) => d.rows);
    const min = Math.min(...values, 0);
    const max = Math.max(...values, 0);
    const first = new Date(`${all[0]}T00:00:00Z`);
    const last = new Date(`${all[all.length - 1]}T00:00:00Z`);
    const start = new Date(first);
    start.setUTCDate(start.getUTCDate() - ((start.getUTCDay() + 6) % 7));
    const weeks: CalendarWeek[] = [];
    for (const d = new Date(start); d <= last; ) {
        const week: CalendarWeek = { start: iso(d), days: [] };
        for (let i = 0; i < 7; i++, d.setUTCDate(d.getUTCDate() + 1)) {
            const date = iso(d);
            if (d < first || d > last) {
                week.days.push(null);
                continue;
            }
            const k = known.get(date);
            const isMissing = missing.has(date);
            const rows = k ? k.rows : isMissing ? 0 : null;
            const distinct = k?.distinct ?? null;
            const text = isMissing
                ? `${date}: missing`
                : rows == null
                  ? `${date}: not read`
                  : `${date}: ${count(rows)} rows${distinct != null ? `, ${count(distinct)} distinct` : ''}`;
            week.days.push({
                date,
                rows,
                distinct,
                missing: isMissing,
                background: rows && !isMissing ? rampColor('primary', sequentialLevel(rows, min, max)) : null,
                text,
            });
        }
        weeks.push(week);
    }
    return weeks;
}

/** The weekday rows of a calendar (the table is drawn row-per-weekday). */
export function calendarRows(weeks: CalendarWeek[]): { weekday: string; cells: (CalendarCell | null)[] }[] {
    return WEEKDAYS.map((weekday, i) => ({ weekday, cells: weeks.map((w) => w.days[i]) }));
}

/**
 * The URL segments above an Entities page: the host mounts the pages at `entities` (la-app) or `studio/entities` (the
 * full SPA), so the base is read from the route rather than assumed. `depth` = how many segments the page itself adds
 * (0 the list, 1 an Entity Type, 2 an Entity).
 */
export function entitiesBase(route: ActivatedRoute, depth: 0 | 1 | 2): string[] {
    const segs = route.snapshot.pathFromRoot.flatMap((r) => r.url.map((u) => u.path));
    return ['/', ...segs.slice(0, segs.length - depth)];
}

/** Link Analysis is the Entities pages' sibling in both hosts (`link-analysis` / `studio/link-analysis`). */
export function linkAnalysisFrom(entitiesBaseCommands: string[]): string[] {
    return [...entitiesBaseCommands.slice(0, -1), 'link-analysis'];
}

/** The Entity Type page's section names, in page order (the `sections=` keys of the profile read). */
export const PROFILE_SECTIONS = [
    { key: 'bindings', title: 'Where it appears' },
    { key: 'lineage', title: 'Lineage' },
    { key: 'volume', title: 'Volume and quality' },
    { key: 'shapes', title: 'Value shapes' },
    { key: 'time', title: 'Time' },
    { key: 'links', title: 'Links' },
    { key: 'relationships', title: 'Relationships' },
] as const;

type ProfileSectionKey = (typeof PROFILE_SECTIONS)[number]['key'];

/** A section the read did not return reads as not assessed: absent is not empty. */
export const NOT_RETURNED: Assessed = { assessed: false, reason: 'not returned by the server' };

/**
 * "What is not here": what the profile could NOT say, in words - no binding at all, bindings with no time column,
 * Datasets with no data-as-of, and every section (or per-Dataset row) that was not assessed.
 */
export function notHere(p: {
    bindings?: {
        dataset: string;
        column: string;
        timeColumn: string | null;
        assessed: boolean;
        reason: string | null;
    }[];
    time?: Assessed & { dataAsOf: { dataset: string; dataAsOf: string | null }[] };
    lineage?: (Assessed & { dataset: string })[];
    relationships?: (Assessed & { otherType: string; dataset: string })[];
    [k: string]: unknown;
}): string[] {
    const out: string[] = [];
    if (p.bindings && !p.bindings.length) out.push('No Dataset column is bound to this Entity Type.');
    for (const b of p.bindings ?? []) {
        if (!b.timeColumn) out.push(`${b.dataset}.${b.column} has no time column: it has no timeline.`);
        if (!b.assessed) out.push(`${b.dataset}.${b.column}: ${notAssessedText(b, true)}.`);
    }
    if (p.time?.assessed)
        for (const d of p.time.dataAsOf)
            if (!d.dataAsOf) out.push(`${d.dataset} has no data-as-of (no freshness column).`);
    for (const l of p.lineage ?? []) if (!l.assessed) out.push(`Lineage of ${l.dataset}: ${notAssessedText(l, true)}.`);
    for (const r of p.relationships ?? [])
        if (!r.assessed) out.push(`Relationship with ${r.otherType} in ${r.dataset}: ${notAssessedText(r, true)}.`);
    for (const s of PROFILE_SECTIONS) {
        const v = p[s.key as ProfileSectionKey];
        if (v === undefined) out.push(`${s.title}: ${notAssessedText(NOT_RETURNED, true)}.`);
        else if (!Array.isArray(v) && (v as Assessed).assessed === false)
            out.push(`${s.title}: ${notAssessedText(v as Assessed, true)}.`);
    }
    return out;
}
