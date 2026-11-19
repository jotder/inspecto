import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiUrl } from '@inspecto/core/api';
import { summary, transfersDraft } from './add-data.testing';
import { ImportRunFailed, OnboardService } from './onboard.service';

describe('OnboardService (the Add data routes, LA-ONBOARD-1 OB-2)', () => {
    let svc: OnboardService;
    let http: HttpTestingController;
    const file = new File(['a,b\n1,2\n'], 'transfers.csv', { type: 'text/csv' });

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        svc = TestBed.inject(OnboardService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => {
        vi.useRealTimers();
        http.verify();
    });

    it('draft: the raw file as the body, its name and kind, and a re-draft states the format choices', () => {
        svc.draft(file, 'stream', { delimiter: ';', header: false, quote: '' }).subscribe();
        const req = http.expectOne((r) => r.url === apiUrl('/onboard/files'));
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toBe(file);
        expect(req.request.params.get('name')).toBe('transfers.csv');
        expect(req.request.params.get('kind')).toBe('stream');
        expect(req.request.params.get('delimiter')).toBe(';');
        expect(req.request.params.get('header')).toBe('false');
        expect(req.request.params.get('quote')).toBe(''); // "no quoting" is a stated choice, never dropped
        req.flush(transfersDraft());
    });

    it('commit 200 answers the run summary', () => {
        let got: unknown;
        svc.commit('d1', { name: 'transfers', columns: [] }).subscribe((s) => (got = s));
        const req = http.expectOne(apiUrl('/onboard/files/d1/commit'));
        expect(req.request.body).toEqual({ name: 'transfers', columns: [] });
        req.flush(summary());
        expect(got).toEqual(summary());
    });

    it('commit 202 is followed at /onboard/runs/{runId} until it ends; the caller is told it continues on the server', () => {
        vi.useFakeTimers();
        let got: unknown;
        const onFollow = vi.fn();
        svc.commit('d1', { name: 'big', columns: [] }, onFollow).subscribe((s) => (got = s));
        http.expectOne(apiUrl('/onboard/files/d1/commit')).flush(
            { runId: 'r9', dataset: 'big', action: 'commit', state: 'running' },
            { status: 202, statusText: 'Accepted' },
        );
        expect(onFollow).toHaveBeenCalledWith('r9');
        vi.advanceTimersByTime(1500);
        http.expectOne(apiUrl('/onboard/runs/r9')).flush({
            runId: 'r9',
            dataset: 'big',
            action: 'commit',
            state: 'running',
        });
        expect(got).toBeUndefined();
        vi.advanceTimersByTime(1500);
        http.expectOne(apiUrl('/onboard/runs/r9')).flush({
            runId: 'r9',
            dataset: 'big',
            action: 'commit',
            state: 'succeeded',
            summary: summary({ dataset: 'big' }),
        });
        expect(got).toEqual(summary({ dataset: 'big' }));
        vi.advanceTimersByTime(3000);
        http.expectNone(apiUrl('/onboard/runs/r9')); // stopped following
    });

    it('a followed run that failed errors with the server reason', () => {
        vi.useFakeTimers();
        let err: unknown;
        svc.follow('r2', 10).subscribe({ error: (e) => (err = e) });
        vi.advanceTimersByTime(10);
        http.expectOne(apiUrl('/onboard/runs/r2')).flush({
            runId: 'r2',
            dataset: 'x',
            action: 'commit',
            state: 'failed',
            message: 'nothing was imported',
        });
        expect(err).toBeInstanceOf(ImportRunFailed);
        expect((err as Error).message).toBe('nothing was imported');
    });

    it('Add a file and Run now', () => {
        svc.addFile('transfers', file, 'append').subscribe();
        const add = http.expectOne((r) => r.url === apiUrl('/datasets/transfers/files'));
        expect(add.request.body).toBe(file);
        expect(add.request.params.get('mode')).toBe('append');
        expect(add.request.params.get('name')).toBe('transfers.csv');
        add.flush(summary());
        svc.runNow('transfers').subscribe();
        const run = http.expectOne(apiUrl('/datasets/transfers/run'));
        expect(run.request.method).toBe('POST');
        run.flush(summary());
    });
});
