import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { LensService, ScreeningHit, ScreeningService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { allowedDecisions, hitTone, ScreeningComponent, scorePercent } from './screening.component';

const HIT: ScreeningHit = {
    id: 'sh-20261009120000-abcdef',
    state: 'open',
    version: 1,
    listId: 'sanctions',
    purpose: 'block',
    entry: 'vladimir putin',
    method: 'name',
    score: 0.9862,
    threshold: 0.85,
    subjectKey: 'c1',
    subjectName: 'Putin, Vladimir',
    subjectIdentifier: null,
    source: { job: 'nightly', runId: 'r1', dataset: 'customers' },
    raisedAt: '2026-10-09T12:00:00Z',
    raisedBy: 'job:screening.run:nightly',
    history: [{ state: 'open', by: 'job:screening.run:nightly', at: '2026-10-09T12:00:00Z' }],
};

async function create(overrides: Partial<Record<keyof ScreeningService, unknown>> = {}, canWork = true) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        hits: vi.fn(() => of({ hits: [HIT] })),
        hit: vi.fn(() => of(HIT)),
        decide: vi.fn(() => of({ ...HIT, state: 'dismissed', version: 2, decidedBy: 'ops-1' })),
        ...overrides,
    } as unknown as ScreeningService;
    TestBed.configureTestingModule({
        imports: [ScreeningComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ScreeningService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: LensService, useValue: { canWorkIncidents: () => canWork } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(ScreeningComponent);
    fixture.detectChanges();
    return { fixture, api, toastr };
}

const buttons = (el: HTMLElement): string[] =>
    Array.from(el.querySelectorAll('button')).map((b) => (b.textContent ?? '').trim());

describe('ScreeningComponent', () => {
    it('lists the open hits by default and opens one with its decisions', async () => {
        const { fixture, api } = await create();
        expect(api.hits).toHaveBeenCalledWith('open');
        fixture.componentInstance.open({ id: HIT.id });
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelector('#sh-title')?.textContent).toContain('Putin, Vladimir');
        expect(el.textContent).toContain('99 %');
        expect(buttons(el)).toEqual(expect.arrayContaining(['Escalate', 'Dismiss', 'Confirm match']));
        await expectNoA11yViolations(el);
    });

    it('requires a reason, then decides with the hit version and shows the server answer', async () => {
        const { fixture, api, toastr } = await create();
        const c = fixture.componentInstance;
        c.open({ id: HIT.id });
        fixture.detectChanges();
        c.decide('dismiss');
        fixture.detectChanges();
        expect(api.decide).not.toHaveBeenCalled();
        expect(
            fixture.nativeElement.querySelector('.mat-mdc-form-field-subscript-wrapper mat-error')?.textContent,
        ).toContain('A reason is required');

        c.reason.setValue('different date of birth');
        c.decide('dismiss');
        fixture.detectChanges();
        expect(api.decide).toHaveBeenCalledWith(HIT.id, 'dismiss', 'different date of birth', 1);
        expect(toastr.success).toHaveBeenCalledWith('Screening Hit dismissed');
        expect(c.selected()?.state).toBe('dismissed');
        expect(c.decisions()).toEqual([]);
    });

    it("surfaces the server's refusal of a stale decision", async () => {
        const { fixture } = await create({
            decide: vi.fn(() =>
                throwError(() => ({
                    status: 409,
                    error: { error: { message: 'screening hit is at version 2, not 1' } },
                })),
            ),
        });
        const c = fixture.componentInstance;
        c.open({ id: HIT.id });
        c.reason.setValue('r');
        c.decide('confirm');
        fixture.detectChanges();
        expect(c.decideError()).toBeTruthy();
        expect(fixture.nativeElement.textContent).toContain('Not decided');
    });

    it('offers no decision without canWorkIncidents, on a forged record, or on a final state', async () => {
        const { fixture } = await create({}, false);
        const c = fixture.componentInstance;
        c.open({ id: HIT.id });
        fixture.detectChanges();
        expect(c.decisions()).toEqual([]);
        expect(buttons(fixture.nativeElement)).not.toContain('Dismiss');
        expect(allowedDecisions('escalated')).toEqual(['dismiss', 'confirm']);
        expect(allowedDecisions('confirmed')).toEqual([]);
        expect(allowedDecisions('dismissed')).toEqual([]);
    });

    it('explains an absent Screening module instead of failing', async () => {
        const { fixture, toastr } = await create({ hits: vi.fn(() => throwError(() => ({ status: 503 }))) });
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('Screening is not available on this deployment');
        expect(el.querySelector('inspecto-data-table')).toBeNull();
        expect(toastr.error).not.toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('maps states to tones and scores to percentages', () => {
        expect(hitTone('confirmed')).toBe('error');
        expect(hitTone('dismissed')).toBe('success');
        expect(hitTone('escalated')).toBe('warning');
        expect(hitTone('open')).toBe('info');
        expect(scorePercent(0.85)).toBe('85 %');
        expect(scorePercent(undefined)).toBe('');
    });
});
