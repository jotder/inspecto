import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { SpaceLocationCheckResult, SpaceLocationKind, SpacesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { penByLabel, penEdit } from 'app/inspecto/testing/pen-field';
import { SpaceFolderChoice, SpaceFoldersComponent } from './space-folders.component';

@Component({
    standalone: true,
    imports: [SpaceFoldersComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<app-space-folders [spaceId]="id" (changed)="last = $event" />`,
})
class HostComponent {
    id = 'delta';
    last: SpaceFolderChoice | null = null;
}

const PASS: SpaceLocationCheckResult = { ok: true, checks: [{ check: 'allowed', ok: true }] };
const FAIL: SpaceLocationCheckResult = {
    ok: false,
    checks: [
        { check: 'allowed', ok: true },
        { check: 'content', ok: false, reason: 'The folder holds 3 files.' },
    ],
};

function create(check: (path: string, kind: SpaceLocationKind, id?: string) => Observable<SpaceLocationCheckResult>) {
    const locationsDisabled = signal(false);
    const checkLocation = vi.fn(check);
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            { provide: SpacesService, useValue: { locationsDisabled, checkLocation } },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('[data-testid="folders-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    return { fixture, el, checkLocation, locationsDisabled };
}

describe('SpaceFoldersComponent', () => {
    it('is ready with no folder given, and sends none', async () => {
        const { fixture, el } = create(() => of(PASS));
        expect(fixture.componentInstance.last).toEqual({ ready: true });
        expect((el.querySelector('[data-testid="folders-check"]') as HTMLButtonElement).disabled).toBe(true);
        await expectNoA11yViolations(el);
    });

    it('a folder given is not ready until its check passes, then sends it', async () => {
        const { fixture, el, checkLocation } = create((_p, kind) => of(kind === 'space' ? PASS : PASS));
        penEdit(fixture, penByLabel(el, 'Space folder')!, '/srv/spaces/delta');
        penEdit(fixture, penByLabel(el, 'Data folder')!, '/srv/data/delta');
        expect(fixture.componentInstance.last?.ready).toBe(false);
        expect(el.querySelector('[data-testid="folders-unchecked"]')).not.toBeNull();

        (el.querySelector('[data-testid="folders-check"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(checkLocation).toHaveBeenCalledWith('/srv/spaces/delta', 'space', 'delta');
        expect(checkLocation).toHaveBeenCalledWith('/srv/data/delta', 'data', 'delta');
        expect(fixture.componentInstance.last).toEqual({
            path: '/srv/spaces/delta',
            dataRoot: '/srv/data/delta',
            ready: true,
        });
        expect(el.querySelectorAll('[data-testid="location-checks"]').length).toBe(2);
        await expectNoA11yViolations(el);
    });

    it('a failed check keeps it not ready and shows the reason', () => {
        const { fixture, el } = create(() => of(FAIL));
        penEdit(fixture, penByLabel(el, 'Data folder')!, '/srv/data/full');
        (el.querySelector('[data-testid="folders-check"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(fixture.componentInstance.last?.ready).toBe(false);
        expect(el.textContent).toContain('1 check failed.');
        expect(el.textContent).toContain('The folder holds 3 files.');
        expect(el.querySelector('li[data-ok="false"]')?.textContent).toContain('Failed');
        // check ids read in plain words
        expect(el.querySelector('li[data-ok="false"]')?.textContent).toContain('Holds what it should');
        expect(el.querySelector('li[data-ok="true"]')?.textContent).toContain('In a folder the server allows');
    });

    it('an edit after a passed check asks for a new check', () => {
        const { fixture, el } = create(() => of(PASS));
        penEdit(fixture, penByLabel(el, 'Space folder')!, '/srv/spaces/delta');
        (el.querySelector('[data-testid="folders-check"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(fixture.componentInstance.last?.ready).toBe(true);
        penEdit(fixture, penByLabel(el, 'Space folder')!, '/srv/spaces/other');
        expect(fixture.componentInstance.last?.ready).toBe(false);
    });

    it('when the server has no allowed folders it says what to set and sends no folder', async () => {
        let disabled: ReturnType<typeof signal<boolean>> | null = null;
        const ctx = create(() => {
            disabled!.set(true);
            return throwError(() => new HttpErrorResponse({ status: 409 }));
        });
        disabled = ctx.locationsDisabled;
        const { fixture, el } = ctx;
        penEdit(fixture, penByLabel(el, 'Space folder')!, '/srv/spaces/delta');
        (el.querySelector('[data-testid="folders-check"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="locations-disabled"]')?.textContent).toContain('spaces.allowed.roots');
        expect(el.querySelector('[data-testid="folders-check"]')).toBeNull();
        expect(fixture.componentInstance.last).toEqual({ ready: true });
        await expectNoA11yViolations(el);
    });
});
