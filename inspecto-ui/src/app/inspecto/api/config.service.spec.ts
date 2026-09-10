import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ConfigService } from './config.service';
import { ConfigReadResult, ConfigWriteResult } from './models';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1';

/**
 * Optimistic concurrency on the config read/write pair — `CLIENT-HALVES-1` (a).
 *
 * The ETag is an HTTP **header**, not an envelope field (`Envelope.java` never sets `metadata.etag`), so
 * these tests are about headers travelling in both directions. Server-side twin:
 * `ControlApiConfigIfMatchTest`.
 */
describe('ConfigService optimistic concurrency', () => {
    let svc: ConfigService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [ConfigService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(ConfigService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('read() surfaces the ETag the response header carried', () => {
        let got: ConfigReadResult | undefined;
        svc.read('pipeline', 'p1').subscribe((r) => (got = r));

        httpMock
            .expectOne((r) => r.method === 'GET' && r.url === `${base}/config/pipeline/p1`)
            .flush(
                { type: 'pipeline', name: 'p1', path: 'p1.toon', config: { name: 'p1' } },
                { headers: { ETag: '"sha256:abc"' } },
            );

        expect(got?.etag).toBe('"sha256:abc"');
        expect(got?.config).toEqual({ name: 'p1' }); // the body is still the plain DTO
    });

    it('read() leaves etag undefined when the server sent none, rather than inventing one', () => {
        let got: ConfigReadResult | undefined;
        svc.read('pipeline', 'p1').subscribe((r) => (got = r));

        httpMock
            .expectOne((r) => r.url === `${base}/config/pipeline/p1`)
            .flush({ type: 'pipeline', name: 'p1', path: 'p1.toon', config: {} });

        expect(got?.etag).toBeUndefined();
    });

    it('write() sends ifMatch as the If-Match HEADER', () => {
        svc.write('pipeline', { name: 'p1' }, { overwrite: true, ifMatch: '"sha256:abc"' }).subscribe();

        const req = httpMock.expectOne((r) => r.method === 'POST' && r.url === `${base}/config/write`);
        expect(req.request.headers.get('If-Match')).toBe('"sha256:abc"');
        req.flush({
            type: 'pipeline',
            written: true,
            path: 'p1.toon',
            name: 'p1',
            bytes: 1,
            overwritten: true,
            findings: [],
        });
    });

    it('🔴 write() must NOT leak ifMatch into the request BODY', () => {
        svc.write('pipeline', { name: 'p1' }, { overwrite: true, subdir: 'sub', ifMatch: '"sha256:abc"' }).subscribe();

        const req = httpMock.expectOne((r) => r.url === `${base}/config/write`);
        // The rest of `opts` IS spread into the body, and the server sweeps an unrecognised top-level key
        // into the config instead of rejecting it — so a leak here would silently write a bogus
        // `ifMatch` key into the operator's config file.
        expect(req.request.body.ifMatch).toBeUndefined();
        expect(req.request.body.overwrite).toBe(true); // the real body options still travel
        expect(req.request.body.subdir).toBe('sub');
        req.flush({
            type: 'pipeline',
            written: true,
            path: 'p1.toon',
            name: 'p1',
            bytes: 1,
            overwritten: true,
            findings: [],
        });
    });

    it('write() sends no If-Match at all when none was asked for', () => {
        svc.write('pipeline', { name: 'p1' }, { overwrite: true }).subscribe();

        const req = httpMock.expectOne((r) => r.url === `${base}/config/write`);
        expect(req.request.headers.has('If-Match')).toBe(false);
        req.flush({
            type: 'pipeline',
            written: true,
            path: 'p1.toon',
            name: 'p1',
            bytes: 1,
            overwritten: true,
            findings: [],
        });
    });

    it('write() hands back the POST-save ETag, which is what makes a second save possible', () => {
        let got: ConfigWriteResult | undefined;
        svc.write('pipeline', { name: 'p1' }, { overwrite: true, ifMatch: '"sha256:old"' }).subscribe((r) => (got = r));

        httpMock
            .expectOne((r) => r.url === `${base}/config/write`)
            .flush(
                {
                    type: 'pipeline',
                    written: true,
                    path: 'p1.toon',
                    name: 'p1',
                    bytes: 1,
                    overwritten: true,
                    findings: [],
                },
                { headers: { ETag: '"sha256:new"' } },
            );

        expect(got?.etag).toBe('"sha256:new"');
        expect(got?.written).toBe(true);
    });
});
