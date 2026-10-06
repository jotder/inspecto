import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { NavigationService } from 'app/core/navigation/navigation.service';
import { InstalledModule, LensService, ModuleSettingsService } from 'app/inspecto/api';
import { SessionService } from 'app/inspecto/auth/session.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ModuleSettingsComponent } from './module-settings.component';

const mod = (
    id: string,
    offeringRole: string,
    features: string[],
    state: 'ACTIVE' | 'INERT' = 'ACTIVE',
): InstalledModule => ({
    id,
    title: id.toUpperCase(),
    buildRole: 'implementation',
    offeringRole,
    bindingTime: 'space',
    state,
    reasons: state === 'INERT' ? ['requires missing-module'] : [],
    provides: { features },
    requires: { modules: [] },
    enabledInSpace: true,
});

function setup(opts: { canAdminister?: boolean; save?: ModuleSettingsService['save'] } = {}) {
    const api = {
        modules: vi.fn(() =>
            of({
                modules: [
                    mod('engine', 'base', ['authoring']),
                    mod('recon', 'optional', ['reconciliation', 'breaks']),
                    mod('geo', 'provider', ['geoLink'], 'INERT'),
                ],
                diagnostics: [],
            }),
        ),
        get: vi.fn(() => of({ disabled: ['ghost'], inert: ['ghost'], installed: [], unreadable: false })),
        save: vi.fn(
            opts.save ?? ((disabled: string[]) => of({ disabled, inert: ['ghost'], installed: [], unreadable: false })),
        ),
    };
    const session = { reloadFeatures: vi.fn(() => Promise.resolve()) };
    const navigation = { get: vi.fn(() => of({})) };
    TestBed.configureTestingModule({
        imports: [ModuleSettingsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ModuleSettingsService, useValue: api },
            { provide: SessionService, useValue: session },
            { provide: NavigationService, useValue: navigation },
            { provide: LensService, useValue: { canAdminister: () => opts.canAdminister !== false } },
        ],
    });
    const fixture = TestBed.createComponent(ModuleSettingsComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const button = (label: string) =>
        Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === label);
    return { fixture, c: fixture.componentInstance, el, api, session, navigation, button };
}

describe('ModuleSettingsComponent', () => {
    it('groups Modules, gives switches only to switchable ones, lists unknown ids (no a11y violations)', async () => {
        const { el } = setup();
        expect(Array.from(el.querySelectorAll('h2')).map((h) => h.textContent?.trim())).toEqual([
            'Optional Modules (1)',
            'Provider Modules (1)',
            'Base Modules (1)',
        ]);
        expect(el.querySelectorAll('h1').length).toBe(1);
        const toggles = el.querySelectorAll('mat-slide-toggle');
        expect(toggles.length).toBe(2); // recon + geo; the Base module has none
        expect(el.querySelector('mat-slide-toggle[data-module="engine"]')).toBeNull();
        expect(el.textContent).toContain('Inert because: requires missing-module');
        expect(el.textContent).toContain("Unknown modules in this Space's settings");
        expect(el.textContent).toContain('ghost');
        await expectNoA11yViolations(el);
    });

    it('gives each switch an accessible name', () => {
        const { el } = setup();
        const input = el.querySelector('mat-slide-toggle[data-module="recon"] button[role="switch"]')!;
        expect(input.getAttribute('aria-label')).toBe('Enabled in this Space: RECON');
    });

    it('toggle then Save sends every Feature of the Module plus the inert ids, and refreshes features and nav', async () => {
        const { c, fixture, el, api, session, navigation, button } = setup();
        expect(button('Save modules')!.disabled).toBe(true);
        c.set(c.modules()[1], false);
        fixture.detectChanges();
        expect(el.textContent).toContain('Saving will hide Features');
        expect(button('Save modules')!.disabled).toBe(false);
        button('Save modules')!.click();
        fixture.detectChanges();
        expect(api.save).toHaveBeenCalledWith(['breaks', 'ghost', 'reconciliation']);
        await Promise.resolve();
        expect(session.reloadFeatures).toHaveBeenCalled();
        await Promise.resolve();
        expect(navigation.get).toHaveBeenCalled();
        expect(el.querySelector('div[tabindex="-1"]')!.textContent).toContain('Module settings saved');
        expect(c.hasUnsavedChanges()).toBe(false);
    });

    it('shows a 422 in the live region and stays dirty', () => {
        const err = new HttpErrorResponse({ status: 422, error: { error: { message: "'x' is not a feature" } } });
        const { c, fixture, el, button } = setup({ save: () => throwError(() => err) });
        c.set(c.modules()[1], false);
        fixture.detectChanges();
        button('Save modules')!.click();
        fixture.detectChanges();
        expect(el.querySelector('div[tabindex="-1"][aria-live="polite"]')!.textContent).toContain('not saved');
        expect(c.hasUnsavedChanges()).toBe(true);
    });

    it('is read-only without the administer capability', () => {
        const { el, button } = setup({ canAdminister: false });
        expect(button('Save modules')).toBeUndefined();
        expect(el.textContent).toContain('Administer capability required');
    });
});
