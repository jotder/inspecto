import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionService } from '@inspecto/core/auth/session.service';
import {
    LA_AI_ASSIST,
    LA_CASES,
    LA_CATALOG,
    LA_DASHBOARD_HEADER,
    LA_DATASETS,
    LA_FEATURES,
    LA_PIPELINE_GRAPH,
    LA_TAGS,
    LA_TRANSFER,
    LA_WIDGETS,
} from '@inspecto/link-analysis';
import { LA_APP_TOKEN_PROVISION, NoDashboardHeaderComponent, provideLaAppHostServices } from './la-host.providers';

/** Every token in `la-host.ts`; an eleventh token must be added here AND answered by la-app. */
const TEN_TOKENS = {
    LA_DATASETS,
    LA_WIDGETS,
    LA_CATALOG,
    LA_PIPELINE_GRAPH,
    LA_DASHBOARD_HEADER,
    LA_TAGS,
    LA_TRANSFER,
    LA_AI_ASSIST,
    LA_CASES,
    LA_FEATURES,
};

describe('la-app host provider set', () => {
    let session: {
        opsEnabled: ReturnType<typeof signal<boolean>>;
        exchangeEnabled: ReturnType<typeof signal<boolean>>;
        geoLinkEnabled: ReturnType<typeof signal<boolean>>;
    };

    beforeEach(() => {
        session = { opsEnabled: signal(false), exchangeEnabled: signal(false), geoLinkEnabled: signal(true) };
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                { provide: SessionService, useValue: session },
                { provide: MatDialog, useValue: { open: vi.fn() } },
                ...provideLaAppHostServices(),
            ],
        });
    });

    afterEach(() => vi.restoreAllMocks());

    it('lists all ten tokens, each exactly once, each marked real or stub', () => {
        expect(LA_APP_TOKEN_PROVISION.map((p) => p.token).sort()).toEqual(Object.keys(TEN_TOKENS).sort());
        for (const p of LA_APP_TOKEN_PROVISION) expect(['real', 'stub']).toContain(p.kind);
    });

    it('provides every token: injecting any of the ten never throws (nine throw by design when unprovided)', () => {
        for (const [name, token] of Object.entries(TEN_TOKENS)) {
            expect(() => TestBed.inject(token as never), name).not.toThrow();
        }
    });

    it('answers a stub token with available:false and reports it on the console, never as an error', () => {
        const info = vi.spyOn(console, 'info').mockImplementation(() => undefined);
        const err = vi.spyOn(console, 'error').mockImplementation(() => undefined);
        expect(TestBed.inject(LA_WIDGETS).available).toBe(false);
        expect(TestBed.inject(LA_CATALOG).available).toBe(false);
        expect(TestBed.inject(LA_PIPELINE_GRAPH).available).toBe(false);
        expect(TestBed.inject(LA_DASHBOARD_HEADER)).toBe(NoDashboardHeaderComponent);
        // Once per token per page load: the module-level set may already hold names from an earlier test in this file.
        expect(info.mock.calls.length).toBeLessThanOrEqual(4);
        for (const call of info.mock.calls) expect(String(call[0])).toMatch(/not available in la-app/);
        expect(err).not.toHaveBeenCalled();
        expect(LA_APP_TOKEN_PROVISION.filter((p) => p.kind === 'stub').map((p) => p.token)).toEqual([
            'LA_WIDGETS',
            'LA_CATALOG',
            'LA_PIPELINE_GRAPH',
            'LA_DASHBOARD_HEADER',
        ]);
    });

    it('LA_FEATURES is the live SessionService flags, not a copy', () => {
        const f = TestBed.inject(LA_FEATURES);
        expect([f.ops(), f.exchange(), f.geoLink()]).toEqual([false, false, true]);
        session.opsEnabled.set(true);
        expect(f.ops()).toBe(true);
    });

    it('LA_CASES.available follows the ops flag', () => {
        const cases = TestBed.inject(LA_CASES);
        expect(cases.available()).toBe(false);
        session.opsEnabled.set(true);
        expect(cases.available()).toBe(true);
    });

    it('LA_DATASETS lists the registry `dataset` Components as LaDatasets (physicalRef when no sourceName)', () => {
        const http = TestBed.inject(HttpTestingController);
        let got: { id: string; sourceName: string }[] = [];
        TestBed.inject(LA_DATASETS)
            .list()
            .subscribe((rows) => (got = rows));
        http.expectOne((r) => r.url.endsWith('/components/dataset')).flush([
            { name: 'calls', content: { sourceName: 'calls_store' } },
            { name: 'landed', content: { physicalRef: 'landed_store' } },
            { name: 'shared', content: { physicalRef: 'shared/other/ds' } },
        ]);
        expect(got.map((d) => [d.id, d.sourceName])).toEqual([
            ['calls', 'calls_store'],
            ['landed', 'landed_store'],
            ['shared', ''],
        ]);
    });

    it('LA_TAGS opens the core Tag assignment dialog', () => {
        const dialog = TestBed.inject(MatDialog) as unknown as { open: ReturnType<typeof vi.fn> };
        const target = { targetKind: 'link-analysis-view', targetId: 'v1', label: 'View' };
        TestBed.inject(LA_TAGS).open(target);
        expect(dialog.open).toHaveBeenCalledWith(expect.anything(), { data: target });
        expect(TestBed.inject(LA_TRANSFER).menu).toBeTruthy();
        expect(TestBed.inject(LA_AI_ASSIST).explain).toBeTruthy();
    });
});
