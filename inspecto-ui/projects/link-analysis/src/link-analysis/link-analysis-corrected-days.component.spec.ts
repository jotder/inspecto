import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InvService, InvestigationDataChanges } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisCorrectedDaysComponent } from './link-analysis-corrected-days.component';

const BASE: InvestigationDataChanges = {
    id: 'inv-1',
    dataset: 'calls',
    window: null,
    lastLookAt: '2026-10-10T12:00:00Z',
    assessed: true,
    changes: [],
    changedDays: [],
    capped: false,
    readAt: '2026-10-11T00:00:00Z',
};

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisCorrectedDaysComponent],
    template: `<inspecto-link-analysis-corrected-days
        investigationId="inv-1"
        [reloadToken]="token()"
        (refresh)="refreshed = refreshed + 1"
    />`,
})
class HostComponent {
    token = signal(0);
    refreshed = 0;
}

function mount(read: ReturnType<typeof vi.fn>) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [provideNoopAnimations(), { provide: InvService, useValue: { investigationDataChanges: read } }],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return fixture;
}

describe('LinkAnalysisCorrectedDaysComponent', () => {
    it('says which day was corrected after the last look, offers a refresh, and has no a11y violations', async () => {
        const read = vi.fn().mockReturnValue(
            of({
                ...BASE,
                changes: [
                    {
                        day: '2026-10-06',
                        kind: 'REDELIVERED',
                        file: 'a.csv',
                        at: '2026-10-10T13:00:00Z',
                        pipeline: 'calls',
                    },
                    {
                        day: '2026-10-06',
                        kind: 'RENAMED_CORRECTION',
                        file: 'a2.csv',
                        at: '2026-10-10T14:00:00Z',
                        pipeline: 'calls',
                    },
                    {
                        day: '2026-10-05',
                        kind: 'PART_COUNT_CHANGED',
                        file: 'b.csv',
                        at: '2026-10-10T15:00:00Z',
                        pipeline: 'calls',
                    },
                ],
            }),
        );
        const fixture = mount(read);
        const el: HTMLElement = fixture.nativeElement;
        const rows = Array.from(el.querySelectorAll('[data-test="corrected-day"]'));
        expect(rows.length).toBe(2); // 06 corrected twice = one warning
        expect(rows[0].textContent).toContain('Day 2026-10-05 was corrected after you last looked');
        expect(rows[1].textContent).toContain('corrected File arrived under a new name'); // the newest of the 06th wins
        expect(el.querySelector('inspecto-alert')).toBeTruthy();
        expect(el.querySelector('[data-test="corrected-clears"]')?.textContent).toContain('until you run a step');
        (el.querySelector('[data-test="corrected-refresh"]') as HTMLButtonElement).click();
        expect(fixture.componentInstance.refreshed).toBe(1);
        await expectNoA11yViolations(el);
    });

    it('renders nothing when nothing changed, when not assessed, or when the read fails', () => {
        for (const read of [
            vi.fn().mockReturnValue(of(BASE)),
            vi.fn().mockReturnValue(of({ ...BASE, assessed: false, note: 'n' })),
            vi.fn().mockReturnValue(throwError(() => new Error('boom'))),
        ]) {
            TestBed.resetTestingModule();
            const fixture = mount(read);
            expect(fixture.nativeElement.querySelector('inspecto-alert')).toBeNull();
        }
    });

    it('re-reads when the reload token changes', () => {
        const read = vi.fn().mockReturnValue(of(BASE));
        const fixture = mount(read);
        expect(read).toHaveBeenCalledTimes(1);
        fixture.componentInstance.token.set(1);
        fixture.detectChanges();
        expect(read).toHaveBeenCalledTimes(2);
    });
});
