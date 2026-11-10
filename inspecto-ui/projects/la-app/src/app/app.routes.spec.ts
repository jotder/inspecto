import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
    ActivatedRouteSnapshot,
    Route,
    Router,
    RouterStateSnapshot,
    UrlSegment,
    UrlTree,
    provideRouter,
} from '@angular/router';
import { describe, expect, it } from 'vitest';
import { SessionService } from '@inspecto/core/auth/session.service';
import { BootstrapProduct } from '@inspecto/core/product/product-host';
import { areaGuard, laAppRoutes } from './app.routes';
import { PRODUCT } from './product.generated';
import { PRODUCT as TELCO } from './product.telco.generated';

const shell = laAppRoutes.find((r) => r.path === '' && r.children) as Route;
const child = (path: string): Route => shell.children!.find((r) => r.path === path) as Route;
/** Settings is matched, not pathed: one config for /settings and /settings/<section>. */
const settings = shell.children!.find((r) => r.matcher) as Route;

describe('la-app routes', () => {
    it('has the two OIDC guest routes outside the guarded shell, and one guarded shell', () => {
        expect(laAppRoutes.map((r) => r.path)).toEqual(['sign-in', 'auth/callback', 'embed/inv/:token', '']);
        expect(shell.canActivate?.length).toBe(1);
    });

    // PRODUCT-ASSEMBLY-1 PA-4/PA-5b: every area the Apps of EVERY Product this host builds declare (app.toon ->
    // product.generated.ts, product.telco.generated.ts) must have a route here, behind the area guard.
    it('routes every nav entry of both Products la-app builds, and lands on the landing page at the root', () => {
        for (const item of [...PRODUCT.nav, ...TELCO.nav]) {
            const r = child(item.path);
            expect(r, item.path).toBeTruthy();
            expect(r.loadChildren || r.loadComponent, item.path).toBeTruthy();
            // /entities stays open: the Space guide's columns step links there on every edition (Basic has no Entities area).
            expect(r.canActivate, item.path).toEqual(item.path === 'entities' ? undefined : [areaGuard]);
        }
        expect(child('').component).toBeTruthy();
        expect(child('**').redirectTo).toBe('');
    });

    it("resolves the console pages Inspecto Telco's Apps mount whole", async () => {
        for (const path of ['reconciliation', 'kpi-reports', 'incidents', 'cases', 'risk-scores', 'anomaly-models']) {
            const mod = (await child(path).loadChildren!()) as unknown as { default: Route[] };
            expect(Array.isArray(mod.default) && mod.default.length > 0, path).toBe(true);
        }
    }, 120_000);

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

    it('resolves the Add data page (LA-ONBOARD-1: Getting started links it)', async () => {
        expect(await child('add-data').loadComponent!()).toBeTruthy();
    }, 60_000);

    it('resolves the Settings page', async () => {
        expect(await settings.loadComponent!()).toBeTruthy();
    }, 60_000);

    // PA-5b: one guard for every App area - the page opens only when the Product's nav offers it. Operator 2026-10-21:
    // Basic (investigations false) has no Alerts, so /alerts is not reachable there; Standard opens it.
    it("opens an area only when the Product's nav offers it, else lands on the landing page", () => {
        const features = signal<Record<string, boolean>>({ investigations: false });
        const product = signal<BootstrapProduct | null>(null);
        TestBed.configureTestingModule({
            providers: [provideRouter([]), { provide: SessionService, useValue: { features, product } }],
        });
        const run = (path: string) =>
            TestBed.runInInjectionContext(() =>
                areaGuard(
                    { routeConfig: child(path) } as ActivatedRouteSnapshot,
                    { url: `/${path}` } as RouterStateSnapshot,
                ),
            );
        const home = (r: unknown) => r instanceof UrlTree && TestBed.inject(Router).serializeUrl(r) === '/';
        // No server Product: the generated nav under the feature rule (what la-nav.ts did before PA-4).
        expect(home(run('alerts')), 'Basic: no Investigations, no Alerts').toBe(true);
        expect(run('link-analysis')).toBe(true);
        features.set({ investigations: true });
        expect(run('alerts')).toBe(true);
        expect(home(run('reconciliation')), 'an Inspecto LA build has no Revenue Assurance').toBe(true);
        // The server resolved this Product's nav: it is the whole truth (a Space that switched Link Analysis off).
        product.set({ id: 'la', title: 'Inspecto LA', nav: [] });
        expect(home(run('link-analysis'))).toBe(true);
    });

    it('matches /settings and /settings/<section> with one route, and nothing deeper', () => {
        const seg = (...p: string[]) => p.map((x) => new UrlSegment(x, {}));
        expect(settings.matcher!(seg('settings'), {} as never, settings)?.consumed.length).toBe(1);
        expect(settings.matcher!(seg('settings', 'access'), {} as never, settings)?.posParams?.['section'].path).toBe(
            'access',
        );
        expect(settings.matcher!(seg('settings', 'a', 'b'), {} as never, settings)).toBeNull();
        expect(settings.matcher!(seg('alerts'), {} as never, settings)).toBeNull();
    });
});
