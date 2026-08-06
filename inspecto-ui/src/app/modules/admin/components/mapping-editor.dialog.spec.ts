import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { ComponentDef, ComponentsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { MappingEditorDialog } from './mapping-editor.dialog';

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

function create(def?: ComponentDef) {
    const ref = { close: vi.fn(), disableClose: false };
    const saved = { ...DEF };
    const api = { create: vi.fn(() => of(saved)), update: vi.fn(() => of(saved)) };
    TestBed.configureTestingModule({
        imports: [MappingEditorDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { def } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ComponentsService, useValue: api },
            { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    const fixture = TestBed.createComponent(MappingEditorDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, api };
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
});
