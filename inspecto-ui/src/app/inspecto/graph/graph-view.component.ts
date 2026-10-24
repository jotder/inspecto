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
import {
    CanvasEvent,
    EdgeData,
    EdgeEvent,
    ElementDatum,
    Graph,
    GraphData,
    LayoutOptions,
    NodeData,
    NodeEvent,
} from '@antv/g6';
import { G6GraphData, entityTypeIcon, nodeColor, nodeKindLabel, nodeShape } from './catalog-graph';
import { toSvg } from './graph-export';
import type { G6Node } from './graph-types';
import { NodeRule, nodeRuleColor } from './node-rules';
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

/**
 * The OPT-IN pointer model of one host (Link Analysis, operator 2026-10-15). The host is shared with the catalog
 * and pipeline graphs, so `null` (the default) keeps every other screen exactly as it was: a drag on the empty
 * canvas pans, a click is delivered at once, nothing re-fits on a data change.
 */
export interface GraphViewInteraction {
    /** `false`: a drag on the empty canvas does NOT pan. The Minimap, the wheel zoom and {@link GraphViewComponent.panBy} still move the view. */
    panByDrag?: boolean;
    /** `true`: a plain drag on the empty canvas draws a rubber-band box that selects the nodes inside it (emitted as `selectionChange`). */
    boxSelect?: boolean;
    /** `true`: a double click emits `nodeDblClick`, and the single click is delivered after {@link NODE_DBLCLICK_DELAY_MS} unless a second click cancels it. */
    doubleClick?: boolean;
    /** `true`: the view re-fits after a DATA change (an expand, a fold, a hide), never after a hover, a selection or a restyle. */
    fitOnDataChange?: boolean;
    /** `true`: the view re-fits when the canvas is RESIZED (a side panel opens or closes, the window changes), once it settles. */
    fitOnResize?: boolean;
}

/** How long a single click waits for a possible second one when {@link GraphViewInteraction.doubleClick} is on. */
export const NODE_DBLCLICK_DELAY_MS = 230;

/** A canvas resize smaller than this (either side, in px) does not re-fit the view. */
export const RESIZE_REFIT_MIN_PX = 8;

/** G6's selection state: the box select sets it, the `selection` input is mirrored into it, and `drag-element` groups by it. */
export const SELECTED_STATE = 'selected';

/**
 * The G6 behaviour list for a pointer model - pure, so the wiring is unit-testable without a canvas. `drag-canvas`
 * is dropped for `panByDrag: false`; `boxSelect` adds a plain-drag `brush-select` (G6 documents that it replaces
 * `drag-canvas`, so the two never coexist) that starts only on the empty canvas, and replaces the shift+drag
 * `brush-select` the View toolbox may also have asked for.
 */
export function buildBehaviors(
    interaction: GraphViewInteraction | null,
    plugins: GraphViewPlugins | null,
    onBrush: (nodeIds: string[]) => void,
    brushStyle: Record<string, unknown> = {},
): unknown[] {
    const box = interaction?.boxSelect === true;
    const extra = (plugins?.behaviors ?? []).filter((b) => !(box && b === 'brush-select'));
    return [
        ...(interaction?.panByDrag === false ? [] : ['drag-canvas']),
        { type: 'zoom-canvas', minZoom: 0.1, maxZoom: 4 },
        'drag-element',
        ...(box
            ? [
                  {
                      type: 'brush-select',
                      trigger: ['drag'],
                      enableElements: ['node'],
                      // G6's own `selected` state: `drag-element` moves EVERY node in this state together when one of them
                      // is dragged, which is what an analyst expects after boxing a group.
                      state: SELECTED_STATE,
                      mode: 'default',
                      enable: (e: { targetType?: string }) => e.targetType === 'canvas',
                      style: brushStyle,
                      onSelect: (states: Record<string, unknown>) => onBrush(Object.keys(states)),
                  },
              ]
            : []),
        ...extra,
    ];
}

