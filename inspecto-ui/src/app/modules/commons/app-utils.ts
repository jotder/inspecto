import { Injectable } from '@angular/core';
import { ActivatedRoute, NavigationExtras } from '@angular/router';
import { Observable, of } from 'rxjs';
import { PageManager } from './page.manager';
import { AppProperties } from './app.properties';
import { environment } from 'environments/environment';
import { challengeFromVerifier, randomState, randomVerifier } from '../auth/pkce';

export const PKCE_VERIFIER_STORAGE_KEY = 'pkce_code_verifier';
export const PKCE_STATE_STORAGE_KEY = 'pkce_state';

@Injectable({
    providedIn: 'root',
})
export class AppUtils {
    static readonly APP_PREFIX = ' | Skybase';
    static readonly DEFAULT_TITLE = 'App';

    static getBaseRoute(activatedRoute: ActivatedRoute): ActivatedRoute {
        return activatedRoute.firstChild
            ? AppUtils.getBaseRoute(activatedRoute.firstChild)
            : activatedRoute;
    }

    static getObservableTitle(activatedRoute: ActivatedRoute, signature: boolean): Observable<string> {
        const childRoute = AppUtils.getBaseRoute(activatedRoute);
        let pageTitle: string = childRoute.snapshot.data['title'] ?? AppUtils.DEFAULT_TITLE;

        if (signature && !pageTitle.endsWith(AppUtils.APP_PREFIX)) {
            pageTitle += AppUtils.APP_PREFIX;
        }

        return of(pageTitle);
    }

    static async redirectToAuthServer(props: AppProperties, pageManager: PageManager): Promise<void> {
        // Capture the return URL BEFORE the first await. This runs from a CanActivate guard that
        // returns false, so once we yield the router may already have reverted the address bar —
        // reading it after `await` would send the user somewhere else after login.
        pageManager.redirectPath = window.location.href;

        const verifier = randomVerifier();
        const state = randomState();
        const challenge = await challengeFromVerifier(verifier);
        sessionStorage.setItem(PKCE_VERIFIER_STORAGE_KEY, verifier);
        sessionStorage.setItem(PKCE_STATE_STORAGE_KEY, state);

        const params = new URLSearchParams({
            client_id: props.appClientId,
            response_type: 'code',
            scope: props.appScope,
            redirect_uri: props.appRedirectUri,
            code_challenge: challenge,
            code_challenge_method: 'S256',
            state,
        });

        const navigateUrl = `${environment.authServerUrl}${environment.authVersion}/authorize?${params}`;
        window.location.href = navigateUrl;
    }

    static titleCaseWord(word: string): string {
        if (!word) return word;
        return word.charAt(0).toUpperCase() + word.slice(1);
    }

    static getNavigationExtrasFromPath(routePath: string): NavigationExtras {
        const queryString = routePath.substring(routePath.indexOf('?') + 1);
        const queryParams: Record<string, string> = {};
        new URLSearchParams(queryString).forEach((value, key) => {
            queryParams[key] = value;
        });
        return { queryParams };
    }
}