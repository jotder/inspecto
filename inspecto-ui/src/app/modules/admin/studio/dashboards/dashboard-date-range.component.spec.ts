import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DashboardDateRangeComponent } from './dashboard-date-range.component';
import { DateRangeSelection } from './dashboard-date-range';

function create(selection: DateRangeSelection | null) {
    TestBed.configureTestingModule({ imports: [DashboardDateRangeComponent], providers: [provideNoopAnimations()] });
    const f = TestBed.createComponent(DashboardDateRangeComponent);
    f.componentRef.setInput('selection', selection);
    f.componentRef.setInput('anchor', '2026-09-24');
    const emitted: (DateRangeSelection | null)[] = [];
    f.componentInstance.selectionChange.subscribe((v) => emitted.push(v));
    f.detectChanges();
    return { f, emitted, el: f.nativeElement as HTMLElement };
}

function typeDay(el: HTMLElement, label: string, value: string): void {
    const field = Array.from(el.querySelectorAll('mat-form-field')).find((ff) =>
        ff.querySelector('mat-label')?.textContent?.includes(label),
    )!;
    const input = field.querySelector('input') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('change'));
}

describe('DashboardDateRangeComponent (UIE-5 d)', () => {
    it('is a labelled group showing the span a preset resolves to, counted back from the anchor', async () => {
        const { f, el } = create('month-to-date');
        const group = el.querySelector('[role="group"]')!;
        expect(group.getAttribute('aria-label')).toBe('Date range');
        expect(el.querySelector('[data-testid="date-range-span"]')!.textContent).toContain('1 Sep – 24 Sep 2026');
        await expectNoA11yViolations(f.nativeElement);
    });

    it('emits a preset id, and null for All dates', () => {
        const { f, emitted } = create(null);
        f.componentInstance.onPick('quarter-to-date');
        f.componentInstance.onPick('');
        expect(emitted).toEqual(['quarter-to-date', null]);
    });

    it('Custom shows From / To and emits only a complete, ordered span; an inverted one is announced', async () => {
        const { f, emitted, el } = create('last-7-days');
        f.componentInstance.onPick('custom');
        f.detectChanges();
        expect(emitted).toEqual([]); // no days yet — nothing to apply
        typeDay(el, 'From', '2026-09-10');
        f.detectChanges();
        expect(emitted).toEqual([]);
        typeDay(el, 'To', '2026-09-01');
        f.detectChanges();
        expect(emitted).toEqual([]);
        expect(el.querySelector('[role="alert"]')!.textContent).toContain('From must be on or before To.');
        typeDay(el, 'To', '2026-09-12');
        f.detectChanges();
        expect(emitted).toEqual([{ from: '2026-09-10', to: '2026-09-12' }]);
        expect(el.querySelector('[role="alert"]')).toBeNull();
        await expectNoA11yViolations(f.nativeElement);
    });

    it('a stored custom span opens on Custom with its days filled in', () => {
        const { el } = create({ from: '2026-01-05', to: '2026-02-10' });
        const inputs = Array.from(el.querySelectorAll('input[type="date"]')) as HTMLInputElement[];
        expect(inputs.map((i) => i.value)).toEqual(['2026-01-05', '2026-02-10']);
        expect(el.querySelector('[data-testid="date-range-span"]')!.textContent).toContain('5 Jan – 10 Feb 2026');
    });
});
