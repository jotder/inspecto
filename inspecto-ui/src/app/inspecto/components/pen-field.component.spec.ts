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

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [PenFieldComponent, ReactiveFormsModule],
    template: `
        <inspecto-pen-field #note label="Note" [multiline]="true" [maxlength]="20" [formControl]="noteCtl" />
        <inspecto-pen-field label="From" type="date" [formControl]="dateCtl" />
    `,
})
class NotesHostComponent {
    noteCtl = new FormControl<string | null>(null);
    dateCtl = new FormControl<string | null>(null);
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

    describe('notes and dates', () => {
        function setupNotes() {
            const fixture = TestBed.createComponent(NotesHostComponent);
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            return { fixture, el, hosts: Array.from(el.querySelectorAll<HTMLElement>('inspecto-pen-field')) };
        }

        it('a multiline field edits in a textarea, limits its length, and Shift+Enter keeps the line break', () => {
            const { fixture, hosts } = setupNotes();
            (hosts[0].querySelector('button.pen-pencil') as HTMLButtonElement).click();
            fixture.detectChanges();
            const box = hosts[0].querySelector('textarea') as HTMLTextAreaElement;
            expect(box).not.toBeNull();
            expect(box.getAttribute('maxlength')).toBe('20');
            box.value = 'line one';
            box.dispatchEvent(new Event('input'));
            const shifted = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, cancelable: true });
            box.dispatchEvent(shifted);
            expect(shifted.defaultPrevented).toBe(false); // Shift+Enter is left to the textarea: a line break
            expect(fixture.componentInstance.noteCtl.value).toBeNull(); // and nothing is committed yet
            box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
            fixture.detectChanges();
            expect(fixture.componentInstance.noteCtl.value).toBe('line one');
            expect(hosts[0].querySelector('textarea')).toBeNull();
        });

        it('a date field edits in a native date input and keeps the ISO text', () => {
            const { fixture, hosts } = setupNotes();
            (hosts[1].querySelector('button.pen-pencil') as HTMLButtonElement).click();
            fixture.detectChanges();
            const box = hosts[1].querySelector('input') as HTMLInputElement;
            expect(box.type).toBe('date');
            box.value = '2026-10-15';
            box.dispatchEvent(new Event('input'));
            box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
            fixture.detectChanges();
            expect(fixture.componentInstance.dateCtl.value).toBe('2026-10-15');
            expect(hosts[1].textContent).toContain('2026-10-15');
        });

        it('focus() opens the field for editing, as a host pointing at a missing value does', () => {
            const { fixture, hosts } = setupNotes();
            const pen = fixture.debugElement.children[0].componentInstance as PenFieldComponent;
            expect(hosts[0].querySelector('textarea')).toBeNull();
            pen.focus();
            fixture.detectChanges();
            expect(hosts[0].querySelector('textarea')).not.toBeNull();
        });
    });
});
