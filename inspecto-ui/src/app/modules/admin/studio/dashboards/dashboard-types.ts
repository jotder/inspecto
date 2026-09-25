import { ConditionGroup, emptyGroup } from 'app/inspecto/query';
import { TileSpan } from 'app/inspecto/viz/dashboard-grid';

/**
 * Studio **Dashboard** model — a composite of saved widgets laid out in a grid, with an optional dashboard-level
 * **cross-filter** (a Query Core {@link ConditionGroup}) injected into every tile's query. Stored as a
 * `dashboard` component; its config is the {@link DashboardConfig}. The grid is the dashboard's `layout` wiring
 * in component-model terms. Mirrors `widget-types.ts`.
 */

/** One placed tile: which saved widget + how many of the grid's FOUR columns it takes (UIE-3, `inspecto/viz/dashboard-grid`).
 *  Order in the array = layout order. */
export interface DashboardTile {
    widgetId: string;
    span: TileSpan;
}

/**
 * UIE-5 — what a Dashboard's viewer header says beyond its title. All optional; an absent key is not written.
 * `asOf` is a calendar date (`YYYY-MM-DD`) — the day the figures describe, NOT a timestamp, so it never shifts
 * with the viewer's time zone. `illustrative` marks a STAGED page whose figures are synthetic (absorbs DW-02).
 */
export interface DashboardHeader {
    /** One line naming the question the page answers. */
    description?: string;
    /** The day the figures are as of (`YYYY-MM-DD`). */
    asOf?: string;
    /** The figures are illustrative (synthetic), not computed from live data. */
    illustrative?: boolean;
}

/** The shape `asOf` must have — a calendar date. */
export const AS_OF_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** `2026-09-23` → `23 Sep 2026`, read as a calendar date (no zone shift); a value that is not a real date is shown as is. */
export function asOfLabel(asOf: string): string {
    if (!AS_OF_PATTERN.test(asOf)) return asOf;
    const [y, m, d] = asOf.split('-').map(Number);
    const date = new Date(Date.UTC(y, m - 1, d));
    if (date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) return asOf;
    return `${d} ${MONTHS[m - 1]} ${y}`;
}

/** Only the header keys that say something — a blank or non-string description/as-of, or a flag that is not
 *  `true`, is omitted. Tolerant of raw stored content (the component route censuses keys, not types). */
export function compactHeader(h: DashboardHeader): DashboardHeader {
    const description = typeof h.description === 'string' ? h.description.trim() : '';
    const asOf = typeof h.asOf === 'string' ? h.asOf.trim() : '';
    return {
        ...(description ? { description } : {}),
        ...(asOf ? { asOf } : {}),
        ...(h.illustrative === true ? { illustrative: true } : {}),
    };
}

export interface DashboardConfig extends DashboardHeader {
    tiles: DashboardTile[];
    /** Cross-filter applied to every tile's QuerySpec (reuses the Query Core filter). */
    filter?: ConditionGroup | null;
    /** Columns exposed to viewers as quick filters (the dashboard filter bar). */
    exposedFields?: string[];
}

export interface Dashboard extends DashboardConfig {
    id: string;
    name: string;
}

/** Build a {@link Dashboard} from a name + tiles/filter (mirrors `buildWidget`); blank header fields are dropped. */
export function buildDashboard(
    name: string,
    tiles: DashboardTile[],
    filter?: ConditionGroup | null,
    exposedFields?: string[],
    header: DashboardHeader = {},
): Dashboard {
    return {
        id: name,
        name,
        tiles,
        filter: filter ?? emptyGroup('AND'),
        exposedFields: exposedFields ?? [],
        ...compactHeader(header),
    };
}
