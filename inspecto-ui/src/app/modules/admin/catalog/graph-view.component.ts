import {
    AfterViewInit,
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    ElementRef,
    EventEmitter,
    inject,
    Input,
    OnChanges,
    OnDestroy,
    Output,
    SimpleChanges,
    ViewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { GammaConfigService } from '@gamma/services/config';
import { EdgeData, EdgeEvent, ElementDatum, Graph, GraphData, LayoutOptions, NodeData, NodeEvent } from '@antv/g6';
import { G6GraphData, nodeColor, nodeKindLabel, nodeShape } from './catalog-graph';
import { toSvg } from 'app/inspecto/graph';
import { NodeKind } from 'app/inspecto/api';
import { ICON_COLOR_SWATCHES, canvasTheme } from 'app/inspecto/theme/chart-tokens';

/**
 * An analysis-result overlay (Link Analysis Studio): listed nodes/edges render full-strength (or
 * group-coloured), everything else dims. `groups` maps nodeId → a group key (e.g. a community id);
 * each distinct key gets a swatch colour. `null` = no emphasis (all full-strength).
 */
export interface GraphEmphasis {
    nodeIds: string[];
    edgeIds?: string[];
    groups?: Map<string, string>;
}

/** Line pattern for a relationship kind (Link Analysis "Display" menu). */
export type EdgePattern = 'solid' | 'dashed' | 'dotted';

/**
 * Optional presentation overrides (Link Analysis Studio "Display" menu — persisted with a saved
 * view): label visibility plus per-kind colour/shape/pattern/size overrides. Edge kinds match on
 * the base kind (the projection's folded `calls · 2` styles as `calls`). `null` = the built-in
 * defaults.
 */
/**
 * Opt-in canvas plugins and behaviors (Link Analysis's View toolbox, mockup review point 6). Each flag maps to
 * a G6 v5 built-in — no new engine. `hulls` draws one hull per group (community id → member node ids).
 */
export interface GraphViewPlugins {
    minimap?: boolean;
    gridLine?: boolean;
    fisheye?: boolean;
    edgeFilterLens?: boolean;
    edgeBundling?: boolean;
    hulls?: Map<string, string[]> | null;
    /**
     * Draw the SAME community membership as {@link hulls} with G6's bubble-sets renderer instead of its
     * hull renderer — a softer, set-theoretic outline that reads better when communities interleave.
     * Mutually exclusive with `hulls`: both draw one shape per community, so enabling both would paint
     * every community twice. When this is on, bubble sets win and hulls are skipped.
     */
    bubbleSets?: boolean;
    /** Alignment guides while dragging a node — a pure interaction aid, no data required. */
    snapline?: boolean;
    behaviors?: ('brush-select' | 'lasso-select' | 'hover-activate')[];
}

/** The G6 plugin list for a {@link GraphViewPlugins} — pure, so the mapping is unit-testable without a canvas. */
export function buildPluginList(p: GraphViewPlugins | null, swatches: readonly string[]): Record<string, unknown>[] {
    if (!p) return [];
    const out: Record<string, unknown>[] = [];
    if (p.minimap) out.push({ type: 'minimap', key: 'minimap', size: [160, 100], position: 'right-bottom' });
    if (p.gridLine) out.push({ type: 'grid-line', key: 'grid-line', follow: true });
    if (p.fisheye) out.push({ type: 'fisheye', key: 'fisheye', trigger: 'drag', r: 120, scaleRBy: 'wheel' });
    if (p.edgeFilterLens) out.push({ type: 'edge-filter-lens', key: 'edge-filter-lens', trigger: 'drag', r: 90 });
    if (p.edgeBundling) out.push({ type: 'edge-bundling', key: 'edge-bundling', bundleThreshold: 0.6 });
    if (p.snapline) out.push({ type: 'snapline', key: 'snapline' });
    let i = 0;
    for (const [group, members] of p.hulls ?? []) {
        if (members.length < 2) continue;
        const color = swatches[i++ % swatches.length];
        out.push(
            p.bubbleSets
                ? {
                      type: 'bubble-sets',
                      key: `bubble-sets-${group}`,
                      members,
                      labelText: `community ${group}`,
                      fill: color,
                      fillOpacity: 0.1,
                      stroke: color,
                      strokeOpacity: 0.5,
                  }
                : {
                      type: 'hull',
                      key: `hull-${group}`,
                      members,
                      corner: 'smooth',
                      padding: 14,
                      labelText: `community ${group}`,
                      fill: color,
                      fillOpacity: 0.08,
                      stroke: color,
                      strokeOpacity: 0.5,
                  },
        );
    }
    return out;
}

export interface GraphDisplayOptions {
    nodeLabels: boolean;
    edgeLabels: boolean;
    /** node kind → stroke colour (a chart-token swatch). */
    nodeColors: Record<string, string>;
    /** edge (relationship) kind → stroke colour. */
    edgeColors: Record<string, string>;
    /** node kind → G6 shape name (the per-kind "icon"). */
    nodeShapes: Record<string, string>;
    /** edge (relationship) kind → line pattern. */
    edgePatterns: Record<string, EdgePattern>;
    /** edge (relationship) kind → line width in px. */
    edgeSizes: Record<string, number>;
}

/** The node shapes the Display menu offers per kind (value = G6 node type; glyph = the picker face). */
export const GRAPH_NODE_SHAPES: readonly {
    value: string;
    glyph: string;
    label: string;
}[] = [
    { value: 'circle', glyph: '●', label: 'Circle' },
    { value: 'rect', glyph: '■', label: 'Square' },
    { value: 'diamond', glyph: '◆', label: 'Diamond' },
    { value: 'triangle', glyph: '▲', label: 'Triangle' },
    { value: 'star', glyph: '★', label: 'Star' },
    { value: 'hexagon', glyph: '⬢', label: 'Hexagon' },
];

/** The line patterns the Display menu offers per relationship kind. */
export const GRAPH_EDGE_PATTERNS: readonly {
    value: EdgePattern;
    glyph: string;
    label: string;
}[] = [
    { value: 'solid', glyph: '—', label: 'Solid' },
    { value: 'dashed', glyph: '╌', label: 'Dashed' },
    { value: 'dotted', glyph: '⋯', label: 'Dotted' },
];

/** The line widths the Display menu offers per relationship kind. */
export const GRAPH_EDGE_SIZES: readonly { value: number; label: string }[] = [
    { value: 1.5, label: 'S' },
    { value: 3, label: 'M' },
    { value: 5, label: 'L' },
];

/** The G6 `lineDash` array for a pattern; solid (or unset) = a solid stroke (`undefined`). */
export function edgeDash(pattern: EdgePattern | undefined): number[] | undefined {
    return pattern === 'dashed' ? [6, 4] : pattern === 'dotted' ? [1, 3] : undefined;
}

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

/** The G6 layout options for an id; `null`/`dagre` = the default LR layered layout (unchanged). */
export function layoutConfig(id: GraphLayoutId | null): Record<string, unknown> {
    switch (id) {
        case 'grid':
            return { type: 'grid' };
        case 'force':
            return { type: 'd3-force', collide: { radius: 28 } };
        case 'force-cluster':
            return { type: 'force-atlas2', kr: 20, preventOverlap: true };
        case 'radial':
            return { type: 'radial', unitRadius: 120, preventOverlap: true };
        case 'concentric':
            return {
                type: 'concentric',
                sortBy: 'degree',
                preventOverlap: true,
                nodeSize: 40,
            };
        case 'circular':
            return { type: 'circular' };
        case 'mds':
            return { type: 'mds' };
        case 'fruchterman':
            return { type: 'fruchterman', gravity: 5, speed: 5 };
        case 'mindmap':
            return {
                type: 'mindmap',
                direction: 'H',
                getHeight: () => 32,
                getWidth: () => 32,
                getVGap: () => 12,
                getHGap: () => 60,
            };
        case 'org':
            return {
                type: 'compact-box',
                direction: 'TB',
                getHeight: () => 32,
                getWidth: () => 60,
                getVGap: () => 30,
                getHGap: () => 20,
            };
        case 'radial-tree':
            return { type: 'dendrogram', radial: true, nodeSep: 40, rankSep: 120 };
        // The same G6 engine as `radial-tree`, laid out left-to-right instead of around a centre.
        case 'dendrogram':
            return { type: 'dendrogram', radial: false, nodeSep: 36, rankSep: 120 };
        case 'fishbone':
            return { type: 'fishbone', direction: 'LR', hGap: 60, vGap: 40 };
        case 'dagre':
        default:
            return { type: 'antv-dagre', rankdir: 'LR', nodesep: 18, ranksep: 60 };
    }
}

/** The relationship kind an edge styles by — the folded-count suffix (`calls · 2`) stripped. */
export function baseEdgeKind(kind: unknown): string {
    return String(kind ?? '').split(' · ')[0];
}

const esc = (s: unknown): string => String(s ?? '').replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

/**
 * Value signature used by the change classifier (LA-05). The Studio binds `[data]`, `[display]`,
 * `[emphasis]` and `[plugins]` from `computed()`s that yield a FRESH object on every evaluation, so
 * an identity check would classify every cosmetic toggle as a structural change. `Map` and `Set` are
 * spelled out because `JSON.stringify` renders both as `{}` — `emphasis.groups` is a `Map`, and
 * `plugins.hulls` is a `Map` of community member lists.
 */
export function stableKey(value: unknown): string {
    return JSON.stringify(value, (_k, v) =>
        v instanceof Map ? { __map: [...v] } : v instanceof Set ? { __set: [...v] } : v,
    );
}

/**
 * Read-only AntV G6 host for the catalog metadata graph: layered (dagre) layout,
 * pan/zoom, per-kind shapes/outline colours, node-click emits the node id.
 * Recreated when the data or the gamma colour scheme changes.
 */
@Component({
    selector: 'inspecto-graph-view',
    standalone: true,
    template: '<div #host class="relative h-full w-full"></div>',
    // Default: viewport-dynamic height for scrolling pages. `fill` mode instead grows into the
    // remaining space of a flex-column studio (Link Analysis); autoFit:'view' scales the graph.
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: {
        '[class]': `fill ? 'block w-full min-h-0 flex-auto' : 'block w-full min-h-96 h-[62vh]'`,
    },
})
export class GraphViewComponent implements AfterViewInit, OnChanges, OnDestroy {
    @Input({ required: true }) data: G6GraphData | null = null;
    @Input() emphasis: GraphEmphasis | null = null;
    /** Fill the remaining space of a flex-column parent instead of the fixed 62vh page band. */
    @Input() fill = false;
    /** Presentation overrides (labels on/off, per-kind colours); `null` = defaults. */
    @Input() display: GraphDisplayOptions | null = null;
    /** Enable hover tooltips with short node/edge details (Link Analysis). */
    @Input() tooltips = false;
    /** Graph layout; `null` = the default LR layered layout (the 4 existing hosts). */
    @Input() layout: GraphLayoutId | null = null;
    /** Opt-in canvas plugins/behaviors (Link Analysis View toolbox); `null` = the four existing hosts' defaults. */
    @Input() plugins: GraphViewPlugins | null = null;
    @Output() nodeClick = new EventEmitter<string>();
    @Output() edgeClick = new EventEmitter<string>();

    @ViewChild('host') private hostEl!: ElementRef<HTMLDivElement>;
    private graph: Graph | null = null;
    private dark = false;
    private ready = false;
    private resizeObserver: ResizeObserver | null = null;
    private destroyRef = inject(DestroyRef);

    // -- LA-05 incremental update: what the live graph was last built/drawn from --
    private prevLayoutKey = '';
    private prevPluginKey = '';
    private prevDataKey = '';
    private prevStyleKey = '';
    private renderedNodeIds = new Set<string>();
    // Style state, refreshed before every draw and read LIVE by the element mappers below. Held on the
    // instance rather than captured in `create()`'s closure precisely so `draw()` repaints with the
    // current emphasis/display/theme without constructing a new `Graph`.
    private styleFg = '';
    private styleNodeFill = '';
    private styleEdgeColor = '';
    private emNodes: Set<string> | null = null;
    private emEdges: Set<string> | null = null;
    private emGroups: Map<string, string> | null = null;
    private groupSwatch = new Map<string, string>();

    constructor() {
        inject(GammaConfigService)
            .config$.pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((config) => {
                this.dark =
                    config?.scheme === 'dark' ||
                    (config?.scheme === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
                if (!this.ready) return;
                // The scheme is COSMETIC (LA-05): the canvas colours are read live by the element
                // mappers, so a light/dark flip repaints in place instead of tearing the graph down.
                if (this.graph) this.redraw(this.prevStyleKey);
                else this.create();
            });
    }

    ngAfterViewInit(): void {
        this.ready = true;
        this.create();
        // Track container size (collapsible side panes resize the canvas live). Absent in jsdom.
        if (typeof ResizeObserver !== 'undefined') {
            this.resizeObserver = new ResizeObserver(() => this.graph?.resize());
            this.resizeObserver.observe(this.hostEl.nativeElement);
        }
        // G6's tooltip plugin (`[tooltips]`) is meant to hide itself on pointer-leave/pan/zoom, but
        // observed live it can leave its card showing indefinitely — through a pan, a zoom, even
        // closing the node it described — with no further app interaction able to clear it. Rather
        // than depend on the plugin's own (evidently unreliable) hide triggers, force it closed
        // ourselves on the same gestures a real analyst uses to move on: leaving the canvas, panning
        // (pointerdown = drag start), or zooming (wheel). Plain add/removeEventListener, not an
        // AbortSignal — jsdom's AbortSignal isn't a real EventTarget under zone.js, so `{signal}`
        // throws in the component test harness.
        const host = this.hostEl.nativeElement;
        host.addEventListener('pointerleave', this.hideStaleTooltipBound, { passive: true });
        host.addEventListener('pointerdown', this.hideStaleTooltipBound, { passive: true });
        host.addEventListener('wheel', this.hideStaleTooltipBound, { passive: true });
    }

    private readonly hideStaleTooltipBound = (): void => this.hideStaleTooltip();

    private hideStaleTooltip(): void {
        this.hostEl?.nativeElement.querySelectorAll<HTMLElement>('.tooltip').forEach((el) => {
            el.style.visibility = 'hidden';
        });
    }

    /**
     * Classify the change and do the SMALLEST thing that satisfies it (LA-05). A full rebuild is the
     * last resort: G6 fixes the layout, behaviors, plugins and tooltip wiring at construction, so only
     * those force a recreate. A data change is applied onto the live graph, and a purely cosmetic
     * change (emphasis, display overrides, theme) is a redraw that never runs layout — which is what
     * keeps node positions under the analyst's cursor mid-investigation.
     *
     * Every comparison is BY VALUE ({@link stableKey}), never by the reference `changes` carries.
     */
    ngOnChanges(changes: SimpleChanges): void {
        if (!this.ready) return;
        if (!this.graph) {
            this.create();
            return;
        }
        // `tooltips` and `fill` are read once, at construction and on the host class binding.
        if (changes['tooltips'] || changes['fill']) {
            this.create();
            return;
        }
        if (changes['layout'] || changes['plugins']) {
            const layoutKey = stableKey(this.layout ?? null);
            const pluginKey = stableKey(this.plugins ?? null);
            if (layoutKey !== this.prevLayoutKey || pluginKey !== this.prevPluginKey) {
                this.create();
                return;
            }
        }
        if (changes['data']) {
            const dataKey = stableKey(this.data ?? null);
            if (dataKey !== this.prevDataKey) {
                this.applyData(dataKey);
                return;
            }
        }
        if (changes['emphasis'] || changes['display']) {
            const styleKey = this.styleKey();
            if (styleKey !== this.prevStyleKey) this.redraw(styleKey);
        }
    }

    ngOnDestroy(): void {
        this.resizeObserver?.disconnect();
        const host = this.hostEl?.nativeElement;
        host?.removeEventListener('pointerleave', this.hideStaleTooltipBound);
        host?.removeEventListener('pointerdown', this.hideStaleTooltipBound);
        host?.removeEventListener('wheel', this.hideStaleTooltipBound);
        this.destroyGraph();
    }

    /** The rendered canvas as a PNG data-URI (Link Analysis export), or `null` before the first render. */
    exportPng(): Promise<string> | null {
        return this.graph ? this.graph.toDataURL({ type: 'image/png' }) : null;
    }

    /**
     * The rendered graph as a standalone SVG string (Phase F export) — a hand-rolled serializer
     * ({@link toSvg}), not a G6 renderer-mode switch, so it stays decoupled from the canvas renderer.
     * `null` before the first render (mirrors {@link exportPng}).
     */
    exportSvg(): string | null {
        if (!this.graph || !this.data) return null;
        const positions = new Map<string, { x: number; y: number }>();
        for (const n of this.data.nodes) {
            try {
                const p = this.graph.getElementPosition(n.id);
                if (p) positions.set(n.id, { x: p[0], y: p[1] });
            } catch {
                // Not (yet) rendered — skipped, same as a position-less node in toSvg.
            }
        }
        return toSvg(this.data, positions);
    }

    /** Re-fit the whole graph into the viewport (the toolbar's fit-to-screen). */
    fitView(): void {
        void this.graph?.fitView();
    }

    /** Short hover details: node → label/kind/degree, edge → kind + endpoint labels. */
    private tooltipHtml(items: ElementDatum[]): string {
        const d = items[0];
        if (!d) return '';
        const data = (d.data ?? {}) as { label?: string; kind?: string; provenance?: string[] };
        // LA-08: a multi-Dataset projection names the Datasets an element came from.
        const from = data.provenance?.length ? `<br/>from ${esc(data.provenance.join(', '))}` : '';
        if ('source' in d && 'target' in d) {
            const label = (id: unknown): string =>
                (this.data?.nodes.find((n) => n.id === id)?.data.label ?? String(id)) as string;
            return `<b>${esc(data.kind)}</b><br/>${esc(label((d as EdgeData).source))} → ${esc(label((d as EdgeData).target))}${from}`;
        }
        const degree = this.data?.edges.filter((e) => e.source === d.id || e.target === d.id).length ?? 0;
        return `<b>${esc(data.label)}</b><br/>${esc(nodeKindLabel(data.kind ?? ''))} · ${degree} link${degree === 1 ? '' : 's'}${from}`;
    }

    /** Tear the live instance down, sweeping any tooltip card its own cleanup left orphaned. */
    private destroyGraph(): void {
        this.graph?.destroy();
        this.graph = null;
        // `destroy()` is expected to remove the G6 tooltip plugin's card, but observed live it sometimes
        // leaves the DOM node behind (orphaned, no longer owned by any Graph instance) - sweep our own
        // container so a stale tooltip never survives, regardless of why the plugin's cleanup missed it.
        this.hostEl?.nativeElement.querySelectorAll('.tooltip').forEach((el) => el.remove());
    }

    /** Signature of the inputs that affect PAINT ONLY. */
    private styleKey(): string {
        return stableKey([this.emphasis ?? null, this.display ?? null]);
    }

    /** Record what the live graph now reflects, so the next `ngOnChanges` can classify against it. */
    private snapshotKeys(): void {
        this.prevLayoutKey = stableKey(this.layout ?? null);
        this.prevPluginKey = stableKey(this.plugins ?? null);
        this.prevDataKey = stableKey(this.data ?? null);
        this.prevStyleKey = this.styleKey();
        this.renderedNodeIds = new Set((this.data?.nodes ?? []).map((n) => String(n.id)));
    }

    /** Recompute the live style state the element mappers read. Cheap; runs before every draw. */
    private refreshStyleState(): void {
        const { fg, surface, edge } = canvasTheme(this.dark);
        this.styleFg = fg;
        this.styleNodeFill = surface;
        this.styleEdgeColor = edge;
        // Emphasis overlay: swatch per distinct group key; non-listed elements dim.
        const em = this.emphasis;
        this.emNodes = em ? new Set(em.nodeIds) : null;
        this.emEdges = em?.edgeIds ? new Set(em.edgeIds) : null;
        this.emGroups = em?.groups ?? null;
        this.groupSwatch = new Map<string, string>();
        for (const g of this.emGroups?.values() ?? []) {
            if (!this.groupSwatch.has(g))
                this.groupSwatch.set(g, ICON_COLOR_SWATCHES[this.groupSwatch.size % ICON_COLOR_SWATCHES.length]);
        }
    }

    private nodeDim(id: string): boolean {
        return !!this.emNodes && !this.emNodes.has(id) && !this.emGroups?.has(id);
    }

    private nodeColorOf(d: NodeData): string {
        const group = this.emGroups?.get(d.id as string);
        if (group) return this.groupSwatch.get(group)!; // analysis overlay wins over styling
        const kind = (d.data as { kind: NodeKind }).kind;
        return this.display?.nodeColors[kind] ?? (d.data as { color?: string }).color ?? nodeColor(kind);
    }

    private edgeColorOf(d: EdgeData): string {
        return this.display?.edgeColors[baseEdgeKind((d.data as { kind?: string }).kind)] ?? this.styleEdgeColor;
    }

    /**
     * Repaint the live graph from the current emphasis/display/theme. No layout runs and no element is
     * added or removed, so every node keeps the position the analyst last saw it in - the whole point of
     * LA-05. The element mappers read `this.*`, so a plain `draw()` picks the new values up.
     */
    private redraw(styleKey: string): void {
        this.refreshStyleState();
        this.prevStyleKey = styleKey;
        const graph = this.graph;
        if (!graph) return;
        // ⚠ `draw()` ALONE DOES NOT REPAINT. G6 re-evaluates the element style mappers only when the
        // data is (re)set, so a bare `draw()` leaves the old paint on the canvas — measured in the
        // preview: toggling node labels off left every label showing. Re-setting the SAME data object
        // re-runs the mappers and does NOT re-run layout, so positions are untouched.
        graph.setData(this.data as unknown as GraphData);
        void graph.draw();
    }

    /**
     * Apply a data change onto the LIVE graph. Surviving nodes carry their current canvas position onto
     * the incoming data, and layout re-runs only when a node was ADDED - a node that does not exist yet
     * is the only one that needs a position computed for it. A removal (the analyst excluding a branch)
     * or an attribute-only change therefore redraws exactly in place.
     */
    private applyData(dataKey: string): void {
        const graph = this.graph;
        if (!graph) {
            this.create();
            return;
        }
        if (!this.data?.nodes.length) {
            this.destroyGraph();
            this.snapshotKeys();
            return;
        }
        const next = this.data.nodes.map((n) => String(n.id));
        const added = next.some((id) => !this.renderedNodeIds.has(id));
        const seeded = { nodes: this.data.nodes.map((n) => this.seedPosition(n)), edges: this.data.edges };
        graph.setData(seeded as unknown as GraphData);
        this.refreshStyleState();
        this.prevDataKey = dataKey;
        this.prevStyleKey = this.styleKey();
        this.renderedNodeIds = new Set(next);
        void (added ? graph.render() : graph.draw());
    }

    /**
     * The node with its current canvas position pinned onto it, or unchanged if it has none yet.
     * Returns the loose G6 shape rather than the app's `G6Node`, because `style.x`/`style.y` is a G6
     * wire concern the catalog's own node type deliberately does not model.
     */
    private seedPosition(node: G6GraphData['nodes'][number]): Record<string, unknown> {
        let point: ArrayLike<number> | null;
        try {
            point = this.graph?.getElementPosition(String(node.id)) ?? null;
        } catch {
            return node as unknown as Record<string, unknown>; // not yet rendered - let the layout place it
        }
        if (!point || !Number.isFinite(point[0]) || !Number.isFinite(point[1])) {
            return node as unknown as Record<string, unknown>;
        }
        const style = (node as { style?: Record<string, unknown> }).style ?? {};
        return { ...node, style: { ...style, x: point[0], y: point[1] } };
    }

    /** Build a brand-new G6 instance. The last resort - see {@link ngOnChanges}. */
    private create(): void {
        this.destroyGraph();
        if (!this.data?.nodes.length) {
            this.snapshotKeys();
            return;
        }
        this.refreshStyleState();
        const kindOf = (d: NodeData): NodeKind => (d.data as { kind: NodeKind }).kind;
        const iconOf = (d: NodeData): string | undefined => (d.data as { iconSrc?: string }).iconSrc;
        const graph = new Graph({
            container: this.hostEl.nativeElement,
            data: this.data as unknown as GraphData,
            autoFit: 'view',
            // No element/layout animation (operator, 2026-09-20): on a few-hundred-node Link Analysis canvas the
            // enter/update tweens cost frames on every re-render (filter, emphasis, layout switch) for no
            // analytical value. Static draw — the same graph, just immediately.
            animation: false,
            // See the pipeline editor canvas: G6's default [0.01, 10] lets a flick of the wheel land on
            // specks or on one node filling the viewport. Floored much lower than the editor's because
            // this host is shared with Link Analysis and the catalog metadata graph, where a
            // several-hundred-node `autoFit: 'view'` legitimately needs to zoom well out.
            zoomRange: [0.05, 4],
            node: {
                // Icon tile (rounded rect + glyph) when the data carries a resolved icon (pipeline views);
                // otherwise the per-kind shape (the catalog metadata graph).
                type: (d) => (iconOf(d) ? 'rect' : (this.display?.nodeShapes[kindOf(d)] ?? nodeShape(kindOf(d)))),
                style: {
                    size: (d) => (iconOf(d) ? [46, 34] : 32),
                    radius: 8,
                    fill: () => this.styleNodeFill,
                    stroke: (d) => this.nodeColorOf(d),
                    lineWidth: 2,
                    // A stranded node (Link Analysis: excluded by a pushed-down predicate, kept so the analyst
                    // sees what the narrower question removed) renders dimmed with a dashed outline.
                    lineDash: (d) => ((d.data as { missing?: boolean }).missing ? [4, 3] : undefined),
                    iconSrc: (d) => iconOf(d),
                    iconWidth: 22,
                    iconHeight: 22,
                    // `label` is G6's own on/off switch for the label shape. A `labelText` mapper that
                    // returns `undefined` does NOT clear a label: G6 reads that as "no change" and the
                    // previous text stays on the canvas, so turning labels off left them showing.
                    label: () => this.display?.nodeLabels !== false,
                    labelText: (d): string => (d.data as { label: string }).label,
                    labelFill: () => this.styleFg,
                    labelFontSize: 11,
                    labelPlacement: 'bottom',
                    cursor: 'pointer',
                    opacity: (d) =>
                        this.nodeDim(d.id as string) ? 0.25 : (d.data as { missing?: boolean }).missing ? 0.45 : 1,
                    labelOpacity: (d) => (this.nodeDim(d.id as string) ? 0.35 : 1),
                },
            },
            edge: {
                type: 'line',
                style: {
                    stroke: (d) => this.edgeColorOf(d),
                    opacity: (d) => (this.emEdges ? (this.emEdges.has(d.id as string) ? 1 : 0.2) : 1),
                    endArrow: true,
                    // A dry-run's provenance rows (PIPELINE-DRYRUN-1) paint their edge dashed regardless of
                    // the Display-menu pattern override, so a simulated run stays visually distinct.
                    lineDash: (d) =>
                        (d.data as { simulated?: boolean }).simulated
                            ? edgeDash('dashed')
                            : edgeDash(this.display?.edgePatterns[baseEdgeKind((d.data as { kind?: string }).kind)]),
                    // Per-kind size override wins; else the optional data-plane weight (T22 provenance
                    // overlay) scales the line width log-style; else the default (catalog/combined views).
                    lineWidth: (d) => {
                        const override = this.display?.edgeSizes[baseEdgeKind((d.data as { kind?: string }).kind)];
                        if (override) return override;
                        const w = (d.data as { weight?: number }).weight;
                        return w && w > 0 ? Math.min(12, 1.5 + Math.log2(w + 1)) : 1.5;
                    },
                    label: () => this.display?.edgeLabels !== false,
                    labelText: (d): string => (d.data as { kind: string }).kind,
                    labelFill: () => this.styleFg,
                    labelFontSize: 9,
                    labelBackground: false,
                },
            },
            layout: layoutConfig(this.layout) as LayoutOptions,
            behaviors: [
                'drag-canvas',
                { type: 'zoom-canvas', minZoom: 0.1, maxZoom: 4 },
                'drag-element',
                ...(this.plugins?.behaviors ?? []),
            ],
            plugins: [
                ...(this.tooltips
                    ? [
                          {
                              type: 'tooltip',
                              trigger: 'hover',
                              getContent: async (_e: unknown, items: ElementDatum[]) => this.tooltipHtml(items),
                          },
                      ]
                    : []),
                ...(buildPluginList(this.plugins, ICON_COLOR_SWATCHES) as unknown as never[]),
            ],
        });
        graph.on(NodeEvent.CLICK, (e) => {
            const id = (e as unknown as { target?: { id?: string } }).target?.id;
            if (id) this.nodeClick.emit(id);
        });
        graph.on(EdgeEvent.CLICK, (e) => {
            const id = (e as unknown as { target?: { id?: string } }).target?.id;
            if (id) this.edgeClick.emit(id);
        });
        void graph.render();
        this.graph = graph;
        this.snapshotKeys();
    }
}
