import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable } from 'rxjs';
import { SessionService, SpacesService } from '@inspecto/core/api';
import { InvService, MaskingNote, NeighborsRequest, ProjectionRequest, ProjectionResult } from './inv.service';
import { CONDITIONAL_CACHE_BUDGET } from './conditional-read-cache';
import { environment } from 'environments/environment';

const base = environment.apiBaseUrl + '/v1';
const PROJ = `${base}/inv/projection`;
const NEI = `${base}/inv/projection/neighbors`;
const REQ = { dataset: 'calls', sourceCol: 'A', targetCol: 'B' } as unknown as ProjectionRequest;
const NREQ = { ...REQ, value: 'x' } as unknown as NeighborsRequest;
const NOTE = { maskingMode: 'mask', masked: true } as unknown as MaskingNote;
const NOT_MODIFIED = { status: 304, statusText: 'Not Modified' };
const body = (n = 1, masking?: MaskingNote): ProjectionResult =>
    ({
        edges: [{ source: 'a', target: 'b', count: n }],
        truncated: false,
        ...(masking ? { masking } : {}),
    }) as unknown as ProjectionResult;
const tag = (t: string) => ({ headers: { ETag: t } });

describe('InvService - conditional projection reads (PERF-P10a-2)', () => {
    let svc: InvService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [InvService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(InvService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    /** One read, answered by `answer`; returns the request that was sent and what the caller got. */
    function read(
        call: () => Observable<ProjectionResult>,
        answer: (r: TestRequest) => void,
        url = PROJ,
    ): { r: TestRequest; got?: ProjectionResult; err?: unknown } {
        const out: { r: TestRequest; got?: ProjectionResult; err?: unknown } = { r: undefined as never };
        call().subscribe({ next: (v) => (out.got = v), error: (e) => (out.err = e) });
        out.r = http.expectOne(url);
        answer(out.r);
        return out;
    }
    const project = () => svc.project(REQ);
    const hasHeader = (r: TestRequest) => r.request.headers.has('If-None-Match');
    const seed = (etag = '"v1"', b = body()) => read(project, (r) => r.flush(b, tag(etag))).got!;
    /** A read that must carry no conditional header and is answered 200 with a tag. */
    const unconditional = (etag = '"v1"') => {
        const x = read(project, (r) => r.flush(body(), tag(etag)));
        expect(hasHeader(x.r)).toBe(false);
    };

    it('(a) stores the ETag; the identical call sends If-None-Match with EXACTLY that tag', () => {
        unconditional('"v1"');
        const second = read(project, (r) => r.flush(null, NOT_MODIFIED));
        expect(second.r.request.headers.get('If-None-Match')).toBe('"v1"');
    });

    it('(a) a weak W/"x" tag is echoed verbatim', () => {
        seed('W/"x"');
        const second = read(project, (r) => r.flush(null, NOT_MODIFIED));
        expect(second.r.request.headers.get('If-None-Match')).toBe('W/"x"');
    });

    it('(a) the neighbours read revalidates the same way', () => {
        const first = read(
            () => svc.neighbors(NREQ),
            (r) => r.flush(body(), tag('"n1"')),
            NEI,
        );
        const second = read(
            () => svc.neighbors(NREQ),
            (r) => r.flush(null, NOT_MODIFIED),
            NEI,
        );
        expect(second.r.request.headers.get('If-None-Match')).toBe('"n1"');
        expect(second.got).toBe(first.got);
    });

    it('(b) a 304 returns the SAME stored object and restores its masking note', () => {
        const stored = seed('"v1"', body(1, NOTE));
        expect(svc.masking()).toBe(NOTE);
        svc.masking.set(null); // something else cleared the badge
        const second = read(project, (r) => r.flush(null, NOT_MODIFIED));
        expect(second.got).toBe(stored);
        expect(svc.masking()).toBe(NOTE);
        expect(second.err).toBeUndefined();
    });

    it('(c) a 200 with a new ETag replaces the entry', () => {
        seed('"v1"');
        const fresh = body(7);
        const second = read(project, (r) => r.flush(fresh, tag('"v2"')));
        expect(second.got).toBe(fresh);
        const third = read(project, (r) => r.flush(null, NOT_MODIFIED));
        expect(third.r.request.headers.get('If-None-Match')).toBe('"v2"');
        expect(third.got).toBe(fresh);
    });

    it('(d) a 404 on revalidation propagates, drops the entry, and the next call sends NO header', () => {
        seed();
        const second = read(project, (r) => r.flush({ error: 'gone' }, { status: 404, statusText: 'Not Found' }));
        expect(second.err).toBeInstanceOf(HttpErrorResponse);
        expect((second.err as HttpErrorResponse).status).toBe(404);
        expect(second.got).toBeUndefined();
        unconditional('"v3"');
    });

    it.each([401, 403, 422, 429])('(d) a %i on revalidation is never masked by the old body', (status) => {
        seed();
        const second = read(project, (r) => r.flush('no', { status, statusText: 'x' }));
        expect((second.err as HttpErrorResponse).status).toBe(status);
        expect(svc.reads.size).toBe(0);
    });

    it('(e) a user change: the next identical call sends NO header', () => {
        seed();
        TestBed.inject(SessionService).actor.set('someone-else');
        unconditional();
    });

    it('(e) a Space change: the next identical call sends NO header', () => {
        seed();
        TestBed.inject(SpacesService).currentSpaceId.set('other-space');
        unconditional();
    });

    it('(e) a masking-mode change (scope) and an Entity List write (clear): NO header', () => {
        seed();
        svc.reads.setScope('mask');
        unconditional();
        svc.reads.clear();
        unconditional();
    });

    it('(e) a different request body is a different key: no header', () => {
        seed();
        const other = read(
            () => svc.project({ ...REQ, limit: 5 } as ProjectionRequest),
            (r) => r.flush(body(), tag('"z"')),
        );
        expect(hasHeader(other.r)).toBe(false);
    });

    it('(f) Refresh (clear, then read) sends no header and replaces the entry', () => {
        seed('"v1"');
        svc.reads.clear();
        const fresh = body(9);
        const hard = read(project, (r) => r.flush(fresh, tag('"v9"')));
        expect(hasHeader(hard.r)).toBe(false);
        const next = read(project, (r) => r.flush(null, NOT_MODIFIED));
        expect(next.r.request.headers.get('If-None-Match')).toBe('"v9"');
        expect(next.got).toBe(fresh);
    });

    it('(g) a response without an ETag stores nothing: the next call is not conditional', () => {
        read(project, (r) => r.flush(body()));
        expect(svc.reads.size).toBe(0);
        const again = read(project, (r) => r.flush(body()));
        expect(hasHeader(again.r)).toBe(false);
    });

    it('(g) a 200 without a tag on a revalidation forgets the older answer', () => {
        seed();
        read(project, (r) => r.flush(body(2)));
        const third = read(project, (r) => r.flush(body(2)));
        expect(hasHeader(third.r)).toBe(false);
    });

    it('(h) an over-size body is not retained: no conditional is ever sent for it', () => {
        const huge = {
            ...body(),
            pad: 'x'.repeat(CONDITIONAL_CACHE_BUDGET + 1),
        } as unknown as ProjectionResult;
        read(project, (r) => r.flush(huge, tag('"big"')));
        expect(svc.reads.size).toBe(0);
        const again = read(project, (r) => r.flush(huge, tag('"big"')));
        expect(hasHeader(again.r)).toBe(false);
    });

    it('(h) the response Content-Length is the size estimate: over budget is not retained, within budget is counted as sent', () => {
        read(project, (r) =>
            r.flush(body(), { headers: { ETag: '"a"', 'Content-Length': String(CONDITIONAL_CACHE_BUDGET + 1) } }),
        );
        expect(svc.reads.size).toBe(0);
        const again = read(project, (r) => r.flush(body(), tag('"a"')));
        expect(hasHeader(again.r)).toBe(false);
        read(project, (r) => r.flush(body(2), { headers: { ETag: '"b"', 'Content-Length': '1234' } }));
        expect(svc.reads.bytes).toBe(1234);
    });

    it('a 304 with no stored entry is retried ONCE without the header', () => {
        let got: ProjectionResult | undefined;
        project().subscribe((r) => (got = r));
        http.expectOne(PROJ).flush(null, NOT_MODIFIED); // nothing was held
        const retry = http.expectOne(PROJ);
        expect(hasHeader(retry)).toBe(false);
        const b = body();
        retry.flush(b, tag('"r"'));
        expect(got).toBe(b);
    });

    it('(j) neighbour list and /multi never carry a conditional header and store nothing', () => {
        for (let i = 0; i < 2; i++) {
            svc.neighbourList({ ...NREQ } as never).subscribe();
            const r = http.expectOne(NEI);
            expect(hasHeader(r)).toBe(false);
            r.flush({ neighbours: [], total: 0 }, tag('"l"'));
        }
        for (let i = 0; i < 2; i++) {
            svc.projectMulti({ nodes: [], edges: [] } as never).subscribe();
            const r = http.expectOne(`${PROJ}/multi`);
            expect(hasHeader(r)).toBe(false);
            r.flush({ nodes: [], edges: [], mappings: [], truncated: false }, tag('"m"'));
        }
        expect(svc.reads.size).toBe(0);
    });
});
