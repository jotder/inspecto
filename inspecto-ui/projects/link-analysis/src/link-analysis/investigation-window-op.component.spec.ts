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
