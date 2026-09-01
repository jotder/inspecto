import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineDuplicateDialog, PipelineDuplicateData } from './pipeline-duplicate.dialog';

describe('PipelineDuplicateDialog', () => {
    function make(data: Partial<PipelineDuplicateData> = {}) {
        const ref = { close: vi.fn(), disableClose: false };
        TestBed.configureTestingModule({
            imports: [PipelineDuplicateDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MatDialogRef, useValue: ref },
                { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn().mockResolvedValue(true) } },
                {
                    provide: MAT_DIALOG_DATA,
                    useValue: { sourceId: 'orders', existingNames: ['orders', 'calls'], ...data },
                },
            ],
        });
        const fixture = TestBed.createComponent(PipelineDuplicateDialog);
        fixture.detectChanges();
        return { fixture, c: fixture.componentInstance, ref };
    }

    it('suggests <source>_copy and closes with the trimmed typed name', () => {
        const { c, ref } = make();
        expect(c.form.controls.name.value).toBe('orders_copy');
        c.form.controls.name.setValue('  orders eu  ');
        c.save();
        expect(ref.close).toHaveBeenCalledWith({ name: 'orders eu' });
    });

    /** Mirrors onboarding-create's uniqueNameValidator (⛔ not imported — a feature may not import a feature). */
    it('rejects a name already in use, case-insensitively, and refuses to close', () => {
        const { c, ref } = make();
        c.form.controls.name.setValue('  Calls ');
        c.save();
        expect(c.form.controls.name.hasError('duplicate')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('requires a name', () => {
        const { c, ref } = make();
        c.form.controls.name.setValue('   ');
        c.save();
        expect(c.form.controls.name.hasError('required')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('renders accessibly', async () => {
        const { fixture } = make();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
