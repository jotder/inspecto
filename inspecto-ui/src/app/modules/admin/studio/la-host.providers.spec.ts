import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SessionService } from 'app/inspecto/api';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { provideLaHostServices } from './la-host.providers';

/** The Inspecto host's side of LA_FEATURES: the flags ARE the SessionService signals, not copies of them. */
describe('provideLaHostServices - LA_FEATURES', () => {
    it('maps ops / exchange / geoLink onto SessionService.opsEnabled / exchangeEnabled / geoLinkEnabled, live', () => {
        const session = { opsEnabled: signal(false), exchangeEnabled: signal(true), geoLinkEnabled: signal(false) };
        TestBed.configureTestingModule({
            providers: [{ provide: SessionService, useValue: session }, ...provideLaHostServices()],
        });
        const f = TestBed.inject(LA_FEATURES);
        expect([f.ops(), f.exchange(), f.geoLink()]).toEqual([false, true, false]);
        session.opsEnabled.set(true);
        session.geoLinkEnabled.set(true);
        expect([f.ops(), f.exchange(), f.geoLink()]).toEqual([true, true, true]);
    });
});
