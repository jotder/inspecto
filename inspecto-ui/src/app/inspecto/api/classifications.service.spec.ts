import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ClassificationsService } from './classifications.service';
import { SpacesService } from './spaces.service';
import { environment } from '../../../environments/environment';

const url = environment.apiBaseUrl + '/v1/classifications';

describe('ClassificationsService', () => {
    let svc: ClassificationsService;
    let httpMock: HttpTestingController;
    const space = signal<string | null>('a');

    beforeEach(() => {
        space.set('a');
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                { provide: SpacesService, useValue: { currentSpaceId: space } },
            ],
        });
        svc = TestBed.inject(ClassificationsService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('reads the vocabulary once per Space, however many pickers ask', () => {
        svc.ensureLoaded();
        svc.ensureLoaded();
        expect(svc.status()).toBe('loading');
        httpMock.expectOne(url).flush({ items: [{ class: 'MSISDN', masked: true, source: 'sensitive' }] });
        expect(svc.status()).toBe('ready');
        expect(svc.items().map((c) => c.class)).toEqual(['MSISDN']);
        svc.ensureLoaded();
        httpMock.expectNone(url);

        space.set('b');
        svc.ensureLoaded();
        expect(svc.items()).toEqual([]);
        httpMock.expectOne(url).flush({ items: [] });
        expect(svc.status()).toBe('ready');
    });

    it('a failed read is reported, not retried by every picker in the Space', () => {
        svc.ensureLoaded();
        httpMock.expectOne(url).flush({ error: { message: 'no' } }, { status: 404, statusText: 'Not Found' });
        expect(svc.status()).toBe('error');
        svc.ensureLoaded();
        httpMock.expectNone(url);
    });
});
