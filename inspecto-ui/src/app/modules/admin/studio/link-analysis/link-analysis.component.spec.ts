import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { PipelinesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ElementDetailData } from 'app/inspecto/investigation';
import { G6GraphData, GraphSource } from 'app/inspecto/graph';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisComponent } from './link-analysis.component';
import { LinkAnalysisQueryPanelComponent } from './link-analysis-query-panel.component';
import { LinkAnalysisService, LinkAnalysisView } from './link-analysis.service';

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

function create(
    opts: {
        fail?: boolean;
        views?: LinkAnalysisView[];
        expand?: GraphSource['expand'];
        graph?: G6GraphData;
        /** What the fake source returns when the query carries a `filter` (the stage-2 push). */
        filtered?: G6GraphData;
        queryParams?: Record<string, string>;
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
            provideNoopAnimations(),
            provideRouter([]),
            { provide: GraphSourcesService, useValue: { sources: [fakeSource], byId: () => fakeSource } },
            { provide: DatasetsService, useValue: { list: () => of([DS]) } },
            { provide: PipelinesService, useValue: { list: () => of([]) } },
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

        await c.expandNode('c', 'C');
        expect(expand).toHaveBeenCalledWith('c', 'C', expect.objectContaining({ projection: expect.anything() }));
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

        await c.expandNode('c', 'C');
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

        await c.expandNode('c', 'C');
        expect(c.truncated()).toBe(false);
    });

    it('expandNode is a no-op when the source has no expand()', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await runQuery(fixture);
        const c = fixture.componentInstance;
        await c.expandNode('c', 'C');
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
        const attachData = (open.mock.calls[1] as unknown as [unknown, { data: { snapshot: unknown } }])[1].data;
        expect(attachData.snapshot).toBe(snap);
        expect(c.latestSnapshot()).toBe(snap);

        await runQuery(fixture); // a fresh graph is a new answer — the old snapshot no longer describes it
        expect(c.latestSnapshot()).toBeNull();
    });
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
});
