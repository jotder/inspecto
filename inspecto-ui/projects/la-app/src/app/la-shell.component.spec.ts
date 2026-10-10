import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { APP_ENVIRONMENT } from '@inspecto/core/api/app-environment';
import { SessionService } from '@inspecto/core/auth/session.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LA_APP_NAV } from './la-nav';
import { LA_RAIL_EXPANDED_KEY, LaShellComponent } from './la-shell.component';
import { LandingComponent } from './landing.component';

describe('la-app shell and landing page', () => {
    beforeEach(() => localStorage.removeItem(LA_RAIL_EXPANDED_KEY));

    let harness: RouterTestingHarness;

    async function render() {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideNoopAnimations(),
                {
                    provide: SessionService,
                    useValue: { authMode: signal('none'), actorName: signal(''), loginRequired: signal(false) },
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
        expect(hrefs('nav[aria-label="Areas"] a')).toEqual(expected);
        expect(hrefs('ul a')).toEqual(expected);
        expect(el.querySelector('h1')?.textContent).toContain('Link Analysis');
        expect(el.querySelector('main')).toBeTruthy();
    });

    it('is a left rail: three areas, Space switcher and user menu inside it, no header or footer', async () => {
        const el = await render();
        const rail = el.querySelector('aside') as HTMLElement;
        expect(rail.querySelector('nav[aria-label="Areas"]')).toBeTruthy();
        expect(rail.querySelectorAll('nav[aria-label="Areas"] a').length).toBe(LA_APP_NAV.length);
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
        expect(links.map((a) => a.textContent?.trim())).toEqual(LA_APP_NAV.map((n) => n.label));
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
});
