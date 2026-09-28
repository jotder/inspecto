import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { BrandingService, Space, SpacesService, TimezoneSettingsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SpaceFormData, SpaceFormDialog } from './space-form.dialog';

const toastr = { success: vi.fn(), warning: vi.fn(), error: vi.fn() };
const NO_BRANDING = { logoDataUrl: null, caption: null, footerText: null };

function create(
    data: SpaceFormData | null = null,
    tzSave = vi.fn(() => of({ timezone: null, effectiveTimezone: 'UTC' })),
) {
    const getFor = vi.fn(() => of(NO_BRANDING));
    const saveFor = vi.fn(() => of(NO_BRANDING));
    const spaceSave = vi.fn(() => of({ id: 'beta', displayName: 'Beta', description: '', createdAt: '' }));
    const tzGetFor = vi.fn(() => of({ timezone: 'Asia/Kolkata', effectiveTimezone: 'Asia/Kolkata' }));
    TestBed.configureTestingModule({
        imports: [SpaceFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            {
                provide: SpacesService,
                useValue: { availableSpaces: signal([{ id: 'taken' }]), create: spaceSave, update: spaceSave },
            },
            { provide: BrandingService, useValue: { getFor, saveFor } },
            { provide: TimezoneSettingsService, useValue: { getFor: tzGetFor, saveFor: tzSave } },
            { provide: ToastrService, useValue: toastr },
        ],
    });
    const fixture = TestBed.createComponent(SpaceFormDialog);
    fixture.detectChanges();
    return { fixture, getFor, spaceSave, tzSave };
}

/** The `<mat-form-field>` hosting `control` inside `scope`, so a mat-error assertion is scoped to that one field. */
function fieldOf(el: HTMLElement, scope: string, control: string): HTMLElement {
    return el.querySelector(`${scope} [formControlName="${control}"]`)!.closest('mat-form-field') as HTMLElement;
}

describe('SpaceFormDialog', () => {
    it('derives a slug id from the display name (fixing the disabled-Create trap)', () => {
        const c = create().fixture.componentInstance;
        expect(c.form.invalid).toBe(true); // empty name
        c.form.patchValue({ display_name: 'My New Space' });
        expect(c.form.get('id')!.value).toBe('my-new-space');
        expect(c.form.valid).toBe(true); // a plain name now yields a valid, submittable form
    });

    it('blocks a duplicate id inline (case-insensitive) and a bad manual id', () => {
        const c = create().fixture.componentInstance;
        c.form.patchValue({ display_name: 'Anything' });
        c.form.patchValue({ id: 'taken' });
        expect(c.form.get('id')!.hasError('duplicate')).toBe(true);
        c.form.patchValue({ id: 'Bad Id' });
        expect(c.form.get('id')!.hasError('pattern')).toBe(true);
        c.form.patchValue({ id: 'fresh' });
        expect(c.form.valid).toBe(true);
    });

    it('edit mode prefills name + branding and drops the immutable id control', () => {
        const space: Space = { id: 'beta', displayName: 'Beta', description: 'd', createdAt: '' };
        const { fixture, getFor } = create({ space });
        const c = fixture.componentInstance;
        expect(c.editMode).toBe(true);
        expect(c.form.get('id')).toBeNull();
        expect(c.form.get('display_name')!.value).toBe('Beta');
        expect(getFor).toHaveBeenCalledWith('beta');
        expect(c.form.get('timezone')!.value).toBe('Asia/Kolkata'); // the Space default timezone is prefilled
    });

    it('an invalid timezone blocks submit inline — nothing is saved', () => {
        const space: Space = { id: 'beta', displayName: 'Beta', description: 'd', createdAt: '' };
        const { fixture, spaceSave, tzSave } = create({ space });
        const c = fixture.componentInstance;
        for (const bad of ['+05:30', 'Z', 'UTC+5', 'Mars/Olympus', 'asia/kolkata', 'utc', 'Asia/CALCUTTA']) {
            c.form.patchValue({ timezone: bad });
            expect(c.form.get('timezone')!.hasError('timezone')).toBe(true);
        }
        c.submit();
        fixture.detectChanges();
        expect(spaceSave).not.toHaveBeenCalled();
        expect(tzSave).not.toHaveBeenCalled();
        expect(fixture.nativeElement.textContent).toContain('Not an IANA timezone name');
        for (const ok of ['', 'UTC', 'Asia/Kolkata', 'Asia/Calcutta', 'EST5EDT']) {
            c.form.patchValue({ timezone: ok });
            expect(c.form.get('timezone')!.valid).toBe(true);
        }
    });

    it('create mode never PUTs an empty timezone', () => {
        const { fixture, spaceSave, tzSave } = create();
        const c = fixture.componentInstance;
        c.form.patchValue({ display_name: 'Beta' });
        c.submit();
        expect(spaceSave).toHaveBeenCalled();
        expect(tzSave).not.toHaveBeenCalled();
        expect(toastr.success).toHaveBeenCalled();
    });

    it('a refused timezone says the Space was saved and the timezone was not', () => {
        toastr.error.mockClear();
        const space: Space = { id: 'beta', displayName: 'Beta', description: 'd', createdAt: '' };
        const refused = vi.fn(() => throwError(() => ({ status: 403 })));
        const { fixture, spaceSave } = create({ space }, refused as never);
        const c = fixture.componentInstance;
        c.form.controls['timezone'].setValue('Europe/Paris');
        c.form.controls['timezone'].markAsDirty();
        c.submit();
        expect(spaceSave).toHaveBeenCalled();
        expect(refused).toHaveBeenCalledWith('beta', 'Europe/Paris');
        expect(toastr.warning).toHaveBeenCalledWith(
            expect.stringContaining('was saved, but its default timezone was not'),
        );
        expect(toastr.error).not.toHaveBeenCalled();
    });

    it('has no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the id pattern error only after submit — never before (the error lands in the subscript)', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.form.patchValue({ display_name: 'Anything' });
        c.editId.set(true);
        c.form.patchValue({ id: 'Bad Id' });
        fixture.detectChanges();
        const field = fieldOf(fixture.nativeElement, 'form', 'id');
        // Untouched: no error. The old outer "@if (…; as c)" wrapper mis-projected the <mat-error> into
        // the form field's DEFAULT slot, so it rendered beside the input before any touch/submit.
        expect(field.querySelector('mat-error')).toBeNull();
        expect(field.textContent).not.toContain('Use a–z, 0–9, hyphen; start with a letter or digit; max 63 chars.');
        c.submit();
        fixture.detectChanges();
        const err = field.querySelector('mat-error');
        expect(err?.textContent).toContain('Use a–z, 0–9, hyphen; start with a letter or digit; max 63 chars.');
        expect(err?.closest('.mat-mdc-form-field-subscript-wrapper')).not.toBeNull();
    });
});
