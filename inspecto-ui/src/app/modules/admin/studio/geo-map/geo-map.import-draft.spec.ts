import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { GeoSource } from 'app/inspecto/geo';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ImportDraft, TransferMenuComponent } from 'app/inspecto/transfer';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { environment } from '../../../../../environments/environment';
import { GeoMapComponent } from './geo-map.component';
import { GeoSourcesService, ProjectedGeo } from './geo-projection';

const base = environment.apiBaseUrl + '/v1';
const QUERY = { projection: { datasetId: 'towers-ds', latCol: 'lat', lonCol: 'lon', kindCol: 'type' } };
const INCOMING = { name: 'Dhaka towers', sourceId: 'dataset', query: QUERY, display: 'heatmap' };
const STORED = { name: 'Dhaka towers', sourceId: 'dataset', query: QUERY, display: 'markers' };
const GEO: ProjectedGeo = {
    points: [{ id: 'pt:0', lat: 23.81, lon: 90.41, kind: 'tower', label: 'T1' }],
    routes: [],
    truncated: false,
    skipped: 0,
};

function draft(targetExists: boolean): ImportDraft {
    return {
        kind: 'geo-map-view',
        id: 'dhaka-towers',
        content: INCOMING,
        sourceSpace: 'staging',
        targetExists,
        // The preview judges no geo-map-view references, so its list is always [] for this kind.
        integrity: [],
        prerequisites: [],
    };
}

/**
 * Import as draft in the Geo map view editor (operator decisions 2026-09-25): adopting a draft is UNSAVED —
 * zero writes until Save — and Save is exactly the pane's own `/components` request (D2), with `If-Match`
 * against the stored copy when the draft landed on an existing id (D6). Real services over
 * HttpTestingController; only the geo source and the column probe are faked (MapLibre cannot run in jsdom).
 */
function create() {
    const queried: unknown[] = [];
    const fakeSource: GeoSource = {
        id: 'dataset',
        label: 'Locations (from a Dataset)',
        query: (q) => {
            queried.push(q);
            return Promise.resolve(GEO);
        },
    };
    TestBed.configureTestingModule({
        imports: [GeoMapComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            provideHttpClient(withXhr()),
            provideHttpClientTesting(),
            { provide: GeoSourcesService, useValue: { sources: [fakeSource] } },
            { provide: DatasetRowsService, useValue: { columns: () => Promise.resolve([]) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            {
                provide: ToastrService,
                useValue: { warning: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn() },
            },
            {
                provide: ActivatedRoute,
                useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
            },
        ],
    });
    const fixture = TestBed.createComponent(GeoMapComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    return { fixture, c: fixture.componentInstance, http, queried };
}

/** Answer every pending read: the stored view where asked, the settings object, an empty list elsewhere. */
function flushReads(http: HttpTestingController): void {
    for (const req of http.match((r) => r.method === 'GET')) {
        if (req.request.url === `${base}/components/geo-map-view/dhaka-towers`)
            req.flush({
                type: 'geo-map-view',
                name: 'dhaka-towers',
                ref: 'geo-map-view/dhaka-towers',
                content: STORED,
                contentHash: 'abc123',
            });
        else if (req.request.url.includes('settings')) req.flush({ tileServerUrl: null });
        else req.flush([]);
    }
}

const writes = (http: HttpTestingController) => http.match((r) => r.method !== 'GET');

describe('GeoMapComponent — Import as draft', () => {
    it('offers "Import as draft…" in its editor menu (D8)', () => {
        const { fixture, http } = create();
        flushReads(http);
        const menu = fixture.debugElement.query(By.directive(TransferMenuComponent))
            .componentInstance as TransferMenuComponent;
        expect(menu.importDraft()).toBe(true);
    });

    it('adopts a NEW-id draft unsaved (refs "not checked"), then Save is one POST through the pane', async () => {
        const { fixture, c, http, queried } = create();
        flushReads(http);
        const adopted = c.onDraftImported(draft(false));
        flushReads(http);
        fixture.detectChanges();

        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Imported draft from staging — not saved');
        // An always-empty list must not read as clean: this kind's references are not judged by the preview.
        expect(text).toContain('References not checked');
        await expectNoA11yViolations(fixture.nativeElement);
        await adopted;

        expect(c.displayMode()).toBe('heatmap');
        expect(queried).toEqual([expect.objectContaining(QUERY)]);
        expect(writes(http)).toEqual([]); // adopting wrote nothing

        const saved = c.saveDraft();
        const [post, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(post.request.method).toBe('POST');
        expect(post.request.url).toBe(`${base}/components/geo-map-view`);
        expect(post.request.headers.has('If-Match')).toBe(false);
        expect(post.request.body).toMatchObject({ id: 'dhaka-towers', name: 'Dhaka towers', display: 'heatmap' });
        http.expectNone(`${base}/bundle/import`);
        http.expectNone(`${base}/bundle/preview`);
        post.flush({});
        await saved;
        expect(c.importDraft()).toBeNull();
        expect(c.views().map((v) => v.id)).toContain('dhaka-towers');
    });

    it('opens an EXISTING id with incoming content as unsaved edits + diff; Save is a PUT with If-Match (D6)', async () => {
        const { fixture, c, http } = create();
        flushReads(http);
        const adopted = c.onDraftImported(draft(true));
        flushReads(http);
        fixture.detectChanges();
        expect(c.draftStored()).toEqual(STORED);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Changes against the stored geo-map-view');
        await adopted;
        expect(writes(http)).toEqual([]);

        // An edit AFTER adoption is what Save writes — not the content the bundle carried.
        c.displayMode.set('markers');
        const saved = c.saveDraft();
        const [put, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(put.request.method).toBe('PUT');
        expect(put.request.url).toBe(`${base}/components/geo-map-view/dhaka-towers`);
        expect(put.request.headers.get('If-Match')).toBe('"sha256:abc123"');
        expect(put.request.body).toMatchObject({ name: 'Dhaka towers', display: 'markers' });
        // A refused Save (a concurrent edit) keeps the draft on the page.
        put.flush({}, { status: 409, statusText: 'Conflict' });
        await saved;
        expect(c.importDraft()?.id).toBe('dhaka-towers');
    });

    it('Discard drops the draft, writing nothing', async () => {
        const { c, http } = create();
        flushReads(http);
        await c.onDraftImported(draft(true));
        flushReads(http);
        await c.discardDraft();
        flushReads(http);
        expect(c.importDraft()).toBeNull();
        expect(writes(http)).toEqual([]);
    });
});
