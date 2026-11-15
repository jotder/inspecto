import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { UserService } from 'app/core/user/user.service';
import { GammaConfigService } from '@gamma/services/config';
import { schemeStorageKey } from 'app/inspecto/shell/scheme-preference.service';
import { SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { UserComponent } from './user.component';

/** What the menu set on the Gamma config (the scheme), last first. */
let gammaSet: unknown[] = [];
const gamma = {
    set config(v: unknown) {
        gammaSet.unshift(v);
    },
};

function create(authMode: 'none' | 'oidc', actor: string | null = 'ada', displayName?: string, schemeChoice = false) {
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
            { provide: GammaConfigService, useValue: gamma },
        ],
    });
    const fixture = TestBed.createComponent(UserComponent);
    fixture.componentInstance.schemeChoice = schemeChoice;
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

    describe('colour scheme (LA-FULL-CYCLE-TEST-1 phase 6, finding 20)', () => {
        const open = (fixture: ReturnType<typeof create>['fixture']): HTMLElement => {
            (fixture.nativeElement.querySelector('button[aria-label="User menu"]') as HTMLButtonElement).click();
            fixture.detectChanges();
            return document.querySelector('.mat-mdc-menu-panel') as HTMLElement;
        };

        it('the console menu (no schemeChoice) is unchanged: no scheme items, and nothing on Basic', () => {
            const { fixture } = create('oidc');
            expect(open(fixture).querySelector('[data-testid^="scheme-"]')).toBeNull();
        });

        it('Inspecto LA offers Match system, Light and Dark as radio items, even where nobody signs in', async () => {
            gammaSet = [];
            localStorage.removeItem(schemeStorageKey(null));
            const { fixture } = create('none', null, undefined, true);
            const panel = open(fixture);
            const items = Array.from(panel.querySelectorAll<HTMLElement>('[role="menuitemradio"]'));
            expect(items.map((b) => b.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
                'Match system colour scheme',
                'Light colour scheme',
                'Dark colour scheme',
            ]);
            expect(items.map((b) => b.getAttribute('aria-checked'))).toEqual(['true', 'false', 'false']);
            expect(panel.textContent).not.toContain('Sign out'); // Basic: no session to end
            (panel.querySelector('[data-testid="scheme-light"]') as HTMLButtonElement).click();
            fixture.detectChanges();
            expect(gammaSet[0]).toEqual({ scheme: 'light' });
            expect(localStorage.getItem(schemeStorageKey(null))).toBe('light');
            localStorage.removeItem(schemeStorageKey(null));
        });

        it('under OIDC the scheme sits between the name and Sign out, and the open menu has no a11y violations', async () => {
            const { fixture } = create('oidc', 'ada', undefined, true);
            const panel = open(fixture);
            const text = panel.textContent ?? '';
            expect(text.indexOf('Signed in as')).toBeLessThan(text.indexOf('Match system'));
            expect(text.indexOf('Dark')).toBeLessThan(text.indexOf('Sign out'));
            await expectNoA11yViolations(panel);
        });
    });
});
