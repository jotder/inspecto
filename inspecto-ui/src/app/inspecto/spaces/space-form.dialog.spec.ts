import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { BrandingService, Space, SpacesService, TimezoneSettingsService } from 'app/inspecto/api';
import { SessionService } from 'app/inspecto/auth/session.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { penByLabel, penEdit, penError, refresh } from 'app/inspecto/testing/pen-field';
import { SpaceFormData, SpaceFormDialog } from './space-form.dialog';

const toastr = { success: vi.fn(), warning: vi.fn(), error: vi.fn() };
const NO_BRANDING = { logoDataUrl: null, caption: null, footerText: null };

function create(
    data: SpaceFormData | null = null,
    tzSave = vi.fn(() => of({ timezone: null, effectiveTimezone: 'UTC' })),
    /** Under OIDC, the subject's effective capabilities; omitted = no sign-in (every capability). */
    capabilities?: string[],
    /** A multi-Space server (SPACE-LOCATIONS-1: offers Advanced: folders) and its folder check. */
    folders: { multi?: boolean; checkLocation?: () => Observable<unknown> } = {},
) {
    const close = vi.fn();
    const getFor = vi.fn(() => of(NO_BRANDING));
    const saveFor = vi.fn((): Observable<unknown> => of(NO_BRANDING));
    const spaceSave = vi.fn(() => of({ id: 'beta', displayName: 'Beta', description: '', createdAt: '' }));
    const tzGetFor = vi.fn(() => of({ timezone: 'Asia/Kolkata', effectiveTimezone: 'Asia/Kolkata' }));
    const domainSave = vi.fn(() => of({}));
    TestBed.configureTestingModule({
        imports: [SpaceFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            {
                provide: SpacesService,
                useValue: {
                    availableSpaces: signal([{ id: 'taken' }]),
                    currentSpaceId: signal<string | null>(null), // read by SessionService, behind LensService
                    create: spaceSave,
                    update: spaceSave,
                    setDomainProfile: domainSave,
                    multiSpace: signal(folders.multi ?? false),
                    locationsDisabled: signal(false),
                    checkLocation: vi.fn(folders.checkLocation ?? (() => of({ ok: true, checks: [] }))),
                },
            },
            { provide: BrandingService, useValue: { getFor, saveFor } },
            { provide: TimezoneSettingsService, useValue: { getFor: tzGetFor, saveFor: tzSave } },
            { provide: ToastrService, useValue: toastr },
        ],
    });
    if (capabilities) {
        const session = TestBed.inject(SessionService);
        session.authMode.set('oidc');
        session.capabilities.set(capabilities);
    }
    const fixture = TestBed.createComponent(SpaceFormDialog);
    fixture.detectChanges();
    return { fixture, close, getFor, saveFor, spaceSave, tzSave, domainSave };
}

