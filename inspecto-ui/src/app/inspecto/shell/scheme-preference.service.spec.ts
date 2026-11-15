import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { GammaConfigService } from '@gamma/services/config';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionService } from '../auth/session.service';
import { SchemePreferenceService, schemeStorageKey } from './scheme-preference.service';

/** LA-FULL-CYCLE-TEST-1 phase 6 (finding 20): Match system / Light / Dark, remembered per person in this browser. */
describe('SchemePreferenceService', () => {
    const actor = signal<string | null>('ada');
    let applied: unknown[];

    function create(): SchemePreferenceService {
        applied = [];
        const config = {
            set config(v: unknown) {
                applied.push(v);
            },
        };
        TestBed.configureTestingModule({
            providers: [
                { provide: GammaConfigService, useValue: config },
                { provide: SessionService, useValue: { actor } },
            ],
        });
        return TestBed.inject(SchemePreferenceService);
    }

    beforeEach(() => {
        actor.set('ada');
        localStorage.removeItem(schemeStorageKey('ada'));
        localStorage.removeItem(schemeStorageKey('bob'));
        localStorage.removeItem(schemeStorageKey(null));
    });
    afterEach(() => vi.restoreAllMocks());

    it('follows the browser until a choice is made, and does nothing before the host starts it', () => {
        const svc = create();
        TestBed.tick();
        expect(applied).toEqual([]); // the console never starts it: its configured scheme stands
        svc.start();
        TestBed.tick();
        expect(svc.choice()).toBe('auto');
        expect(applied).toEqual([{ scheme: 'auto' }]);
    });

    it('a choice is applied and remembered for that person only; signing in as another person brings back theirs', () => {
        const svc = create();
        svc.start();
        TestBed.tick();
        svc.choose('light');
        expect(applied.at(-1)).toEqual({ scheme: 'light' });
        expect(localStorage.getItem(schemeStorageKey('ada'))).toBe('light');
        actor.set('bob');
        TestBed.tick();
        expect(svc.choice()).toBe('auto'); // bob never chose
        svc.choose('dark');
        actor.set('ada');
        TestBed.tick();
        expect(svc.choice()).toBe('light');
        expect(applied.at(-1)).toEqual({ scheme: 'light' });
    });

    it('an unreadable or blocked store means the default, never an error; a stored nonsense value is ignored', () => {
        localStorage.setItem(schemeStorageKey('ada'), 'purple');
        const svc = create();
        svc.start();
        TestBed.tick();
        expect(svc.choice()).toBe('auto');
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
            throw new Error('blocked');
        });
        expect(() => svc.choose('dark')).not.toThrow();
        expect(svc.choice()).toBe('dark'); // held for this visit
        vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
            throw new Error('blocked');
        });
        actor.set('bob');
        TestBed.tick();
        expect(svc.choice()).toBe('auto');
    });

    it('with nobody signed in (Basic) one choice is kept for this browser', () => {
        actor.set(null);
        const svc = create();
        svc.start();
        TestBed.tick();
        svc.choose('dark');
        expect(localStorage.getItem('inspecto.scheme')).toBe('dark');
    });
});
