import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '../testing/a11y';
import { StepperFieldComponent } from './stepper-field.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [StepperFieldComponent, ReactiveFormsModule],
    template: `
        <inspecto-stepper-field
            label="Degrees"
            decrementLabel="Fewer degrees"
            incrementLabel="More degrees"
            [min]="min"
            [max]="3"
            [valueText]="text"
            minReason="One degree is the fewest."
            maxReason="3 degrees is the most."
            [formControl]="ctl"
        />
    `,
})
class Host {
    min = 1;
    text: ((n: number) => string) | null = null;
    ctl = new FormControl(1, { nonNullable: true });
}

function create(over: Partial<Pick<Host, 'min' | 'text'>> & { value?: number } = {}) {
    TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(Host);
    Object.assign(fixture.componentInstance, over);
    if (over.value !== undefined) fixture.componentInstance.ctl.setValue(over.value);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const spin = el.querySelector('[role="spinbutton"]') as HTMLElement;
    const dec = el.querySelector('[data-testid="stepper-decrement"]') as HTMLButtonElement;
    const inc = el.querySelector('[data-testid="stepper-increment"]') as HTMLButtonElement;
    const key = (k: string) => {
        spin.dispatchEvent(new KeyboardEvent('keydown', { key: k, cancelable: true }));
        fixture.detectChanges();
    };
    const click = (b: HTMLButtonElement) => {
        b.click();
        fixture.detectChanges();
    };
    const reason = () => el.querySelector('[data-testid="stepper-reason"]')?.textContent?.trim() ?? null;
    return { fixture, el, spin, dec, inc, key, click, reason, ctl: fixture.componentInstance.ctl };
}

describe('StepperFieldComponent (− N +)', () => {
    it('is a labelled spinbutton with its value and bounds, between two named buttons', async () => {
        const { el, spin, dec, inc } = create();
        const label = el.querySelector(`#${spin.getAttribute('aria-labelledby')}`)!;
        expect(label.textContent!.trim()).toBe('Degrees');
        expect(spin.getAttribute('aria-valuenow')).toBe('1');
        expect(spin.getAttribute('aria-valuemin')).toBe('1');
        expect(spin.getAttribute('aria-valuemax')).toBe('3');
        expect(spin.getAttribute('tabindex')).toBe('0');
        expect(spin.textContent!.trim()).toBe('1');
        expect(dec.getAttribute('aria-label')).toBe('Fewer degrees');
        expect(inc.getAttribute('aria-label')).toBe('More degrees');
        // in order: − value +
        expect(dec.compareDocumentPosition(spin) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        expect(spin.compareDocumentPosition(inc) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        await expectNoA11yViolations(el);
    });

    it('the buttons step the value and tell the form; at a bound the button is inert and says why', () => {
        const { el, spin, dec, inc, click, reason, ctl } = create();
        expect(dec.getAttribute('aria-disabled')).toBe('true');
        expect(inc.getAttribute('aria-disabled')).toBeNull();
        expect(reason()).toBeNull();
        click(dec);
        expect(ctl.value).toBe(1);
        expect(reason()).toBe('One degree is the fewest.');
        expect(spin.getAttribute('aria-describedby')).toBe(el.querySelector('[data-testid="stepper-reason"]')!.id);
        click(inc);
        expect(ctl.value).toBe(2);
        expect(reason()).toBeNull(); // a real step clears the reason
        click(inc);
        expect(ctl.value).toBe(3);
        expect(inc.getAttribute('aria-disabled')).toBe('true');
        click(inc);
        expect(ctl.value).toBe(3);
        expect(reason()).toBe('3 degrees is the most.');
        expect(spin.getAttribute('aria-valuenow')).toBe('3');
    });

    it('arrow keys step it, Home / End jump to the bounds, other keys are left alone', () => {
        const { spin, key, ctl, reason } = create();
        key('ArrowUp');
        expect(ctl.value).toBe(2);
        key('ArrowRight');
        expect(ctl.value).toBe(3);
        key('ArrowUp');
        expect(ctl.value).toBe(3);
        expect(reason()).toBe('3 degrees is the most.');
        key('ArrowDown');
        key('ArrowLeft');
        expect(ctl.value).toBe(1);
        key('End');
        expect(ctl.value).toBe(3);
        key('Home');
        expect(ctl.value).toBe(1);
        const tab = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true });
        spin.dispatchEvent(tab);
        expect(tab.defaultPrevented).toBe(false);
        expect(ctl.value).toBe(1);
    });

    it('words a value when asked (0 = "Don\'t expand"), on screen and as aria-valuetext', async () => {
        const { el, spin, inc, click } = create({ min: 0, value: 0, text: (n) => (n === 0 ? "Don't expand" : String(n)) });
        expect(spin.textContent!.trim()).toBe("Don't expand");
        expect(spin.getAttribute('aria-valuetext')).toBe("Don't expand");
        expect(spin.getAttribute('aria-valuemin')).toBe('0');
        click(inc);
        expect(spin.textContent!.trim()).toBe('1');
        expect(spin.getAttribute('aria-valuetext')).toBe('1');
        await expectNoA11yViolations(el);
    });

    it('a stored value past the maximum reads as the maximum; a disabled control does not step', () => {
        const { fixture, spin, inc, click, ctl } = create({ value: 9 });
        expect(spin.getAttribute('aria-valuenow')).toBe('3');
        ctl.disable();
        fixture.detectChanges();
        expect(spin.getAttribute('aria-disabled')).toBe('true');
        expect(spin.getAttribute('tabindex')).toBe('-1');
        click(inc);
        expect(ctl.value).toBe(9);
    });
});
