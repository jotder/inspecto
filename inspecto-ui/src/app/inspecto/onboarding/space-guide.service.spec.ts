import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { Space, SpacesService } from '../api/spaces.service';
import { NO_SPACE, SpaceGuideState } from './space-guide';
import { SpaceGuideService } from './space-guide.service';

const ALPHA: Space = { id: 'alpha', displayName: 'Alpha', description: '', createdAt: '' };

function create(multiSpace: boolean, current: Space | null) {
    const spaces = {
        refresh: () => of(current ? [current] : []),
        multiSpace: signal(multiSpace),
        currentSpace: signal(current),
    } as unknown as SpacesService;
    TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), { provide: SpacesService, useValue: spaces }],
    });
    return { service: TestBed.inject(SpaceGuideService), http: TestBed.inject(HttpTestingController) };
}

describe('SpaceGuideService', () => {
    it('a multi-Space server with no Space reads nothing else and answers NO_SPACE', () => {
        const { service, http } = create(true, null);
        let got: SpaceGuideState | undefined;
        service.load().subscribe((s) => (got = s));
        http.expectNone(() => true);
        expect(got).toEqual(NO_SPACE);
    });

    it('reads Datasets, views and Entity Lists of the current Space', () => {
        const { service, http } = create(true, ALPHA);
        let got: SpaceGuideState | undefined;
        service.load().subscribe((s) => (got = s));
        http.expectOne((r) => r.url.endsWith('/components/dataset')).flush([
            { type: 'dataset', name: 'a', ref: 'a', content: { columns: [{ name: 'X' }] } },
            { type: 'dataset', name: 'b', ref: 'b', content: { columns: [{ name: 'P', classification: 'ACCOUNT' }] } },
        ]);
        http.expectOne((r) => r.url.endsWith('/components/link-analysis-view')).flush([]);
        http.expectOne((r) => r.url.endsWith('/entity-lists')).flush({
            lists: [{ id: 'l1' }],
            headSeq: 1,
            headHash: null,
        });
        expect(got).toEqual({ hasSpace: true, datasets: 2, columnsDescribed: true, views: 0, lists: 1 });
        http.verify();
    });

    it('each read degrades on its own: a failed one counts as "not yet"', () => {
        const { service, http } = create(false, null); // single-Space server: the one Space is in force
        let got: SpaceGuideState | undefined;
        service.load().subscribe((s) => (got = s));
        http.expectOne((r) => r.url.endsWith('/components/dataset')).flush([
            { type: 'dataset', name: 'a', ref: 'a', content: {} },
        ]);
        http.expectOne((r) => r.url.endsWith('/components/link-analysis-view')).flush('boom', {
            status: 500,
            statusText: 'Server Error',
        });
        http.expectOne((r) => r.url.endsWith('/entity-lists')).flush('absent', {
            status: 503,
            statusText: 'Unavailable',
        });
        expect(got).toEqual({ hasSpace: true, datasets: 1, columnsDescribed: false, views: 0, lists: 0 });
    });
});
