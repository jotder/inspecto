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
import { G6GraphData, GraphSource } from 'app/inspecto/graph';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ImportDraft, TransferMenuComponent } from 'app/inspecto/transfer';
import { environment } from '../../../../../environments/environment';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisComponent } from './link-analysis.component';

const base = environment.apiBaseUrl + '/v1';
const QUERY = { projection: { datasetId: 'links-ds', sourceCol: 'a_party', targetCol: 'b_party' } };
const INCOMING = {
    name: 'Fraud ring',
    sourceId: 'entity-projection',
    query: QUERY,
    layout: 'force',
    profile: 'telecom',
};
const STORED = { name: 'Fraud ring', sourceId: 'entity-projection', query: QUERY, layout: 'dagre' };
const GRAPH: G6GraphData = { nodes: [{ id: 'a', data: { label: 'A', kind: 'entity' } }], edges: [] };

function draft(targetExists: boolean): ImportDraft {
    return {
        kind: 'link-analysis-view',
        id: 'fraud-ring',
        content: INCOMING,
        sourceSpace: 'staging',
        targetExists,
        // The preview judges no link-analysis-view references, so its list is always [] for this kind.
        integrity: [],
        prerequisites: [],
    };
}

/**
 * Import as draft in the Link Analysis view editor (operator decisions 2026-09-25): adopting a draft is
 * UNSAVED — zero writes until Save — and Save is exactly the pane's own `/components` request (D2), with
 * `If-Match` against the stored copy when the draft landed on an existing id (D6). Real services over
 * HttpTestingController, so "no write" is a fact about the wire; only the graph source is faked (its
 * result feeds a G6 canvas jsdom cannot host).
 */
function create() {
    const queried: unknown[] = [];
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const fakeSource: GraphSource = {
        id: 'entity-projection',
        label: 'Entity/Link (from a Dataset)',
        query: async (q) => {
            queried.push(q);
            await gate; // held open so the banner can be rendered before the canvas would mount
            return GRAPH;
        },
    };
    TestBed.configureTestingModule({
        imports: [LinkAnalysisComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            provideHttpClient(withXhr()),
            provideHttpClientTesting(),
            { provide: GraphSourcesService, useValue: { sources: [fakeSource], byId: () => fakeSource } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            {
                provide: ToastrService,
                useValue: { warning: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn() },
            },
            {
                provide: ActivatedRoute,
                useValue: {
                    snapshot: { queryParamMap: convertToParamMap({}) },
                    queryParamMap: of(convertToParamMap({})),
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    return { fixture, c: fixture.componentInstance, http, queried, release };
}

/** Answer every pending read: the stored view where asked, an empty list everywhere else. */
function flushReads(http: HttpTestingController): void {
    for (const req of http.match((r) => r.method === 'GET')) {
        if (req.request.url === `${base}/components/link-analysis-view/fraud-ring`)
            req.flush({
                type: 'link-analysis-view',
                name: 'fraud-ring',
                ref: 'link-analysis-view/fraud-ring',
                content: STORED,
                contentHash: 'abc123',
            });
        else req.flush([]);
    }
}

const writes = (http: HttpTestingController) => http.match((r) => r.method !== 'GET');

describe('LinkAnalysisComponent — Import as draft', () => {
    it('offers "Import as draft…" in its editor menu (D8)', () => {
        const { fixture, http } = create();
        flushReads(http);
        const menu = fixture.debugElement.query(By.directive(TransferMenuComponent))
            .componentInstance as TransferMenuComponent;
        expect(menu.importDraft()).toBe(true);
    });

    it('adopts a NEW-id draft unsaved (refs "not checked"), then Save is one POST through the pane', async () => {
        const { fixture, c, http, queried, release } = create();
        flushReads(http);
        const adopted = c.onDraftImported(draft(false));
        flushReads(http);
        fixture.detectChanges();

        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Imported draft from staging — not saved');
        // An always-empty list must not read as clean: this kind's references are not judged by the preview.
        expect(text).toContain('References not checked');
        await expectNoA11yViolations(fixture.nativeElement);
        release();
        await adopted;

        expect(c.layoutId()).toBe('force');
        expect(c.profileId()).toBe('telecom');
        expect(queried).toEqual([expect.objectContaining(QUERY)]);
        expect(writes(http)).toEqual([]); // adopting wrote nothing

        const saved = c.saveDraft();
        const [post, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(post.request.method).toBe('POST');
        expect(post.request.url).toBe(`${base}/components/link-analysis-view`);
        expect(post.request.headers.has('If-Match')).toBe(false);
        expect(post.request.body).toMatchObject({ id: 'fraud-ring', name: 'Fraud ring', layout: 'force' });
        http.expectNone(`${base}/bundle/import`);
        http.expectNone(`${base}/bundle/preview`);
        post.flush({});
        await saved;
        expect(c.importDraft()).toBeNull();
        expect(c.views().map((v) => v.id)).toContain('fraud-ring');
    });

    it('opens an EXISTING id with incoming content as unsaved edits + diff; Save is a PUT with If-Match (D6)', async () => {
        const { fixture, c, http, release } = create();
        flushReads(http);
        const adopted = c.onDraftImported(draft(true));
        flushReads(http);
        fixture.detectChanges();
        expect(c.draftStored()).toEqual(STORED);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain(
            'Changes against the stored link-analysis-view',
        );
        release();
        await adopted;
        expect(writes(http)).toEqual([]);

        // An edit AFTER adoption is what Save writes — not the content the bundle carried.
        c.layoutId.set('circular');
        const saved = c.saveDraft();
        const [put, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(put.request.method).toBe('PUT');
        expect(put.request.url).toBe(`${base}/components/link-analysis-view/fraud-ring`);
        expect(put.request.headers.get('If-Match')).toBe('"sha256:abc123"');
        expect(put.request.body).toMatchObject({ name: 'Fraud ring', layout: 'circular' });
        // A refused Save (a concurrent edit) keeps the draft on the page.
        put.flush({}, { status: 409, statusText: 'Conflict' });
        await saved;
        expect(c.importDraft()?.id).toBe('fraud-ring');
    });

    it('Discard drops the draft, writing nothing', async () => {
        const { c, http, release } = create();
        flushReads(http);
        release();
        await c.onDraftImported(draft(true));
        flushReads(http);
        await c.discardDraft();
        flushReads(http);
        expect(c.importDraft()).toBeNull();
        expect(writes(http)).toEqual([]);
    });
});
