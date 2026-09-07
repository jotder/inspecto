import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { LensService, SessionService } from 'app/inspecto/api';
import { LENS_HOME, lensHomeRedirect } from './app.routes';

describe('lensHomeRedirect (W4 per-lens home page)', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('maps each lens to its documented home route', () => {
        expect(LENS_HOME).toEqual({ business: 'kpi-reports', builder: 'pipelines', ops: 'events' });
    });

    it("redirects to the current lens's home route", () => {
        const lens = TestBed.inject(LensService);
        // EDG-01 cell 6: the Ops home is conditional on the events feed being installed, so arm it here —
        // this case is about lens→route mapping, not about edition gating.
        TestBed.inject(SessionService).eventsEnabled.set(true);
        lens.selectLens('business');
        expect(TestBed.runInInjectionContext(() => lensHomeRedirect())).toBe('kpi-reports');
        lens.selectLens('ops');
        expect(TestBed.runInInjectionContext(() => lensHomeRedirect())).toBe('events');
        lens.selectLens('builder');
        expect(TestBed.runInInjectionContext(() => lensHomeRedirect())).toBe('pipelines');
    });

    /**
     * EDG-01 cell 6 (EDITIONS CP-13): `LENS_HOME.ops` names `events`, whose whole data source is the
     * optional `inspecto-events` module. Hiding the nav entry cannot protect `/` — nothing is clicked
     * there — so on a Personal build the Ops lens must land somewhere that exists.
     *
     * ⚠ Both directions, and only the Ops lens moves: a fallback applied to every lens would pass the
     * first assertion alone.
     */
    it('falls the Ops home back to pipelines when the events feed is not installed', () => {
        const lens = TestBed.inject(LensService);
        TestBed.inject(SessionService).eventsEnabled.set(false);
        lens.selectLens('ops');
        expect(TestBed.runInInjectionContext(() => lensHomeRedirect())).toBe('pipelines');
        // The other two lenses are untouched — their homes are core panes.
        lens.selectLens('business');
        expect(TestBed.runInInjectionContext(() => lensHomeRedirect())).toBe('kpi-reports');
        lens.selectLens('builder');
        expect(TestBed.runInInjectionContext(() => lensHomeRedirect())).toBe('pipelines');
    });

    /** ⛔ The const itself still documents the INTENT — the fallback is a deployment fact, not a remap. */
    it('leaves LENS_HOME.ops naming events regardless of the fallback', () => {
        expect(LENS_HOME.ops).toBe('events');
    });
});
