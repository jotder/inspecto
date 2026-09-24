import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ImportDraft, ImportDraftHandoff } from 'app/inspecto/transfer';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { environment } from '../../../../../environments/environment';
import { DashboardEditorComponent } from './dashboard-editor.component';

const base = environment.apiBaseUrl + '/v1';
const INCOMING = { name: 'd1', tiles: [{ widgetId: 'w1', span: 2 }], filter: null, exposedFields: ['region'] };
const STORED = { name: 'd1', tiles: [{ widgetId: 'w0', span: 1 }], filter: null, exposedFields: [] };

function draft(targetExists: boolean, id = 'd1'): ImportDraft {
    return {
        kind: 'dashboard',
        id,
        content: INCOMING,
        sourceSpace: 'staging',
        targetExists,
        integrity: ["broken reference: dashboard 'd1' tile -> missing widget 'w1'"],
        prerequisites: [],
    };
}

/**
 * Import as draft in the Dashboard editor (operator decisions 2026-09-25): adopting a draft is
 * UNSAVED — zero writes until Save — and Save is exactly the pane's own request (D2), carrying
 * `If-Match` against the stored copy when the draft landed on an existing id (D6).
 * Real services over HttpTestingController, so "no write" is a fact about the wire, not a spy.
 */
function create(id?: string) {
    TestBed.configureTestingModule({
        imports: [DashboardEditorComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            provideHttpClient(withXhr()),
            provideHttpClientTesting(),
            {
                provide: DatasetRowsService,
                useValue: { rows: () => Promise.resolve({ rows: [], columns: [], truncated: false }) },
            },
            {
                provide: ToastrService,
                useValue: { warning: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn() },
            },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    const fixture = TestBed.createComponent(DashboardEditorComponent);
    if (id) fixture.componentRef.setInput('id', id);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    return { fixture, c: fixture.componentInstance, http };
}

/** Answer every pending read: the stored dashboard where asked, an empty list everywhere else. */
function flushReads(http: HttpTestingController): void {
    for (const req of http.match((r) => r.method === 'GET')) {
        if (req.request.url === `${base}/components/dashboard/d1`)
            req.flush({ type: 'dashboard', name: 'd1', ref: 'dashboard/d1', content: STORED, contentHash: 'abc123' });
        else req.flush([]);
    }
}

const writes = (http: HttpTestingController) => http.match((r) => r.method !== 'GET');

describe('DashboardEditorComponent — Import as draft', () => {
    it('adopts a NEW-id draft unsaved on the create route, then Save is one POST through the pane', async () => {
        const { fixture, c, http } = create();
        flushReads(http);
        c.onDraftImported(draft(false));
        flushReads(http);
        fixture.detectChanges();

        expect(c.tiles()).toEqual(INCOMING.tiles);
        expect(c.exposedFields()).toEqual(['region']);
        expect(c.form.controls.name.value).toBe('d1');
        expect(writes(http)).toEqual([]); // adopting wrote nothing
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Imported draft from staging — not saved');
        expect(text).toContain("missing widget 'w1'");
        await expectNoA11yViolations(fixture.nativeElement);

        c.save();
        const [post, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(post.request.method).toBe('POST');
        expect(post.request.url).toBe(`${base}/components/dashboard`);
        expect(post.request.headers.has('If-Match')).toBe(false);
        expect(post.request.body).toMatchObject({ id: 'd1', tiles: INCOMING.tiles });
        http.expectNone(`${base}/bundle/import`);
    });

    it('opens an EXISTING id with incoming content as unsaved edits + diff; Save is a PUT with If-Match (D6)', () => {
        const { fixture, c, http } = create('d1');
        flushReads(http);
        c.onDraftImported(draft(true));
        flushReads(http);
        fixture.detectChanges();

        expect(c.tiles()).toEqual(INCOMING.tiles);
        expect(c.draftStored()).toEqual(STORED);
        expect(writes(http)).toEqual([]);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Changes against the stored dashboard');

        c.save();
        const [put, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(put.request.method).toBe('PUT');
        expect(put.request.url).toBe(`${base}/components/dashboard/d1`);
        expect(put.request.headers.get('If-Match')).toBe('"sha256:abc123"');
    });

    it('Discard drops the draft and restores the stored dashboard, writing nothing', () => {
        const { c, http } = create('d1');
        flushReads(http);
        c.onDraftImported(draft(true));
        flushReads(http);
        c.discardDraft();
        flushReads(http);
        expect(c.importDraft()).toBeNull();
        expect(c.tiles()).toEqual(STORED.tiles);
        expect(writes(http)).toEqual([]);
    });

    it('a draft for another id is not adopted here: it is handed to that editor', () => {
        const { c, http } = create('d0');
        flushReads(http);
        const handoff = TestBed.inject(ImportDraftHandoff);
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
        vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        c.onDraftImported(draft(true));
        expect(c.importDraft()).toBeNull();
        expect(navigate).toHaveBeenCalled(); // edit -> edit bounces through the list (route reuse)
        expect(handoff.take('dashboard', 'd1')?.id).toBe('d1');
    });
});
