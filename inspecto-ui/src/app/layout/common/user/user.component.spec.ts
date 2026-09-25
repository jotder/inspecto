import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { UserService } from 'app/core/user/user.service';
import { SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { UserComponent } from './user.component';

function create(authMode: 'none' | 'oidc', actor: string | null = 'ada', displayName?: string) {
    const actorSig = signal(actor);
    const session = {
        authMode: signal(authMode),
        actor: actorSig,
        actorName: computed(() => (actorSig() ? displayName || actorSig() : null)),
        logout: vi.fn(),
    };
    TestBed.configureTestingModule({
        imports: [UserComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: SessionService, useValue: session },
            { provide: UserService, useValue: { user$: of({ name: 'Ada' }) } },
        ],
    });
    const fixture = TestBed.createComponent(UserComponent);
    fixture.detectChanges();
    return { fixture, session };
}

describe('UserComponent', () => {
    // Regression, 2026-07-26: Sign out used to navigate to `/logout` — a route that does not exist and
    // has no wildcard fallback — after wiping ALL of localStorage. It never reached the backend, so the
    // httpOnly refresh cookie outlived the "sign out".
    it('signOut delegates to SessionService.logout', () => {
        const { fixture, session } = create('oidc');
        fixture.componentInstance.signOut();
        expect(session.logout).toHaveBeenCalledOnce();
    });

    it('renders the menu under OIDC', () => {
        const { fixture } = create('oidc');
        expect(fixture.nativeElement.querySelector('button[aria-label="User menu"]')).toBeTruthy();
    });

    // Regression, 2026-09-15: "Signed in as" rendered a hardcoded '' because the menu read a
    // `UserService.user$` nothing populated, while the actor in `bootstrap.session` was parsed and dropped.
    it('names the signed-in actor from SessionService in the menu', () => {
        const { fixture } = create('oidc', 'priya.n');
        (fixture.nativeElement.querySelector('button[aria-label="User menu"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        const panel = document.querySelector('.mat-mdc-menu-panel');
        expect(panel?.textContent).toContain('Signed in as');
        expect(panel?.textContent).toContain('priya.n');
    });

    // R2-16: a Demo User is named by its displayName ("Demo Manager"), not its id ("demo.manager").
    it('names the signed-in subject by display name when the session has one', () => {
        const { fixture } = create('oidc', 'demo.manager', 'Demo Manager');
        (fixture.nativeElement.querySelector('button[aria-label="User menu"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        const name = document.querySelector('.mat-mdc-menu-panel .font-medium');
        expect(name?.textContent?.trim()).toBe('Demo Manager');
    });

    it('renders with no a11y violations', async () => {
        await expectNoA11yViolations(create('oidc', 'demo.manager', 'Demo Manager').fixture.nativeElement);
    });

    // Hiding only Sign out left a menu containing nothing but a blank "Signed in as" — verified in the
    // offline preview — so the auth-free shell drops the whole thing.
    it('renders nothing on Personal, where there is no principal and no session to end', () => {
        const { fixture } = create('none');
        expect(fixture.nativeElement.querySelector('button[aria-label="User menu"]')).toBeNull();
    });
});
