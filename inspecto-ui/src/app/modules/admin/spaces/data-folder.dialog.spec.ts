import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { Space, SpaceLocationCheckResult, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { penByLabel, penEdit, refresh } from 'app/inspecto/testing/pen-field';
import { DataFolderDialog } from './data-folder.dialog';

const ALPHA: Space = {
    id: 'alpha',
    displayName: 'Alpha',
    description: '',
    createdAt: '',
    location: { spaceFolder: '/srv/spaces/alpha', dataFolder: '/srv/spaces/alpha/data', holdsData: false },
};

function create(
    check: () => Observable<SpaceLocationCheckResult>,
    setDataRoot: () => Observable<Space> = () => of(ALPHA),
) {
    const locationsDisabled = signal(false);
    const checkLocation = vi.fn(check);
    const setRoot = vi.fn(setDataRoot);
    const close = vi.fn();
    TestBed.configureTestingModule({
        imports: [DataFolderDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: { space: ALPHA } },
            { provide: SpacesService, useValue: { locationsDisabled, checkLocation, setDataRoot: setRoot } },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(DataFolderDialog);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const save = () => el.querySelector('[data-testid="data-save"]') as HTMLButtonElement;
    const checkBtn = () => el.querySelector('[data-testid="data-check"]') as HTMLButtonElement;
    return { fixture, el, save, checkBtn, checkLocation, setRoot, close, locationsDisabled };
}

describe('DataFolderDialog', () => {
    it('checks a new Data folder for this Space and saves it once every check passed', async () => {
        const { fixture, el, save, checkBtn, checkLocation, setRoot, close } = create(() =>
            of({ ok: true, checks: [{ check: 'content', ok: true }] }),
        );
        expect(el.textContent).toContain('/srv/spaces/alpha/data');
        expect(save().disabled).toBe(true);
        penEdit(fixture, penByLabel(el, 'Data folder')!, '/srv/data/alpha');
        checkBtn().click();
        fixture.detectChanges();
        expect(checkLocation).toHaveBeenCalledWith('/srv/data/alpha', 'data', 'alpha');
        expect(save().disabled).toBe(false);
        await expectNoA11yViolations(el);
        save().click();
        fixture.detectChanges();
        expect(setRoot).toHaveBeenCalledWith('alpha', '/srv/data/alpha');
        expect(close).toHaveBeenCalledWith(ALPHA);
    });

    it('a failed check keeps Save disabled', () => {
        const { fixture, el, save, checkBtn, setRoot } = create(() =>
            of({ ok: false, checks: [{ check: 'overlap', ok: false, reason: 'Inside Space beta.' }] }),
        );
        penEdit(fixture, penByLabel(el, 'Data folder')!, '/srv/spaces/beta/data');
        checkBtn().click();
        fixture.detectChanges();
        expect(el.textContent).toContain('Inside Space beta.');
        expect(save().disabled).toBe(true);
        fixture.componentInstance.submit();
        expect(setRoot).not.toHaveBeenCalled();
    });

    it('puts the data back in the Space folder without a check', () => {
        const { fixture, save, setRoot, checkLocation } = create(() => of({ ok: true, checks: [] }));
        fixture.componentInstance.form.controls.where.setValue('space');
        refresh(fixture);
        expect(save().disabled).toBe(false);
        save().click();
        fixture.detectChanges();
        expect(checkLocation).not.toHaveBeenCalled();
        expect(setRoot).toHaveBeenCalledWith('alpha', null);
    });

    it("shows the server's refusal when the Space already holds data", () => {
        const { fixture, el, save, close } = create(
            () => of({ ok: true, checks: [] }),
            () =>
                throwError(
                    () =>
                        new HttpErrorResponse({
                            status: 409,
                            error: { error: { errorCode: 'SPACE_HOLDS_DATA', message: 'The Space holds data.' } },
                        }),
                ),
        );
        fixture.componentInstance.form.controls.where.setValue('space');
        refresh(fixture);
        save().click();
        fixture.detectChanges();
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('The Space holds data.');
        expect(close).not.toHaveBeenCalled();
    });

    it('on a server with no allowed folders it says what to set and offers no Save', async () => {
        let ctx: ReturnType<typeof create> | null = null;
        ctx = create(() => {
            ctx!.locationsDisabled.set(true);
            return throwError(() => new HttpErrorResponse({ status: 409 }));
        });
        const { fixture, el, checkBtn } = ctx;
        penEdit(fixture, penByLabel(el, 'Data folder')!, '/srv/data/alpha');
        checkBtn().click();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="locations-disabled"]')?.textContent).toContain('spaces.allowed.roots');
        expect(el.querySelector('[data-testid="data-save"]')).toBeNull();
        await expectNoA11yViolations(el);
    });
});
