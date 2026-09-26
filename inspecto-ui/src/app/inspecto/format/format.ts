/**
 * Pure, framework-agnostic display formatters shared across the app — the P4 consolidation of helpers that
 * were duplicated inline in several components. Templates use the thin pipes in `pipes.ts`; TS call sites import
 * these directly. Only Luxon (`DateTime`) and the one display `LOCALE` are imported besides Angular-free stdlib
 * (still vitest-pure, like `query/`). Only formatters with ≥2 real call sites live here (adoption-plan STOP).
 */

import { DateTime } from 'luxon';
import { LOCALE } from 'app/inspecto/viz/number-format';

const MONTH = new Intl.DateTimeFormat(LOCALE, { month: 'short' });
const pad2 = (n: number): string => String(n).padStart(2, '0');

/**
 * A date-time for grids / detail views — a Date, epoch millis or ISO string → `26 Sep 2026, 08:05:09` in the
 * viewer's zone ('' for empty/falsy; an unparseable value comes back raw). R3-01: ONE spelling on every machine,
 * the rule `viz/number-format` follows for numbers — `toLocaleString()` wrote `9/26/2026, 8:05:09 AM` on a US
 * host and `26/09/2026, …` elsewhere, beside Dashboards that already read "26 Sep 2026".
 */
export function fmtDateTime(value: unknown): string {
    if (!value) return '';
    const d = value instanceof Date ? value : typeof value === 'number' ? new Date(value) : new Date(String(value));
    if (isNaN(d.getTime())) return String(value);
    const time = `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
    return `${d.getDate()} ${MONTH.format(d)} ${d.getFullYear()}, ${time}`;
}

/** A whole-number count with thousands separators (rounds first). */
export function fmtInt(n: number): string {
    return Math.round(n).toLocaleString();
}

/** A human byte size — B / KB / MB / GB / TB, one decimal above 1 KB. */
export function fmtBytes(n: number): string {
    if (n < 1024) return `${Math.round(n)} B`;
    const units = ['KB', 'MB', 'GB', 'TB'];
    let v = n / 1024;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
        v /= 1024;
        i++;
    }
    return `${v.toFixed(1)} ${units[i]}`;
}

/** A ratio (0–1) as a percentage with one decimal, e.g. 0.0123 → "1.2%". */
export function fmtPercent(ratio: number): string {
    return (ratio * 100).toFixed(1) + '%';
}

/**
 * A "when did this happen" stamp for activity feeds / comments / history lists — relative within ±24h
 * ("3m ago", "in 2h"), absolute locale date-time beyond that (falls back to {@link fmtDateTime}'s
 * contract: '' for falsy, raw string back when unparseable).
 */
export function fmtWhen(value: unknown, now: Date = new Date()): string {
    if (!value) return '';
    const dt =
        value instanceof Date
            ? DateTime.fromJSDate(value)
            : typeof value === 'number'
              ? DateTime.fromMillis(value)
              : /^\d+$/.test(String(value))
                ? DateTime.fromMillis(Number(value))
                : DateTime.fromISO(String(value));
    if (!dt.isValid) return String(value);
    const ref = DateTime.fromJSDate(now);
    const diffMs = dt.diff(ref, 'milliseconds').milliseconds;
    const gap = Math.abs(diffMs);
    if (gap < DAY_MS) {
        // The single largest whole unit (h, else m) that fits the gap — sub-minute reads as "1m".
        const [amount, unit] = gap >= HOUR_MS ? [gap / HOUR_MS, 'h'] : [gap / MINUTE_MS, 'm'];
        const rounded = Math.max(1, Math.floor(amount));
        return diffMs < 0 ? `${rounded}${unit} ago` : `in ${rounded}${unit}`;
    }
    return dt.toLocaleString();
}

const MINUTE_MS = 60 * 1000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;
