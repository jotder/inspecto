import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GAMMA_CONFIG } from '@gamma/services/config/config.constants';
import { AuthoredPipeline } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { ImportDraft } from 'app/inspecto/transfer';
import { environment } from '../../../../environments/environment';
import { PipelineEditorComponent } from './pipeline-editor.component';

const base = environment.apiBaseUrl + '/v1';
const ETAG = '"sha256:stored1"';

const STORED: AuthoredPipeline = {
    name: 'demo',
    active: true,
    nodes: [{ id: 'src', type: 'acquisition', config: { source_store: 'events' } }],
    edges: [],
};
const INCOMING = {
    name: 'demo_from_staging', // a bundle's in-file name never renames the pipeline
    active: false, // …nor changes its lifecycle
    nodes: [
        { id: 'src', type: 'acquisition', config: { source_store: 'events' } },
        { id: 'flt', type: 'transform.filter', config: { where: 'amt >= 100' } },
    ],
    edges: [{ from: 'src', rel: 'data', to: 'flt' }],
};

function draft(targetExists: boolean, id = 'demo'): ImportDraft {
    return {
        kind: 'authored-pipeline',
        id,
        content: INCOMING,
        sourceSpace: 'staging',
        targetExists,
        integrity: [], // what /bundle/preview answers for a pipeline — ALWAYS, since it judges no pipeline refs
        prerequisites: [],
    };
}

/**
 * Import as draft in the Pipeline editor (bundle load-as-draft slice 5, operator decisions 2026-09-25):
 * adopting is UNSAVED — zero writes until Save — and Save is the pane's own `PUT …/graph` (D2), guarded by
 * `If-Match` against the stored file's ETag when the draft landed on an existing pipeline (D6). A new id is
 * scaffolded + registered by the create route first (D5). Real services over HttpTestingController, so "no
 * write" is a fact about the wire, not a spy.
 */
