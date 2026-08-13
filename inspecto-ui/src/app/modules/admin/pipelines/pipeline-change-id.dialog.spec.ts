import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { PipelineChangeIdDialog, PipelineChangeIdData } from './pipeline-change-id.dialog';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';

function make(data: Partial<PipelineChangeIdData> = {}) {
    const ref = { close: vi.fn(), disableClose: false };
    TestBed.configureTestingModule({
        imports: [PipelineChangeIdDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: ref },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn().mockResolvedValue(true) } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: { id: 'orders', displayName: 'orders', existingNames: ['orders', 'invoices'], ...data },
            },
        ],
    });
    const fixture = TestBed.createComponent(PipelineChangeIdDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('PipelineChangeIdDialog', () => {
    it('closes with the trimmed new id once the current id is typed to confirm', () => {
        const { c, ref } = make();
        c.form.setValue({ newId: '  orders_eu  ', confirmId: 'orders' });
        c.save();
        expect(ref.close).toHaveBeenCalledWith({ newId: 'orders_eu' });
    });

    it('does not enable the confirm action until the current id is typed exactly', () => {
        const { c, ref } = make();
        c.form.setValue({ newId: 'orders_eu', confirmId: 'Orders' }); // wrong case
        expect(c.confirmed()).toBe(false);
        c.save();
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('blocks a duplicate new id inline rather than letting the server 409', () => {
        const { c, ref } = make();
        c.form.setValue({ newId: 'INVOICES', confirmId: 'orders' }); // the check is case-insensitive
        c.save();
        expect(c.form.controls.newId.hasError('duplicate')).toBe(true);
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('rejects a new id the backend would refuse (uppercase, spaces, punctuation)', () => {
        const { c, ref } = make();
        for (const bad of ['Orders EU', 'orders-eu', '_leading', 'orders!']) {
            c.form.setValue({ newId: bad, confirmId: 'orders' });
            c.save();
            expect(c.form.controls.newId.hasError('pattern'), `${bad} must be refused`).toBe(true);
        }
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('says the migration moves the identity itself — the whole point of the dialog', async () => {
        const { fixture } = make();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('moves the identity itself');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
