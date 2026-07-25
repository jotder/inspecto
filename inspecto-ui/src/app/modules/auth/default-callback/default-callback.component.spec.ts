import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppProperties } from 'app/modules/commons/app.properties';
import { PageManager } from 'app/modules/commons/page.manager';
import { PKCE_STATE_STORAGE_KEY, PKCE_VERIFIER_STORAGE_KEY } from 'app/modules/commons/app-utils';
import { SecurityPrincipal } from 'app/modules/commons/security-principal';
import { AuthService } from '../auth-service';
import { DefaultCallbackComponent, authParamsFrom } from './default-callback.component';

describe('authParamsFrom', () => {
    // The regression this function exists to prevent: the old callback read the code as
    // `href.substring(indexOf('code=') + 5)`, which swallows everything to the end of the URL.
    // That was harmless while `/authorize` sent no `state`, and broke the moment PKCE added one.
    it('reads the code when state trails it (the shape that broke the substring scan)', () => {
        const p = authParamsFrom('https://app.example/redirect/oauth/pronto?code=abc123&state=xyz789');
        expect(p.get('code')).toBe('abc123');
        expect(p.get('state')).toBe('xyz789');
    });

    it('reads the code when state precedes it', () => {
        const p = authParamsFrom('https://app.example/redirect/oauth/pronto?state=xyz789&code=abc123');
        expect(p.get('code')).toBe('abc123');
        expect(p.get('state')).toBe('xyz789');
    });

    it('reads params carried in a hash fragment (Angular hash routing)', () => {
        const p = authParamsFrom('https://app.example/#/redirect/oauth/pronto?code=abc123&state=xyz789');
        expect(p.get('code')).toBe('abc123');
        expect(p.get('state')).toBe('xyz789');
    });

    it('returns no code when the callback carries none', () => {
        expect(authParamsFrom('https://app.example/redirect/oauth/pronto').get('code')).toBeNull();
    });
});

function create(href: string) {
    const retrieveToken = vi.fn(() => of({ access_token: 't' }));
    const navigate = vi.fn();
    window.history.pushState({}, '', href);
    TestBed.configureTestingModule({
        imports: [DefaultCallbackComponent],
        providers: [
            { provide: Router, useValue: { navigate } },
            { provide: AuthService, useValue: { retrieveToken, saveTokens: vi.fn() } },
            { provide: SecurityPrincipal, useValue: { loadPrincipalData: vi.fn() } },
            { provide: PageManager, useValue: { redirectPath: '' } },
            { provide: AppProperties, useValue: { appBaseContext: 'https://app.example' } },
        ],
    });
    const f = TestBed.createComponent(DefaultCallbackComponent);
    f.detectChanges();
    return { retrieveToken, navigate };
}

describe('DefaultCallbackComponent state validation', () => {
    beforeEach(() => sessionStorage.clear());

    it('exchanges the code when the returned state matches the stored one', () => {
        sessionStorage.setItem(PKCE_STATE_STORAGE_KEY, 'xyz789');
        const { retrieveToken } = create('/redirect/oauth/pronto?code=abc123&state=xyz789');
        expect(retrieveToken).toHaveBeenCalledWith('abc123');
    });

    it('refuses the exchange on a state mismatch and clears the PKCE keys', () => {
        sessionStorage.setItem(PKCE_STATE_STORAGE_KEY, 'expected');
        sessionStorage.setItem(PKCE_VERIFIER_STORAGE_KEY, 'verifier');
        const { retrieveToken, navigate } = create('/redirect/oauth/pronto?code=abc123&state=attacker');
        expect(retrieveToken).not.toHaveBeenCalled();
        expect(navigate).toHaveBeenCalledWith(['/login']);
        expect(sessionStorage.getItem(PKCE_VERIFIER_STORAGE_KEY)).toBeNull();
        expect(sessionStorage.getItem(PKCE_STATE_STORAGE_KEY)).toBeNull();
    });

    it('refuses the exchange when no state was stored (an unsolicited callback)', () => {
        const { retrieveToken } = create('/redirect/oauth/pronto?code=abc123&state=whatever');
        expect(retrieveToken).not.toHaveBeenCalled();
    });

    it('does nothing at all when the callback carries no code', () => {
        sessionStorage.setItem(PKCE_STATE_STORAGE_KEY, 'xyz789');
        const { retrieveToken, navigate } = create('/redirect/oauth/pronto');
        expect(retrieveToken).not.toHaveBeenCalled();
        expect(navigate).not.toHaveBeenCalled();
    });
});
