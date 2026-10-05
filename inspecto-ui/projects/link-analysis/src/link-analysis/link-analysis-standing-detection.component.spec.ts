import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { EventsService, LensService } from '@inspecto/core/api';
import { InvService, InvestigationHeader, StandingDetectionEnabled } from '@inspecto/link-analysis/api/inv.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisStandingDetectionComponent } from './link-analysis-standing-detection.component';
import { LinkAnalysisStandingMonitorComponent } from './link-analysis-standing-monitor.component';
import {
    STANDING_REFUSAL_CODES,
    STANDING_REFUSAL_HELP,
    standingDetectionErrorMessage,
    standingRefusalCode,
    summariseStandingEvents,
} from './standing-detection';

const HEADER: InvestigationHeader = {
    id: 'inv-1',
    title: null,
    owner: 'ana',
    dataset: 'transfers',
    sourceCol: 'payer',
    targetCol: 'payee',
    linkKindCol: null,
    createdAt: '2026-09-30T00:00:00Z',
    datasetVersion: null,
    parent: null,
};

const ENABLED: StandingDetectionEnabled = {
    rule: 'r1',
    investigation: 'inv-1',
    principal: 'sweep:inv-1',
    dataset: 'transfers',
    masking: { mode: 'typed', columns: ['payer:msisdn'] },
    enabledAt: '2026-10-04T10:00:00Z',
    replaced: false,
    authority: 'x',
};

const refusal = (code: string) =>
    new HttpErrorResponse({
        status: 422,
        error: { error: { message: `standing detection refused [${code}]: because` } },
    });

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisStandingDetectionComponent, LinkAnalysisStandingMonitorComponent],
    template: `<inspecto-link-analysis-standing-detection [investigation]="inv" rule="r1" />
        <inspecto-link-analysis-standing-monitor />`,
})
class HostComponent {
    readonly inv = HEADER;
}

function setup(opts: { cap?: boolean; enable?: () => unknown; events?: (f: { type?: string }) => unknown } = {}) {
    const inv = {
        enableStandingDetection: vi.fn(() => (opts.enable ? opts.enable() : of(ENABLED))),
        disableStandingDetection: vi.fn(() =>
            of({ rule: 'r1', investigation: 'inv-1', enabled: false, wasEnabled: true }),
        ),
    };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: LensService, useValue: { canAuthorAlertRules: () => opts.cap ?? true } },
            {
                provide: EventsService,
                useValue: { search: (f: { type?: string }) => (opts.events ? opts.events(f) : of([])) },
            },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const click = async (sel: string) => {
        (el.querySelector(sel) as HTMLButtonElement).click();
        await fixture.whenStable();
        fixture.detectChanges();
    };
    return { fixture, el, inv, click };
}

describe('standing detection helpers', () => {
    it('explains every refusal code in plain language with an action', () => {
        for (const code of STANDING_REFUSAL_CODES) {
            expect(STANDING_REFUSAL_HELP[code].means.length).toBeGreaterThan(20);
            expect(STANDING_REFUSAL_HELP[code].action.length).toBeGreaterThan(20);
            expect(standingDetectionErrorMessage(refusal(code))).toContain(STANDING_REFUSAL_HELP[code].means);
            expect(standingRefusalCode(refusal(code))).toBe(code);
        }
    });

    it('counts sweeps and refusals by code, flags a full page, and carries no ids', () => {
        const s = summariseStandingEvents(
            [{}, {}],
            [
                { attributes: { code: 'NOT_LEAD' } },
                { attributes: { code: 'NOT_LEAD' } },
                { attributes: { code: 'DATASET_GONE' } },
            ],
            500,
        );
        expect(s).toEqual({
            swept: 2,
            refused: 3,
            refusedByCode: [
                { code: 'NOT_LEAD', count: 2 },
                { code: 'DATASET_GONE', count: 1 },
            ],
            capped: false,
        });
        expect(summariseStandingEvents([{}, {}], [], 2).capped).toBe(true);
    });
});

describe('LinkAnalysisStandingDetectionComponent', () => {
    it('enables for the owner and shows principal, masking and status', async () => {
        const { el, inv, click } = setup();
        expect(el.querySelector('[data-test=standing-status]')!.textContent).toContain('Not enabled');
        await click('[data-test=standing-enable]');
        expect(inv.enableStandingDetection).toHaveBeenCalledWith('inv-1', 'r1');
        expect(el.querySelector('[data-test=standing-principal]')!.textContent).toContain('sweep:inv-1');
        expect(el.querySelector('[data-test=standing-masking]')!.textContent).toContain('payer:msisdn');
        expect(el.querySelector('[data-test=standing-status]')!.textContent).toContain('Enabled');
        await expectNoA11yViolations(el);
    });

    it('disables after enabling and says the rule stays bound', async () => {
        const { el, inv, click } = setup();
        expect(el.querySelector('[data-test=standing-disable]')).toBeNull();
        await click('[data-test=standing-enable]');
        await click('[data-test=standing-disable]');
        expect(inv.disableStandingDetection).toHaveBeenCalledWith('inv-1', 'r1');
        expect(el.querySelector('[data-test=standing-disabled]')!.textContent).toContain('NOT_ENABLED');
        expect(el.querySelector('[data-test=standing-status]')!.textContent).toContain('Not enabled');
        expect(el.querySelector('[data-test=standing-disable]')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('explains a non-owner 403 and offers no button without the capability', async () => {
        const denied = setup({ enable: () => throwError(() => new HttpErrorResponse({ status: 403 })) });
        await denied.click('[data-test=standing-enable]');
        expect(denied.el.textContent).toContain('Only the Investigation owner');
        TestBed.resetTestingModule();
        const noCap = setup({ cap: false });
        expect(noCap.el.querySelector('[data-test=standing-enable]')).toBeNull();
        expect(noCap.el.querySelector('[data-test=standing-no-cap]')).not.toBeNull();
    });

    it('turns a coded refusal into its plain-language explanation', async () => {
        const { el, click } = setup({ enable: () => throwError(() => refusal('ROLE_SHARE_ONLY')) });
        await click('[data-test=standing-enable]');
        expect(el.textContent).toContain('ROLE_SHARE_ONLY');
        expect(el.textContent).toContain('share it with the Investigation owner by user name');
        await expectNoA11yViolations(el);
    });
});

describe('LinkAnalysisStandingMonitorComponent', () => {
    it('shows aggregate swept and refused counts only', async () => {
        const { el, click } = setup({
            events: (f) =>
                of(
                    f.type === 'LINK_STANDING_DETECTION_SWEPT'
                        ? [{ attributes: { value: '3' }, message: 'x on inv-1' }]
                        : [{ attributes: { code: 'NOT_LEAD' }, message: 'x on inv-1' }],
                ),
        });
        await click('[data-test=monitor-refresh]');
        expect(el.querySelector('[data-test=monitor-counts]')!.textContent).toContain('1 sweep(s) read');
        expect(el.querySelector('[data-test=monitor-refusals]')!.textContent).toContain('NOT_LEAD × 1');
        expect(el.textContent).not.toContain('inv-1 ');
        await expectNoA11yViolations(el);
    });

    it('says so when the events cannot be read', async () => {
        const { el, click } = setup({ events: () => throwError(() => new HttpErrorResponse({ status: 403 })) });
        await click('[data-test=monitor-refresh]');
        expect(el.textContent).toContain('could not be read');
    });
});
