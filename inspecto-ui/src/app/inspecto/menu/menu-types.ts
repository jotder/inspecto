/**
 * Menu Builder model — a user-curated navigation tree, shared per Space. Framework-free (no Angular) so
 * the tree logic is unit-testable in isolation; the Angular {@link MenuService} holds it in a signal and
 * the nav layer converts `MenuNode` → `GammaNavigationItem`. See docs/superpower/menu-builder-plan.md.
 *
 * A node is either a **group** (a menu / sub-menu — carries `children`) or a **leaf** (a Menu item bound
 * to a library Component via `binding`). Sibling order is array position (no separate order field).
 */

/** The library artifact kinds a Menu item can open (GLOSSARY: Widget / Dashboard / saved View). */
export type PlaceableKind = 'dashboard' | 'widget' | 'link-analysis-view' | 'geo-map-view';

/** What a leaf opens: a reference to a Component of the given kind. */
export interface MenuBinding {
    kind: PlaceableKind;
    componentId: string;
}

/**
 * UIE-7: a leaf that opens an in-app screen (`/cases`, `/alerts?status=open`) instead of a library Component,
 * so business users reach platform panes from the Space menu. The server refuses anything but an absolute
 * in-app path ({@link routeError} is the client mirror of `NavMenus.checkRoute`).
 */
export interface RouteBinding {
    kind: 'route';
    route: string;
}

/** Longest route the server accepts (`NavMenus.MAX_ROUTE_LENGTH`). */
export const MAX_ROUTE_LENGTH = 512;

/** Why `route` is not a safe in-app route, or null when it is — the mirror of the server's 422 walk, pinned to it by `contracts/menu-route.contract.json`. */
export function routeError(route: string): string | null {
    if (!route) return 'A route is required.';
    if (route.length > MAX_ROUTE_LENGTH) return `Keep the route under ${MAX_ROUTE_LENGTH} characters.`;
    if (!route.startsWith('/')) return 'Start the route with / (for example /cases).';
    if (route.startsWith('//') || route.includes('\\')) return 'The route must stay inside this app.';
    // eslint-disable-next-line no-control-regex -- refusing whitespace and control characters is the point
    if (/[\u0000- \u007f]/.test(route)) return 'The route must not contain spaces.';
    const path = route.split(/[?#]/)[0];
    // A `:` only matters in the path (a scheme leads it); the query and fragment may carry one (operator 2026-09-25).
    if (path.includes(':')) return 'The route must not carry a scheme.';
    if (path.split('/').some((seg) => seg === '.' || seg === '..'))
        return 'The route must not contain . or .. segments.';
    return null;
}

/** Narrow a leaf's binding to an in-app route. */
export function isRouteBinding(b: MenuBinding | RouteBinding | undefined): b is RouteBinding {
    return b?.kind === 'route';
}

/** The library-Component half of a binding (what `MenuArtifactComponent` renders); undefined for a route. */
export function artifactBinding(b: MenuBinding | RouteBinding | undefined): MenuBinding | undefined {
    return b && !isRouteBinding(b) ? b : undefined;
}

export interface MenuNode {
    id: string;
    title: string;
    /** gamma svg icon name, e.g. 'heroicons_outline:chart-bar'. */
    icon?: string;
    /** Present on a group node (menu / sub-menu). */
    children?: MenuNode[];
    /** Present on a leaf node (opens an artifact or an in-app route). Mutually exclusive with `children`. */
    binding?: MenuBinding | RouteBinding;
}

/** The whole per-Space tree. `version` guards the persisted schema for future migrations. */
export interface MenuTree {
    space: string;
    version: 1;
    /** UIE-7: the leaf opened instead of the platform Home when the app opens at `/` (the Space landing). */
    landing?: string;
    nodes: MenuNode[];
}

export const MENU_TREE_VERSION = 1 as const;

export function emptyTree(space: string): MenuTree {
    return { space, version: MENU_TREE_VERSION, nodes: [] };
}

/** A leaf opens an artifact; a group holds children. */
export function isLeaf(node: MenuNode): boolean {
    return node.binding != null;
}
