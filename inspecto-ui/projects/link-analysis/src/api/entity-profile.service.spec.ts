import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { EntityProfileService } from './entity-profile.service';

describe('EntityProfileService (la-entity-profile-plan section 4.5)', () => {
    it('reads the three routes, encoding the type and key and sending sections only when asked', () => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        const svc = TestBed.inject(EntityProfileService);
        const http = TestBed.inject(HttpTestingController);

        svc.entityTypes().subscribe();
        http.expectOne((r) => r.method === 'GET' && r.url.endsWith('/inv/entity-types')).flush({ items: [] });

        svc.entityTypeProfile('msisdn').subscribe();
        const all = http.expectOne((r) => r.url.endsWith('/inv/entity-types/msisdn/profile'));
        expect(all.request.params.has('sections')).toBe(false);
        all.flush({});

        svc.entityTypeProfile('msisdn', ['volume', 'time']).subscribe();
        const some = http.expectOne((r) => r.url.endsWith('/inv/entity-types/msisdn/profile'));
        expect(some.request.params.get('sections')).toBe('volume,time');
        some.flush({});

        svc.entityProfile('msisdn', 'masked:f01a/9b').subscribe();
        http.expectOne(
            (r) => r.method === 'GET' && r.url.endsWith('/inv/entities/msisdn/masked%3Af01a%2F9b/profile'),
        ).flush({});
        // The raw value travels in the body of the resolve POST only, never in its URL.
        svc.resolveEntity('msisdn', '+249 911').subscribe();
        const res = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/inv/entities/msisdn/resolve'));
        expect(res.request.body).toEqual({ key: '+249 911' });
        expect(res.request.urlWithParams).not.toContain('249');
        res.flush({ type: 'msisdn', key: 'masked:ab', masked: true });
        http.verify();
    });
});
