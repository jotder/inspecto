import { beforeEach, describe, expect, it } from 'vitest';
import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { apiErrorMessage } from './api-base';
import { v1Interceptor } from './v1.interceptor';

const url = `${environment.apiBaseUrl}/v1/pipelines`;

let http: HttpClient;
let mock: HttpTestingController;

describe('v1Interceptor', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(withInterceptors([v1Interceptor])), provideHttpClientTesting()],
        });
        http = TestBed.inject(HttpClient);
        mock = TestBed.inject(HttpTestingController);
    });

    it('unwraps a v1 success envelope to its data', () => {
        let body: unknown;
        http.get(url).subscribe((b) => (body = b));
        mock.expectOne(url).flush({
            data: [{ name: 'mini_etl' }],
            metadata: { timestamp: '2026-07-07T00:00:00Z', apiVersion: 'v1' },
            diagnostics: { correlationId: 'c-1' },
        });
        expect(body).toEqual([{ name: 'mini_etl' }]);
        mock.verify();
    });

    it('passes non-envelope JSON through untouched (legacy / probes)', () => {
        let body: unknown;
        http.get(url).subscribe((b) => (body = b));
        mock.expectOne(url).flush({ status: 'UP' });
        expect(body).toEqual({ status: 'UP' });
        mock.verify();
    });

    it('passes text bodies through untouched (Prometheus /metrics)', () => {
        let body: unknown;
        http.get(url, { responseType: 'text' }).subscribe((b) => (body = b));
        mock.expectOne(url).flush('etl_rows_total 42');
        expect(body).toBe('etl_rows_total 42');
        mock.verify();
    });

    it('does not unwrap a JSON body that merely has a data key', () => {
        let body: unknown;
        http.get(url).subscribe((b) => (body = b));
        mock.expectOne(url).flush({ data: 'x', other: true });
        expect(body).toEqual({ data: 'x', other: true });
        mock.verify();
    });

    // The still-unexplained non-array `GET /spaces` body (docs/BACKLOG.md §6): every mechanism in
    // current source was eliminated, leaving a bundle/server version skew — which used to pass through
    // in total silence and fail later inside the feature service.
    it('reports an envelope-shaped body it could not unwrap, naming the version and the request', () => {
        const errors: string[] = [];
        const original = console.error;
        console.error = (msg: string) => errors.push(msg);
        try {
            let body: unknown;
            http.get(url).subscribe((b) => (body = b));
            mock.expectOne(url).flush({ data: [{ name: 'mini_etl' }], metadata: { apiVersion: 'v2' } });
            // Passed through unwrapped — deliberately NOT silently accepted as v1.
            expect(body).toEqual({ data: [{ name: 'mini_etl' }], metadata: { apiVersion: 'v2' } });
            expect(errors).toHaveLength(1);
            expect(errors[0]).toContain('apiVersion=v2');
            expect(errors[0]).toContain('/v1/pipelines');
        } finally {
            console.error = original;
        }
        mock.verify();
    });

    it('stays quiet for legacy JSON, a bare data key, and a real v1 envelope', () => {
        const errors: string[] = [];
        const original = console.error;
        console.error = (msg: string) => errors.push(msg);
        try {
            for (const payload of [
                { status: 'UP' },
                { data: 'x', other: true }, // no metadata object — legacy, not a skew
                { data: [], metadata: { apiVersion: 'v1' } },
            ]) {
                http.get(url).subscribe();
                mock.expectOne(url).flush(payload);
            }
            expect(errors).toEqual([]);
        } finally {
            console.error = original;
        }
        mock.verify();
    });

    it('leaves the v1 error body wrapped for apiErrorMessage', () => {
        let caught: HttpErrorResponse | undefined;
        http.get(url).subscribe({ error: (e) => (caught = e) });
        mock.expectOne(url).flush(
            { error: { errorCode: 'NOT_FOUND', message: 'no such pipeline', recoverable: true, correlationId: 'c-2' } },
            { status: 404, statusText: 'Not Found' },
        );
        expect(apiErrorMessage(caught, 'fallback')).toBe('no such pipeline');
        mock.verify();
    });
});
