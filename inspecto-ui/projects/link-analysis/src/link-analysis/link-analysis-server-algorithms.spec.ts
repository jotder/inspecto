import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penEdit, penText } from '@inspecto/core/testing/pen-field';
import {
    GraphAlgorithm,
    GraphAlgorithmCatalogue,
    GraphRunResult,
    GraphRunView,
    GraphRunsService,
} from '@inspecto/link-analysis/api/graph-runs.service';
import { ServerIdMap } from './graph-run-apply';
import { propagatedRiskRows, summarizeGraphRun } from './graph-run-summary';
import {
    LinkAnalysisServerAlgorithmsComponent,
    enumOptionsOf,
    missingNodesReason,
    paramLabel,
} from './link-analysis-server-algorithms.component';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';
import { NodeRiskService } from './node-risk.service';

/** LA-EDITIONS-1: these specs drive the Investigation surface, which the host reports present on Standard and above. */
const STANDARD_EDITION_FEATURES = {
    ops: signal(false),
    exchange: signal(false),
    geoLink: signal(true),
    investigations: signal(true),
};

/** DR-U11: every catalogue algorithm is reachable from one catalogue-driven panel; the answer is read by its result kind. */
const MAP: ServerIdMap = {
    node: (id) => (id.startsWith('x') ? undefined : 'c-' + id),
    edge: (id) => 'e-' + id,
    serverNode: (id) => (id.startsWith('c-') ? id.slice(2) : undefined),
};

const alg = (id: string, over: Partial<GraphAlgorithm> = {}): GraphAlgorithm => ({
    id,
    label: id,
    cost: 'SYNC',
    inlineNodeCeiling: 100,
    params: [],
    needsWeights: false,
    needsSource: false,
    needsTarget: false,
    needsNode: false,
    resultKind: 'SCORES',
    ...over,
});

// The seven that had no toolbox control, with their real descriptors.
const CATALOGUE = {
    engine: 'memory',
    algorithms: [
        alg('connectedComponents', { resultKind: 'GROUPS' }),
        alg('kCore', { label: 'k-core', description: 'How deep each node sits in a dense core' }),
        alg('triangleCount'),
        alg('isForest', { resultKind: 'FLAG' }),
        alg('descendants', { resultKind: 'IDS', needsNode: true }),
        alg('jaccardSimilarity', { needsNode: true }),
        alg('pageRank', {
            params: [
                { name: 'damping', type: 'DOUBLE', default: 0.85, min: 0, max: 1 },
                { name: 'iterations', type: 'INT', default: 60, min: 0, max: 10000 },
            ],
        }),
        // a parameter type this panel has no control for: the algorithm is not runnable here (T-114)
        alg('futureAlgo', { label: 'Future', params: [{ name: 'shape', type: 'GEOMETRY' as never, default: null }] }),
        alg('neighborhood', {
            resultKind: 'GRAPH',
            needsNode: true,
            params: [{ name: 'direction', type: 'ENUM', default: 'both', allowed: ['out', 'in', 'both'] }],
        }),
        // list / map parameters with their own controls (weights, seeds, nodeScores)
        alg('propagatedRisk', {
            label: 'Propagated risk',
            cost: 'JOB',
            resultKind: 'PROPAGATED_RISK',
            params: [
                // the server's X1 words (label, description, allowedLabels), as Algorithm.java declares them
                {
                    name: 'nodeScores',
                    label: 'Risk scores',
                    description: "Each node's own risk, 0 to 100",
                    type: 'SCORE_MAP',
                    default: {},
                    min: 0,
                    max: 100,
                    maxSize: 500000,
                },
                {
                    name: 'seeds',
                    label: 'Risk origins',
                    description: 'Only these nodes pass on risk; empty uses every scored node',
                    type: 'ID_LIST',
                    default: [],
                    maxSize: 500000,
                },
                {
                    name: 'weights',
                    label: 'Weight per hop',
                    description: 'Share of risk passed on at each hop, 0 to 1',
                    type: 'DOUBLE_LIST',
                    default: [1, 0.6, 0.35, 0.15],
                    min: 0,
                    max: 1,
                    maxSize: 6,
                },
                {
                    name: 'direction',
                    label: 'Direction',
                    description: 'Which way links are followed',
                    type: 'ENUM',
                    default: 'both',
                    allowed: ['out', 'in', 'both'],
                    allowedLabels: ['Outgoing', 'Incoming', 'Both ways'],
                },
            ],
        }),
    ],
    ceilings: { maxNodes: 1, maxEdges: 1, timeoutMs: 1 },
    defaults: { maxNodes: 1, maxEdges: 1, timeoutMs: 1, clamped: false },
    pool: { threads: 1, queue: 1 },
} as unknown as GraphAlgorithmCatalogue;

