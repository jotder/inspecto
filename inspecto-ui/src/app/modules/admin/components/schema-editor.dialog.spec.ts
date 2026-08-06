import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { ComponentDef, ConfigService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SchemaEditorDialog } from './schema-editor.dialog';

const DEF: ComponentDef = {
    type: 'schema',
    name: 'ev',
    ref: 'schema/ev',
    content: {
        raw: {
            name: 'ev',
            format: 'CSV',
            fields: [
                { name: 'ID', selector: '0', type: 'VARCHAR' },
                { name: 'QTY', selector: '1', type: 'INTEGER', description: 'count' },
            ],
        },
        mapping: { canonicalName: 'events' },
    },
};

/** The v1 422 envelope the real route emits (findings under error.details). */
const REFUSAL = {
    status: 422,
    error: {
        error: {
            details: {
                type: 'schema', written: false,
                findings: [
                    { severity: 'ERROR', fieldPath: 'raw.fields[QTY].type', message: 'type narrowed' },
                    { severity: 'ERROR', fieldPath: 'raw.fields[GONE]', message: 'field removed' },
                ],
            },
        },
    },
};

function create(def?: ComponentDef, config: Partial<ConfigService> = {}) {
    const ref = { close: vi.fn(), disableClose: false };
    const api = {
        write: vi.fn().mockReturnValue(of({ type: 'schema', written: true, path: 'ev.toon', name: 'ev', bytes: 1, overwritten: true, findings: [] })),
        ...config,
    };
    const confirm = { confirmDestructive: vi.fn().mockResolvedValue(true) };
    TestBed.configureTestingModule({
        imports: [SchemaEditorDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { def } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ConfigService, useValue: api },
            { provide: ToastrService, useValue: { success: () => undefined, warning: () => undefined, error: () => undefined } },
            { provide: InspectoConfirmService, useValue: confirm },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    const fixture = TestBed.createComponent(SchemaEditorDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api, confirm };
}

describe('SchemaEditorDialog', () => {
    it('loads raw.fields as rows and saves through the gated /config/write, sections preserved verbatim', async () => {
        const { fixture, c, ref, api } = create(DEF);
        expect(c.rows()[0]).toMatchObject({ name: 'ID', selector: '0', type: 'VARCHAR' });
        expect(c.rows()[1]).toMatchObject({ name: 'QTY', type: 'INTEGER', description: 'count' });

        c.save();
        expect(api.write).toHaveBeenCalledWith('schema', expect.objectContaining({
            // the mapping section and raw.format survive the save untouched
            mapping: { canonicalName: 'events' },
            raw: expect.objectContaining({
                name: 'ev', format: 'CSV',
                fields: [
                    { name: 'ID', selector: '0', type: 'VARCHAR' },
                    { name: 'QTY', selector: '1', type: 'INTEGER', description: 'count' },
                ],
            }),
        }), { overwrite: true });
        expect(ref.close).toHaveBeenCalledWith({ saved: expect.objectContaining({ name: 'ev', type: 'schema' }) });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('translates a 422 refusal onto grid cells by field NAME and shows the role=alert summary', async () => {
        const { fixture, c, ref } = create(DEF, { write: vi.fn().mockReturnValue(throwError(() => REFUSAL)) });
        c.save();
        fixture.detectChanges();

        expect(ref.close).not.toHaveBeenCalled();
        expect(c.refused()).toBe(true);
        // QTY is row 1; the removed field GONE has no row to anchor to — summary only
        expect(c.cellFindings().get('1|type')).toMatchObject({ severity: 'error', message: 'type narrowed' });
        expect(c.cellFindings().size).toBe(1);
        const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
        expect(alert?.textContent).toContain('type narrowed');
        expect(alert?.textContent).toContain('field removed');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('saveAnyway re-sends with compatibility "none" after a confirmed destructive prompt', async () => {
        const write = vi.fn()
            .mockReturnValueOnce(throwError(() => REFUSAL))
            .mockReturnValue(of({ type: 'schema', written: true, path: 'ev.toon', name: 'ev', bytes: 1, overwritten: true, findings: [] }));
        const { c, ref, confirm } = create(DEF, { write });
        c.save();
        expect(c.refused()).toBe(true);

        await c.saveAnyway();
        expect(confirm.confirmDestructive).toHaveBeenCalled();
        expect(write).toHaveBeenLastCalledWith('schema', expect.anything(), { overwrite: true, compatibility: 'none' });
        expect(ref.close).toHaveBeenCalledWith({ saved: expect.anything() });
    });

    it('create mode requires a name, refuses an empty field list, and drops rows with a blank name', () => {
        const { c, api } = create();
        c.save();
        expect(api.write).not.toHaveBeenCalled(); // no name yet

        c.name.setValue('new_schema');
        c.save();
        expect(api.write).not.toHaveBeenCalled(); // no fields yet — refused

        c.onRows([
            { name: ' A ', selector: '0', type: 'varchar', description: '', unit: '', classification: '' },
            { name: '', selector: '3', type: 'VARCHAR', description: '', unit: '', classification: '' }, // blank name — dropped
        ]);
        c.save();
        expect(api.write).toHaveBeenCalledWith('schema', expect.objectContaining({
            raw: expect.objectContaining({
                name: 'new_schema',
                fields: [{ name: 'A', selector: '0', type: 'varchar' }],
            }),
        }), { overwrite: true });
    });
});
