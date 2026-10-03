import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    GraphAlgorithmCatalogue,
    GraphRunRequest,
    GraphRunStatus,
    GraphRunView,
    GraphRunsService,
    LinkIndexList,
} from '@inspecto/link-analysis/api/graph-runs.service';
import { LinkAnalysisServerRunComponent } from './link-analysis-server-run.component';

const CATALOGUE = {
    ceilings: { maxNodes: 100000, maxEdges: 1000000, timeoutMs: 600000 },
    algorithms: [
        { id: 'neighborhood', engines: ['memory', 'index'] },
        { id: 'degreeCentrality', engines: ['memory', 'index'] },
        { id: 'pageRank', engines: ['memory'] },
    ],
} as unknown as GraphAlgorithmCatalogue;

const index = (over: Record<string, unknown> = {}) => ({
    dataset: 'calls',
    mapping: { sourceCol: 'a', targetCol: 'b', kindCol: 'k' },
    version: 3,
    stale: false,
    reason: null,
    ...over,
});

function indexRun(over: Partial<GraphRunView> = {}): GraphRunView {
    return {
        runId: 'r1',
        status: 'COMPLETED' as GraphRunStatus,
        investigationId: 'inv-1',
        algorithm: 'neighborhood',
        engine: 'index',
        budget: { maxNodes: 5000, maxEdges: 50000, timeoutMs: 30000 },
        budgetClamped: false,
        consumed: { nodes: 2, edges: 1, elapsedMs: 5, work: 0 },
        progress: { work: 0, fraction: 1 },
        cancelRequested: false,
        cached: false,
        createdAt: '2026-10-03T00:00:00Z',
        source: { kind: 'index', version: 3, stale: false, fingerprint: 'known' },
        result: {
            algorithm: 'neighborhood',
            kind: 'GRAPH',
            dropped: 0,
            elapsedMs: 4,
            nodes: [
                { id: 'A', label: 'A' },
                { id: 'B', label: 'B' },
            ],
            edges: [{ id: 'e1', source: 'A', target: 'B' }],
        },
        ...over,
    };
}

