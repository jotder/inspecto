import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { GraphRunStatus, GraphRunView, GraphRunsService, graphRunErrorMessage } from './graph-runs.service';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1';

/** A run view as `GraphRunRoutes.view` writes it; `over` overrides the fields a case cares about. */
function view(status: GraphRunStatus, over: Partial<GraphRunView> = {}): GraphRunView {
    return {
        runId: 'r1',
        status,
        investigationId: 'inv-1',
        algorithm: 'betweennessCentrality',
        engine: 'in-memory',
        budget: { maxNodes: 5000, maxEdges: 50000, timeoutMs: 30000 },
        budgetClamped: false,
        consumed: { nodes: 800, edges: 1200, elapsedMs: 10, work: 0 },
        progress: { work: 0, fraction: 0 },
        cancelRequested: false,
        cached: false,
        createdAt: '2026-10-01T00:00:00Z',
        ...over,
    };
}

/** D-4 step 7 - the five routes `GraphRunRoutes` serves, and polling a started run to its terminal state. */
describe('GraphRunsService', () => {
    let svc: GraphRunsService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [GraphRunsService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(GraphRunsService);
        svc.pollMs = 5; // real timers, a short period
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    /** Wait until a request to `path` is pending, then answer it. */
    async function answer(path: string, body: unknown, method = 'GET'): Promise<void> {
        for (let i = 0; i < 200; i++) {
            const m = http.match((r) => r.url === `${base}${path}` && r.method === method);
            if (m.length) {
                m[0].flush(body);
                return;
            }
            await new Promise((r) => setTimeout(r, 2));
        }
        throw new Error(`no ${method} ${path} arrived`);
    }

    it('lists the algorithms, starts, reads, lists and cancels on the routes the server serves', () => {
        svc.algorithms().subscribe();
        http.expectOne(`${base}/inv/graph/algorithms`).flush({ algorithms: [] });

        svc.start({ investigationId: 'inv-1', algorithm: 'cliques', params: { minSize: 3 } }).subscribe();
        const post = http.expectOne(`${base}/inv/graph/runs`);
        expect(post.request.method).toBe('POST');
        expect(post.request.body).toEqual({ investigationId: 'inv-1', algorithm: 'cliques', params: { minSize: 3 } });
        post.flush(view('QUEUED'));

        svc.get('r1').subscribe();
        http.expectOne(`${base}/inv/graph/runs/r1`).flush(view('RUNNING'));

        svc.list('inv-1').subscribe();
        const list = http.expectOne((r) => r.url === `${base}/inv/graph/runs`);
        expect(list.request.params.get('investigationId')).toBe('inv-1');
        list.flush({ runs: [], total: 0 });

        svc.cancel('r1').subscribe();
        const cancel = http.expectOne(`${base}/inv/graph/runs/r1/cancel`);
        expect(cancel.request.method).toBe('POST');
        cancel.flush({ runId: 'r1', status: 'RUNNING', cancelRequested: true });
    });

    it('run(): a 202 is polled at the interval until COMPLETED, emitting every view', async () => {
        const seen: GraphRunStatus[] = [];
        svc.run({ investigationId: 'inv-1', algorithm: 'betweennessCentrality' }).subscribe((v) => seen.push(v.status));
        http.expectOne(`${base}/inv/graph/runs`).flush(view('QUEUED'), { status: 202, statusText: 'Accepted' });

        await answer('/inv/graph/runs/r1', view('RUNNING', { progress: { work: 10, fraction: 0.5 } }));
        await answer(
            '/inv/graph/runs/r1',
            view('COMPLETED', {
                result: { algorithm: 'betweennessCentrality', kind: 'SCORES', dropped: 0, elapsedMs: 9, scores: [] },
            }),
        );

        expect(seen).toEqual(['QUEUED', 'RUNNING', 'COMPLETED']);
        // terminal: no further poll is scheduled (afterEach's verify() would fail on a pending read)
        await new Promise((r) => setTimeout(r, 30));
    });

    it('run(): a start that is already BUDGET_EXCEEDED is terminal at once - no polling, and no result key', async () => {
        const seen: GraphRunView[] = [];
        svc.run({ investigationId: 'inv-1', algorithm: 'betweennessCentrality' }).subscribe((v) => seen.push(v));
        http.expectOne(`${base}/inv/graph/runs`).flush(
            view('BUDGET_EXCEEDED', {
                exceeded: 'NODES',
                reason: 'the Working Set has 800 nodes; the budget allows 500 (budget.maxNodes).',
                budget: { maxNodes: 500, maxEdges: 50000, timeoutMs: 30000 },
            }),
        );
        await new Promise((r) => setTimeout(r, 30));
        expect(seen).toHaveLength(1);
        expect(seen[0].status).toBe('BUDGET_EXCEEDED');
        expect(seen[0].exceeded).toBe('NODES');
        expect(seen[0].consumed.nodes).toBe(800);
        expect('result' in seen[0]).toBe(false);
    });

    it('run(): a run polled into BUDGET_EXCEEDED (a timeout) ends there with the measured numbers', async () => {
        const seen: GraphRunView[] = [];
        svc.run({ investigationId: 'inv-1', algorithm: 'betweennessCentrality' }).subscribe((v) => seen.push(v));
        http.expectOne(`${base}/inv/graph/runs`).flush(view('QUEUED'), { status: 202, statusText: 'Accepted' });
        await answer(
            '/inv/graph/runs/r1',
            view('BUDGET_EXCEEDED', {
                exceeded: 'TIMEOUT',
                consumed: { nodes: 800, edges: 1200, elapsedMs: 30011, work: 7 },
            }),
        );
        expect(seen.map((v) => v.status)).toEqual(['QUEUED', 'BUDGET_EXCEEDED']);
        expect(seen[1].consumed.elapsedMs).toBe(30011);
    });

    it('watch(): unsubscribing stops the polling', async () => {
        const sub = svc.watch('r1').subscribe();
        await answer('/inv/graph/runs/r1', view('RUNNING'));
        sub.unsubscribe();
        await new Promise((r) => setTimeout(r, 30)); // several periods; a live timer would leave an open request
        expect(http.match(`${base}/inv/graph/runs/r1`)).toHaveLength(0);
    });

    it('loadCatalogue(): fills the signal once and swallows a failure', () => {
        svc.loadCatalogue();
        http.expectOne(`${base}/inv/graph/algorithms`).flush({
            engine: 'in-memory',
            algorithms: [],
            ceilings: { maxNodes: 100000, maxEdges: 1000000, timeoutMs: 600000 },
            defaults: { maxNodes: 5000, maxEdges: 50000, timeoutMs: 30000, clamped: false },
            pool: { threads: 2, queue: 8 },
            inlineWaitMs: 3000,
        });
        expect(svc.catalogue()?.ceilings.maxNodes).toBe(100000);
        svc.loadCatalogue(); // already loaded: no second request (verify() would fail)

        const other = TestBed.inject(GraphRunsService);
        other.catalogue.set(null);
        other.loadCatalogue();
        http.expectOne(`${base}/inv/graph/algorithms`).flush('', { status: 503, statusText: 'Service Unavailable' });
        expect(other.catalogue()).toBeNull();
    });
});

describe('graphRunErrorMessage', () => {
    const fail = (status: number, errorCode = '', message = '') =>
        new HttpErrorResponse({ status, error: { error: { errorCode, message } } });

    it('403 names the capability', () => {
        expect(graphRunErrorMessage(fail(403), 'x')).toMatch(/Run link graph analysis capability/);
    });
    it('404 says the Investigation or run is not visible to the caller', () => {
        expect(graphRunErrorMessage(fail(404), 'x')).toMatch(/not found/);
    });
    it('409 says the run already finished', () => {
        expect(graphRunErrorMessage(fail(409), 'x')).toMatch(/already finished/);
    });
    it('422 carries the server reason', () => {
        expect(graphRunErrorMessage(fail(422, 'CONFIG_VALIDATION_FAILED', "unknown field 'x'"), 'f')).toContain(
            "unknown field 'x'",
        );
    });
    it('503 tells a full waiting line from an unavailable module', () => {
        expect(graphRunErrorMessage(fail(503, 'STORE_BUSY'), 'x')).toMatch(/waiting line/);
        expect(graphRunErrorMessage(fail(503, 'CAPABILITY_UNAVAILABLE'), 'x')).toMatch(/not available here/);
    });
    it('anything else falls back', () => {
        expect(graphRunErrorMessage(fail(500), 'fallback')).toBe('fallback');
        expect(graphRunErrorMessage(new Error('boom'), 'fallback')).toBe('fallback');
    });
});
