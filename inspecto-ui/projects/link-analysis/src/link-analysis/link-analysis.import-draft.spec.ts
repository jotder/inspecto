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
import { G6GraphData, GraphSource } from '@inspecto/core/graph';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { ImportDraft, TransferMenuComponent } from '@inspecto/core/transfer';
import { apiUrl } from '@inspecto/core/api/api-base';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisComponent } from './link-analysis.component';
import { provideLaHostServices } from 'app/modules/admin/studio/la-host.providers';

const base = apiUrl('');
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

const FINDING = "broken reference: link-analysis-view 'fraud-ring' → missing dataset 'ghost_ds'";

function draft(targetExists: boolean, integrity: string[] | null = []): ImportDraft {
    return {
        kind: 'link-analysis-view',
        id: 'fraud-ring',
        content: INCOMING,
        sourceSpace: 'staging',
        targetExists,
        integrity,
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
            ...provideLaHostServices(),
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

// `POST /bundle/preview` is a POST that writes nothing — the Save's re-check, answered by `recheck` below.
const writes = (http: HttpTestingController) =>
    http.match((r) => r.method !== 'GET' && !r.url.endsWith('/bundle/preview'));

/** Answer the Save's reference re-check (D3, operator 2026-10-06), then let the awaited Save reach the wire. */
async function recheck(http: HttpTestingController, answer: string[] | 'error') {
    const pre = http.expectOne(`${base}/bundle/preview`);
    const body = pre.request.body;
    if (answer === 'error') pre.flush({}, { status: 500, statusText: 'Boom' });
    else pre.flush({ items: [], integrity: answer });
    await new Promise((r) => setTimeout(r));
    return body;
}

describe('LinkAnalysisComponent — Import as draft', () => {
    it('offers "Import as draft…" in its editor menu (D8)', () => {
        const { fixture, http } = create();
        flushReads(http);
        const menu = fixture.debugElement.query(By.directive(TransferMenuComponent))
            .componentInstance as TransferMenuComponent;
        expect(menu.importDraft()).toBe(true);
    });

    it('adopts a NEW-id draft unsaved (its preview findings shown), then Save is one POST through the pane', async () => {
        const { fixture, c, http, queried, release } = create();
        flushReads(http);
        const adopted = c.onDraftImported(draft(false, [FINDING]));
        flushReads(http);
        fixture.detectChanges();

        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Imported draft from staging — not saved');
        // The preview now judges this kind's Dataset reference: its findings reach the banner as-is.
        expect(text).toContain('Broken references this draft would introduce');
        expect(text).toContain(FINDING);
        expect(text).not.toContain('References not checked');
        await expectNoA11yViolations(fixture.nativeElement);
        release();
        await adopted;

        expect(c.layoutId()).toBe('force');
        expect(c.profileId()).toBe('telecom');
        expect(queried).toEqual([expect.objectContaining(QUERY)]);
        expect(writes(http)).toEqual([]); // adopting wrote nothing

        const saved = c.saveDraft();
        expect(writes(http)).toEqual([]); // the re-check runs BEFORE the write
        const checked = await recheck(http, [FINDING]);
        // The re-check judges exactly what Save is about to write, as a one-item envelope.
        expect(checked.items).toEqual([
            { kind: 'link-analysis-view', id: 'fraud-ring', content: expect.objectContaining({ layout: 'force' }) },
        ]);
        expect(c.importDraft()?.integrity).toEqual([FINDING]);
        const [post, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(post.request.method).toBe('POST');
        expect(post.request.url).toBe(`${base}/components/link-analysis-view`);
        expect(post.request.headers.has('If-Match')).toBe(false);
        expect(post.request.body).toMatchObject({ id: 'fraud-ring', name: 'Fraud ring', layout: 'force' });
        http.expectNone(`${base}/bundle/import`);
        post.flush({});
        await saved;
        expect(c.importDraft()).toBeNull();
        // Advisory (D3): saved anyway, and the toast names the finding instead of a plain success.
        const toastr = TestBed.inject(ToastrService);
        expect(toastr.warning).toHaveBeenCalledWith(expect.stringContaining(FINDING));
        expect(toastr.success).not.toHaveBeenCalled();
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
        const checked = await recheck(http, 'error');
        expect(checked.items[0].content).toMatchObject({ layout: 'circular' }); // the EDITED content is judged
        // An unreadable re-check is "not checked" — never clean — and does not block the Save.
        expect(c.importDraft()?.integrity).toBeNull();
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

    it('an unreadable preview (null) still says "not checked", never clean', async () => {
        const { fixture, c, http, release } = create();
        flushReads(http);
        const adopted = c.onDraftImported(draft(false, null));
        flushReads(http);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('References not checked');
        release();
        await adopted;
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
