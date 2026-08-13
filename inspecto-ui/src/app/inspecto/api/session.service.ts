import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, firstValueFrom, map, Observable, of, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { apiUrl } from './api-base';

/**
 * OIDC endpoint configuration for the Standard-edition login redirect. Comes from `bootstrap.auth`
 * when the backend supplies it (the mock does, with `mock:true`); otherwise falls back to
 * `environment.oidc` (a real deployment bakes its non-secret authorize URL + public client id there,
 * the conventional SPA pattern). Never carries a client secret — the SPA is a public PKCE client.
 */
export interface OidcConfig {
    authorizeUrl: string;
    clientId: string;
    scopes: string;
    /**
     * The provider's `end_session_endpoint` (OIDC RP-Initiated Logout 1.0). Optional: when absent
     * {@link SessionService.logout} ends the Inspecto session only, which is the pre-2026-07-26
     * behavior and all a provider without the endpoint can support.
     *
     * **Declared config, not derived and not discovered** — the same call D15 made for
     * `auth.oidc.tokenEndpoint`: no vendor's path layout is assumed. It sits beside `authorizeUrl`
     * so both endpoints come from one place; a hardcoded host anywhere in the SPA is what caused
     * BACKLOG §5's incident.
     */
    endSessionUrl?: string;
    /** Offline/mock only: skip the real IAM round-trip and grant a fake code locally (dev demo). */
    mock?: boolean;
}

/** The slice of `GET /bootstrap` this service consumes (edition switch + session). */
interface Bootstrap {
    edition?: string;
    features?: { authMode?: string; exchange?: boolean };
    session?: { authenticated?: boolean; actor?: string; capabilities?: string[] };
    auth?: Partial<OidcConfig>;
}

const VERIFIER_KEY = 'inspecto.pkce.verifier';
const STATE_KEY = 'inspecto.pkce.state';

