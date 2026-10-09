import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { G6GraphData } from './catalog-graph';
import {
    DENSE_EDGE_LABEL_CAP,
    GRAPH_LAYOUTS,
    GraphDisplayOptions,
    GraphViewComponent,
    buildPluginList,
    demotePositiveTabindex,
    graphSummaryLabel,
    edgeLabelsHiddenByDensity,
    layoutConfig,
    stableKey,
} from './graph-view.component';

/** G6 can't instantiate in jsdom (per the angular-ui skill) — only the empty/no-data path is testable
 *  here; `rebuild()` returns before touching the canvas when there are no nodes. */
function create(data: GraphViewComponent['data'] = null) {
    TestBed.configureTestingModule({
        imports: [GraphViewComponent],
        providers: [{ provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
    });
    const fixture = TestBed.createComponent(GraphViewComponent);
    fixture.componentRef.setInput('data', data);
    fixture.detectChanges();
    return fixture;
}

describe('GraphViewComponent', () => {
    it('renders the empty (no data) host with no a11y violations', async () => {
        const fixture = create(null);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders an empty (zero-node) graph with no a11y violations', async () => {
        const fixture = create({ nodes: [], edges: [] });
        await expectNoA11yViolations(fixture.nativeElement);
    });
    it('buildPluginList maps each View flag to one G6 built-in and one hull per multi-member community', () => {
        expect(buildPluginList(null, ['swatch-a'])).toEqual([]);
        const list = buildPluginList(
            {
                minimap: true,
                gridLine: true,
                fisheye: true,
                edgeFilterLens: true,
                edgeBundling: true,
                hulls: new Map([
                    ['0', ['a', 'b', 'c']],
                    ['1', ['d']], // a singleton gets no hull
                ]),
            },
            ['swatch-a', 'swatch-b'],
        );
        expect(list.map((p) => p['type'])).toEqual([
            'minimap',
            'grid-line',
            'fisheye',
            'edge-filter-lens',
            'edge-bundling',
            'hull',
        ]);
        expect(list[5]).toMatchObject({ key: 'hull-0', members: ['a', 'b', 'c'], fill: 'swatch-a' });
    });

    // ── LA-09: View toolbox completions ──
    it('buildPluginList maps the snapline flag to its G6 built-in', () => {
        expect(buildPluginList({ snapline: true }, []).map((p) => p['type'])).toEqual(['snapline']);
    });

    it('bubble sets REPLACE hulls rather than drawing a second shape per community', () => {
        const hulls = new Map([['0', ['a', 'b', 'c']]]);
        const asHulls = buildPluginList({ hulls }, ['swatch-a']);
        expect(asHulls.map((p) => p['type'])).toEqual(['hull']);

        const asSets = buildPluginList({ hulls, bubbleSets: true }, ['swatch-a']);
        // One shape per community either way - both on must NOT paint the community twice.
        expect(asSets.map((p) => p['type'])).toEqual(['bubble-sets']);
        expect(asSets[0]).toMatchObject({ key: 'bubble-sets-0', members: ['a', 'b', 'c'], fill: 'swatch-a' });
    });

    it('bubble sets honour the same singleton rule as hulls', () => {
        const hulls = new Map([['0', ['a']]]); // a community of one gets no shape
        expect(buildPluginList({ hulls, bubbleSets: true }, ['swatch-a'])).toEqual([]);
    });
});

describe('GRAPH_LAYOUTS (LA-09)', () => {
    it('every offered layout id resolves to a G6 layout type', () => {
        for (const l of GRAPH_LAYOUTS) {
            const cfg = layoutConfig(l.id);
            expect(cfg['type'], `layout '${l.id}' must map to a G6 type`).toBeTruthy();
        }
    });

    it('offers the layouts LA-09 adds, each mapped to its installed G6 built-in', () => {
        expect(layoutConfig('fruchterman')).toMatchObject({ type: 'fruchterman' });
        expect(layoutConfig('fishbone')).toMatchObject({ type: 'fishbone' });
        expect(layoutConfig('dendrogram')).toMatchObject({ type: 'dendrogram', radial: false });
        // the pre-existing radial variant is the SAME engine and must stay radial
        expect(layoutConfig('radial-tree')).toMatchObject({ type: 'dendrogram', radial: true });
    });

    it('gates the hierarchical additions on a tree-shaped graph, as the existing tree layouts are', () => {
        const byId = Object.fromEntries(GRAPH_LAYOUTS.map((l) => [l.id, l.tree]));
        expect(byId['dendrogram']).toBe(true);
        expect(byId['fishbone']).toBe(true);
        expect(byId['fruchterman']).toBe(false); // force-directed, works on any graph
    });

    it('has no duplicate ids and every id is unique to one label', () => {
        const ids = GRAPH_LAYOUTS.map((l) => l.id);
        expect(new Set(ids).size).toBe(ids.length);
    });
});

/**
 * LA-05 change classifier. G6 cannot instantiate in jsdom, so the live graph is a stub and `create()`
 * is spied: what these pin is WHICH path a given input change takes, which is the whole behaviour —
 * a cosmetic change must not tear the canvas down, and only a new node may re-run layout.
 */
describe('GraphViewComponent change classifier (LA-05)', () => {
    type Internals = {
        ready: boolean;
        graph: unknown;
        data: G6GraphData | null;
        display: GraphViewComponent['display'];
        emphasis: GraphViewComponent['emphasis'];
        layout: GraphViewComponent['layout'];
        snapshotKeys(): void;
        ngOnChanges(changes: Record<string, unknown>): void;
    };

    function graphStub(positions: Record<string, [number, number]> = {}) {
        return {
            setData: vi.fn(),
            draw: vi.fn(() => Promise.resolve()),
            render: vi.fn(() => Promise.resolve()),
            destroy: vi.fn(),
            getElementPosition: vi.fn((id: string) => {
                const p = positions[id];
                if (!p) throw new Error('not rendered');
                return p;
            }),
        };
    }

    const graphOf = (ids: string[]): G6GraphData => ({
        nodes: ids.map((id) => ({ id, data: { label: id, kind: 'DATASET' } })) as G6GraphData['nodes'],
        edges: [],
    });

    /** A component whose `create()` is stubbed out, holding a live stub graph and a settled baseline. */
    function harness(data: G6GraphData, positions: Record<string, [number, number]> = {}) {
        TestBed.configureTestingModule({
            imports: [GraphViewComponent],
            providers: [{ provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
        });
        const fixture = TestBed.createComponent(GraphViewComponent);
        const comp = fixture.componentInstance as unknown as Internals;
        const create = vi.spyOn(comp as unknown as { create(): void }, 'create').mockImplementation(() => undefined);
        const graph = graphStub(positions);
        comp.ready = true;
        comp.graph = graph;
        comp.data = data;
        comp.snapshotKeys();
        create.mockClear();
        return { comp, graph, create };
    }

    it('a display change repaints in place — no rebuild, no layout', () => {
        // The repaint re-sets the SAME data on purpose: G6 re-evaluates the element style mappers only
        // when the data is set, so a bare `draw()` leaves the old paint on the canvas (measured in the
        // preview — labels stayed visible after being switched off). `render()` is the one that would
        // re-run layout, so THAT is the call this pins as absent.
        const { comp, graph, create } = harness(graphOf(['a', 'b']));
        comp.display = { nodeLabels: false } as GraphViewComponent['display'];
        comp.ngOnChanges({ display: {} });
        expect(create).not.toHaveBeenCalled();
        // Gate G-R7 states the criterion as `destroy()` specifically, so assert that literally and not
        // only through the spied `create()` — the two can drift if teardown ever moves.
        expect(graph.destroy).not.toHaveBeenCalled();
        expect(graph.render).not.toHaveBeenCalled();
        expect(graph.setData).toHaveBeenCalledTimes(1);
        expect(graph.draw).toHaveBeenCalledTimes(1);
    });

    it('centerOn pans the viewport to the nodes without animation, and tolerates an id the graph lacks', async () => {
        const { comp, graph } = harness(graphOf(['a', 'b']));
        const focusElement = vi.fn(() => Promise.reject(new Error('no such element')));
        (graph as unknown as { focusElement: unknown }).focusElement = focusElement;
        (comp as unknown as GraphViewComponent).centerOn(['a']);
        expect(focusElement).toHaveBeenCalledWith(['a'], false);
        (comp as unknown as GraphViewComponent).centerOn([]);
        expect(focusElement).toHaveBeenCalledTimes(1);
        await Promise.resolve(); // the rejection is swallowed, never unhandled
    });

    it('an emphasis change repaints in place', () => {
        const { comp, graph, create } = harness(graphOf(['a', 'b']));
        comp.emphasis = { nodeIds: ['a'] };
        comp.ngOnChanges({ emphasis: {} });
        expect(create).not.toHaveBeenCalled();
        expect(graph.destroy).not.toHaveBeenCalled();
        expect(graph.render).not.toHaveBeenCalled();
        expect(graph.draw).toHaveBeenCalledTimes(1);
    });

    it('a rebind of an EQUAL-valued display object does nothing at all', () => {
        // The reason the classifier compares by VALUE: the Studio's `[display]` is a `computed()` that
        // yields a fresh object on every evaluation, so an identity check would repaint constantly.
        const { comp, graph, create } = harness(graphOf(['a']));
        comp.display = { nodeLabels: false } as GraphViewComponent['display'];
        comp.snapshotKeys();
        graph.draw.mockClear();
        comp.display = { nodeLabels: false } as GraphViewComponent['display']; // fresh object, same value
        comp.ngOnChanges({ display: {} });
        expect(create).not.toHaveBeenCalled();
        expect(graph.draw).not.toHaveBeenCalled();
    });

    it('a layout change is the one input that forces a rebuild', () => {
        const { comp, graph, create } = harness(graphOf(['a']));
        comp.layout = 'radial' as GraphViewComponent['layout'];
        comp.ngOnChanges({ layout: {} });
        expect(create).toHaveBeenCalledTimes(1);
        expect(graph.draw).not.toHaveBeenCalled();
    });

    it('removing a node applies in place and never re-runs layout', () => {
        const { comp, graph, create } = harness(graphOf(['a', 'b']), { a: [10, 20], b: [30, 40] });
        comp.data = graphOf(['a']);
        comp.ngOnChanges({ data: {} });
        expect(create).not.toHaveBeenCalled();
        expect(graph.setData).toHaveBeenCalledTimes(1);
        expect(graph.render).not.toHaveBeenCalled();
        expect(graph.draw).toHaveBeenCalledTimes(1);
    });

    it('a surviving node carries its current canvas position onto the new data', () => {
        const { comp, graph } = harness(graphOf(['a', 'b']), { a: [10, 20], b: [30, 40] });
        comp.data = graphOf(['a']);
        comp.ngOnChanges({ data: {} });
        const seeded = graph.setData.mock.calls[0][0] as G6GraphData;
        expect(seeded.nodes).toHaveLength(1);
        expect((seeded.nodes[0] as unknown as { style: { x: number; y: number } }).style).toMatchObject({
            x: 10,
            y: 20,
        });
    });

    it('adding a node re-runs layout, because a new node has no position yet', () => {
        const { comp, graph } = harness(graphOf(['a']), { a: [10, 20] });
        comp.data = graphOf(['a', 'c']);
        comp.ngOnChanges({ data: {} });
        expect(graph.setData).toHaveBeenCalledTimes(1);
        expect(graph.render).toHaveBeenCalledTimes(1);
        expect(graph.draw).not.toHaveBeenCalled();
    });
});

describe('stableKey', () => {
    it('is equal for structurally equal objects built fresh — the memoisation contract', () => {
        expect(stableKey({ nodeLabels: true, nodeColors: {} })).toBe(stableKey({ nodeLabels: true, nodeColors: {} }));
    });

    it('separates Maps that JSON.stringify would both render as {}', () => {
        const a = stableKey({ groups: new Map([['n1', 'c0']]) });
        const b = stableKey({ groups: new Map([['n1', 'c1']]) });
        expect(JSON.stringify({ groups: new Map([['n1', 'c0']]) })).toBe(
            JSON.stringify({ groups: new Map([['n1', 'c1']]) }),
        );
        expect(a).not.toBe(b);
    });

    it('separates Sets the same way', () => {
        expect(stableKey(new Set(['a']))).not.toBe(stableKey(new Set(['b'])));
    });
});

describe('dense-graph link labels (telco demo ring, 2026-09-27)', () => {
    const disp = (o: Partial<GraphDisplayOptions> = {}): GraphDisplayOptions =>
        ({ nodeLabels: true, edgeLabels: true, ...o }) as GraphDisplayOptions;

    it('hides labels only ABOVE the cap, unless all-labels is on or labels are off anyway', () => {
        expect(DENSE_EDGE_LABEL_CAP).toBe(20);
        expect(edgeLabelsHiddenByDensity(disp(), 20)).toBe(false); // sparse keeps today's labels
        expect(edgeLabelsHiddenByDensity(disp(), 21)).toBe(true);
        expect(edgeLabelsHiddenByDensity(disp(), 42)).toBe(true); // the demo ring
        expect(edgeLabelsHiddenByDensity(null, 42)).toBe(true); // a saved view with no display block
        expect(edgeLabelsHiddenByDensity(disp({ allEdgeLabels: true }), 42)).toBe(false);
        expect(edgeLabelsHiddenByDensity(disp({ edgeLabels: false }), 42)).toBe(false); // off is off, not "dense"
    });

    it('reveals the hovered edge, or every edge touching a hovered node — only while dense', () => {
        const fixture = create(null);
        const comp = fixture.componentInstance;
        const edges = Array.from({ length: 21 }, (_, i) => ({
            id: `e${i}`,
            source: i === 0 ? 'hub' : `n${i}`,
            target: i < 3 ? 'hub' : 'x',
        }));
        comp.data = { nodes: [], edges } as unknown as G6GraphData;
        expect(comp.edgesToReveal('e7', 'edge')).toEqual(['e7']);
        expect(comp.edgesToReveal('hub', 'node')).toEqual(['e0', 'e1', 'e2']);
        expect(comp.edgesToReveal(undefined, 'node')).toEqual([]);
        comp.display = disp({ allEdgeLabels: true });
        expect(comp.edgesToReveal('hub', 'node')).toEqual([]); // every label already shows
        comp.display = null;
        comp.data = { nodes: [], edges: edges.slice(0, 20) } as unknown as G6GraphData;
        expect(comp.edgesToReveal('hub', 'node')).toEqual([]); // sparse: nothing hidden to reveal
    });
});

describe('graph text alternative and canvas tabindex (LA-A11Y-AUDIT-1)', () => {
    it('the host is a figure named by the graph size', () => {
        const host: HTMLElement = create({ nodes: [], edges: [] }).nativeElement;
        expect(host.getAttribute('role')).toBe('figure');
        expect(host.getAttribute('aria-label')).toBe('Graph, 0 nodes, 0 links');
        expect(graphSummaryLabel({ nodes: [{ id: 'a' }], edges: [] } as unknown as G6GraphData)).toBe(
            'Graph, 1 node, 0 links',
        );
        expect(graphSummaryLabel({ nodes: [], edges: [] }, 'Use Show as list for a text version.')).toBe(
            'Graph, 0 nodes, 0 links. Use Show as list for a text version.',
        );
    });

    it('demotes the positive tabindex G6 puts on its canvases to one tab stop and leaves others alone', async () => {
        const root = document.createElement('div');
        root.innerHTML = '<canvas tabindex="1"></canvas><canvas tabindex="1"></canvas><button tabindex="-1">x</button>';
        document.body.appendChild(root);
        demotePositiveTabindex(root);
        const values = Array.from(root.querySelectorAll('[tabindex]')).map((el) => el.getAttribute('tabindex'));
        expect(values).toEqual(['0', '-1', '-1']); // one tab stop for the stacked layers
        await expectNoA11yViolations(root);
        root.remove();
    });
});
