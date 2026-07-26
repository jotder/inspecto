import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { UserService } from 'app/core/user/user.service';
import { SessionService } from 'app/inspecto/api';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { UserComponent } from './user.component';

function create(authMode: 'none' | 'oidc') {
    const session = { authMode: signal(authMode), logout: vi.fn() };
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

    // Hiding only Sign out left a menu containing nothing but a blank "Signed in as" — verified in the
    // offline preview — so the auth-free shell drops the whole thing.
    it('renders nothing on Personal, where there is no principal and no session to end', () => {
        const { fixture } = create('none');
        expect(fixture.nativeElement.querySelector('button[aria-label="User menu"]')).toBeNull();
    });
});
