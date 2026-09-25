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

export interface DashboardConfig {
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

/** Build a {@link Dashboard} from a name + tiles/filter (mirrors `buildWidget`). */
export function buildDashboard(
    name: string,
    tiles: DashboardTile[],
    filter?: ConditionGroup | null,
    exposedFields?: string[],
): Dashboard {
    return { id: name, name, tiles, filter: filter ?? emptyGroup('AND'), exposedFields: exposedFields ?? [] };
}
