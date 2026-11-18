import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { Space, SpaceLocationCheckResult, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { penByLabel, penEdit } from 'app/inspecto/testing/pen-field';
import { AttachSpaceDialog } from './attach-space.dialog';

const DELTA: Space = { id: 'delta', displayName: 'Delta', description: '', createdAt: '' };

function create(check: () => Observable<SpaceLocationCheckResult>, disabled = false) {
    const locationsDisabled = signal(disabled);
    const checkLocation = vi.fn(check);
    const attach = vi.fn(() => of(DELTA));
    const close = vi.fn();
    TestBed.configureTestingModule({
        imports: [AttachSpaceDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close } },
            { provide: SpacesService, useValue: { locationsDisabled, checkLocation, attach } },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(AttachSpaceDialog);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const save = () => el.querySelector('[data-testid="attach-save"]') as HTMLButtonElement;
    const checkBtn = () => el.querySelector('[data-testid="attach-check"]') as HTMLButtonElement;
    return { fixture, el, save, checkBtn, checkLocation, attach, close, locationsDisabled };
}

describe('AttachSpaceDialog', () => {
    it('lists the passed checks and enables Attach, which attaches the folder', async () => {
        const { fixture, el, save, checkBtn, checkLocation, attach, close } = create(() =>
            of({ ok: true, checks: [{ check: 'content', ok: true, reason: 'space.toon found' }] }),
        );
        expect(save().disabled).toBe(true);
        expect(checkBtn().disabled).toBe(true);
        penEdit(fixture, penByLabel(el, 'Space folder')!, '/srv/elsewhere/delta');
        penEdit(fixture, penByLabel(el, 'Id')!, 'delta');
        expect(save().disabled).toBe(true);
        checkBtn().click();
        fixture.detectChanges();
        expect(checkLocation).toHaveBeenCalledWith('/srv/elsewhere/delta', 'space', 'delta');
        expect(el.textContent).toContain('every check passed.');
        expect(el.textContent).toContain('space.toon found');
        expect(save().disabled).toBe(false);
        await expectNoA11yViolations(el);

        save().click();
        fixture.detectChanges();
        expect(attach).toHaveBeenCalledWith('/srv/elsewhere/delta', 'delta');
        expect(close).toHaveBeenCalledWith(DELTA);
    });

    it('a failed check keeps Attach disabled and shows each reason', () => {
        const { fixture, el, save, checkBtn, attach } = create(() =>
            of({
                ok: false,
                checks: [
                    { check: 'allowed', ok: false, reason: 'Not under an allowed folder.' },
                    { check: 'content', ok: false, reason: 'No space.toon in the folder.' },
                ],
            }),
        );
        penEdit(fixture, penByLabel(el, 'Space folder')!, '/etc/delta');
        checkBtn().click();
        fixture.detectChanges();
        expect(el.textContent).toContain('2 checks failed.');
        expect(el.textContent).toContain('Not under an allowed folder.');
        expect(save().disabled).toBe(true);
        fixture.componentInstance.submit();
        expect(attach).not.toHaveBeenCalled();
    });

    it('an edit after a passed check disables Attach again', () => {
        const { fixture, el, save, checkBtn } = create(() => of({ ok: true, checks: [] }));
        penEdit(fixture, penByLabel(el, 'Space folder')!, '/srv/elsewhere/delta');
        checkBtn().click();
        fixture.detectChanges();
        expect(save().disabled).toBe(false);
        penEdit(fixture, penByLabel(el, 'Space folder')!, '/srv/elsewhere/other');
        expect(save().disabled).toBe(true);
    });

    it('on a server with no allowed folders it says what to set and offers no Attach', async () => {
        let ctx: ReturnType<typeof create> | null = null;
        ctx = create(() => {
            ctx!.locationsDisabled.set(true);
            return throwError(() => new HttpErrorResponse({ status: 409 }));
        });
        const { fixture, el, checkBtn } = ctx;
        penEdit(fixture, penByLabel(el, 'Space folder')!, '/srv/elsewhere/delta');
        checkBtn().click();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="locations-disabled"]')?.textContent).toContain('SPACES_ALLOWED_ROOTS');
        expect(el.querySelector('[data-testid="attach-save"]')).toBeNull();
        expect(el.querySelector('[data-testid="attach-check"]')).toBeNull();
        await expectNoA11yViolations(el);
    });
});
