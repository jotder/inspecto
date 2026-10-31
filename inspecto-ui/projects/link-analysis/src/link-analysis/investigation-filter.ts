import type { InvestigationWindow } from '@inspecto/link-analysis/api/inv.service';

/**
 * LA-INVESTIGATE-UX-1 — the Investigation tab's ONE **Expand filter**: the Expand form's values, grouped as the tab shows
 * them. Every expand (the Expand button, a double-click, the node menu, the selection bar, G) uses it without asking.
 * An untouched filter is the defaults below, laid over the Industry pack's presets — so it behaves as the old form did.
 */
export type FilterGroupId = 'direction' | 'depth' | 'kinds' | 'time' | 'strength' | 'limits';

/** The quick time chips: the Investigation's own window, all data, the last 7 / 30 / 90 days, or a custom window. */
export type FilterWindow = 'inherit' | 'full' | 'last7' | 'last30' | 'last90' | 'override';

export const QUICK_WINDOW_DAYS: Partial<Record<FilterWindow, number>> = { last7: 7, last30: 30, last90: 90 };

/** The filter as the form holds it: blank numbers are null (the pack preset or the server default applies). */
export interface FilterValues {
    direction: string;
    degrees: number;
    /** null = every link kind. */
    kinds: string[] | null;
    /** Link kinds typed (no link kind list known); '' = all. */
    typedKinds: string;
    window: FilterWindow;
    minEvents: unknown;
    minDistinctDays: unknown;
    hold: boolean;
    threshold: number | null;
    candidateDegreeMin: unknown;
    candidateDegreeMax: unknown;
    maxFanOut: unknown;
    budget: unknown;
}

export const FILTER_DEFAULTS: FilterValues = {
    direction: 'either',
    degrees: 1,
    kinds: null,
    typedKinds: '',
    window: 'inherit',
    minEvents: null,
    minDistinctDays: null,
    hold: true,
    threshold: null,
    candidateDegreeMin: null,
    candidateDegreeMax: null,
    maxFanOut: null,
    budget: null,
};

/** Which fields each group holds (its ↺ resets exactly these). */
export const FILTER_GROUP_FIELDS: Record<FilterGroupId, (keyof FilterValues)[]> = {
    direction: ['direction'],
    depth: ['degrees'],
    kinds: ['kinds', 'typedKinds'],
    time: ['window'],
    strength: ['minEvents', 'minDistinctDays'],
    limits: ['hold', 'threshold', 'candidateDegreeMin', 'candidateDegreeMax', 'maxFanOut', 'budget'],
};

const blank = (v: unknown): unknown => (v === undefined || (typeof v === 'string' && v.trim() === '') ? null : v);

function same(a: unknown, b: unknown): boolean {
    const x = blank(a);
    const y = blank(b);
    if (Array.isArray(x) || Array.isArray(y)) return JSON.stringify(x) === JSON.stringify(y);
    return x === y || (x !== null && y !== null && String(x) === String(y));
}

/** The groups whose values differ from the defaults (the tab marks each with a dot and counts them). */
export function changedGroups(v: FilterValues): FilterGroupId[] {
    return (Object.keys(FILTER_GROUP_FIELDS) as FilterGroupId[]).filter((g) =>
        FILTER_GROUP_FIELDS[g].some((k) => !same(v[k], FILTER_DEFAULTS[k])),
    );
}

/**
 * The window a quick chip sends: the last `days` days up to `end` (the bound Dataset's latest event time — never
 * "today", so a historical Dataset still answers). The server's range is half-open `[from, to)`, so `to` is one second
 * past `end` to keep the latest event in. Null when `end` is unknown or not a time.
 */
export function quickWindow(days: number, end: string | null): InvestigationWindow | null {
    const t = end ? Date.parse(end) : NaN;
    if (!Number.isFinite(t)) return null;
    return { from: new Date(t - days * 86_400_000).toISOString(), to: new Date(t + 1000).toISOString() };
}
