import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NumberFormat } from '../number-format';
import { KpiComponent } from './kpi.component';

function create(inputs: {
    value?: number;
    compare?: number;
    format?: NumberFormat;
    target?: number;
    better?: 'higher' | 'lower';
} = {}) {
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
        const { text, el } = create({ value: 7664957.18, format: { style: 'currency', currency: 'SAR', compact: true } });
        expect(text('kpi-value')).toBe('SAR 7.7M');
        expect(el.textContent).not.toContain('live aggregate');
    });

    it('without a format, caps a float at two decimals instead of printing its noise', () => {
        expect(create({ value: 13175.369999999999 }).text('kpi-value')).toBe(new Intl.NumberFormat('en').format(13175.37));
    });

    it('states the delta vs the prior period in words, toned by the good direction', () => {
        const up = create({ value: 110, compare: 100 });
        expect(up.text('kpi-delta')).toContain('▲ Up 10.0 % vs prior period');
        expect(up.el.querySelector('[data-testid="kpi-delta"] span')?.className).toContain('green');

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
});
