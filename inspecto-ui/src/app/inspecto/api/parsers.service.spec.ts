import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ParsersService } from './parsers.service';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1';

describe('ParsersService.decodeProfile (GET /parsers/asn1/profile)', () => {
    let svc: ParsersService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [ParsersService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(ParsersService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('sends the ref and the Pipeline subdir — an empty subdir too (a Pipeline at the root)', () => {
        let got: Record<string, unknown> | undefined;
        svc.decodeProfile('../vendors/acme/acme.decode.toon', '').subscribe((r) => (got = r.asn1));
        const req = httpMock.expectOne((r) => r.method === 'GET' && r.url === `${base}/parsers/asn1/profile`);
        expect(req.request.params.get('profile_file')).toBe('../vendors/acme/acme.decode.toon');
        expect(req.request.params.get('subdir')).toBe('');
        req.flush({ asn1: { strictness: 'DER' } });
        expect(got).toEqual({ strictness: 'DER' });
    });

    it('omits subdir when there is no Pipeline context', () => {
        svc.decodeProfile('acme.decode.toon').subscribe();
        const req = httpMock.expectOne((r) => r.url === `${base}/parsers/asn1/profile`);
        expect(req.request.params.has('subdir')).toBe(false);
        req.flush({ asn1: {} });
    });
});
