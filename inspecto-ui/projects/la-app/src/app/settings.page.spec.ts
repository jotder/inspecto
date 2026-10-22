import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { LensService } from 'app/inspecto/api';
import { SessionService } from 'app/inspecto/auth/session.service';
import { LaLimitsService } from '@inspecto/link-analysis/link-analysis/la-limits.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { laAppRoutes } from './app.routes';

/** The app's own Settings route (one matcher for /settings and /settings/<section>), without the guarded shell around it. */
const settingsRoute = laAppRoutes.find((r) => r.path === '' && r.children)!.children!.find((r) => r.matcher)!;

describe('SettingsPageComponent (compact master-detail, split by audience)', () => {
    async function render(
        opts: {
            author?: boolean;
            access?: boolean;
            items?: unknown[];
            url?: string;
            signIn?: boolean;
            admin?: boolean;
        } = {},
    ) {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideNoopAnimations(),
                { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
                {
                    provide: LensService,
                    useValue: {
                        canAuthorWorkbench: signal(opts.author ?? false),
                        canConfigureAccess: signal(opts.access ?? false),
                        canAdminister: signal(opts.admin ?? false),
                    },
                },
                { provide: SessionService, useValue: { authMode: signal(opts.signIn ? 'oidc' : 'none') } },
                {
                    provide: LaLimitsService,
                    useValue: {
                        items: signal(opts.items ?? []),
                        loading: signal(false),
                        loadError: signal(null),
                        podScoped: signal(false),
                        personal: () => null,
                        setPersonal: () => undefined,
                    },
                },
                provideRouter([settingsRoute]),
            ],
        });
        const harness = await RouterTestingHarness.create(opts.url ?? '/settings');
        harness.detectChanges();
        return harness.routeNativeElement as HTMLElement;
    }

    const names = (el: HTMLElement) =>
        [...el.querySelectorAll<HTMLButtonElement>('nav button')].map((b) => b.dataset['section']);
    const readOnly = (el: HTMLElement, id: string) =>
        !!el.querySelector(`nav button[data-section="${id}"] inspecto-chip`);

    it('groups the sections by who they are for: my settings, this Space, this server', async () => {
        const el = await render();
        const groups = [...el.querySelectorAll('nav [role="group"]')].map((g) => ({
            label: g.firstElementChild?.textContent?.trim(),
            sections: [...g.querySelectorAll<HTMLButtonElement>('button')].map((b) => b.dataset['section']),
        }));
        expect(groups).toEqual([
            { label: 'My settings', sections: ['my-browser'] },
            { label: 'This Space', sections: ['link-analysis', 'analysis-limits', 'add-data', 'access'] },
            { label: 'This server', sections: ['host-resources', 'getting-started-counters'] },
        ]);
    });

    it('opens the first section when none is named, and marks it current', async () => {
        const el = await render();
        expect(el.querySelector('nav button[aria-current="true"]')?.getAttribute('data-section')).toBe('my-browser');
    });

    it('opens the section the URL names', async () => {
        const el = await render({ url: '/settings/access' });
        expect(el.querySelector('nav button[aria-current="true"]')?.getAttribute('data-section')).toBe('access');
    });

    it('lists every section for every user but marks the ones they may not change read-only', async () => {
        const plain = await render();
        expect(names(plain)).toHaveLength(7);
        expect(readOnly(plain, 'my-browser')).toBe(false);
        for (const id of ['link-analysis', 'analysis-limits', 'access', 'host-resources'])
            expect(readOnly(plain, id), id).toBe(true);
    });

    it('drops the read-only mark where the user holds the privilege (the server marks each limit editable)', async () => {
        const el = await render({
            author: true,
            access: true,
            items: [
                { key: 'a', layer: 'space', editable: true },
                { key: 'b', layer: 'install', editable: false },
            ],
        });
        expect(readOnly(el, 'link-analysis')).toBe(false);
        expect(readOnly(el, 'access')).toBe(false);
        expect(readOnly(el, 'analysis-limits')).toBe(false);
        expect(readOnly(el, 'host-resources')).toBe(true);
    });

    it('lists the Getting started counters, read-only, with no sign-in (LA-ONBOARD-1 OB-13)', async () => {
        const noSignIn = await render();
        expect(names(noSignIn)).toContain('getting-started-counters');
        expect(readOnly(noSignIn, 'getting-started-counters')).toBe(true);
    });

    it('with sign-in, lists the counters to an administrator only', async () => {
        expect(names(await render({ signIn: true }))).not.toContain('getting-started-counters');
    });

    it('with sign-in, an administrator sees the counters', async () => {
        expect(names(await render({ signIn: true, admin: true }))).toContain('getting-started-counters');
    });

    it('has no axe violations', async () => {
        await expectNoA11yViolations(await render());
    }, 30_000);
});
