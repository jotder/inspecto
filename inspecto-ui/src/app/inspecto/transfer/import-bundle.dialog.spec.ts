import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
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

    it('a read-only lens cannot apply', async () => {
        const { c, applyImport } = create({}, { canAuthor: false });
        await c.onFile(fileEvent(JSON.stringify(buildBundle([{ kind: 'dataset', id: 'new_ds', content: {} }], null))));
        c.apply();
        expect(applyImport).not.toHaveBeenCalled();
    });
});
