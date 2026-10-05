import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InvestigationWindowOpComponent } from './investigation-window-op.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';

function setup(applyOk = true, serverError = '') {
    const store = {
        busy: signal(false),
        error: signal(serverError),
        apply: vi.fn(async () => applyOk),
    };
    TestBed.configureTestingModule({
        imports: [InvestigationWindowOpComponent],
        providers: [provideNoopAnimations(), { provide: InvestigationSessionStore, useValue: store }],
    });
    const fixture = TestBed.createComponent(InvestigationWindowOpComponent);
    fixture.detectChanges();
    return { fixture, cmp: fixture.componentInstance, store, el: fixture.nativeElement as HTMLElement };
}

const alerts = (el: HTMLElement) => Array.from(el.querySelectorAll('[role="alert"]')).map((a) => a.textContent!.trim());

describe('InvestigationWindowOpComponent (LA-SPA-OWED-SURFACES-1)', () => {
    it('appends a window op: range + midnight-crossing slot + day mask + timezone, only the keys set', async () => {
        const { fixture, cmp, store, el } = setup();
        await expectNoA11yViolations(el);
        cmp.form.patchValue({
            from: '2026-09-01T00:00:00Z',
            to: '2026-10-01T00:00:00+03:00',
            slotStart: '22:00',
            slotEnd: '06:00',
            timezone: 'Asia/Riyadh',
            days: { FRI: true, MON: true },
        });
        fixture.detectChanges();
        expect(el.textContent).toContain('Crosses midnight');
        await cmp.submit();
        expect(store.apply).toHaveBeenCalledWith({
            op: 'window',
            window: {
                from: '2026-09-01T00:00:00Z',
                to: '2026-10-01T00:00:00+03:00',
                slot: { start: '22:00', end: '06:00' },
                days: ['MON', 'FRI'],
                timezone: 'Asia/Riyadh',
            },
        });
    });

    it('refuses client-side what the server refuses: zone-less slot, naive instant, empty window', async () => {
        const { fixture, cmp, store, el } = setup();
        await cmp.submit();
        fixture.detectChanges();
        expect(alerts(el).join(' ')).toContain('Set at least one of');
        cmp.form.patchValue({ from: '2026-09-01T00:00:00', slotStart: '08:00', slotEnd: '08:00' });
        await cmp.submit();
        fixture.detectChanges();
        expect(cmp.form.controls.from.hasError('pattern')).toBe(true);
        expect(alerts(el).join(' ')).toContain('needs a timezone');
        expect(alerts(el).join(' ')).toContain('start equals its end');
        expect(store.apply).not.toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('sends calendar exclusions (dates, ranges, names) and needs a timezone for them', async () => {
        const { fixture, cmp, store, el } = setup();
        cmp.form.patchValue({ exclude: '2026-12-25, Winter break=2026-12-24..2026-12-26\n2026-01-01' });
        await cmp.submit();
        fixture.detectChanges();
        expect(alerts(el).join(' ')).toContain('needs a timezone');
        expect(store.apply).not.toHaveBeenCalled();
        cmp.form.patchValue({ timezone: 'Asia/Riyadh' });
        await cmp.submit();
        expect(store.apply).toHaveBeenCalledWith({
            op: 'window',
            window: {
                exclude: [
                    { date: '2026-12-25' },
                    { from: '2026-12-24', to: '2026-12-26', name: 'Winter break' },
                    { date: '2026-01-01' },
                ],
                timezone: 'Asia/Riyadh',
            },
        });
    });

    it('refuses a malformed or inverted excluded date client-side', async () => {
        const { fixture, cmp, store, el } = setup();
        cmp.form.patchValue({ exclude: '2026-12-26..2026-12-24', timezone: 'UTC' });
        await cmp.submit();
        fixture.detectChanges();
        expect(alerts(el).join(' ')).toContain('Excluded dates are YYYY-MM-DD');
        cmp.form.patchValue({ exclude: '25/12/2026' });
        await cmp.submit();
        expect(store.apply).not.toHaveBeenCalled();
    });

    it('sends yearly and nth-weekday exclusion rules, named or not', async () => {
        const { fixture, cmp, store, el } = setup();
        cmp.form.patchValue({
            exclude: 'Christmas=*-12-25, *-11-THU#4\n2026-01-01, *-02-29',
            timezone: 'America/New_York',
        });
        fixture.detectChanges();
        await expectNoA11yViolations(el);
        await cmp.submit();
        expect(store.apply).toHaveBeenCalledWith({
            op: 'window',
            window: {
                exclude: [
                    { rule: 'yearly', month: 12, day: 25, name: 'Christmas' },
                    { rule: 'nthWeekday', month: 11, weekday: 'THU', nth: 4 },
                    { date: '2026-01-01' },
                    { rule: 'yearly', month: 2, day: 29 },
                ],
                timezone: 'America/New_York',
            },
        });
    });

    it('refuses malformed recurring rules client-side, with an announced error and no axe violations', async () => {
        const { fixture, cmp, store, el } = setup();
        for (const bad of [
            '*-13-01',
            '*-02-30',
            '*-04-31',
            '*-12',
            '*-11-THUR#4',
            '*-11-THU#6',
            '*-11-THU#0',
            '*-11-THU',
            'x=*-1-1',
            '=*-12-25',
        ]) {
            cmp.form.patchValue({ exclude: bad, timezone: 'UTC' });
            await cmp.submit();
            fixture.detectChanges();
            expect(cmp.form.hasError('exclude'), bad).toBe(true);
        }
        expect(alerts(el).join(' ')).toContain('yearly rule');
        expect(store.apply).not.toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('caps recurring rules at 32 (the server cap)', async () => {
        const { cmp, store } = setup();
        const rules = (n: number) =>
            Array.from({ length: n }, (_, i) => `*-01-${String((i % 28) + 1).padStart(2, '0')}`);
        cmp.form.patchValue({ exclude: rules(33).join(','), timezone: 'UTC' });
        await cmp.submit();
        expect(store.apply).not.toHaveBeenCalled();
        cmp.form.patchValue({ exclude: rules(32).join(',') });
        await cmp.submit();
        expect(store.apply).toHaveBeenCalledTimes(1);
    });

    it("'All time' sends window 'full'; a server 422 is shown verbatim in place", async () => {
        const { fixture, cmp, store, el } = setup(false, "window.timezone: unknown zone 'Mars/Base'");
        cmp.form.controls.full.setValue(true);
        await cmp.submit();
        fixture.detectChanges();
        expect(store.apply).toHaveBeenCalledWith({ op: 'window', window: 'full' });
        expect(el.textContent).toContain("window.timezone: unknown zone 'Mars/Base'");
        await expectNoA11yViolations(el);
    });
});
