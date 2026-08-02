import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { PipelineRenameDialog, PipelineRenameData } from './pipeline-rename.dialog';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';

function make(data: Partial<PipelineRenameData> = {}) {
    const ref = { close: vi.fn(), disableClose: false };
    TestBed.configureTestingModule({
        imports: [PipelineRenameDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn().mockResolvedValue(true) } },
            { provide: MAT_DIALOG_DATA, useValue: { id: 'orders', displayName: 'orders', ...data } },
        ],
    });
    const fixture = TestBed.createComponent(PipelineRenameDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('PipelineRenameDialog', () => {
    it('closes with the trimmed display name', () => {
        const { c, ref } = make();
        c.form.controls.name.setValue('  Retail Orders (EU)  ');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({ name: 'Retail Orders (EU)' });
    });

    it('seeds the field with the current label so a rename is an edit, not a re-type', () => {
        const { c } = make({ displayName: 'Retail Orders' });
        expect(c.form.controls.name.value).toBe('Retail Orders');
    });

    it('refuses a blank name', () => {
        const { c, ref } = make();
        c.form.controls.name.setValue('   ');
        c.save();
        expect(c.form.controls.name.hasError('required')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('states that the identity does not move — the surprising part of a rename', async () => {
        const { fixture } = make();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain("identity stays 'orders'");
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
