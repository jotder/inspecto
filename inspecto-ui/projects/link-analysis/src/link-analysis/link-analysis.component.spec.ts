import { Component, Input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuTrigger } from '@angular/material/menu';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { InvService, RecursivePathsRequest } from '@inspecto/link-analysis/api/inv.service';
import { PipelinesService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { ElementDetailData } from '@inspecto/link-analysis/investigation/element-detail.dialog';
import { G6GraphData, GraphSource } from '@inspecto/core/graph';
import { GeoLinkBrushService } from '@inspecto/link-analysis/graph/geo-link-brush';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { GraphViewComponent } from '@inspecto/core/graph/graph-view.component';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisComponent } from './link-analysis.component';
import { LinkAnalysisQueryPanelComponent } from './link-analysis-query-panel.component';
import { LinkAnalysisService, LinkAnalysisView } from './link-analysis.service';
import { provideLaHostServices } from 'app/modules/admin/studio/la-host.providers';

const DS: Dataset = {
    id: 'links-ds',
    name: 'Links',
    kind: 'physical',
    sourceName: 'links',
    columns: [],
    measures: [],
    calculated: [],
};

const TEST_COLOR = '#ff0000'; // a literal test value passed to setNodeColor(), not app styling — ds-allow

/** A tiny two-cluster graph: a–b–c plus d–e. */
const GRAPH: G6GraphData = {
    nodes: ['a', 'b', 'c', 'd', 'e'].map((id) => ({
        id,
        data: { label: id.toUpperCase(), kind: id < 'd' ? 'entity' : 'other' },
    })),
    edges: [
        { id: 'a->b', source: 'a', target: 'b', data: { kind: 'link' } },
        { id: 'b->c', source: 'b', target: 'c', data: { kind: 'link' } },
        { id: 'd->e', source: 'd', target: 'e', data: { kind: 'link' } },
    ],
};

/** Stands in for the G6 host (which cannot instantiate in jsdom) — same selector and inputs. */
@Component({ selector: 'inspecto-graph-view', standalone: true, template: '' })
class StubGraphView {
    @Input() data: G6GraphData | null = null;
    @Input() fill = false;
    @Input() emphasis: unknown;
    @Input() display: unknown;
    @Input() tooltips = false;
    @Input() textHint = '';
    @Input() layout: unknown;
    @Input() plugins: unknown;
}

// The Toolbox dock remembers an explicit open/close in sessionStorage; no test may inherit another's.
beforeEach(() => sessionStorage.clear());

