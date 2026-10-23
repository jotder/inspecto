import { NextStepsDialog } from './next-steps.dialog';
import { LA_APP_SHORTCUTS, ShortcutsHelpDialog } from 'app/inspecto/shortcuts-help.dialog';
import { MatDialog } from '@angular/material/dialog';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { APP_ENVIRONMENT } from '@inspecto/core/api/app-environment';
import { SessionService } from '@inspecto/core/auth/session.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { SpaceGuideService } from '@inspecto/core/onboarding/space-guide.service';
import { LensService } from 'app/inspecto/api';
import { of } from 'rxjs';
import { LA_PRODUCT_NAME } from './la-brand';
import { LA_APP_NAV, LA_APP_SETTINGS_NAV } from './la-nav';
import { LA_RAIL_EXPANDED_KEY, LaShellComponent } from './la-shell.component';
import { LandingComponent } from './landing.component';

describe('la-app shell and landing page', () => {
    beforeEach(() => localStorage.removeItem(LA_RAIL_EXPANDED_KEY));

    let harness: RouterTestingHarness;

    async function render(features: Record<string, boolean> = { geoMap: true, investigations: true }) {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideNoopAnimations(),
                { provide: LensService, useValue: { canAuthorWorkbench: signal(false), canManageIncidents: signal(false) } },
                {
                    provide: SessionService,
                    useValue: {
                        authMode: signal('none'),
                        actorName: signal(''),
                        loginRequired: signal(false),
                        features: signal(features),
                    },
                },
                // A set-up install (Space, data, columns, a graph): the landing page shows its area cards, no guide.
                {
                    provide: SpaceGuideService,
                    useValue: {
                        load: () => of({ hasSpace: true, datasets: 1, columnsDescribed: true, views: 1, lists: 1 }),
                    },
                },
                provideRouter([
                    { path: '', component: LaShellComponent, children: [{ path: '', component: LandingComponent }] },
                ]),
            ],
        });
        harness = await RouterTestingHarness.create('/');
        harness.detectChanges();
        return harness.routeNativeElement as HTMLElement;
    }

    it('renders the nav and one landing card per area, linking the same routes', async () => {
        const el = await render();
        const hrefs = (sel: string) =>
            [...el.querySelectorAll<HTMLAnchorElement>(sel)].map((a) => a.getAttribute('href'));
        const expected = LA_APP_NAV.map((n) => '/' + n.path);
        expect(hrefs('nav[aria-label="Areas"] a')).toEqual([...expected, '/settings']);
        expect(hrefs('ul a')).toEqual(expected);
        expect(el.querySelector('h1')?.textContent?.trim()).toBe('Inspecto LA');
        expect(el.querySelector('main')).toBeTruthy();
    });

    // ED-1 (LA-EDITIONS-1): the product is "Inspecto LA"; the capability area inside it keeps "Link Analysis".
    it('brands the app Inspecto LA and keeps Link Analysis as the name of its area', async () => {
        localStorage.setItem(LA_RAIL_EXPANDED_KEY, '1');
        const el = await render();
        expect(LA_PRODUCT_NAME).toBe('Inspecto LA');
        const home = el.querySelector('aside a[href="/"]') as HTMLElement;
        expect(home.getAttribute('aria-label')).toBe('Inspecto LA home');
        expect(home.textContent?.trim()).toBe('Inspecto LA');
        expect(el.querySelector('h1')?.textContent?.trim()).toBe('Inspecto LA');
        expect(el.querySelector('nav[aria-label="Areas"] a[href="/link-analysis"]')?.textContent?.trim()).toBe(
            'Link Analysis',
        );
        await expectNoA11yViolations(el);
    });

    it('offers Geo and Alerts in the rail and on the landing page in Standard', async () => {
        const el = await render();
        for (const path of ['/geo-map', '/alerts']) {
            expect(el.querySelector(`nav[aria-label="Areas"] a[href="${path}"]`), path).toBeTruthy();
            expect(el.querySelector(`ul a[href="${path}"]`), path).toBeTruthy();
        }
    });

    // D-LAE5 (operator 2026-10-20): the Geo map is Standard and up, so Basic's `/bootstrap` says geoMap false and the area is not offered.
    // Operator 2026-10-21: the Alerts here are the standing-detection Alerts behind Investigations, so Basic (investigations false) hides them too.
    it('does not offer Geo or Alerts in the rail or on the landing page in Basic', async () => {
        const el = await render({ geoMap: false, investigations: false });
        for (const path of ['/geo-map', '/alerts']) {
            expect(el.querySelector(`nav[aria-label="Areas"] a[href="${path}"]`), path).toBeNull();
            expect(el.querySelector(`ul a[href="${path}"]`), path).toBeNull();
        }
        // the other areas stay
        expect(el.querySelector('nav[aria-label="Areas"] a[href="/link-analysis"]')).toBeTruthy();
        expect(el.querySelector('nav[aria-label="Areas"] a[href="/entities"]')).toBeTruthy();
        expect(el.querySelector('ul a[href="/link-analysis"]')).toBeTruthy();
    });

    it('offers the Settings link to every user, last in the rail and not on the landing page', async () => {
        const el = await render();
        const links = [...el.querySelectorAll<HTMLAnchorElement>('nav[aria-label="Areas"] a')];
        expect(links.at(-1)?.getAttribute('href')).toBe('/' + LA_APP_SETTINGS_NAV.path);
        expect(links.at(-1)?.textContent?.trim()).toBe(LA_APP_SETTINGS_NAV.label);
        expect(el.querySelector('ul a[href="/settings"]')).toBeNull();
    });

    it('is a left rail: three areas, Space switcher and user menu inside it, no header or footer', async () => {
        const el = await render();
        const rail = el.querySelector('aside') as HTMLElement;
        expect(rail.querySelector('nav[aria-label="Areas"]')).toBeTruthy();
        expect(rail.querySelectorAll('nav[aria-label="Areas"] a').length).toBe(LA_APP_NAV.length + 1);
        expect(rail.querySelector('inspecto-space-switcher')).toBeTruthy();
        expect(rail.querySelector('user')).toBeTruthy();
        expect([...el.querySelectorAll('header')].filter((h) => !h.closest('main'))).toEqual([]);
        expect(el.querySelector('footer')).toBeNull();
    });

    it('provides the chrome height variable the Link Analysis page reads: 0 beside the rail, 3rem top bar when narrow', async () => {
        const el = await render();
        const host = el.matches('la-app-shell') ? el : (el.querySelector('la-app-shell') as HTMLElement);
        expect(host.className).toContain('md:[--shell-chrome-height:0rem]');
        expect(host.className).toContain('[--shell-chrome-height:3rem]');
    });

    it('marks the open area with aria-current and keeps each area nameable when slim', async () => {
        TestBed.configureTestingModule({});
        const el = await render();
        const links = [...el.querySelectorAll<HTMLAnchorElement>('nav[aria-label="Areas"] a')];
        // Landing is "/", so no area is current yet; every link still has a text name (visually hidden when slim).
        expect(links.every((a) => a.getAttribute('aria-current') === null)).toBe(true);
        expect(links.map((a) => a.textContent?.trim())).toEqual([...LA_APP_NAV.map((n) => n.label), LA_APP_SETTINGS_NAV.label]);
    });

    it('is slim by default, expands on the toggle, and remembers the choice', async () => {
        const el = await render();
        const toggle = () => el.querySelector<HTMLButtonElement>('button[aria-expanded]') as HTMLButtonElement;
        expect(toggle().getAttribute('aria-expanded')).toBe('false');
        expect(toggle().getAttribute('aria-label')).toBe('Expand navigation');
        toggle().click();
        harness.detectChanges();
        expect(toggle().getAttribute('aria-expanded')).toBe('true');
        expect(localStorage.getItem(LA_RAIL_EXPANDED_KEY)).toBe('1');
        expect(el.querySelector('aside')?.className).toContain('md:w-56');
        expect(el.textContent).toContain(TestBed.inject(APP_ENVIRONMENT).footerText);
    });

    it('starts expanded when the remembered choice says so', async () => {
        localStorage.setItem(LA_RAIL_EXPANDED_KEY, '1');
        const el = await render();
        expect(el.querySelector('button[aria-expanded]')?.getAttribute('aria-expanded')).toBe('true');
    });

    it('has no axe violations', async () => {
        await expectNoA11yViolations(await render());
    }, 30_000);

    it('offers What can I do next? in the rail on every screen; it opens the next-steps dialog (UC-15 rule 4)', async () => {
        const el = await render();
        const open = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({} as never);
        const button = el.querySelector('[data-testid="la-next-steps-open"]') as HTMLButtonElement;
        expect(button.getAttribute('aria-label')).toBe('What can I do next?');
        button.click();
        expect(open).toHaveBeenCalledWith(NextStepsDialog, expect.anything());
    });

    it('opens the keyboard-shortcuts help on ? - its own list - but not while typing in a field', async () => {
        const el = await render();
        const open = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({} as never);
        const input = document.createElement('input');
        el.appendChild(input);
        input.dispatchEvent(new KeyboardEvent('keydown', { key: '?', bubbles: true }));
        expect(open).not.toHaveBeenCalled();
        document.body.dispatchEvent(new KeyboardEvent('keydown', { key: '?', bubbles: true }));
        expect(open).toHaveBeenCalledWith(ShortcutsHelpDialog, expect.objectContaining({ data: { shortcuts: LA_APP_SHORTCUTS } }));
    });
});
