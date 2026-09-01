import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { HttpHeaders, HttpResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PipelineBundleImportResult, PipelinesService } from './pipelines.service';
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
