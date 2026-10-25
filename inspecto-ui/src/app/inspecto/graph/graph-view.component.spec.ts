import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { G6GraphData } from './catalog-graph';
import {
    DENSE_EDGE_LABEL_CAP,
    GRAPH_DEFAULT_LAYOUT_SPACING,
    GRAPH_DEFAULT_NODE_SIZE,
    GRAPH_LAYOUT_SPACINGS,
    GRAPH_LAYOUTS,
    GRAPH_NODE_SIZES,
    GraphDisplayOptions,
    HOST_NODE_SIZE,
    GraphViewComponent,
    NODE_DBLCLICK_DELAY_MS,
    SELECTED_STATE,
    buildBehaviors,
    buildPluginList,
    demotePositiveTabindex,
    graphSummaryLabel,
    edgeLabelsHiddenByDensity,
    layoutConfig,
    graphSignature,
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

describe('layoutConfig node size and spacing (operator 2026-10-24)', () => {
    it('with neither given, every layout is exactly what it was before they existed', () => {
        expect(layoutConfig('force')).toEqual({ type: 'd3-force', collide: { radius: 28 } });
        expect(layoutConfig('dagre')).toEqual({ type: 'antv-dagre', rankdir: 'LR', nodesep: 18, ranksep: 60 });
        expect(layoutConfig('radial')).toEqual({ type: 'radial', unitRadius: 120, preventOverlap: true });
        expect(layoutConfig('concentric')).toEqual({
            type: 'concentric',
            sortBy: 'degree',
            preventOverlap: true,
            nodeSize: 40,
        });
        expect(layoutConfig('fruchterman')).toEqual({ type: 'fruchterman', gravity: 5, speed: 5 });
        expect(layoutConfig('org')['getWidth' as never]).toBeDefined();
        // spacing 1 and the host's own node size is the same as passing nothing
        for (const l of GRAPH_LAYOUTS) {
            const none = JSON.stringify(layoutConfig(l.id), (_k, v) => (typeof v === 'function' ? v() : v));
            const one = JSON.stringify(layoutConfig(l.id, { nodeSize: 32, spacing: 1 }), (_k, v) =>
                typeof v === 'function' ? v() : v,
            );
            expect(one, l.id).toBe(none);
        }
    });

    it('spacing multiplies the distances: force link and charge, layered gaps, radial radius, tree gaps', () => {
        expect(layoutConfig('force', { spacing: 4 })).toMatchObject({
            link: { distance: 120 },
            manyBody: { strength: -120 },
        });
        expect(layoutConfig('dagre', { spacing: 4 })).toMatchObject({ nodesep: 72, ranksep: 240 });
        expect(layoutConfig('radial', { spacing: 2 })).toMatchObject({ unitRadius: 240 });
        expect(layoutConfig('force-cluster', { spacing: 3 })).toMatchObject({ kr: 60 });
        expect(layoutConfig('dendrogram', { spacing: 2 })).toMatchObject({ nodeSep: 72, rankSep: 240 });
        expect((layoutConfig('mindmap', { spacing: 2 })['getHGap'] as () => number)()).toBe(120);
        expect(layoutConfig('fruchterman', { spacing: 2 })).toMatchObject({ gravity: 2.5 });
        expect(layoutConfig('concentric', { spacing: 2 })).toMatchObject({ nodeSpacing: 40 });
    });

    it('the collision radius and tree box follow the node size, so small nodes keep a small gap', () => {
        expect(layoutConfig('force', { nodeSize: 20 })).toMatchObject({ collide: { radius: 18 } });
        expect((layoutConfig('mindmap', { nodeSize: 20 })['getWidth'] as () => number)()).toBe(20);
        expect(layoutConfig('concentric', { nodeSize: 20 })).toMatchObject({ nodeSize: 25 });
    });

    it('offers the node sizes and spacings the Display menu shows, with the defaults among them', () => {
        expect(GRAPH_NODE_SIZES.map((s) => s.value)).toContain(GRAPH_DEFAULT_NODE_SIZE);
        expect(GRAPH_LAYOUT_SPACINGS.map((s) => s.value)).toContain(GRAPH_DEFAULT_LAYOUT_SPACING);
        expect(GRAPH_DEFAULT_NODE_SIZE).toBeLessThan(HOST_NODE_SIZE); // small by default
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
            getPlugins: () => [],
            draw: vi.fn(() => Promise.resolve()),
            render: vi.fn(() => Promise.resolve()),
            destroy: vi.fn(),
            getOptions: vi.fn(() => ({})),
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

    // N2: a hulls toggle used to change `pluginKey`, so it rebuilt the graph and re-ran layout (~3 s on 767 nodes).
    describe('community hulls ride the live graph (N2)', () => {
        const members = new Map([['0', ['a', 'b']]]);
        /** G6 5.1.1 `Graph.setPlugins`: the updater receives the current plugin options and its result replaces them. */
        function withPlugins(plugins: GraphViewComponent['plugins']) {
            const h = harness(graphOf(['a', 'b']), { a: [10, 20], b: [30, 40] });
            let options: Record<string, unknown>[] = [{ type: 'minimap', key: 'minimap' }];
            const setPlugins = vi.fn((u: (p: Record<string, unknown>[]) => Record<string, unknown>[]) => {
                options = u(options);
            });
            Object.assign(h.graph, { setPlugins, getPlugins: () => options, getPluginInstance: () => undefined });
            const c = h.comp as unknown as { plugins: GraphViewComponent['plugins'] };
            c.plugins = plugins;
            h.comp.snapshotKeys();
            return { ...h, c, setPlugins, options: () => options };
        }

        it('turning hulls ON adds the hull plugins in place: no create, no layout, no data reset', () => {
            const { comp, graph, create, c, setPlugins, options } = withPlugins({ minimap: true, hulls: null });
            c.plugins = { minimap: true, hulls: members };
            comp.ngOnChanges({ plugins: {} });
            expect(create).not.toHaveBeenCalled();
            expect(graph.destroy).not.toHaveBeenCalled();
            expect(graph.render).not.toHaveBeenCalled();
            expect(graph.setData).not.toHaveBeenCalled();
            expect(setPlugins).toHaveBeenCalledTimes(1);
            expect(options().map((p) => p['key'])).toEqual(['minimap', 'hull-0']);
        });

        it('turning hulls OFF removes only the hull plugins, in place', () => {
            const { comp, graph, create, c, setPlugins, options } = withPlugins({ minimap: true, hulls: members });
            setPlugins.mockClear();
            options().push({ type: 'hull', key: 'hull-0' });
            c.plugins = { minimap: true, hulls: null };
            comp.ngOnChanges({ plugins: {} });
            expect(create).not.toHaveBeenCalled();
            expect(graph.render).not.toHaveBeenCalled();
            expect(setPlugins).toHaveBeenCalledTimes(1);
            expect(options().map((p) => p['key'])).toEqual(['minimap']);
        });

        it('switching hulls to bubble sets is in place too (bubbleSets is not a rebuild key)', () => {
            const { comp, create, c, setPlugins, options } = withPlugins({ minimap: true, hulls: members });
            c.plugins = { minimap: true, hulls: members, bubbleSets: true };
            comp.ngOnChanges({ plugins: {} });
            expect(create).not.toHaveBeenCalled();
            expect(setPlugins).toHaveBeenCalledTimes(1);
            expect(options().map((p) => p['key'])).toEqual(['minimap', 'bubble-sets-0']);
        });

        it('hulls and data changing together: the shapes follow only once the data is drawn', async () => {
            const { comp, create, c, setPlugins } = withPlugins({ minimap: true, hulls: members });
            comp.data = graphOf(['a']); // a node left: applyData, not a rebuild
            c.plugins = { minimap: true, hulls: new Map([['0', ['a']]]) };
            comp.ngOnChanges({ data: {}, plugins: {} });
            expect(setPlugins).not.toHaveBeenCalled(); // not before the canvas has the new data
            await Promise.resolve();
            await Promise.resolve();
            expect(setPlugins).toHaveBeenCalledTimes(1);
            expect(create).not.toHaveBeenCalled();
        });

        it('a rebind of equal hulls touches nothing, and a changed minimap still rebuilds', () => {
            const { comp, create, c, setPlugins } = withPlugins({ minimap: true, hulls: members });
            c.plugins = { minimap: true, hulls: new Map([['0', ['a', 'b']]]) };
            comp.ngOnChanges({ plugins: {} });
            expect(create).not.toHaveBeenCalled();
            expect(setPlugins).not.toHaveBeenCalled();
            c.plugins = { minimap: false, hulls: members };
            comp.ngOnChanges({ plugins: {} });
            expect(create).toHaveBeenCalledTimes(1);
        });
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

describe('GraphViewComponent selection is a state change, not a repaint (PERF-P1)', () => {
    type Internals = {
        ready: boolean;
        graph: unknown;
        data: G6GraphData | null;
        selection: string[] | null;
        interaction: GraphViewComponent['interaction'];
        selectionChange: { emit(ids: string[]): void };
        styleKey(): string;
        snapshotKeys(): void;
        ngOnChanges(changes: Record<string, unknown>): void;
        onBrushSelect(ids: string[]): void;
        flushBrush(): void;
    };
    const stub = () => ({
        // The elements the graph holds are the last data set; a removed node loses its states, a returning one starts bare.
        setData: vi.fn(function (this: { held: Set<string> }, d: { nodes: { id: string }[] }) {
            this.held = new Set(d.nodes.map((n) => String(n.id)));
            for (const id of [...applied.keys()]) if (!this.held.has(id)) applied.delete(id);
        }),
        getPlugins: () => [],
        draw: vi.fn(() => Promise.resolve()),
        render: vi.fn(() => Promise.resolve()),
        // G6 5.1.1 faithful: every id is looked up first; an unknown one throws and the WHOLE batch is dropped.
        setElementState: vi.fn(function (this: { held: Set<string> }, states: Record<string, string[]>) {
            for (const id of Object.keys(states))
                if (!this.held.has(id)) return Promise.reject(new Error('Unknown element type of id: ' + id));
            for (const id of Object.keys(states)) applied.set(id, states[id]);
            return Promise.resolve();
        }),
        destroy: vi.fn(),
        getOptions: vi.fn(() => ({})),
        held: new Set<string>(),
    });
    const applied = new Map<string, string[]>();
    /** The ids the canvas actually paints as selected. */
    const painted = () =>
        [...applied]
            .filter(([, st]) => st.includes(SELECTED_STATE))
            .map(([id]) => id)
            .sort();
    function host() {
        applied.clear();
        TestBed.configureTestingModule({
            imports: [GraphViewComponent],
            providers: [{ provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } }],
        });
        const fixture = TestBed.createComponent(GraphViewComponent);
        const comp = fixture.componentInstance as unknown as Internals;
        vi.spyOn(comp as unknown as { create(): void }, 'create').mockImplementation(() => undefined);
        const graph = stub();
        graph.held = new Set(['a', 'b', 'c', 'd']);
        comp.ready = true;
        comp.graph = graph;
        comp.interaction = { boxSelect: true };
        comp.data = {
            nodes: ['a', 'b', 'c', 'd'].map((id) => ({ id, data: { label: id, kind: 'DATASET' } })),
            edges: [],
        } as unknown as G6GraphData;
        comp.snapshotKeys();
        return { comp, graph };
    }
    const select = (comp: Internals, ids: string[]) => {
        comp.selection = ids;
        comp.ngOnChanges({ selection: {} });
    };

    it('a selection change never calls setData or draw, and sets state only for the changed ids', () => {
        const { comp, graph } = host();
        select(comp, ['a', 'b']);
        expect(graph.setData).not.toHaveBeenCalled();
        expect(graph.draw).not.toHaveBeenCalled();
        expect(graph.setElementState).toHaveBeenCalledTimes(1);
        expect(graph.setElementState.mock.calls[0][0]).toEqual({ a: [SELECTED_STATE], b: [SELECTED_STATE] });

        select(comp, ['b', 'c']); // a leaves and c joins; b is already in the state
        const diff = graph.setElementState.mock.calls[1][0];
        expect(Object.keys(diff).sort()).toEqual(['a', 'c']);
        expect(diff['a']).toEqual([]);
        expect(diff['c']).toEqual([SELECTED_STATE]);
        expect(graph.setData).not.toHaveBeenCalled();
        expect(graph.draw).not.toHaveBeenCalled();
    });

    it('data and selection changing in ONE change still refresh the selection and its states', () => {
        const { comp, graph } = host();
        select(comp, ['a']);
        graph.setElementState.mockClear();
        comp.data = {
            nodes: ['b', 'c'].map((id) => ({ id, data: { label: id, kind: 'DATASET' } })),
            edges: [],
        } as unknown as G6GraphData;
        comp.selection = ['b'];
        comp.ngOnChanges({ data: {}, selection: {} });
        expect((comp as unknown as { selSet: Set<string> }).selSet).toEqual(new Set(['b']));
        return Promise.resolve().then(() => {
            const calls = graph.setElementState.mock.calls.map((c) => c[0]);
            expect(Object.assign({}, ...calls)).toEqual({ b: [SELECTED_STATE] }); // a left the graph: never sent, G6 would reject the batch
        });
    });

    it('a node that leaves the data and comes back is selected again', async () => {
        const { comp, graph } = host();
        select(comp, ['a']);
        const withNodes = (ids: string[]) =>
            ({
                nodes: ids.map((id) => ({ id, data: { label: id, kind: 'DATASET' } })),
                edges: [],
            }) as unknown as G6GraphData;
        comp.data = withNodes(['b']);
        comp.ngOnChanges({ data: {} }); // a leaves the data while the selection still lists it
        await Promise.resolve();
        graph.setElementState.mockClear();
        comp.data = withNodes(['a', 'b']);
        comp.ngOnChanges({ data: {} });
        await Promise.resolve();
        await Promise.resolve();
        expect(Object.assign({}, ...graph.setElementState.mock.calls.map((c) => c[0]))).toEqual({
            a: [SELECTED_STATE],
        });
    });

    it('a selected node dropped by the data, with a new selection in the same change, paints the new selection', async () => {
        const { comp } = host();
        select(comp, ['a']);
        comp.data = {
            nodes: ['b', 'c'].map((id) => ({ id, data: { label: id, kind: 'DATASET' } })),
            edges: [],
        } as unknown as G6GraphData;
        comp.selection = ['b'];
        comp.ngOnChanges({ data: {}, selection: {} });
        await Promise.resolve();
        await Promise.resolve();
        expect(painted()).toEqual(['b']);
    });

    it('one data change that removes a selected node and brings another selected one back paints the returning one', async () => {
        const { comp } = host();
        comp.data = {
            nodes: ['a', 'b'].map((id) => ({ id, data: { label: id, kind: 'DATASET' } })),
            edges: [],
        } as unknown as G6GraphData;
        comp.ngOnChanges({ data: {} });
        await Promise.resolve();
        select(comp, ['a', 'c']); // c is not in the graph yet
        await Promise.resolve();
        comp.data = {
            nodes: ['b', 'c'].map((id) => ({ id, data: { label: id, kind: 'DATASET' } })),
            edges: [],
        } as unknown as G6GraphData;
        comp.ngOnChanges({ data: {} });
        await Promise.resolve();
        await Promise.resolve();
        expect(painted()).toEqual(['c']);
    });

    it('styleKey does not move with the selection', () => {
        const { comp } = host();
        const before = comp.styleKey();
        comp.selection = ['a', 'b', 'c'];
        expect(comp.styleKey()).toBe(before);
    });

    it('coalesces brush updates to one emission per frame and emits the final ids on release', () => {
        const frames: FrameRequestCallback[] = [];
        vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => frames.push(cb));
        vi.stubGlobal('cancelAnimationFrame', vi.fn());
        try {
            const { comp } = host();
            const emitted: string[][] = [];
            comp.selectionChange.emit = (ids) => emitted.push(ids);
            comp.onBrushSelect(['a']);
            comp.onBrushSelect(['a', 'b']);
            comp.onBrushSelect(['a', 'b', 'c']);
            expect(emitted).toEqual([]); // nothing until the frame
            expect(frames).toHaveLength(1); // one frame requested for three updates
            frames[0](0);
            expect(emitted).toEqual([['a', 'b', 'c']]);
            comp.onBrushSelect(['a', 'd']);
            comp.flushBrush(); // the release, before the next frame
            expect(emitted).toEqual([
                ['a', 'b', 'c'],
                ['a', 'd'],
            ]);
            comp.flushBrush(); // nothing pending: no duplicate
            expect(emitted).toHaveLength(2);
        } finally {
            vi.unstubAllGlobals();
        }
    });
});

describe('graphSignature (PERF-P2)', () => {
    const base = (): G6GraphData =>
        ({
            nodes: [
                { id: 'a', data: { label: 'A', kind: 'entity', attrs: { risk: '5' } } },
                { id: 'b', data: { label: 'B', kind: 'entity' } },
            ],
            edges: [{ id: 'e1', source: 'a', target: 'b', data: { kind: 'calls' } }],
        }) as unknown as G6GraphData;
    const node = (g: G6GraphData, i: number) => g.nodes[i].data as unknown as Record<string, unknown>;

    it('is stable for identical content in a fresh object', () => {
        expect(graphSignature(base())).toBe(graphSignature(base()));
        expect(graphSignature(null)).toBe(graphSignature(undefined));
    });

    it('moves when a drawn attribute, a label, or a flag changes', () => {
        const sig = graphSignature(base());
        const attr = base();
        (node(attr, 0)['attrs'] as Record<string, string>)['risk'] = '6';
        expect(graphSignature(attr)).not.toBe(sig);
        const label = base();
        node(label, 1)['label'] = 'B2';
        expect(graphSignature(label)).not.toBe(sig);
        for (const flag of ['missing', 'unexpanded', 'highConnectivity', 'held']) {
            const g = base();
            node(g, 1)[flag] = true;
            expect(graphSignature(g), flag).not.toBe(sig);
        }
        const edgeKind = base();
        (edgeKind.edges[0].data as { kind: string }).kind = 'sms';
        expect(graphSignature(edgeKind)).not.toBe(sig);
    });

    it('moves when only an edge provenance or a node provenance changes (the tooltips read both)', () => {
        const sig = graphSignature(base());
        const e = base();
        (e.edges[0].data as { provenance?: string[] }).provenance = ['ds1'];
        expect(graphSignature(e)).not.toBe(sig);
        const n = base();
        (n.nodes[0].data as { provenance?: string[] }).provenance = ['ds1'];
        expect(graphSignature(n)).not.toBe(sig);
    });

    it('tells null, empty and absent apart', () => {
        const withAttr = (v: string | null | undefined) => {
            const g = base();
            (node(g, 0)['attrs'] as Record<string, unknown>)['risk'] = v;
            return graphSignature(g);
        };
        expect(new Set([withAttr(null), withAttr(''), withAttr(undefined)]).size).toBe(3);
    });

    it('moves when a node or an edge is added or removed', () => {
        const sig = graphSignature(base());
        const moreNodes = base();
        moreNodes.nodes.push({ id: 'c', data: { label: 'C', kind: 'entity' } } as G6GraphData['nodes'][number]);
        expect(graphSignature(moreNodes)).not.toBe(sig);
        const lessNodes = base();
        lessNodes.nodes.pop();
        expect(graphSignature(lessNodes)).not.toBe(sig);
        const moreEdges = base();
        moreEdges.edges.push({
            id: 'e2',
            source: 'b',
            target: 'a',
            data: { kind: 'calls' },
        } as G6GraphData['edges'][number]);
        expect(graphSignature(moreEdges)).not.toBe(sig);
        const lessEdges = base();
        lessEdges.edges.pop();
        expect(graphSignature(lessEdges)).not.toBe(sig);
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

    it("names a ringed node's contact count in its hover text, and only when served", () => {
        const comp = create(null).componentInstance as unknown as { tooltipHtml(items: unknown[]): string };
        const node = (data: object) => [{ id: 'h', data: { label: 'H', kind: 'entity', ...data } }];
        expect(comp.tooltipHtml(node({ highConnectivity: true, contacts: 650 }))).toContain(
            'High connectivity — 650 contacts',
        );
        expect(comp.tooltipHtml(node({ highConnectivity: true, contacts: 1 }))).toContain('— 1 contact');
        const bare = comp.tooltipHtml(node({ highConnectivity: true }));
        expect(bare).toContain('High connectivity');
        expect(bare).not.toContain('contact');
        expect(comp.tooltipHtml(node({}))).not.toContain('High connectivity');
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

describe('opt-in pointer model (Link Analysis, 2026-10-15)', () => {
    const types = (b: unknown[]) => b.map((x) => (typeof x === 'string' ? x : (x as { type: string }).type));

    it('no interaction keeps every other screen exactly as it was: drag pans, no box, no extras', () => {
        expect(types(buildBehaviors(null, null, vi.fn()))).toEqual(['drag-canvas', 'zoom-canvas', 'drag-element']);
        expect(types(buildBehaviors({}, null, vi.fn()))).toEqual(['drag-canvas', 'zoom-canvas', 'drag-element']);
    });

    it('panByDrag:false drops drag-canvas and keeps the wheel zoom and node dragging', () => {
        expect(types(buildBehaviors({ panByDrag: false }, null, vi.fn()))).toEqual(['zoom-canvas', 'drag-element']);
    });

    it('boxSelect draws a plain-drag brush on the empty canvas only, over nodes only', () => {
        const forwarded: string[][] = [];
        const b = buildBehaviors({ panByDrag: false, boxSelect: true }, null, (ids) => forwarded.push(ids)) as Record<
            string,
            unknown
        >[];
        const brush = b.find((x) => x['type'] === 'brush-select') as Record<string, unknown>;
        expect(brush['trigger']).toEqual(['drag']); // a plain drag, no shift
        expect(brush['enableElements']).toEqual(['node']);
        expect(brush['state']).toBe(SELECTED_STATE); // drag-element moves every node in this state together
        expect(SELECTED_STATE).toBe('selected'); // drag-element's default group state
        const enable = brush['enable'] as (e: { targetType?: string }) => boolean;
        expect(enable({ targetType: 'canvas' })).toBe(true); // starts on the empty canvas
        expect(enable({ targetType: 'node' })).toBe(false); // never on a node: that drags the node
        (brush['onSelect'] as (s: Record<string, unknown>) => void)({ a: 'brushed', b: 'brushed' });
        expect(forwarded).toEqual([['a', 'b']]);
        expect(types(b)).not.toContain('drag-canvas'); // G6: a drag-triggered brush and drag-canvas cannot coexist
    });

    it('boxSelect replaces the View toolbox shift+drag brush but keeps its other behaviours', () => {
        const b = buildBehaviors(
            { boxSelect: true, panByDrag: false },
            { behaviors: ['hover-activate', 'brush-select', 'lasso-select'] },
            vi.fn(),
        );
        expect(types(b).filter((t) => t === 'brush-select')).toHaveLength(1);
        expect(types(b)).toContain('hover-activate');
        expect(types(b)).toContain('lasso-select');
    });

    it('without boxSelect the toolbox shift+drag brush is untouched', () => {
        expect(types(buildBehaviors(null, { behaviors: ['brush-select'] }, vi.fn()))).toContain('brush-select');
    });

    it('the single-click hold for a double click is short enough to feel instant', () => {
        expect(NODE_DBLCLICK_DELAY_MS).toBeGreaterThanOrEqual(150);
        expect(NODE_DBLCLICK_DELAY_MS).toBeLessThanOrEqual(300);
    });

    it('the new inputs and outputs default to the current behaviour', () => {
        const fixture = create(null);
        const c = fixture.componentInstance;
        expect(c.interaction).toBeNull();
        expect(c.selection).toBeNull();
        expect(c.nodeDblClick).toBeDefined();
        expect(c.selectionChange).toBeDefined();
        fixture.destroy();
    });
});
