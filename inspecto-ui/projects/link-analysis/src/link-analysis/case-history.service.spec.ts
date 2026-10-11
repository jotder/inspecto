import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { CaseHistoryService } from './case-history.service';

function make() {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    return { svc: TestBed.inject(CaseHistoryService), http: TestBed.inject(HttpTestingController) };
}

const flushNext = async () => {
    await Promise.resolve();
};

describe('CaseHistoryService', () => {
    it('GETs /objects/by-entity with type, id and status, reads the truncation header, and maps 422 / 429 / other errors', async () => {
        const { svc, http } = make();

        const ok = svc.byEntity('msisdn', '+256700000001', 'open');
        await flushNext();
        const req = http.expectOne((r) => r.url.endsWith('/objects/by-entity'));
        expect(req.request.params.get('type')).toBe('msisdn');
        expect(req.request.params.get('id')).toBe('+256700000001');
        expect(req.request.params.get('status')).toBe('open');
        req.flush([{ kind: 'incident', id: 'i1' }], { headers: { 'X-Inspecto-Truncated': 'true' } });
        expect(await ok).toEqual({ items: [{ kind: 'incident', id: 'i1' }], truncated: true });

        const short = svc.byEntity('msisdn', '123', 'all');
        await flushNext();
        http.expectOne((r) => r.url.endsWith('/objects/by-entity')).flush(
            { error: { message: 'id is too short' } },
            { status: 422, statusText: 'Unprocessable' },
        );
        expect((await short).error).toContain('This entity cannot be looked up');

        const limited = svc.byEntity('msisdn', '25670', 'all');
        await flushNext();
        http.expectOne((r) => r.url.endsWith('/objects/by-entity')).flush(
            { error: { errorCode: 'RATE_LIMITED', message: 'slow down' } },
            { status: 429, statusText: 'Too Many' },
        );
        expect((await limited).error).toBe('Too many lookups just now. Wait a moment and open the section again.');

        const boom = svc.byEntity('msisdn', '25670', 'all');
        await flushNext();
        http.expectOne((r) => r.url.endsWith('/objects/by-entity')).flush(null, { status: 500, statusText: 'x' });
        expect((await boom).error).toContain('Case history could not be read');
        http.verify();
    });

    it('POSTs a masked key to /inv/case-history (never /objects/by-entity) and maps resolved / unresolved / 503', async () => {
        const { svc, http } = make();

        const ok = svc.byMaskedKey('masked:00ff', 'inv-1', 'open');
        await flushNext();
        const req = http.expectOne((r) => r.url.endsWith('/inv/case-history'));
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ key: 'masked:00ff', status: 'open', investigationId: 'inv-1' });
        req.flush({ entityType: 'msisdn', resolved: true, objects: [{ kind: 'case', id: 'c1' }], total: 3, truncated: true });
        expect(await ok).toEqual({ items: [{ kind: 'case', id: 'c1' }], truncated: true, total: 3 });

        const gone = svc.byMaskedKey('masked:00ff', null, 'all');
        await flushNext();
        const req2 = http.expectOne((r) => r.url.endsWith('/inv/case-history'));
        expect(req2.request.body).toEqual({ key: 'masked:00ff', status: 'all' });
        req2.flush({ entityType: 'msisdn', resolved: false, objects: [], total: 0, truncated: false });
        expect((await gone).error).toContain('no longer knows this masked number');

        const absent = svc.byMaskedKey('masked:00ff', null, 'all');
        await flushNext();
        http.expectOne((r) => r.url.endsWith('/inv/case-history')).flush(
            { error: { message: 'not installed' } },
            { status: 503, statusText: 'Unavailable' },
        );
        expect((await absent).error).toContain('Case management');
        http.expectNone((r) => r.url.endsWith('/objects/by-entity'));
        http.verify();
    });

    it('reads truncated / message / total from the v1 envelope metadata when the server sends it', async () => {
        const { svc, http } = make();
        const ok = svc.byEntity('msisdn', '+256700000001', 'all');
        await flushNext();
        const req = http.expectOne((r) => r.url.endsWith('/objects/by-entity'));
        req.flush({
            data: [{ kind: 'case', id: 'c1' }],
            metadata: {
                apiVersion: 'v1',
                timestamp: 't',
                pagination: { total: 1240, truncated: true, message: 'Scanned the newest 500 of 1,240 objects.' },
            },
            diagnostics: { correlationId: 'x' },
        });
        expect(await ok).toEqual({
            items: [{ kind: 'case', id: 'c1' }],
            truncated: true,
            truncatedMessage: 'Scanned the newest 500 of 1,240 objects.',
            total: 1240,
        });
        http.verify();
    });
});
