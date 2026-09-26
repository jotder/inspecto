import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ObjectsService, OperationalObject } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { ImpactPanelComponent } from './impact-panel.component';

const CASE: OperationalObject = {
    id: 'c1',
    objectType: 'CASE',
    title: 'Leakage',
    description: '',
    status: 'INVESTIGATING',
    attributes: {},
    impact: {
        suspected: 5000,
        confirmed: 1200.5,
        recovered: 200.25,
        prevented: null,
        outstanding: 1000.25,
        currency: 'EUR',
        period: '2026-09',
        basis: 'rated vs billed CDRs',
    },
    createdAt: 1,
    updatedAt: 1,
    closedAt: 0,
};

function create(object: OperationalObject = CASE, saveImpact = vi.fn(() => of(object))) {
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [ImpactPanelComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ObjectsService, useValue: { saveImpact } },
            { provide: ToastrService, useValue: toastr },
        ],
    });
    const fixture = TestBed.createComponent(ImpactPanelComponent);
    fixture.componentRef.setInput('object', object);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, saveImpact, toastr, el: fixture.nativeElement as HTMLElement };
}

describe('ImpactPanelComponent (WS-10)', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('shows the four amounts and the outstanding the SERVER derived', async () => {
        const { el, fixture } = create();
        const text = el.textContent ?? '';
        expect(text).toContain('Outstanding');
        expect(text).toContain('1,000.25');
        expect(text).toContain('rated vs billed CDRs');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('says so when no impact is recorded', () => {
        const { el } = create({ ...CASE, impact: undefined });
        expect(el.textContent).toContain('No impact recorded.');
    });

    it('saves exact decimal strings, upper-cases the currency, and never sends outstanding', () => {
        const { c, saveImpact } = create();
        c.startEdit();
        c.form.patchValue({ confirmed: '300', recovered: '100', currency: 'usd', basis: '' });
        expect(c.previewOutstanding()).toContain('200');
        c.save();
        expect(saveImpact).toHaveBeenCalledTimes(1);
        const [id, body] = saveImpact.mock.calls[0] as unknown as [string, Record<string, string>];
        expect(id).toBe('c1');
        expect(body).toEqual({
            suspected: '5000',
            confirmed: '300',
            recovered: '100',
            currency: 'USD',
            period: '2026-09',
        });
        expect('outstanding' in body).toBe(false);
    });

    it('refuses an amount without a currency, and a negative amount, before calling the server', () => {
        const { c, saveImpact, fixture, el } = create({ ...CASE, impact: undefined });
        c.startEdit();
        c.form.patchValue({ confirmed: '10' });
        c.save();
        fixture.detectChanges();
        expect(saveImpact).not.toHaveBeenCalled();
        expect(el.textContent).toContain('A currency is required once an amount is set.');
        c.form.patchValue({ confirmed: '-1', currency: 'EUR' });
        expect(c.form.controls.confirmed.hasError('decimal')).toBe(true);
    });

    it('shows the server refusal inline', () => {
        const refusal = vi.fn(() =>
            throwError(
                () => new HttpErrorResponse({ status: 409, error: { error: { message: 'CASE c1 is CLOSED' } } }),
            ),
        );
        const { c, fixture, el } = create(CASE, refusal as never);
        c.startEdit();
        c.save();
        fixture.detectChanges();
        expect(c.serverError()).not.toBe('');
        expect(el.querySelector('inspecto-alert')).not.toBeNull();
    });

    it('offers no edit on a terminal object — its impact is closed server-side', () => {
        const { el } = create({ ...CASE, status: 'CLOSED' });
        expect(
            Array.from(el.querySelectorAll('button')).some((b) => /Edit|Record impact/.test(b.textContent ?? '')),
        ).toBe(false);
    });
});
