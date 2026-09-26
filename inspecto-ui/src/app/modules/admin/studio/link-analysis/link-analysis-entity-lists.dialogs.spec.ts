import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService } from 'app/inspecto/api';
import { EntityTypeConfig } from 'app/inspecto/api/link-analysis-settings.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { CreateEntityListDialog, EntityListReasonDialog } from './link-analysis-entity-lists.dialogs';

const TYPES: EntityTypeConfig[] = [
    { id: 'msisdn', label: 'MSISDN', normaliser: 'e164', masked: true, classifications: [] },
    { id: 'imei', label: 'IMEI', normaliser: 'digits', masked: false, classifications: [] },
];

function configure(data: unknown, inv: Partial<Record<keyof InvService, unknown>> = {}) {
    const close = vi.fn();
    TestBed.configureTestingModule({
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: { close } },
            { provide: InvService, useValue: inv },
        ],
    });
    return close;
}

const http = (status: number, message: string) => () =>
    throwError(() => new HttpErrorResponse({ status, error: { error: { message } } }));

function type(el: HTMLElement, selector: string, value: string) {
    const input = el.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
}

describe('CreateEntityListDialog (LA-17)', () => {
    it('refuses an empty form on screen: every required field names itself, nothing is sent', async () => {
        const createEntityList = vi.fn();
        const close = configure({ entityTypes: TYPES }, { createEntityList });
        const f = TestBed.createComponent(CreateEntityListDialog);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        expect(el.querySelector('mat-error')).toBeNull(); // a fresh form does not open red
        expect(el.querySelector('[role="alert"]')).toBeNull();
        await expectNoA11yViolations(el);

        f.componentInstance.save();
        f.detectChanges();
        const errors = Array.from(el.querySelectorAll('.mat-mdc-form-field-subscript-wrapper mat-error')).map(
            (e) => e.textContent,
        );
        expect(errors).toEqual([
            expect.stringContaining('A title is required'),
            expect.stringContaining('A reason is required'),
        ]);
        const pickers = Array.from(el.querySelectorAll('p[role="alert"]')).map((e) => e.textContent);
        expect(pickers).toEqual([expect.stringContaining('Choose a purpose'), expect.stringContaining('Entity Type')]);
        expect(createEntityList).not.toHaveBeenCalled();
        expect(close).not.toHaveBeenCalled();

        // A whitespace-only reason is still no reason.
        type(el, 'input[formcontrolname="reason"]', '   ');
        f.detectChanges();
        expect(f.componentInstance.form.controls.reason.invalid).toBe(true);
    });

    it('creates with the trimmed body and closes with the list', async () => {
        const created = { id: 'l-1', title: 'Mules' };
        const createEntityList = vi.fn(() => of(created));
        const close = configure({ entityTypes: TYPES }, { createEntityList });
        const f = TestBed.createComponent(CreateEntityListDialog);
        f.detectChanges();
        f.componentInstance.form.setValue({
            title: ' Mules ',
            purpose: 'block',
            entityType: 'imei',
            reason: ' case 7 ',
        });
        await f.componentInstance.save();
        expect(createEntityList).toHaveBeenCalledWith({
            title: 'Mules',
            purpose: 'block',
            entityType: 'imei',
            reason: 'case 7',
        });
        expect(close).toHaveBeenCalledWith(created);
    });

    it('keeps a 409 in the dialog with the server reason, and explains a 503 as info', async () => {
        const createEntityList = vi.fn(http(409, 'id already used'));
        const close = configure({ entityTypes: TYPES }, { createEntityList });
        const f = TestBed.createComponent(CreateEntityListDialog);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        f.componentInstance.form.setValue({ title: 'M', purpose: 'watch', entityType: 'msisdn', reason: 'r' });
        await f.componentInstance.save();
        f.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(el.querySelector('inspecto-alert [role="alert"]')?.textContent).toContain('Refused — id already used');

        createEntityList.mockImplementation(http(503, 'not installed'));
        await f.componentInstance.save();
        f.detectChanges();
        expect(el.querySelector('inspecto-alert [role="status"]')?.textContent).toContain(
            'Entity Lists are not available here',
        );
    });

    it('warns when no Entity Type is in force', () => {
        configure({ entityTypes: [] });
        const f = TestBed.createComponent(CreateEntityListDialog);
        f.detectChanges();
        expect((f.nativeElement as HTMLElement).textContent).toContain('No Entity Types are in force');
    });
});

describe('EntityListReasonDialog (LA-17)', () => {
    it('requires a reason, then closes with it trimmed; a destructive prompt confirms in warn', async () => {
        const close = configure({
            title: 'Retire Entity List',
            message: 'Retire “Mules”?',
            confirmLabel: 'Retire',
            destructive: true,
            maxLength: 1000,
        });
        const f = TestBed.createComponent(EntityListReasonDialog);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        expect(el.textContent).toContain('Retire “Mules”?');
        expect(el.querySelector('mat-error')).toBeNull();
        await expectNoA11yViolations(el);

        const confirm = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Retire')!;
        expect(confirm.classList.contains('mat-warn')).toBe(true);
        confirm.click();
        f.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(el.querySelector('.mat-mdc-form-field-subscript-wrapper mat-error')?.textContent).toContain(
            'A reason is required',
        );

        type(el, 'input', '  superseded  ');
        f.detectChanges();
        confirm.click();
        expect(close).toHaveBeenCalledWith('superseded');
    });

    it('honours the per-action bound (an exclusion’s 200)', () => {
        const close = configure({ title: 'Exclude', message: 'm', confirmLabel: 'Exclude', maxLength: 200 });
        const f = TestBed.createComponent(EntityListReasonDialog);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        type(el, 'input', 'x'.repeat(201));
        f.componentInstance.submit();
        f.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(el.querySelector('mat-error')?.textContent).toContain('At most 200 characters');
    });
});
