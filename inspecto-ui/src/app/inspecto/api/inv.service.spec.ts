import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { InvService, MultiProjectionResult, RecursivePathsResult } from './inv.service';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1';

/** LA-08 / LA-11 — the two server routes the Link Analysis studio calls, pinned to `InvRoutes`' body names. */
describe('InvService (LA-08 multi projection, LA-11 recursive paths)', () => {
    let svc: InvService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [InvService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(InvService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('POSTs node + edge mappings verbatim to /inv/projection/multi', () => {
        let got: MultiProjectionResult | undefined;
        const body = {
            nodes: [{ dataset: 'people', idColumn: 'PERSON_ID', labelColumn: 'NAME', category: 'person' }],
            edges: [{ dataset: 'calls', sourceColumn: 'A_PARTY', targetColumn: 'B_PARTY', type: 'called' }],
        };
        svc.projectMulti(body).subscribe((r) => (got = r));

        const req = httpMock.expectOne(`${base}/inv/projection/multi`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual(body);
        const res: MultiProjectionResult = { nodes: [], edges: [], mappings: [], truncated: false };
        req.flush(res);
        expect(got).toEqual(res);
    });

    it('POSTs a traversal to /inv/traversal/recursive-paths with the /inv/projection column names', () => {
        let got: RecursivePathsResult | undefined;
        const body = {
            dataset: 'calls',
            sourceCol: 'A_PARTY',
            targetCol: 'B_PARTY',
            startNode: '4471',
            targetNode: '4499',
            maxDepth: 4,
            direction: 'UNDIRECTED' as const,
        };
        svc.recursivePaths(body).subscribe((r) => (got = r));

        const req = httpMock.expectOne(`${base}/inv/traversal/recursive-paths`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual(body);
        const res: RecursivePathsResult = {
            paths: [{ nodes: ['4471', '4480', '4499'], hops: 2, weight: null }],
            truncated: false,
            edgeYieldCapped: false,
            fences: { maxDepth: 4, maxEdgeYield: 10000, timeoutMs: 5000 },
        };
        req.flush(res);
        expect(got).toEqual(res);
    });

    // ── LA-10 — pinned to `InvestigationRoutes`' paths and body names ──

    it('creates an Investigation with the projection column names', () => {
        const body = {
            title: 'Burners',
            dataset: 'calls',
            sourceCol: 'A_PARTY',
            targetCol: 'B_PARTY',
            linkKindCol: 'KIND',
        };
        svc.createInvestigation(body).subscribe();
        const req = httpMock.expectOne(`${base}/inv/investigations`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual(body);
        req.flush({ id: 'inv-1' });
    });

    it('appends ops to /{id}/ops with the op body verbatim (exclude carries its reason)', () => {
        svc.appendInvestigationOp('inv-1', { op: 'exclude', ids: ['4471'], reason: 'switchboard' }).subscribe();
        const req = httpMock.expectOne(`${base}/inv/investigations/inv-1/ops`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ op: 'exclude', ids: ['4471'], reason: 'switchboard' });
        req.flush({});
    });

    it('undo, reorder and replay POST to their own sub-routes; the id is path-encoded', () => {
        svc.undoInvestigation('inv/1').subscribe();
        const undo = httpMock.expectOne(`${base}/inv/investigations/inv%2F1/undo`);
        expect(undo.request.method).toBe('POST');
        undo.flush({});

        svc.reorderInvestigation('inv-1', { order: [2, 1] }).subscribe();
        const fork = httpMock.expectOne(`${base}/inv/investigations/inv-1/reorder`);
        expect(fork.request.body).toEqual({ order: [2, 1] });
        fork.flush({});

        svc.replayInvestigation('inv-1', { reread: true }).subscribe();
        const replay = httpMock.expectOne(`${base}/inv/investigations/inv-1/replay`);
        expect(replay.request.method).toBe('POST');
        expect(replay.request.body).toEqual({ reread: true });
        replay.flush({});
    });

    it('reads the log with GET and an optional limit', () => {
        svc.investigationLog('inv-1', 50).subscribe();
        const req = httpMock.expectOne((r) => r.url === `${base}/inv/investigations/inv-1/log`);
        expect(req.request.method).toBe('GET');
        expect(req.request.params.get('limit')).toBe('50');
        req.flush({});
    });
});
