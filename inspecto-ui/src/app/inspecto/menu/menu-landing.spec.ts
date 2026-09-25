import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { SessionService } from 'app/inspecto/api';
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
