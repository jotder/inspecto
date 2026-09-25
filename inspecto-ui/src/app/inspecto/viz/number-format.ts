/**
 * UIE-4 — how a widget's numbers read. ONE formatter behind table cells, chart axes, tooltips and the KPI tile, so
 * a value reads the same wherever it appears. Framework-free.
 *
 * Without a configured {@link NumberFormat} a value is grouped and capped at two decimals — enough to stop a
 * floating-point sum rendering as `13175.369999999999`. An axis defaults to compact (`2.5M`), because tick labels
 * need to stay short.
 */

/** A widget's number format (`options.format`, or one entry of a table's `options.columnFormats`). */
export interface NumberFormat {
    /**
     * `number` (default), `currency` (needs {@link currency}), or `percent`. A percent value is already in points
     * (`12.5` reads `12.5 %`); it is not multiplied by 100.
     */
    style?: 'number' | 'currency' | 'percent';
    /** ISO 4217 code for `currency`, e.g. `SAR`. */
    currency?: string;
    /** `7,664,957` → `7.7M`. */
    compact?: boolean;
    /** Fixed fraction digits. Default: up to 2 (0–1 when compact). */
    decimals?: number;
}

/**
 * ONE locale for every widget number, so a demo reads the same on every machine: the host locale made `2.5M` read
 * `25L` (lakh) on an Indian-English laptop and grouped digits differently per evaluator.
 */
export const LOCALE = 'en';

/** Column names that hold identifiers or calendar parts, never quantities: they render raw, ungrouped. */
const RAW_COLUMN = /(^|_)(id|ids|year|month|day|code|msisdn|imsi|imei|iccid|key|no|number)$/i;

/** Whether a table column's numbers should render as written rather than grouped (`2026` stays `2026`). */
export function isRawNumberColumn(column: string): boolean {
    return RAW_COLUMN.test(column);
}

/** Format one number. Non-finite values render as an em dash (a value that isn't there is not a zero). */
export function formatNumber(value: number, format?: NumberFormat): string {
    if (value == null || !Number.isFinite(value)) return '—';
    const f = format ?? {};
    const compact = !!f.compact;
    const digits: Intl.NumberFormatOptions =
        f.decimals != null
            ? { minimumFractionDigits: f.decimals, maximumFractionDigits: f.decimals }
            : { maximumFractionDigits: compact ? 1 : 2 };
    const base: Intl.NumberFormatOptions = { ...digits, ...(compact ? { notation: 'compact' } : {}) };
    if (f.style === 'percent') return `${new Intl.NumberFormat(LOCALE, base).format(value)} %`;
    if (f.style === 'currency' && f.currency) {
        try {
            return new Intl.NumberFormat(LOCALE, { ...base, style: 'currency', currency: f.currency, currencyDisplay: 'code' })
                .format(value)
                .replace(/\s/g, ' ');
        } catch {
            // An unknown currency code is an authoring slip, not a reason to show nothing — fall back to the number.
        }
    }
    return new Intl.NumberFormat(LOCALE, base).format(value);
}

/** An axis tick: the widget's format made compact (tick labels must stay short). */
export function formatAxisTick(value: number, format?: NumberFormat): string {
    return formatNumber(value, { ...format, compact: true, decimals: undefined });
}
