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

    it('ready() called before the Space effect runs waits for the first read, and sends one GET (LA-DOMAIN-PROFILE-READY-RACE-1)', async () => {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                { provide: SpacesService, useValue: { currentSpaceId: signal('s1') } },
            ],
        });
        const svc = TestBed.inject(DomainProfileService);
        const http = TestBed.inject(HttpTestingController);
        let settled = false;
        const ready = svc.ready().then(() => (settled = true)); // a cold `?seed=` link: no effect has run yet
        await Promise.resolve();
        await Promise.resolve();
        expect(settled).toBe(false);

        TestBed.tick(); // the Space effect runs now and finds the read already started
        const req = http.expectOne(
            (r) => r.method === 'GET' && r.url.endsWith('/settings/link-analysis/domain-profile'),
        );
        expect(settled).toBe(false);
        req.flush({ active: 'generic', stated: null, profile: null, installed: [], refused: [] });
        await ready;
        expect(settled).toBe(true);
        expect(svc.state()?.active).toBe('generic');
        http.verify();
    });

    it('clears a failure when the next read starts, and when a save answers', async () => {
        const space = signal('s1');
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                { provide: SpacesService, useValue: { currentSpaceId: space } },
            ],
        });
        const svc = TestBed.inject(DomainProfileService);
        const http = TestBed.inject(HttpTestingController);
        const url = (r: { url: string }) => r.url.endsWith('/settings/link-analysis/domain-profile');
        const answer = { active: 'generic', stated: null, profile: null, installed: [], refused: [] };
        TestBed.tick();
        http.expectOne(url).flush({ error: 'boom' }, { status: 500, statusText: 'Server Error' });
        await svc.ready();
        expect(svc.failed()).toBe(true);

        space.set('s2'); // another Space: the retry starts, and the old failure no longer shows
        TestBed.tick();
        const retry = http.expectOne(url);
        expect(svc.failed()).toBe(false);
        retry.flush({ error: 'boom' }, { status: 500, statusText: 'Server Error' });
        await svc.ready();
        expect(svc.failed()).toBe(true);

        svc.save('generic').subscribe();
        http.expectOne((r) => r.method === 'PUT' && url(r)).flush(answer);
        expect(svc.failed()).toBe(false);
        expect(svc.state()?.active).toBe('generic');
        http.verify();
    });

    it("an older Space's answer that arrives last never overwrites the current Space's profile", async () => {
        const space = signal('a');
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                { provide: SpacesService, useValue: { currentSpaceId: space } },
            ],
        });
        const svc = TestBed.inject(DomainProfileService);
        const http = TestBed.inject(HttpTestingController);
        const url = (r: { url: string }) => r.url.endsWith('/settings/link-analysis/domain-profile');
        const answer = (active: string) => ({ active, stated: active, profile: null, installed: [], refused: [] });
        TestBed.tick();
        const forA = http.expectOne(url);
        space.set('b');
        TestBed.tick();
        const forB = http.expectOne(url);

        forB.flush(answer('b'));
        await svc.ready();
        forA.flush(answer('a')); // Space a's read answers late
        await Promise.resolve();
        await Promise.resolve();
        expect(svc.state()?.stated).toBe('b');
        expect(svc.failed()).toBe(false);
        http.verify();
    });
});
