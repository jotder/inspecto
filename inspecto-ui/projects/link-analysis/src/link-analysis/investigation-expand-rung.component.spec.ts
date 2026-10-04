import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InvestigationExpandRungComponent } from './investigation-expand-rung.component';

function setup() {
    TestBed.configureTestingModule({
        imports: [InvestigationExpandRungComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(InvestigationExpandRungComponent);
    fixture.detectChanges();
    return { fixture, cmp: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

describe('InvestigationExpandRungComponent (LA-SPA-OWED-SURFACES-1)', () => {
    it('blank = server defaults; set fields are sent as numbers; bounds and min<=max are enforced in place', async () => {
        const { fixture, cmp, el } = setup();
        await expectNoA11yViolations(el);
        expect(cmp.rung()).toEqual({});

        cmp.form.patchValue({
            budget: 500,
            direction: 'out',
            window: 'full',
            minEvents: 2,
            minDistinctDays: 3,
            candidateDegreeMin: 0,
            candidateDegreeMax: 50,
            maxFanOut: 10,
        });
        expect(cmp.rung()).toEqual({
            budget: 500,
            direction: 'out',
            window: 'full',
            minEvents: 2,
            minDistinctDays: 3,
            candidateDegreeMin: 0,
            candidateDegreeMax: 50,
            maxFanOut: 10,
        });

        cmp.form.patchValue({ budget: 20_001, minEvents: 0, candidateDegreeMin: 60, maxFanOut: 1.5 });
        expect(cmp.open()).toBe(false);
        expect(cmp.rung()).toBeNull();
        fixture.detectChanges();
        expect(cmp.open()).toBe(true);
        const errors = Array.from(el.querySelectorAll('mat-error')).map((e) => e.textContent!.trim());
        expect(errors).toContain('A whole number from 1 to 20000.');
        expect(errors.filter((e) => e === 'A whole number of at least 1.').length).toBe(2);
        expect(el.querySelector('[role="alert"]')!.textContent).toContain('must not exceed the maximum');
        await expectNoA11yViolations(el);
    });

    it("shows the server's refusal verbatim", async () => {
        const { fixture, cmp, el } = setup();
        cmp.fail("'candidateDegreeMin' must not exceed 'candidateDegreeMax'");
        fixture.detectChanges();
        expect(el.textContent).toContain("'candidateDegreeMin' must not exceed 'candidateDegreeMax'");
        await expectNoA11yViolations(el);
    });

    it('sends link kinds (trimmed, deduplicated) and refuses more than the server accepts', () => {
        const { cmp } = setup();
        cmp.form.patchValue({ linkKinds: ' call, sms ,call,, ' });
        expect(cmp.rung()).toEqual({ linkKinds: ['call', 'sms'] });
        cmp.form.patchValue({ linkKinds: Array.from({ length: 101 }, (_, i) => 'k' + i).join(',') });
        expect(cmp.rung()).toBeNull();
        cmp.form.patchValue({ linkKinds: '' });
        expect(cmp.rung()).toEqual({}); // blank = all kinds, nothing sent
    });

    it('overrides the window for this rung only: the object, validated like the window op', async () => {
        const { fixture, cmp, el } = setup();
        cmp.form.patchValue({ window: 'override' });
        fixture.detectChanges();
        // nothing set yet: the section opens on the error and the rung is not sent
        expect(cmp.rung()).toBeNull();
        fixture.detectChanges();
        expect(el.textContent).toContain('Set at least one of From, To');
        cmp.override.patchValue({
            from: '2026-09-01T00:00:00Z',
            to: '2026-10-01T00:00:00+03:00',
            slotStart: '22:00',
            slotEnd: '06:00',
            timezone: 'Asia/Riyadh',
            days: { FRI: true },
        });
        fixture.detectChanges();
        expect(cmp.rung()).toEqual({
            window: {
                from: '2026-09-01T00:00:00Z',
                to: '2026-10-01T00:00:00+03:00',
                slot: { start: '22:00', end: '06:00' },
                days: ['FRI'],
                timezone: 'Asia/Riyadh',
            },
        });
        await expectNoA11yViolations(el);
        // choosing inherit again ignores whatever the override still holds
        cmp.form.patchValue({ window: 'inherit' });
        expect(cmp.rung()).toEqual({});
    });
});
