import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { HttpHeaders, HttpResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
    AuthoredPipeline,
    withoutDerivedEdges,
    PipelineBundleImportResult,
    PipelineListRow,
    PipelineSummary,
    PipelinesService,
    splitPipelineRows,
} from './pipelines.service';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths

/**
 * The R2 server-bundle pair (`PipelineBundleRoutes`): the export is a blob download whose filename
 * rides Content-Disposition, and the import is a RAW zip body — `name`/`conflict` travel as query
 * params, never a JSON body (the server reads the body as the zip bytes verbatim).
 */
describe('PipelinesService (server bundle, R2)', () => {
    let svc: PipelinesService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [PipelinesService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(PipelinesService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('GET /pipelines/{name}/bundle as a blob response for exportBundle()', () => {
        let received: HttpResponse<Blob> | undefined;
        svc.exportBundle('demo').subscribe((r) => (received = r));

        const req = httpMock.expectOne((r) => r.method === 'GET' && r.url === `${base}/pipelines/demo/bundle`);
        expect(req.request.responseType).toBe('blob');
        req.flush(new Blob(['zip']), {
            headers: new HttpHeaders({ 'Content-Disposition': 'attachment; filename="demo.pipeline-bundle.zip"' }),
        });

        expect(received!.body).toBeInstanceOf(Blob);
        expect(svc.bundleFilename(received!, 'demo')).toBe('demo.pipeline-bundle.zip');
    });

    it('bundleFilename falls back to <name>-bundle.zip when the header is absent', () => {
        const res = new HttpResponse<Blob>({ body: new Blob() });
        expect(svc.bundleFilename(res, 'demo')).toBe('demo-bundle.zip');
    });

    it('POST /pipelines/import with the raw zip body and name/conflict as query params', () => {
        const zip = new Blob(['zip'], { type: 'application/zip' });
        let received: PipelineBundleImportResult | undefined;
        svc.importBundle(zip, 'demo copy', 'refuse').subscribe((r) => (received = r));

        const req = httpMock.expectOne((r) => r.method === 'POST' && r.url === `${base}/pipelines/import`);
        // The body IS the zip — a JSON wrapper would be unreadable to the server's unzip.
        expect(req.request.body).toBe(zip);
        expect(req.request.params.get('name')).toBe('demo copy');
        expect(req.request.params.get('conflict')).toBe('refuse');
        req.flush({
            written: true,
            pipeline: 'demo_copy',
            path: 'demo_copy/demo_copy_pipeline.toon',
            files: ['demo_copy_pipeline.toon'],
            active: false,
            findings: [],
        });

        expect(received!.pipeline).toBe('demo_copy');
        expect(received!.active).toBe(false);
    });
});

/** PIPELINE-LOAD-FAILURE-INVISIBLE-1: `GET /pipelines` also lists files that did not load (`loadError`). */
describe('PipelinesService (broken rows)', () => {
    let svc: PipelinesService;
    let httpMock: HttpTestingController;
    const rows = [
        { name: 'good', active: true, nodeCount: 4, edgeCount: 3, produces: ['good'], consumes: [] },
        { name: 'orders', path: '/c/orders_pipeline.toon', loadError: { file: '/c/s.toon', line: 7, message: 'm' } },
    ];

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [PipelinesService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(PipelinesService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('list() drops broken rows, so no consumer that picks or counts Pipelines ever sees one', () => {
        let got: PipelineSummary[] | undefined;
        svc.list().subscribe((r) => (got = r));
        httpMock.expectOne(`${base}/pipelines`).flush(rows);
        expect(got!.map((p) => p.name)).toEqual(['good']);
    });

    it('listWithBroken() keeps them, and splitPipelineRows separates the two in served order', () => {
        let got: PipelineListRow[] | undefined;
        svc.listWithBroken().subscribe((r) => (got = r));
        httpMock.expectOne(`${base}/pipelines`).flush(rows);
        const { pipelines, broken } = splitPipelineRows(got!);
        expect(pipelines.map((p) => p.name)).toEqual(['good']);
        expect(broken.map((b) => [b.name, b.loadError.line])).toEqual([['orders', 7]]);
    });
});

/**
 * GRAPH-RAW-COMPANION-ENRICHMENT-ILLEGAL-EMIT-1: `GET …/graph/raw` draws a companion enrichment with a
 * DERIVED display-only edge (`rel: companion, derived: true`). It must never travel back as a real edge —
 * neither in a save nor in a candidate dry run.
 */
describe('PipelinesService (derived companion edge)', () => {
    let svc: PipelinesService;
    let httpMock: HttpTestingController;
    const graph: AuthoredPipeline = {
        name: 'orders',
        active: true,
        nodes: [
            { id: 'acq', type: 'acquisition' },
            { id: 'sink', type: 'sink.persistent' },
            { id: 'daily', type: 'enrichment', use: 'enrichment/daily' },
        ],
        edges: [
            { from: 'acq', rel: 'data', to: 'sink' },
            { from: 'sink', rel: 'companion', to: 'daily', derived: true },
        ],
    };

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [PipelinesService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(PipelinesService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('withoutDerivedEdges drops only the derived edge and keeps the companion node', () => {
        const out = withoutDerivedEdges(graph);
        expect(out.edges).toEqual([{ from: 'acq', rel: 'data', to: 'sink' }]);
        expect(out.nodes.map((n) => n.id)).toEqual(['acq', 'sink', 'daily']);
        expect(graph.edges.length).toBe(2); // the canvas model is not mutated
    });

    it('savePipelineGraph never sends the derived edge back', () => {
        svc.savePipelineGraph('orders', graph).subscribe();
        const req = httpMock.expectOne((r) => r.method === 'PUT' && r.url === `${base}/pipelines/orders/graph`);
        expect((req.request.body as AuthoredPipeline).edges.some((e) => e.derived)).toBe(false);
        req.flush({ written: true });
    });

    it('a candidate dry run never sends the derived edge back', () => {
        svc.dryRunAuthored('orders', [{ ID: '1' }], graph).subscribe();
        const req = httpMock.expectOne(
            (r) => r.method === 'POST' && r.url === `${base}/pipelines/authored/orders/dry-run`,
        );
        const body = req.request.body as { pipeline: AuthoredPipeline };
        expect(body.pipeline.edges.some((e) => e.derived)).toBe(false);
        req.flush({});
    });
});