/** The `<inspecto-pen-field>` row bound to `control` inside `scope`, so an error assertion is scoped to that one row. */
function fieldOf(el: HTMLElement, scope: string, control: string): HTMLElement {
    return el.querySelector(`${scope} inspecto-pen-field[formControlName="${control}"]`) as HTMLElement;
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
        refresh(fixture);
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

    // SPACE-CREATE-PARTIAL-1: POST /spaces succeeded, the branding PUT was refused, and the dialog stayed open on
    // only the refusal — so the operator believed the create had failed while the Space existed.
    it('a refused branding names that step and still closes on the created Space', () => {
        toastr.error.mockClear();
        toastr.warning.mockClear();
        const { fixture, close, saveFor } = create();
        saveFor.mockReturnValue(
            throwError(() => ({ status: 403, error: { error: "missing capability 'canAuthorWorkbench'" } })),
        );
        const c = fixture.componentInstance;
        c.form.patchValue({ display_name: 'Beta', caption: 'Our data' });
        c.submit();
        expect(saveFor).toHaveBeenCalledWith('beta', expect.objectContaining({ caption: 'Our data' }));
        expect(toastr.warning).toHaveBeenCalledWith(
            expect.stringContaining('Space "beta" was created, but its branding was not saved'),
        );
        expect(toastr.error).not.toHaveBeenCalled();
        expect(close).toHaveBeenCalledWith(expect.objectContaining({ id: 'beta' }));
    });

    it('a caller without canAuthorWorkbench is not offered branding or timezone, and the create sends none', async () => {
        toastr.success.mockClear();
        const { fixture, close, saveFor, tzSave } = create(null, undefined, ['canAdminister']); // the seeded admin
        const el = fixture.nativeElement as HTMLElement;
        for (const gone of ['caption', 'footerText', 'timezone'])
            expect(el.querySelector(`[formControlName="${gone}"]`)).toBeNull();
        expect(el.textContent).not.toContain('Upload');
        expect(el.textContent).toContain('canAuthorWorkbench');
        await expectNoA11yViolations(el);
        const c = fixture.componentInstance;
        c.form.patchValue({ display_name: 'Beta' });
        c.submit();
        expect(saveFor).not.toHaveBeenCalled();
        expect(tzSave).not.toHaveBeenCalled();
        expect(toastr.success).toHaveBeenCalledWith('Space "beta" created');
        expect(close).toHaveBeenCalledWith(expect.objectContaining({ id: 'beta' }));
    });

    it('has no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    describe('the Inspecto LA create (dataKind, UC-02)', () => {
        it('asks the name and What kind of data? only: three kinds with example columns (no violations)', async () => {
            const { fixture } = create({ dataKind: true });
            const el = fixture.nativeElement as HTMLElement;
            expect(el.querySelector('h2')!.textContent).toContain('Create a Space');
            expect(el.textContent).toContain('What kind of data?');
            const radios = Array.from(el.querySelectorAll('mat-radio-button'));
            expect(radios.map((r) => r.textContent!.replace(/\s+/g, ' ').trim())).toEqual([
                'Mobile Money Columns such as sender_wallet, receiver_wallet, amount',
                'Telecom Columns such as calling_number, called_number, call_duration',
                'Generic Columns such as from_id, to_id, date',
            ]);
            // Ask the minimum: no description, branding or timezone.
            for (const gone of ['description', 'caption', 'footerText', 'timezone'])
                expect(el.querySelector(`[formControlName="${gone}"]`)).toBeNull();
            expect(el.textContent).not.toContain('Logo');
            await expectNoA11yViolations(el);
        });

        it('Name is a labelled text box from the start, not a row reading “—”: typing fills the id and enables Create', () => {
            const { fixture } = create({ dataKind: true });
            const el = fixture.nativeElement as HTMLElement;
            const name = penByLabel(el, 'Name')!;
            const box = name.querySelector('input')!;
            expect(box).not.toBeNull();
            expect(box.getAttribute('aria-label')).toBe('Name');
            expect(box.getAttribute('aria-required')).toBe('true');
            expect(name.querySelector('button')).toBeNull(); // no value to click and no pencil
            expect(name.textContent).not.toContain('—');
            const createButton = el.querySelector<HTMLButtonElement>('button[type="submit"]')!;
            expect(createButton.disabled).toBe(true);

            box.value = 'Mobile money';
            box.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            expect(fixture.componentInstance.form.get('display_name')!.value).toBe('Mobile money');
            expect(el.textContent).toContain('mobile-money'); // the id follows as it is typed
            expect(createButton.disabled).toBe(false);
            expect(penError(name)).toBe('');

            // emptied and left: the row says what is missing, and Create is off again
            box.value = '';
            box.dispatchEvent(new Event('input'));
            box.dispatchEvent(new Event('blur'));
            fixture.detectChanges();
            expect(penError(name)).toBe('A name is required.');
            expect(createButton.disabled).toBe(true);
        });

        it('the console’s New space keeps its property row for the name', () => {
            const { fixture } = create();
            const name = penByLabel(fixture.nativeElement as HTMLElement, 'Display name')!;
            expect(name.querySelector('input')).toBeNull();
            expect(name.querySelector('button')).not.toBeNull();
        });

        it("sets the chosen Industry pack as the new Space's Domain Profile, and writes no branding", () => {
            const { fixture, spaceSave, domainSave, saveFor } = create({ dataKind: true });
            const c = fixture.componentInstance;
            c.form.patchValue({ display_name: 'Call records', dataKind: 'telecom' });
            c.submit();
            expect(spaceSave).toHaveBeenCalledWith(expect.objectContaining({ id: 'call-records' }));
            expect(domainSave).toHaveBeenCalledWith('beta', 'telecom');
            expect(saveFor).not.toHaveBeenCalled();
        });

        it('Generic leaves the Domain Profile unset', () => {
            const { fixture, domainSave } = create({ dataKind: true });
            const c = fixture.componentInstance;
            c.form.patchValue({ display_name: 'Things' });
            expect(c.form.get('dataKind')!.value).toBe('generic');
            c.submit();
            expect(domainSave).not.toHaveBeenCalled();
        });

        it('a refused pack says the Space was created but its kind of data was not set', () => {
            toastr.warning.mockClear();
            const { fixture, domainSave } = create({ dataKind: true });
            domainSave.mockReturnValue(throwError(() => ({ status: 422 })) as never);
            const c = fixture.componentInstance;
            c.form.patchValue({ display_name: 'Wallets', dataKind: 'mobile-money' });
            c.submit();
            expect(toastr.warning).toHaveBeenCalledWith(expect.stringContaining('its kind of data was not set'));
        });

        it('is ignored in edit mode', () => {
            const space: Space = { id: 'beta', displayName: 'Beta', description: 'd', createdAt: '' };
            const { fixture } = create({ space, dataKind: true });
            expect(fixture.componentInstance.dataKind).toBe(false);
            expect(fixture.nativeElement.textContent).not.toContain('What kind of data?');
        });
    });

    it("shows the id pattern error only after submit — never before (the error lands in the row's alert line)", () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        c.form.patchValue({ display_name: 'Anything' });
        c.editId.set(true);
        c.form.patchValue({ id: 'Bad Id' });
        refresh(fixture);
        const field = fieldOf(fixture.nativeElement, 'form', 'id');
        // Untouched: no error. The old outer "@if (…; as c)" wrapper mis-projected the <mat-error> into
        // the form field's DEFAULT slot, so it rendered beside the input before any touch/submit.
        expect(penError(field)).toBe('');
        expect(field.textContent).not.toContain('Use a–z, 0–9, hyphen; start with a letter or digit; max 63 chars.');
        c.submit();
        refresh(fixture);
        expect(penError(field)).toBe('Use a–z, 0–9, hyphen; start with a letter or digit; max 63 chars.');
    });

    it('a committed id stops the name from re-deriving it; until then the name drives it', () => {
        const { fixture } = create();
        const c = fixture.componentInstance;
        const el = fixture.nativeElement as HTMLElement;
        penEdit(fixture, penByLabel(el, 'Display name')!, 'First name');
        expect(c.form.get('id')!.value).toBe('first-name');
        c.editId.set(true);
        refresh(fixture);
        penEdit(fixture, fieldOf(el, 'form', 'id'), 'my-own-id');
        penEdit(fixture, penByLabel(el, 'Display name')!, 'Second name');
        expect(c.form.get('id')!.value).toBe('my-own-id');
    });

    // ── SPACE-LOCATIONS-1 D6: Advanced: folders ──────────────────────────────────

    it('offers Advanced: folders on a multi-Space create only, never on edit or a single-Space server', () => {
        const q = '[data-testid="folders-toggle"]';
        expect(
            (create(null, undefined, undefined, { multi: true }).fixture.nativeElement as HTMLElement).querySelector(q),
        ).not.toBeNull();
        TestBed.resetTestingModule();
        expect((create().fixture.nativeElement as HTMLElement).querySelector(q)).toBeNull();
        TestBed.resetTestingModule();
        const space: Space = { id: 'beta', displayName: 'Beta', description: '', createdAt: '' };
        expect(
            (
                create({ space }, undefined, undefined, { multi: true }).fixture.nativeElement as HTMLElement
            ).querySelector(q),
        ).toBeNull();
    });

    it('keeps Create disabled until a given folder passed its check, then sends it', async () => {
        const { fixture, spaceSave } = create(null, undefined, undefined, { multi: true });
        const el = fixture.nativeElement as HTMLElement;
        const createBtn = () => el.querySelector('button[type="submit"]') as HTMLButtonElement;
        penEdit(fixture, penByLabel(el, 'Display name')!, 'Delta');
        (el.querySelector('[data-testid="folders-toggle"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        penEdit(fixture, penByLabel(el, 'Space folder')!, '/srv/spaces/delta');
        expect(createBtn().disabled).toBe(true);
        (el.querySelector('[data-testid="folders-check"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(createBtn().disabled).toBe(false);
        await expectNoA11yViolations(el);
        createBtn().click();
        fixture.detectChanges();
        expect(spaceSave).toHaveBeenCalledWith(expect.objectContaining({ id: 'delta', path: '/srv/spaces/delta' }));
    });

    it('a failed folder check keeps Create disabled', () => {
        const { fixture, spaceSave } = create(null, undefined, undefined, {
            multi: true,
            checkLocation: () => of({ ok: false, checks: [{ check: 'content', ok: false, reason: 'Holds files.' }] }),
        });
        const el = fixture.nativeElement as HTMLElement;
        penEdit(fixture, penByLabel(el, 'Display name')!, 'Delta');
        (el.querySelector('[data-testid="folders-toggle"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        penEdit(fixture, penByLabel(el, 'Data folder')!, '/srv/data/full');
        (el.querySelector('[data-testid="folders-check"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect((el.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(true);
        fixture.componentInstance.submit();
        expect(spaceSave).not.toHaveBeenCalled();
    });
});
