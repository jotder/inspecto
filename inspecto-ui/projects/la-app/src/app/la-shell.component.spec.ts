import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';
import { SessionService } from '@inspecto/core/auth/session.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LA_APP_NAV } from './la-nav';
import { LaShellComponent } from './la-shell.component';
import { LandingComponent } from './landing.component';

describe('la-app shell and landing page', () => {
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
        const harness = await RouterTestingHarness.create('/');
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

    it('has no axe violations', async () => {
        await expectNoA11yViolations(await render());
    }, 30_000);
});
