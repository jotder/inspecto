import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router } from '@angular/router';
import { SessionService } from 'app/inspecto/api';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SignInComponent } from './sign-in.component';

const FAILED_KEY = 'inspecto.signInFailed';

/**
 * Behaviour spec for the Standard-edition sign-in screen (SIGN-IN-NO-SPEC-1). The sibling
 * `sign-in.a11y.spec.ts` covers rendering, branding and the version line; this one covers the two
 * things the component itself DECIDES: the `ngOnInit` bounce when no login is required, and the
 * one-shot "sign-in failed" flag round-trip.
 *
 * ⚠ The authorize vs. mock-code split is NOT here. `SignInComponent.signIn()` unconditionally
 * delegates to `SessionService.beginLogin()`, which is where that branch lives; it is asserted in
 * `inspecto/api/session.service.spec.ts`. There is likewise no `mockAuthMode` client-side dev
 * switch to test — `session.service.ts:64` says so explicitly; `auth.mock` arrives on `/bootstrap`.
 */
function create(opts: { loginRequired?: boolean } = {}) {
    TestBed.resetTestingModule();
    const session = {
        loginRequired: () => opts.loginRequired ?? true,
        beginLogin: vi.fn(),
        version: signal<string | null>(null),
        branding: signal({ logoDataUrl: null, caption: null, footerText: null }),
    };
    const router = { navigate: vi.fn() };
    TestBed.configureTestingModule({
        imports: [SignInComponent],
        providers: [
            provideNoopAnimations(),
            { provide: SessionService, useValue: session },
            { provide: Router, useValue: router },
        ],
    });
    const fixture = TestBed.createComponent(SignInComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, session, router };
}

describe('SignInComponent behaviour (SIGN-IN-NO-SPEC-1)', () => {
    beforeEach(() => sessionStorage.clear());

    // The guard only routes here when OIDC is on AND there is no session, but a returning user whose
    // refresh cookie was resumed at startup can still land on the URL directly.
    it('bounces into the app when no login is required, without consuming the failure flag', () => {
        sessionStorage.setItem(FAILED_KEY, '1');
        const { router, el } = create({ loginRequired: false });

        expect(router.navigate).toHaveBeenCalledWith(['/']);
        // The early return happens BEFORE the flag is read, so a genuine failure still surfaces on the
        // screen that can act on it rather than being silently swallowed by a drive-by visit.
        expect(sessionStorage.getItem(FAILED_KEY)).toBe('1');
        expect(el.querySelector('inspecto-alert')).toBeNull();
    });

    it('stays on the screen and shows no failure alert on a first, clean visit', () => {
        const { router, el } = create();

        expect(router.navigate).not.toHaveBeenCalled();
        expect(el.querySelector('inspecto-alert')).toBeNull();
    });

    // The flag is written by the callback screen when an exchange fails. It must be ONE-SHOT: left in
    // place, every later visit to /sign-in would accuse a working deployment of having just failed.
    it('surfaces a failed sign-in once, then clears the flag so a reload is clean', () => {
        sessionStorage.setItem(FAILED_KEY, '1');
        const first = create();

        const alert = first.el.querySelector('inspecto-alert');
        expect(alert).toBeTruthy();
        expect(alert?.textContent).toContain("We couldn't complete sign-in");
        expect(sessionStorage.getItem(FAILED_KEY)).toBeNull();

        // Re-mount with the (now cleared) storage — the accusation does not come back.
        const second = create();
        expect(second.el.querySelector('inspecto-alert')).toBeNull();
    });

    it('treats any value other than "1" as no failure', () => {
        sessionStorage.setItem(FAILED_KEY, '0');
        const { el } = create();

        expect(el.querySelector('inspecto-alert')).toBeNull();
    });

    // `busy` latches synchronously, before `beginLogin()` is awaited, so a slow or hanging redirect
    // cannot be double-submitted by an impatient second click.
    it('latches into a busy state on click, blocking a second submit', async () => {
        const { el, fixture, session } = create();
        const button = el.querySelector('button') as HTMLButtonElement;
        expect(button.disabled).toBe(false);
        expect(button.textContent).toContain('Sign in with SSO');

        button.click();
        await fixture.whenStable();
        fixture.detectChanges();

        expect(session.beginLogin).toHaveBeenCalledOnce();
        expect(button.disabled).toBe(true);
        expect(button.querySelector('mat-progress-spinner')).toBeTruthy();

        button.click(); // a disabled button's handler must not fire again
        expect(session.beginLogin).toHaveBeenCalledOnce();
    });
});
