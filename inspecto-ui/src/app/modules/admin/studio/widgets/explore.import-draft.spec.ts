import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ImportDraft } from 'app/inspecto/transfer';
import { DatasetResultService } from 'app/inspecto/viz/dataset-result.service';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { runSpec } from 'app/inspecto/viz/query-spec';
import { environment } from '../../../../../environments/environment';
import { ExploreComponent } from './explore.component';

const base = environment.apiBaseUrl + '/v1';
const DATASET = { name: 'cdr_sample', kind: 'virtual', sourceName: 'cdr', columns: [], measures: [], calculated: [] };
const INCOMING = { name: 'w1', datasetId: 'cdr_sample', vizType: 'table', controls: {}, description: 'from staging' };
const STORED = { name: 'w1', datasetId: 'cdr_sample', vizType: 'bar', controls: {} };

function draft(targetExists: boolean): ImportDraft {
    return {
        kind: 'widget',
        id: 'w1',
        content: INCOMING,
        sourceSpace: 'staging',
        targetExists,
        integrity: [],
        prerequisites: ['dataset/cdr_sample'],
    };
}

/**
 * Import as draft in the Widget editor (operator decisions 2026-09-25): adopt ⇒ unsaved, zero writes;
 * Save ⇒ exactly the pane's own request (POST new / PUT + If-Match existing). Real services over
 * HttpTestingController; only the save-name dialog is stubbed.
 */
function create(id?: string) {
    const open = vi.fn(() => ({ afterClosed: () => of({ name: 'w1', description: 'from staging' }) }));
    TestBed.configureTestingModule({
        imports: [ExploreComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            provideHttpClient(withXhr()),
            provideHttpClientTesting(),
            { provide: DatasetResultService, useValue: { run: runSpec, clear: () => undefined } },
            {
                provide: DatasetRowsService,
                useValue: { rows: () => Promise.resolve({ rows: [], columns: [], truncated: false }) },
            },
            { provide: ToastrService, useValue: { warning: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn() } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    TestBed.overrideProvider(MatDialog, { useValue: { open } });
    const fixture = TestBed.createComponent(ExploreComponent);
    if (id) fixture.componentRef.setInput('id', id);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, http: TestBed.inject(HttpTestingController), open };
}

function flushReads(http: HttpTestingController): void {
    for (const req of http.match((r) => r.method === 'GET')) {
        const url = req.request.url;
        if (url === `${base}/components/widget/w1`)
            req.flush({ type: 'widget', name: 'w1', ref: 'widget/w1', content: STORED, contentHash: 'h1' });
        else if (url === `${base}/components/dataset/cdr_sample`)
            req.flush({ type: 'dataset', name: 'cdr_sample', ref: 'dataset/cdr_sample', content: DATASET });
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

describe('ExploreComponent — Import as draft', () => {
    it('adopts a NEW-id draft unsaved; Save proposes its id and is one POST through the pane', async () => {
        const { fixture, c, http, open } = create();
        flushReads(http);
        c.onDraftImported(draft(false));
        flushReads(http);
        await fixture.whenStable();
        fixture.detectChanges();

        expect(c.vizType()).toBe('table');
        expect(c.selectedId()).toBe('cdr_sample');
        expect(writes(http)).toEqual([]);
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Imported draft from staging — not saved');
        expect(text).toContain('dataset/cdr_sample'); // the prerequisite imported first is named
        // Scoped to the new banner: the adopted `table` viz mounts an empty ag-grid, a known jsdom axe finding.
        await expectNoA11yViolations(
            (fixture.nativeElement as HTMLElement).querySelector('inspecto-import-draft-banner') as HTMLElement,
        );

        c.save();
        expect(
            (open.mock.calls[0] as unknown as [unknown, { data: { suggestedId: string } }])[1].data.suggestedId,
        ).toBe('w1');
        expect(writes(http)).toEqual([]); // the re-check runs FIRST — nothing is written before it answers
        const checked = answerRecheck(http, []);
        expect(checked.items).toEqual([
            { kind: 'widget', id: 'w1', content: expect.objectContaining({ vizType: 'table' }) },
        ]);
        const [post, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(post.request.method).toBe('POST');
        expect(post.request.url).toBe(`${base}/components/widget`);
        expect(post.request.body).toMatchObject({ id: 'w1', vizType: 'table' });
        http.expectNone(`${base}/bundle/import`);
        post.flush({});
        // A clean re-check is a plain success, not a warning.
        expect(TestBed.inject(ToastrService).warning).not.toHaveBeenCalled();
        expect(TestBed.inject(ToastrService).success).toHaveBeenCalled();
    });

    it('an EXISTING id opens with the incoming content unsaved and saves as a PUT with If-Match (D6)', async () => {
        const { fixture, c, http } = create('w1');
        flushReads(http);
        c.onDraftImported(draft(true));
        flushReads(http);
        await fixture.whenStable();
        fixture.detectChanges();

        expect(c.vizType()).toBe('table');
        expect(c.draftStored()).toEqual(STORED);
        expect(writes(http)).toEqual([]);

        // An edit AFTER adoption is what the re-check judges — not the content the bundle carried.
        c.vizType.set('bar');
        c.save();
        const checked = answerRecheck(http, ["broken reference: widget 'w1' -> missing dataset 'cdr_sample'"]);
        expect(checked.items[0].content).toMatchObject({ vizType: 'bar' });
        const [put, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(put.request.method).toBe('PUT');
        expect(put.request.url).toBe(`${base}/components/widget/w1`);
        expect(put.request.headers.get('If-Match')).toBe('"sha256:h1"');
        put.flush({}, { status: 409, statusText: 'Conflict' });
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain("missing dataset 'cdr_sample'");
    });

    it('an unreadable re-check does not block the Save — it says "not checked" instead', () => {
        const { c, http } = create('w1');
        flushReads(http);
        c.onDraftImported(draft(true));
        flushReads(http);
        c.save();
        answerRecheck(http, 'unreadable');
        const [put, ...rest] = writes(http);
        expect(rest).toEqual([]);
        put.flush({});
        expect(TestBed.inject(ToastrService).warning).toHaveBeenCalledWith(expect.stringContaining('could not run'));
    });

    it('Discard restores the stored widget and writes nothing', async () => {
        const { c, http } = create('w1');
        flushReads(http);
        c.onDraftImported(draft(true));
        flushReads(http);
        c.discardDraft();
        flushReads(http);
        expect(c.importDraft()).toBeNull();
        expect(c.vizType()).toBe('bar');
        expect(writes(http)).toEqual([]);
    });
});
