import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NEVER } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ResolveDialog, ResolveDialogData } from './resolve.dialog';

function create(data: ResolveDialogData) {
    const ref = { close: vi.fn(), disableClose: false, backdropClick: () => NEVER, keydownEvents: () => NEVER };
    TestBed.configureTestingModule({
        imports: [ResolveDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
            { provide: InspectoConfirmService, useValue: { confirm: vi.fn(() => Promise.resolve(true)) } },
        ],
    });
    const fixture = TestBed.createComponent(ResolveDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, el: fixture.nativeElement as HTMLElement };
}

describe('ResolveDialog', () => {
    it('asks an Incident for a Disposition and refuses to resolve without one (WS-10)', async () => {
        const { c, ref, fixture, el } = create({ count: 1, label: 'incident', askDisposition: true });
        expect(el.textContent).toContain('Disposition');
        c.form.controls.comment.setValue('feed restored');
        c.apply();
        fixture.detectChanges();
        expect(ref.close).not.toHaveBeenCalled();
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('A Disposition is required');
        await expectNoA11yViolations(fixture.nativeElement);

        c.form.controls.disposition.setValue('DUPLICATE');
        c.apply();
        expect(ref.close).toHaveBeenCalledWith({ comment: 'feed restored', disposition: 'DUPLICATE' });
    });

    it('asks a Case for the comment only — its Disposition lives in its Findings', () => {
        const { c, ref, el } = create({ count: 2, label: 'case' });
        expect(el.textContent).not.toContain('Disposition');
        c.form.controls.comment.setValue('done');
        c.apply();
        expect(ref.close).toHaveBeenCalledWith({ comment: 'done' });
    });
});
