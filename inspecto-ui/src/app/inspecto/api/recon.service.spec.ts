import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ReconApiService } from './recon.service';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths

describe('ReconApiService — recorded state (R2-03)', () => {
    let svc: ReconApiService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [ReconApiService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(ReconApiService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('reads one recorded state and the list by their GET routes', () => {
        svc.state('orders_recon').subscribe();
        httpMock.expectOne((r) => r.method === 'GET' && r.url === `${base}/recon/orders_recon/state`).flush({});
        svc.states().subscribe();
        httpMock.expectOne((r) => r.method === 'GET' && r.url === `${base}/recon/state`).flush({ states: [] });
    });

    it('records a run of the SAVED Reconciliation by id — the server computes the Breaks', () => {
        svc.record('orders_recon').subscribe();
        const req = httpMock.expectOne((r) => r.method === 'POST' && r.url === `${base}/recon/orders_recon/record`);
        expect(req.request.body).toEqual({});
        req.flush({});
    });

    it('sends a status change by identity, dropping an absent column and note', () => {
        svc.setBreakStatus('orders_recon', { type: 'missing_left', key: 'APAC · sms' }, 'resolved').subscribe();
        const req = httpMock.expectOne(
            (r) => r.method === 'POST' && r.url === `${base}/recon/orders_recon/breaks/status`,
        );
        expect(req.request.body).toEqual({ type: 'missing_left', key: 'APAC · sms', status: 'resolved' });
        req.flush({});

        svc.setBreakStatus(
            'orders_recon',
            { type: 'value_break', key: 'EU', column: 'amount' },
            'open',
            'back',
        ).subscribe();
        const reopen = httpMock.expectOne((r) => r.url === `${base}/recon/orders_recon/breaks/status`);
        expect(reopen.request.body).toEqual({
            type: 'value_break',
            key: 'EU',
            column: 'amount',
            status: 'open',
            note: 'back',
        });
        reopen.flush({});
    });

    it('encodes the id into the path', () => {
        svc.state('a b').subscribe();
        httpMock.expectOne((r) => r.url === `${base}/recon/a%20b/state`).flush({});
    });
});
