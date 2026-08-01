import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ComponentsService, ConnectionsService, DecisionRulesService, JobsService, LensService, PipelinesService, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { buildBundle, planImport, targetIndex } from 'app/inspecto/transfer';
import { TransferComponent } from './transfer.component';

const DATASET_DEF = { type: 'dataset', name: 'cdr_sample', ref: 'dataset/cdr_sample', content: { name: 'cdr_sample' } };
const WIDGET_DEF = { type: 'widget', name: 'cost_by_tariff', ref: 'widget/cost_by_tariff', content: { vizType: 'bar', datasetId: 'cdr_sample', controls: {} } };
const PIPELINE = { name: 'cdr_ingest', active: true, nodes: [{ id: 'c', type: 'acquisition', use: 'connections/cdr_sftp_prod' }], edges: [] };
const CONNECTION = { id: 'cdr_sftp_prod', connector: 'sftp' };
const JOB = { name: 'enrich_roaming', type: 'enrich', cron: null, onPipeline: 'cdr_ingest', enabled: true, catchUp: false, params: {}, lastStatus: 'SUCCESS' };

/** The `POST /bundle/import` outcome shape — the server's own per-item verdicts (U-F). */
const outcome = (results: { kind: string; id: string; status: string; message?: string }[]) => ({
    imported: results.filter((r) => r.status === 'imported').length,
    overwritten: results.filter((r) => r.status === 'overwritten').length,
    skipped: results.filter((r) => r.status === 'skipped').length,
    unchanged: results.filter((r) => r.status === 'unchanged').length,
    failed: results.filter((r) => r.status === 'failed').length,
    results,
});

