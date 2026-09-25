import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router } from '@angular/router';
import { DemoUser, SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SignInComponent } from './sign-in.component';

const FAILED_KEY = 'inspecto.signInFailed';

/**
 * Behaviour spec for the Professional-edition sign-in screen (SIGN-IN-NO-SPEC-1). The sibling
 * `sign-in.a11y.spec.ts` covers rendering, branding and the version line; this one covers the two
 * things the component itself DECIDES: the `ngOnInit` bounce when no login is required, and the
 * one-shot "sign-in failed" flag round-trip.
 *
 * ⚠ The authorize vs. mock-code split is NOT here. `SignInComponent.signIn()` unconditionally
 * delegates to `SessionService.beginLogin()`, which is where that branch lives; it is asserted in
 * `inspecto/api/session.service.spec.ts`. There is likewise no `mockAuthMode` client-side dev
 * switch to test — `session.service.ts:64` says so explicitly; `auth.mock` arrives on `/bootstrap`.
 */
function create(opts: { loginRequired?: boolean; demoUsers?: DemoUser[] } = {}) {
    TestBed.resetTestingModule();
    const session = {
        loginRequired: () => opts.loginRequired ?? true,
        beginLogin: vi.fn(),
        version: signal<string | null>(null),
        branding: signal({ logoDataUrl: null, caption: null, footerText: null }),
        demoUsers: signal<DemoUser[]>(opts.demoUsers ?? []),
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

    // DEMO-AUTH-1: a demo build's bootstrap carries Demo Users — the SSO button gives way to a picker, and the
    // picked id is what beginLogin() receives (the demo relay turns `demo:<id>` into that user's session).
    it('renders a Demo User picker instead of the SSO button and signs in as the picked user', async () => {
        const { el, fixture, session } = create({
            demoUsers: [
                { id: 'ra.analyst', displayName: 'Demo RA Analyst', title: 'Revenue Assurance analyst' },
                { id: 'admin', displayName: 'Demo Admin', title: 'Platform administrator' },
            ],
        });
        expect(el.textContent).not.toContain('Sign in with SSO');
        expect(el.querySelector('inspecto-alert')?.textContent).toContain('Not secure, local only');
        const picks = Array.from(el.querySelectorAll('[data-demo-user]')) as HTMLButtonElement[];
        expect(picks.map((b) => b.dataset['demoUser'])).toEqual(['ra.analyst', 'admin']);
        expect(picks[0].textContent).toContain('Revenue Assurance analyst');

        picks[1].click();
        await fixture.whenStable();
        expect(session.beginLogin).toHaveBeenCalledWith('admin');
        await expectNoA11yViolations(el);
    });
});
