import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { Router, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { LensService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ImportDraft, ImportDraftHandoff } from 'app/inspecto/transfer';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { environment } from '../../../../../environments/environment';
import { DatasetEditorComponent } from './dataset-editor.component';

const base = environment.apiBaseUrl + '/v1';
const INCOMING = {
    name: 'orders_view',
    kind: 'physical',
    sourceName: 'orders',
    physicalRef: 'orders',
    columns: [{ name: 'amount', type: 'number', role: 'measure' }],
    measures: [{ id: 'revenue', label: 'Revenue', expression: 'sum(amount)' }],
    calculated: [],
};
const STORED = { ...INCOMING, measures: [] };

function draft(targetExists: boolean): ImportDraft {
    return {
        kind: 'dataset',
        id: 'orders_view',
        content: INCOMING,
        sourceSpace: 'staging',
        targetExists,
        integrity: [],
        prerequisites: [],
    };
}

/**
 * Import as draft in the Dataset editor (operator decisions 2026-09-25): adopt ⇒ unsaved, zero writes;
 * Save ⇒ exactly the pane's own request (POST new / PUT + If-Match existing). Real services over
 * HttpTestingController; the rows seam and the lens are stubbed as in the editor's own spec.
 */
function create(id?: string, before?: () => void) {
    localStorage.removeItem('inspecto.currentLens');
    TestBed.configureTestingModule({
        imports: [DatasetEditorComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            provideHttpClient(withXhr()),
            provideHttpClientTesting(),
            {
                provide: LensService,
                useValue: { canOperateRuns: () => true, canAuthorWorkbench: () => true, canOfferDatasets: () => true },
            },
            {
                provide: DatasetRowsService,
                useValue: {
                    stores: () => Promise.resolve({ names: ['orders'] }),
                    rows: () =>
                        Promise.resolve({ rows: [], columns: [{ name: 'amount', type: 'number' }], truncated: false }),
                },
            },
            { provide: ToastrService, useValue: { warning: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn() } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    TestBed.overrideProvider(MatDialog, { useValue: { open: vi.fn() } });
    before?.();
    const fixture = TestBed.createComponent(DatasetEditorComponent);
    if (id) fixture.componentRef.setInput('id', id);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, http: TestBed.inject(HttpTestingController) };
}

function flushReads(http: HttpTestingController): void {
    for (const req of http.match((r) => r.method === 'GET')) {
        if (req.request.url === `${base}/components/dataset/orders_view`)
            req.flush({
                type: 'dataset',
                name: 'orders_view',
                ref: 'dataset/orders_view',
                content: STORED,
                contentHash: 'dh',
            });
        else req.flush([]);
    }
}

/** Every mutating request. `POST /bundle/preview` is a READ (the pre-Save re-check) and is answered on its own. */
const writes = (http: HttpTestingController) =>
    http.match((r) => r.method !== 'GET' && r.url !== `${base}/bundle/preview`);

/** Answer the pre-Save re-check (D3): exactly one read-only preview; returns the envelope it judged. */
function answerRecheck(http: HttpTestingController, integrity: string[] | 'unreadable') {
    const req = http.expectOne(`${base}/bundle/preview`);
    if (integrity === 'unreadable') req.flush({}, { status: 500, statusText: 'Server Error' });
    else req.flush({ items: [], requires: [], integrity });
    return req.request.body as { items: { kind: string; id: string; content: Record<string, unknown> }[] };
}

describe('DatasetEditorComponent — Import as draft', () => {
    it('adopts a NEW-id draft unsaved with its id still editable; Save is one POST through the pane', async () => {
        const { fixture, c, http } = create();
        flushReads(http);
        c.onDraftImported(draft(false));
        await fixture.whenStable();
        flushReads(http);
        fixture.detectChanges();

        expect(c.measures().map((m) => m.id)).toEqual(['revenue']);
        expect(c.form.controls.name.enabled).toBe(true);
        expect(c.form.controls.name.value).toBe('orders_view');
        expect(writes(http)).toEqual([]);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Imported draft from staging — not saved');
        await expectNoA11yViolations(
            (fixture.nativeElement as HTMLElement).querySelector('inspecto-import-draft-banner') as HTMLElement,
        );

        c.save();
        expect(writes(http)).toEqual([]); // the re-check runs FIRST — nothing is written before it answers
        const checked = answerRecheck(http, []);
        expect(checked.items).toEqual([
            { kind: 'dataset', id: 'orders_view', content: expect.objectContaining({ sourceName: 'orders' }) },
        ]);
        const [post, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(post.request.method).toBe('POST');
        expect(post.request.url).toBe(`${base}/components/dataset`);
        expect(post.request.body).toMatchObject({ id: 'orders_view', kind: 'physical', sourceName: 'orders' });
        http.expectNone(`${base}/bundle/import`);
    });

    it('a draft routed here onto an EXISTING id replaces the stored load, and saves as a PUT with If-Match (D6)', async () => {
        // The handoff is how a draft reaches a freshly-opened editor; taking it must win over the stored load.
        const { fixture, c, http } = create('orders_view', () => {
            vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
            TestBed.inject(ImportDraftHandoff).open(
                draft(true),
                ['/catalog/datasets', 'orders_view'],
                '/catalog/datasets',
                false,
            );
        });
        // ONE stored read (the D6 baseline) — the plain edit load would be a second, racing the draft.
        const reads = http.match(`${base}/components/dataset/orders_view`);
        expect(reads.length).toBe(1);
        reads[0].flush({ type: 'dataset', name: 'orders_view', ref: '', content: STORED, contentHash: 'dh' });
        await fixture.whenStable();
        flushReads(http);
        await fixture.whenStable();
        fixture.detectChanges();

        expect(c.measures().map((m) => m.id)).toEqual(['revenue']);
        expect(c.draftStored()).toEqual(STORED);
        expect(writes(http)).toEqual([]);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Changes against the stored dataset');

        // An edit AFTER adoption is what the re-check judges — not the content the bundle carried.
        c.measures.set([]);
        c.save();
        const checked = answerRecheck(http, ["broken reference: dataset 'orders_view' -> missing query 'q'"]);
        expect(checked.items[0].content).toMatchObject({ measures: [] });
        const [put, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(put.request.method).toBe('PUT');
        expect(put.request.url).toBe(`${base}/components/dataset/orders_view`);
        expect(put.request.headers.get('If-Match')).toBe('"sha256:dh"');
        expect(TestBed.inject(ImportDraftHandoff).take('dataset', 'orders_view')).toBeNull();
        put.flush({}, { status: 409, statusText: 'Conflict' });
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain("missing query 'q'");
    });

    it('an unreadable re-check does not block the Save — it says "not checked" instead', async () => {
        const { fixture, c, http } = create();
        flushReads(http);
        c.onDraftImported(draft(false));
        await fixture.whenStable();
        flushReads(http);
        c.save();
        answerRecheck(http, 'unreadable');
        const [post, ...rest] = writes(http);
        expect(rest).toEqual([]);
        post.flush({});
        expect(TestBed.inject(ToastrService).warning).toHaveBeenCalledWith(expect.stringContaining('could not run'));
    });
});
