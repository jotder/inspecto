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

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [PenFieldComponent, ReactiveFormsModule],
    template: `
        <inspecto-pen-field label="Name" [formControl]="nameCtl" />
        <inspecto-pen-field label="Depth" type="number" [formControl]="depthCtl" />
        <inspecto-pen-field label="From" type="date" [formControl]="dateCtl" />
        <inspecto-pen-field label="Fixed" [readOnly]="true" [formControl]="fixedCtl" />
        <inspecto-pen-field label="Off" [formControl]="offCtl" />
    `,
})
class TypingHostComponent {
    nameCtl = new FormControl<string | null>('old');
    depthCtl = new FormControl<number | null>(3);
    dateCtl = new FormControl<string | null>(null);
    fixedCtl = new FormControl<string | null>('kept');
    offCtl = new FormControl<string | null>({ value: 'off', disabled: true });
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

    describe('typing on the closed row (a keyboard user who tabs to it and types)', () => {
        function setupTyping() {
            const fixture = TestBed.createComponent(TypingHostComponent);
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            return {
                fixture,
                host: fixture.componentInstance,
                hosts: Array.from(el.querySelectorAll<HTMLElement>('inspecto-pen-field')),
            };
        }
        function press(target: Element, key: string, init: KeyboardEventInit = {}): KeyboardEvent {
            const e = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
            target.dispatchEvent(e);
            return e;
        }
        const valueButton = (h: HTMLElement) => h.querySelector('button:not(.pen-pencil)') as HTMLButtonElement;

        it('a printable key opens the editor holding that key (it replaces the value), focused with the caret after it', async () => {
            const { fixture, host, hosts } = setupTyping();
            const e = press(valueButton(hosts[0]), 'N');
            expect(e.defaultPrevented).toBe(true); // the key is not typed a second time
            fixture.detectChanges();
            await fixture.whenStable();
            const box = hosts[0].querySelector('input') as HTMLInputElement;
            expect(box).not.toBeNull();
            expect(box.value).toBe('N');
            expect(document.activeElement).toBe(box);
            expect(box.selectionStart).toBe(1);
            box.value = 'New';
            box.dispatchEvent(new Event('input'));
            press(box, 'Enter');
            fixture.detectChanges();
            expect(host.nameCtl.value).toBe('New');
        });

        it('Enter and Escape hand focus back to the value button, never to the page body', async () => {
            const { fixture, hosts } = setupTyping();
            for (const key of ['Enter', 'Escape']) {
                press(valueButton(hosts[0]), 'N');
                fixture.detectChanges();
                await fixture.whenStable();
                const box = hosts[0].querySelector('input') as HTMLInputElement;
                expect(document.activeElement).toBe(box);
                press(box, key);
                fixture.detectChanges();
                await fixture.whenStable();
                expect(hosts[0].querySelector('input'), key).toBeNull();
                expect(document.activeElement, key).toBe(valueButton(hosts[0]));
            }
        });

        it('the pencil takes a typed key too; Escape still drops the edit', () => {
            const { fixture, host, hosts } = setupTyping();
            press(hosts[0].querySelector('button.pen-pencil')!, 'x');
            fixture.detectChanges();
            const box = hosts[0].querySelector('input') as HTMLInputElement;
            expect(box.value).toBe('x');
            press(box, 'Escape');
            fixture.detectChanges();
            expect(host.nameCtl.value).toBe('old');
        });

        it('shortcuts, Space, Tab, arrows and Enter keep their meaning', () => {
            const { fixture, hosts } = setupTyping();
            const btn = valueButton(hosts[0]);
            for (const [key, init] of [
                ['a', { ctrlKey: true }],
                ['c', { metaKey: true }],
                ['x', { altKey: true }],
                [' ', {}],
                ['Tab', {}],
                ['ArrowDown', {}],
                ['Enter', {}],
                ['F2', {}],
            ] as const) {
                const e = press(btn, key, init);
                fixture.detectChanges();
                expect(e.defaultPrevented, key).toBe(false);
                expect(hosts[0].querySelector('input'), key).toBeNull();
            }
        });

        it('a number field opens only on a key a number can start with; a date field never opens from one key', () => {
            const { fixture, hosts } = setupTyping();
            press(valueButton(hosts[1]), 'q');
            fixture.detectChanges();
            expect(hosts[1].querySelector('input')).toBeNull();
            press(valueButton(hosts[1]), '7');
            fixture.detectChanges();
            expect((hosts[1].querySelector('input') as HTMLInputElement).value).toBe('7');
            press(valueButton(hosts[2]), '2');
            fixture.detectChanges();
            expect(hosts[2].querySelector('input')).toBeNull();
        });

        it('a read-only or disabled row does not open', () => {
            const { fixture, hosts } = setupTyping();
            for (const h of [hosts[3], hosts[4]]) {
                const e = press(valueButton(h), 'z');
                fixture.detectChanges();
                expect(e.defaultPrevented).toBe(false);
                expect(h.querySelector('input')).toBeNull();
            }
        });
    });

    describe('one accessible name per field (LA-UI-TEXT-PASS-1 X3)', () => {
        function names(): { fixture: ReturnType<typeof TestBed.createComponent<TypingHostComponent>>; hosts: HTMLElement[] } {
            const fixture = TestBed.createComponent(TypingHostComponent);
            fixture.detectChanges();
            return { fixture, hosts: Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('inspecto-pen-field')) };
        }

        it('names the value button "<label>: <value>, edit" and hides the pencil from assistive tech', () => {
            const { hosts } = names();
            const value = hosts[0].querySelector('button:not(.pen-pencil)')!;
            const pencil = hosts[0].querySelector('button.pen-pencil')!;
            expect(value.getAttribute('aria-label')).toBe('Name: old, edit');
            expect(pencil.getAttribute('aria-hidden')).toBe('true');
            expect(pencil.getAttribute('tabindex')).toBe('-1');
        });

        it('says "empty" for a blank value and drops ", edit" where the row cannot be edited', () => {
            const { hosts } = names();
            const label = (h: HTMLElement) => h.querySelector('button:not(.pen-pencil)')!.getAttribute('aria-label');
            expect(label(hosts[2])).toBe('From: empty, edit');
            expect(label(hosts[3])).toBe('Fixed: kept');
            expect(label(hosts[4])).toBe('Off: off');
        });

        it('tells the editor how to save and cancel', () => {
            const { fixture, hosts } = names();
            (hosts[0].querySelector('button:not(.pen-pencil)') as HTMLButtonElement).click();
            fixture.detectChanges();
            expect(hosts[0].querySelector('input')!.getAttribute('aria-description')).toBe('Enter to save, Escape to cancel');
        });
    });
});
