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
