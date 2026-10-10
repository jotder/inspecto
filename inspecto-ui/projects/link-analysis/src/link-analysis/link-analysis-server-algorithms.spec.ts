import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    GraphAlgorithm,
    GraphAlgorithmCatalogue,
    GraphRunResult,
    GraphRunView,
    GraphRunsService,
} from '@inspecto/link-analysis/api/graph-runs.service';
import { ServerIdMap } from './graph-run-apply';
import { summarizeGraphRun } from './graph-run-summary';
import { LinkAnalysisServerAlgorithmsComponent } from './link-analysis-server-algorithms.component';

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
        alg('kCore'),
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
        alg('neighborhood', {
            resultKind: 'GRAPH',
            needsNode: true,
            params: [{ name: 'direction', type: 'ENUM', default: 'both', allowed: ['out', 'in', 'both'] }],
        }),
        // a list/map parameter the panel has no control for: API only
        alg('propagatedRisk', {
            label: 'Propagated risk',
            cost: 'JOB',
            resultKind: 'PROPAGATED_RISK',
            params: [
                { name: 'nodeScores', type: 'SCORE_MAP', default: {}, min: 0, max: 100, maxSize: 500000 },
                { name: 'seeds', type: 'ID_LIST', default: [], maxSize: 500000 },
                { name: 'weights', type: 'DOUBLE_LIST', default: [1, 0.6, 0.35, 0.15], min: 0, max: 1, maxSize: 6 },
                { name: 'direction', type: 'ENUM', default: 'both', allowed: ['out', 'in', 'both'] },
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
    TestBed.configureTestingModule({
        imports: [LinkAnalysisServerAlgorithmsComponent],
        providers: [
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
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisServerAlgorithmsComponent);
    fixture.componentRef.setInput('investigationId', 'inv');
    fixture.componentRef.setInput('serverIds', MAP);
    fixture.componentRef.setInput('nodeOptions', [{ id: 'c-a', label: 'A' }]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, c: fixture.componentInstance, run, el };
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
        expect(el.querySelector('[data-testid=algo-count]')!.textContent).toContain('9 algorithms');
    });

    it('marks an algorithm with a list or map parameter API only: no number field for it, Run held and said why', async () => {
        const { fixture, c, el, run } = make();
        expect(c.algorithmOptions().find((o) => o.value === 'propagatedRisk')!.label).toBe(
            'Propagated risk (API only)',
        );
        expect(c.algorithmOptions().find((o) => o.value === 'pageRank')!.label).toBe('pageRank');
        c.pick('propagatedRisk');
        fixture.detectChanges();
        for (const name of ['nodeScores', 'seeds', 'weights'])
            expect(el.querySelector(`[data-testid=param-${name}]`)).toBeNull();
        expect(el.querySelector('[data-testid=param-direction]')).not.toBeNull();
        expect(el.querySelector('[data-testid=blocked-reason]')!.textContent).toContain(
            'API only: this panel cannot edit nodeScores, seeds, weights yet',
        );
        const button = el.querySelector<HTMLButtonElement>('[data-testid=run-on-server]')!;
        expect(button.disabled).toBe(true);
        button.click();
        expect(run).not.toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('builds the parameter form from the descriptors and sends what was edited', () => {
        const { fixture, c, el, run } = make();
        c.pick('pageRank');
        fixture.detectChanges();
        const damping = el.querySelector<HTMLInputElement>('[data-testid=param-damping]')!;
        expect(damping.value).toBe('0.85');
        damping.value = '0.5';
        damping.dispatchEvent(new Event('change'));
        fixture.detectChanges();
        el.querySelector<HTMLButtonElement>('[data-testid=run-on-server]')!.click();
        expect(run).toHaveBeenCalledWith({ investigationId: 'inv', algorithm: 'pageRank', params: { damping: 0.5 } });
    });

    it('holds the run until a required node is picked, then sends the SERVER id of the canvas node', () => {
        const { fixture, c, el, run } = make();
        c.pick('descendants');
        fixture.detectChanges();
        expect(el.querySelector('[data-testid=blocked-reason]')!.textContent).toContain('Pick node first');
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
});
