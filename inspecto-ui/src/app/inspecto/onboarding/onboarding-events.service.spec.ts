import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { OnboardingEventsService, OnboardingMetrics } from './onboarding-events.service';

function create() {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    return { service: TestBed.inject(OnboardingEventsService), http: TestBed.inject(HttpTestingController) };
}

describe('OnboardingEventsService - the browser half of the onboarding counters (LA-ONBOARD-1 OB-13)', () => {
    it('reports an abandoned step and the guide completion (once per load), and a failure stays silent', () => {
        const { service, http } = create();
        service.stepAbandoned('add_data:columns');
        const r = http.expectOne((q) => q.url.endsWith('/onboarding/events'));
        expect(r.request.method).toBe('POST');
        expect(r.request.body).toEqual({ event: 'step_abandoned', step: 'add_data:columns' });
        r.flush({ error: { message: 'rate limit' } }, { status: 429, statusText: 'Too Many Requests' });

        service.guideCompleted();
        service.guideCompleted();
        const g = http.match((q) => q.url.endsWith('/onboarding/events'));
        expect(g.length).toBe(1);
        expect(g[0].request.body).toEqual({ event: 'guide_completed' });
        g[0].flush({ recorded: 'guide_completed' });
        http.verify();
    });

    it('reads the metrics, and answers null when the server cannot (an older server, or not allowed)', () => {
        const { service, http } = create();
        let got: OnboardingMetrics | null | undefined;
        service.metrics().subscribe((m) => (got = m));
        http.expectOne((q) => q.url.endsWith('/onboarding/metrics')).flush({ recording: true, launches: 1 });
        expect(got?.launches).toBe(1);
        service.metrics().subscribe((m) => (got = m));
        http.expectOne((q) => q.url.endsWith('/onboarding/metrics')).flush(
            {},
            { status: 403, statusText: 'Forbidden' },
        );
        expect(got).toBeNull();
        http.verify();
    });
});
