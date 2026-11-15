import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SpacesService } from '@inspecto/core/api';
import { DomainProfileService } from './domain-profile.service';

describe('DomainProfileService', () => {
    it('is not failed while the read is in flight, and failed only once it errors (LA-SETTINGS-PROFILE-FLASH-1)', async () => {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                { provide: SpacesService, useValue: { currentSpaceId: signal('s1') } },
            ],
        });
        const svc = TestBed.inject(DomainProfileService);
        const http = TestBed.inject(HttpTestingController);
        TestBed.tick(); // the Space effect starts the read

        const req = http.expectOne(
            (r) => r.method === 'GET' && r.url.endsWith('/settings/link-analysis/domain-profile'),
        );
        expect(svc.failed()).toBe(false);
        expect(svc.state()).toBeNull();

        req.flush({ error: 'boom' }, { status: 500, statusText: 'Server Error' });
        await svc.ready();
        expect(svc.failed()).toBe(true);
        expect(svc.state()).toBeNull();
        http.verify();
    });
});
