import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { EventsService, SignalTypeInfo } from 'app/inspecto/api';
import { AttributeOptionLoader } from './schema-form.component';
import { signalTypeOptionLoader } from './entity-option-loaders';

/** SIGNAL-BOARD-1 SB-1c: the Job form's `on_signal` suggestions come from `GET /signals/types`. */
describe('signalTypeOptionLoader (GET /signals/types)', () => {
    let httpMock: HttpTestingController;

    const type = (t: string, title: string, extra: Partial<SignalTypeInfo> = {}): SignalTypeInfo => ({
        type: t,
        title,
        poster: 'engine',
        fields: [],
        since: '4.0.0',
        source: 'core',
        fieldsDeclared: true,
        ...extra,
    });

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(withXhr()), provideHttpClientTesting()] });
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    const loader = (): AttributeOptionLoader => TestBed.runInInjectionContext(() => signalTypeOptionLoader());

    it('offers every catalogued type by its bare name, sorted, labelled with its title', async () => {
        const pending = Promise.resolve(loader()({}));
        httpMock
            .expectOne((r) => r.method === 'GET' && r.url.endsWith('/v1/signals/types'))
            .flush({
                types: [
                    type('pipeline.commit', 'Consignment committed (mirror of the commit event)'),
                    type('exchange.*', 'A Signal delivered from another Space', { fieldsDeclared: false }),
                    type('acme.detected', 'acme.detected', { source: 'pack:acme.jar', fieldsDeclared: false }),
                ],
                count: 3,
            });
        expect(await pending).toEqual([
            { value: 'acme.detected', label: 'acme.detected' },
            { value: 'exchange.*', label: 'exchange.* · A Signal delivered from another Space' },
            { value: 'pipeline.commit', label: 'pipeline.commit · Consignment committed (mirror of the commit event)' },
        ]);
    });

    it('the service reads the types list and treats a body without one as empty', async () => {
        const svc = TestBed.inject(EventsService);
        const pending = firstValueFrom(svc.signalTypes());
        httpMock.expectOne((r) => r.url.endsWith('/v1/signals/types')).flush({ count: 0 });
        expect(await pending).toEqual([]);
    });

    it('rejects on a refusal, which the schema form turns into no suggestions (free text stays)', async () => {
        const pending = Promise.resolve(loader()({}));
        httpMock
            .expectOne((r) => r.url.endsWith('/v1/signals/types'))
            .flush({ error: { message: 'forbidden' } }, { status: 403, statusText: 'Forbidden' });
        await expect(pending).rejects.toBeTruthy();
    });
});
