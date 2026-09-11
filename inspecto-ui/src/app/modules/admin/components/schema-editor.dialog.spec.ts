import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { ComponentDef, ComponentsService, ConfigService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SchemaEditorDialog } from './schema-editor.dialog';

const DEF: ComponentDef = {
    type: 'schema',
    name: 'ev',
    ref: 'schema/ev',
    contentHash: 'abc123',
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
                type: 'schema',
                written: false,
                findings: [
                    { severity: 'ERROR', fieldPath: 'raw.fields[QTY].type', message: 'type narrowed' },
                    { severity: 'ERROR', fieldPath: 'raw.fields[GONE]', message: 'field removed' },
                ],
            },
        },
    },
};

function create(
    def?: ComponentDef,
    config: Partial<ConfigService> = {},
    sampleRows?: Record<string, unknown>[],
    components: Partial<ComponentsService> = {},
) {
    const ref = { close: vi.fn(), disableClose: false };
    // An EDIT saves here (the registry component); only a CREATE goes to ConfigService.write.
    const comps = {
        update: vi.fn().mockReturnValue(
            of({
                type: 'schema',
                name: 'ev',
                ref: 'schema/ev',
                contentHash: 'def456',
                content: DEF.content,
            } as ComponentDef),
        ),
        ...components,
    };
    const api = {
        write: vi.fn().mockReturnValue(
            of({
                type: 'schema',
                written: true,
                path: 'ev.toon',
                name: 'ev',
                bytes: 1,
                overwritten: true,
                findings: [],
            }),
        ),
        suggestSchema: vi.fn().mockReturnValue(
            of({
                fields: [
                    { name: 'ID', selector: 'ID', type: 'BIGINT' },
                    { name: 'AMT', selector: 'AMT', type: 'DOUBLE' },
                ],
                mapping: { fields: [] },
            }),
        ),
        ...config,
    };
    const confirm = { confirmDestructive: vi.fn().mockResolvedValue(true) };
    TestBed.configureTestingModule({
        imports: [SchemaEditorDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { def, sampleRows } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ConfigService, useValue: api },
            { provide: ComponentsService, useValue: comps },
            {
                provide: ToastrService,
                useValue: { success: () => undefined, warning: () => undefined, error: () => undefined },
            },
            { provide: InspectoConfirmService, useValue: confirm },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    const fixture = TestBed.createComponent(SchemaEditorDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api, comps, confirm };
}

describe('SchemaEditorDialog', () => {
    it('loads raw.fields as rows and saves an EDIT to the registry component with If-Match, sections preserved verbatim', async () => {
        const { fixture, c, ref, api, comps } = create(DEF);
        expect(c.rows()[0]).toMatchObject({ name: 'ID', selector: '0', type: 'VARCHAR' });
        expect(c.rows()[1]).toMatchObject({ name: 'QTY', type: 'INTEGER', description: 'count' });

        c.save();
        // 🔴 SCHEMA-DIALOG-IFMATCH-1: an edit must reach `registry/schemas/ev.toon` — the component the
        // dialog was opened over — and NOT `/config/write`, which writes `<write-root>/ev.toon` instead.
        expect(comps.update).toHaveBeenCalledWith(
            'schema',
            'ev',
            expect.objectContaining({
                // the mapping section and raw.format survive the save untouched
                mapping: { canonicalName: 'events' },
                raw: expect.objectContaining({
                    name: 'ev',
                    format: 'CSV',
                    fields: [
                        { name: 'ID', selector: '0', type: 'VARCHAR' },
                        { name: 'QTY', selector: '1', type: 'INTEGER', description: 'count' },
                    ],
                }),
            }),
            { ifMatch: 'abc123' },
        );
        expect(api.write).not.toHaveBeenCalled();
        // closes with the SERVER's doc, so the post-save hash is the handle for the next edit
        expect(ref.close).toHaveBeenCalledWith({
            saved: expect.objectContaining({ name: 'ev', type: 'schema', contentHash: 'def456' }),
        });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('refuses a stale edit (409) without closing, so the author is told to reopen rather than clobber', () => {
        const { c, ref, comps } = create(DEF, {}, undefined, {
            update: vi.fn().mockReturnValue(throwError(() => ({ status: 409 }))),
        });
        c.save();
        expect(comps.update).toHaveBeenCalled();
        expect(ref.close).not.toHaveBeenCalled();
        expect(c.refused()).toBe(false); // a stale conflict is NOT a compatibility refusal
    });

    it('translates a 422 refusal onto grid cells by field NAME and shows the role=alert summary', async () => {
        const { fixture, c, ref } = create(DEF, {}, undefined, {
            update: vi.fn().mockReturnValue(throwError(() => REFUSAL)),
        });
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
        const update = vi
            .fn()
            .mockReturnValueOnce(throwError(() => REFUSAL))
            .mockReturnValue(of({ type: 'schema', name: 'ev', ref: 'schema/ev', content: DEF.content }));
        const { c, ref, confirm } = create(DEF, {}, undefined, { update });
        c.save();
        expect(c.refused()).toBe(true);

        await c.saveAnyway();
        expect(confirm.confirmDestructive).toHaveBeenCalled();
        // the override travels alongside the precondition — the escape hatch is for the compatibility
        // gate only, and must not also drop the concurrency check
        expect(update).toHaveBeenLastCalledWith('schema', 'ev', expect.anything(), {
            ifMatch: 'abc123',
            compatibility: 'none',
        });
        expect(ref.close).toHaveBeenCalledWith({ saved: expect.anything() });
    });

    it('shows no Suggest button without sampleRows in the dialog data', () => {
        const { fixture } = create(DEF);
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Suggest from sample');
    });

    it('Suggest from sample fills the grid with the served DRAFT and marks the dialog dirty — nothing is written', async () => {
        const sample = [{ ID: '1', AMT: '1.5' }];
        const { fixture, c, api } = create(undefined, {}, sample);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Suggest from sample');

        await c.suggestFromSample();
        expect(api.suggestSchema).toHaveBeenCalledWith(sample);
        expect(c.rows()).toEqual([
            { name: 'ID', selector: 'ID', type: 'BIGINT', description: '', unit: '', classification: '' },
            { name: 'AMT', selector: 'AMT', type: 'DOUBLE', description: '', unit: '', classification: '' },
        ]);
        expect(api.write).not.toHaveBeenCalled(); // a draft seeds the grid; the human still saves
        // Flush the row update into the grid before axe runs — an un-flushed ag-grid sits in a
        // transient rowless state that trips aria-required-children on .ag-root.
        fixture.detectChanges();
        await new Promise((r) => setTimeout(r, 0));
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('suggesting over already-named rows asks first and keeps them on decline', async () => {
        const { c, api, confirm } = create(DEF, {}, [{ ID: '1' }]);
        confirm.confirmDestructive.mockResolvedValue(false);
        const before = c.rows();

        await c.suggestFromSample();
        expect(confirm.confirmDestructive).toHaveBeenCalled();
        expect(api.suggestSchema).not.toHaveBeenCalled();
        expect(c.rows()).toEqual(before);
    });

    it('create mode requires a name, refuses an empty field list, and drops rows with a blank name', () => {
        const { c, api, comps } = create();
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
        expect(api.write).toHaveBeenCalledWith(
            'schema',
            expect.objectContaining({
                raw: expect.objectContaining({
                    name: 'new_schema',
                    fields: [{ name: 'A', selector: '0', type: 'varchar' }],
                }),
            }),
            { overwrite: true },
        );
        // A CREATE stays on /config/write on purpose: this dialog is also opened with no `def` from the
        // parse editor, where authoring a pipeline's satellite schema at the write root IS the intent.
        expect(comps.update).not.toHaveBeenCalled();
    });
});
