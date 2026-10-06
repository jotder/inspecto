import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { EntityTypeConfig } from './link-analysis-settings.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    CreateEntityListDialog,
    EntityListEntriesDialog,
    EntityListReasonDialog,
} from './link-analysis-entity-lists.dialogs';

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

describe('EntityListEntriesDialog (ASSURE-ENTITY-LISTS-RESIDUALS-1 (2))', () => {
    const LIST = { id: 'mules', title: 'Mules', purpose: 'watch', entityType: 'msisdn', size: 2 };
    const DETAIL = {
        ...LIST,
        members: ['a', 'b'],
        ranges: ['prefix:+4478'],
        expiring: [{ entry: 'a', kind: 'key', expiresAt: '2026-01-01T00:00:00Z', expired: true }],
    };

    it('shows the current range and expiring entries, and refuses bad lines on screen without sending', async () => {
        const changeEntityListMembers = vi.fn();
        const close = configure(
            { list: LIST, typeLabel: 'MSISDN' },
            { getEntityList: () => of(DETAIL), changeEntityListMembers },
        );
        const f = TestBed.createComponent(EntityListEntriesDialog);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        expect(el.querySelector('[aria-label="Range entries"]')?.textContent).toContain('prefix:+4478');
        expect(el.querySelector('[aria-label="Expiring entries"]')?.textContent).toContain('Expired');
        expect(el.querySelector('[role="alert"]')).toBeNull();
        await expectNoA11yViolations(el);

        type(el, 'textarea', '44785..447899');
        type(el, 'input[formcontrolname="expires"]', '2000-01-01T00:00');
        type(el, 'input[formcontrolname="reason"]', 'case 7');
        await f.componentInstance.save();
        f.detectChanges();
        const alerts = Array.from(el.querySelectorAll('[role="alert"]')).map((e) => e.textContent);
        expect(alerts).toEqual([expect.stringContaining('same length'), expect.stringContaining('in the future')]);
        expect(changeEntityListMembers).not.toHaveBeenCalled();
        expect(close).not.toHaveBeenCalled();
    });

    it('sends keys and ranges with the expiry on add, and closes with the held 202 body', async () => {
        const held = { status: 'pending', written: false, pendingChange: { id: 'pc-20261006120000-abcdef' } };
        const changeEntityListMembers = vi.fn(() => of(held));
        const close = configure(
            { list: LIST, typeLabel: 'MSISDN' },
            { getEntityList: () => of(DETAIL), changeEntityListMembers },
        );
        const f = TestBed.createComponent(EntityListEntriesDialog);
        f.detectChanges();
        const el = f.nativeElement as HTMLElement;
        type(el, 'textarea', '+447700900123\n+4478*\n10.1.0.0/16');
        type(el, 'input[formcontrolname="expires"]', '2999-01-01T00:00');
        type(el, 'input[formcontrolname="reason"]', ' case 7 ');
        await f.componentInstance.save();
        expect(changeEntityListMembers).toHaveBeenCalledWith('mules', {
            add: ['+447700900123'],
            addRanges: [{ prefix: '+4478' }, { cidr: '10.1.0.0/16' }],
            reason: 'case 7',
            expiresAt: new Date('2999-01-01T00:00').toISOString(),
        });
        expect(close).toHaveBeenCalledWith(held);
    });

    it('a remove sends removeRanges and never an expiry; a 422 stays in the dialog', async () => {
        const changeEntityListMembers = vi.fn(http(422, 'host bits set: network is 10.1.0.0/16'));
        const close = configure(
            { list: LIST, typeLabel: 'MSISDN' },
            { getEntityList: () => throwError(() => new Error('x')), changeEntityListMembers },
        );
        const f = TestBed.createComponent(EntityListEntriesDialog);
        f.componentInstance.form.patchValue({
            mode: 'remove',
            entries: '10.1.2.0/16',
            expires: '2999-01-01T00:00',
            reason: 'r',
        });
        await f.componentInstance.save();
        f.detectChanges();
        expect(changeEntityListMembers).toHaveBeenCalledWith('mules', {
            remove: [],
            removeRanges: [{ cidr: '10.1.2.0/16' }],
            reason: 'r',
        });
        expect(close).not.toHaveBeenCalled();
        expect((f.nativeElement as HTMLElement).textContent).toContain('host bits set');
    });
});
