import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { LensService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { BundleItem, buildBundle } from './bundle';
import { BundleTransferService } from './bundle-transfer.service';
import { ImportBundleData, ImportBundleDialog } from './import-bundle.dialog';

const TARGET: BundleItem[] = [{ kind: 'dataset', id: 'cdr_sample', content: { name: 'cdr_sample' } }];

function fileEvent(text: string): Event {
    return { target: { files: [new File([text], 'bundle.json')], value: '' } } as unknown as Event;
}

/** The `POST /bundle/import` outcome the service now returns (U-F). */
const outcome = (results: { kind: string; id: string; status: string; message?: string }[]) => ({
    imported: results.filter((r) => r.status === 'imported').length,
    overwritten: results.filter((r) => r.status === 'overwritten').length,
    skipped: results.filter((r) => r.status === 'skipped').length,
    unchanged: results.filter((r) => r.status === 'unchanged').length,
    failed: results.filter((r) => r.status === 'failed').length,
    results,
});

function create(data: ImportBundleData = {}, opts: { canAuthor?: boolean; outcome?: ReturnType<typeof outcome> } = {}) {
    const applyImport = vi.fn(() =>
        of(opts.outcome ?? outcome([{ kind: 'dataset', id: 'new_ds', status: 'imported' }])),
    );
    TestBed.configureTestingModule({
        imports: [ImportBundleDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: vi.fn() } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: BundleTransferService, useValue: { loadAll: () => of(TARGET), applyImport } },
            { provide: ToastrService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() } },
            { provide: LensService, useValue: { canAuthorWorkbench: signal(opts.canAuthor !== false) } },
        ],
    });
    const fixture = TestBed.createComponent(ImportBundleDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, applyImport };
}

/** Draft-mode fixture: the service also answers `preview`, and `close` is observable. */
function createDraft(
    data: Omit<ImportBundleData, 'mode'>,
    opts: { importError?: unknown; previewFails?: boolean } = {},
) {
    const applyImport = vi.fn(() =>
        opts.importError
            ? throwError(() => opts.importError)
            : of(outcome([{ kind: 'widget', id: 'w1', status: 'imported' }])),
    );
    const preview = vi.fn(() =>
        opts.previewFails
            ? throwError(() => ({ status: 500 }))
            : of({ items: [], requires: [], integrity: ['finding'] }),
    );
    const close = vi.fn();
    const toastr = { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ImportBundleDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: { ...data, mode: 'draft' } },
            { provide: BundleTransferService, useValue: { loadAll: () => of(TARGET), applyImport, preview } },
            { provide: ToastrService, useValue: toastr },
            { provide: LensService, useValue: { canAuthorWorkbench: signal(true) } },
        ],
    });
    const fixture = TestBed.createComponent(ImportBundleDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, applyImport, preview, close, toastr };
}

