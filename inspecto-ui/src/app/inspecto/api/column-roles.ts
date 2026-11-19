/**
 * A Dataset column's **role** as stored (`columns[].role`, LA-COLUMN-ROLES-1; one vocabulary with the Studio Dataset
 * editor's dimension / measure / temporal) and the words the UI shows for it (`docs/GLOSSARY.md` *Column Role*).
 */
export type ColumnRole = 'entity' | 'temporal' | 'measure' | 'dimension' | 'detail';

export const ROLE_LABELS: Record<ColumnRole, string> = {
    entity: 'Entity',
    temporal: 'Time',
    measure: 'Measure',
    dimension: 'Category',
    detail: 'Detail',
};

/** The roles whose columns ride on every link and in its index, unless a column says `carried: false` (plan Q-5). */
export const CARRIED_ROLES: ReadonlySet<ColumnRole> = new Set<ColumnRole>(['temporal', 'measure', 'dimension']);

/** At most this many carried columns: a link index build's own bound (the server's `IndexRoutes.MAX_ATTR_COLS`). */
export const MAX_CARRIED = 50;

/**
 * The columns of a Dataset entry that ride on every link (LA-COLUMN-ROLES-1 P2; the server's
 * `DatasetEntryRules.carriedColumns`): each Time, Measure and Category column in column order, unless it says
 * `carried: false`, and never one of `ends` (the link's From and To); the first {@link MAX_CARRIED} only.
 */
export function carriedColumns(columns: readonly unknown[] | null | undefined, ends: readonly string[] = []): string[] {
    const skip = new Set(ends.map((e) => e.toLowerCase()));
    const out: string[] = [];
    for (const c of columns ?? []) {
        const col = c as { name?: unknown; role?: unknown; carried?: unknown } | null;
        const name = typeof col?.name === 'string' ? col.name.trim() : '';
        const role = typeof col?.role === 'string' ? (col.role.trim().toLowerCase() as ColumnRole) : null;
        if (out.length >= MAX_CARRIED) break;
        if (name && role && CARRIED_ROLES.has(role) && col?.carried !== false && !skip.has(name.toLowerCase()))
            out.push(name);
    }
    return out;
}

/** The names of a Dataset entry's columns stored with `role`, in column order (the Time columns Play can step through). */
export function columnsOfRole(columns: readonly unknown[] | null | undefined, role: ColumnRole): string[] {
    const out: string[] = [];
    for (const c of columns ?? []) {
        const col = c as { name?: unknown; role?: unknown } | null;
        if (typeof col?.name === 'string' && col.name.trim() && typeof col.role === 'string')
            if (col.role.trim().toLowerCase() === role) out.push(col.name.trim());
    }
    return out;
}

/** What each role means, under its name in a role picker (the Data page, Add data). */
export const ROLE_HINTS: Record<ColumnRole, string> = {
    entity: 'Holds an Entity Type: links join on it',
    temporal: 'A date or time: to play and filter by',
    measure: 'A number to sum and filter by (an amount, a volume)',
    dimension: 'A few named values: to filter by (a type, a channel)',
    detail: 'Carried along, not filtered on',
};

/**
 * What a record type says of its usual columns (ENTITY-TYPE-RECORD-ROLES-1; `GET /inv/entity-types` → `recordTypes[].roleHints`):
 * the column-name patterns of its Times, Measures and Categories and of its two parties. Patterns as the pack's profile
 * hints: case-insensitive, matched anywhere in the name unless anchored with `^` / `$`.
 */
export interface RecordRoleHints {
    time?: string[];
    measure?: string[];
    category?: string[];
    from?: string[];
    to?: string[];
}

/** The first of `hints` naming `column`, or null (a pattern this engine refuses is skipped). */
export function matchingHint(hints: readonly string[] | null | undefined, column: string): string | null {
    for (const h of hints ?? []) {
        try {
            if (new RegExp(h, 'i').test(column)) return h;
        } catch {
            // the server validates the shape; a pattern this engine refuses names nothing
        }
    }
    return null;
}

const DATE_TYPE = /^(date|timestamp)/i;
const DECIMAL_TYPE = /^(double|float|real|decimal|numeric)/i;
const NUMBER_TYPE =
    /^(double|float|real|decimal|numeric|number|bigint|hugeint|integer|int|smallint|tinyint|ubigint|uinteger)/i;

/**
 * The role a record type's hints give a column of `type` (the server's `EntityShapes.hintedRole`): its first Time,
 * Measure or Category hint naming the column, in that order, whose role the type can hold - a Time is a date or
 * timestamp, a Measure a number, a Category neither a decimal number nor a date. Null when none does.
 */
export function hintedRole(
    column: string,
    type: string | null | undefined,
    hints: RecordRoleHints | null | undefined,
): { role: ColumnRole; hint: string } | null {
    if (!hints) return null;
    const t = type ?? 'VARCHAR';
    const date = DATE_TYPE.test(t);
    let h: string | null;
    if (date && (h = matchingHint(hints.time, column))) return { role: 'temporal', hint: h };
    if (NUMBER_TYPE.test(t) && (h = matchingHint(hints.measure, column))) return { role: 'measure', hint: h };
    if (!date && !DECIMAL_TYPE.test(t) && (h = matchingHint(hints.category, column)))
        return { role: 'dimension', hint: h };
    return null;
}

/** "a voice call record", "an invoice", "an SMS record": a record type's label inside a sentence. */
export function recordPhrase(label: string): string {
    const l = label.trim();
    const first = l.split(/\s+/)[0] ?? '';
    const abbreviation = first.length > 1 && first === first.toUpperCase();
    const shown = abbreviation ? l : l.charAt(0).toLowerCase() + l.slice(1);
    const an = abbreviation ? /^[AEFHILMNORSX]/.test(shown) : /^[aeiou]/i.test(shown);
    return `${an ? 'an' : 'a'} ${shown}`;
}
