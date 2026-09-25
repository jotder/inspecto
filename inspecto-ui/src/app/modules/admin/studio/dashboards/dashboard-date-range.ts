import { ConditionGroup } from 'app/inspecto/query';

/**
 * UIE-5 (d) — the Dashboard date-range selector's model and date maths. Pure; no Angular.
 *
 * A range is picked as a PRESET that counts back from the Dashboard's `asOf` day (or today when it has none),
 * or as a CUSTOM `{from, to}`. Every date is a calendar day `YYYY-MM-DD`: the maths runs on UTC calendar fields
 * only, so no viewer time zone ever shifts a boundary. Both ends are INCLUSIVE days.
 *
 * ⚠ The server mirrors these rules for a shared link (`DashboardDateRange.java` in `inspecto/.../control`) —
 * change one, change the other.
 */

export type DateRangePreset =
    | 'last-7-days'
    | 'last-30-days'
    | 'last-90-days'
    | 'month-to-date'
    | 'quarter-to-date'
    | 'year-to-date'
    | 'last-12-months';

/** A calendar-day span, both ends inclusive. */
export interface DateSpan {
    from: string;
    to: string;
}

/** What a Dashboard stores as `defaultRange` and what the viewer picks: a preset id, or a custom span. */
export type DateRangeSelection = DateRangePreset | DateSpan;

export const DATE_RANGE_PRESETS: readonly { id: DateRangePreset; label: string }[] = [
    { id: 'last-7-days', label: 'Last 7 days' },
    { id: 'last-30-days', label: 'Last 30 days' },
    { id: 'last-90-days', label: 'Last 90 days' },
    { id: 'month-to-date', label: 'Month to date' },
    { id: 'quarter-to-date', label: 'Quarter to date' },
    { id: 'year-to-date', label: 'Year to date' },
    { id: 'last-12-months', label: 'Last 12 months' },
];

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;
const PRESET_IDS = new Set<string>(DATE_RANGE_PRESETS.map((p) => p.id));

/** A real calendar day in exactly `YYYY-MM-DD` form (`2026-02-30` is not one). */
export function isIsoDay(s: unknown): s is string {
    if (typeof s !== 'string' || !ISO_DAY.test(s)) return false;
    const [y, m, d] = s.split('-').map(Number);
    const date = new Date(Date.UTC(y, m - 1, d));
    return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}

/** A stored/picked value as a {@link DateRangeSelection}, or null when it is neither a known preset nor a valid
 *  custom span (`from` ≤ `to`, both real days). Tolerant of raw content — the component route censuses keys only. */
export function asRangeSelection(raw: unknown): DateRangeSelection | null {
    if (typeof raw === 'string') return PRESET_IDS.has(raw) ? (raw as DateRangePreset) : null;
    if (raw && typeof raw === 'object') {
        const { from, to } = raw as Record<string, unknown>;
        if (isIsoDay(from) && isIsoDay(to) && from <= to) return { from, to };
    }
    return null;
}

/** Today as a calendar day in the viewer's own calendar (the day on their wall clock). */
export function todayIso(now: Date = new Date()): string {
    return iso(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()));
}

/**
 * The inclusive span a selection covers, counted back from `anchor` (a `YYYY-MM-DD` day: the Dashboard's `asOf`,
 * or today). Null for an invalid selection or anchor.
 *
 * - Last N days: `anchor − (N−1)` … `anchor` (N days including the anchor day).
 * - Month / quarter / year to date: the first day of the anchor's calendar month / quarter (Jan, Apr, Jul, Oct) /
 *   year … `anchor`.
 * - Last 12 months: the day after `anchor − 12 months` … `anchor`; month arithmetic clamps to the month's last
 *   day (as `java.time.LocalDate.minusMonths` does), so 29 Feb 2024 counts back to 1 Mar 2023.
 */
export function resolveRange(selection: DateRangeSelection | null, anchor: string): DateSpan | null {
    const sel = asRangeSelection(selection);
    if (!sel) return null;
    if (typeof sel !== 'string') return sel;
    if (!isIsoDay(anchor)) return null;
    const [y, m, d] = anchor.split('-').map(Number);
    const to = anchor;
    switch (sel) {
        case 'last-7-days':
            return { from: addDays(anchor, -6), to };
        case 'last-30-days':
            return { from: addDays(anchor, -29), to };
        case 'last-90-days':
            return { from: addDays(anchor, -89), to };
        case 'month-to-date':
            return { from: iso(Date.UTC(y, m - 1, 1)), to };
        case 'quarter-to-date':
            return { from: iso(Date.UTC(y, Math.floor((m - 1) / 3) * 3, 1)), to };
        case 'year-to-date':
            return { from: iso(Date.UTC(y, 0, 1)), to };
        case 'last-12-months':
            return { from: addDays(minusMonthsClamped(y, m, d, 12), 1), to };
    }
}

/** `2026-09-01` … `2026-09-24` → `1 Sep – 24 Sep 2026` (the year once when both ends share it). */
export function spanLabel(span: DateSpan): string {
    const [fy, fm, fd] = span.from.split('-').map(Number);
    const [ty, tm, td] = span.to.split('-').map(Number);
    const head = `${fd} ${MONTHS[fm - 1]}${fy === ty ? '' : ' ' + fy}`;
    return `${head} – ${td} ${MONTHS[tm - 1]} ${ty}`;
}

/**
 * The per-tile condition for a span: `field >= from AND field < (to + 1 day)`. Half-open on purpose — it is the
 * inclusive `between from and to` for a DATE column, and ALSO keeps every instant of the last day for a
 * TIMESTAMP column (where `<= to` would stop at midnight and drop the day).
 */
export function rangeCondition(field: string, span: DateSpan): ConditionGroup {
    return {
        kind: 'group',
        op: 'AND',
        items: [
            { kind: 'condition', field, operator: '>=', value: span.from },
            { kind: 'condition', field, operator: '<', value: addDays(span.to, 1) },
        ],
    };
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function iso(utcMillis: number): string {
    return new Date(utcMillis).toISOString().slice(0, 10);
}

function addDays(day: string, n: number): string {
    const [y, m, d] = day.split('-').map(Number);
    return iso(Date.UTC(y, m - 1, d + n));
}

/** `y-m-d` minus `n` months, the day clamped to the target month's length. */
function minusMonthsClamped(y: number, m: number, d: number, n: number): string {
    const target = Date.UTC(y, m - 1 - n, 1);
    const ty = new Date(target).getUTCFullYear();
    const tm = new Date(target).getUTCMonth();
    const last = new Date(Date.UTC(ty, tm + 1, 0)).getUTCDate();
    return iso(Date.UTC(ty, tm, Math.min(d, last)));
}
