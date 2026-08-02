import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { PipelineTemplateDialog, PipelineTemplateData } from './pipeline-template.dialog';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';

function make(data: Partial<PipelineTemplateData> = {}) {
    const ref = { close: vi.fn(), disableClose: false };
    TestBed.configureTestingModule({
        imports: [PipelineTemplateDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn().mockResolvedValue(true) } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: { source: 'orders', existingNames: ['orders', 'invoices'], ...data },
            },
        ],
    });
    const fixture = TestBed.createComponent(PipelineTemplateDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('PipelineTemplateDialog', () => {
    it('closes with the trimmed id and display name', () => {
        const { c, ref } = make();
        c.form.setValue({ id: '  orders_eu  ', displayName: '  Orders (EU)  ' });
        c.save();
        expect(ref.close).toHaveBeenCalledWith({ id: 'orders_eu', displayName: 'Orders (EU)' });
    });

    it('omits the display name when blank, so the server defaults it to the id', () => {
        const { c, ref } = make();
        c.form.setValue({ id: 'orders_eu', displayName: '   ' });
        c.save();
        expect(ref.close).toHaveBeenCalledWith({ id: 'orders_eu', displayName: undefined });
    });

    it('blocks a duplicate id inline rather than letting the server 409', () => {
        const { c, ref } = make();
        c.form.controls.id.setValue('ORDERS');   // the check is case-insensitive
        c.save();
        expect(c.form.controls.id.hasError('duplicate')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('rejects an id the backend would refuse (uppercase, spaces, punctuation)', () => {
        const { c, ref } = make();
        for (const bad of ['Orders EU', 'orders-eu', '_leading', 'orders!']) {
            c.form.controls.id.setValue(bad);
            c.save();
            expect(c.form.controls.id.hasError('pattern'), `${bad} must be refused`).toBe(true);
        }
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('says the copy is not runnable — the whole point of the dialog', async () => {
        const { fixture } = make();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('not runnable');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
