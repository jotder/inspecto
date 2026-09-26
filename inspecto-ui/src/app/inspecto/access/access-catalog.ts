import { GammaNavigationItem } from '@gamma/components/navigation';
import { defaultNavigation } from 'app/core/navigation/navigation-data';
import { AccessGrant, AccessNode } from '../api/access.service';
import type { Lens } from '../api/lens.service';
import { menuTreeToNav } from '../menu/menu-nav';
import { loadMenuTrees } from '../menu/menu-persist';
import type { MenuNode } from '../menu/menu-types';

/**
 * Access Catalog derivation + grant resolution (framework-free — design
 * `docs/superpower/lens-access-config-design.md` §4). The UI is the source of truth for what exists
 * on screen: the catalog tree is derived from the platform navigation (menu groups → panes) with the
 * **action nodes** below grafted in, then snapshotted to the backend on save. New nav items appear in
 * the catalog automatically; a new gateable functionality is one entry in {@link ACCESS_ACTION_NODES}.
 */

/**
 * The functionality (action) nodes, grafted under their owning nav node — the honest list: exactly
 * one node per Capability that really gates something today, never one per pane sharing a capability
 * (denying it on one pane while the same capability drives three would lie).
 */
export const ACCESS_ACTION_NODES: Record<string, AccessNode[]> = {
    'workbench-group': [
        {
            id: 'workbench.author',
            kind: 'action',
            capability: 'canAuthorWorkbench',
            label: 'Author Workbench content (create / edit / delete)',
        },
    ],
    runs: [
        {
            id: 'runs.operate',
            kind: 'action',
            capability: 'canOperateRuns',
            label: 'Operate Runs (trigger / pause / resume / reprocess)',
        },
    ],
    requirements: [
        {
            id: 'requirements.triage',
            kind: 'action',
            capability: 'canTriageRequirements',
            label: 'Triage Requirements (accept / reject / deliver)',
        },
    ],
    alerts: [
        {
            id: 'alerts.author',
            kind: 'action',
            capability: 'canAuthorAlertRules',
            label: 'Author Alert Rules',
        },
    ],
    cases: [
        {
            // Hung under Case Manager, where its one client-side gate lives (the Findings-fields editor, D1).
            // The server gates more on it (opening an Incident, promote, Investigations); denying it here
            // only hides the affordance — the server stays the boundary.
            id: 'incidents.manage',
            kind: 'action',
            capability: 'canManageIncidents',
            label: 'Manage Incidents and Cases (author Findings fields)',
        },
    ],
    catalog: [
        {
            id: 'exchange.offer',
            kind: 'action',
            capability: 'canOfferDatasets',
            label: 'Offer Datasets and Widgets for sharing',
        },
        {
            id: 'exchange.approve',
            kind: 'action',
            capability: 'canApproveShares',
            label: 'Decide Share requests (approve / deny / revoke)',
        },
        {
            id: 'exchange.request',
            kind: 'action',
            capability: 'canRequestShares',
            label: 'Request access to another Space’s offer',
        },
    ],
    settings: [
        {
            id: 'access.configure',
            kind: 'action',
            capability: 'canConfigureAccess',
            label: 'Configure Lens access',
        },
        {
            id: 'menus.curate',
            kind: 'action',
            capability: 'canCurateMenus',
            label: 'Curate the Space menu tree',
        },
        {
            // Grafted here since Connections moved out of Workbench into a Settings section (2026-07-28).
            // ⚠ An action node is keyed by the NAV id it hangs under, so a pane that stops being a nav
            // item takes its capability out of the catalog with it — `canOnboardConnections` would
            // silently become unconfigurable. Re-home the action whenever a pane moves.
            id: 'connections.onboard',
            kind: 'action',
            capability: 'canOnboardConnections',
            label: 'Onboard Connections (create / edit / delete)',
        },
        {
            // The coarse Space-governance grant (2026-09-15): the backend gates Space update/delete and
            // the agent-governance routes on it. Hung under Settings, which is where both surfaces live.
            id: 'space.administer',
            kind: 'action',
            capability: 'canAdminister',
            label: 'Administer the Space (settings / agent governance)',
        },
    ],
};

/**
 * A Capability id's human label, for surfaces that list grants (Home's "Your access here", R2-16): its
 * {@link ACCESS_ACTION_NODES} label without the trailing "(…)" detail, so the catalog stays the one label
 * source. A Capability no action node carries (a backend-only gate such as `canRevealLinkEntities`) is
 * humanised from its id — "Reveal link entities" — so a new grant never renders blank.
 */
export function capabilityLabel(capability: string): string {
    for (const nodes of Object.values(ACCESS_ACTION_NODES)) {
        const node = nodes.find((n) => n.capability === capability);
        if (node) return node.label.replace(/\s*\([^)]*\)$/, '');
    }
    const words = capability
        .replace(/^can(?=[A-Z])/, '')
        .split(/(?=[A-Z])/)
        .join(' ')
        .toLowerCase();
    return words ? words.charAt(0).toUpperCase() + words.slice(1) : capability;
}