function make(
    opts: {
        algorithm?: string;
        list?: LinkIndexList | null;
        params?: Record<string, unknown>;
        seeds?: string[];
        indexOnly?: boolean;
        run?: () => Observable<GraphRunView>;
    } = {},
) {
    const stream = new Subject<GraphRunView>();
    const runs = {
        catalogue: signal(CATALOGUE),
        indexes: signal<LinkIndexList | null>(
            opts.list === undefined ? ({ enabled: true, indexes: [index()] } as LinkIndexList) : opts.list,
        ),
        loadCatalogue: vi.fn(),
        loadIndexes: vi.fn(),
        run: vi.fn((_req: GraphRunRequest) => (opts.run ? opts.run() : stream)),
        cancel: vi.fn(),
    };
    TestBed.configureTestingModule({
        imports: [LinkAnalysisServerRunComponent],
        providers: [provideNoopAnimations(), { provide: GraphRunsService, useValue: runs }],
    });
    const fixture = TestBed.createComponent(LinkAnalysisServerRunComponent);
    fixture.componentRef.setInput('algorithm', opts.algorithm ?? 'neighborhood');
    fixture.componentRef.setInput('params', opts.params ?? { node: 'A', hops: 1 });
    fixture.componentRef.setInput('investigationId', 'inv-1');
    if (opts.seeds) fixture.componentRef.setInput('seeds', opts.seeds);
    if (opts.indexOnly) fixture.componentRef.setInput('indexOnly', true);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const q = (id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
    return { fixture, runs, stream, el, q };
}

const idx = (list: unknown): LinkIndexList => list as LinkIndexList;

/** D-3 step 7 SPA side - "Run on index" is offered only where the server can honour it, and never falls back. */
describe('LinkAnalysisServerRunComponent - input index', () => {
    it('offers Run on index for an index-capable algorithm when the Space serves a published index', () => {
        const { q } = make();
        expect(q('run-on-index')).not.toBeNull();
        expect((q('run-on-index') as HTMLButtonElement).disabled).toBe(false);
    });

    it('offers nothing for an algorithm whose engines lack index', () => {
        expect(make({ algorithm: 'pageRank' }).q('run-on-index')).toBeNull();
    });

    it('offers nothing when index.enabled is off', () => {
        expect(make({ list: idx({ enabled: false, indexes: [index()] }) }).q('run-on-index')).toBeNull();
    });

    it('offers nothing when no index exists or the list could not be read', () => {
        expect(make({ list: idx({ enabled: true, indexes: [] }) }).q('run-on-index')).toBeNull();
    });

    it('offers nothing while the index list is unknown', () => {
        expect(make({ list: null }).q('run-on-index')).toBeNull();
    });

    it('indexOnly hides the Working Set button', () => {
        const { q } = make({ indexOnly: true });
        expect(q('run-on-server')).toBeNull();
        expect(q('run-on-index')).not.toBeNull();
    });

    it('sends the index body: input, the index own dataset and columns, and no seeds for neighborhood', () => {
        const { q, runs, fixture } = make();
        q('run-on-index')!.click();
        fixture.detectChanges();
        expect(runs.run).toHaveBeenCalledWith({
            investigationId: 'inv-1',
            algorithm: 'neighborhood',
            input: 'index',
            dataset: 'calls',
            sourceCol: 'a',
            targetCol: 'b',
            linkKindCol: 'k',
            params: { node: 'A', hops: 1 },
        });
    });

    it('omits linkKindCol for an index without a kind column and sends seeds for degreeCentrality only', () => {
        const { q, runs, fixture } = make({
            algorithm: 'degreeCentrality',
            params: {},
            seeds: ['A', 'B'],
            list: idx({
                enabled: true,
                indexes: [index({ mapping: { sourceCol: 'a', targetCol: 'b', kindCol: null } })],
            }),
        });
        q('run-on-index')!.click();
        fixture.detectChanges();
        expect(runs.run).toHaveBeenCalledWith({
            investigationId: 'inv-1',
            algorithm: 'degreeCentrality',
            input: 'index',
            dataset: 'calls',
            sourceCol: 'a',
            targetCol: 'b',
            seeds: ['A', 'B'],
        });
    });

    it('states why it cannot run: degreeCentrality without seeds', () => {
        const { q } = make({ algorithm: 'degreeCentrality', params: {} });
        expect((q('run-on-index') as HTMLButtonElement).disabled).toBe(true);
        expect(q('index-blocked-reason')!.textContent).toMatch(/1 to 20 nodes/);
    });

    it('states why it cannot run: more than 20 seeds', () => {
        const { q } = make({
            algorithm: 'degreeCentrality',
            params: {},
            seeds: Array.from({ length: 21 }, (_, i) => `n${i}`),
        });
        expect(q('index-blocked-reason')!.textContent).toMatch(/at most 20/);
    });

    it('states why it cannot run: more than 2 hops, and starts nothing', () => {
        const { q, runs } = make({ params: { node: 'A', hops: 3 } });
        expect(q('index-blocked-reason')!.textContent).toMatch(/at most 2 hops/);
        q('run-on-index')!.click();
        expect(runs.run).not.toHaveBeenCalled();
    });

    it('shows a stale chip beside the button when the listed index is stale', () => {
        const { q } = make({ list: idx({ enabled: true, indexes: [index({ stale: true, reason: 'files added' })] }) });
        expect(q('index-stale-chip')!.textContent).toMatch(/stale/i);
    });

    it('a COMPLETED index run states the version, summarises the answer and does not touch the canvas', () => {
        const { q, stream, fixture } = make();
        const emitted = vi.fn();
        fixture.componentInstance.completed.subscribe(emitted);
        q('run-on-index')!.click();
        stream.next(indexRun());
        fixture.detectChanges();
        expect(q('index-source')!.textContent).toMatch(/version 3/);
        expect(q('source-stale-chip')).toBeNull();
        expect(q('index-summary')!.textContent).toMatch(/2 nodes and 1 link/);
        expect(emitted).not.toHaveBeenCalled();
    });

    it('a stale-but-served run shows the stale chip and the reason', () => {
        const { q, stream, fixture } = make();
        q('run-on-index')!.click();
        stream.next(
            indexRun({
                source: { kind: 'index', version: 3, stale: true, staleReason: 'files added', fingerprint: 'known' },
            }),
        );
        fixture.detectChanges();
        expect(q('source-stale-chip')).not.toBeNull();
        expect(q('index-source')!.textContent).toMatch(/files added/);
    });

    it('a 422 refusal is stated in words with the server sentence, and the Working Set is never run instead', () => {
        const refusal = new HttpErrorResponse({
            status: 422,
            error: {
                error: {
                    message:
                        'the edge index cannot serve this run (no_index); input "index" never falls back to the Working Set',
                },
            },
        });
        const { q, runs, el, fixture } = make({ run: () => throwError(() => refusal) });
        q('run-on-index')!.click();
        fixture.detectChanges();
        expect(el.textContent).toMatch(/No edge index has been built/);
        expect(el.textContent).toMatch(/no_index/);
        expect(runs.run).toHaveBeenCalledTimes(1);
        expect(runs.run.mock.calls[0][0]).toMatchObject({ input: 'index' });
    });

    it('has no accessibility violations with the index button, stale chip and a result', async () => {
        const { q, stream, fixture } = make({ list: idx({ enabled: true, indexes: [index({ stale: true })] }) });
        q('run-on-index')!.click();
        stream.next(indexRun());
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