/** The host's accessible name: the graph's size, so the canvas is not an unnamed blank to a screen reader. */
export function graphSummaryLabel(data: G6GraphData | null, hint = ''): string {
    const n = data?.nodes?.length ?? 0;
    const e = data?.edges?.length ?? 0;
    return `Graph, ${n} ${n === 1 ? 'node' : 'nodes'}, ${e} ${e === 1 ? 'link' : 'links'}${hint ? '. ' + hint : ''}`;
}

/**
 * G6 creates its canvas layers with `tabindex="1"` — a positive tabindex that pulls them ahead of the
 * page in the keyboard order (axe `tabindex`, LA-A11Y-AUDIT-1). Reset the first to `0` (still focusable for
 * G6's keyboard behaviours, in document order) and the rest to `-1`: the layers are stacked on one spot, so
 * live (Tab order driven 2026-10-05) four `0`s were four identical tab stops on one graph.
 */
export function demotePositiveTabindex(root: HTMLElement): void {
    let first = true;
    root.querySelectorAll<HTMLElement>('[tabindex]').forEach((el) => {
        if (el.tabIndex <= 0) return;
        el.setAttribute('tabindex', first ? '0' : '-1');
        first = false;
    });
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
    /**
     * Show EVERY link label even on a dense graph. Absent/false = the density rule
     * ({@link edgeLabelsHiddenByDensity}): above {@link DENSE_EDGE_LABEL_CAP} edges, labels show only on
     * hover/click of the edge or an endpoint.
     */
    allEdgeLabels?: boolean;
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
    /** Conditional formatting: ordered rules colouring the nodes that satisfy an attribute predicate (first match wins). */
    nodeRules?: NodeRule[];
    /**
     * Node diameter in px (an icon tile scales with it). It never follows degree or any attribute: the analyst sets it.
     * Absent = {@link HOST_NODE_SIZE}, what the catalog and pipeline graphs have always drawn.
     */
    nodeSize?: number;
    /**
     * How far apart the layout spreads nodes, as a multiplier on its distances (1 = the layout's own spacing).
     * Absent = 1. A change re-runs the layout.
     */
    layoutSpacing?: number;
}

/** The node size every graph drew before {@link GraphDisplayOptions.nodeSize} existed. */
export const HOST_NODE_SIZE = 32;
/** The node size Link Analysis starts at: small, so a busy graph stays readable. */
export const GRAPH_DEFAULT_NODE_SIZE = 20;
/** The layout spacing Link Analysis starts at: wide, because small nodes on a busy graph should not touch. */
export const GRAPH_DEFAULT_LAYOUT_SPACING = 4;

/** The node sizes the Display menu offers. */
export const GRAPH_NODE_SIZES: readonly { value: number; label: string }[] = [
    { value: 12, label: 'Tiny' },
    { value: 20, label: 'Small' },
    { value: 28, label: 'Medium' },
    { value: 40, label: 'Large' },
];

/** The layout spacings the Display menu offers (a multiplier on the layout's own distances). */
export const GRAPH_LAYOUT_SPACINGS: readonly { value: number; label: string }[] = [
    { value: 1, label: 'Compact' },
    { value: 2, label: 'Roomy' },
    { value: 3, label: 'Spacious' },
    { value: 4, label: 'Wide' },
    { value: 5, label: 'Widest' },
];

/**
 * Above this many edges, link labels are hidden until hovered/clicked. 20: at the canvas' 9px label size a
 * link label is ~60px wide, and past ~20 links on an auto-fitted canvas they start colliding with each other
 * and with node labels (the telco demo ring, 18 nodes / 42 links, was unreadable); a small investigation
 * (a subscriber, its devices and cells) stays fully labelled. The legend still names every link type.
 */
export const DENSE_EDGE_LABEL_CAP = 20;

