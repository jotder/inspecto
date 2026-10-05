import { AbstractControl, FormControl, FormGroup, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import type {
    ExpandDirection,
    ExpandRung,
    InvestigationWindow,
    InvestigationWindowExclude,
    WindowDay,
} from '@inspecto/link-analysis/api/inv.service';

/**
 * LA-SPA-OWED-SURFACES-1 — the client-side mirror of the server's expand rung and `window` op bounds
 * (`InvestigationRoutes.expandParams`, `InvestigationTime.window`). The server stays the gate: these only stop a
 * request the server is known to refuse, and its 422 message is still shown verbatim.
 */

/** `InvestigationRoutes.MAX_EXPAND_BUDGET` — a larger budget is clamped server-side, so it is refused here. */
export const MAX_EXPAND_BUDGET = 20_000;
export const EXPAND_DIRECTIONS: ExpandDirection[] = ['either', 'out', 'in', 'reciprocal'];
export const WINDOW_DAYS: WindowDay[] = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
/** `InvestigationTime.MAX_RULES` — more recurring exclusion rules than this is refused with 422. */
export const MAX_RECURRING_RULES = 32;

const int = (min: number, max?: number): ValidatorFn[] => [
    Validators.pattern(/^-?\d+$/),
    Validators.min(min),
    ...(max === undefined ? [] : [Validators.max(max)]),
];

/** `candidateDegreeMin` must not exceed `candidateDegreeMax` (the server's own 422). */
const degreeOrder: ValidatorFn = (g: AbstractControl): ValidationErrors | null => {
    const min = num(g.get('candidateDegreeMin')?.value);
    const max = num(g.get('candidateDegreeMax')?.value);
    return min !== null && max !== null && min > max ? { degreeOrder: true } : null;
};

function num(v: unknown): number | null {
    if (v === null || v === undefined || String(v).trim() === '') return null;
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
}

/** `InvestigationRoutes.MAX_LINK_KINDS` — the server refuses a longer list. */
export const MAX_LINK_KINDS = 100;

/** The kinds typed into the field: comma-separated, trimmed, blanks and repeats dropped. */
export function parseKinds(text: string): string[] {
    return [
        ...new Set(
            text
                .split(',')
                .map((k) => k.trim())
                .filter((k) => k),
        ),
    ];
}

const maxKinds: ValidatorFn = (c: AbstractControl): ValidationErrors | null =>
    parseKinds(String(c.value ?? '')).length > MAX_LINK_KINDS ? { maxKinds: true } : null;

export function rungForm() {
    return new FormGroup(
        {
            budget: new FormControl<number | string | null>(null, { validators: int(1, MAX_EXPAND_BUDGET) }),
            direction: new FormControl<ExpandDirection>('either', { nonNullable: true }),
            window: new FormControl<'inherit' | 'full' | 'override'>('inherit', { nonNullable: true }),
            /** Comma-separated link kinds; blank = all kinds. */
            linkKinds: new FormControl('', { nonNullable: true, validators: maxKinds }),
            minEvents: new FormControl<number | string | null>(null, { validators: int(1) }),
            minDistinctDays: new FormControl<number | string | null>(null, { validators: int(1) }),
            candidateDegreeMin: new FormControl<number | string | null>(null, { validators: int(0) }),
            candidateDegreeMax: new FormControl<number | string | null>(null, { validators: int(1) }),
            maxFanOut: new FormControl<number | string | null>(null, { validators: int(1) }),
        },
        { validators: degreeOrder },
    );
}
export type RungForm = ReturnType<typeof rungForm>;

/** Only the fields the analyst set — an unset field keeps the server default (budget 1 000-ish, minEvents 1, …). */
export function rungOf(f: RungForm, override?: WindowForm): ExpandRung {
    const v = f.getRawValue();
    const r: ExpandRung = {};
    for (const k of [
        'budget',
        'minEvents',
        'minDistinctDays',
        'candidateDegreeMin',
        'candidateDegreeMax',
        'maxFanOut',
    ] as const) {
        const n = num(v[k]);
        if (n !== null) r[k] = n;
    }
    if (v.direction !== 'either') r.direction = v.direction;
    if (v.window === 'override') {
        if (override) r.window = windowOf(override);
    } else if (v.window !== 'inherit') r.window = v.window;
    const kinds = parseKinds(v.linkKinds);
    if (kinds.length) r.linkKinds = kinds;
    return r;
}

const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/;
const HH_MM = /^([01]\d|2[0-3]):[0-5]\d$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Days in each month, February at its leap length (`02-29` is a valid yearly rule, matching leap years only). */
const MONTH_DAYS = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

/**
 * A recurring rule body: `*-MM-DD` (every year on that day) or `*-MM-DOW#N` (the Nth DOW of month MM, N 1-5, e.g.
 * `*-11-THU#4`). `undefined` = not a recurring entry (starts without `*-`); `null` = recurring but malformed (the
 * server's own 422 shapes: month 1-12, a day the month has, a known weekday, nth 1-5).
 */
function parseRecurring(body: string): InvestigationWindowExclude | null | undefined {
    if (!body.startsWith('*-')) return undefined;
    const yearly = /^\*-(\d{2})-(\d{2})$/.exec(body);
    if (yearly) {
        const month = Number(yearly[1]);
        const day = Number(yearly[2]);
        return month >= 1 && month <= 12 && day >= 1 && day <= MONTH_DAYS[month - 1]
            ? { rule: 'yearly', month, day }
            : null;
    }
    const nth = /^\*-(\d{2})-([A-Z]{3})#(\d)$/.exec(body);
    if (nth) {
        const month = Number(nth[1]);
        const n = Number(nth[3]);
        return month >= 1 && month <= 12 && (WINDOW_DAYS as readonly string[]).includes(nth[2]) && n >= 1 && n <= 5
            ? { rule: 'nthWeekday', month, weekday: nth[2] as WindowDay, nth: n }
            : null;
    }
    return null;
}

/**
 * The calendar-exclusion text -> the wire entries. One entry per comma / newline: `[Name=]DATE` or `[Name=]DATE..DATE`
 * (both ends inclusive), DATE = `YYYY-MM-DD`; or a recurring rule `[Name=]*-MM-DD` / `[Name=]*-MM-DOW#N` (see
 * {@link parseRecurring}). `null` when any entry is malformed (the server's own 422 shapes) or there are more than
 * {@link MAX_RECURRING_RULES} rules.
 */
export function parseExcludes(text: string): InvestigationWindowExclude[] | null {
    const out: InvestigationWindowExclude[] = [];
    for (const raw of text
        .split(/[,\n]/)
        .map((t) => t.trim())
        .filter(Boolean)) {
        const eq = raw.indexOf('=');
        const name = eq >= 0 ? raw.slice(0, eq).trim() : '';
        const body = (eq >= 0 ? raw.slice(eq + 1) : raw).trim();
        const recurring = parseRecurring(body);
        if (recurring !== undefined) {
            if (recurring === null || (eq >= 0 && !name)) return null;
            out.push({ ...recurring, ...(name ? { name } : {}) });
            continue;
        }
        const [a, b, ...rest] = body
            .trim()
            .split('..')
            .map((d) => d.trim());
        if (rest.length || !ISO_DATE.test(a) || (b !== undefined && !ISO_DATE.test(b)) || (eq >= 0 && !name))
            return null;
        if (b !== undefined && b < a) return null;
        out.push(
            b === undefined ? { date: a, ...(name ? { name } : {}) } : { from: a, to: b, ...(name ? { name } : {}) },
        );
    }
    if (out.filter((e) => e.rule).length > MAX_RECURRING_RULES) return null;
    return out;
}

/** The window's cross-field rules, each the server's own 422 (`InvestigationTime.window`). */
const windowRules: ValidatorFn = (g: AbstractControl): ValidationErrors | null => {
    const v = (g as WindowForm).getRawValue();
    if (v.full) return null;
    const errors: ValidationErrors = {};
    const days = WINDOW_DAYS.filter((d) => v.days[d]);
    const slot = !!(v.slotStart || v.slotEnd);
    const excl = parseExcludes(v.exclude);
    if (excl === null) errors['exclude'] = true;
    if (!v.from && !v.to && !slot && !days.length && excl !== null && !excl.length) errors['empty'] = true;
    if (v.from && v.to && ISO_INSTANT.test(v.from) && ISO_INSTANT.test(v.to) && Date.parse(v.from) >= Date.parse(v.to))
        errors['inverted'] = true;
    if (slot && (!v.slotStart || !v.slotEnd)) errors['slotHalf'] = true;
    if (v.slotStart && v.slotStart === v.slotEnd) errors['slotEqual'] = true;
    if ((slot || days.length || excl?.length) && !v.timezone) errors['zone'] = true;
    return Object.keys(errors).length ? errors : null;
};

export function windowForm() {
    return new FormGroup(
        {
            /** Read all time (`{op:'window', window:'full'}`) — clears any window. */
            full: new FormControl(false, { nonNullable: true }),
            from: new FormControl('', { nonNullable: true, validators: Validators.pattern(ISO_INSTANT) }),
            to: new FormControl('', { nonNullable: true, validators: Validators.pattern(ISO_INSTANT) }),
            slotStart: new FormControl('', { nonNullable: true, validators: Validators.pattern(HH_MM) }),
            slotEnd: new FormControl('', { nonNullable: true, validators: Validators.pattern(HH_MM) }),
            days: new FormGroup(
                Object.fromEntries(
                    WINDOW_DAYS.map((d) => [d, new FormControl(false, { nonNullable: true })]),
                ) as Record<WindowDay, FormControl<boolean>>,
            ),
            /** Calendar exclusions as text — see {@link parseExcludes}. */
            exclude: new FormControl('', { nonNullable: true }),
            timezone: new FormControl('', { nonNullable: true }),
        },
        { validators: windowRules },
    );
}
export type WindowForm = ReturnType<typeof windowForm>;

/** The `window` op body: `'full'`, or only the keys the analyst set (the server refuses unknown/empty keys). */
export function windowOf(f: WindowForm): InvestigationWindow | 'full' {
    const v = f.getRawValue();
    if (v.full) return 'full';
    const w: InvestigationWindow = {};
    if (v.from) w.from = v.from;
    if (v.to) w.to = v.to;
    if (v.slotStart && v.slotEnd) w.slot = { start: v.slotStart, end: v.slotEnd };
    const days = WINDOW_DAYS.filter((d) => v.days[d]);
    if (days.length) w.days = days;
    const exclude = parseExcludes(v.exclude);
    if (exclude?.length) w.exclude = exclude;
    if (v.timezone) w.timezone = v.timezone;
    return w;
}

/** A slot whose end is not after its start crosses midnight (`22:00–06:00` = 22:00 → 06:00 next day). */
export function crossesMidnight(start: string, end: string): boolean {
    return HH_MM.test(start) && HH_MM.test(end) && end < start;
}
