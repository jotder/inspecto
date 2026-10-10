import { Component, NO_ERRORS_SCHEMA } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, RouterOutlet } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { NEVER } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaNavigationService } from '@gamma/components/navigation';
import { GammaMediaWatcherService } from '@gamma/services/media-watcher';
import { NavigationService } from 'app/core/navigation/navigation.service';
import { AccessStateService } from 'app/inspecto/access/access-state.service';
import { BrandingService, LensService } from 'app/inspecto/api';
import { ClassicLayoutComponent } from './classic.component';

describe('ClassicLayoutComponent', () => {
    /** LA-A11Y-AUDIT-1 (axe landmark-one-main / region): the routed content sits in a `main` landmark. */
    it('wraps the router outlet in a main element (landmark)', () => {
        TestBed.configureTestingModule({
            providers: [
                provideRouter([]),
                { provide: NavigationService, useValue: { navigation$: NEVER } },
                { provide: GammaMediaWatcherService, useValue: { onMediaChange$: NEVER } },
                { provide: GammaNavigationService, useValue: {} },
                { provide: BrandingService, useValue: { logoUrl: () => '', caption: () => '', footerText: () => '' } },
                { provide: AccessStateService, useValue: { filterNav: (n: unknown) => n } },
                { provide: LensService, useValue: {} },
                { provide: MatDialog, useValue: {} },
            ],
        });
        // The shell's child components are not under test — keep the layout's own template, drop the kids.
        TestBed.overrideComponent(ClassicLayoutComponent, {
            set: { imports: [RouterOutlet], schemas: [NO_ERRORS_SCHEMA] },
        });
        const fixture = TestBed.createComponent(ClassicLayoutComponent);
        fixture.detectChanges();
        const main = (fixture.nativeElement as HTMLElement).querySelector('main');
        expect(main).toBeTruthy();
        expect(main?.querySelector('router-outlet')).toBeTruthy();
        // The rest of the shell is landmarked too (axe `region`): footer, named navigation, status loading bar.
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('footer')).toBeTruthy();
        expect(root.querySelector('gamma-vertical-navigation')?.getAttribute('role')).toBe('navigation');
        expect(root.querySelector('gamma-vertical-navigation')?.getAttribute('aria-label')).toBe('Main');
        expect(root.querySelector('gamma-loading-bar')?.getAttribute('role')).toBe('status');
    });

    /** Link Analysis takes the footer's 56px for its canvas; every other route keeps it. */
    it('hides the footer (and hands its height back) only on a route with data.hideShellFooter', async () => {
        @Component({ template: '' })
        class Page {}
        TestBed.configureTestingModule({
            providers: [
                provideRouter([
                    { path: 'la', data: { hideShellFooter: true }, component: Page },
                    { path: 'home', component: Page },
                ]),
                { provide: NavigationService, useValue: { navigation$: NEVER } },
                { provide: GammaMediaWatcherService, useValue: { onMediaChange$: NEVER } },
                { provide: GammaNavigationService, useValue: {} },
                { provide: BrandingService, useValue: { logoUrl: () => '', caption: () => '', footerText: () => '' } },
                { provide: AccessStateService, useValue: { filterNav: (n: unknown) => n } },
                { provide: LensService, useValue: {} },
                { provide: MatDialog, useValue: {} },
            ],
        });
        TestBed.overrideComponent(ClassicLayoutComponent, {
            set: { imports: [RouterOutlet], schemas: [NO_ERRORS_SCHEMA] },
        });
        const fixture = TestBed.createComponent(ClassicLayoutComponent);
        const router = TestBed.inject(Router);
        const root = fixture.nativeElement as HTMLElement;
        const wrapper = (): HTMLElement => root.querySelector('main')!.parentElement as HTMLElement;

        await router.navigateByUrl('/home');
        fixture.detectChanges();
        expect(root.querySelector('footer')).toBeTruthy();
        expect(wrapper().style.getPropertyValue('--shell-chrome-height')).toBe('');

        await router.navigateByUrl('/la');
        fixture.detectChanges();
        expect(root.querySelector('footer')).toBeNull();
        expect(wrapper().style.getPropertyValue('--shell-chrome-height')).toBe('4rem'); // header only

        await router.navigateByUrl('/home');
        fixture.detectChanges();
        expect(root.querySelector('footer')).toBeTruthy();
    });
});
