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
        kind: 'authored-pipeline',
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
        expect(sent.map((i) => `${i.kind}/${i.id}`).sort()).toEqual(['authored-pipeline/cdr_ingest', 'grammar/cdr']);
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
});
