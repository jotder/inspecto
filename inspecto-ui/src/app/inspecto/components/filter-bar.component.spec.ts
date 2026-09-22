import { describe, expect, it } from 'vitest';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { FilterField, FilterValues, InspectoFilterBarComponent } from './filter-bar.component';

@Component({
    standalone: true,
    imports: [InspectoFilterBarComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
        <inspecto-filter-bar
            [fields]="fields"
            [value]="value"
            (valueChange)="value = $event"
            (apply)="applied.push($event)"
        >
            <button end id="export">Export CSV</button>
        </inspecto-filter-bar>
    `,
})
class HostComponent {
    fields: FilterField[] = [
        {
            key: 'level',
            label: 'Min level',
            type: 'select',
            defaultValue: '',
            options: [
                { value: '', label: 'All' },
                { value: 'WARN', label: 'WARN' },
                { value: 'ERROR', label: 'ERROR' },
            ],
        },
        { key: 'q', label: 'Search', type: 'text' },
        { key: 'limit', label: 'Limit', type: 'number', defaultValue: 100 },
    ];
    value: FilterValues = { level: '', q: '', limit: 100 };
    applied: FilterValues[] = [];
}

describe('InspectoFilterBarComponent', () => {
    function create(inputs: Partial<HostComponent> = {}) {
        TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(HostComponent);
        Object.assign(fixture.componentInstance, inputs);
        fixture.detectChanges();
        return fixture;
    }
    // The active-filter chips only — NOT the count pill, which is a chip inside the Filter button.
    const activeChips = (el: HTMLElement) =>
        (Array.from(el.querySelectorAll('inspecto-chip')) as HTMLElement[]).filter((c) => !c.closest('button'));
    const toggle = (el: HTMLElement) =>
        Array.from(el.querySelectorAll('button')).find(
            (b) => b.getAttribute('aria-expanded') !== null,
        ) as HTMLButtonElement;

    it('starts collapsed with no chips when every value is at its default', () => {
        const el: HTMLElement = create().nativeElement;
        expect(toggle(el).getAttribute('aria-expanded')).toBe('false');
        expect(el.querySelector('#inspecto-filter-panel')).toBeNull();
        expect(activeChips(el).length).toBe(0);
        expect(el.textContent).not.toContain('Reset');
    });

    it('projects the end slot on the collapsed row', () => {
        const el: HTMLElement = create().nativeElement;
        expect(el.querySelector('#export')).not.toBeNull();
    });

    it('draws one chip per non-default value using the option label, and a count on the toggle', () => {
        const el: HTMLElement = create({ value: { level: 'WARN', q: 'timeout', limit: 100 } }).nativeElement;
        const chips = activeChips(el).map((c) => c.textContent?.replace(/\s+/g, ' ').trim());
        expect(chips).toEqual(['Min level: WARN', 'Search: timeout']);
        expect(toggle(el).textContent).toContain('2');
        expect(el.textContent).toContain('Reset');
    });

    it('opens the panel with one control per field', () => {
        const fixture = create();
        toggle(fixture.nativeElement).click();
        fixture.detectChanges();
        const panel = fixture.nativeElement.querySelector('#inspecto-filter-panel') as HTMLElement;
        expect(panel).not.toBeNull();
        expect(panel.querySelectorAll('mat-form-field').length).toBe(3);
        expect(toggle(fixture.nativeElement).getAttribute('aria-expanded')).toBe('true');
    });

    it('removing a chip restores that field to its default and applies', () => {
        const fixture = create({ value: { level: 'ERROR', q: '', limit: 50 } });
        const removes = fixture.nativeElement.querySelectorAll('inspecto-chip button') as NodeListOf<HTMLButtonElement>;
        expect(removes.length).toBe(2);
        removes[1].click();
        fixture.detectChanges();
        expect(fixture.componentInstance.value).toEqual({ level: 'ERROR', q: '', limit: 100 });
        expect(fixture.componentInstance.applied.length).toBe(1);
    });

    it('reset restores every default and applies once', () => {
        const fixture = create({ value: { level: 'ERROR', q: 'x', limit: 5 } });
        const reset = Array.from(
            fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
        ).find((b) => b.textContent?.trim() === 'Reset')!;
        reset.click();
        fixture.detectChanges();
        expect(fixture.componentInstance.value).toEqual({ level: '', q: null, limit: 100 });
        expect(fixture.componentInstance.applied.length).toBe(1);
        expect(activeChips(fixture.nativeElement).length).toBe(0);
    });

    it('has no axe violations open and closed', async () => {
        const fixture = create({ value: { level: 'WARN', q: '', limit: 100 } });
        await expectNoA11yViolations(fixture.nativeElement);
        toggle(fixture.nativeElement).click();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
