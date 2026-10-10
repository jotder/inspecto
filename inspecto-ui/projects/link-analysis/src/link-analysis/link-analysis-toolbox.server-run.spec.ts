import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { GammaConfigService } from '@gamma/services/config';
import { Observable, of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ComponentsService } from '@inspecto/core/api';
import {
    GraphAlgorithmCatalogue,
    GraphRunRequest,
    GraphRunResult,
    GraphRunView,
    GraphRunsService,
} from '@inspecto/link-analysis/api/graph-runs.service';
import { WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import { EntityProjection } from '@inspecto/core/graph';
import { configureGraphLimits, resetGraphLimits } from '@inspecto/link-analysis/graph/graph-analysis';
import { GraphEmphasis } from '@inspecto/core/graph/graph-view.component';
import { buildServerIdMap } from './graph-run-apply';
import { workingSetToGraph } from './investigation-state';
import { LinkAnalysisToolboxComponent } from './link-analysis-toolbox.component';

/**
 * D-4 step 7 - "browser first under the cap, server above": the toolbox runs locally at or under an algorithm's cap
 * exactly as before, hands the same question to the server above it, and applies the server's answer through the same
 * code. The caps are lowered to 3 nodes so a five-node graph is "over".
 */
const P: EntityProjection = {
    datasetId: 'calls',
    sourceCol: 'A',
    targetCol: 'B',
    linkKindCol: 'K',
    entityType: 'acct',
};

function linkId(source: string, target: string, kind: string): string {
    return 'lk.' + btoa(`${source}\0${target}\0${kind}`).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

const WS: WorkingSet = {
    entities: ['a', 'b', 'c', 'd', 'e'].map((id) => ({
        id,
        type: null,
        hop: 0,
        seed: id,
        admittedBy: 1,
        hidden: false,
        kept: false,
    })),
    links: [
        ['a', 'b'],
        ['b', 'c'],
        ['c', 'd'],
        ['d', 'e'],
    ].map(([source, target]) => ({
        source,
        target,
        kind: 'calls',
        count: 1,
        admittedBy: 1,
        linkId: linkId(source, target, 'calls'),
    })),
    excluded: [],
    hash: 'h',
};
const CANVAS = workingSetToGraph(WS, P);
const IDS = buildServerIdMap(WS, P, CANVAS);
const nodeId = (label: string) => CANVAS.nodes.find((n) => n.data.label === label)!.id;
const edgeId = (s: string, t: string) => CANVAS.edges.find((e) => e.source === nodeId(s) && e.target === nodeId(t))!.id;

function view(status: GraphRunView['status'], over: Partial<GraphRunView> = {}): GraphRunView {
    return {
        runId: 'r1',
        status,
        investigationId: 'inv-1',
        algorithm: 'x',
        engine: 'in-memory',
        budget: { maxNodes: 5000, maxEdges: 50000, timeoutMs: 30000 },
        budgetClamped: false,
        consumed: { nodes: 5, edges: 4, elapsedMs: 3, work: 0 },
        progress: { work: 0, fraction: 0 },
        cancelRequested: false,
        cached: false,
        createdAt: 't',
        ...over,
    };
}

function make(
    opts: {
        allowed?: boolean;
        investigationId?: string | null;
        workingSetNodes?: number | null;
        serverCeilings?: Record<string, number> | null;
        graph?: typeof CANVAS | null;
        answer?: (r: GraphRunRequest) => Observable<GraphRunView>;
    } = {},
) {
    const run = vi.fn(opts.answer ?? (() => of(view('COMPLETED'))));
    TestBed.configureTestingModule({
        imports: [LinkAnalysisToolboxComponent],
        providers: [
            provideNoopAnimations(),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            { provide: ComponentsService, useValue: { list: () => of([]) } },
            {
                provide: GraphRunsService,
                useValue: {
                    catalogue: signal<GraphAlgorithmCatalogue | null>(null),
                    loadCatalogue: vi.fn(),
                    indexes: signal(null),
                    loadIndexes: vi.fn(),
                    run,
                    cancel: vi.fn(),
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisToolboxComponent);
    const c = fixture.componentInstance;
    fixture.componentRef.setInput('graph', opts.graph === undefined ? CANVAS : opts.graph);
    fixture.componentRef.setInput('workingSetNodes', opts.workingSetNodes ?? null);
    fixture.componentRef.setInput(
        'nodeOptions',
        CANVAS.nodes.map((n) => ({ id: n.id, label: n.data.label })),
    );
    fixture.componentRef.setInput('serverIds', IDS);
    fixture.componentRef.setInput('serverCeilings', opts.serverCeilings ?? null);
    fixture.componentRef.setInput('canRunOnServer', opts.allowed ?? true);
    fixture.componentRef.setInput(
        'investigationId',
        opts.investigationId === undefined ? 'inv-1' : opts.investigationId,
    );
    const emphases: (GraphEmphasis | null)[] = [];
    c.emphasisChange.subscribe((e) => emphases.push(e));
    const el = fixture.nativeElement as HTMLElement;
    const button = (text: RegExp) =>
        [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => text.test(b.textContent ?? ''));
    return { fixture, c, run, emphases, el, button, last: () => emphases[emphases.length - 1] };
}

describe('LinkAnalysisToolboxComponent - Run on server (D-4 step 7)', () => {
    afterEach(() => resetGraphLimits());

    it('at or under the cap the local run is unchanged and no server control exists', () => {
        const { fixture, c, el, button, run } = make();
        c.tab.set('centrality');
        c.centralityMetric.set('betweenness');
        fixture.detectChanges();
        expect(c.serverSpecs().centrality).toBeNull();
        expect(el.querySelector('inspecto-link-analysis-server-run')).toBeNull();
        button(/Rank nodes/)!.click();
        expect(c.ranking().length).toBeGreaterThan(0); // ran in the browser, instantly
        expect(c.analysisError()).toBe('');
        expect(run).not.toHaveBeenCalled();
    });

    it('above the cap the browser control becomes Run on server - and no "capped at" refusal is shown', () => {
        configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
        const { fixture, c, el, button } = make();
        c.tab.set('centrality');
        c.centralityMetric.set('betweenness');
        fixture.detectChanges();
        expect(button(/Rank nodes/)).toBeUndefined();
        expect(button(/Run on server/)).toBeDefined();
        expect(el.textContent).toContain('This graph has 5 nodes - above the 3-node limit');
        expect(el.textContent).not.toMatch(/capped at/);
        expect(c.serverSpecs().centrality?.algorithm).toBe('betweennessCentrality');
    });

    it('an algorithm without a browser cap (degree) stays local even on a big graph', () => {
        configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
        const { fixture, c, button } = make();
        c.tab.set('centrality');
        c.centralityMetric.set('degree');
        fixture.detectChanges();
        expect(c.serverSpecs().centrality).toBeNull();
        expect(button(/Rank nodes/)).toBeDefined();
    });

    it('a completed server ranking lands on the canvas ids through the same ranking code a local run uses', () => {
        configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
        const result: GraphRunResult = {
            algorithm: 'betweennessCentrality',
            kind: 'SCORES',
            dropped: 0,
            elapsedMs: 4,
            scores: [
                { id: 'c', label: 'c', score: 4 },
                { id: 'b', label: 'b', score: 3 },
                { id: 'a', label: 'a', score: 0 },
            ],
        };
        const { fixture, c, button, run, last } = make({ answer: () => of(view('COMPLETED', { result })) });
        c.tab.set('centrality');
        c.centralityMetric.set('betweenness');
        fixture.detectChanges();
        button(/Run on server/)!.click();
        fixture.detectChanges();
        expect(run).toHaveBeenCalledWith({ investigationId: 'inv-1', algorithm: 'betweennessCentrality' });
        expect(c.ranking().map((r) => r.id)).toEqual([nodeId('c'), nodeId('b'), nodeId('a')]);
        expect(c.ranking()[0].score).toBe(4);
        expect(last()).toBeNull(); // the same emphasis a local ranking emits
        expect(c.analysisError()).toBe('');
    });

    it('a returned min-cut edge highlights the intended canvas edge (flow, params carry the server node ids)', () => {
        configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
        const result: GraphRunResult = {
            algorithm: 'maxFlow',
            kind: 'FLOW',
            dropped: 0,
            elapsedMs: 2,
            value: 1,
            minCut: { nodeIds: ['b', 'c'], edgeIds: [linkId('b', 'c', 'calls')] },
        };
        const { fixture, c, button, run, last } = make({ answer: () => of(view('COMPLETED', { result })) });
        c.tab.set('flow');
        fixture.detectChanges();
        // before the pick the run is held, with the reason stated
        expect((button(/Run on server/) as HTMLButtonElement).disabled).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('Pick a source and a sink first.');

        c.flowFrom.set(nodeId('a'));
        c.flowTo.set(nodeId('e'));
        fixture.detectChanges();
        button(/Run on server/)!.click();
        fixture.detectChanges();
        expect(run).toHaveBeenCalledWith({
            investigationId: 'inv-1',
            algorithm: 'maxFlow',
            params: { from: 'a', to: 'e' },
        });
        expect(last()?.edgeIds).toEqual([edgeId('b', 'c')]);
        expect(last()?.edgeIds).not.toContain(edgeId('a', 'b'));
        expect(c.flowResult()?.value).toBe(1);
    });

    it('a server BUDGET_EXCEEDED leaves the canvas and every result untouched', () => {
        configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
        const probe: GraphRunResult = {
            algorithm: 'betweennessCentrality',
            kind: 'SCORES',
            dropped: 0,
            elapsedMs: 1,
            scores: [{ id: 'a', label: 'a', score: 9 }],
        };
        const { fixture, c, button, emphases, el } = make({
            answer: () => of(view('BUDGET_EXCEEDED', { exceeded: 'NODES', reason: 'too big', result: probe })),
        });
        c.tab.set('centrality');
        c.centralityMetric.set('betweenness');
        fixture.detectChanges();
        button(/Run on server/)!.click();
        fixture.detectChanges();
        expect(c.ranking()).toEqual([]);
        expect(emphases).toEqual([]);
        expect(el.querySelector('[data-testid="budget-reason"]')!.textContent).toContain('too big');
    });

    it('without the capability the control is disabled with a stated reason and no run is sent', () => {
        configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
        const { fixture, c, button, run, el } = make({ allowed: false });
        c.tab.set('scoring');
        fixture.detectChanges();
        expect((button(/Run on server/) as HTMLButtonElement).disabled).toBe(true);
        expect(el.querySelector('[data-testid="blocked-reason"]')!.textContent).toMatch(/capability/);
        button(/Run on server/)!.click();
        expect(run).not.toHaveBeenCalled();
    });

    it('with no Investigation open it says so instead of running', () => {
        configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
        const { fixture, c, el } = make({ investigationId: null });
        c.tab.set('communities');
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="blocked-reason"]')!.textContent).toMatch(/Investigation/);
    });
    it('with a Working Set open the decision is made on ITS size, not the query graph under it', () => {
        configureGraphLimits({ analysisNodeCap: 750, suspicionNodeCap: 750 });
        // no query graph loaded at all, but an Investigation whose Working Set has 1 225 nodes
        const big = make({ graph: null, workingSetNodes: 1225 });
        big.c.tab.set('scoring');
        big.fixture.detectChanges();
        expect(big.c.serverSpecs().scoring?.note).toBe(
            'The Working Set has 1225 nodes - above the 750-node limit for running this in the browser.',
        );
        expect(big.button(/Run on server/)).toBeDefined();
    });

    it('a Working Set under the cap stays local even when the query graph beside it is over', () => {
        configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
        const { c } = make({ workingSetNodes: 2 }); // CANVAS has 5 nodes, over the 3-node cap
        expect(c.serverSpecs().scoring).toBeNull();
    });
});

/**
 * LA-GRAPH-RUN-SELECTION-LOCAL-ONLY-1: the algorithms with a SELECTION result and no browser cap of their own (paths,
 * cycles, cut points, spanning forest) take the same browser-first / server-above route. The one threshold is
 * `selectionNodeCapValue()` (the analysis cap), lowered by the server's per-algorithm `inlineNodeCeiling`.
 */
describe('LinkAnalysisToolboxComponent - selection algorithms on the server', () => {
    afterEach(() => resetGraphLimits());
    const over = () => configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
    const sel = (nodes: string[], edges: [string, string][]) => ({
        nodeIds: nodes,
        edgeIds: edges.map(([s, t]) => linkId(s, t, 'calls')),
    });
    const result = (more: Partial<GraphRunResult>): GraphRunResult => ({
        algorithm: 'x',
        kind: 'SELECTION',
        dropped: 0,
        elapsedMs: 1,
        ...more,
    });
    const AE = sel(
        ['a', 'b', 'c', 'd', 'e'],
        [
            ['a', 'b'],
            ['b', 'c'],
            ['c', 'd'],
            ['d', 'e'],
        ],
    );

    it('shortest path: the local button is replaced, the run is held until both nodes are picked, then the path lands on canvas ids', () => {
        over();
        const { fixture, c, button, run, last } = make({
            answer: () => of(view('COMPLETED', { result: result({ algorithm: 'shortestPath', selection: AE }) })),
        });
        c.tab.set('path');
        fixture.detectChanges();
        expect(button(/Find shortest path/)).toBeUndefined();
        expect((button(/Run on server/) as HTMLButtonElement).disabled).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('Pick the two nodes first.');

        c.pathFrom.set(nodeId('a'));
        c.pathTo.set(nodeId('e'));
        fixture.detectChanges();
        button(/Run on server/)!.click();
        fixture.detectChanges();
        expect(run).toHaveBeenCalledWith({
            investigationId: 'inv-1',
            algorithm: 'shortestPath',
            params: { from: 'a', to: 'e' },
        });
        expect(c.pathResult()?.hops).toEqual(['a', 'b', 'c', 'd', 'e'].map(nodeId));
        expect(last()?.edgeIds).toEqual([edgeId('a', 'b'), edgeId('b', 'c'), edgeId('c', 'd'), edgeId('d', 'e')]);
        expect(c.serverDropped()).toBe('');
    });

    it('strongest ties asks the server for the weighted algorithm; "no path" is the browser sentence', () => {
        over();
        const { fixture, c, button, run, last } = make({
            answer: () =>
                of(view('COMPLETED', { result: result({ algorithm: 'weightedShortestPath', selection: null }) })),
        });
        c.tab.set('path');
        c.pathMetric.set('weighted');
        c.pathFrom.set(nodeId('a'));
        c.pathTo.set(nodeId('e'));
        fixture.detectChanges();
        button(/Run on server/)!.click();
        expect(run.mock.calls[0][0].algorithm).toBe('weightedShortestPath');
        expect(c.analysisError()).toBe('No path connects the two nodes.');
        expect(last()).toBeNull();
    });

    it('all paths applies its selection LIST through the same code as the local run', () => {
        over();
        const { fixture, c, button, run, last } = make({
            answer: () =>
                of(
                    view('COMPLETED', {
                        result: result({
                            algorithm: 'allPaths',
                            kind: 'SELECTIONS',
                            selections: [
                                sel(
                                    ['a', 'b', 'c'],
                                    [
                                        ['a', 'b'],
                                        ['b', 'c'],
                                    ],
                                ),
                                sel(['a', 'b'], [['a', 'b']]),
                            ],
                        }),
                    }),
                ),
        });
        c.tab.set('all-paths');
        c.pathFrom.set(nodeId('a'));
        c.pathTo.set(nodeId('c'));
        fixture.detectChanges();
        button(/Run on server/)!.click();
        expect(run).toHaveBeenCalledWith({
            investigationId: 'inv-1',
            algorithm: 'allPaths',
            params: { from: 'a', to: 'c' },
        });
        expect(c.allPathsResult().map((p) => p.nodeIds.length)).toEqual([3, 2]);
        expect(last()?.nodeIds).toEqual(['a', 'b', 'c'].map(nodeId));
        expect(last()?.edgeIds).toEqual([edgeId('a', 'b'), edgeId('b', 'c')]);
    });

    it('cycles: the local button is replaced and the returned loops land on canvas ids', () => {
        over();
        const { fixture, c, button, run } = make({
            answer: () =>
                of(
                    view('COMPLETED', {
                        result: result({
                            algorithm: 'findCycles',
                            kind: 'SELECTIONS',
                            selections: [
                                sel(
                                    ['b', 'c', 'd'],
                                    [
                                        ['b', 'c'],
                                        ['c', 'd'],
                                    ],
                                ),
                            ],
                        }),
                    }),
                ),
        });
        c.tab.set('cycles');
        fixture.detectChanges();
        expect(button(/Find cycles/)).toBeUndefined();
        button(/Run on server/)!.click();
        expect(run).toHaveBeenCalledWith({ investigationId: 'inv-1', algorithm: 'findCycles' });
        expect(c.cycles()[0].nodeIds).toEqual(['b', 'c', 'd'].map(nodeId));
    });

    it('cut points are two server algorithms: nodes and bridges each have a control, and the two results combine', () => {
        over();
        const answers: Record<string, GraphRunResult> = {
            articulationPoints: result({ algorithm: 'articulationPoints', kind: 'IDS', ids: ['b', 'c'] }),
            bridges: result({
                algorithm: 'bridges',
                kind: 'IDS',
                ids: [linkId('a', 'b', 'calls'), linkId('d', 'e', 'calls')],
            }),
        };
        const { fixture, c, el, run, last } = make({
            answer: (req) => of(view('COMPLETED', { result: answers[req.algorithm] })),
        });
        c.tab.set('cut-points');
        fixture.detectChanges();
        const controls = () => el.querySelectorAll<HTMLButtonElement>('[data-testid="run-on-server"]');
        expect(controls()).toHaveLength(2);
        expect(el.textContent).toContain('Articulation nodes: This graph');
        expect(el.textContent).toContain('Bridges: This graph');
        controls()[0].click();
        fixture.detectChanges();
        expect(c.cutNodes()).toEqual(['b', 'c'].map(nodeId));
        controls()[1].click();
        fixture.detectChanges();
        expect(run.mock.calls.map((x) => x[0].algorithm)).toEqual(['articulationPoints', 'bridges']);
        expect(c.cutEdges()).toEqual([edgeId('a', 'b'), edgeId('d', 'e')]);
        expect(c.cutNodes()).toEqual(['b', 'c'].map(nodeId)); // the first result is still there
        expect(last()?.nodeIds).toEqual(['b', 'c'].map(nodeId));
        expect(last()?.edgeIds).toEqual([edgeId('a', 'b'), edgeId('d', 'e')]);
    });

    it('the strongest backbone (maximum spanning forest) applies like the local run', () => {
        over();
        const { fixture, c, button, last, el } = make({
            answer: () =>
                of(view('COMPLETED', { result: result({ algorithm: 'maximumSpanningForest', selection: AE }) })),
        });
        c.tab.set('flow');
        fixture.detectChanges();
        expect(button(/Strongest backbone/)).toBeUndefined();
        const runs = el.querySelectorAll<HTMLButtonElement>('[data-testid="run-on-server"]');
        runs[runs.length - 1].click(); // flow's own control comes first, the backbone's is last
        fixture.detectChanges();
        expect(c.spanningForest()?.edgeIds).toHaveLength(4);
        expect(last()?.edgeIds).toContain(edgeId('c', 'd'));
        expect(c.flowResult()).toBeNull();
    });

    it('the threshold is ONE shared cap, lowered (never raised) by the server ceiling', () => {
        configureGraphLimits({ analysisNodeCap: 100, suspicionNodeCap: 100 });
        // 5 nodes: under the 100 cap, so the browser runs it...
        expect(make().c.serverSpecs().cycles).toBeNull();
        TestBed.resetTestingModule();
        // ...unless the server says it only waits for 3 inline
        expect(make({ serverCeilings: { findCycles: 3 } }).c.serverSpecs().cycles?.algorithm).toBe('findCycles');
        TestBed.resetTestingModule();
        // a HIGHER server ceiling never raises the browser threshold
        configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
        expect(make({ serverCeilings: { findCycles: 100_000 } }).c.serverSpecs().cycles?.algorithm).toBe('findCycles');
    });

    it('with no Investigation open the local button stays (these never refuse locally) and the server control says why it cannot run', () => {
        over();
        const { fixture, c, button, el } = make({ investigationId: null });
        c.tab.set('cycles');
        fixture.detectChanges();
        expect(c.serverFirst('cycles')).toBe(false);
        expect(button(/Find cycles/)).toBeDefined();
        expect(el.querySelector('[data-testid="blocked-reason"]')!.textContent).toMatch(/Investigation/);
    });

    it('a server result naming nodes and links the canvas does not draw states how many (LA-GRAPH-RUN-HIDDEN-NODES-1)', () => {
        over();
        const partial = sel(
            ['a', 'b', 'ghost'],
            [
                ['a', 'b'],
                ['b', 'ghost'],
            ],
        );
        const { fixture, c, button, el } = make({
            answer: () => of(view('COMPLETED', { result: result({ algorithm: 'shortestPath', selection: partial }) })),
        });
        c.tab.set('path');
        c.pathFrom.set(nodeId('a'));
        c.pathTo.set(nodeId('b'));
        fixture.detectChanges();
        button(/Run on server/)!.click();
        fixture.detectChanges();
        expect(c.serverDropped()).toBe(
            '1 of 3 result nodes and 1 of 2 result links are not drawn on the canvas right now, so the result shown here leaves them out.',
        );
        expect(el.querySelector('[data-testid="server-dropped"]')!.textContent).toContain('1 of 3 result nodes');
        // the drawn part is still applied
        expect(c.pathResult()?.hops).toEqual(['a', 'b'].map(nodeId));
        // a later browser run clears the (now untrue) notice
        c.runCycles();
        expect(c.serverDropped()).toBe('');
    });
});

describe('LinkAnalysisToolboxComponent - the server result cap is never silent (D1)', () => {
    afterEach(() => resetGraphLimits());
    const over = () => configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
    const ranking = (more: Partial<GraphRunResult>): GraphRunResult => ({
        algorithm: 'betweennessCentrality',
        kind: 'SCORES',
        dropped: 0,
        elapsedMs: 4,
        scores: [{ id: 'c', label: 'c', score: 4 }],
        truncated: false,
        lists: { scores: { total: 1, returned: 1, limit: 10000, truncated: false } },
        ...more,
    });
    const runRanking = (result: GraphRunResult) => {
        over();
        const m = make({ answer: () => of(view('COMPLETED', { result })) });
        m.c.tab.set('centrality');
        m.c.centralityMetric.set('betweenness');
        m.fixture.detectChanges();
        m.button(/Run on server/)!.click();
        m.fixture.detectChanges();
        return m;
    };

    it('a result cut at the server cap says exactly what was cut and the cap', () => {
        const { c, el } = runRanking(
            ranking({
                truncated: true,
                lists: { scores: { total: 25311, returned: 10000, limit: 10000, truncated: true } },
            }),
        );
        const text =
            'Showing the first 10,000 of 25,311 scores (the server cap is 10,000; raise graph_run.max_result_items in Settings).';
        expect(c.serverTruncated()).toBe(text);
        expect(el.querySelector('[data-testid="server-truncated"]')!.textContent).toBe(text);
    });

    it('a result that was not cut shows no notice', () => {
        const { c, el } = runRanking(ranking({}));
        expect(c.serverTruncated()).toBe('');
        expect(el.querySelector('[data-testid="server-truncated"]')).toBeNull();
    });

    it('a list nested inside another (one group) is worded plainly, and a reset clears it', () => {
        over();
        const { fixture, c, el, button } = make({
            answer: () =>
                of(
                    view('COMPLETED', {
                        result: {
                            algorithm: 'cliques',
                            kind: 'GROUPS',
                            dropped: 0,
                            elapsedMs: 1,
                            groups: [['a', 'b', 'c']],
                            truncated: true,
                            lists: {
                                groups: { total: 1, returned: 1, limit: 2, truncated: false },
                                'groups[0]': { total: 5, returned: 2, limit: 2, truncated: true },
                            },
                        },
                    }),
                ),
        });
        c.tab.set('cohesion');
        c.cohesionMetric.set('cliques');
        fixture.detectChanges();
        button(/Run on server/)!.click();
        fixture.detectChanges();
        expect(c.serverTruncated()).toBe(
            'Showing the first 2 of 5 members of group 1 (the server cap is 2; raise graph_run.max_result_items in Settings).',
        );
        expect(el.querySelector('[data-testid="server-truncated"]')).not.toBeNull();
        c.reset();
        expect(c.serverTruncated()).toBe('');
    });
});

describe('LinkAnalysisToolboxComponent - a server cut-points answer never combines with another Working Set (D2)', () => {
    afterEach(() => resetGraphLimits());
    const result = (more: Partial<GraphRunResult>): GraphRunResult => ({
        algorithm: 'x',
        kind: 'IDS',
        dropped: 0,
        elapsedMs: 1,
        ...more,
    });

    it('articulation on one Working Set, then bridges on another: the bridges answer carries no old cut nodes', () => {
        configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
        const answers: Record<string, GraphRunResult> = {
            articulationPoints: result({ algorithm: 'articulationPoints', ids: ['b', 'c'] }),
            bridges: result({ algorithm: 'bridges', ids: [linkId('a', 'b', 'calls')] }),
        };
        const { fixture, c, el, last } = make({
            answer: (req) => of(view('COMPLETED', { result: answers[req.algorithm] })),
        });
        c.tab.set('cut-points');
        fixture.detectChanges();
        const controls = () => el.querySelectorAll<HTMLButtonElement>('[data-testid="run-on-server"]');
        controls()[0].click();
        fixture.detectChanges();
        expect(c.cutNodes()).toEqual(['b', 'c'].map(nodeId));
        // the Working Set changed: the host rebuilds the id map (a new object)
        fixture.componentRef.setInput('serverIds', buildServerIdMap(WS, P, CANVAS));
        fixture.detectChanges();
        controls()[1].click();
        fixture.detectChanges();
        expect(c.cutEdges()).toEqual([edgeId('a', 'b')]);
        expect(c.cutNodes()).toEqual([]);
        expect(last()?.nodeIds).toEqual([]);
        expect(last()?.edgeIds).toEqual([edgeId('a', 'b')]);
    });

    it('"No cut points" is claimed only when both halves were checked on this Working Set', () => {
        configureGraphLimits({ analysisNodeCap: 3, suspicionNodeCap: 3 });
        const answers: Record<string, GraphRunResult> = {
            articulationPoints: result({ algorithm: 'articulationPoints', ids: [] }),
            bridges: result({ algorithm: 'bridges', ids: [] }),
        };
        const { fixture, c, el } = make({
            answer: (req) => of(view('COMPLETED', { result: answers[req.algorithm] })),
        });
        c.tab.set('cut-points');
        fixture.detectChanges();
        const controls = () => el.querySelectorAll<HTMLButtonElement>('[data-testid="run-on-server"]');
        controls()[0].click();
        fixture.detectChanges();
        expect(c.analysisError()).not.toContain('No cut points');
        expect(c.analysisError()).toContain('No articulation nodes found');
        controls()[1].click();
        fixture.detectChanges();
        expect(c.analysisError()).toContain('No cut points');
    });
});

describe('LinkAnalysisToolboxComponent - All algorithms (server) results feed the ranking and community views', () => {
    const base = { dropped: 0, elapsedMs: 1 };

    it('a ranking kind (PageRank, k-core) fills the same ranking view a local Rank nodes run fills', () => {
        const { c } = make();
        c.applyCatalogueResult({
            ...base,
            algorithm: 'pageRank',
            kind: 'SCORES',
            scores: [
                { id: 'c', label: 'c', score: 0.4 },
                { id: 'zz', label: 'not drawn', score: 0.3 },
                { id: 'a', label: 'a', score: 0.1 },
            ],
        });
        expect(c.ranking().map((s) => s.id)).toEqual([nodeId('c'), nodeId('a')]);
        expect(c.toolBadge('centrality')).toBe('top 2');
    });

    it('a partition kind (connected components, Louvain) fills the same community view', () => {
        const { c, last } = make();
        c.applyCatalogueResult({
            ...base,
            algorithm: 'connectedComponents',
            kind: 'GROUPS',
            groups: [
                ['a', 'b', 'c'],
                ['d', 'e'],
            ],
        });
        expect(c.communities().map((x) => x.members.length)).toEqual([3, 2]);
        expect(last()?.groups?.get(nodeId('d'))).toBe(last()?.groups?.get(nodeId('e')));
        c.applyCatalogueResult({
            ...base,
            algorithm: 'louvainCommunities',
            kind: 'COMMUNITIES',
            communities: [
                { id: 'a', community: 'a' },
                { id: 'b', community: 'a' },
            ],
        });
        expect(c.communities()).toEqual([{ id: nodeId('a'), members: [nodeId('a'), nodeId('b')] }]);
    });

    it('any other kind (a flag, overlapping cliques) leaves the ranking and community views alone', () => {
        const { c } = make();
        c.applyCatalogueResult({ ...base, algorithm: 'isForest', kind: 'FLAG', value: true });
        c.applyCatalogueResult({ ...base, algorithm: 'cliques', kind: 'GROUPS', groups: [['a', 'b']] });
        expect(c.ranking()).toEqual([]);
        expect(c.communities()).toEqual([]);
    });
});
