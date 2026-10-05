import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InvestigationCompareOpComponent } from './investigation-compare-op.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';

function setup(applyOk = true, serverError = '') {
    const store = {
        busy: signal(false),
        error: signal(serverError),
        apply: vi.fn(async () => applyOk),
    };
    TestBed.configureTestingModule({
        imports: [InvestigationCompareOpComponent],
        providers: [provideNoopAnimations(), { provide: InvestigationSessionStore, useValue: store }],
    });
    const fixture = TestBed.createComponent(InvestigationCompareOpComponent);
    fixture.detectChanges();
    return { fixture, cmp: fixture.componentInstance, store, el: fixture.nativeElement as HTMLElement };
}

describe('InvestigationCompareOpComponent (LA-INVESTIGATION-OPS-DEFERRED-1)', () => {
    it('appends a compare op carrying both windows', async () => {
        const { cmp, store, el } = setup();
        await expectNoA11yViolations(el);
        cmp.formA.patchValue({ from: '2026-09-01T00:00:00Z', to: '2026-09-03T00:00:00Z' });
        cmp.formB.patchValue({ from: '2026-09-03T00:00:00Z', timezone: 'America/Sao_Paulo' });
        await cmp.submit();
        expect(store.apply).toHaveBeenLastCalledWith({
            op: 'compare',
            windowA: { from: '2026-09-01T00:00:00Z', to: '2026-09-03T00:00:00Z' },
            windowB: { from: '2026-09-03T00:00:00Z', timezone: 'America/Sao_Paulo' },
        });
    });

    it('sends inherit for a side and mode activity when asked, without validating the inherited side', async () => {
        const { fixture, cmp, store, el } = setup();
        cmp.inheritB.set(true);
        cmp.byCount.set(true);
        fixture.detectChanges();
        expect(el.textContent).toContain("Use the Investigation's window");
        await expectNoA11yViolations(el);
        cmp.formA.patchValue({ from: '2026-09-01T00:00:00Z' });
        await cmp.submit();
        expect(store.apply).toHaveBeenLastCalledWith({
            op: 'compare',
            windowA: { from: '2026-09-01T00:00:00Z' },
            windowB: 'inherit',
            mode: 'activity',
        });
        expect(cmp.inheritB()).toBe(false);
        expect(cmp.byCount()).toBe(false);
    });

    it('does not send an empty side, nor a naive instant', async () => {
        const { fixture, cmp, store, el } = setup();
        cmp.formA.patchValue({ from: '2026-09-01T00:00:00Z' });
        await cmp.submit();
        fixture.detectChanges();
        expect(el.textContent).toContain('Set at least one of From, To, a slot or days');
        cmp.formB.patchValue({ from: '2026-09-01T00:00:00' });
        await cmp.submit();
        expect(store.apply).not.toHaveBeenCalled();
    });

    it('shows a server 422 verbatim in place', async () => {
        const { fixture, cmp, el } = setup(false, 'this Investigation has no time column');
        cmp.formA.patchValue({ from: '2026-09-01T00:00:00Z' });
        cmp.formB.patchValue({ from: '2026-09-03T00:00:00Z' });
        await cmp.submit();
        fixture.detectChanges();
        expect(el.textContent).toContain('this Investigation has no time column');
        await expectNoA11yViolations(el);
    });
});
