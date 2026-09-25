import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { SessionService } from 'app/inspecto/api';
import { authInterceptor } from 'app/inspecto/api/auth.interceptor';
import { environment } from '../../../environments/environment';
import { NavMenusService } from './menu-api';
import { landingGuard } from './menu-landing';
import { MenuTree } from './menu-types';

const TREE: MenuTree = {
    space: 'telco',
    version: 1,
    landing: 'cockpit',
    nodes: [
        { id: 'cockpit', title: 'Assurance cockpit', binding: { kind: 'dashboard', componentId: 'assurance_cockpit' } },
        {
            id: 'fm',
            title: 'Fraud',
            children: [{ id: 'cases', title: 'Cases', binding: { kind: 'route', route: '/cases' } }],
        },
    ],
};

interface Setup {
    tree?: MenuTree;
    menus?: Observable<MenuTree>;
    userLanding?: string;
    loginRequired?: boolean;
}

/** Run the guard once against a stubbed Menu read and session; returns the redirect URL and the read spy. */
async function run(s: Setup): Promise<{ url: string; get: ReturnType<typeof vi.fn> }> {
    const get = vi.fn(() => s.menus ?? of(s.tree ?? TREE));
    TestBed.configureTestingModule({
        providers: [
            provideRouter([]),
            { provide: NavMenusService, useValue: { get } },
            {
                provide: SessionService,
                useValue: {
                    loginRequired: () => s.loginRequired ?? false,
                    sessionSettled: () => Promise.resolve(),
                    demoUserLanding: () => s.userLanding,
                },
            },
        ],
    });
    const result = (await TestBed.runInInjectionContext(() => landingGuard({} as never, {} as never))) as UrlTree;
    return { url: TestBed.inject(Router).serializeUrl(result), get };
}

describe('landingGuard (UIE-7: per-user > Space > Home)', () => {
    it('opens the Space landing when the Demo User names none', async () => {
        expect((await run({})).url).toBe('/w/cockpit');
    });

    it('prefers the Demo User landing over the Space landing, and follows a route leaf to its route', async () => {
        expect((await run({ userLanding: 'cases' })).url).toBe('/cases');
    });

    it('falls back to the Space landing when the per-user one names no menu item, and warns once', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
        expect((await run({ userLanding: 'stale-once' })).url).toBe('/w/cockpit');
        expect(warn).toHaveBeenCalledTimes(1);
        expect(String(warn.mock.calls[0][0])).toContain('stale-once');
        warn.mockRestore();
    });

    it('opens the platform Home when nothing resolves', async () => {
        expect((await run({ tree: { ...TREE, landing: undefined } })).url).toBe('/home');
    });

    it('treats a landing naming a group as no landing', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
        expect((await run({ tree: { ...TREE, landing: 'fm' } })).url).toBe('/home');
        warn.mockRestore();
    });

    it('opens Home, never a blank screen, when the Menu tree cannot be read', async () => {
        expect((await run({ menus: throwError(() => new Error('503')) })).url).toBe('/home');
    });

    it('sends a visitor who must still sign in to Home without reading the Menu tree', async () => {
        const { url, get } = await run({ loginRequired: true });
        expect(url).toBe('/home');
        expect(get).not.toHaveBeenCalled();
    });
});

/**
 * The REAL sign-in → landing sequence (2026-09-25: one Demo RA Analyst sign-in was seen landing on /home). The
 * callback navigates to `/` from inside `completeLogin`'s subscriber, so the guard starts while the post-sign-in
 * bootstrap re-read — the only thing that sets `actor` — is still in flight. These pin that the guard can neither
 * read a half-initialised session nor reach Home through it: `authenticated` is set before the callback navigates,
 * and `sessionSettled()` is already the re-read by then. Only a failed Menu read opens Home, and it says so.
 */
