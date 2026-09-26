import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ReconAssignDialog } from './recon-assign.dialog';

function create(assignee: string) {
    const ref = { close: vi.fn(), disableClose: false };
    TestBed.configureTestingModule({
        imports: [ReconAssignDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: MAT_DIALOG_DATA, useValue: { label: 'missing right · key "MEA"', assignee } },
            { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
        ],
    });
    const fixture = TestBed.createComponent(ReconAssignDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('ReconAssignDialog', () => {
    it('closes with the trimmed assignee, and refuses a blank one with a visible error', async () => {
        const { fixture, c, ref } = create('   ');
        c.save();
        fixture.detectChanges();
        expect(ref.close).not.toHaveBeenCalled();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('.mat-mdc-form-field-subscript-wrapper mat-error')?.textContent).toContain(
            'Name who owns this break.',
        );

        c.form.controls.assignee.setValue('  dana ');
        c.save();
        expect(ref.close).toHaveBeenCalledWith('dana');
        await expectNoA11yViolations(el);
    });
});
