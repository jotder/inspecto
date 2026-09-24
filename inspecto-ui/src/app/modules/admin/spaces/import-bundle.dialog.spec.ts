import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { ConnectionWarning, ImportPreview, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ImportBundleData, ImportBundleDialog } from './import-bundle.dialog';

const PREVIEW: ImportPreview = {
    kind: 'data_source',
    sourceSpace: 'alpha',
    dataSources: ['orders'],
    files: ['orders_pipeline.toon', 'orders_schema.toon'],
    hasSpaceToon: false,
    conflicts: ['orders'],
    findings: { 'orders_pipeline.toon': [{ severity: 'WARNING', fieldPath: 'dirs.poll', message: 'check path' }] },
    valid: true,
};

const WARNING: ConnectionWarning = {
    connection: 'absent_conn',
    code: 'WARN_UNRESOLVED_CONNECTION',
    message: 'connect absent_conn to enable',
    disabled: [
        { kind: 'pipeline', name: 'orders', file: 'orders_pipeline.toon' },
        { kind: 'job', name: 'export_x', file: 'export_job.toon' },
    ],
};

function create(data: ImportBundleData, preview?: ImportPreview, toastr = { warning: vi.fn() }) {
    const stub = {
        availableSpaces: signal([{ id: 'alpha' }]),
        importPreview: () => of(preview ?? PREVIEW),
        importBundle: () =>
            of({
                kind: 'data_source',
                imported: ['orders'],
                pipelines: ['orders'],
                overwritten: true,
                connectionWarnings: [WARNING],
            }),
        createFromBundle: () => of({ id: 'x', displayName: '', description: '', createdAt: '' }),
    } as unknown as SpacesService;
    TestBed.configureTestingModule({
        imports: [ImportBundleDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: SpacesService, useValue: stub },
            { provide: ToastrService, useValue: { warning: toastr.warning, error: () => {}, success: () => {} } },
        ],
    });
    const fixture = TestBed.createComponent(ImportBundleDialog);
    fixture.detectChanges();
    return fixture;
}

describe('ImportBundleDialog', () => {
    it('blocks import until conflicts are acknowledged via overwrite', () => {
        const c = create({ spaceId: 'alpha' }).componentInstance;
        c.file = new File([], 'b.zip');
        c.preview.set(PREVIEW); // valid but with a conflict
        expect(c.canImport()).toBe(false);
        c.overwrite.setValue(true);
        expect(c.canImport()).toBe(true);
    });

    it('blocks import when the preview is invalid', () => {
        const c = create({ spaceId: 'alpha' }).componentInstance;
        c.file = new File([], 'b.zip');
        c.preview.set({ ...PREVIEW, conflicts: [], valid: false });
        expect(c.canImport()).toBe(false);
    });

    it('blocks a duplicate new-space id inline in create-from-bundle mode', () => {
        const c = create({}).componentInstance;
        c.newId.setValue('alpha');
        expect(c.newId.hasError('duplicate')).toBe(true);
        c.newId.setValue('fresh');
        expect(c.newId.valid).toBe(true);
    });

    it('previews a missing connection as a warning naming what will import disabled, without blocking', async () => {
        const fixture = create({ spaceId: 'alpha' });
        const c = fixture.componentInstance;
        c.file = new File([], 'b.zip');
        c.preview.set({ ...PREVIEW, conflicts: [], connectionWarnings: [WARNING] });
        fixture.detectChanges();
        const alert = fixture.nativeElement.querySelector('inspecto-alert') as HTMLElement;
        expect(alert.textContent).toContain('Connect absent_conn to enable');
        expect(alert.textContent).toContain('pipeline orders, job export_x');
        expect(c.canImport()).toBe(true);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('toasts each connection warning after an import succeeds', () => {
        const toastr = { warning: vi.fn() };
        const c = create({ spaceId: 'alpha' }, undefined, toastr).componentInstance;
        c.file = new File([], 'b.zip');
        c.preview.set({ ...PREVIEW, conflicts: [] });
        c.doImport();
        expect(toastr.warning).toHaveBeenCalledWith(
            'Connect absent_conn to enable: pipeline orders, job export_x imported disabled.',
        );
    });

    it('import mode has no a11y violations', async () => {
        const fixture = create({ spaceId: 'alpha' });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('create-from-bundle mode has no a11y violations', async () => {
        const fixture = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