/**
 * Map a navigation tree into catalog nodes: `collapsable` → `menu`, `basic` → `pane`, dividers
 * skipped. Menu-Builder custom menus never pass through here (they're per-Space curation, not
 * platform surface — the callers pass the static platform nav).
 */
export function deriveAccessCatalog(nav: GammaNavigationItem[]): AccessNode[] {
    const nodes: AccessNode[] = [];
    for (const item of nav) {
        if (item.type === 'divider' || !item.id || !item.title) continue;
        const node: AccessNode = {
            id: item.id,
            label: item.title,
            kind: item.type === 'collapsable' ? 'menu' : 'pane',
        };
        if (item.icon) node.icon = item.icon;
        if (item.link) node.link = item.link;
        const children = [
            ...(item.children?.length ? deriveAccessCatalog(item.children) : []),
            ...(ACCESS_ACTION_NODES[item.id] ?? []),
        ];
        if (children.length) node.children = children;
        nodes.push(node);
    }
    return nodes;
}

/** The catalog over the platform navigation (`core/navigation/navigation-data.ts` — the canonical
 *  nav config, served client-side by NavigationService since the M4 Fuse-shell re-plumb). */
export function deriveDefaultAccessCatalog(): AccessNode[] {
    return deriveAccessCatalog(defaultNavigation);
}

/** Catalog id of the synthetic parent every Menu-Builder custom menu hangs under — deny it and the whole
 *  per-Space custom navigation is hidden for that lens; deny a group or an entry below it for less. */
export const CUSTOM_MENUS_NODE_ID = 'custom-menus';

/** The active Space's Menu-Builder nodes, from the mirror NavigationService hydrates at start-up. */
export function activeSpaceMenuNodes(): MenuNode[] {
    const space = (typeof localStorage !== 'undefined' && localStorage.getItem('inspecto.currentSpace')) || 'default';
    return loadMenuTrees()[space]?.nodes ?? [];
}

/**
 * The Space's full catalog: the platform navigation plus, when the Space has a custom Menu tree, a
 * **Custom menus** node carrying it. The custom nodes keep the sidebar's own `menu-<id>` ids (via
 * {@link menuTreeToNav}), so {@link filterNavByAccess} matches them with no translation. Grants on a
 * custom node live in the same per-lens profile as every other node; the backend only enforces action
 * nodes, so these are UI-side visibility, exactly like platform menus/panes.
 */
export function deriveSpaceAccessCatalog(customMenus: MenuNode[] = activeSpaceMenuNodes()): AccessNode[] {
    const platform = deriveDefaultAccessCatalog();
    const custom = deriveAccessCatalog(menuTreeToNav(customMenus));
    if (!custom.length) return platform;
    return [
        ...platform,
        {
            id: CUSTOM_MENUS_NODE_ID,
            label: 'Custom menus',
            kind: 'menu',
            icon: 'heroicons_outline:bars-3',
            children: custom,
        },
    ];
}

export interface CatalogIndex {
    byId: Map<string, AccessNode>;
    parentOf: Map<string, string | null>;
}

export function indexCatalog(nodes: AccessNode[]): CatalogIndex {
    const byId = new Map<string, AccessNode>();
    const parentOf = new Map<string, string | null>();
    const walk = (ns: AccessNode[], parent: string | null): void => {
        for (const n of ns) {
            byId.set(n.id, n);
            parentOf.set(n.id, parent);
            if (n.children?.length) walk(n.children, n.id);
        }
    };
    walk(nodes, null);
    return { byId, parentOf };
}

/** A node's resolved grant: what applies (`effective`), what is set on the node itself (`explicit`,
 *  null = inheriting), and where the applied value comes from (null = the allow root default). */
export interface GrantState {
    effective: AccessGrant;
    explicit: AccessGrant | null;
    sourceId: string | null;
    sourceLabel: string | null;
}

/** Walk self → root; the first explicit grant wins; no explicit ancestor = allow (today's behavior). */
export function resolveGrant(nodeId: string, grants: Record<string, AccessGrant>, idx: CatalogIndex): GrantState {
    const explicit = grants[nodeId] ?? null;
    let cursor: string | null = nodeId;
    while (cursor !== null) {
        const g = grants[cursor];
        if (g) {
            return { effective: g, explicit, sourceId: cursor, sourceLabel: idx.byId.get(cursor)?.label ?? cursor };
        }
        cursor = idx.parentOf.get(cursor) ?? null;
    }
    return { effective: 'allow', explicit: null, sourceId: null, sourceLabel: null };
}

/**
 * Drop navigation items (with their subtree) whose effective grant is deny. Items unknown to the
 * catalog — dividers, custom menus absent from the catalog passed in — always stay: unknown = allow, so an empty or
 * missing profile leaves the sidebar byte-identical.
 */
