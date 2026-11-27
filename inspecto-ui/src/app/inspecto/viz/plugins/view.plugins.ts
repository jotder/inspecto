import { QuerySpec, VizPlugin, VizProps } from '../viz-types';

/**
 * The **view-bound** plugins (Phase 4 of docs/superpower/geo-map-analysis-plan.md): a widget of these types
 * renders a saved investigation view — a `geo-map-view` / `link-analysis-view` Component — through the shared
 * studio hosts, instead of compiling a dataset + channel mapping. The query pipeline is inert (`controls` is
 * empty and callers skip it via `meta.viewKind`); the render host resolves `componentKey` to a lazily-loaded
 * read-only wrapper (see `viz-components.ts`).
 */

/** Inert spec — a view-bound plugin's data comes from its saved view's own GeoSource/GraphSource query. */
const NO_QUERY = (): QuerySpec => ({ datasetId: '', sourceName: '', groupBy: [], measures: [] });
const NO_PROPS = (): VizProps => ({ labels: [], series: [] });

export const GEO_MAP_PLUGIN: VizPlugin = {
    meta: { type: 'geo-map', label: 'Geo map', icon: 'heroicons_outline:map', fit: {}, viewKind: 'geo-map-view' },
    controls: [],
    buildQuery: NO_QUERY,
    transformProps: NO_PROPS,
    render: { kind: 'component', componentKey: 'geo-map-view' },
};

export const LINK_ANALYSIS_PLUGIN: VizPlugin = {
    meta: {
        type: 'link-analysis',
        label: 'Link analysis',
        icon: 'heroicons_outline:share',
        fit: {},
        viewKind: 'link-analysis-view',
    },
    controls: [],
    buildQuery: NO_QUERY,
    transformProps: NO_PROPS,
    render: { kind: 'component', componentKey: 'link-analysis-view' },
};

/** DAT-7 P3: a dashboard tile renders a saved Reconciliation's Board read-only (compact Δ% tree). */
export const RECONCILIATION_PLUGIN: VizPlugin = {
    meta: {
        type: 'reconciliation',
        label: 'Reconciliation',
        icon: 'heroicons_outline:scale',
        fit: {},
        viewKind: 'reconciliation',
    },
    controls: [],
    buildQuery: NO_QUERY,
    transformProps: NO_PROPS,
    render: { kind: 'component', componentKey: 'reconciliation' },
};

/**
 * LA-21: a dashboard tile over an **Investigation's Working Set** (decision D-E6). `viewKind` is `investigation`: the
 * binding is the Investigation id (`viewId`) plus the widget's `workingSet` {relation, mode, pin}. It is saved from the
 * Link Analysis Investigation panel — the one place that knows the head to pin — so Explore does not offer it.
 */
export const WORKING_SET_VIEW_KIND = 'investigation';

export const WORKING_SET_PLUGIN: VizPlugin = {
    meta: {
        type: 'working-set',
        label: 'Working Set',
        icon: 'heroicons_outline:finger-print',
        fit: {},
        viewKind: WORKING_SET_VIEW_KIND,
    },
    controls: [],
    buildQuery: NO_QUERY,
    transformProps: NO_PROPS,
    render: { kind: 'component', componentKey: 'working-set' },
};

/**
 * BI-INSIGHTS-1: a dashboard tile listing one Insight Profile's latest Insights (`GET /insights?profile=`). The binding
 * (`viewId`) is the Profile id; the tile reads what the viewer may see of its Insights Dataset.
 */
export const INSIGHT_FEED_PLUGIN: VizPlugin = {
    meta: {
        type: 'insight-feed',
        label: 'Insight feed',
        icon: 'heroicons_outline:light-bulb',
        fit: {},
        viewKind: 'insight-profile',
    },
    controls: [],
    buildQuery: NO_QUERY,
    transformProps: NO_PROPS,
    render: { kind: 'component', componentKey: 'insight-feed' },
};

/**
 * BI-INSIGHTS-1 P4 BR-3: the Dashboard Brief - one Insight Profile's top Insights as plain sentences
 * (`GET /insights/brief`), with a Narrate button when AI Assist is installed. Bound like the feed (`viewId` = the Profile).
 */
export const INSIGHT_BRIEF_PLUGIN: VizPlugin = {
    meta: {
        type: 'insight-brief',
        label: 'Insight brief',
        icon: 'heroicons_outline:document-text',
        fit: {},
        viewKind: 'insight-profile',
    },
    controls: [],
    buildQuery: NO_QUERY,
    transformProps: NO_PROPS,
    render: { kind: 'component', componentKey: 'insight-brief' },
};