function create(
    opts: {
        fail?: boolean;
        views?: LinkAnalysisView[];
        expand?: GraphSource['expand'];
        graph?: G6GraphData;
        /** What the fake source returns when the query carries a `filter` (the stage-2 push). */
        filtered?: G6GraphData;
        queryParams?: Record<string, string>;
        /** LA-11: a stand-in for the traversal route (the real service is used when absent). */
        inv?: Partial<InvService>;
        /** Swap the G6 host for an inert stand-in, so the template can render with a graph in jsdom. */
        stubGraph?: boolean;
    } = {},
) {
    const queried: unknown[] = [];
    const fakeSource: GraphSource = {
        id: 'entity-projection',
        label: 'Entity/Link (from a Dataset)',
        query: (q) => {
            queried.push(q);
            if (opts.fail) return Promise.reject(new Error('bad mapping'));
            const q2 = q as { filter?: unknown };
            return Promise.resolve(q2.filter && opts.filtered ? opts.filtered : (opts.graph ?? GRAPH));
        },
        expand: opts.expand,
    };
    const save = vi.fn((v: LinkAnalysisView) => of(v));
    TestBed.configureTestingModule({
        imports: [LinkAnalysisComponent],
        providers: [
            ...provideLaHostServices(),
            provideNoopAnimations(),
            provideRouter([]),
            { provide: GraphSourcesService, useValue: { sources: [fakeSource], byId: () => fakeSource } },
            { provide: DatasetsService, useValue: { list: () => of([DS]) } },
            { provide: PipelinesService, useValue: { list: () => of([]) } },
            ...(opts.inv ? [{ provide: InvService, useValue: opts.inv }] : []),
            { provide: LinkAnalysisService, useValue: { list: () => of(opts.views ?? []), save } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            {
                provide: ToastrService,
                useValue: { success: () => undefined, error: () => undefined, info: () => undefined },
            },
            {
                provide: ActivatedRoute,
                // 🔴 BOTH halves, because a real ActivatedRoute has both and the component uses each for a
                // different reason: `snapshot.queryParamMap` is the one-shot read the pivot handshake makes,
                // while `queryParamMap` is the live observable behind the `?case=` deep link, which must
                // survive a reload. A stub carrying only the snapshot made every test in this file die in the
                // CONSTRUCTOR on `undefined.pipe(...)` — 28 of 28, none of them on an assertion, so the file
                // stopped guarding anything the day the deep link was added.
                useValue: {
                    snapshot: { queryParamMap: convertToParamMap(opts.queryParams ?? {}) },
                    queryParamMap: of(convertToParamMap(opts.queryParams ?? {})),
                },
            },
        ],
    });
    if (opts.stubGraph) {
        TestBed.overrideComponent(LinkAnalysisComponent, {
            remove: { imports: [GraphViewComponent] },
            add: { imports: [StubGraphView] },
        });
    }
    return { fixture: TestBed.createComponent(LinkAnalysisComponent), queried, save };
}

// After a successful load the state is driven directly (no detectChanges) —
// the G6 host can't instantiate in jsdom (see registry.component.spec.ts).
async function runQuery(fixture: ReturnType<typeof create>['fixture']): Promise<void> {
    const c = fixture.componentInstance;
    // The query form lives in the (always-mounted, [hidden]-gated) query-panel child; patch it there.
    // Callers detectChanges() once before this (graph is still null then, so the G6 host never mounts).
    const panel = fixture.debugElement.query(By.directive(LinkAnalysisQueryPanelComponent))
        .componentInstance as LinkAnalysisQueryPanelComponent;
    panel.queryForm.patchValue({ datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target' });
    await c.run();
}

describe('LinkAnalysisComponent', () => {
    it('renders the empty state, is a11y-clean, and validates the mapping before querying', async () => {
        const { fixture, queried } = create();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('No graph yet');
        await fixture.componentInstance.run(); // no mapping picked yet
        expect(fixture.componentInstance.loadError()).toMatch(/source and target/);
        expect(queried).toHaveLength(0);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('runs the query, shows the graph counts, and search/kind filters drive emphasis + display', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.graph()).toEqual(GRAPH);
        expect(c.nodeKinds()).toEqual(['entity', 'other']);

        c.onSearch('a');
        expect(c.emphasis()?.nodeIds).toEqual(['a']);

        c.toggleKind('other', false);
        expect(c.displayed()?.nodes.map((n) => n.id)).toEqual(['a', 'b', 'c']);
        c.clearFilters();
        expect(c.displayed()?.nodes).toHaveLength(5);
    });

    it('timeline: filters edges by a temporal attrs column, resets on a fresh run and via clearFilters', async () => {
        const timedGraph: G6GraphData = {
            nodes: GRAPH.nodes,
            edges: [
                {
                    id: 'a->b',
                    source: 'a',
                    target: 'b',
                    data: { kind: 'link', attrs: { when: '2026-01-01T00:00:00Z' } },
                },
                {
                    id: 'b->c',
                    source: 'b',
                    target: 'c',
                    data: { kind: 'link', attrs: { when: '2026-06-01T00:00:00Z' } },
                },
                { id: 'd->e', source: 'd', target: 'e', data: { kind: 'link' } }, // no attrs
            ],
        };
        const { fixture } = create({ graph: timedGraph });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.attrColumns()).toEqual(['when']);
        c.setTimeColumn('when');
        expect(c.timeColumn()).toBe('when');
        expect(c.timeExtent()).toEqual([Date.parse('2026-01-01T00:00:00Z'), Date.parse('2026-06-01T00:00:00Z')]);
        expect(c.timeCutoff()).toBe(Date.parse('2026-06-01T00:00:00Z')); // defaults to the extent max — nothing hidden yet

        c.setTimeCutoff(Date.parse('2026-03-01T00:00:00Z'));
        expect(c.displayed()?.edges.map((e) => e.id)).toEqual(['a->b']); // b->c is after cutoff, d->e has no attrs

        c.clearFilters();
        expect(c.timeColumn()).toBe('');
        expect(c.displayed()?.edges).toHaveLength(3);

        c.setTimeColumn('when');
        c.setTimeCutoff(Date.parse('2026-03-01T00:00:00Z'));
        await runQuery(fixture); // a fresh query resets the timeline like the other presentation filters
        expect(c.timeColumn()).toBe('');
        expect(c.timeCutoff()).toBeNull();
    });

    it('offers a pivot to the map for a node carrying an objectRef (ui-design-review R8)', async () => {
        const graphWithRef: G6GraphData = {
            nodes: [
                ...GRAPH.nodes,
                { id: 'f', data: { label: 'F', kind: 'entity', objectRef: { id: 'case-1', type: 'CASE' } } },
            ],
            edges: GRAPH.edges,
        };
        const { fixture } = create({ graph: graphWithRef });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const dialog = fixture.debugElement.injector.get(MatDialog);
        const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
        const dataOf = (call: number): ElementDetailData =>
            (openSpy.mock.calls[call][1] as { data: ElementDetailData }).data;

        c.onNodeClick('a'); // plain node — no objectRef, no pivot offered
        expect(dataOf(0).pivotViews).toBeUndefined();

        c.onNodeClick('f');
        expect(dataOf(1).objectRef).toEqual({ id: 'case-1', type: 'CASE' });
        expect(dataOf(1).pivotViews).toEqual(['map']);
    });

    it('LA-22: a Geo brush highlights the nodes its keys project to, and a node click brushes the map', async () => {
        const keyed: G6GraphData = {
            nodes: [
                { id: 'entity:k1', data: { label: 'Alias', kind: 'entity' } },
                { id: 'entity:k2', data: { label: 'K1', kind: 'entity' } }, // label equal to the key: must NOT match
            ],
            edges: [{ id: 'e', source: 'entity:k1', target: 'entity:k2', data: { kind: 'link' } }],
        };
        const { fixture } = create({ graph: keyed });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const brush = TestBed.inject(GeoLinkBrushService);
        brush.fromGeo(['K1']);
        expect(c.geoBrushEmphasis()).toEqual({ nodeIds: ['entity:k1'], edgeIds: [] });

        const dialog = fixture.debugElement.injector.get(MatDialog);
        vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
        c.onNodeClick('entity:k2');
        expect(brush.brush()).toMatchObject({ origin: 'link', nodeIds: ['entity:k2'] });
        expect(c.geoBrushEmphasis()).toBeNull();
    });

    it('resolves an incoming investigation pivot against the loaded graph, or toasts if absent', async () => {
        const graphWithRef: G6GraphData = {
            nodes: [
                ...GRAPH.nodes,
                { id: 'f', data: { label: 'F', kind: 'entity', objectRef: { id: 'case-1', type: 'CASE' } } },
            ],
            edges: GRAPH.edges,
        };
        const { fixture } = create({ graph: graphWithRef, queryParams: { pivotId: 'case-1', pivotType: 'CASE' } });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        await runQuery(fixture);
        expect(c.emphasis()?.nodeIds).toEqual(['f']);
    });

    it('toasts when the pivoted-in record is not in the loaded graph', async () => {
        const { fixture } = create({ queryParams: { pivotId: 'case-missing', pivotType: 'CASE' } });
        fixture.detectChanges();
        const toastr = TestBed.inject(ToastrService);
        const infoSpy = vi.spyOn(toastr, 'info');
        await runQuery(fixture);
        expect(infoSpy).toHaveBeenCalled();
    });

    // Analysis-toolbox logic (shortest path, explain, centrality, communities, all-paths, components,
    // patterns, tool badges) lives in LinkAnalysisToolboxComponent — see its own spec.

    it("expandNode (Phase E): merges the source's one-hop neighborhood into the loaded graph, filters intact", async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'f', data: { label: 'F', kind: 'entity' } }],
            edges: [{ id: 'c->f', source: 'c', target: 'f', data: { kind: 'link' } }],
        }));
        const { fixture } = create({ expand });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.onSearch('a'); // some filter/analysis state that must survive the merge

        await c.expandNode('c', 'C', ['C']);
        expect(expand).toHaveBeenCalledWith('c', 'C', expect.objectContaining({ projection: expect.anything() }), [
            'C',
        ]);
        expect(
            c
                .graph()
                ?.nodes.map((n) => n.id)
                .sort(),
        ).toEqual(['a', 'b', 'c', 'd', 'e', 'f']);
        expect(c.graph()?.edges.map((e) => e.id)).toContain('c->f');
        expect(c.emphasis()?.nodeIds).toEqual(['a']); // untouched by the merge
    });

    // LA-02: an unsurfaced truncation is a false negative presented as a finding. `mergeGraphs` returns
    // a bare `G6GraphData` and structurally drops `truncated`, so the flag has to be carried onto the
    // signal by `expandNode` itself.
    it('expandNode surfaces a TRUNCATED neighborhood instead of leaving the working set reading complete', async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'f', data: { label: 'F', kind: 'entity' } }],
            edges: [{ id: 'c->f', source: 'c', target: 'f', data: { kind: 'link' } }],
            truncated: true,
        }));
        const { fixture } = create({ expand: expand as unknown as GraphSource['expand'] });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.truncated()).toBe(false); // the initial projection was complete

        await c.expandNode('c', 'C', ['C']);
        expect(c.truncated()).toBe(true);
    });

    it('expandNode leaves the flag alone when the neighborhood came back complete', async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'f', data: { label: 'F', kind: 'entity' } }],
            edges: [{ id: 'c->f', source: 'c', target: 'f', data: { kind: 'link' } }],
            truncated: false,
        }));
        const { fixture } = create({ expand: expand as unknown as GraphSource['expand'] });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        await c.expandNode('c', 'C', ['C']);
        expect(c.truncated()).toBe(false);
    });

    it('expandNode is a no-op when the source has no expand()', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        await c.expandNode('c', 'C', ['C']);
        expect(c.graph()).toEqual(GRAPH); // unchanged
    });

    it('undo/redo (Phase G): a sequence of display mutations restores exact prior signal values', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.canUndo()).toBe(false);

        c.setNodeColor('entity', TEST_COLOR);
        expect(c.nodeColors()).toEqual({ entity: TEST_COLOR });
        expect(c.canUndo()).toBe(true);

        c.toggleKind('other', false); // a second, independent mutation
        expect(c.kindFilter()).toEqual(['entity']);

        c.undoPresentation();
        expect(c.kindFilter()).toEqual([]); // back to before the toggle
        expect(c.nodeColors()).toEqual({ entity: TEST_COLOR }); // the color change is untouched
        expect(c.canRedo()).toBe(true);

        c.undoPresentation();
        expect(c.nodeColors()).toEqual({}); // back to before the color change
        expect(c.canUndo()).toBe(false);

        c.redoPresentation();
        c.redoPresentation();
        expect(c.nodeColors()).toEqual({ entity: TEST_COLOR });
        expect(c.kindFilter()).toEqual(['entity']);
        expect(c.canRedo()).toBe(false);
    });

    it('undo/redo: a fresh run() clears history (stale snapshots would reference a different graph)', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.setNodeColor('entity', TEST_COLOR);
        expect(c.canUndo()).toBe(true);
        await runQuery(fixture);
        expect(c.canUndo()).toBe(false);
    });

    it('exportGraphml (Phase F): downloads the displayed graph as generic GraphML', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
        c.exportGraphml();
        expect(clickSpy).toHaveBeenCalled();
        clickSpy.mockRestore();
    });

    it('smart form: auto-collapses to the selected-values summary after a run, and edit reopens it', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        expect(fixture.componentInstance.queryOpen()).toBe(true); // full form until something runs
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.queryOpen()).toBe(false); // collapsed — the summary + status bar take over
        expect(c.sourceLabel()).toBe('Entity/Link (from a Dataset)');
        expect(c.querySummary().map((i) => `${i.label}: ${i.value}`)).toEqual([
            'Dataset: Links',
            'Mapping: source → target',
        ]);

        c.queryDockOpen.set(false);
        c.editQuery(); // the status-bar pencil reopens the query dock with the form expanded
        expect(c.queryDockOpen()).toBe(true);
        expect(c.queryOpen()).toBe(true);
    });

    it('the View tools are hidden (not merely [hidden]) under the other toolbox tabs, and stay mounted', () => {
        // jsdom does not compute Tailwind, so pin the class contract: `.flex` beat the [hidden] attribute in the
        // browser (computed display: flex), which put Layout / Overlays / Lenses under Analysis and Investigation.
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.toolboxDockOpen.set(true);
        c.toolboxTab.set('analysis');
        fixture.detectChanges();
        const view = (): HTMLElement | null => fixture.nativeElement.querySelector('[data-toolbox-view]');
        expect(view()).not.toBeNull(); // still mounted
        expect(view()!.classList.contains('hidden')).toBe(true);
        expect(view()!.hasAttribute('hidden')).toBe(false); // the attribute Tailwind overrides is not relied on

        c.toolboxTab.set('investigation');
        fixture.detectChanges();
        expect(view()!.classList.contains('hidden')).toBe(true);

        c.toolboxTab.set('view');
        fixture.detectChanges();
        expect(view()!.classList.contains('hidden')).toBe(false);
    });

    it('workspace: a failed query keeps the form open; the docks open, collapse to rails and maximize', async () => {
        const { fixture } = create({ fail: true });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.queryOpen()).toBe(true); // a failing query needs its form back
        expect(c.querySummary()).toEqual([]);

        c.toolboxDockOpen.set(false);
        c.toolboxTab.set('view');
        c.openAnalysis(); // the toolbar algorithms icon opens the right dock on its Analysis tab
        expect(c.toolboxDockOpen()).toBe(true);
        expect(c.toolboxTab()).toBe('analysis');
        c.openViewTools();
        expect(c.toolboxTab()).toBe('view');

        // Maximize hides both docks in one step and a second call restores them.
        c.toggleCanvasMaximized();
        expect(c.canvasMaximized()).toBe(true);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelector('[aria-label="Show the query panel"]')).not.toBeNull(); // the rail
        expect(el.querySelector('[aria-label="Show the toolbox"]')).not.toBeNull();
        expect(el.querySelector('inspecto-link-analysis-query-panel')).toBeNull();
        c.toggleCanvasMaximized();
        expect(c.queryDockOpen()).toBe(true);
        expect(c.toolboxDockOpen()).toBe(true);
        fixture.detectChanges();
        expect(el.querySelector('inspecto-link-analysis-query-panel')).not.toBeNull();
        expect(el.querySelector('[aria-label="Resize the query panel (arrow keys or drag)"]')).not.toBeNull();
    });

    it('surfaces a failing source as an inline error, not a blank pane', async () => {
        const { fixture } = create({ fail: true });
        fixture.detectChanges();
        await runQuery(fixture);
        fixture.detectChanges(); // graph stays null on failure, so the G6 host never mounts
        expect(fixture.componentInstance.loadError()).toBe('bad mapping');
        expect(fixture.nativeElement.textContent).toContain('Query failed');
    });

    it('collapse/expand branches hide and restore a node’s downstream subtree', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        c.collapseBranch('b'); // a→b→c: hides c, keeps b
        expect(c.displayed()?.nodes.map((n) => n.id)).toEqual(['a', 'b', 'd', 'e']);
        c.expandBranch('b');
        expect(c.displayed()?.nodes).toHaveLength(5);

        c.collapseBranch('a');
        c.collapseBranch('d');
        expect(c.displayed()?.nodes.map((n) => n.id)).toEqual(['a', 'd']);
        c.expandAll();
        expect(c.displayed()?.nodes).toHaveLength(5);
    });

    it('display options travel with a saved view and are re-applied on load', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.displayCustomized()).toBe(false);
        c.edgeLabels.set(false);
        c.setNodeColor('entity', c.swatches[0]);
        c.setNodeColor('entity', c.swatches[1]); // re-pick replaces
        c.setEdgeColor('link', c.swatches[2]);
        c.setNodeShape('entity', 'diamond');
        c.setEdgePattern('link', 'dashed');
        c.setEdgeSize('link', 3);
        expect(c.displayCustomized()).toBe(true);

        c.saveForm.patchValue({ name: 'Styled' });
        await c.saveView();
        const saved = save.mock.calls[0][0];
        expect(saved.display).toEqual({
            nodeLabels: true,
            edgeLabels: false,
            nodeColors: { entity: c.swatches[1] },
            edgeColors: { link: c.swatches[2] },
            nodeShapes: { entity: 'diamond' },
            edgePatterns: { link: 'dashed' },
            edgeSizes: { link: 3 },
        });

        c.setNodeColor('entity', null); // drift away, then load restores the captured styling
        c.setNodeShape('entity', null);
        c.setEdgePattern('link', null);
        c.edgeLabels.set(true);
        await c.loadView(saved);
        expect(c.edgeLabels()).toBe(false);
        expect(c.nodeColors()).toEqual({ entity: c.swatches[1] });
        expect(c.nodeShapes()).toEqual({ entity: 'diamond' });
        expect(c.edgePatterns()).toEqual({ link: 'dashed' });
        expect(c.edgeSizes()).toEqual({ link: 3 });

        await c.loadView({ id: 'plain', name: 'Plain', sourceId: 'entity-projection', query: {} });
        expect(c.displayCustomized()).toBe(false); // a view without display resets to defaults
    });

    it('all-link-labels toggle: off by default, persisted in the saved view display, restored on load', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.allEdgeLabels()).toBe(false);
        expect(c.displayOptions().allEdgeLabels).toBeUndefined(); // absent = the density rule applies
        c.allEdgeLabels.set(true);
        expect(c.displayOptions().allEdgeLabels).toBe(true);
        expect(c.edgeLabelsDense()).toBe(false);
        expect(c.displayCustomized()).toBe(true);

        c.saveForm.patchValue({ name: 'All labels' });
        await c.saveView();
        const saved = save.mock.calls[0][0];
        expect(saved.display.allEdgeLabels).toBe(true);

        c.allEdgeLabels.set(false);
        await c.loadView(saved);
        expect(c.allEdgeLabels()).toBe(true);
        await c.loadView({ id: 'plain', name: 'Plain', sourceId: 'entity-projection', query: {} });
        expect(c.allEdgeLabels()).toBe(false);
    });

    it('layout: defaults to dagre, gates tree layouts on the graph shape, and travels with a saved view', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.layoutId()).toBe('dagre');
        expect(c.isTreeShaped()).toBe(true); // GRAPH (a→b→c, d→e) is a forest — tree layouts enabled

        c.setLayout('radial');
        expect(c.layoutId()).toBe('radial');

        c.saveForm.patchValue({ name: 'Radial view' });
        await c.saveView();
        expect(save.mock.calls[0][0].layout).toBe('radial');

        c.setLayout('mindmap'); // drift, then load restores the captured layout
        await c.loadView({ id: 'r', name: 'Radial view', sourceId: 'entity-projection', query: {}, layout: 'radial' });
        expect(c.layoutId()).toBe('radial');

        await c.loadView({ id: 'plain', name: 'Plain', sourceId: 'entity-projection', query: {} });
        expect(c.layoutId()).toBe('dagre'); // a view without a layout resets to the default
    });

    it('the bottom panel tables the displayed graph and narrows with the search', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        expect(c.tableRows()).toHaveLength(3); // links mode: all three edges
        c.tableMode.set('nodes');
        expect(c.tableRows()).toHaveLength(5);
        expect(c.tableRows()[0]).toEqual({ label: 'A', kind: 'entity', links: 1, id: 'a' });

        c.onSearch('a'); // search narrows the table to the matched node…
        expect(c.tableRows()).toEqual([{ label: 'A', kind: 'entity', links: 1, id: 'a' }]);
        c.tableMode.set('links');
        expect(c.tableRows().map((r) => r['id'])).toEqual(['a->b']); // …and to links touching it

        c.toggleKind('other', false); // the kind filter flows through too
        c.onSearch('');
        expect(c.tableRows().map((r) => r['id'])).toEqual(['a->b', 'b->c']);
    });

    it('saves a view (duplicate name blocked inline) and reloads a saved view', async () => {
        const existing: LinkAnalysisView = { id: 'ring', name: 'Ring', sourceId: 'entity-projection', query: {} };
        const { fixture, save, queried } = create({ views: [existing] });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;

        c.saveForm.patchValue({ name: 'Ring' });
        await c.saveView();
        expect(c.saveForm.controls.name.hasError('duplicate')).toBe(true);
        expect(save).not.toHaveBeenCalled();

        c.saveForm.patchValue({ name: 'Device ring' });
        await c.saveView();
        expect(save).toHaveBeenCalledOnce();
        expect(c.views().map((v) => v.name)).toContain('Device ring');

        await c.loadView(existing);
        expect(queried.length).toBeGreaterThan(1);
        expect(c.sourceId()).toBe('entity-projection');
    });

    it('opens the shared version-history dialog for a saved view and reloads on restore', async () => {
        const view: LinkAnalysisView = { id: 'ring', name: 'Ring', sourceId: 'entity-projection', query: {} };
        const { fixture } = create({ views: [view] });
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const dialog = fixture.debugElement.injector.get(MatDialog);
        const reloadSpy = vi.spyOn(c, 'reloadViews');

        // restore succeeded → dialog closes true → the list is reloaded
        const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(true) } as never);
        c.openHistory(view);
        expect((openSpy.mock.calls[0][1] as { data: unknown }).data).toEqual({
            type: 'link-analysis-view',
            id: 'ring',
            label: 'Ring',
        });
        expect(reloadSpy).toHaveBeenCalledTimes(1);

        // closed without restoring → no reload
        openSpy.mockReturnValue({ afterClosed: () => of(undefined) } as never);
        c.openHistory(view);
        expect(reloadSpy).toHaveBeenCalledTimes(1);
    });
    it('domain profile: renames the working-set tiles, badges suggested tools, and travels with a saved view', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.workingSet().map((s) => s.label)).toEqual(['Nodes', 'Links']); // generic: no counts/attrs in GRAPH
        expect(c.profile().suggestedTools).toEqual([]);

        c.profileControl.setValue('finance');
        expect(c.workingSet().map((s) => s.label)).toEqual(['Accounts', 'Transfers']);
        expect(c.profile().suggestedTools).toContain('scoring');

        c.saveForm.setValue({ name: 'Falcon', description: '' });
        await c.saveView();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ profile: 'finance' }), expect.anything());

        await c.loadView({ id: 'x', name: 'x', sourceId: 'entity-projection', query: c.lastRun()!.query });
        expect(c.profileId()).toBe('generic'); // a view without a profile resets to generic
    });
    it('two-stage loop: a local predicate narrows the canvas for free; a push marks stranded nodes, never drops them', async () => {
        const FILTERED: G6GraphData = { nodes: GRAPH.nodes.slice(0, 3), edges: GRAPH.edges.slice(0, 2) }; // a–b–c only
        const { fixture, queried } = create({ filtered: FILTERED });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.filterColumns().map((col) => col.name)).toEqual(['source', 'target', 'kind', 'count']);
        expect(c.localMatch()).toEqual({ matched: 3, total: 3 });

        // Stage 1 — edit the tree (as the editor would, in place) and apply locally: no query issued.
        c.filterWhere().items.push({ kind: 'condition', field: 'source', operator: '=', value: 'D' });
        c.onFilterChanged();
        expect(c.localMatch()).toEqual({ matched: 1, total: 3 });
        c.applyFilterLocally();
        expect(c.displayed()!.edges.map((e) => e.id)).toEqual(['d->e']);
        expect(c.displayed()!.nodes.map((n) => n.id)).toEqual(['d', 'e']);
        expect(queried).toHaveLength(1);

        // Stage 2 — push: the query carries the tree as `filter`; the result merges over the working set.
        await c.pushFilter();
        expect(queried).toHaveLength(2);
        expect((queried[1] as { filter?: unknown }).filter).toMatchObject({ kind: 'group', op: 'AND' });
        expect(c.localFilter()).toBeNull(); // the server applied it
        expect(c.pushState()).toBe('2 links · complete');
        const g = c.graph()!;
        expect(g.edges.map((e) => e.id)).toEqual(['a->b', 'b->c']);
        expect(g.nodes.filter((n) => n.data.missing).map((n) => n.id)).toEqual(['d', 'e']); // marked, kept
        expect(c.strandedCount()).toBe(2);
        expect(c.lastRun()!.query.filter).toBeDefined(); // a saved view now persists the predicate

        c.clearFilter();
        expect(c.filterWhere().items).toEqual([]);

        // Loading a view that carries a predicate seeds the builder (and a view without one clears it).
        await c.loadView({ id: 'v', name: 'v', sourceId: 'entity-projection', query: c.lastRun()!.query });
        expect(c.filterWhere().items).toHaveLength(1);
        expect(c.pushState()).toBe('sent with the query');
        await c.loadView({
            id: 'w',
            name: 'w',
            sourceId: 'entity-projection',
            query: { ...c.lastRun()!.query, filter: undefined },
        });
        expect(c.filterWhere().items).toEqual([]);
    });
    it('advanced search: needs a loaded Dataset projection, then adopts the folded result with stranded marks', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        const dialog = fixture.debugElement.injector.get(MatDialog);
        const open = vi.spyOn(dialog, 'open').mockReturnValue({
            afterClosed: () =>
                of({
                    nodes: [{ id: 'a', data: { label: 'A', kind: 'entity' } }],
                    edges: [],
                    truncated: false,
                }),
        } as never);

        c.openAdvancedSearch(); // nothing loaded yet — a hint, not a dialog
        expect(open).not.toHaveBeenCalled();

        await runQuery(fixture);
        c.openAdvancedSearch();
        expect(open).toHaveBeenCalledTimes(1);
        const data = (open.mock.calls[0] as unknown as [unknown, { data: { dataset: Dataset } }])[1].data;
        expect(data.dataset.id).toBe('links-ds');
        expect(c.graph()!.nodes.map((n) => [n.id, !!n.data.missing])).toEqual([
            ['a', false],
            ['b', true],
            ['c', true],
            ['d', true],
            ['e', true],
        ]);
        expect(c.pushState()).toBe('0 links · from advanced search');
    });
    it('view toolbox: plugin and behavior toggles build the canvas plugin set, hulls follow communities, and the view persists them', async () => {
        const { fixture, save } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.canvasPlugins()).toEqual({ minimap: true, behaviors: ['hover-activate'], hulls: null });

        c.toggleViewFlag('minimap', false);
        c.toggleViewFlag('hulls', true);
        c.toggleBehavior('brush-select', true);
        c.toggleBehavior('hover-activate', false);
        const p = c.canvasPlugins();
        expect(p.minimap).toBe(false);
        expect(p.behaviors).toEqual(['brush-select']);
        expect([...p.hulls!.values()].map((m) => m.length).sort()).toEqual([2, 3]); // a–b–c and d–e

        c.legendOpen.set(false);
        c.saveForm.setValue({ name: 'Hulls', description: '' });
        await c.saveView();
        const saved = save.mock.calls[0][0] as LinkAnalysisView;
        expect(saved.view).toEqual({
            plugins: { minimap: false, hulls: true, behaviors: ['brush-select'] },
            legend: false,
            workingSet: true,
        });

        await c.loadView({ id: 'x', name: 'x', sourceId: 'entity-projection', query: c.lastRun()!.query });
        expect(c.legendOpen()).toBe(true); // a view without options resets the overlays
        expect(c.viewOptions().hulls).toBe(true); // …but keeps the plugin set the analyst last chose
    });
    it('evidence: snapshot freezes the displayed graph (stranded excluded) and Attach to Case snapshots first when none exists', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const dialog = fixture.debugElement.injector.get(MatDialog);
        const snap = { id: 'snp-1', title: 'T', manifestHash: 'abcdef0123456789', attachedTo: [] };
        const open = vi
            .spyOn(dialog, 'open')
            .mockReturnValueOnce({ afterClosed: () => of(snap) } as never) // the snapshot dialog
            .mockReturnValueOnce({ afterClosed: () => of({ caseId: 'CASE-1' }) } as never); // the attach dialog

        expect(c.latestSnapshot()).toBeNull();
        await c.openAttachToCase();
        expect(open).toHaveBeenCalledTimes(2);
        const snapData = (
            open.mock.calls[0] as unknown as [unknown, { data: { graph: G6GraphData; origin: unknown } }]
        )[1].data;
        expect(snapData.graph.nodes).toHaveLength(5);
        expect(snapData.origin).toMatchObject({ sourceId: 'entity-projection', dataset: 'links-ds' });
        expect((snapData as { investigationId?: string }).investigationId).toBeUndefined(); // none open
        const attachData = (open.mock.calls[1] as unknown as [unknown, { data: { snapshot: unknown } }])[1].data;
        expect(attachData.snapshot).toBe(snap);
        expect(c.latestSnapshot()).toBe(snap);

        await runQuery(fixture); // a fresh graph is a new answer — the old snapshot no longer describes it
        expect(c.latestSnapshot()).toBeNull();
    });
    it('evidence: a snapshot taken while an Investigation is open carries its id (the Dossier anchor)', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const open = vi
            .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
            .mockReturnValueOnce({ afterClosed: () => of(undefined) } as never);
        c.investigation.activeId.set('inv-7');
        await c.openSnapshot();
        const data = (open.mock.calls[0] as unknown as [unknown, { data: { investigationId?: string } }])[1].data;
        expect(data.investigationId).toBe('inv-7');
    });
    it('evidence A1: a snapshot captures what the canvas shows — the Working Set, else the query graph', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const open = vi
            .spyOn(fixture.debugElement.injector.get(MatDialog), 'open')
            .mockReturnValue({ afterClosed: () => of(undefined) } as never);
        type Data = { graph: G6GraphData; investigationId?: string; origin: { sourceId: string; dataset?: string } };
        const dataAt = (i: number) => (open.mock.calls[i] as unknown as [unknown, { data: Data }])[1].data;

        await c.openSnapshot(); // no Investigation: the query graph is on screen
        expect(dataAt(0).graph).toBe(c.canvasData());
        expect(dataAt(0).graph.nodes).toHaveLength(5);

        c.investigation.activeId.set('inv-9');
        c.investigation.log.set({
            header: { dataset: 'links-ds', sourceCol: 'src', targetCol: 'dst' },
            entries: [],
        } as never);
        c.investigation.workingSet.set({
            entities: [{ id: 'ws-a' }, { id: 'ws-b' }],
            links: [{ source: 'ws-a', target: 'ws-b', kind: 'k', count: 1 }],
            excluded: [],
            hash: 'h',
        } as never);
        expect(c.investigation.canvas()).not.toBeNull();
        await c.openSnapshot();
        const ws = dataAt(1);
        expect(ws.graph).toBe(c.canvasData());
        expect(ws.graph.nodes.map((n) => n.data.label).sort()).toEqual(['ws-a', 'ws-b']);
        expect(ws.investigationId).toBe('inv-9');
        expect(ws.origin).toMatchObject({ sourceId: 'investigation', dataset: 'links-ds' });

        c.investigation.showWorkingSet.set(false); // the query graph is drawn again while it stays open
        await c.openSnapshot();
        expect(dataAt(2).graph).toBe(c.canvasData());
        expect(dataAt(2).graph.nodes).toHaveLength(5);
    });
    it('the query graph "Truncated" alert shows on the query graph, not over an open Investigation canvas, and returns after', async () => {
        const expand = vi.fn(async () => ({
            nodes: [{ id: 'f', data: { label: 'F', kind: 'entity' } }],
            edges: [{ id: 'c->f', source: 'c', target: 'f', data: { kind: 'link' } }],
            truncated: true,
        }));
        const { fixture } = create({ expand: expand as unknown as GraphSource['expand'] });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        await c.expandNode('c', 'C', ['C']);
        const alert = () => (fixture.nativeElement as HTMLElement).textContent?.includes('projection hit the node cap');
        fixture.detectChanges();
        expect(alert()).toBe(true);

        c.investigation.activeId.set('inv-1');
        c.investigation.log.set({ header: { dataset: 'd', sourceCol: 's', targetCol: 't' }, entries: [] } as never);
        c.investigation.workingSet.set({
            entities: [{ id: 'a' }, { id: 'b' }],
            links: [{ source: 'a', target: 'b', kind: 'k', count: 1 }],
            excluded: [],
            hash: 'h',
        } as never);
        fixture.detectChanges();
        expect(c.truncated()).toBe(true);
        expect(alert()).toBe(false);

        c.investigation.close();
        fixture.detectChanges();
        expect(alert()).toBe(true);
        fixture.destroy();
    });
    it('From / To pickers list the nodes the canvas draws: over an Investigation canvas every option resolves to a server id, on the query graph they are the query graph', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        const queryLabels = c.nodeOptions().map((o) => o.label);
        expect(queryLabels.length).toBeGreaterThan(0);

        c.investigation.activeId.set('inv-1');
        c.investigation.log.set({ header: { dataset: 'd', sourceCol: 's', targetCol: 't' }, entries: [] } as never);
        c.investigation.workingSet.set({
            entities: [{ id: 'ws-a' }, { id: 'ws-b' }],
            links: [{ source: 'ws-a', target: 'ws-b', kind: 'k', count: 1 }],
            excluded: [],
            hash: 'h',
        } as never);
        fixture.detectChanges();
        // Reported: the options were the QUERY graph's, so a pick never resolved through the Working Set's ServerIdMap.
        expect(c.nodeOptions().map((o) => o.label)).toEqual(['ws-a', 'ws-b']);
        for (const o of c.nodeOptions()) expect(c.serverIds()!.serverNode(o.id)).toBeTruthy();

        c.investigation.close();
        fixture.detectChanges();
        expect(c.nodeOptions().map((o) => o.label)).toEqual(queryLabels);
        fixture.destroy();
    });
    it('footer: a Working Set over the link render ceiling says how many links were left off; below it, nothing', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        c.investigation.activeId.set('inv-9');
        c.investigation.log.set({ header: { dataset: 'd', sourceCol: 's', targetCol: 't' }, entries: [] } as never);
        const ws = (links: number) => ({
            entities: Array.from({ length: 200 }, (_, i) => ({ id: `n${i}`, hidden: false })),
            links: Array.from({ length: links }, (_, k) => ({
                source: `n${Math.floor(k / 199)}`,
                target: `n${(Math.floor(k / 199) + 1 + (k % 199)) % 200}`,
                kind: 'k',
                count: 1,
                admittedBy: 2,
            })),
            excluded: [],
            hash: 'h',
        });
        c.investigation.workingSet.set(ws(5003) as never);
        fixture.detectChanges();
        const footer = (fixture.nativeElement as HTMLElement).querySelector('[aria-label="Render status"]')!;
        expect(footer.textContent).toContain('Showing 5,000 of 5,003 links (render limit)');
        expect(c.workingSetCanvas()).toBe(true);

        c.investigation.workingSet.set(ws(40) as never);
        fixture.detectChanges();
        expect(footer.textContent).not.toContain('render limit');
        expect(footer.textContent).toContain('40 links drawn');
        fixture.destroy();
        // Builds and renders a 5,003-link Working Set; the CI runner under coverage took more than the default 5 s (run 36952537914).
    }, 30_000);
    it('level of detail: drops labels above the cap while on, and the footer states the published caps', async () => {
        const big: G6GraphData = {
            nodes: Array.from({ length: 301 }, (_, i) => ({ id: `n${i}`, data: { label: `N${i}`, kind: 'entity' } })),
            edges: [],
        };
        const { fixture } = create({ graph: big });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.lodLabelsOff()).toBe(true);
        expect(c.displayOptions().nodeLabels).toBe(false);
        expect(c.displayOptions().edgeLabels).toBe(false);

        c.levelOfDetail.set(false);
        expect(c.displayOptions().nodeLabels).toBe(true);
        expect(c.caps).toEqual({ projection: 500, analysis: 2000 });
    });

    it('LA-11 find paths: sends the RAW spelling over the loaded mapping, merges the walk and highlights it', async () => {
        const minted: G6GraphData = {
            nodes: [
                { id: 'entity:acme', data: { label: 'ACME', kind: 'entity', spellings: ['ACME', 'acme.'] } },
                { id: 'entity:bob', data: { label: 'Bob', kind: 'entity', spellings: ['Bob'] } },
            ],
            edges: [
                {
                    id: 'entity:acme->entity:bob:link',
                    source: 'entity:acme',
                    target: 'entity:bob',
                    data: { kind: 'link' },
                },
            ],
        };
        const sent: RecursivePathsRequest[] = [];
        const recursivePaths = vi.fn((req: RecursivePathsRequest) => {
            sent.push(req);
            return of({
                paths: [{ nodes: ['ACME', 'Bob', 'Cara'], hops: 2, weight: null }],
                truncated: false,
                edgeYieldCapped: true,
                fences: { maxDepth: 3, maxEdgeYield: 10000, timeoutMs: 5000 },
            });
        });
        const { fixture } = create({ graph: minted, inv: { recursivePaths } });
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        expect(c.traversalMappingOptions()).toEqual([{ value: '0', label: 'Links: source → target' }]);

        await c.findPaths({ from: 'entity:acme', mapping: 0, maxDepth: 3, direction: 'DIRECTED' });
        expect(sent[0]).toMatchObject({
            dataset: 'links-ds',
            sourceCol: 'source',
            targetCol: 'target',
            startNode: 'ACME', // the first raw spelling, never the normalised id
            targetNode: undefined,
            maxDepth: 3,
            direction: 'DIRECTED',
        });
        expect(c.graph()?.nodes.map((n) => n.id)).toEqual(['entity:acme', 'entity:bob', 'entity:cara']);
        expect(c.emphasis()?.nodeIds).toEqual(['entity:acme', 'entity:bob', 'entity:cara']);
        expect(c.emphasis()?.edgeIds[0]).toBe('entity:acme->entity:bob:link');
        expect(c.serverPaths()).toMatchObject({ edgeYieldCapped: true, depthLimit: 3, deepest: 2 });
    });

    /** LA-A11Y-AUDIT-1: an empty role=menu panel (text only) breaks aria-required-children, so the empty text is a menuitem. */
    it('the empty saved-views menu holds a (disabled) menu item, not bare text', () => {
        const { fixture } = create({ views: [] });
        fixture.detectChanges();
        (fixture.nativeElement.querySelector('button[aria-label="Open saved views"]') as HTMLElement).click();
        fixture.detectChanges();
        const panel = document.querySelector('.mat-mdc-menu-panel') as HTMLElement;
        expect(panel.getAttribute('role')).toBe('menu');
        const item = panel.querySelector('[role="menuitem"]') as HTMLElement;
        expect(item.textContent).toContain('Nothing saved yet.');
        expect(item.hasAttribute('disabled')).toBe(true);
    });
    describe('toolbox tablist (LA-A11Y-AUDIT-1: the Analysis / View / Investigation switch)', () => {
        const open = () => {
            const { fixture } = create();
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            const tabs = (): HTMLElement[] => Array.from(el.querySelectorAll<HTMLElement>('[role="tab"]'));
            const press = (k: string): void => {
                tabs()
                    .find((t) => t.tabIndex === 0)!
                    .dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
                fixture.detectChanges();
            };
            return { fixture, c: fixture.componentInstance, el, tabs, press };
        };

        it('keeps the keyboard order the reader sees: no positive tabindex, the Query dock before the Toolbox dock, one tab stop in the tablist', () => {
            const { el, tabs } = open();
            const positive = Array.from(el.querySelectorAll<HTMLElement>('[tabindex]')).filter((n) => n.tabIndex > 0);
            expect(positive).toEqual([]); // a positive tabindex reorders focus ahead of everything else
            const query = el.querySelector('aside[aria-label="Query"]')!;
            const toolbox = el.querySelector('aside[aria-label="Toolbox"]')!;
            expect(query.compareDocumentPosition(toolbox) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
            expect(tabs().filter((t) => t.tabIndex === 0)).toHaveLength(1);
        });

        it('stacks the three docks below md so 400% zoom / 320 px reflows (live-proven 2026-10-05: a row of two fixed-width docks left the canvas 0 px wide)', () => {
            const { el } = open();
            const workspace = el.querySelector('aside[aria-label="Query"]')!.parentElement as HTMLElement;
            expect(workspace.classList).toContain('max-md:flex-col');
            for (const dock of ['Query', 'Toolbox']) {
                expect(el.querySelector(`aside[aria-label="${dock}"]`)!.classList).toContain('max-md:!w-full'); // beats the inline px width
            }
            // the drag handles have nothing to drag once the docks stack
            const handles = Array.from(el.querySelectorAll('[aria-label^="Resize the"]'));
            expect(handles).toHaveLength(2);
            for (const handle of handles) {
                expect(handle.classList).toContain('max-md:hidden');
            }
        });

        it('is a tablist of three tabs, one tab stop, whose panes are tabpanels labelled by their tab', () => {
            const { el, tabs } = open();
            const list = el.querySelector('[role="tablist"]') as HTMLElement;
            expect(list.getAttribute('aria-label')).toBe('Toolbox tab');
            expect(tabs().map((t) => t.textContent?.trim())).toEqual(['Analysis', 'View', 'Investigation']);
            expect(tabs().map((t) => t.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false']);
            expect(tabs().map((t) => t.tabIndex)).toEqual([0, -1, -1]);
            expect(list.parentElement!.querySelector('[role="radio"], [role="radiogroup"]')).toBeNull(); // the old switch
            const panel = el.querySelector('#la-toolbox-panel-analysis') as HTMLElement;
            expect(panel.getAttribute('role')).toBe('tabpanel');
            expect(panel.getAttribute('aria-labelledby')).toBe('la-toolbox-tab-analysis');
            expect(tabs()[0].getAttribute('aria-controls')).toBe('la-toolbox-panel-analysis');
            const view = el.querySelector('[data-toolbox-view]') as HTMLElement;
            expect(view.getAttribute('role')).toBe('tabpanel');
            expect(view.getAttribute('aria-labelledby')).toBe('la-toolbox-tab-view');
            // the Investigation pane is only mounted while it is the tab: no dangling aria-controls
            expect(el.querySelector('#la-toolbox-panel-investigation')).toBeNull();
            expect(tabs()[2].hasAttribute('aria-controls')).toBe(false);
        });

        it('click and arrow keys select (automatic activation), wrap, and Home / End jump; the tab stop roves', () => {
            const { fixture, c, tabs, press } = open();
            const state = () => [c.toolboxTab(), tabs().findIndex((t) => t.tabIndex === 0)];
            tabs()[1].click();
            fixture.detectChanges();
            expect(state()).toEqual(['view', 1]);
            press('ArrowRight');
            expect(state()).toEqual(['investigation', 2]);
            expect(document.activeElement).toBe(tabs()[2]);
            press('ArrowRight'); // wraps
            expect(state()).toEqual(['analysis', 0]);
            press('ArrowLeft'); // wraps back
            expect(state()).toEqual(['investigation', 2]);
            press('Home');
            expect(state()).toEqual(['analysis', 0]);
            press('End');
            expect(state()).toEqual(['investigation', 2]);
            press('a'); // any other key is left alone
            expect(state()).toEqual(['investigation', 2]);
        });

        it('the Investigation pane mounts as a labelled tabpanel on its tab, and selecting a tab does not move focus into a panel', () => {
            const { fixture, el, tabs, press } = open();
            tabs()[0].focus();
            press('End');
            const panel = el.querySelector('#la-toolbox-panel-investigation') as HTMLElement;
            expect(panel.getAttribute('role')).toBe('tabpanel');
            expect(panel.getAttribute('aria-labelledby')).toBe('la-toolbox-tab-investigation');
            expect(tabs()[2].getAttribute('aria-controls')).toBe('la-toolbox-panel-investigation');
            expect(document.activeElement).toBe(tabs()[2]);
            expect(panel.contains(document.activeElement)).toBe(false);
            expect(fixture.componentInstance.toolboxTab()).toBe('investigation');
        });

        it('the collapsed rail still switches the tab (openInvestigation unchanged)', () => {
            const { fixture, c, el } = open();
            c.openInvestigation();
            fixture.detectChanges();
            expect(c.toolboxTab()).toBe('investigation');
            expect(el.querySelector('#la-toolbox-tab-investigation')!.getAttribute('aria-selected')).toBe('true');
        });
    });

    describe('tool rail (the former top toolbar, docked on the LEFT for canvas height)', () => {
        const ready = async () => {
            const { fixture } = create({ stubGraph: true });
            fixture.detectChanges();
            await runQuery(fixture);
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            return { fixture, el, rail: el.querySelector('nav[data-testid="la-tool-rail"]') as HTMLElement };
        };

        it('is a labelled nav that sits BEFORE the workspace in the same row, so the canvas starts at the title row', async () => {
            const { el, rail } = await ready();
            expect(rail.getAttribute('aria-label')).toBe('Link Analysis tools');
            const workspace = el.querySelector('aside[aria-label="Query"]')!.parentElement as HTMLElement;
            expect(rail.parentElement).toBe(workspace.parentElement); // one row: [rail | workspace]
            expect(rail.compareDocumentPosition(workspace) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
            expect(rail.classList).toContain('flex-col');
            expect(rail.classList).toContain('max-md:flex-row'); // stacked layout keeps a wrapped row
        });

        it('keeps every action reachable by its accessible name', async () => {
            const { rail } = await ready();
            const names = Array.from(rail.querySelectorAll('button')).map((b) => b.getAttribute('aria-label') ?? '');
            for (const name of [
                'Search nodes',
                'Filter node types',
                'Graph layout',
                'Save this analysis',
                'Attach to Case',
                'Open the graph-algorithms toolbox',
                'Open the Investigation',
                'Open saved views',
                'Save the current view',
                'Undo the last display/filter change',
                'Redo the last undone display/filter change',
                'Fit the graph to the screen',
                'Show as list',
                'View the graph in full screen',
                'Export the graph',
            ]) {
                expect(
                    names.some((n) => n.startsWith(name)),
                    name,
                ).toBe(true);
            }
            expect(names.every((n) => n.length > 0)).toBe(true); // an icon-only button without a name is an axe failure
        });

        it('groups the four exports behind one Export menu whose items keep their names', async () => {
            const { fixture, rail } = await ready();
            expect(rail.querySelector('[aria-label="Export as PNG"]')).toBeNull(); // not loose in the rail
            (rail.querySelector('button[aria-label="Export the graph"]') as HTMLElement).click();
            fixture.detectChanges();
            const panel = document.querySelector('.mat-mdc-menu-panel') as HTMLElement;
            for (const f of ['PNG', 'JSON', 'SVG', 'GraphML']) {
                expect(panel.querySelector(`[aria-label="Export as ${f}"]`), f).not.toBeNull();
            }
        });

        it('Hide the side panels still collapses both docks and the rail stays', async () => {
            const { fixture, el, rail } = await ready();
            (el.querySelector('button[aria-label="Hide the side panels"]') as HTMLElement).click();
            fixture.detectChanges();
            expect(el.querySelector('inspecto-link-analysis-query-panel')).toBeNull();
            expect(el.querySelector('button[aria-label="Show the side panels"]')).not.toBeNull();
            expect(el.querySelector('nav[data-testid="la-tool-rail"]')).toBe(rail);
        });

        it('the workspace fills the remaining height instead of a fixed calc()', async () => {
            const { el } = await ready();
            const workspace = el.querySelector('aside[aria-label="Query"]')!.parentElement as HTMLElement;
            expect(workspace.classList).toContain('flex-auto');
            expect(workspace.style.height).toBe('');
        });
    });

    describe('graph as a list (LA-A11Y-AUDIT-1: the canvas text alternative)', () => {
        const ready = async () => {
            const { fixture } = create({ stubGraph: true });
            fixture.detectChanges();
            await runQuery(fixture);
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            const toggle = (): HTMLButtonElement =>
                el.querySelector('[data-testid="graph-list-toggle"]') as HTMLButtonElement;
            const rowIds = (): (string | undefined)[] =>
                Array.from(el.querySelectorAll<HTMLElement>('table[role="grid"] tbody tr')).map(
                    (r) => r.dataset['nodeId'],
                );
            return { fixture, c: fixture.componentInstance, el, toggle, rowIds };
        };
        const openWorkingSet = (c: LinkAnalysisComponent, ids: string[]): void => {
            c.investigation.activeId.set('inv-9');
            c.investigation.log.set({
                header: { dataset: 'links-ds', sourceCol: 'src', targetCol: 'dst' },
                entries: [],
            } as never);
            c.investigation.workingSet.set({
                entities: ids.map((id) => ({ id })),
                links: ids.length > 1 ? [{ source: ids[0], target: ids[1], kind: 'k', count: 1 }] : [],
                excluded: [],
                hash: 'h',
            } as never);
        };

        it('the toggle is a pressed button that swaps the canvas for the list and keeps focus on itself', async () => {
            const { fixture, el, toggle, rowIds } = await ready();
            expect(toggle().getAttribute('aria-pressed')).toBe('false');
            expect(toggle().getAttribute('aria-label')).toBe('Show as list');
            expect(el.querySelector('inspecto-link-analysis-node-list')).toBeNull();
            toggle().focus();
            toggle().click();
            fixture.detectChanges();
            expect(toggle().getAttribute('aria-pressed')).toBe('true');
            expect(toggle().getAttribute('aria-label')).toBe('Show graph');
            expect(document.activeElement).toBe(toggle());
            expect(rowIds()).toEqual(['a', 'b', 'c', 'd', 'e']);
            // the canvas behind the list is out of the tab order and the accessibility tree
            expect(el.querySelector('inspecto-graph-view')!.hasAttribute('inert')).toBe(true);
            toggle().click();
            fixture.detectChanges();
            expect(el.querySelector('inspecto-link-analysis-node-list')).toBeNull();
            expect(el.querySelector('inspecto-graph-view')!.hasAttribute('inert')).toBe(false);
            expect(document.activeElement).toBe(toggle());
        });

        it('the canvas figure points to the list for a text version', async () => {
            const { fixture } = await ready();
            const view = fixture.debugElement.query(By.directive(StubGraphView)).componentInstance as StubGraphView;
            expect(view.textHint).toBe('Use Show as list for a text version.');
        });

        it('lists what the canvas draws: over an open Investigation that is the Working Set, not the query graph', async () => {
            const { fixture, c, toggle, rowIds } = await ready();
            openWorkingSet(c, ['****0001', '****0002']);
            toggle().click();
            fixture.detectChanges();
            expect(rowIds()).toEqual(c.canvasData()!.nodes.map((n) => n.id));
            expect(rowIds()).not.toEqual(['a', 'b', 'c', 'd', 'e']);
            expect(rowIds()).toHaveLength(2);
            expect((fixture.nativeElement as HTMLElement).textContent).toContain('****0001'); // masked as drawn
        });

        it('activating a row takes the same path as a canvas click', async () => {
            const { fixture, c, el, toggle } = await ready();
            const click = vi.spyOn(c, 'onNodeClick').mockImplementation(() => undefined);
            toggle().click();
            fixture.detectChanges();
            const row = el.querySelector('table[role="grid"] tbody tr[data-node-id="c"]') as HTMLElement;
            row.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
            fixture.detectChanges();
            expect(click).toHaveBeenCalledWith('c');
        });

        it('carries the Working Set render-limit notice into the list', async () => {
            const { fixture, c, el, toggle } = await ready();
            openWorkingSet(c, ['x']);
            vi.spyOn(c, 'omittedLinks').mockReturnValue(40);
            toggle().click();
            fixture.detectChanges();
            expect(el.querySelector('[data-testid="node-list-omitted"]')?.textContent).toContain('40 more links');
        });
    });

    describe('sealTemporal (LA-INVESTIGATION-OPS-DEFERRED-1)', () => {
        const REQ = { mapping: 0, mode: 'burst', series: 'link', windowSeconds: 60, minEvents: 5, maxCv: 0.2 };
        /** The scan panel is spec'd on its own; here the host's seal wiring is driven from the state a run leaves. */
        function scanned() {
            const { fixture } = create();
            fixture.detectChanges();
            const c = fixture.componentInstance;
            (c as unknown as { lastTemporalRun: unknown }).lastTemporalRun = { ...REQ };
            c.temporalFindings.set({} as never);
            return c;
        }

        it('sends the scan knobs of the shown run as a temporal op and records what was sealed', async () => {
            const c = scanned();
            const apply = vi.spyOn(c.investigation, 'apply').mockResolvedValue(true);
            c.investigation.lastStep.set({
                step: 4,
                temporalFindings: { count: 2, outsideWorkingSet: 1, fingerprint: 'abc' },
            } as never);
            await c.sealTemporal();
            expect(apply).toHaveBeenCalledWith({
                op: 'temporal',
                mode: 'burst',
                series: 'link',
                minEvents: 5,
                windowSeconds: 60,
            });
            expect(c.temporalSealed()).toEqual({ step: 4, count: 2, outsideWorkingSet: 1, fingerprint: 'abc' });
            expect(c.temporalSealError()).toBe('');
        });

        it('sends maxCv, not windowSeconds, for periodicity', async () => {
            const c = scanned();
            (c as unknown as { lastTemporalRun: unknown }).lastTemporalRun = { ...REQ, mode: 'periodicity' };
            const apply = vi.spyOn(c.investigation, 'apply').mockResolvedValue(true);
            await c.sealTemporal();
            expect(apply).toHaveBeenCalledWith({
                op: 'temporal',
                mode: 'periodicity',
                series: 'link',
                minEvents: 5,
                maxCv: 0.2,
            });
        });

        it('shows the server refusal and records no seal', async () => {
            const c = scanned();
            vi.spyOn(c.investigation, 'apply').mockResolvedValue(false);
            c.investigation.error.set('this Investigation has no time column');
            await c.sealTemporal();
            expect(c.temporalSealError()).toBe('this Investigation has no time column');
            expect(c.temporalSealed()).toBeNull();
        });

        it('does nothing when no scan is showing', async () => {
            const { fixture } = create();
            fixture.detectChanges();
            const apply = vi.spyOn(fixture.componentInstance.investigation, 'apply');
            await fixture.componentInstance.sealTemporal();
            expect(apply).not.toHaveBeenCalled();
        });
    });
});

describe('LinkAnalysisComponent - starter cards and result actions (operator 2026-10-10)', () => {
    const MONEY: LinkAnalysisView = {
        id: 'mule_layering_ring',
        name: 'mule_layering_ring',
        description: 'Money-mule layering ring. Planted inside ordinary transfers.',
        sourceId: 'entity-projection',
        query: { projection: { datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target' } },
    };
    const OTHER: LinkAnalysisView = {
        id: 'roaming',
        name: 'roaming',
        description: 'Roaming footprint',
        sourceId: 'entity-projection',
        query: { projection: { datasetId: 'links-ds', sourceCol: 'source', targetCol: 'target' } },
    };
    const card = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLButtonElement | null;

    it('replaces the empty canvas with starter cards; the Explore card is always there', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('No graph yet');
        expect(card(el, 'starter-explore')).toBeTruthy();
        // nothing saved in this Space: the cards that need a saved view are hidden, not dead
        expect(card(el, 'starter-follow')).toBeNull();
        expect(card(el, 'starter-views')).toBeNull();
    });

    it('Follow the money loads the ready-made saved view and puts its graph on screen in one click', async () => {
        const { fixture, queried } = create({ views: [OTHER, MONEY] });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(card(el, 'starter-follow')?.textContent).toContain('mule_layering_ring');
        card(el, 'starter-follow')!.click();
        await fixture.whenStable();
        expect(queried).toEqual([MONEY.query]);
        expect(fixture.componentInstance.graph()).not.toBeNull();
    });

    it('the follow-the-money card is hidden when the saved views are not money-themed', () => {
        const { fixture } = create({ views: [OTHER] });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(card(el, 'starter-follow')).toBeNull();
        expect(card(el, 'starter-views')?.textContent).toContain('1 saved view');
    });

    it('Open a saved view opens the same menu as the rail button', () => {
        const { fixture } = create({ views: [OTHER] });
        fixture.detectChanges();
        const open = vi.spyOn(MatMenuTrigger.prototype, 'openMenu').mockImplementation(() => undefined);
        card(fixture.nativeElement, 'starter-views')!.click();
        expect(open).toHaveBeenCalledTimes(1);
        open.mockRestore();
    });

    it('Explore a Dataset shows the Query panel and hands over to its guided picker', () => {
        const { fixture } = create();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        c.queryDockOpen.set(false);
        const panel = fixture.debugElement.query(By.directive(LinkAnalysisQueryPanelComponent))
            ?.componentInstance as LinkAnalysisQueryPanelComponent;
        const explore = vi
            .spyOn(LinkAnalysisQueryPanelComponent.prototype, 'startExplore')
            .mockImplementation(() => undefined);
        fixture.detectChanges();
        card(fixture.nativeElement, 'starter-explore')!.click();
        expect(c.queryDockOpen()).toBe(true);
        expect(explore).toHaveBeenCalledTimes(1);
        explore.mockRestore();
        expect(panel === undefined || panel instanceof LinkAnalysisQueryPanelComponent).toBe(true);
    });

    it('a picked ranking row highlights the node and centres the canvas on it', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        const centerOn = vi.fn();
        (c as unknown as { graphView: unknown }).graphView = { centerOn };
        c.onNodePick('b');
        expect(c.emphasis()).toEqual({ nodeIds: ['b'], edgeIds: [] });
        expect(centerOn).toHaveBeenCalledWith(['b']);
    });

    it('"Start an Investigation from the top results" queues the seeds and opens the Investigation tab - creating nothing', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.toolboxTab.set('analysis');
        c.queueSeedsAndOpen([{ id: 'a', label: 'A', ids: ['a'] }]);
        expect(c.investigation.queuedSeeds()).toEqual([{ id: 'a', label: 'A', ids: ['a'] }]);
        expect(c.toolboxTab()).toBe('investigation');
        expect(c.investigation.active()).toBe(false); // the analyst confirms title/purpose first
    });

    it('queues nothing when no top result can seed (all masked)', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.queueSeedsAndOpen([]);
        expect(c.investigation.queuedSeeds()).toEqual([]);
    });

    /**
     * Measured in the real shell 2026-10-10: the fill-mode graph view is `flex-auto`, which does nothing in a block
     * parent, so G6 kept its 480px default inside a 1012px zone. Its wrapper must be a flex column.
     */
    it('the canvas wrapper is a flex column, so the fill-mode graph view grows into the zone', async () => {
        const { fixture } = create({ stubGraph: true });
        fixture.detectChanges();
        await runQuery(fixture);
        fixture.detectChanges();
        const wrapper = (fixture.nativeElement as HTMLElement).querySelector('inspecto-graph-view')!
            .parentElement as HTMLElement;
        expect(wrapper.classList).toContain('flex');
        expect(wrapper.classList).toContain('flex-col');
        expect(wrapper.classList).toContain('max-md:min-h-[480px]'); // stacked layout keeps the old canvas height
    });

    it('bounds the page to the viewport at md+ (no page scroll): root height, row min-h-0, strip shrink-0; stacked keeps page scroll', async () => {
        const { fixture } = create({ stubGraph: true });
        fixture.detectChanges();
        await runQuery(fixture);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const root = el.firstElementChild as HTMLElement;
        // header 4rem + footer 3.5rem; floored at 35rem so a short window scrolls the PAGE instead of crushing the canvas
        expect(root.classList).toContain('h-[max(calc(100dvh-var(--shell-chrome-height,7.5rem)),35rem)]');
        expect(root.classList).toContain('max-md:h-auto');
        expect(root.classList).not.toContain('h-full');
        const workspace = el.querySelector('aside[aria-label="Query"]')!.parentElement as HTMLElement;
        for (const c of ['min-h-[26.25rem]', 'flex-auto', 'overflow-hidden', 'max-md:min-h-[28rem]']) {
            expect(workspace.classList).toContain(c);
        }
        for (const dock of ['Query', 'Toolbox']) {
            const aside = el.querySelector(`aside[aria-label="${dock}"]`)!;
            expect(aside.classList).toContain('overflow-hidden');
            expect(aside.querySelector('.overflow-y-auto')).toBeTruthy(); // the dock body scrolls inside
        }
        expect(el.querySelector('nav[data-testid="la-tool-rail"]')!.classList).toContain('overflow-y-auto');
        const strip = Array.from(el.querySelectorAll<HTMLElement>('div.shrink-0.rounded-lg.border')).find((d) =>
            d.textContent?.includes('Data'),
        );
        expect(strip).toBeTruthy();
    });

    it('keeps the canvas row at least 420px high (short windows scroll the page) and the page header floor holds it', () => {
        const { fixture } = create({ stubGraph: true });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const workspace = el.querySelector('aside[aria-label="Query"]')!.parentElement as HTMLElement;
        expect(workspace.classList).toContain('min-h-[26.25rem]'); // 26.25rem = 420px
        expect(workspace.classList).not.toContain('min-h-0');
        // the root floor (35rem) leaves room for the chrome above the row plus that 420px
        expect((el.firstElementChild as HTMLElement).className).toContain(',35rem)]');
    });

    it('puts the active-query status chips in the page-header title row, not in a row above the canvas', async () => {
        const { fixture } = create({ stubGraph: true });
        fixture.detectChanges();
        await runQuery(fixture);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const status = el.querySelector('[data-testid="la-status"]') as HTMLElement;
        expect(status).toBeTruthy();
        expect(status.closest('inspecto-page-header header')).toBeTruthy();
        expect(status.textContent).toContain('nodes');
        expect(status.querySelector('button[aria-label="Change the query"]')).toBeTruthy();
        expect(el.querySelectorAll('[aria-label="Active query"]').length).toBe(1); // no second, separate panel
    });

    describe('Toolbox dock start state (width)', () => {
        const media = (narrow: boolean): void => {
            const noop = (): void => undefined;
            vi.stubGlobal('matchMedia', (q: string) => ({
                matches: narrow && q.includes('1299px'),
                media: q,
                addListener: noop,
                removeListener: noop,
                addEventListener: noop,
                removeEventListener: noop,
            }));
        };
        const toolboxRail = (el: HTMLElement): HTMLElement | null =>
            el.querySelector('aside[aria-label="Toolbox"] button[aria-label="Open the view tools"]');

        afterEach(() => {
            vi.unstubAllGlobals();
            sessionStorage.clear();
        });

        it('starts collapsed to its rail below 1300px and the Query dock stays open', () => {
            sessionStorage.clear();
            media(true);
            const { fixture } = create({ stubGraph: true });
            fixture.detectChanges();
            const c = fixture.componentInstance;
            expect(c.toolboxDockOpen()).toBe(false);
            expect(c.queryDockOpen()).toBe(true);
            expect(c.canvasMaximized()).toBe(false); // "Hide the side panels" is untouched
            expect(toolboxRail(fixture.nativeElement)).toBeTruthy();
        });

        it('starts open at 1300px and wider', () => {
            sessionStorage.clear();
            media(false);
            const { fixture } = create({ stubGraph: true });
            fixture.detectChanges();
            expect(fixture.componentInstance.toolboxDockOpen()).toBe(true);
        });

        it('remembers an explicit open (narrow) and an explicit close (wide) for the session', () => {
            sessionStorage.clear();
            media(true);
            const a = create({ stubGraph: true });
            a.fixture.detectChanges();
            a.fixture.componentInstance.openAnalysis(); // the user opens it on demand
            a.fixture.detectChanges();
            expect(sessionStorage.getItem('inspecto.la.toolboxOpen')).toBe('true');
            TestBed.resetTestingModule();
            const b = create({ stubGraph: true }); // a fresh visit, still narrow
            b.fixture.detectChanges();
            expect(b.fixture.componentInstance.toolboxDockOpen()).toBe(true);

            sessionStorage.clear();
            media(false);
            TestBed.resetTestingModule();
            const c = create({ stubGraph: true });
            c.fixture.detectChanges();
            c.fixture.componentInstance.toolboxDockOpen.set(false);
            c.fixture.detectChanges();
            TestBed.resetTestingModule();
            const d = create({ stubGraph: true });
            d.fixture.detectChanges();
            expect(d.fixture.componentInstance.toolboxDockOpen()).toBe(false);
        });

        it('writes nothing until the user acts (the viewport default is not a choice)', () => {
            sessionStorage.clear();
            media(true);
            const { fixture } = create({ stubGraph: true });
            fixture.detectChanges();
            expect(sessionStorage.getItem('inspecto.la.toolboxOpen')).toBeNull();
        });
    });

    it('docks are narrower by default: Query 220px (min 200), Toolbox 340px', () => {
        const { fixture } = create({ stubGraph: true });
        fixture.detectChanges();
        const q = fixture.debugElement.query(By.css('[inspectoSplit="link-analysis.query"]'));
        const t = fixture.debugElement.query(By.css('[inspectoSplit="link-analysis.toolbox"]'));
        expect(q.nativeElement.getAttribute('aria-valuemin')).toBe('200');
        expect(q.nativeElement.getAttribute('aria-valuenow')).toBe('220');
        expect(t.nativeElement.getAttribute('aria-valuenow')).toBe('340');
    });

    it('the starter cards are a11y-clean', async () => {
        const { fixture } = create({ views: [OTHER, MONEY] });
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
