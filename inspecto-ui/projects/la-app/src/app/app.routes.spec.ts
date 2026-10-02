import { Route } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { laAppRoutes } from './app.routes';
import { LA_APP_NAV } from './la-nav';

const shell = laAppRoutes.find((r) => r.path === '' && r.children) as Route;
const child = (path: string): Route => shell.children!.find((r) => r.path === path) as Route;

describe('la-app routes', () => {
    it('has the two OIDC guest routes outside the guarded shell, and one guarded shell', () => {
        expect(laAppRoutes.map((r) => r.path)).toEqual(['sign-in', 'auth/callback', '']);
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

    it('resolves the Link Analysis routes from the library', async () => {
        expect(await child('link-analysis').loadChildren!()).toBeTruthy();
    }, 60_000);

    it('resolves the Geo routes from the library', async () => {
        expect(await child('geo-map').loadChildren!()).toBeTruthy();
    }, 60_000);

    it('resolves the Entity Lists page from the library', async () => {
        expect(await child('entity-lists').loadComponent!()).toBeTruthy();
    }, 60_000);
});