describe('PipelineEditorComponent — Import as draft', () => {
    let toast: {
        success: ReturnType<typeof vi.fn>;
        error: ReturnType<typeof vi.fn>;
        warning: ReturnType<typeof vi.fn>;
        info: ReturnType<typeof vi.fn>;
    };

    beforeEach(() => {
        localStorage.removeItem('inspecto.currentLens');
        localStorage.removeItem('inspecto.pipelines.openTabs');
        toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
    });

    function create() {
        const dialog = { open: vi.fn() };
        TestBed.configureTestingModule({
            imports: [PipelineEditorComponent],
            providers: [
                provideNoopAnimations(),
                provideRouter([]),
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                { provide: GAMMA_CONFIG, useValue: {} },
                { provide: ToastrService, useValue: toast },
                {
                    provide: InspectoConfirmService,
                    useValue: {
                        confirm: vi.fn().mockResolvedValue(true),
                        confirmDestructive: vi.fn().mockResolvedValue(true),
                    },
                },
                { provide: MatDialog, useValue: dialog },
            ],
        });
        TestBed.overrideProvider(MatDialog, { useValue: dialog });
        const c = TestBed.createComponent(PipelineEditorComponent).componentInstance;
        c.ngOnInit();
        // No live G6 in jsdom — the canvas is never rendered here (the suite drives the component).
        (c as unknown as { canvas: unknown }).canvas = { setNodeStatus: vi.fn() };
        const http = TestBed.inject(HttpTestingController);
        return { c, http };
    }

    /** Answer every pending read: the registered list, the stored graph (with its ETag), 404 elsewhere. */
    function flushReads(http: HttpTestingController): void {
        for (const req of http.match((r) => r.method === 'GET')) {
            const url = req.request.url;
            if (url === `${base}/pipelines`)
                req.flush([{ name: 'demo', active: true, nodeCount: 1, edgeCount: 0, produces: [], consumes: [] }]);
            else if (url === `${base}/pipelines/demo/graph/raw`)
                req.flush(structuredClone(STORED), { headers: { ETag: ETAG } });
            else req.flush({}, { status: 404, statusText: 'Not Found' });
        }
    }

    const writes = (http: HttpTestingController): TestRequest[] => http.match((r) => r.method !== 'GET');
    const settle = () => new Promise((r) => setTimeout(r, 0));

    it('adopts an EXISTING pipeline unsaved, then Save is ONE PUT …/graph with If-Match (D2/D6)', async () => {
        const { c, http } = create();
        flushReads(http);
        c.onDraftImported(draft(true));
        flushReads(http);
        await settle();

        expect(c.selectedId()).toBe('demo');
        expect(c.model()!.nodes.map((n) => n.id)).toEqual(['src', 'flt']);
        expect(c.model()!.name).toBe('demo');
        expect(c.model()!.active).toBe(true); // the stored lifecycle, not the bundle's
        expect(c.dirty()).toBe(true);
        expect(c.activeDraft()?.id).toBe('demo');
        expect(c.draftStored()).toEqual(STORED); // the banner's diff baseline
        // The preview's [] means "pipelines are not judged", so it must not read as clean.
        expect(c.activeDraft()?.integrity).toBeNull();
        expect(writes(http)).toEqual([]); // adopting wrote nothing

        await c.save();
        const [put, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(put.request.method).toBe('PUT');
        expect(put.request.url).toBe(`${base}/pipelines/demo/graph`);
        expect(put.request.headers.get('If-Match')).toBe(ETAG);
        expect((put.request.body as AuthoredPipeline).nodes.map((n) => n.id)).toEqual(['src', 'flt']);
        http.expectNone(`${base}/bundle/import`);
        put.flush({ written: true, path: 'demo_pipeline.toon', name: 'demo', findings: [] });

        expect(c.dirty()).toBe(false);
        expect(c.importDraft()).toBeNull(); // saved: no longer a draft
    });

    it("an ordinary Save of the same tab sends NO If-Match — only a draft's Save is guarded", async () => {
        const { c, http } = create();
        flushReads(http);
        c.select('demo');
        flushReads(http);
        c.dirty.set(true);
        await c.save();
        const [put] = writes(http);
        expect(put.request.headers.has('If-Match')).toBe(false);
        put.flush({ written: true, path: 'demo_pipeline.toon', name: 'demo', findings: [] });
    });

    it('a NEW id opens its own tab unsaved; Save scaffolds + registers (D5), then ONE graph PUT', async () => {
        const { c, http } = create();
        flushReads(http);
        c.onDraftImported(draft(false, 'fresh'));
        flushReads(http);
        await settle();

        expect(c.selectedId()).toBe('fresh');
        expect(c.openIds()).toContain('fresh');
        expect(c.model()!.active).toBe(false); // a new pipeline lands inactive
        expect(c.dirty()).toBe(true);
        expect(writes(http)).toEqual([]);

        // A re-list (e.g. after another import) must not evict the unlisted draft tab.
        c.load();
        flushReads(http);
        expect(c.openIds()).toContain('fresh');

        const saving = c.save();
        const [scaffold, ...none] = writes(http);
        expect(none).toEqual([]);
        expect(scaffold.request.url).toBe(`${base}/config/write`);
        expect((scaffold.request.body as { config: { id: string; active: boolean } }).config).toMatchObject({
            id: 'fresh',
            active: false,
        });
        scaffold.flush({ written: true, path: 'fresh_pipeline.toon', name: 'fresh' });
        const [register] = writes(http);
        expect(register.request.url).toBe(`${base}/runs`);
        register.flush({ registered: true });
        await saving;

        const [put, ...rest] = writes(http);
        expect(rest).toEqual([]);
        expect(put.request.url).toBe(`${base}/pipelines/fresh/graph`);
        expect(put.request.headers.has('If-Match')).toBe(false); // nothing stored to be stale against
        expect((put.request.body as AuthoredPipeline).edges).toEqual(INCOMING.edges);
        put.flush({ written: true, path: 'fresh_pipeline.toon', name: 'fresh', findings: [] });
        expect(c.importDraft()).toBeNull();
        expect(c.flows().some((f) => f.name === 'fresh')).toBe(true);
    });

    it('a refused scaffold (id taken meanwhile) writes no graph and keeps the draft', async () => {
        const { c, http } = create();
        flushReads(http);
        c.onDraftImported(draft(false, 'fresh'));
        flushReads(http);
        await settle();

        const saving = c.save();
        writes(http)[0].flush({}, { status: 409, statusText: 'Conflict' });
        await saving;
        expect(writes(http)).toEqual([]);
        expect(c.activeDraft()?.id).toBe('fresh');
        expect(c.dirty()).toBe(true);
    });

    it('Discard on an existing pipeline restores the stored graph and writes nothing', async () => {
        const { c, http } = create();
        flushReads(http);
        c.onDraftImported(draft(true));
        flushReads(http);
        await settle();
        c.discardImportDraft();
        flushReads(http);
        expect(c.importDraft()).toBeNull();
        expect(c.model()).toEqual(STORED);
        expect(c.dirty()).toBe(false);
        expect(writes(http)).toEqual([]);
    });

    it('Discard on a new id closes the tab it opened and writes nothing', async () => {
        const { c, http } = create();
        flushReads(http);
        c.onDraftImported(draft(false, 'fresh'));
        flushReads(http);
        await settle();
        c.discardImportDraft();
        expect(c.openIds()).not.toContain('fresh');
        expect(c.selectedId()).toBeNull();
        expect(writes(http)).toEqual([]);
    });

    it('adopting onto an open tab with unsaved edits keeps them one undo away', async () => {
        const { c, http } = create();
        flushReads(http);
        c.select('demo');
        flushReads(http);
        c.model.set({ ...c.model()!, nodes: [...c.model()!.nodes, { id: 'mine', type: 'transform.filter' }] });
        c.dirty.set(true);
        c.onDraftImported(draft(true));
        flushReads(http);
        await settle();
        expect(c.model()!.nodes.map((n) => n.id)).toEqual(['src', 'flt']);
        await c.undo();
        expect(c.model()!.nodes.map((n) => n.id)).toEqual(['src', 'mine']);
        expect(writes(http)).toEqual([]);
    });

    it('a second draft for another pipeline is refused while one is held', async () => {
        const { c, http } = create();
        flushReads(http);
        c.onDraftImported(draft(true));
        flushReads(http);
        await settle();
        c.onDraftImported(draft(false, 'other'));
        expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining("'demo'"));
        expect(c.activeDraft()?.id).toBe('demo');
        expect(writes(http)).toEqual([]);
    });
});
