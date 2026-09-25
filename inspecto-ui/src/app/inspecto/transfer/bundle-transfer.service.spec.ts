import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { environment } from '../../../environments/environment';
import { BundleItem, MetadataBundle } from './bundle';
import { BundleTransferService } from './bundle-transfer.service';

const base = environment.apiBaseUrl + '/v1';

/**
 * 🔴 <b>The WIRING of pipeline spec gap 6a</b>, which the pure closure tests in `bundle.spec.ts` cannot
 * reach. Those prove `withDependencies` follows a server edge when it is handed one; these prove the
 * export path actually GOES AND GETS one, and that a correct closure function reached by nobody would
 * fail here rather than pass everywhere.
 *
 * <p>The hole being closed: a pipeline's companion bound by CONFIG KEY (`parsing.grammar: grammar/cdr`)
 * is invisible to the client's `nodes[].use` derivation, so such a pipeline exported without its
 * grammar and the import landed a pipeline that could not parse.
 */
describe('BundleTransferService — buildExport consults the server closure (gap 6a)', () => {
    let svc: BundleTransferService;
    let http: HttpTestingController;

    const PIPELINE: BundleItem = {
        kind: 'pipeline',
        id: 'cdr_ingest',
        content: { name: 'cdr_ingest', nodes: [], edges: [] },
    };
    const GRAMMAR: BundleItem = { kind: 'grammar', id: 'cdr', content: { name: 'cdr' } };

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [BundleTransferService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(BundleTransferService);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    /** The export body must carry the grammar, which only the server's answer could have contributed. */
    it('pulls in a companion only the server knows about', () => {
        let out: { bundle: MetadataBundle; missing: string[]; absent: string[] } | undefined;
        svc.buildExport([PIPELINE], [PIPELINE, GRAMMAR], true).subscribe((r) => (out = r));

        http.expectOne(`${base}/pipelines/cdr_ingest/related`).flush({
            pipeline: 'cdr_ingest',
            references: [
                { kind: 'grammar', ref: 'grammar/cdr', path: 'registry/grammars/cdr.toon' },
                // ⚠ a plain file is NOT a bundle item and must not become a closure edge
                { kind: 'file', path: 'cdr_schema.toon' },
            ],
            dependents: {},
            total: 0,
            truncated: false,
        });

        const exportReq = http.expectOne(`${base}/bundle/export`);
        const sent = (exportReq.request.body as { items: { kind: string; id: string }[] }).items;
        expect(sent.map((i) => `${i.kind}/${i.id}`).sort()).toEqual(['grammar/cdr', 'pipeline/cdr_ingest']);
        exportReq.flush({ bundle: { items: [] } });
        expect(out?.missing).toEqual([]);
    });

    /**
     * ⚠ An older server without the route, or one refusing it, must not fail the export — the
     * client-derived closure is then exactly what it was before this change.
     */
    it('degrades to the client closure when the route is unavailable', () => {
        let out: { bundle: MetadataBundle } | undefined;
        svc.buildExport([PIPELINE], [PIPELINE, GRAMMAR], true).subscribe((r) => (out = r));

        http.expectOne(`${base}/pipelines/cdr_ingest/related`).flush('nope', {
            status: 404,
            statusText: 'Not Found',
        });

        const exportReq = http.expectOne(`${base}/bundle/export`);
        expect((exportReq.request.body as { items: unknown[] }).items).toHaveLength(1);
        exportReq.flush({ bundle: { items: [] } });
        expect(out).toBeTruthy();
    });

    /** ⚠ A selection-only export must not make N extra calls to answer a question nobody asked. */
    it('does not consult the server when dependencies are not being followed', () => {
        svc.buildExport([PIPELINE], [PIPELINE, GRAMMAR], false).subscribe();
        http.expectNone(`${base}/pipelines/cdr_ingest/related`);
        http.expectOne(`${base}/bundle/export`).flush({ bundle: { items: [] } });
    });

    /** Nothing to ask about: no pipeline in the selection means no closure call at all. */
    it('makes no closure call for a selection with no pipeline', () => {
        svc.buildExport([GRAMMAR], [GRAMMAR], true).subscribe();
        http.expectNone(`${base}/pipelines/cdr/related`);
        http.expectOne(`${base}/bundle/export`).flush({ bundle: { items: [] } });
    });

    /** Import as draft (D3): the advisory integrity read is the READ-ONLY preview, never the import door. */
    it('previews through /bundle/preview and hands back its integrity findings', () => {
        const bundle = { format: 'inspecto-metadata-bundle', version: 2, items: [] } as unknown as MetadataBundle;
        let integrity: string[] | undefined;
        svc.preview(bundle).subscribe((p) => (integrity = p.integrity));
        const req = http.expectOne(`${base}/bundle/preview`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toBe(bundle);
        req.flush({ items: [], requires: [], integrity: ["broken reference: widget 'w' -> missing dataset 'd'"] });
        http.expectNone(`${base}/bundle/import`);
        expect(integrity).toEqual(["broken reference: widget 'w' -> missing dataset 'd'"]);
    });

    /** The pre-Save re-check: the draft's CURRENT content, as a one-item envelope, through the same preview. */
    it('re-checks an edited draft through /bundle/preview with just that content', () => {
        const content = { dataset: 'd2', type: 'table' };
        let integrity: string[] | null | undefined;
        svc.draftIntegrity('widget', 'w', content).subscribe((i) => (integrity = i));
        const req = http.expectOne(`${base}/bundle/preview`);
        expect(req.request.method).toBe('POST');
        const body = req.request.body as MetadataBundle;
        expect(body.format).toBe('inspecto-metadata-bundle');
        expect(body.version).toBe(2);
        expect(body.items).toEqual([{ kind: 'widget', id: 'w', content }]);
        req.flush({ items: [], requires: [], integrity: ["broken reference: widget 'w' -> missing dataset 'd2'"] });
        http.expectNone(`${base}/bundle/import`);
        expect(integrity).toEqual(["broken reference: widget 'w' -> missing dataset 'd2'"]);
    });

    it('an unreadable re-check is null ("not checked"), never an empty list', () => {
        let integrity: string[] | null | undefined;
        svc.draftIntegrity('widget', 'w', {}).subscribe((i) => (integrity = i));
        http.expectOne(`${base}/bundle/preview`).flush({}, { status: 500, statusText: 'boom' });
        expect(integrity).toBeNull();

        svc.draftIntegrity('widget', 'w', {}).subscribe((i) => (integrity = i));
        http.expectOne(`${base}/bundle/preview`).flush({ items: [], requires: [] }); // an older server: no list
        expect(integrity).toBeNull();
    });
});

/**
 * BUNDLE-AUTHORED-PIPELINE-STORE-1, option B: the Transfer screen's pipelines are the REGISTERED
 * `*_pipeline.toon` files (`GET /pipelines` + `…/graph/raw`), so they must travel as kind `pipeline` —
 * the server kind whose import lands the file and its sidecars through `POST /pipelines/import`'s core.
 * As `authored-pipeline` they exported as `missing`, and a write-through import of that kind is now a 422.
 */
describe('BundleTransferService — loadAll offers registered pipelines as kind `pipeline`', () => {
    let svc: BundleTransferService;
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [BundleTransferService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(BundleTransferService);
        http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    it('emits `pipeline` items filled from graph/raw, and no `authored-pipeline` ones', () => {
        let items: BundleItem[] | undefined;
        svc.loadAll().subscribe((i) => (items = i));

        for (const req of http.match(() => true))
            req.flush(req.request.url === `${base}/pipelines` ? [{ name: 'cdr_ingest', nodes: 1, edges: 0 }] : []);
        http.expectOne(`${base}/pipelines/cdr_ingest/graph/raw`).flush({
            name: 'cdr_ingest',
            active: false,
            nodes: [],
            edges: [],
        });

        const pipelines = (items ?? []).filter((i) => i.kind === 'pipeline' || i.kind === 'authored-pipeline');
        expect(pipelines.map((i) => `${i.kind}/${i.id}`)).toEqual(['pipeline/cdr_ingest']);
    });
});