function create(opts: { canAuthor?: boolean } = {}) {
    const create = vi.fn((_type: string, content: Record<string, unknown>) => of({ content }));
    const update = vi.fn(() => of({}));
    const componentLists: Record<string, unknown[]> = { dataset: [DATASET_DEF], widget: [WIDGET_DEF] };
    TestBed.configureTestingModule({
        imports: [TransferComponent],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(),
            provideHttpClientTesting(),
            { provide: ComponentsService, useValue: { list: (t: string) => of(componentLists[t] ?? []), create, update } },
            { provide: ConnectionsService, useValue: { list: () => of([CONNECTION]), create: vi.fn(() => of(CONNECTION)), update: vi.fn(() => of(CONNECTION)) } },
            {
                provide: PipelinesService,
                useValue: {
                    authoredList: () => of([{ name: 'cdr_ingest' }]),
                    authoredRaw: () => of(PIPELINE),
                    createAuthored: vi.fn(() => of({})),
                    replaceAuthored: vi.fn(() => of({})),
                },
            },
            {
                provide: JobsService,
                useValue: {
                    list: () => of([{ name: 'enrich_roaming' }]),
                    get: () => of(JOB),
                    create: vi.fn(() => of({})),
                    update: vi.fn(() => of({})),
                },
            },
            { provide: DecisionRulesService, useValue: { list: () => of([]), create: vi.fn(() => of({})), update: vi.fn(() => of({})) } },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'staging' } },
            { provide: LensService, useValue: { canAuthorWorkbench: () => opts.canAuthor !== false } },
            { provide: ToastrService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(TransferComponent);
    return {
        fixture,
        c: fixture.componentInstance,
        createSpy: create,
        updateSpy: update,
        http: TestBed.inject(HttpTestingController),
    };
}

/** The one import request the pane now makes, instead of a write per row. */
const importRequest = (http: HttpTestingController) =>
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/bundle/import'));

describe('TransferComponent', () => {
    it('loads every artifact family into export groups (components + connections + pipelines + jobs)', () => {
        const { fixture, c } = create();
        fixture.detectChanges();
        expect(c.groups().map((g) => g.kind)).toEqual(['connection', 'dataset', 'widget', 'authored-pipeline', 'job']);
        // A job's transportable content is the upsert shape — runtime state never travels.
        const job = c.allItems().find((i) => i.kind === 'job')!;
        expect(job.content['onPipeline']).toBe('cdr_ingest');
        expect(job.content['lastStatus']).toBeUndefined();
    });

    it('exports the selection expanded to its dependency closure', async () => {
        const { fixture, c } = create();
        fixture.detectChanges();
        let captured: Blob | null = null;
        URL.createObjectURL = vi.fn((b: Blob) => {
            captured = b;
            return 'blob:x';
        });
        URL.revokeObjectURL = vi.fn();
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
        c.toggleItem({ kind: 'widget', id: 'cost_by_tariff', content: WIDGET_DEF.content });
        expect(c.selectedCount()).toBe(1);
        c.exportBundle();
        click.mockRestore();
        const bundle = JSON.parse(await captured!.text());
        expect(bundle.format).toBe('inspecto-metadata-bundle');
        expect(bundle.sourceSpace).toBe('staging');
        // The widget's dataset came along, so the viz renders as-is on the target.
        expect(bundle.items.map((i: { id: string }) => i.id)).toEqual(['cdr_sample', 'cost_by_tariff']);
    });

    /**
     * U-F (2026-08-01): apply posts the envelope to the backend ONCE rather than fanning out a
     * per-kind write per row, so the server's gates (referential integrity, connection secrets,
     * dependency order) actually run. These assert the request shape and that the pane paints the
     * server's own verdicts.
     */
    it('applies an import as ONE /bundle/import call carrying the actionable rows and the overwrite actions', () => {
        const { fixture, c, createSpy, updateSpy, http } = create();
        fixture.detectChanges();
        const bundle = buildBundle(
            [
                { kind: 'dataset', id: 'cdr_sample', content: { name: 'cdr_sample' } }, // exists on target
                { kind: 'dataset', id: 'new_ds', content: { name: 'new_ds' } }, // new
            ],
            'staging',
        );
        c.bundle.set(bundle);
        c.rows.set(planImport(bundle, targetIndex([{ kind: 'dataset', id: 'cdr_sample', content: { name: 'cdr_sample' } }])));
        c.overwriteAllExisting();
        c.apply();

        const req = importRequest(http);
        expect(req.request.body.bundle.format).toBe('inspecto-metadata-bundle');
        expect(req.request.body.bundle.sourceSpace).toBe('staging');
        expect(req.request.body.bundle.items.map((i: { id: string }) => i.id)).toEqual(['cdr_sample', 'new_ds']);
        expect(req.request.body.actions).toEqual({ 'dataset/cdr_sample': 'overwrite' });
        // The per-kind stores are no longer written through — that is exactly the point.
        expect(createSpy).not.toHaveBeenCalled();
        expect(updateSpy).not.toHaveBeenCalled();

        req.flush(outcome([
            { kind: 'dataset', id: 'cdr_sample', status: 'overwritten' },
            { kind: 'dataset', id: 'new_ds', status: 'imported' },
        ]));
        expect(c.rows().map((r) => r.result)).toEqual(['overwritten', 'imported']);
        expect(c.applied()).toBe(true);
        http.verify();
    });

    it('skip is the default for existing items — nothing is sent unless chosen', () => {
        const { fixture, c, http } = create();
        fixture.detectChanges();
        const bundle = buildBundle([{ kind: 'dataset', id: 'cdr_sample', content: {} }], null);
        c.rows.set(planImport(bundle, targetIndex([{ kind: 'dataset', id: 'cdr_sample', content: {} }])));
        c.apply();
        expect(c.actionableCount()).toBe(0);
        http.verify();   // no request at all
    });

    it('read-only lens cannot apply', () => {
        const { fixture, c, http } = create({ canAuthor: false });
        fixture.detectChanges();
        c.rows.set(planImport(buildBundle([{ kind: 'dataset', id: 'x', content: {} }], null), new Map()));
        c.apply();
        http.verify();
    });

    it('paints a per-item server failure on its row without failing the others', () => {
        const { fixture, c, http } = create();
        fixture.detectChanges();
        const bundle = buildBundle(
            [
                { kind: 'dataset', id: 'a', content: {} },
                { kind: 'dataset', id: 'b', content: {} },
            ],
            null,
        );
        c.bundle.set(bundle);
        c.rows.set(planImport(bundle, new Map()));
        c.apply();
        importRequest(http).flush(outcome([
            { kind: 'dataset', id: 'a', status: 'failed', message: 'bad content' },
            { kind: 'dataset', id: 'b', status: 'imported' },
        ]));

        expect(c.rows().map((r) => r.result)).toEqual(['failed', 'imported']);
        expect(c.rows()[0].message).toBe('bad content');
        expect(c.rows()[1].message).toBeUndefined();
    });

    /** A gate rejects the bundle as a whole, so NO row has a verdict — painting per-row failures
     *  the server never reported would misdescribe what happened. */
    it('reports a whole-bundle gate rejection once and leaves every row without a result', () => {
        const { fixture, c, http } = create();
        fixture.detectChanges();
        const bundle = buildBundle([{ kind: 'widget', id: 'w', content: {} }], null);
        c.bundle.set(bundle);
        c.rows.set(planImport(bundle, new Map()));
        c.apply();
        importRequest(http).flush(
            { error: { message: 'bundle fails referential integrity — import would introduce: widget/w → dataset/absent' } },
            { status: 422, statusText: 'Unprocessable Entity' },
        );

        expect(c.rows().every((r) => r.result === undefined)).toBe(true);
        expect(c.applied()).toBe(true);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
