import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { BrandingService, Space, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SpaceFormData, SpaceFormDialog } from './space-form.dialog';

const NO_BRANDING = { logoDataUrl: null, caption: null, footerText: null };

function create(data: SpaceFormData | null = null) {
    const getFor = vi.fn(() => of(NO_BRANDING));
    const saveFor = vi.fn(() => of(NO_BRANDING));
    TestBed.configureTestingModule({
        imports: [SpaceFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: SpacesService, useValue: { availableSpaces: signal([{ id: 'taken' }]) } },
            { provide: BrandingService, useValue: { getFor, saveFor } },
            { provide: ToastrService, useValue: {} },
        ],
    });
    const fixture = TestBed.createComponent(SpaceFormDialog);
    fixture.detectChanges();
    return { fixture, getFor };
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
