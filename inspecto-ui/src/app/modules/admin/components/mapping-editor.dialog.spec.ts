import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { ComponentDef, ComponentsService, Finding } from 'app/inspecto/api';
import { CsvImport } from 'app/inspecto/components/editable-grid.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { MappingEditorDialog, diffRules } from './mapping-editor.dialog';

const DEF: ComponentDef = {
    type: 'mapping',
    name: 'cdr_map',
    ref: 'mapping/cdr_map',
    content: {
        description: 'kept verbatim',
        rules: [
            { targetColumn: 'AMOUNT', sourceExpression: 'amt', transformType: 'DIRECT' },
            { targetColumn: 'DAY', sourceExpression: "strftime(ts, '%Y-%m-%d')", transformType: 'EXPR' },
        ],
    },
};

function create(def?: ComponentDef, findings: Finding[] = []) {
    const ref = { close: vi.fn(), disableClose: false };
    const saved = { ...DEF };
    const api = {
        create: vi.fn(() => of(saved)),
        update: vi.fn(() => of(saved)),
        validateMapping: vi.fn(() => of({ type: 'mapping', findings, clean: findings.length === 0 })),
    };
    const toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn() };
    TestBed.configureTestingModule({
        imports: [MappingEditorDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { def } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ComponentsService, useValue: api },
            { provide: ToastrService, useValue: toast },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    const fixture = TestBed.createComponent(MappingEditorDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api, toast };
}

/** A CSV import event as `<inspecto-editable-grid>` emits it. */
function importOf(rows: Record<string, string>[], over: Partial<CsvImport> = {}): CsvImport {
    return {
        rows,
        unknownHeaders: [],
        missingColumns: [],
        applied: true,
        fileName: 'rules.csv',
        ...over,
    };
}

describe('MappingEditorDialog', () => {
    it('loads the rules as grid rows and saves them back via component update, keys verbatim', async () => {
        const { fixture, c, ref, api } = create(DEF);
        expect(c.rows()).toEqual([
            { targetColumn: 'AMOUNT', sourceExpression: 'amt', transformType: 'DIRECT' },
            { targetColumn: 'DAY', sourceExpression: "strftime(ts, '%Y-%m-%d')", transformType: 'EXPR' },
        ]);

        c.save();
        expect(api.update).toHaveBeenCalledWith('mapping', 'cdr_map', expect.objectContaining({
            // content keys beyond rules survive the save (verbatim-sections rule)
            description: 'kept verbatim',
            rules: DEF.content['rules'],
        }));
        expect(ref.close).toHaveBeenCalledWith({ saved: expect.objectContaining({ name: 'cdr_map' }) });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('create mode requires an id, drops all-blank rows, and refuses an empty rule list', () => {
        const { c, api } = create();
        c.save();
        expect(api.create).not.toHaveBeenCalled(); // no id yet

        c.id.setValue('new_map');
        c.save();
        expect(api.create).not.toHaveBeenCalled(); // no rules yet — refused, not saved empty

        c.onRows([
            { targetColumn: ' A ', sourceExpression: 'a', transformType: 'DIRECT' },
            { targetColumn: '', sourceExpression: '  ', transformType: '' }, // all-blank — dropped
        ]);
        c.save();
        expect(api.create).toHaveBeenCalledWith('mapping', expect.objectContaining({
            id: 'new_map',
            rules: [{ targetColumn: 'A', sourceExpression: 'a', transformType: 'DIRECT' }],
        }));
    });

    describe('import loop (S6b)', () => {
        it('validates on save and refuses to write when the server reports an ERROR', () => {
            const { c, api, toast } = create(DEF, [
                { severity: 'ERROR', fieldPath: 'rules[1].transformType', message: "Unknown transform type 'EXPER'." },
            ]);
            c.save();
            expect(api.validateMapping).toHaveBeenCalledWith(DEF.content['rules']);
            expect(api.update).not.toHaveBeenCalled();
            expect(toast.error).toHaveBeenCalled();
            // and the finding lands on the exact cell the grid marks
            expect(c.cellFindings().get('1|transformType')).toEqual({
                severity: 'error',
                message: "Unknown transform type 'EXPER'.",
            });
        });

        it('a whole-set finding is listed but marks no cell', () => {
            const { c } = create(DEF, [{ severity: 'ERROR', fieldPath: '', message: 'A mapping needs at least one rule.' }]);
            c.save();
            expect(c.findings().length).toBe(1);
            expect(c.cellFindings().size).toBe(0);
        });

        it('a validate transport error warns but still lets the save through (the server re-checks)', () => {
            const { c, api, toast } = create(DEF);
            api.validateMapping.mockReturnValue(throwError(() => ({ status: 500 })));
            c.save();
            expect(toast.warning).toHaveBeenCalled();
            expect(api.update).toHaveBeenCalled();
        });

        it('an import diffs the new rules against the old and validates them', () => {
            const { c, api } = create(DEF);
            c.onRows([
                { targetColumn: 'AMOUNT', sourceExpression: 'amount_cents', transformType: 'DIRECT' }, // changed
                { targetColumn: 'MSISDN', sourceExpression: 'msisdn', transformType: 'DIRECT' },       // added
            ]);
            c.onImported(importOf(c.rows()));
            expect(c.diff()).toEqual([
                { change: 'Changed', targetColumn: 'AMOUNT', before: 'amt', after: 'amount_cents' },
                { change: 'Added', targetColumn: 'MSISDN', before: '', after: 'msisdn' },
                // DAY was in the file's predecessor but not the import
                { change: 'Removed', targetColumn: 'DAY', before: "EXPR(strftime(ts, '%Y-%m-%d'))", after: '' },
            ]);
            expect(api.validateMapping).toHaveBeenCalled();
            expect(c.importNote()?.variant).toBe('success');
        });

        it('a refused import (no matching header) says so and leaves the rules alone', () => {
            const { c, api } = create(DEF);
            const before = c.rows();
            c.onImported(importOf([], { applied: false, unknownHeaders: ['col_a'], fileName: 'wrong.csv' }));
            expect(c.rows()).toEqual(before);
            expect(c.diff()).toEqual([]);
            expect(c.importNote()?.variant).toBe('error');
            expect(c.importNote()?.title).toContain('wrong.csv');
            expect(api.validateMapping).not.toHaveBeenCalled(); // nothing changed — nothing to check
        });

        it('an import with unmatched header columns warns rather than passing silently', () => {
            const { c } = create(DEF);
            c.onImported(importOf(c.rows(), { unknownHeaders: ['notes'], missingColumns: ['transformType'] }));
            expect(c.importNote()?.variant).toBe('warning');
            expect(c.importNote()?.message).toContain('notes');
            expect(c.importNote()?.message).toContain('transformType');
        });

        it('editing a row clears stale findings so no cell stays marked at the wrong index', () => {
            const { c } = create(DEF, [
                { severity: 'ERROR', fieldPath: 'rules[1].targetColumn', message: 'nope' },
            ]);
            c.save();
            expect(c.cellFindings().size).toBe(1);
            c.onRows([{ targetColumn: 'A', sourceExpression: 'a', transformType: '' }]);
            expect(c.cellFindings().size).toBe(0);
        });
    });
});

describe('diffRules', () => {
    it('is keyed by target column and ignores rows without one', () => {
        expect(diffRules(
            [{ targetColumn: '', sourceExpression: 'x', transformType: '' }],
            [{ targetColumn: '', sourceExpression: 'y', transformType: '' }],
        )).toEqual([]);
    });

    it('treats a blank transform type as DIRECT, so it is not a change', () => {
        expect(diffRules(
            [{ targetColumn: 'A', sourceExpression: 'a', transformType: 'DIRECT' }],
            [{ targetColumn: 'A', sourceExpression: 'a', transformType: '' }],
        )).toEqual([]);
    });
});
