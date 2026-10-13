import { Component, ChangeDetectionStrategy } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { describe, expect, it } from 'vitest';
import { PenFieldComponent } from './pen-field.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [PenFieldComponent, ReactiveFormsModule],
    template: `<inspecto-pen-field label="Depth" type="number" [default]="2" [formControl]="ctl" />`,
})
class HostComponent {
    ctl = new FormControl<number | null>(null);
}

describe('PenFieldComponent', () => {
    function setup() {
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        return { fixture, el: fixture.nativeElement as HTMLElement };
    }

    it('shows the default as a ghost until a value is set, without writing it', () => {
        const { fixture, el } = setup();
        expect(el.textContent).toContain('2');
        expect(fixture.componentInstance.ctl.value).toBeNull();
    });

    it('→ in an empty box fills the default; Enter commits it', async () => {
        const { fixture, el } = setup();
        (el.querySelector('button.pen-pencil') as HTMLButtonElement).click();
        fixture.detectChanges();
        const box = el.querySelector('input') as HTMLInputElement;
        box.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', cancelable: true }));
        box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
        fixture.detectChanges();
        expect(fixture.componentInstance.ctl.value).toBe(2);
        expect(el.querySelector('input')).toBeNull();
    });

    it('Escape drops the edit', () => {
        const { fixture, el } = setup();
        (el.querySelector('button.pen-pencil') as HTMLButtonElement).click();
        fixture.detectChanges();
        const box = el.querySelector('input') as HTMLInputElement;
        box.value = '7';
        box.dispatchEvent(new Event('input'));
        box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }));
        fixture.detectChanges();
        expect(fixture.componentInstance.ctl.value).toBeNull();
    });
});
