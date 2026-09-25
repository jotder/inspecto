import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { environment } from '../../../environments/environment';
import { SessionService } from './session.service';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths
const tick = () => new Promise((r) => setTimeout(r, 0));

describe('SessionService (W6d edition switch)', () => {
    let svc: SessionService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        sessionStorage.clear();
        TestBed.configureTestingModule({
            providers: [
                SessionService,
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                // logout() and the mock sign-in fire `router.navigate(...)` without awaiting it. An empty
                // route table rejects those with NG04002, which zone.js used to swallow — zoneless, they
                // escape as unhandled rejections and fail the run. Register what the service navigates to.
                provideRouter([
                    { path: 'sign-in', loadChildren: () => Promise.resolve([]) },
                    { path: 'auth/callback', loadChildren: () => Promise.resolve([]) },
                ]),
            ],
        });
        svc = TestBed.inject(SessionService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('Personal/offline bootstrap ⇒ authMode none, no login, no /auth/refresh', async () => {
        const done = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ edition: 'personal', features: { authMode: 'none' } });
        await done;
        expect(svc.authMode()).toBe('none');
        expect(svc.loginRequired()).toBe(false);
        httpMock.expectNone(`${base}/auth/refresh`); // never attempts a session under Personal
    });

    it('stores the actor only for an authenticated subject, and drops it when the session is lost', async () => {
        const init = svc.init();
        httpMock
            .expectOne(`${base}/bootstrap`)
            .flush({ features: { authMode: 'oidc' }, session: { authenticated: false, actor: 'appUser' } });
        await tick();
        expect(svc.actor()).toBeNull(); // the anonymous read's placeholder is never a principal
        httpMock.expectOne(`${base}/auth/refresh`).flush({ accessToken: 't' });
        await tick();
        httpMock
            .expectOne(`${base}/bootstrap`)
            .flush({ session: { authenticated: true, actor: 'priya.n', capabilities: ['canOperateRuns'] } });
        await init;
        expect(svc.actor()).toBe('priya.n');
        const router = TestBed.inject(Router);
        const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
        svc.onAuthLost();
        expect(svc.actor()).toBeNull();
        // 2026-09-17: the doc comment always promised the bounce; the body never did it, so a user whose
        // refresh failed sat on a dead pane. Losing the session must land on sign-in.
        expect(navigate).toHaveBeenCalledWith(['/sign-in']);
    });

    it('reads features.geoLink, and treats an absent flag as NOT enabled (Personal ships no geo/link module)', async () => {
        const init = svc.init();
        httpMock
            .expectOne(`${base}/bootstrap`)
            .flush({ edition: 'professional', features: { authMode: 'none', geoLink: true } });
        await init;
        expect(svc.geoLinkEnabled()).toBe(true);
    });

    it('geoLink defaults to false when /bootstrap does not mention it — never assumed present', async () => {
        const init = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ edition: 'personal', features: { authMode: 'none' } });
        await init;
        expect(svc.geoLinkEnabled()).toBe(false);
    });

    it('reads features.events (EDG-01 cell 6 — the optional events feed module)', async () => {
        const init = svc.init();
        httpMock
            .expectOne(`${base}/bootstrap`)
            .flush({ edition: 'professional', features: { authMode: 'none', events: true } });
        await init;
        expect(svc.eventsEnabled()).toBe(true);
    });

    it('events defaults to false when /bootstrap does not mention it — never assumed present', async () => {
        const init = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ edition: 'personal', features: { authMode: 'none' } });
        await init;
        expect(svc.eventsEnabled()).toBe(false);
    });

    it('reads features.ops (EDG-01 cell 7 — the optional operational-objects module)', async () => {
        const init = svc.init();
        httpMock
            .expectOne(`${base}/bootstrap`)
            .flush({ edition: 'professional', features: { authMode: 'none', ops: true } });
        await init;
        expect(svc.opsEnabled()).toBe(true);
    });

    it('ops defaults to false when /bootstrap does not mention it — never assumed present', async () => {
        const init = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ edition: 'personal', features: { authMode: 'none' } });
        await init;
        expect(svc.opsEnabled()).toBe(false);
    });

    it('OIDC bootstrap + refresh 401 ⇒ authenticated false, loginRequired true', async () => {
        const done = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ edition: 'professional', features: { authMode: 'oidc' } });
        await tick();
        httpMock
            .expectOne(`${base}/auth/refresh`)
            .flush({ error: 'no session' }, { status: 401, statusText: 'Unauthorized' });
        await done;
        expect(svc.authMode()).toBe('oidc');
        expect(svc.authenticated()).toBe(false);
        expect(svc.loginRequired()).toBe(true);
    });

    it('OIDC bootstrap + refresh 200 ⇒ resumes the session (token + authenticated + effective grants)', async () => {
        const done = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ edition: 'professional', features: { authMode: 'oidc' } });
        await tick();
        httpMock.expectOne(`${base}/auth/refresh`).flush({ accessToken: 'at-resumed', expiresIn: 300 });
        await tick();
        // the resume re-reads bootstrap with the bearer — the anonymous first read had no session slice (R2)
        httpMock.expectOne(`${base}/bootstrap`).flush({
            session: { authenticated: true, capabilities: ['canAuthorWorkbench'] },
        });
        await done;
        expect(svc.authenticated()).toBe(true);
        expect(svc.token()).toBe('at-resumed');
        expect(svc.loginRequired()).toBe(false);
        expect(svc.capabilities()).toEqual(['canAuthorWorkbench']);
    });

    it('completeLogin rejects a mismatched state without any HTTP call (CSRF guard)', async () => {
        sessionStorage.setItem('inspecto.pkce.state', 'expected');
        sessionStorage.setItem('inspecto.pkce.verifier', 'v');
        let ok = true;
        svc.completeLogin('code', 'DIFFERENT').subscribe((r) => (ok = r));
        await tick();
        expect(ok).toBe(false);
        httpMock.expectNone(`${base}/auth/exchange`);
    });

    it('completeLogin posts code+verifier to /auth/exchange and stores the access token', async () => {
        sessionStorage.setItem('inspecto.pkce.state', 's1');
        sessionStorage.setItem('inspecto.pkce.verifier', 'verifier-1');
        let ok = false;
        svc.completeLogin('the-code', 's1').subscribe((r) => (ok = r));
        const req = httpMock.expectOne(`${base}/auth/exchange`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toMatchObject({
            code: 'the-code',
            codeVerifier: 'verifier-1',
        });
        req.flush({ accessToken: 'at-new', expiresIn: 300 });
        await tick();
        expect(ok).toBe(true);
        expect(svc.token()).toBe('at-new');
        // completeLogin re-reads bootstrap to populate capabilities from the new subject.
        httpMock.expectOne(`${base}/bootstrap`).flush({
            session: { authenticated: true, capabilities: ['canOperateRuns'] },
        });
        await tick();
        expect(svc.capabilities()).toEqual(['canOperateRuns']);
    });

    it('logout clears local state and POSTs /auth/logout', async () => {
        // seed an authenticated state
        const done = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ features: { authMode: 'oidc' } });
        await tick();
        httpMock.expectOne(`${base}/auth/refresh`).flush({ accessToken: 'at', expiresIn: 300 });
        await tick();
        httpMock.expectOne(`${base}/bootstrap`).flush({ session: { authenticated: true } });
        await done;
        expect(svc.authenticated()).toBe(true);

        svc.logout();
        httpMock.expectOne(`${base}/auth/logout`).flush({ loggedOut: true });
        await tick();
        expect(svc.authenticated()).toBe(false);
        expect(svc.token()).toBeNull();
    });

    /** Stub the one seam that navigates away from the SPA (jsdom forbids spying `location.assign`). */
    function spyOnRedirect() {
        return vi.spyOn(svc as unknown as { redirect(url: string): void }, 'redirect').mockImplementation(() => {});
    }

    /** Sign in far enough for `oidc` config to be resolved, with the given `bootstrap.auth` block. */
    async function signedInWith(auth: Record<string, unknown>): Promise<void> {
        const done = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({ features: { authMode: 'oidc' }, auth });
        await tick();
        httpMock.expectOne(`${base}/auth/refresh`).flush({ accessToken: 'at', expiresIn: 300 });
        await tick();
        httpMock.expectOne(`${base}/bootstrap`).flush({ session: { authenticated: true } });
        await done;
    }

    // SIGN-IN-NO-SPEC-1: the two beginLogin branches. They were filed against SignInComponent, but the
    // component only delegates — the authorize/mock-code split is decided here.
    it('beginLogin builds an Auth-Code + PKCE authorize URL and leaves the SPA', async () => {
        const assign = spyOnRedirect();
        await signedInWith({ authorizeUrl: 'https://idp/authorize', clientId: 'spa-1', scopes: 'openid email' });

        await svc.beginLogin();

        const url = new URL(assign.mock.calls[0][0] as string);
        expect(url.origin + url.pathname).toBe('https://idp/authorize');
        expect(url.searchParams.get('response_type')).toBe('code');
        expect(url.searchParams.get('client_id')).toBe('spa-1');
        expect(url.searchParams.get('scope')).toBe('openid email');
        expect(url.searchParams.get('redirect_uri')).toBe(`${window.location.origin}/auth/callback`);
        // PKCE must be S256 — a `plain` challenge would defeat the interception defence entirely.
        expect(url.searchParams.get('code_challenge_method')).toBe('S256');
        const challenge = url.searchParams.get('code_challenge') ?? '';
        expect(challenge.length).toBeGreaterThan(0);
        // The verifier is retained for the exchange, and is NOT the challenge sent over the wire.
        const verifier = sessionStorage.getItem('inspecto.pkce.verifier');
        expect(verifier).toBeTruthy();
        expect(challenge).not.toBe(verifier);
        // `state` is stored so completeLogin() can reject a mismatched round-trip.
        expect(url.searchParams.get('state')).toBe(sessionStorage.getItem('inspecto.pkce.state'));
        assign.mockRestore();
    });

    it('beginLogin in offline mock mode grants a fake code in-app and never leaves the SPA', async () => {
        const assign = spyOnRedirect();
        await signedInWith({ authorizeUrl: 'https://idp/authorize', clientId: 'spa-1', mock: true });
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

        await svc.beginLogin();

        expect(assign).not.toHaveBeenCalled(); // there is no IAM to redirect to
        expect(navigate).toHaveBeenCalledWith(['/auth/callback'], {
            queryParams: { code: 'mock-code', state: sessionStorage.getItem('inspecto.pkce.state') },
        });
        navigate.mockRestore();
        assign.mockRestore();
    });

    // DEMO-AUTH-1: a demo build reports authMode 'demo' and a Demo User list; it signs in through the same backend
    // session routes as OIDC, and a picked Demo User travels as `demo:<id>` instead of an IAM code.
    it("treats authMode 'demo' as a login-required session and signs in as the picked Demo User", async () => {
        const done = svc.init();
        httpMock.expectOne(`${base}/bootstrap`).flush({
            features: { authMode: 'demo' },
            auth: { mock: true, demoUsers: [{ id: 'ra.analyst', displayName: 'Demo RA Analyst', title: 'RA' }] },
        });
        await tick();
        httpMock.expectOne(`${base}/auth/refresh`).flush({ error: 'no session' }, { status: 401, statusText: 'x' });
        await done;
        expect(svc.loginRequired()).toBe(true);
        expect(svc.demoUsers().map((u) => u.id)).toEqual(['ra.analyst']);

        const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        await svc.beginLogin('ra.analyst');
        expect(navigate).toHaveBeenCalledWith(['/auth/callback'], {
            queryParams: { code: 'demo:ra.analyst', state: sessionStorage.getItem('inspecto.pkce.state') },
        });
        navigate.mockRestore();
    });

    // RP-Initiated Logout 1.0. Without this the Inspecto session ends but the IdP's SSO session does
    // not, so the next sign-in completes with no credential prompt (BACKLOG §5).
    it('logout redirects to the provider end_session_endpoint when one is configured', async () => {
        const assign = spyOnRedirect();
        await signedInWith({
            authorizeUrl: 'https://idp/authorize',
            clientId: 'spa-1',
            endSessionUrl: 'https://idp/logout',
        });

        svc.logout();
        httpMock.expectOne(`${base}/auth/logout`).flush({ loggedOut: true });
        await tick();

        expect(svc.authenticated()).toBe(false); // local state dropped before the redirect
        const url = new URL(assign.mock.calls[0][0] as string);
        expect(url.origin + url.pathname).toBe('https://idp/logout');
        expect(url.searchParams.get('client_id')).toBe('spa-1');
        expect(url.searchParams.get('post_logout_redirect_uri')).toBe(`${window.location.origin}/sign-in`);
        assign.mockRestore();
    });

    it('logout stays in-app when the provider declares no end_session_endpoint', async () => {
        const assign = spyOnRedirect();
        await signedInWith({
            authorizeUrl: 'https://idp/authorize',
            clientId: 'spa-1',
        });

        svc.logout();
        httpMock.expectOne(`${base}/auth/logout`).flush({ loggedOut: true });
        await tick();

        expect(assign).not.toHaveBeenCalled();
        assign.mockRestore();
    });

    it('logout never redirects in offline mock mode, even with an endSessionUrl', async () => {
        const assign = spyOnRedirect();
        await signedInWith({
            clientId: 'spa-1',
            endSessionUrl: 'https://idp/logout',
            mock: true,
        });

        svc.logout();
        httpMock.expectOne(`${base}/auth/logout`).flush({ loggedOut: true });
        await tick();

        expect(assign).not.toHaveBeenCalled();
        assign.mockRestore();
    });
});
