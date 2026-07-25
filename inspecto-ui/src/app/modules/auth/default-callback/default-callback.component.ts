import { Component, inject, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { AppProperties } from 'app/modules/commons/app.properties';
import { PageManager } from 'app/modules/commons/page.manager';
import { SecurityPrincipal } from 'app/modules/commons/security-principal';
import { AuthService } from '../auth-service';
import { AppUtils, PKCE_STATE_STORAGE_KEY, PKCE_VERIFIER_STORAGE_KEY } from 'app/modules/commons/app-utils';

/**
 * The OAuth params from a callback URL.
 *
 * Read with `URLSearchParams` rather than a substring scan: the authorization response now carries
 * `state` alongside `code`, so the old `href.substring(indexOf('code=') + 5)` would swallow
 * `&state=…` into the code whenever the IdP happens to order the params that way.
 */
export function authParamsFrom(href: string): URLSearchParams {
    const url = new URL(href);
    // Angular hash routing can carry the query inside the fragment instead of `location.search`.
    const hashQuery = url.hash.includes('?') ? url.hash.slice(url.hash.indexOf('?') + 1) : '';
    return new URLSearchParams(url.search.slice(1) || hashQuery);
}

@Component({
    selector: 'default-redirect',
    template: '',
    standalone: true,
})
export class DefaultCallbackComponent implements OnInit {
    private readonly router = inject(Router);
    private readonly pageManager = inject(PageManager);
    private readonly authService = inject(AuthService);
    private readonly securityPrincipal = inject(SecurityPrincipal);
    private readonly props = inject(AppProperties);

    ngOnInit(): void {
        const params = authParamsFrom(window.location.href);
        const code = params.get('code');

        if (!code) return;

        // PKCE/CSRF: the IdP echoes back the `state` we generated on the /authorize redirect.
        // Refuse the exchange unless it matches what we stored, so a code injected by a third
        // party cannot be traded for a token in this session. Clear both keys on a mismatch —
        // the verifier belongs to an authorization request we are abandoning.
        const expected = sessionStorage.getItem(PKCE_STATE_STORAGE_KEY);
        const returned = params.get('state');
        if (!expected || returned !== expected) {
            sessionStorage.removeItem(PKCE_VERIFIER_STORAGE_KEY);
            sessionStorage.removeItem(PKCE_STATE_STORAGE_KEY);
            console.error('OAuth state mismatch — refusing the token exchange.');
            this.router.navigate(['/login']);
            return;
        }

        this.authService.retrieveToken(code).subscribe({
            next: (data) => this.handleAuthorizationCodeTokenOutput(data),
            error: (err) => console.error('Token retrieval failed:', err),
        });
    }

    private handleAuthorizationCodeTokenOutput(data: any): void {
        this.authService.saveTokens(data);
        this.securityPrincipal.loadPrincipalData(data);

        const redirectUri = this.pageManager.redirectPath;
        const baseContext = this.props.appBaseContext;

        if (!redirectUri.includes(baseContext)) return;

        const routePath = redirectUri.substring(baseContext.length);

        if (routePath.includes('?')) {
            const commandPath = routePath.substring(0, routePath.indexOf('?'));
            const navigationExtras = AppUtils.getNavigationExtrasFromPath(routePath);
            this.router.navigate([commandPath], navigationExtras);
        } else {
            this.router.navigate([routePath]);
        }
    }
}