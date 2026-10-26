// The graph layouts as PLAIN DATA, apart from `graph-view.component.ts`. Anything that only needs the layout ids, their labels or the
// capped-layout fallback (the Link Analysis limits, the landing page's saved-view reader) imports this file: importing the component
// pulls @antv/g6, @antv/layout, html2canvas and CodeMirror into the entry chunk of the standalone app (2026-10-26: la-app `main` grew from
// 0.4 MB to 2.2 MB and broke its size budget). `graph-view.component.ts` re-exports everything here, so existing imports keep working.

/** A selectable graph layout — the requested names mapped onto G6 v5 built-in layout types. */
export type GraphLayoutId =
    | 'dagre'
    | 'grid'
    | 'force'
    | 'force-cluster'
    | 'radial'
    | 'concentric'
    | 'circular'
    | 'mds'
    | 'fruchterman'
    | 'mindmap'
    | 'org'
    | 'radial-tree'
    | 'dendrogram'
    | 'fishbone';

/** What is drawn in place of a layout that is over its node cap: cheap (milliseconds at 3,000 nodes) and ring-like. */
export const CAPPED_LAYOUT_FALLBACK: GraphLayoutId = 'concentric';

/** The layouts the Link Analysis "Layout" toolbox offers; `tree` ones need a tree/forest graph. */
export const GRAPH_LAYOUTS: readonly {
    id: GraphLayoutId;
    label: string;
    tree: boolean;
}[] = [
    { id: 'dagre', label: 'Layered', tree: false },
    { id: 'grid', label: 'Grid', tree: false },
    { id: 'force', label: 'Force', tree: false },
    { id: 'force-cluster', label: 'Clustering force', tree: false },
    { id: 'radial', label: 'Radial', tree: false },
    { id: 'concentric', label: 'Degree ordered', tree: false },
    { id: 'circular', label: 'Circular', tree: false },
    { id: 'mds', label: 'Information density', tree: false },
    { id: 'fruchterman', label: 'Fruchterman', tree: false },
    { id: 'mindmap', label: 'Mind map', tree: true },
    { id: 'org', label: 'Organization chart', tree: true },
    { id: 'radial-tree', label: 'Radial tree', tree: true },
    // Hierarchical like the three above, so they carry the same tree/forest gate: G6's tree layouts
    // read a root and a child order, and handed a cyclic graph they lay out only what they can reach.
    { id: 'dendrogram', label: 'Dendrogram', tree: true },
    { id: 'fishbone', label: 'Fishbone', tree: true },
];
