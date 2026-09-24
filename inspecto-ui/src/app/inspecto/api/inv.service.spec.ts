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

    it('LA-13: an expand sends its rung with `budget` (never `limit`), and a window op sends only its window', () => {
        svc.appendInvestigationOp('inv-1', {
            op: 'expand',
            budget: 500,
            direction: 'out',
            window: { slot: { start: '22:00', end: '06:00' }, days: ['FRI'], timezone: 'Europe/London' },
            minDistinctDays: 2,
        }).subscribe();
        const expand = httpMock.expectOne(`${base}/inv/investigations/inv-1/ops`);
        expect(expand.request.body.budget).toBe(500);
        expect('limit' in expand.request.body).toBe(false);
        expand.flush({});
        svc.appendInvestigationOp('inv-1', { op: 'window', window: 'full' }).subscribe();
        const window = httpMock.expectOne(`${base}/inv/investigations/inv-1/ops`);
        expect(window.request.body).toEqual({ op: 'window', window: 'full' });
        window.flush({});
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

    it('LA-19: annotate sends ids + note only (never confidence); coverage GETs from/to/timezone', () => {
        svc.appendInvestigationOp('inv-1', { op: 'annotate', ids: ['a'], note: 'burner pattern' }).subscribe();
        const op = httpMock.expectOne(`${base}/inv/investigations/inv-1/ops`);
        expect(op.request.body).toEqual({ op: 'annotate', ids: ['a'], note: 'burner pattern' });
        op.flush({});

        svc.investigationCoverage('inv-1', { from: '2026-09-01T00:00:00Z', to: '2026-09-08T00:00:00Z' }).subscribe();
        const cov = httpMock.expectOne((r) => r.url === `${base}/inv/investigations/inv-1/coverage`);
        expect(cov.request.method).toBe('GET');
        expect(cov.request.params.get('from')).toBe('2026-09-01T00:00:00Z');
        expect(cov.request.params.get('to')).toBe('2026-09-08T00:00:00Z');
        expect(cov.request.params.has('timezone')).toBe(false);
        cov.flush({});

        svc.investigationCoverage('inv-1').subscribe(); // no params — the server uses the Investigation's window
        const own = httpMock.expectOne((r) => r.url === `${base}/inv/investigations/inv-1/coverage`);
        expect(own.request.params.keys()).toEqual([]);
        own.flush({});
    });

    it('reads the log with GET and an optional limit', () => {
        svc.investigationLog('inv-1', 50).subscribe();
        const req = httpMock.expectOne((r) => r.url === `${base}/inv/investigations/inv-1/log`);
        expect(req.request.method).toBe('GET');
        expect(req.request.params.get('limit')).toBe('50');
        req.flush({});
    });

    // ── LA-12 / LA-20 / LA-23 — pinned to DossierRoutes, WorkingSetRoutes, the Template + Measure routes ──

    it('reads the dossier as json, a rendering as a Blob, and verifies {manifest}', () => {
        svc.dossier('inv-1', { at: 3, snapshots: ['s1', 's2'] }).subscribe();
        const json = httpMock.expectOne((r) => r.url === `${base}/inv/investigations/inv-1/dossier`);
        expect(json.request.method).toBe('GET');
        expect(json.request.params.get('format')).toBe('json');
        expect(json.request.params.get('at')).toBe('3');
        expect(json.request.params.get('snapshots')).toBe('s1,s2');
        json.flush({});

        svc.dossierRendering('inv-1', 'method').subscribe();
        const blob = httpMock.expectOne((r) => r.url === `${base}/inv/investigations/inv-1/dossier`);
        expect(blob.request.params.get('format')).toBe('method');
        expect(blob.request.params.has('snapshots')).toBe(false);
        expect(blob.request.responseType).toBe('blob');
        blob.flush(new Blob(['m']));

        const manifest = { root: 'sha256:r', at: 2, artefacts: [], investigation: 'inv-1' };
        svc.verifyDossier('inv-1', manifest).subscribe();
        const verify = httpMock.expectOne(`${base}/inv/investigations/inv-1/dossier/verify`);
        expect(verify.request.method).toBe('POST');
        expect(verify.request.body).toEqual({ manifest });
        verify.flush({});
    });

    it('reads a Working Set relation page with of/limit/offset', () => {
        svc.workingSetRelation('inv-1', { of: 'links', limit: 200, offset: 400 }).subscribe();
        const req = httpMock.expectOne((r) => r.url === `${base}/inv/investigations/inv-1/working-set`);
        expect(req.request.params.get('of')).toBe('links');
        expect(req.request.params.get('limit')).toBe('200');
        expect(req.request.params.get('offset')).toBe('400');
        req.flush({});
    });

    it('saves, reads and instantiates a template; reads measures; binds an Alert Rule', () => {
        svc.saveInvestigationTemplate('inv-1', { title: 'Method' }).subscribe();
        const save = httpMock.expectOne(`${base}/inv/investigations/inv-1/template`);
        expect(save.request.method).toBe('POST');
        expect(save.request.body).toEqual({ title: 'Method' });
        save.flush({});

        svc.investigationTemplate('tpl/1').subscribe();
        const get = httpMock.expectOne(`${base}/inv/investigation-templates/tpl%2F1`);
        expect(get.request.method).toBe('GET');
        get.flush({});

        const body = { params: { seed1: ['a'] }, dataset: 'calls2', sourceCol: 'A', targetCol: 'B' };
        svc.instantiateTemplate('tpl-1', body).subscribe();
        const inst = httpMock.expectOne(`${base}/inv/investigation-templates/tpl-1/instantiate`);
        expect(inst.request.method).toBe('POST');
        expect(inst.request.body).toEqual(body);
        inst.flush({});

        svc.investigationMeasures('inv-1').subscribe();
        const m = httpMock.expectOne(`${base}/inv/investigations/inv-1/measures`);
        expect(m.request.method).toBe('GET');
        m.flush({});

        const rule = {
            name: 'big',
            relation: 'entities' as const,
            measure: 'count',
            comparator: 'gt' as const,
            threshold: 10,
            severity: 'WARNING' as const,
        };
        svc.bindInvestigationAlertRule('inv-1', rule).subscribe();
        const bind = httpMock.expectOne(`${base}/inv/investigations/inv-1/alert-rules`);
        expect(bind.request.method).toBe('POST');
        expect(bind.request.body).toEqual(rule);
        bind.flush({});
    });
});
