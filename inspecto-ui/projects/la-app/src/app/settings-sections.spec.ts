import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { OnboardingEventsService, OnboardingMetrics } from 'app/inspecto/onboarding/onboarding-events.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { signal } from '@angular/core';
import { vi } from 'vitest';
import { LensService } from 'app/inspecto/api';
import { SessionService } from 'app/inspecto/auth/session.service';
import { OnboardService } from '@inspecto/link-analysis/add-data/onboard.service';
import {
    AddDataSettingsComponent,
    durationWords,
    milestoneWords,
    OnboardingCountersSettingsComponent,
} from './settings-sections';

const METRICS: OnboardingMetrics = {
    recording: true,
    firstLaunch: '2026-10-22T10:00:00Z',
    launches: 2,
    spacesCreated: 1,
    firstSpace: '2026-10-22T10:01:00Z',
    firstSpaceSeconds: 60,
    firstDataset: '2026-10-22T10:03:00Z',
    firstDatasetSeconds: 180,
    firstGraph: '2026-10-22T10:07:30Z',
    firstGraphSeconds: 450,
    guideCompleted: null,
    guideCompletedSeconds: null,
    abandoned: { 'add_data:columns': 1, space: 3 },
};

function create(m: OnboardingMetrics | null) {
    TestBed.configureTestingModule({
        imports: [OnboardingCountersSettingsComponent],
        providers: [{ provide: OnboardingEventsService, useValue: { metrics: () => of(m) } }],
    });
    const f = TestBed.createComponent(OnboardingCountersSettingsComponent);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
}

describe('Getting started counters in Settings (LA-ONBOARD-1 OB-13)', () => {
    it('reads the milestones in plain words and where people stopped, most often first (no violations)', async () => {
        const el = create(METRICS);
        const text = el.querySelector('[data-testid="la-counters"]')?.textContent ?? '';
        expect(text).toContain('First graph');
        expect(text).toContain('After 7 min 30 s');
        expect(text).toContain('Not yet'); // the guide
        const stopped = Array.from(el.querySelectorAll('[data-testid="la-counters-stopped"] li')).map((l) =>
            l.textContent?.trim(),
        );
        expect(stopped).toEqual(['Creating a Space: 3', 'Add data: checking the columns: 1']);
        await expectNoA11yViolations(el);
    });

    it('says so when the server keeps no counters or will not show them', async () => {
        const el = create(null);
        expect(el.textContent).toContain('The counters could not be read');
        await expectNoA11yViolations(el);
    });

    it('durations and milestones read as words', () => {
        expect(durationWords(45)).toBe('45 s');
        expect(durationWords(250)).toBe('4 min 10 s');
        expect(durationWords(600)).toBe('10 min');
        expect(durationWords(7500)).toBe('2 h 5 min');
        expect(durationWords(3 * 86400)).toBe('3 days');
        expect(durationWords(null)).toBe('');
        expect(milestoneWords(null, null)).toBe('Not yet');
    });
});

describe('This Space → Add data in Settings (LA-ONBOARD-1 OB-7, phase 8)', () => {
    function make(signIn: boolean, admin: boolean) {
        const setDataSettings = vi.fn(() =>
            of({ selfImport: true, selfImportDefault: false, selfImportInForce: true }),
        );
        TestBed.configureTestingModule({
            imports: [AddDataSettingsComponent],
            providers: [
                {
                    provide: OnboardService,
                    useValue: {
                        dataSettings: () =>
                            of({ selfImport: null, selfImportDefault: false, selfImportInForce: false }),
                        setDataSettings,
                    },
                },
                { provide: SessionService, useValue: { authMode: signal(signIn ? 'oidc' : 'none') } },
                { provide: LensService, useValue: { canAdminister: signal(admin) } },
            ],
        });
        const f = TestBed.createComponent(AddDataSettingsComponent);
        f.detectChanges();
        return { f, el: f.nativeElement as HTMLElement, setDataSettings };
    }

    it('an administrator turns Add data on for the Space (no violations)', async () => {
        const { f, el, setDataSettings } = make(true, true);
        expect(el.querySelector('[data-testid="la-self-import-why"]')?.textContent).toContain('Off (the default here)');
        const input = el.querySelector(
            '[data-testid="la-self-import"] button, [data-testid="la-self-import"] input',
        ) as HTMLElement;
        input.click();
        f.detectChanges();
        expect(setDataSettings).toHaveBeenCalledWith(true);
        expect(el.querySelector('[data-testid="la-self-import-why"]')?.textContent).toContain('On.');
        await expectNoA11yViolations(el);
    });

    it('anyone else sees it read-only, and why', () => {
        const { el } = make(true, false);
        const input = el.querySelector(
            '[data-testid="la-self-import"] button, [data-testid="la-self-import"] input',
        ) as HTMLButtonElement;
        expect(input.disabled).toBe(true);
        expect(el.textContent).toContain('Only an administrator may change it.');
    });
});
