import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { NavMenusService } from './menu-api';

describe('NavMenusService', () => {
    // UIE-7: PUT replaces the whole document, so a landing left off the body is a landing cleared.
    it('carries the Space landing on every PUT, and omits it when there is none', () => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        const api = TestBed.inject(NavMenusService);
        const http = TestBed.inject(HttpTestingController);

        api.put({ space: 's', version: 1, landing: 'l1', nodes: [] }).subscribe();
        const withLanding = http.expectOne((r) => r.url.endsWith('/nav/menus'));
        expect(withLanding.request.body).toEqual({ version: 1, landing: 'l1', nodes: [] });
        withLanding.flush({});

        api.put({ space: 's', version: 1, nodes: [] }).subscribe();
        const without = http.expectOne((r) => r.url.endsWith('/nav/menus'));
        expect(without.request.body).toEqual({ version: 1, nodes: [] });
        without.flush({});
        http.verify();
    });
});
