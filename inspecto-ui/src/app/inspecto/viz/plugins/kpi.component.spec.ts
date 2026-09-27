import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NumberFormat } from '../number-format';
import { KpiComponent } from './kpi.component';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environment } from '../../../../environments/environment';
import { KpiValue } from 'app/inspecto/api/kpis.service';

function create(
    inputs: {
        value?: number;
        compare?: number;
        format?: NumberFormat;
        target?: number;
        better?: 'higher' | 'lower';
    } = {},
) {
    TestBed.configureTestingModule({ imports: [KpiComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(KpiComponent);
    fixture.componentRef.setInput('value', inputs.value ?? 1234);
    for (const k of ['compare', 'format', 'target', 'better'] as const)
        if (inputs[k] !== undefined) fixture.componentRef.setInput(k, inputs[k]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const text = (id: string) => el.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? null;
    return { fixture, c: fixture.componentInstance, el, text };
}

describe('KpiComponent (UIE-1)', () => {
    it('formats the value with the widget format and shows no technical caption', () => {
        const { text, el } = create({
            value: 7664957.18,
            format: { style: 'currency', currency: 'SAR', compact: true },
        });
        expect(text('kpi-value')).toBe('SAR 7.7M');
        expect(el.textContent).not.toContain('live aggregate');
    });

    it('without a format, caps a float at two decimals instead of printing its noise', () => {
        expect(create({ value: 13175.369999999999 }).text('kpi-value')).toBe(
            new Intl.NumberFormat('en').format(13175.37),
        );
    });

    it('states the delta vs the prior period in words, toned by the good direction', () => {
        const up = create({ value: 110, compare: 100 });
        expect(up.text('kpi-delta')).toBe('▲ Up 10.0 % (+10) vs prior period');
        expect(up.el.querySelector('[data-testid="kpi-delta"] span')?.className).toContain('green');

        TestBed.resetTestingModule();
        expect(create({ value: 148, compare: 163 }).text('kpi-delta')).toBe('▼ Down 9.2 % (−15) vs prior period');

        TestBed.resetTestingModule();
        const sla = create({ value: 88.9, compare: 91.5, format: { style: 'percent' } });
        expect(sla.text('kpi-delta')).toBe('▼ Down 2.6 pts vs prior period');

        TestBed.resetTestingModule();
        const exposureUp = create({ value: 110, compare: 100, better: 'lower' });
        expect(exposureUp.el.querySelector('[data-testid="kpi-delta"] span')?.className).toContain('red');
    });

    it('omits the delta when there is no prior-period value', () => {
        expect(create({ value: 5 }).text('kpi-delta')).toBeNull();
    });

    it('states the target and whether the value meets it, for either direction', () => {
        expect(create({ value: 96.9, target: 99, format: { style: 'percent', decimals: 1 } }).text('kpi-target')).toBe(
            'Target 99.0 % — below target',
        );
        TestBed.resetTestingModule();
        expect(create({ value: 0.2, target: 0.3, better: 'lower' }).text('kpi-target')).toContain('on target');
    });

    it('cycles mini → standard → max in place', () => {
        const { c } = create();
        c.mode.set('mini');
        c.cycle();
        expect(c.mode()).toBe('standard');
        c.cycle();
        expect(c.mode()).toBe('max');
        c.cycle();
        expect(c.mode()).toBe('mini');
    });

    it('renders with no a11y violations, delta and target included', async () => {
        const { el } = create({ value: 110, compare: 100, target: 120 });
        await expectNoA11yViolations(el);
    });

    describe('bound to a KPI definition (ASSURE-KPI-DEFINITIONS-1)', () => {
        const served: KpiValue = {
            kpi: 'refunds',
            grain: 'month',
            comparison: 'previous',
            direction: 'down',
            asOf: '2026-08-13',
            timezone: 'UTC',
            period: { from: '2026-08-01', to: '2026-08-14' },
            value: 50,
            comparisonPeriod: null,
            comparisonValue: 40,
            delta: 10,
            deltaPct: 25,
            target: 35,
            band: 'RED',
            tone: 'error',
            format: { style: 'currency', currency: 'SAR' },
        };

        function bound(response: KpiValue | 'error') {
            TestBed.configureTestingModule({
                imports: [KpiComponent],
                providers: [provideNoopAnimations(), provideHttpClient(withXhr()), provideHttpClientTesting()],
            });
            const fixture = TestBed.createComponent(KpiComponent);
            // hand-set inputs: the fallback until the definition answers
            fixture.componentRef.setInput('value', 7);
            fixture.componentRef.setInput('target', 1);
            fixture.componentRef.setInput('kpiId', 'refunds');
            fixture.detectChanges();
            const http = TestBed.inject(HttpTestingController);
            const req = http.expectOne((r) => r.url === environment.apiBaseUrl + '/v1/kpis/refunds/value');
            if (response === 'error') req.flush('no', { status: 404, statusText: 'Not Found' });
            else req.flush(response);
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            return { el, text: (id: string) => el.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? null };
        }

        it('reads value, comparison, target, direction, format and band from the server', async () => {
            const { el, text } = bound(served);
            expect(text('kpi-value')).toBe('SAR 50.00');
            expect(text('kpi-delta')).toContain('▲ Up 25.0 %');
            expect(text('kpi-target')).toBe('Target SAR 35.00 — above target');
            expect(text('kpi-band')).toBe('RAG: Red');
            await expectNoA11yViolations(el);
        });

        it('says "No data" for a null value and never shows the hand-set one in its place', async () => {
            const { el, text } = bound({ ...served, value: null, delta: null, deltaPct: null, band: null });
            expect(text('kpi-value')).toBe('No data');
            expect(el.textContent).not.toContain('7');
            expect(text('kpi-delta')).toBeNull();
            expect(text('kpi-target')).toBeNull();
            await expectNoA11yViolations(el);
        });

        it('shows "KPI unavailable" on a read error, not the hand-set inputs', async () => {
            const { el, text } = bound('error');
            expect(text('kpi-value')).toBe('KPI unavailable');
            expect(text('kpi-target')).toBeNull();
            expect(text('kpi-band')).toBeNull();
            expect(el.textContent).not.toContain('Target 1');
            await expectNoA11yViolations(el);
        });

        it("evaluates at the host Dashboard's asOf day, re-reads when it changes, and sends none when blank", () => {
            TestBed.configureTestingModule({
                imports: [KpiComponent],
                providers: [provideNoopAnimations(), provideHttpClient(withXhr()), provideHttpClientTesting()],
            });
            const fixture = TestBed.createComponent(KpiComponent);
            const http = TestBed.inject(HttpTestingController);
            const url = environment.apiBaseUrl + '/v1/kpis/refunds/value';
            fixture.componentRef.setInput('kpiId', 'refunds');
            fixture.componentRef.setInput('asOf', '2026-08-13');
            fixture.detectChanges();
            expect(http.expectOne((r) => r.url === url).request.params.get('asOf')).toBe('2026-08-13');

            fixture.componentRef.setInput('asOf', '2026-07-31');
            fixture.detectChanges();
            expect(http.expectOne((r) => r.url === url).request.params.get('asOf')).toBe('2026-07-31');

            fixture.componentRef.setInput('asOf', '');
            fixture.detectChanges();
            expect(http.expectOne((r) => r.url === url).request.params.has('asOf')).toBe(false);
            http.verify();
        });

        it('a band KPI drops the up / down wording and takes its tones from the server band', async () => {
            const { el, text } = bound({ ...served, direction: 'band', value: 50, band: 'AMBER', tone: 'warning' });
            expect(text('kpi-delta')).toBe('Change +SAR 10 (25.0 %) vs prior period');
            expect(text('kpi-delta')).not.toMatch(/Up|Down|▲|▼/);
            expect(text('kpi-target')).toBe('Target SAR 35.00 — near band');
            for (const id of ['kpi-delta', 'kpi-target'])
                expect(el.querySelector(`[data-testid="${id}"] span`)?.className).toContain('amber');
            await expectNoA11yViolations(el);
        });
    });
});