export function filterNavByAccess(
    items: GammaNavigationItem[],
    grants: Record<string, AccessGrant>,
    idx: CatalogIndex,
): GammaNavigationItem[] {
    if (!Object.keys(grants).length) return items;
    const keep = (item: GammaNavigationItem): GammaNavigationItem | null => {
        // A Favorites shortcut (`fav-<id>`) follows the grant of the custom entry it points at
        // (`menu-<id>`) — otherwise a denied entry would stay reachable through its shortcut.
        const id = item.id?.startsWith('fav-') ? `menu-${item.id.slice(4)}` : item.id;
        if (id && idx.byId.has(id) && resolveGrant(id, grants, idx).effective === 'deny') {
            return null;
        }
        if (!item.children?.length) return item;
        return { ...item, children: item.children.map(keep).filter((c): c is GammaNavigationItem => c !== null) };
    };
    return items.map(keep).filter((i): i is GammaNavigationItem => i !== null);
}

/**
 * Per-lens DEFAULT sidebar scope (frontend-review finding #10): each lens shows only the groups/panes
 * it exists for, so switching lenses focuses the nav even when no Access Profile was ever saved.
 * This is presentation-only pruning — ids are never deleted or renamed (the catalog derives from this
 * same tree), and a saved Access Profile still denies further on top. Anything not listed here stays
 * visible in every lens; group entries prune their children independently of the group itself.
 *
 *   business → read/consume surfaces: KPIs & reports, alerts/incidents triage, dashboards, data browsing.
 *   builder  → authoring surfaces: workbench + studio + catalog + requirements (the default lens keeps everything).
 *   ops      → run-the-platform surfaces: operations monitoring, run operation, system maintenance.
 */
export const LENS_NAV_SCOPE: Record<Lens, string[]> = {
    business: [
        'business-group',
        'kpi-reports',
        'requirements',
        'operations-group',
        'alerts',
        'incidents',
        'cases',
        'platform-group',
        'studio-group',
        'studio-dashboards',
        'studio-viz-library',
        'catalog-group',
        'catalog',
        'studio-datasets',
        'data-browser',
        'assist',
    ],
    builder: [
        'business-group',
        'requirements',
        'reconciliation',
        'operations-group',
        'alerts',
        'incidents',
        'approvals',
        'learning',
        'tags',
        'platform-group',
        'workbench-group',
        'pipelines',
        'runs',
        'jobs',
        'expectations',
        'decision-rules',
        'components',
        'enrichment',
        'collectors',
        'studio-group',
        'studio-queries',
        'studio-viz-library',
        'studio-dashboards',
        'studio-templates',
        'studio-link-analysis',
        'menus',
        'studio-geo-map',
        'catalog-group',
        'catalog',
        'catalog-onboard',
        'studio-datasets',
        'data-browser',
        'settings',
        'assist',
    ],
    ops: [
        'operations-group',
        'op-overview',
        'processing-status',
        'events',
        'audit',
        'diagnoses',
        'alerts',
        'incidents',
        'approvals',
        'autonomy',
        'learning',
        'cases',
        'tags',
        'platform-group',
        'workbench-group',
        'runs',
        'jobs',
        'expectations',
        'collectors',
        'catalog-group',
        'catalog',
        'studio-datasets',
        'system-maintenance-group',
        'maintenance-overview',
        'assist',
    ],
};

/**
 * The lens-default counterpart of {@link filterNavByAccess}: keeps only the subtrees whose id is
 * scoped to `lens` (or unscoped, i.e. not mentioned anywhere). A parent kept while ALL its children
 * are pruned is dropped too, so empty groups never render. Unknown/custom ids always stay.
 */
export function filterNavByLens(items: GammaNavigationItem[], lens: Lens): GammaNavigationItem[] {
    const scope = SCOPE_BY_LENS[lens];
    const keep = (item: GammaNavigationItem): GammaNavigationItem | null => {
        const inScope = !item.id || !ALL_SCOPED_IDS.has(item.id) || scope.has(item.id);
        if (!inScope) return null;
        if (!item.children?.length) return item;
        const children = item.children.map(keep).filter((c): c is GammaNavigationItem => c !== null);
        if (!children.length && item.children.length) return null; // group with nothing left inside
        return { ...item, children };
    };
    return items.map(keep).filter((i): i is GammaNavigationItem => i !== null);
}

/** Membership sets built once at module load — `filterNavByLens` runs per nav item on every lens
 * switch, access-profile load and nav-search keystroke, so neither is rebuilt or linearly scanned. */
const SCOPE_BY_LENS: Record<Lens, ReadonlySet<string>> = {
    business: new Set(LENS_NAV_SCOPE.business),
    builder: new Set(LENS_NAV_SCOPE.builder),
    ops: new Set(LENS_NAV_SCOPE.ops),
};

/** Every id any lens names — the complement defines "unscoped" (visible everywhere). */
const ALL_SCOPED_IDS: ReadonlySet<string> = new Set(Object.values(LENS_NAV_SCOPE).flat());
