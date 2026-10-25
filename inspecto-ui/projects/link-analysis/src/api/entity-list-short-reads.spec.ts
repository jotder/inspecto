import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { ComponentsService } from '@inspecto/core/api/components.service';
import { ExclusionCategoriesService } from '@inspecto/core/api/exclusion-categories.service';
import { environment } from 'environments/environment';
import { OnboardService } from '../add-data/onboard.service';
import { InvService } from './inv.service';

const base = environment.apiBaseUrl + '/v1';

/** N5: every write that changes `GET /entity-lists` (or the Dataset list) makes the next read ask the server again. */
describe('Entity List writes clear the short-lived reads (N5)', () => {
    let http: HttpTestingController;
    let inv: InvService;
    let components: ComponentsService;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(withXhr()), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
        inv = TestBed.inject(InvService);
        components = TestBed.inject(ComponentsService);
    });

    /** Read, prove a second read is shared, run the write, and the next read must reach the server. */
    function expectWriteClears(
        write: () => Observable<unknown>,
        writeUrl: string,
        read: 'lists' | 'datasets' = 'lists',
    ): void {
        const get = (): Observable<unknown> => (read === 'lists' ? inv.listEntityLists() : components.list('dataset'));
        const url = read === 'lists' ? `${base}/entity-lists` : `${base}/components/dataset`;
        const answer = read === 'lists' ? { lists: [] } : [];
        get().subscribe();
        http.expectOne(url).flush(answer);
        get().subscribe();
        expect(http.match(url).length).toBe(0);
        write().subscribe();
        http.expectOne((r) => r.url === writeUrl && r.method !== 'GET').flush({});
        get().subscribe();
        http.expectOne(url).flush(answer);
    }

    it('createEntityList', () =>
        expectWriteClears(() => inv.createEntityList({ name: 'x' } as never), `${base}/entity-lists`));
    it('changeEntityListMembers', () =>
        expectWriteClears(
            () => inv.changeEntityListMembers('l', { add: ['a'], reason: 'r' } as never),
            `${base}/entity-lists/l/members`,
        ));
    it('retireEntityList', () =>
        expectWriteClears(() => inv.retireEntityList('l', 'r'), `${base}/entity-lists/l/retire`));
    it('registerEntityListDataset clears the Entity List read', () =>
        expectWriteClears(() => inv.registerEntityListDataset('l'), `${base}/entity-lists/l/register-dataset`));
    it('registerEntityListDataset clears the Dataset list read too', () =>
        expectWriteClears(
            () => inv.registerEntityListDataset('l'),
            `${base}/entity-lists/l/register-dataset`,
            'datasets',
        ));
    it('ExclusionCategoriesService create', () =>
        expectWriteClears(
            () => TestBed.inject(ExclusionCategoriesService).create({ name: 'c' } as never),
            `${base}/entity-lists`,
        ));
    it('ExclusionCategoriesService setAttributes', () =>
        expectWriteClears(
            () => TestBed.inject(ExclusionCategoriesService).setAttributes('l', { reason: 'r' } as never),
            `${base}/entity-lists/l/attributes`,
        ));
    it('onboard commit clears the Dataset list read', () =>
        expectWriteClears(
            () => TestBed.inject(OnboardService).commit('d1', {} as never),
            `${base}/onboard/files/d1/commit`,
            'datasets',
        ));
});