describe('landingGuard after a Demo User sign-in (real SessionService, real Menu read)', () => {
    const base = environment.apiBaseUrl + '/v1';
    const tick = () => new Promise((r) => setTimeout(r, 0));
    const RA_TREE: MenuTree = {
        ...TREE,
        nodes: [
            ...TREE.nodes,
            { id: 'recon', title: 'Reconciliations', binding: { kind: 'route', route: '/reconciliation' } },
        ],
    };

    /** Boot a signed-out demo session, sign in as ra.analyst and start the guard exactly where the callback does. */
    async function signInAndLand(): Promise<{ http: HttpTestingController; landing: () => Promise<string> }> {
        sessionStorage.clear();
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(withXhr(), withInterceptors([authInterceptor])),
                provideHttpClientTesting(),
                provideRouter([]),
            ],
        });
        const session = TestBed.inject(SessionService);
        const http = TestBed.inject(HttpTestingController);
        const router = TestBed.inject(Router);
        const init = session.init();
        http.expectOne(`${base}/bootstrap`).flush({
            features: { authMode: 'demo' },
            auth: {
                mock: true,
                demoUsers: [{ id: 'ra.analyst', displayName: 'Demo RA Analyst', title: 'RA', landing: 'recon' }],
            },
        });
        await tick();
        http.expectOne(`${base}/auth/refresh`).flush({ error: 'no session' }, { status: 401, statusText: 'x' });
        await init;
        expect(session.loginRequired()).toBe(true);

        sessionStorage.setItem('inspecto.pkce.state', 's1');
        sessionStorage.setItem('inspecto.pkce.verifier', 'v1');
        let landing: Promise<string> | undefined;
        session.completeLogin('demo:ra.analyst', 's1').subscribe((ok) => {
            expect(ok).toBe(true);
            expect(session.loginRequired()).toBe(false); // the guard's first check can no longer send it Home
            landing = TestBed.runInInjectionContext(async () =>
                router.serializeUrl((await landingGuard({} as never, {} as never)) as UrlTree),
            );
        });
        http.expectOne(`${base}/auth/exchange`).flush({ accessToken: 'at' });
        return { http, landing: () => landing! };
    }

    it('lands on the per-user route even when the Menu read answers before the actor is known', async () => {
        const { http, landing } = await signInAndLand();
        const menu = http.expectOne(`${base}/nav/menus`);
        expect(menu.request.headers.get('Authorization')).toBe('Bearer at'); // the read already carries the new session
        menu.flush(RA_TREE); // worst order: the tree first, the actor-bearing re-read second
        await tick();
        http.expectOne(`${base}/bootstrap`).flush({ session: { authenticated: true, actor: 'ra.analyst' } });
        expect(await landing()).toBe('/reconciliation');
        http.verify();
    });

    it('lands on the per-user route when the actor is known before the Menu read answers', async () => {
        const { http, landing } = await signInAndLand();
        http.expectOne(`${base}/bootstrap`).flush({ session: { authenticated: true, actor: 'ra.analyst' } });
        await tick();
        http.expectOne(`${base}/nav/menus`).flush(RA_TREE);
        expect(await landing()).toBe('/reconciliation');
        http.verify();
    });

    it('a failed post-sign-in re-read loses only the per-user landing — the Space landing, never Home', async () => {
        const { http, landing } = await signInAndLand();
        http.expectOne(`${base}/nav/menus`).flush(RA_TREE);
        http.expectOne(`${base}/bootstrap`).flush({}, { status: 500, statusText: 'x' });
        expect(await landing()).toBe('/w/cockpit');
        http.verify();
    });

    it('opens Home, and says why, only when the Menu read itself fails', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
        const { http, landing } = await signInAndLand();
        http.expectOne(`${base}/bootstrap`).flush({ session: { authenticated: true, actor: 'ra.analyst' } });
        http.expectOne(`${base}/nav/menus`).flush({}, { status: 503, statusText: 'x' });
        expect(await landing()).toBe('/home');
        expect(warn).toHaveBeenCalledTimes(1);
        expect(String(warn.mock.calls[0][0])).toContain('Menu');
        warn.mockRestore();
        http.verify();
    });
});
