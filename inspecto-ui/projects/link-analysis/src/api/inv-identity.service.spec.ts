import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { InvService } from './inv.service';
import { environment } from 'environments/environment';

const base = environment.apiBaseUrl + '/v1';

/** LA-17 slice 2 — pinned to `EntityIdentityRoutes` (entity-model design §8.2). */
describe('InvService identity resolution (LA-17 slice 2)', () => {
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

    it('lists groups with `at` only when given', () => {
        svc.listIdentityGroups().subscribe();
        const head = httpMock.expectOne(`${base}/inv/entity-identities`);
        expect(head.request.method).toBe('GET');
        expect(head.request.params.keys()).toEqual([]);
        head.flush({ groups: [], atSeq: 0, headSeq: 0, headHash: '' });

        svc.listIdentityGroups(0).subscribe();
        const zero = httpMock.expectOne((r) => r.url === `${base}/inv/entity-identities`);
        expect(zero.request.params.get('at')).toBe('0');
        zero.flush({});
    });

    it('sends the group key with `+` as %2B (a raw + decodes to a space server-side)', () => {
        svc.identityGroup('msisdn:+447700900123', 4).subscribe();
        const req = httpMock.expectOne((r) => r.url === `${base}/inv/entity-identities/group`);
        expect(req.request.method).toBe('GET');
        expect(req.request.urlWithParams).toBe(`${base}/inv/entity-identities/group?key=msisdn%3A%2B447700900123&at=4`);
        req.flush({});
    });

    it('asserts with the body verbatim and retracts by seq with a reason', () => {
        const body = { a: 'msisdn:+447700900123', b: 'imsi:234150000000001', reason: 'same SIM' };
        svc.assertIdentity(body).subscribe();
        const a = httpMock.expectOne(`${base}/inv/entity-identities`);
        expect(a.request.method).toBe('POST');
        expect(a.request.body).toEqual(body);
        a.flush({}, { status: 201, statusText: 'Created' });

        svc.retractIdentity(12, 'wrong SIM').subscribe();
        const r = httpMock.expectOne(`${base}/inv/entity-identities/12/retract`);
        expect(r.request.method).toBe('POST');
        expect(r.request.body).toEqual({ reason: 'wrong SIM' });
        r.flush({});
    });
});