/**
 * The edition-aware session gate (W6d). It reads `GET /bootstrap` once at startup to learn the
 * edition's `authMode`, and holds the browser-visible session as signals: a short-lived access token
 * (in memory only — the refresh token lives in the backend's httpOnly cookie, never here) plus the
 * resolved capability grants.
 *
 * <p><b>Offline / Personal is byte-for-byte unchanged.</b> When `authMode !== 'oidc'` (Personal, or the
 * mock backend answering offline) this service does nothing beyond the one bootstrap read: no login, no
 * token, no redirect — {@link loginRequired} is always false and {@link authGuard} is a pass-through.
 * The Standard flow (redirect → callback → {@link completeLogin}) engages only when a real backend (or
 * the `mockAuthMode: 'oidc'` dev switch) reports OIDC. Mirrors {@link SpacesService}'s signal shape.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
    private http = inject(HttpClient);
    private router = inject(Router);

    /** 'none' (Personal / offline) or 'oidc' (Standard). Drives {@link loginRequired} + {@link authGuard}. */
    readonly authMode = signal<'none' | 'oidc'>('none');
    readonly edition = signal<string>('personal');
    readonly authenticated = signal(false);
    readonly capabilities = signal<string[]>([]);
    /** `bootstrap.features.exchange` — the multi-space runtime hosts the cross-Space Exchange. */
    readonly exchangeEnabled = signal(false);

    private readonly accessToken = signal<string | null>(null);
    private oidc: OidcConfig | null = null;

    /** True only on Standard when there is no live session yet — the sole condition that shows sign-in. */
    readonly loginRequired = computed(() => this.authMode() === 'oidc' && !this.authenticated());

    /** The current access token for the auth interceptor to attach, or null (Personal / signed-out). */
    token(): string | null {
        return this.accessToken();
    }

    /**
     * App-initializer entry point: read bootstrap, set the edition switch, and — under OIDC — try to
     * resume an existing session from the refresh cookie. Never rejects: a bootstrap failure degrades to
     * Personal (auth-free) so the app still loads rather than white-screening on a login it can't reach.
     */
    async init(): Promise<void> {
        const boot = await firstValueFrom(
            this.http.get<Bootstrap>(apiUrl('/bootstrap')).pipe(catchError(() => of({} as Bootstrap))),
        );
        this.edition.set(boot.edition ?? 'personal');
        const mode = boot.features?.authMode === 'oidc' ? 'oidc' : 'none';
        this.authMode.set(mode);
        this.capabilities.set(boot.session?.capabilities ?? []);
        this.exchangeEnabled.set(boot.features?.exchange === true);

        if (mode !== 'oidc') return; // Personal / offline — done, no login path.

        this.oidc = {
            authorizeUrl: boot.auth?.authorizeUrl ?? environment.oidc?.authorizeUrl ?? '',
            clientId: boot.auth?.clientId ?? environment.oidc?.clientId ?? '',
            scopes: boot.auth?.scopes ?? environment.oidc?.scopes ?? 'openid profile',
            endSessionUrl: boot.auth?.endSessionUrl ?? environment.oidc?.endSessionUrl ?? '',
            mock: boot.auth?.mock ?? environment.oidc?.mock ?? false,
        };
        // A returning user still holds the httpOnly refresh cookie — mint an access token from it. A 401
        // just means "not signed in yet"; the guard will route to sign-in.
        const token = await firstValueFrom(this.refresh().pipe(catchError(() => of(null))));
        // The bootstrap read above carried no bearer, so its session slice is the anonymous subject's.
        // Re-read with the fresh token so `capabilities` holds the subject's *effective* grants (R2)
        // before anything renders — init() is awaited by the app initializer.
        if (token) await this.loadSessionFromBootstrap();
    }

    /** Start the Authorization-Code + PKCE redirect (or, in mock mode, grant a code locally offline). */
    async beginLogin(): Promise<void> {
        const { randomVerifier, randomState, challengeFromVerifier } = await import('./pkce');
        const verifier = randomVerifier();
        const state = randomState();
        sessionStorage.setItem(VERIFIER_KEY, verifier);
        sessionStorage.setItem(STATE_KEY, state);

        if (this.oidc?.mock) {
            // Offline demo: no real IAM to redirect to — grant a fake code and jump straight to the callback.
            this.router.navigate(['/auth/callback'], { queryParams: { code: 'mock-code', state } });
            return;
        }
        const challenge = await challengeFromVerifier(verifier);
        const params = new URLSearchParams({
            response_type: 'code',
            client_id: this.oidc?.clientId ?? '',
            redirect_uri: this.redirectUri(),
            scope: this.oidc?.scopes ?? 'openid profile',
            state,
            code_challenge: challenge,
            code_challenge_method: 'S256',
        });
        this.redirect(`${this.oidc?.authorizeUrl}?${params.toString()}`);
    }

    /**
     * Redeem the code the IAM redirected back with: verify the `state`, then hand the code + PKCE
     * verifier to the backend BFF (POST /auth/exchange), which sets the httpOnly refresh cookie and
     * returns the access token. On success, re-read bootstrap so capabilities reflect the new subject.
     */
    completeLogin(code: string, state: string): Observable<boolean> {
        const expected = sessionStorage.getItem(STATE_KEY);
        sessionStorage.removeItem(STATE_KEY);
        const verifier = sessionStorage.getItem(VERIFIER_KEY);
        sessionStorage.removeItem(VERIFIER_KEY);
        if (!state || state !== expected || !verifier) return of(false); // CSRF / stale round-trip

        return this.http
            .post<{ accessToken: string }>(apiUrl('/auth/exchange'), {
                code,
                codeVerifier: verifier,
                redirectUri: this.redirectUri(),
            })
            .pipe(
                tap((r) => {
                    // Mark authenticated synchronously: the caller navigates into the app on `true`, and the
                    // authGuard reads `authenticated` immediately — deferring it to the async bootstrap
                    // re-fetch below would bounce the user back to sign-in mid-redirect.
                    this.accessToken.set(r.accessToken);
                    this.authenticated.set(true);
                }),
                map(() => true),
                // Enrich capabilities from the Subject the backend resolves off the new bearer (non-blocking).
                tap((ok) => {
                    if (ok) void this.loadSessionFromBootstrap();
                }),
                catchError(() => of(false)),
            );
    }

    /** Mint a fresh access token from the httpOnly refresh cookie (startup resume + interceptor 401 retry). */
    refresh(): Observable<string> {
        return this.http.post<{ accessToken: string }>(apiUrl('/auth/refresh'), {}).pipe(
            map((r) => r.accessToken),
            tap((t) => {
                this.accessToken.set(t);
                this.authenticated.set(true);
            }),
        );
    }

    /**
     * Clear the local session and end it at the backend (best-effort), then end the SSO session at the
     * provider if one can be — RP-Initiated Logout 1.0: redirect to `end_session_endpoint` with
     * `client_id` + `post_logout_redirect_uri`, and the provider returns the browser to `/sign-in`.
     *
     * `id_token_hint` is deliberately absent: the id token lives behind the BFF (the SPA only ever holds
     * a short-lived access token), and the spec accepts `client_id` in its place when the RP is
     * identified that way. Without this redirect the Inspecto session ends but the IdP's does not, so
     * the next sign-in completes with no credential prompt — which is what the console did between
     * `ca3680df` and 2026-07-26.
     *
     * Falls back to the in-app route when no `endSessionUrl` is configured (or in offline mock mode,
     * where there is no provider to redirect to), so Personal and the dev mock are unaffected.
     */
    logout(): void {
        this.http
            .post(apiUrl('/auth/logout'), {})
            .pipe(catchError(() => of(null)))
            .subscribe(() => {
                this.onAuthLost();
                const endSession = this.oidc?.endSessionUrl;
                if (!endSession || this.oidc?.mock) {
                    this.router.navigate(['/sign-in']);
                    return;
                }
                const params = new URLSearchParams({
                    client_id: this.oidc?.clientId ?? '',
                    post_logout_redirect_uri: `${window.location.origin}/sign-in`,
                });
                this.redirect(`${endSession}?${params.toString()}`);
            });
    }

    /** Interceptor hook: a refresh failed / the session is gone — drop local state and bounce to sign-in. */
    onAuthLost(): void {
        this.accessToken.set(null);
        this.authenticated.set(false);
        this.capabilities.set([]);
    }

    private redirectUri(): string {
        return `${window.location.origin}/auth/callback`;
    }

    /** Leave the SPA for the provider (authorize / end-session). The one seam that navigates away —
     *  jsdom makes `window.location.assign` non-configurable, so tests stub this instead. */
    protected redirect(url: string): void {
        window.location.assign(url);
    }

    private async loadSessionFromBootstrap(): Promise<void> {
        const boot = await firstValueFrom(
            this.http.get<Bootstrap>(apiUrl('/bootstrap')).pipe(catchError(() => of({} as Bootstrap))),
        );
        this.authenticated.set(boot.session?.authenticated ?? true);
        this.capabilities.set(boot.session?.capabilities ?? []);
    }
}
