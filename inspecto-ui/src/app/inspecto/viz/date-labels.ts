import { LOCALE } from './number-format';

/**
 * Readable date labels for a chart's category axis (a UIE-4 follow-up). A category axis over a DATE column printed
 * the raw ISO value (`2025-10-01`); here, when EVERY category is an ISO date, the axis reads like a calendar:
 *
 * - every date on the 1st of its month (a month grain) → `Oct 2025` on every tick. The year is always shown: a
 *   month axis is short enough to afford it, and a bare `Jan` next to `Dec` is ambiguous.
 * - otherwise → `21 Sep`, with the year added (`21 Sep 2025`) only when the dates span more than one year.
 * - a clock time that is not midnight (an hour grain) → `21 Sep 13:00`.
 *
 * `YYYY-MM` (the offline month bucket, `time-grain.ts`) counts as the 1st of that month. The wall-clock digits are
 * used as written — a trailing `Z` or offset is not converted to the viewer's zone, so a label never shifts a day.
 * Blank categories pass through as `''` (the renderer shows them as "(blank)"). Any other non-date category leaves
 * the whole axis unchanged. Framework-free.
 */

const ISO = /^(\d{4})-(\d{2})(?:-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(?:Z|[+-]\d{2}(?::?\d{2})?)?)?)?$/;

interface DateParts {
    date: Date; // UTC-anchored wall-clock value
    hasClock: boolean; // a non-midnight time
    monthOnly: boolean; // `YYYY-MM`, no day
}

function parse(label: string): DateParts | null {
    const m = ISO.exec(label.trim());
    if (!m) return null;
    const [y, mo, d, h, mi] = [m[1], m[2], m[3] ?? '1', m[4] ?? '0', m[5] ?? '0'].map(Number);
    const date = new Date(Date.UTC(y, mo - 1, d, h, mi));
    // Reject impossible values (month 13, 31 Feb, hour 25) that Date.UTC would silently roll over.
    if (
        date.getUTCMonth() !== mo - 1 ||
        date.getUTCDate() !== d ||
        date.getUTCHours() !== h ||
        date.getUTCMinutes() !== mi
    ) {
        return null;
    }
    return { date, hasClock: h !== 0 || mi !== 0, monthOnly: m[3] === undefined };
}

const MONTH = new Intl.DateTimeFormat(LOCALE, { month: 'short', timeZone: 'UTC' });

function hhmm(date: Date): string {
    return `${String(date.getUTCHours()).padStart(2, '0')}:${String(date.getUTCMinutes()).padStart(2, '0')}`;
}

function dayMonth(date: Date, withYear: boolean): string {
    return `${date.getUTCDate()} ${MONTH.format(date)}${withYear ? ` ${date.getUTCFullYear()}` : ''}`;
}

/**
 * The axis labels for `labels` when every non-blank one is an ISO date, else `null` (leave the axis as it is).
 * Returned labels line up index for index with the input.
 */
export function dateAxisLabels(labels: readonly string[]): string[] | null {
    const parsed = labels.map((l) => (l.trim() === '' ? undefined : parse(l)));
    if (parsed.some((p) => p === null)) return null;
    const dates = parsed.filter((p): p is DateParts => !!p);
    if (!dates.length) return null;
    const hasClock = dates.some((p) => p.hasClock);
    const monthly = !hasClock && dates.every((p) => p.date.getUTCDate() === 1);
    const crossesYear = new Set(dates.map((p) => p.date.getUTCFullYear())).size > 1;
    return parsed.map((p) => {
        if (!p) return '';
        if (monthly) return `${MONTH.format(p.date)} ${p.date.getUTCFullYear()}`;
        const day = dayMonth(p.date, crossesYear);
        return hasClock ? `${day} ${hhmm(p.date)}` : day;
    });
}

/** One ISO date in full (`1 Oct 2025`, `21 Sep 2025 13:00`) for a tooltip, or `null` when it is not a date. */
export function fullDateLabel(label: string): string | null {
    const p = parse(label);
    if (!p) return null;
    if (p.monthOnly) return `${MONTH.format(p.date)} ${p.date.getUTCFullYear()}`;
    const day = dayMonth(p.date, true);
    return p.hasClock ? `${day} ${hhmm(p.date)}` : day;
}