function completed(result: GraphRunResult): GraphRunView {
    return {
        runId: 'r',
        status: 'COMPLETED',
        investigationId: 'inv',
        algorithm: result.algorithm,
        engine: 'memory',
        budget: { maxNodes: 1, maxEdges: 1, timeoutMs: 1 },
        budgetClamped: false,
        consumed: { nodes: 3, edges: 2, elapsedMs: 1, work: 0 },
        progress: { work: 0, fraction: 1 },
        cancelRequested: false,
        cached: false,
        createdAt: 't',
        result,
    };
}

function make(
    answer: (r: unknown) => GraphRunView = () =>
        completed({ algorithm: 'kCore', kind: 'SCORES', dropped: 0, elapsedMs: 1, scores: [] }),
) {
    const run = vi.fn((r: unknown) => of(answer(r)));
    return makeWith(run);
}

function makeWith(run: ReturnType<typeof vi.fn>) {
    const risk = {
        lookup: vi.fn((_ref: unknown, _keys: string[], _inv?: string | null) =>
            Promise.resolve({
                byKey: new Map([['a', { msisdn: 'masked:a', indicator_score: 70 }]]),
                columns: [],
                truncated: false,
                unresolved: 0,
                masked: true,
            }),
        ),
    };
    TestBed.configureTestingModule({
        imports: [LinkAnalysisServerAlgorithmsComponent],
        providers: [
            { provide: LA_FEATURES, useValue: STANDARD_EDITION_FEATURES },
            provideNoopAnimations(),
            {
                provide: GraphRunsService,
                useValue: {
                    catalogue: signal(CATALOGUE),
                    loadCatalogue: vi.fn(),
                    indexes: signal(null),
                    loadIndexes: vi.fn(),
                    run,
                    cancel: vi.fn(),
                },
            },
            { provide: NodeRiskService, useValue: risk },
            {
                provide: LinkAnalysisSettingsService,
                useValue: { limits: signal({ propagatedRiskWeightsInForce: [1, 0.5], maskingModeInForce: 'all' }) },
            },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisServerAlgorithmsComponent);
    fixture.componentRef.setInput('investigationId', 'inv');
    fixture.componentRef.setInput('serverIds', MAP);
    fixture.componentRef.setInput('nodeOptions', [{ id: 'c-a', label: 'A' }]);
    fixture.componentRef.setInput('profileId', 'telecom');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, c: fixture.componentInstance, run, el, risk };
}

describe('LinkAnalysisServerAlgorithmsComponent (DR-U11)', () => {
    it('offers EVERY algorithm the catalogue lists, including the seven that were API-only', () => {
        const { c, el } = make();
        expect(c.algorithmOptions().map((o) => o.value)).toEqual(CATALOGUE.algorithms.map((a) => a.id));
        for (const id of [
            'connectedComponents',
            'kCore',
            'triangleCount',
            'isForest',
            'descendants',
            'jaccardSimilarity',
            'pageRank',
        ])
            expect(c.algorithmOptions().some((o) => o.value === id)).toBe(true);
        expect(el.querySelector('[data-testid=algo-count]')!.textContent).toContain('10 algorithms available.');
    });

    it('gives propagatedRisk real inputs: weights prefilled from the Space default, seeds from the canvas, nodeScores from indicators', async () => {
        const { fixture, c, el, run, risk } = make();
        expect(c.algorithmOptions().find((o) => o.value === 'propagatedRisk')!.label).toBe('Propagated risk');
        c.pick('propagatedRisk');
        fixture.detectChanges();
        await fixture.whenStable(); // ngModel writes the pen field's value a tick later
        fixture.detectChanges();
        const weights = el.querySelector<HTMLElement>('[data-testid=param-weights]')!;
        expect(penText(weights)).toBe('1, 0.5'); // the Space's graph_run.propagated_risk_weights
        expect(el.querySelector('[data-testid=blocked-reason]')!.textContent).toContain('Fill the risk scores first.');
        penEdit(fixture, weights, '1, 2');
        expect(el.querySelector('[role=alert]')!.textContent).toContain('from 0 to 1');
        penEdit(fixture, weights, '1, 0.4, 0.2');
        c.addId('seeds', 'c-a');
        el.querySelector<HTMLButtonElement>('[data-testid=fill-nodeScores]')!.click();
        await fixture.whenStable();
        await Promise.resolve();
        fixture.detectChanges();
        // only the drawn nodes are asked for, with the Investigation (masked Spaces work: the server resolves the ids)
        expect(risk.lookup).toHaveBeenCalledWith(expect.objectContaining({ keyCol: 'msisdn' }), ['a'], 'inv');
        expect(el.querySelector('[data-testid=param-nodeScores]')!.textContent).toContain('1 of 1 nodes');
        el.querySelector<HTMLButtonElement>('[data-testid=run-on-server]')!.click();
        expect(run).toHaveBeenCalledWith({
            investigationId: 'inv',
            algorithm: 'propagatedRisk',
            params: { weights: [1, 0.4, 0.2], seeds: ['a'], nodeScores: { a: 70 } },
        });
        await expectNoA11yViolations(el);
    });

    it('shows a PROPAGATED_RISK answer as a ranked table whose factors expand, and highlights it', () => {
        const { fixture, c, el } = make(() =>
            completed({
                algorithm: 'propagatedRisk',
                kind: 'PROPAGATED_RISK',
                dropped: 0,
                elapsedMs: 1,
                scores: [
                    {
                        id: 'b',
                        label: 'B',
                        score: 68.04,
                        raw: 68.04,
                        own: 40,
                        contributors: 1,
                        factors: [{ origin: 'a', distance: 3, weight: 0.35, contribution: 28.04 }],
                    },
                    { id: 'x', label: 'X', score: 0, raw: 0, own: 0, contributors: 0, factors: [] },
                ],
            }),
        );
        const emitted: unknown[] = [];
        c.emphasisChange.subscribe((e) => emitted.push(e));
        c.pick('propagatedRisk');
        c.setNodeScores('nodeScores', { a: 80 });
        fixture.detectChanges();
        el.querySelector<HTMLButtonElement>('[data-testid=run-on-server]')!.click();
        fixture.detectChanges();
        const table = el.querySelector('[data-testid=risk-table]')!;
        expect(table.textContent).toContain('68');
        const cells = Array.from(table.querySelectorAll('tbody tr:first-child td'));
        expect(cells[2].getAttribute('title')).toBe('68.04');
        expect(cells[2].getAttribute('aria-label')).toBe('68.04');
        expect(cells[4].getAttribute('title')).toBe('40');
        expect(el.querySelector('[data-testid=algo-result]')!.textContent).toContain('1 node carry propagated risk');
        Array.from(table.querySelectorAll('button'))[0].click();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid=factors-b]')!.textContent).toContain('+28 from A, 3 hops, weight 0.35');
        el.querySelector<HTMLButtonElement>('[data-testid=algo-highlight]')!.click();
        expect(emitted).toEqual([{ nodeIds: ['c-b'], edgeIds: [] }]);
    });

    it('X1: shows each parameter by its label with the description as a hint, never the wire name', async () => {
        const { fixture, c, el } = make();
        c.pick('propagatedRisk');
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const text = el.querySelector('[data-testid=algo-form]')!.textContent!;
        for (const words of ['Use risk scores', "Each node's own risk", 'Risk origins', 'Weight per hop', 'Direction'])
            expect(text).toContain(words);
        expect(text).toContain('Share of risk passed on at each hop, 0 to 1');
        expect(text).not.toMatch(/nodeScores|seeds|weights|per hop, 0\.\.1/);
        expect(text).not.toContain('No indicators Dataset');
        expect(el.querySelector('[data-testid=param-direction]')!.getAttribute('title')).toBe(
            'Which way links are followed',
        );
        const dir = c.current()!.params.find((p) => p.name === 'direction')!;
        expect(c.enumOptions(dir).map((o) => o.label)).toEqual(['Outgoing', 'Incoming', 'Both ways']);
        c.addId('seeds', 'c-a');
        fixture.detectChanges();
        const remove = el.querySelector('[data-testid=param-seeds] button[aria-label]')!;
        expect(remove.getAttribute('aria-label')).toBe('Remove A from risk origins');
    });

    it('X1: falls back to the wire name when an older server sends no label', () => {
        expect(paramLabel({ name: 'damping', type: 'DOUBLE', default: 0.85 })).toBe('damping');
        expect(paramLabel({ name: 'damping', label: ' ', type: 'DOUBLE', default: 0.85 })).toBe('damping');
        expect(enumOptionsOf({ name: 'd', type: 'ENUM', default: 'in', allowed: ['in'] })).toEqual([
            { value: 'in', label: 'in' },
        ]);
    });

    it('T-118: each algorithm in the list carries its one-line description as the hint', async () => {
        const { fixture, c, el } = make();
        const k = c.algorithmOptions().find((o) => o.value === 'kCore')!;
        expect(k.label).toBe('k-core');
        expect(k.hint).toBe('How deep each node sits in a dense core');
        expect(
            c.algorithmOptions().find((o) => o.value === 'triangleCount')!.hint,
            'an older server: no hint',
        ).toBeUndefined();
        el.querySelector<HTMLElement>('inspecto-option-picker button')!.click();
        fixture.detectChanges();
        await fixture.whenStable();
        expect(document.body.textContent).toContain('How deep each node sits in a dense core');
    });

    it('T-114: an algorithm the panel cannot edit says so in plain words, with no route or parameter names', () => {
        const { fixture, c, el, run } = make();
        expect(c.algorithmOptions().find((o) => o.value === 'futureAlgo')!.label).toBe('Future (not available here)');
        c.pick('futureAlgo');
        fixture.detectChanges();
        const why = el.querySelector('[data-testid=blocked-reason]')!.textContent!.trim();
        expect(why).toBe('This algorithm cannot be run from this panel yet.');
        el.querySelector<HTMLButtonElement>('[data-testid=run-on-server]')!.click();
        expect(run).not.toHaveBeenCalled();
    });

    it('X6: names the missing node pickers in one fixed phrase', () => {
        expect(missingNodesReason(true, true, false)).toBe('Pick a From and a To node first.');
        expect(missingNodesReason(true, false, false)).toBe('Pick a From node first.');
        expect(missingNodesReason(false, true, false)).toBe('Pick a To node first.');
        expect(missingNodesReason(false, false, true)).toBe('Pick a node first.');
        expect(missingNodesReason(false, false, false)).toBe('');
    });

    it('builds the parameter form from the descriptors and sends what was edited', async () => {
        const { fixture, c, el, run } = make();
        c.pick('pageRank');
        fixture.detectChanges();
        await fixture.whenStable(); // ngModel writes the pen field's value a tick later
        fixture.detectChanges();
        const damping = el.querySelector<HTMLElement>('[data-testid=param-damping]')!;
        expect(penText(damping)).toBe('0.85');
        penEdit(fixture, damping, '0.5');
        el.querySelector<HTMLButtonElement>('[data-testid=run-on-server]')!.click();
        expect(run).toHaveBeenCalledWith({ investigationId: 'inv', algorithm: 'pageRank', params: { damping: 0.5 } });
    });

    it('holds the run until a required node is picked, then sends the SERVER id of the canvas node', () => {
        const { fixture, c, el, run } = make();
        c.pick('descendants');
        fixture.detectChanges();
        expect(el.querySelector('[data-testid=blocked-reason]')!.textContent).toContain('Pick a node first.');
        el.querySelector<HTMLButtonElement>('[data-testid=run-on-server]')!.click();
        expect(run).not.toHaveBeenCalled();
        c.setNode('node', 'c-a');
        fixture.detectChanges();
        el.querySelector<HTMLButtonElement>('[data-testid=run-on-server]')!.click();
        expect(run).toHaveBeenCalledWith({ investigationId: 'inv', algorithm: 'descendants', params: { node: 'a' } });
    });

    it('shows the answer by its kind and highlights the canvas ids', () => {
        const { fixture, c, el } = make(() =>
            completed({
                algorithm: 'connectedComponents',
                kind: 'GROUPS',
                dropped: 0,
                elapsedMs: 1,
                groups: [['a', 'b'], ['c']],
            }),
        );
        const emitted: unknown[] = [];
        c.emphasisChange.subscribe((e) => emitted.push(e));
        c.pick('connectedComponents');
        fixture.detectChanges();
        el.querySelector<HTMLButtonElement>('[data-testid=run-on-server]')!.click();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid=algo-result]')!.textContent).toContain('2 groups found.');
        el.querySelector<HTMLButtonElement>('[data-testid=algo-highlight]')!.click();
        expect(emitted).toEqual([{ nodeIds: ['c-a', 'c-b', 'c-c'], edgeIds: [] }]);
    });
});

