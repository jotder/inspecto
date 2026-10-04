import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    InvestigationSnapshotOpComponent,
    InvestigationThresholdOpComponent,
    MAX_SNAPSHOT_LABEL,
} from './investigation-band-ops.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';

function setup<T>(component: new () => T, applyOk = true, serverError = '') {
    const store = {
        busy: signal(false),
        error: signal(serverError),
        apply: vi.fn(async () => applyOk),
    };
    TestBed.configureTestingModule({
        imports: [component],
        providers: [provideNoopAnimations(), { provide: InvestigationSessionStore, useValue: store }],
    });
    const fixture = TestBed.createComponent(component);
    fixture.detectChanges();
    return { fixture, cmp: fixture.componentInstance, store, el: fixture.nativeElement as HTMLElement };
}

const alerts = (el: HTMLElement) => Array.from(el.querySelectorAll('[role="alert"]')).map((a) => a.textContent!.trim());

describe('InvestigationThresholdOpComponent (LA-INVESTIGATION-OPS-DEFERRED-1)', () => {
    it('appends a threshold op with only the bounds set', async () => {
        const { cmp, store, el } = setup(InvestigationThresholdOpComponent);
        await expectNoA11yViolations(el);
        cmp.form.patchValue({ min: '2' });
        await cmp.submit();
        expect(store.apply).toHaveBeenLastCalledWith({ op: 'threshold', min: 2 });
        cmp.form.patchValue({ min: '0', max: '50' });
        await cmp.submit();
        expect(store.apply).toHaveBeenLastCalledWith({ op: 'threshold', min: 0, max: 50 });
    });

    it('refuses client-side what the server refuses: no bound, min >= max, non-integer, max 0', async () => {
        const { fixture, cmp, store, el } = setup(InvestigationThresholdOpComponent);
        await cmp.submit();
        fixture.detectChanges();
        expect(alerts(el).join(' ')).toContain('Set a minimum, a maximum or both');
        cmp.form.patchValue({ min: '5', max: '5' });
        await cmp.submit();
        fixture.detectChanges();
        expect(alerts(el).join(' ')).toContain('minimum must be less than the maximum');
        cmp.form.patchValue({ min: '1.5', max: '0' });
        await cmp.submit();
        expect(cmp.form.controls.min.hasError('pattern')).toBe(true);
        expect(cmp.form.controls.max.hasError('min')).toBe(true);
        expect(store.apply).not.toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('shows a server 422 verbatim in place', async () => {
        const { fixture, cmp, el } = setup(InvestigationThresholdOpComponent, false, "'min' must be less than 'max'");
        cmp.form.patchValue({ min: '1' });
        await cmp.submit();
        fixture.detectChanges();
        expect(el.textContent).toContain("'min' must be less than 'max'");
        await expectNoA11yViolations(el);
    });
});

describe('InvestigationSnapshotOpComponent (LA-INVESTIGATION-OPS-DEFERRED-1)', () => {
    it('appends a snapshot marker; the label is optional and trimmed', async () => {
        const { cmp, store, el } = setup(InvestigationSnapshotOpComponent);
        await expectNoA11yViolations(el);
        await cmp.submit();
        expect(store.apply).toHaveBeenLastCalledWith({ op: 'snapshot' });
        cmp.form.patchValue({ label: '  before the burst ' });
        await cmp.submit();
        expect(store.apply).toHaveBeenLastCalledWith({ op: 'snapshot', label: 'before the burst' });
    });

    it('refuses an over-long label and shows a server 422 verbatim', async () => {
        const { fixture, cmp, store, el } = setup(
            InvestigationSnapshotOpComponent,
            false,
            "'label' is at most 2000 chars",
        );
        cmp.form.patchValue({ label: 'x'.repeat(MAX_SNAPSHOT_LABEL + 1) });
        await cmp.submit();
        expect(store.apply).not.toHaveBeenCalled();
        cmp.form.patchValue({ label: 'ok' });
        await cmp.submit();
        fixture.detectChanges();
        expect(el.textContent).toContain("'label' is at most 2000 chars");
        await expectNoA11yViolations(el);
    });
});
