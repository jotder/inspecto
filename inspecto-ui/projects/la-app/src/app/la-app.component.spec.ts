import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { GammaConfigService } from '@gamma/services/config';
import { SessionService } from '@inspecto/core/auth/session.service';
import { schemeStorageKey } from '@inspecto/core/shell/scheme-preference.service';
import { BehaviorSubject } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LaAppComponent } from './la-app.component';

/** LA-FULL-CYCLE-TEST-1 phase 6 (finding 20): `auto` follows the browser, live; a remembered choice holds. */
describe('LaAppComponent colour scheme', () => {
    afterEach(() => {
        vi.unstubAllGlobals();
        localStorage.removeItem(schemeStorageKey(null));
        document.body.classList.remove('light', 'dark');
    });

    function create(prefersDark: boolean) {
        const listeners: (() => void)[] = [];
        const query = {
            matches: prefersDark,
            addEventListener: (_: string, fn: () => void) => listeners.push(fn),
            removeEventListener: vi.fn(),
        };
        vi.stubGlobal('matchMedia', () => query);
        const config$ = new BehaviorSubject<{ scheme: string; theme: string }>({
            scheme: 'auto',
            theme: 'theme-default',
        });
        const gamma = {
            config$,
            set config(v: { scheme: string }) {
                config$.next({ ...config$.value, ...v });
            },
        };
        TestBed.configureTestingModule({
            imports: [LaAppComponent],
            providers: [
                provideRouter([]),
                { provide: GammaConfigService, useValue: gamma },
                { provide: SessionService, useValue: { actor: signal<string | null>(null) } },
            ],
        });
        const fixture = TestBed.createComponent(LaAppComponent);
        fixture.detectChanges();
        TestBed.tick();
        return { query, listeners, fixture };
    }

    it('paints the browser scheme on auto and repaints when the browser switches', () => {
        const { query, listeners } = create(false);
        expect(document.body.classList.contains('light')).toBe(true);
        query.matches = true;
        listeners.forEach((fn) => fn());
        expect(document.body.classList.contains('dark')).toBe(true);
        expect(document.body.classList.contains('light')).toBe(false);
    });

    it('a remembered Light holds even when the browser asks for dark', () => {
        localStorage.setItem(schemeStorageKey(null), 'light');
        const { listeners } = create(true);
        expect(document.body.classList.contains('light')).toBe(true);
        listeners.forEach((fn) => fn());
        expect(document.body.classList.contains('light')).toBe(true);
    });
});
