import { Route, UrlSegment } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { laAppRoutes } from './app.routes';
import { LA_APP_NAV } from './la-nav';

const shell = laAppRoutes.find((r) => r.path === '' && r.children) as Route;
const child = (path: string): Route => shell.children!.find((r) => r.path === path) as Route;
/** Settings is matched, not pathed: one config for /settings and /settings/<section>. */
const settings = shell.children!.find((r) => r.matcher) as Route;

describe('la-app routes', () => {
    it('has the two OIDC guest routes outside the guarded shell, and one guarded shell', () => {
        expect(laAppRoutes.map((r) => r.path)).toEqual(['sign-in', 'auth/callback', 'embed/inv/:token', '']);
        expect(shell.canActivate?.length).toBe(1);
    });

    it('routes every nav entry, and lands on the landing page at the root', () => {
        for (const item of LA_APP_NAV) {
            const r = child(item.path);
            expect(r, item.path).toBeTruthy();
            expect(r.loadChildren || r.loadComponent, item.path).toBeTruthy();
        }
        expect(child('').component).toBeTruthy();
        expect(child('**').redirectTo).toBe('');
    });

    // The lazy loaders import the whole Link Analysis / Geo graph (G6, MapLibre) into jsdom, which is slow on a loaded
    // runner: each resolves in its own test with a generous budget (the 5 s default failed once on CI for a smaller load).
    it('resolves the shared sign-in and callback pages', async () => {
        for (const r of laAppRoutes.slice(0, 2)) expect(await r.loadComponent!()).toBeTruthy();
    }, 60_000);

    it('has the anonymous embed viewer outside the guarded shell, with no guard', async () => {
        const r = laAppRoutes.find((x) => x.path === 'embed/inv/:token') as Route;
        expect(r.canActivate).toBeUndefined();
        expect(await r.loadComponent!()).toBeTruthy();
    }, 60_000);

    it('resolves the Link Analysis routes from the library', async () => {
        expect(await child('link-analysis').loadChildren!()).toBeTruthy();
    }, 60_000);

    it('resolves the Geo routes from the library', async () => {
        expect(await child('geo-map').loadChildren!()).toBeTruthy();
    }, 60_000);

    it('resolves the Entities pages from the library', async () => {
        const mod = (await child('entities').loadChildren!()) as unknown as { default: Route[] };
        expect(mod.default.map((r) => r.path)).toEqual(['', ':type', ':type/:key']);
    }, 60_000);

    it('resolves the Open in Inspecto LA deep-link page from the library', async () => {
        const mod = (await child('open').loadChildren!()) as unknown as { default: Route[] };
        expect(mod.default.map((r) => r.path)).toEqual(['']);
    }, 60_000);

    it('resolves the Entity Lists page from the library', async () => {
        expect(await child('entity-lists').loadComponent!()).toBeTruthy();
    }, 60_000);

    it('resolves the Settings page', async () => {
        expect(await settings.loadComponent!()).toBeTruthy();
    }, 60_000);

    it('matches /settings and /settings/<section> with one route, and nothing deeper', () => {
        const seg = (...p: string[]) => p.map((x) => new UrlSegment(x, {}));
        expect(settings.matcher!(seg('settings'), {} as never, settings)?.consumed.length).toBe(1);
        expect(settings.matcher!(seg('settings', 'access'), {} as never, settings)?.posParams?.['section'].path).toBe('access');
        expect(settings.matcher!(seg('settings', 'a', 'b'), {} as never, settings)).toBeNull();
        expect(settings.matcher!(seg('alerts'), {} as never, settings)).toBeNull();
    });
});