describe('ImportBundleDialog', () => {
    it('fit-checks an uploaded bundle: new vs existing, drift, and missing requires', async () => {
        const { fixture, c } = create();
        // cdr_sample exists on target (identical → skip); a new widget bound to a missing dataset
        const bundle = buildBundle(
            [
                { kind: 'dataset', id: 'cdr_sample', content: { name: 'cdr_sample' } },
                { kind: 'widget', id: 'w1', content: { vizType: 'bar', datasetId: 'nope', controls: {} } },
            ],
            'staging',
        );
        await c.onFile(fileEvent(JSON.stringify(bundle)));
        fixture.detectChanges();
        expect(c.rows().map((r) => [r.item.id, r.exists, r.action])).toEqual([
            ['cdr_sample', true, 'skip'],
            ['w1', false, 'import'],
        ]);
        expect(c.missingRequires().map((r) => r.ref.id)).toEqual(['nope']);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('scopes rows to allowedKinds when a library imports', async () => {
        const { c } = create({ allowedKinds: ['widget'] });
        const bundle = buildBundle(
            [
                { kind: 'dataset', id: 'd1', content: {} },
                { kind: 'widget', id: 'w1', content: { vizType: 'bar', datasetId: 'd1', controls: {} } },
            ],
            null,
        );
        await c.onFile(fileEvent(JSON.stringify(bundle)));
        expect(c.rows().map((r) => r.item.id)).toEqual(['w1']);
    });

    /**
     * U-F (2026-08-01): one `/bundle/import` call carrying the actionable rows, instead of a per-kind
     * write per row — so the server's referential-integrity, secret and ordering gates actually run.
     */
    it('applies actionable rows as ONE backend call and counts what the server says it wrote', async () => {
        const { c, applyImport } = create();
        await c.onFile(
            fileEvent(
                JSON.stringify(buildBundle([{ kind: 'dataset', id: 'new_ds', content: { name: 'new_ds' } }], null)),
            ),
        );
        c.apply();

        expect(applyImport).toHaveBeenCalledTimes(1);
        const [envelope, actions] = applyImport.mock.calls[0] as unknown as [
            { items: { id: string }[] },
            Record<string, string>,
        ];
        expect(envelope.items.map((i) => i.id)).toEqual(['new_ds']);
        expect(actions).toEqual({}); // nothing existed, so nothing needed an overwrite opt-in
        expect(c.rows()[0].result).toBe('imported');
        expect(c.importedCount()).toBe(1);
    });

    it('counts an identical re-promotion as nothing written, not as an import', async () => {
        const { c } = create({}, { outcome: outcome([{ kind: 'dataset', id: 'cdr_sample', status: 'unchanged' }]) });
        // cdr_sample is on the target already; the operator opts into overwrite, the server says unchanged.
        await c.onFile(
            fileEvent(
                JSON.stringify(
                    buildBundle([{ kind: 'dataset', id: 'cdr_sample', content: { name: 'cdr_sample' } }], null),
                ),
            ),
        );
        c.overwriteAllExisting();
        c.apply();

        expect(c.rows()[0].result).toBe('unchanged');
        expect(c.importedCount()).toBe(0);
    });

    // ── Import as draft (operator decisions 2026-09-25, D1–D8) ──────────────────────────────────────
    // The probe that would otherwise succeed is the apply-mode spec above: the SAME fixture shape issues
    // exactly one applyImport. In draft mode a target with no prerequisites must issue none.

    it('draft mode opens the chosen item as a draft and never calls the import door', async () => {
        const { fixture, c, applyImport, preview, close } = createDraft({ allowedKinds: ['widget'] });
        await c.onFile(
            fileEvent(
                JSON.stringify(
                    buildBundle(
                        [
                            { kind: 'dataset', id: 'cdr_sample', content: { name: 'cdr_sample' } },
                            { kind: 'widget', id: 'w1', content: { vizType: 'bar', datasetId: 'cdr_sample' } },
                        ],
                        'staging',
                    ),
                ),
            ),
        );
        fixture.detectChanges();
        expect(c.rows().map((r) => r.item.id)).toEqual(['w1']); // only the host's own kind is a draft candidate
        expect(c.prerequisites()).toEqual([]); // its Dataset is already here
        await expectNoA11yViolations(fixture.nativeElement);

        c.openDraft();

        expect(applyImport).not.toHaveBeenCalled();
        expect(preview).toHaveBeenCalledTimes(1);
        const [envelope] = preview.mock.calls[0] as unknown as [{ items: { id: string }[] }];
        expect(envelope.items.map((i) => i.id)).toEqual(['w1']);
        expect(close).toHaveBeenCalledWith({
            kind: 'widget',
            id: 'w1',
            content: { vizType: 'bar', datasetId: 'cdr_sample' },
            sourceSpace: 'staging',
            targetExists: false,
            integrity: ['finding'],
            prerequisites: [],
        });
    });

    it('draft mode imports missing prerequisites write-through FIRST, then opens the draft (D4)', async () => {
        const { c, applyImport, preview, close } = createDraft({ allowedKinds: ['dashboard'] });
        await c.onFile(
            fileEvent(
                JSON.stringify(
                    buildBundle(
                        [
                            {
                                kind: 'dashboard',
                                id: 'd1',
                                content: { name: 'd1', tiles: [{ widgetId: 'w1', span: 1 }] },
                            },
                            { kind: 'widget', id: 'w1', content: { vizType: 'bar', datasetId: 'cdr_sample' } },
                        ],
                        null,
                    ),
                ),
            ),
        );
        expect(c.prerequisites().map((i) => i.id)).toEqual(['w1']);

        c.openDraft();

        expect(applyImport).toHaveBeenCalledTimes(1);
        const [envelope, actions] = applyImport.mock.calls[0] as unknown as [{ items: { id: string }[] }, object];
        expect(envelope.items.map((i) => i.id)).toEqual(['w1']); // ONLY the prerequisite, never the draft target
        expect(actions).toEqual({});
        expect(preview).toHaveBeenCalledTimes(1);
        expect(close).toHaveBeenCalledWith(expect.objectContaining({ id: 'd1', prerequisites: ['widget/w1'] }));
    });

    it('a refused prerequisite import opens NO draft (the applyKpiReport rule)', async () => {
        const { c, preview, close, toastr } = createDraft(
            { allowedKinds: ['dashboard'] },
            { importError: { status: 422, error: { error: { message: 'bundle fails referential integrity' } } } },
        );
        await c.onFile(
            fileEvent(
                JSON.stringify(
                    buildBundle(
                        [
                            { kind: 'dashboard', id: 'd1', content: { tiles: [{ widgetId: 'w1', span: 1 }] } },
                            { kind: 'widget', id: 'w1', content: { vizType: 'bar', datasetId: 'ghost' } },
                        ],
                        null,
                    ),
                ),
            ),
        );
        c.openDraft();
        expect(toastr.error).toHaveBeenCalled();
        expect(preview).not.toHaveBeenCalled();
        expect(close).not.toHaveBeenCalled();
    });

    it('marks a draft onto an existing id, and degrades an unreadable preview to "not checked"', async () => {
        const { c, close } = createDraft({ allowedKinds: ['dataset'] }, { previewFails: true });
        await c.onFile(
            fileEvent(
                JSON.stringify(
                    buildBundle([{ kind: 'dataset', id: 'cdr_sample', content: { name: 'renamed' } }], null),
                ),
            ),
        );
        c.openDraft();
        expect(close).toHaveBeenCalledWith(
            expect.objectContaining({ id: 'cdr_sample', targetExists: true, integrity: null }),
        );
    });

    it('a read-only lens cannot apply', async () => {
        const { c, applyImport } = create({}, { canAuthor: false });
        await c.onFile(fileEvent(JSON.stringify(buildBundle([{ kind: 'dataset', id: 'new_ds', content: {} }], null))));
        c.apply();
        expect(applyImport).not.toHaveBeenCalled();
    });
});