/** Whether the density rule hides link labels by default (still revealed on hover/click). */
export function edgeLabelsHiddenByDensity(display: GraphDisplayOptions | null | undefined, edgeCount: number): boolean {
    return display?.edgeLabels !== false && !display?.allEdgeLabels && edgeCount > DENSE_EDGE_LABEL_CAP;
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

/**
 * The G6 layout options for an id; `null`/`dagre` = the default LR layered layout.
 *
 * <p>`nodeSize` and `spacing` (both from {@link GraphDisplayOptions}) scale the layout's distances: with neither given
 * (spacing 1, the host's own node size) every layout is byte-for-byte what it was before they existed, so the catalog
 * and pipeline graphs are untouched. `grid`, `circular` and `mds` carry no spacing parameter in G6 and ignore both.
 */
export function layoutConfig(
    id: GraphLayoutId | null,
    opts: { nodeSize?: number; spacing?: number } = {},
): Record<string, unknown> {
    const node = opts.nodeSize ?? HOST_NODE_SIZE;
    const k = opts.spacing ?? 1;
    const scaled = (n: number): number => Math.round(n * k);
    switch (id) {
        case 'grid':
            return { type: 'grid' };
        case 'force': {
            // d3's own link distance (30) and charge (-30) are what `k` multiplies; collide keeps a gap round each node.
            const base = { type: 'd3-force', collide: { radius: Math.round(node * 0.875) } };
            return k === 1 ? base : { ...base, link: { distance: 30 * k }, manyBody: { strength: -30 * k } };
        }
        case 'force-cluster':
            return { type: 'force-atlas2', kr: scaled(20), preventOverlap: true };
        case 'radial':
            return { type: 'radial', unitRadius: scaled(120), preventOverlap: true };
        case 'concentric':
            return {
                type: 'concentric',
                sortBy: 'degree',
                preventOverlap: true,
                nodeSize: Math.round(node * 1.25),
                ...(k === 1 ? {} : { nodeSpacing: scaled(20) }),
            };
        case 'circular':
            return { type: 'circular' };
        case 'mds':
            return { type: 'mds' };
        case 'fruchterman':
            return { type: 'fruchterman', gravity: 5 / k, speed: 5 };
        case 'mindmap':
            return {
                type: 'mindmap',
                direction: 'H',
                getHeight: () => node,
                getWidth: () => node,
                getVGap: () => scaled(12),
                getHGap: () => scaled(60),
            };
        case 'org':
            return {
                type: 'compact-box',
                direction: 'TB',
                getHeight: () => node,
                getWidth: () => Math.round(node * 1.875),
                getVGap: () => scaled(30),
                getHGap: () => scaled(20),
            };
        case 'radial-tree':
            return { type: 'dendrogram', radial: true, nodeSep: scaled(40), rankSep: scaled(120) };
        // The same G6 engine as `radial-tree`, laid out left-to-right instead of around a centre.
        case 'dendrogram':
            return { type: 'dendrogram', radial: false, nodeSep: scaled(36), rankSep: scaled(120) };
        case 'fishbone':
            return { type: 'fishbone', direction: 'LR', hGap: scaled(60), vGap: scaled(40) };
        case 'dagre':
        default:
            return { type: 'antv-dagre', rankdir: 'LR', nodesep: scaled(18), ranksep: scaled(60) };
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
        // The canvas has no accessible content (LA-A11Y-AUDIT-1, WCAG 1.1.1): a named figure gives a
        // screen reader the graph's size. The textual route to the rows stays the host page's job.
        role: 'figure',
        '[attr.aria-label]': 'summaryLabel',
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
    /**
     * Draw an Entity Type's built-in glyph as an icon tile (Link Analysis). A per-kind shape the view overrides wins
     * over the icon; a type with no glyph keeps its plain shape. Read at construction, like `tooltips`.
     */
    @Input() entityIcons = false;
    /** Appended to the figure's accessible name: where the text alternative of the canvas is (Link Analysis list view). */
    @Input() textHint = '';
    /** Graph layout; `null` = the default LR layered layout (the 4 existing hosts). */
    @Input() layout: GraphLayoutId | null = null;
    /** Opt-in canvas plugins/behaviors (Link Analysis View toolbox); `null` = the four existing hosts' defaults. */
    @Input() plugins: GraphViewPlugins | null = null;
    /** Opt-in pointer model (Link Analysis); `null` = the other hosts' behaviour. Fixed at construction, like `plugins`. */
    @Input() interaction: GraphViewInteraction | null = null;
    /** The page's multi-selection (node ids): painted with a halo and a thicker outline. A repaint, never a rebuild. */
    @Input() selection: string[] | null = null;
    @Output() nodeClick = new EventEmitter<string>();
    @Output() edgeClick = new EventEmitter<string>();
    /** A double click on a node (only with `interaction.doubleClick`). */
    @Output() nodeDblClick = new EventEmitter<string>();
    /** The nodes a box select enclosed, or `[]` when a click on the empty canvas cleared them (only with `interaction.boxSelect`). */
    @Output() selectionChange = new EventEmitter<string[]>();
    /** A right click on a node (Link Analysis "Exclude…"): its id and the pointer's viewport position. The browser menu is suppressed. */
    @Output() nodeContextMenu = new EventEmitter<{ id: string; x: number; y: number }>();

    @ViewChild('host') private hostEl!: ElementRef<HTMLDivElement>;

    get summaryLabel(): string {
        return graphSummaryLabel(this.data, this.textHint);
    }
    private graph: Graph | null = null;
    /** Edges whose label is revealed by hover (transient) / click (pinned until the next click). */
    private hoverLabelled: string[] = [];
    private pinnedLabelled: string[] = [];
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
    /** The page's multi-selection, read LIVE by the node mappers (a halo + a thicker outline). */
    private selSet = new Set<string>();
    /** A single click waiting to see whether a second one makes it a double click ({@link GraphViewInteraction.doubleClick}). */
    private clickTimer: ReturnType<typeof setTimeout> | null = null;
    /** When a box select last finished: the click that ends a drag must not be read as 'click the empty canvas, clear'. */
    private brushedAt = 0;

    /** What is fixed at construction besides the layout: the plugins AND the pointer model. */
    private pluginKey(): string {
        return stableKey([this.plugins ?? null, this.interaction ?? null]);
    }

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
            this.resizeObserver = new ResizeObserver((entries) => {
                // A zero box is a canvas taken out of the page (a kept-alive page while another shows), not a real
                // size: shrinking to it and back would re-fit and throw the analyst's zoom away on their return.
                const last = entries[entries.length - 1]?.contentRect;
                if (last && (last.width === 0 || last.height === 0)) return;
                this.graph?.resize();
                // G6's resize keeps the old zoom and offset, so a canvas that grew or shrank leaves the graph off to a side:
                // re-fit once the size settles (a drag of a panel edge fires many events), when the pointer model asks for it.
                // A change of a few pixels (a scrollbar, a rounding) is not worth throwing the analyst's zoom away for.
                const box = entries[entries.length - 1]?.contentRect;
                const moved =
                    !box ||
                    Math.abs(box.width - this.lastSize[0]) > RESIZE_REFIT_MIN_PX ||
                    Math.abs(box.height - this.lastSize[1]) > RESIZE_REFIT_MIN_PX;
                if (box) this.lastSize = [box.width, box.height];
                if (moved && this.interaction?.fitOnResize) this.scheduleFit();
            });
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
        if (changes['tooltips'] || changes['fill'] || changes['entityIcons']) {
            this.create();
            return;
        }
        if (changes['layout'] || changes['plugins'] || changes['interaction'] || changes['display']) {
            const layoutKey = this.layoutKey();
            const pluginKey = this.pluginKey();
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
        if (changes['emphasis'] || changes['display'] || changes['selection']) {
            const styleKey = this.styleKey();
            if (styleKey !== this.prevStyleKey) this.redraw(styleKey);
        }
    }

    ngOnDestroy(): void {
        this.resizeObserver?.disconnect();
        if (this.fitTimer !== null) clearTimeout(this.fitTimer);
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

    /**
     * Move the view by a screen offset - the keyboard equivalent of dragging the canvas (WCAG 2.5.7), for a host
     * whose drag no longer pans. A no-op until a graph is drawn.
     */
    panBy(dx: number, dy: number): void {
        void this.graph?.translateBy([dx, dy], false);
    }

    /** Zoom the view by a ratio around its centre (the keyboard equivalent of the wheel). A no-op until a graph is drawn. */
    zoomBy(ratio: number): void {
        void this.graph?.zoomBy(ratio, false);
    }

    /**
     * Pan the viewport so these nodes sit in its centre (a ranking row was picked). A no-op until a graph is
     * drawn; an id the drawn graph does not hold (a node folded into a super-node) is ignored, never an error.
     */
    centerOn(ids: string[]): void {
        const g = this.graph;
        if (!g || !ids.length) return;
        void g.focusElement(ids, false).catch(() => undefined);
    }

    /**
     * The edges whose label the density rule reveals for a hovered/clicked element: the edge itself, or
     * every edge touching a node. Empty when the rule is not hiding labels.
     */
    edgesToReveal(id: string | undefined, kind: 'node' | 'edge'): string[] {
        if (!id || !this.data || !edgeLabelsHiddenByDensity(this.display, this.data.edges.length)) return [];
        if (kind === 'edge') return [id];
        return this.data.edges.filter((e) => e.source === id || e.target === id).map((e) => String(e.id));
    }

    /** Apply the `labelled` state: hover replaces the transient set, click replaces the pinned set. */
    private revealEdgeLabels(id: string | undefined, kind: 'node' | 'edge', pin: boolean): void {
        const graph = this.graph;
        if (!graph) return;
        const next = this.edgesToReveal(id, kind);
        const before = new Set([...this.hoverLabelled, ...this.pinnedLabelled]);
        if (pin) this.pinnedLabelled = next;
        else this.hoverLabelled = next;
        const after = new Set([...this.hoverLabelled, ...this.pinnedLabelled]);
        const states: Record<string, string[]> = {};
        try {
            // Keep any other state (hover-activate's `active`) — only `labelled` is toggled here.
            const others = (e: string): string[] => graph.getElementState(e).filter((st) => st !== 'labelled');
            for (const e of before) if (!after.has(e)) states[e] = others(e);
            for (const e of after) if (!before.has(e)) states[e] = [...others(e), 'labelled'];
            if (Object.keys(states).length) void graph.setElementState(states);
        } catch {
            // An id no longer on the canvas (data swapped under the pointer) — nothing to reveal.
        }
    }

    /** Short hover details: node → label/kind/degree, edge → kind + endpoint labels. */
    private tooltipHtml(items: ElementDatum[]): string {
        const d = items[0];
        if (!d) return '';
        const data = (d.data ?? {}) as {
            label?: string;
            kind?: string;
            provenance?: string[];
            highConnectivity?: boolean;
            contacts?: number;
            unexpanded?: boolean;
        };
        // LA-08: a multi-Dataset projection names the Datasets an element came from.
        const from = data.provenance?.length ? `<br/>from ${esc(data.provenance.join(', '))}` : '';
        if ('source' in d && 'target' in d) {
            const label = (id: unknown): string =>
                (this.data?.nodes.find((n) => n.id === id)?.data.label ?? String(id)) as string;
            return `<b>${esc(data.kind)}</b><br/>${esc(label((d as EdgeData).source))} → ${esc(label((d as EdgeData).target))}${from}`;
        }
        const degree = this.data?.edges.filter((e) => e.source === d.id || e.target === d.id).length ?? 0;
        // The ringed (high-connectivity) node names its sealed contact count when served — the legend's "shown, not expanded".
        const hub = data.highConnectivity
            ? `<br/>High connectivity${typeof data.contacts === 'number' ? ` — ${data.contacts.toLocaleString('en')} contact${data.contacts === 1 ? '' : 's'}` : ''}`
            : '';
        const open = data.unexpanded ? '<br/>Not expanded yet' : '';
        return `<b>${esc(data.label)}</b><br/>${esc(nodeKindLabel(data.kind ?? ''))} · ${degree} link${degree === 1 ? '' : 's'}${hub}${open}${from}`;
    }

    /** Tear the live instance down, sweeping any tooltip card its own cleanup left orphaned. */
    private fitTimer: ReturnType<typeof setTimeout> | null = null;
    /** The canvas size the last resize event reported (width, height), to tell a real resize from a few-pixel wobble. */
    private lastSize: [number, number] = [0, 0];

    /** One re-fit after a burst of resize events has gone quiet. */
    private scheduleFit(): void {
        if (this.fitTimer !== null) clearTimeout(this.fitTimer);
        this.fitTimer = setTimeout(() => {
            this.fitTimer = null;
            void this.graph?.fitView();
        }, 150);
    }

    private cancelPendingClick(): void {
        if (this.clickTimer !== null) clearTimeout(this.clickTimer);
        this.clickTimer = null;
    }

    private destroyGraph(): void {
        this.cancelPendingClick();
        this.graph?.destroy();
        this.graph = null;
        // `destroy()` is expected to remove the G6 tooltip plugin's card, but observed live it sometimes
        // leaves the DOM node behind (orphaned, no longer owned by any Graph instance) - sweep our own
        // container so a stale tooltip never survives, regardless of why the plugin's cleanup missed it.
        this.hostEl?.nativeElement.querySelectorAll('.tooltip').forEach((el) => el.remove());
    }

    /**
     * Signature of what the LAYOUT depends on: the engine, and the node size and spacing it scales its distances by.
     * Those are fixed at construction, so a change in any of them rebuilds the graph and re-runs the layout.
     */
    private layoutKey(): string {
        return stableKey([this.layout ?? null, this.display?.nodeSize ?? null, this.display?.layoutSpacing ?? null]);
    }

    /** Signature of the inputs that affect PAINT ONLY. */
    private styleKey(): string {
        return stableKey([this.emphasis ?? null, this.display ?? null, this.selection ?? null]);
    }

    /** Record what the live graph now reflects, so the next `ngOnChanges` can classify against it. */
    private snapshotKeys(): void {
        this.prevLayoutKey = this.layoutKey();
        this.prevPluginKey = this.pluginKey();
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
        this.selSet = new Set(this.selection ?? []);
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
        const ruled = nodeRuleColor(this.display?.nodeRules, d.data as G6Node['data']);
        if (ruled) return ruled; // conditional formatting outranks the per-kind colour
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
        void graph.draw().then(() => this.syncSelectionState());
    }

    /** The node ids this host last put in {@link SELECTED_STATE}, so the next sync only touches what changed. */
    private selectedStateIds = new Set<string>();

    /**
     * Mirror the page's selection into G6's `selected` element state. The box select sets it itself, but Select all, a
     * clear, a selection that lost nodes to a hide, and a node-list pick all arrive through the `selection` input; without
     * this, dragging one selected node would move only that node (`drag-element` groups by the state, not by our input).
     */
    private syncSelectionState(): void {
        const graph = this.graph;
        if (!graph || !this.interaction?.boxSelect) return;
        const want = this.selSet;
        const touched = new Set([...this.selectedStateIds, ...want]);
        this.selectedStateIds = new Set(want);
        if (!touched.size) return;
        const states: Record<string, string[]> = {};
        for (const id of touched) states[id] = want.has(id) ? [SELECTED_STATE] : [];
        void graph.setElementState(states, false).catch(() => undefined); // an id the graph no longer holds is not an error
    }

    /**
     * Apply a data change onto the LIVE graph. Surviving nodes carry their current canvas position onto
     * the incoming data, and layout re-runs only when a node was ADDED - a node that does not exist yet
     * is the only one that needs a position computed for it. A removal (the analyst excluding a branch)
     * or an attribute-only change therefore redraws exactly in place.
     */
    private applyData(dataKey: string): void {
        this.hoverLabelled = [];
        this.pinnedLabelled = [];
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
        const painted = added ? graph.render() : graph.draw();
        // The pointer model asked for a graph that stays fully in view: re-fit once the new data is on the canvas.
        // Only HERE (a data change), never in `redraw`, so a hover, a selection or a restyle never yanks the view.
        void painted.then(() => {
            this.syncSelectionState();
            if (this.interaction?.fitOnDataChange) void graph.fitView();
        });
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
        this.hoverLabelled = [];
        this.pinnedLabelled = [];
        this.destroyGraph();
        if (!this.data?.nodes.length) {
            this.snapshotKeys();
            return;
        }
        this.refreshStyleState();
        const kindOf = (d: NodeData): NodeKind => (d.data as { kind: NodeKind }).kind;
        const iconOf = (d: NodeData): string | undefined => {
            const iconSrc = (d.data as { iconSrc?: string }).iconSrc;
            if (iconSrc || !this.entityIcons) return iconSrc;
            const kind = kindOf(d);
            return entityTypeIcon(kind, this.nodeColorOf(d), this.display?.nodeShapes);
        };
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
                    // Never scaled by degree or value: the analyst's size (Display menu), else the host's own 32.
                    size: (d) => {
                        const n = this.display?.nodeSize ?? HOST_NODE_SIZE;
                        return iconOf(d) ? [Math.round(n * 1.44), Math.round(n * 1.06)] : n;
                    },
                    radius: 8,
                    fill: () => this.styleNodeFill,
                    stroke: (d) => this.nodeColorOf(d),
                    // A box-selected node (the page's `selection`) wears a thicker outline and a halo.
                    lineWidth: (d) => (this.selSet.has(d.id as string) ? 4 : 2),
                    // A stranded node (Link Analysis: excluded by a pushed-down predicate, kept so the analyst
                    // sees what the narrower question removed) renders dimmed with a dashed outline.
                    lineDash: (d) => {
                        const nd = d.data as { missing?: boolean; unexpanded?: boolean };
                        // LA-UNEXPANDED-MARKER-1: a Working Set entity no expand has followed yet wears a dotted outline.
                        return nd.missing ? [4, 3] : nd.unexpanded ? [2, 3] : undefined;
                    },
                    // A high-connectivity entity (Link Analysis hub suppression) wears a wide halo in its own colour.
                    halo: (d) =>
                        !!(d.data as { highConnectivity?: boolean }).highConnectivity ||
                        this.selSet.has(d.id as string),
                    haloStroke: (d) => this.nodeColorOf(d),
                    haloLineWidth: (d) => (this.selSet.has(d.id as string) ? 16 : 10),
                    haloStrokeOpacity: (d) => (this.selSet.has(d.id as string) ? 0.6 : 0.35),
                    // LA-GROW-EXCLUDE-1: a busy entity a Grow held wears a "held" badge over its ring.
                    badges: (d) =>
                        (d.data as { held?: boolean }).held
                            ? [
                                  {
                                      text: 'held',
                                      placement: 'right-top',
                                      fontSize: 9,
                                      fill: this.styleNodeFill,
                                      backgroundFill: this.nodeColorOf(d),
                                  },
                              ]
                            : [],
                    iconSrc: (d) => iconOf(d),
                    iconWidth: Math.max(
                        10,
                        Math.round(((this.display?.nodeSize ?? HOST_NODE_SIZE) * 22) / HOST_NODE_SIZE),
                    ),
                    iconHeight: Math.max(
                        10,
                        Math.round(((this.display?.nodeSize ?? HOST_NODE_SIZE) * 22) / HOST_NODE_SIZE),
                    ),
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
                    label: () =>
                        this.display?.edgeLabels !== false &&
                        !edgeLabelsHiddenByDensity(this.display, this.data?.edges.length ?? 0),
                    labelText: (d): string => (d.data as { kind: string }).kind,
                    labelFill: () => this.styleFg,
                    labelFontSize: 9,
                    labelBackground: false,
                },
                // The density rule's reveal: set on hover/click by revealEdgeLabels(), over the base style.
                state: { labelled: { label: true } },
            },
            layout: layoutConfig(this.layout, {
                nodeSize: this.display?.nodeSize,
                spacing: this.display?.layoutSpacing,
            }) as LayoutOptions,
            behaviors: buildBehaviors(
                this.interaction,
                this.plugins,
                (ids) => {
                    this.brushedAt = Date.now();
                    this.selectionChange.emit(ids);
                },
                // The rubber band reads the theme's foreground, so it shows in both colour schemes without a literal colour.
                { fill: this.styleFg, fillOpacity: 0.08, stroke: this.styleFg, lineWidth: 1, lineDash: [4, 3] },
            ) as never[],
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
        const targetId = (e: unknown): string | undefined => (e as { target?: { id?: string } }).target?.id;
        graph.on(NodeEvent.CLICK, (e) => {
            const id = targetId(e);
            if (this.interaction?.doubleClick) {
                // A double click is two clicks and a dblclick: hold the single click back so it does not also fire.
                this.cancelPendingClick();
                if (id) this.clickTimer = setTimeout(() => this.nodeClick.emit(id), NODE_DBLCLICK_DELAY_MS);
            } else if (id) this.nodeClick.emit(id);
            this.revealEdgeLabels(id, 'node', true);
        });
        graph.on(NodeEvent.DBLCLICK, (e) => {
            if (!this.interaction?.doubleClick) return;
            this.cancelPendingClick();
            const id = targetId(e);
            if (id) this.nodeDblClick.emit(id);
        });
        graph.on(NodeEvent.CONTEXT_MENU, (e) => {
            const ev = e as unknown as {
                client?: { x: number; y: number };
                nativeEvent?: MouseEvent;
                preventDefault?: () => void;
            };
            ev.preventDefault?.();
            ev.nativeEvent?.preventDefault?.();
            const id = targetId(e);
            if (id)
                this.nodeContextMenu.emit({
                    id,
                    x: ev.client?.x ?? ev.nativeEvent?.clientX ?? 0,
                    y: ev.client?.y ?? ev.nativeEvent?.clientY ?? 0,
                });
        });
        graph.on(CanvasEvent.CLICK, () => {
            // The click that ends a box select must not clear what the box just selected.
            if (this.interaction?.boxSelect && Date.now() - this.brushedAt > 250 && this.selSet.size > 0) {
                this.selectionChange.emit([]);
            }
        });
        graph.on(EdgeEvent.CLICK, (e) => {
            const id = targetId(e);
            if (id) this.edgeClick.emit(id);
            this.revealEdgeLabels(id, 'edge', true);
        });
        graph.on(NodeEvent.POINTER_ENTER, (e) => this.revealEdgeLabels(targetId(e), 'node', false));
        graph.on(EdgeEvent.POINTER_ENTER, (e) => this.revealEdgeLabels(targetId(e), 'edge', false));
        graph.on(NodeEvent.POINTER_LEAVE, () => this.revealEdgeLabels(undefined, 'node', false));
        graph.on(EdgeEvent.POINTER_LEAVE, () => this.revealEdgeLabels(undefined, 'edge', false));
        const host = this.hostEl.nativeElement;
        void graph.render().then(() => {
            demotePositiveTabindex(host);
            this.selectedStateIds = new Set(); // a fresh instance carries no states
            this.syncSelectionState();
        });
        this.graph = graph;
        this.snapshotKeys();
    }
}
