import { GammaNavigationItem } from '@gamma/components/navigation';
import { defaultNavigation } from 'app/core/navigation/navigation-data';
import { AccessGrant, AccessNode } from '../api/access.service';
import type { Lens } from '../api/lens.service';

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
            label: 'Operate runs (trigger / pause / resume / reprocess)',
        },
    ],
    requirements: [
        {
            id: 'requirements.triage',
            kind: 'action',
            capability: 'canTriageRequirements',
            label: 'Triage requirements (accept / reject / deliver)',
        },
    ],
    alerts: [
        {
            id: 'alerts.author',
            kind: 'action',
            capability: 'canAuthorAlertRules',
            label: 'Author alert rules',
        },
    ],
    catalog: [
        {
            id: 'exchange.offer',
            kind: 'action',
            capability: 'canOfferDatasets',
            label: 'Offer datasets and widgets for sharing',
        },
        {
            id: 'exchange.approve',
            kind: 'action',
            capability: 'canApproveShares',
            label: 'Decide share requests (approve / deny / revoke)',
        },
        {
            id: 'exchange.request',
            kind: 'action',
            capability: 'canRequestShares',
            label: 'Request access to another space’s offer',
        },
    ],
    settings: [
        {
            id: 'access.configure',
            kind: 'action',
            capability: 'canConfigureAccess',
            label: 'Configure lens access',
        },
        {
            id: 'menus.curate',
            kind: 'action',
            capability: 'canCurateMenus',
            label: 'Curate the space menu tree',
        },
        {
            // Grafted here since Connections moved out of Workbench into a Settings section (2026-07-28).
            // ⚠ An action node is keyed by the NAV id it hangs under, so a pane that stops being a nav
            // item takes its capability out of the catalog with it — `canOnboardConnections` would
            // silently become unconfigurable. Re-home the action whenever a pane moves.
            id: 'connections.onboard',
            kind: 'action',
            capability: 'canOnboardConnections',
            label: 'Onboard connections (create / edit / delete)',
        },
    ],
};

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
 * catalog — dividers, Menu-Builder custom menus — always stay: unknown = allow, so an empty or
 * missing profile leaves the sidebar byte-identical.
 */
export function filterNavByAccess(
    items: GammaNavigationItem[],
    grants: Record<string, AccessGrant>,
    idx: CatalogIndex,
): GammaNavigationItem[] {
    if (!Object.keys(grants).length) return items;
    const keep = (item: GammaNavigationItem): GammaNavigationItem | null => {
        if (item.id && idx.byId.has(item.id) && resolveGrant(item.id, grants, idx).effective === 'deny') {
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