describe('LinkAnalysisServerAlgorithmsComponent refused re-run (LA-DEMO-POLISH-1)', () => {
    it('a 429 clears the previous answer instead of leaving it under the error banner', async () => {
        let n = 0;
        const run = vi.fn(() =>
            n++ === 0
                ? of(
                      completed({
                          algorithm: 'kCore',
                          kind: 'SCORES',
                          dropped: 0,
                          elapsedMs: 1,
                          scores: [{ id: 'a', label: 'A', score: 0.4080267558479552 }],
                      }),
                  )
                : throwError(() => new HttpErrorResponse({ status: 429, error: { error: 'busy' } })),
        );
        const { fixture, c, el } = makeWith(run);
        c.pick('kCore');
        fixture.detectChanges();
        el.querySelector<HTMLButtonElement>('[data-testid=run-on-server]')!.click();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid=algo-result]')!.textContent).toContain('A: 0.408');
        el.querySelector<HTMLButtonElement>('[data-testid=run-on-server]')!.click();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid=algo-result]')).toBeNull();
        expect(el.querySelector('inspecto-alert')).not.toBeNull();
        await expectNoA11yViolations(el);
    });
});

describe('summarizeGraphRun', () => {
    const base = { dropped: 0, elapsedMs: 1 };
    it('reads a flag, bridges (link ids) and a score list', () => {
        expect(summarizeGraphRun({ ...base, algorithm: 'isForest', kind: 'FLAG', value: true }, MAP).headline).toBe(
            'Yes.',
        );
        const b = summarizeGraphRun({ ...base, algorithm: 'bridges', kind: 'IDS', ids: ['l1'] }, MAP);
        expect(b.edgeIds).toEqual(['e-l1']);
        expect(b.nodeIds).toEqual([]);
        const s = summarizeGraphRun(
            {
                ...base,
                algorithm: 'kCore',
                kind: 'SCORES',
                scores: [
                    { id: 'a', label: 'A', score: 2 },
                    { id: 'x', label: 'X', score: 1 },
                ],
            },
            MAP,
        );
        expect(s.lines).toEqual(['A: 2', 'X: 1']);
        expect(s.nodeIds).toEqual(['c-a']); // x is not drawn
    });

    it('rounds scores for display to at most 4 decimals (LA-DEMO-POLISH-1)', () => {
        const s = summarizeGraphRun(
            {
                ...base,
                algorithm: 'pageRank',
                kind: 'SCORES',
                scores: [{ id: 'a', label: 'A', score: 0.4080267558479552 }],
            },
            MAP,
        );
        expect(s.lines).toEqual(['A: 0.408']);
        const h = summarizeGraphRun(
            {
                ...base,
                algorithm: 'hits',
                kind: 'HITS',
                hubs: [{ id: 'a', label: 'A', score: 0.123456 }],
                authorities: [{ id: 'a', label: 'A', score: 1 / 3 }],
            },
            MAP,
        );
        expect(h.lines).toEqual(['Hub A: 0.1235', 'Authority A: 0.3333']);
    });

    it('carries the full-precision score for the tooltip only where the display rounded it (LA-DEMO-POLISH-1)', () => {
        const s = summarizeGraphRun(
            {
                ...base,
                algorithm: 'pageRank',
                kind: 'SCORES',
                scores: [
                    { id: 'a', label: 'A', score: 0.4080267558479552 },
                    { id: 'x', label: 'X', score: 1 },
                ],
            },
            MAP,
        );
        expect(s.fullLines).toEqual(['A: 0.4080267558479552', '']);
        const h = summarizeGraphRun(
            {
                ...base,
                algorithm: 'hits',
                kind: 'HITS',
                hubs: [{ id: 'a', label: 'A', score: 0.123456 }],
                authorities: [],
            },
            MAP,
        );
        expect(h.fullLines).toEqual(['Hub A: 0.123456']);
    });

    it('carries the full-precision propagated score on the summary line and the table row (LA-DEMO-POLISH-1)', () => {
        const r = {
            ...base,
            algorithm: 'propagatedRisk',
            kind: 'PROPAGATED_RISK',
            scores: [{ id: 'a', label: 'A', score: 68.04, raw: 70.123, own: 40, contributors: 1, factors: [] }],
        } as GraphRunResult;
        const s = summarizeGraphRun(r, MAP);
        expect(s.lines).toEqual(['A: 68']);
        expect(s.fullLines).toEqual(['A: 68.04']);
        expect(propagatedRiskRows(r, MAP, (i) => i)[0].full).toEqual({ score: 68.04, raw: 70.123, own: 40 });
    });
});
