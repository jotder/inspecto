import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LockedActionComponent } from './locked-action.component';

@Component({
    standalone: true,
    imports: [LockedActionComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-locked-action [label]="label()" [reason]="reason()" />`,
})
class HostComponent {
    label = signal('Start an Investigation');
    reason = signal('Needs the Investigations add-on, which this installation does not hold.');
}

function create() {
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return fixture;
}

describe('LockedActionComponent', () => {
    it('renders the action disabled but focusable, with the reason as its visible description', async () => {
        const fixture = create();
        const el = fixture.nativeElement as HTMLElement;
        const button = el.querySelector('[data-testid="locked-action"]') as HTMLButtonElement;
        const reason = el.querySelector('[data-testid="locked-reason"]') as HTMLElement;

        expect(button.textContent).toContain('Start an Investigation');
        expect(button.getAttribute('aria-disabled')).toBe('true');
        expect(button.disabled).toBe(false); // disabledInteractive: still reachable by keyboard
        expect(reason.textContent?.trim()).toBe('Needs the Investigations add-on, which this installation does not hold.');
        expect(button.getAttribute('aria-describedby')).toBe(reason.id);
        expect(reason.id).not.toBe('');

        fixture.componentInstance.reason.set('Investigations are in Standard and above, with the Investigations add-on.');
        fixture.detectChanges();
        expect(reason.textContent).toContain('Standard and above');
        await expectNoA11yViolations(el);
    });

    it('gives every instance its own reason id', () => {
        const fixture = create();
        const first = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="locked-reason"]')!.id;
        const second = TestBed.createComponent(LockedActionComponent);
        second.componentRef.setInput('label', 'Build index');
        second.componentRef.setInput('reason', 'r');
        second.detectChanges();
        expect((second.nativeElement as HTMLElement).querySelector('[data-testid="locked-reason"]')!.id).not.toBe(first);
    });
});
