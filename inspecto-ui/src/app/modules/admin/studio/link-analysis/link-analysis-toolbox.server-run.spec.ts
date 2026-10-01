import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { GammaConfigService } from '@gamma/services/config';
import { Observable, of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
    ComponentsService,
    GraphAlgorithmCatalogue,
    GraphRunRequest,
    GraphRunResult,
    GraphRunView,
    GraphRunsService,
    WorkingSet,
} from 'app/inspecto/api';
import { EntityProjection, configureGraphLimits, resetGraphLimits } from 'app/inspecto/graph';
import { GraphEmphasis } from 'app/inspecto/graph/graph-view.component';
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
