import { ChangeDetectorRef } from '@angular/core';
import { ComponentFixture } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { InspectoOptionPickerComponent } from '../components/option-picker.component';

/**
 * Spec helpers for `<inspecto-pen-field>`: the value reads as text until the pencil (or the text) opens an input, and
 * the edit is committed on Enter or blur. A spec that used to set `input.value` on a `matInput` now edits the way a
 * person does, so the control sees exactly what a real edit sends.
 */

/** The pen field whose visible label starts with `label` (first match), or null. */
export function penByLabel(root: ParentNode, label: string): HTMLElement | null {
    return (
        Array.from(root.querySelectorAll<HTMLElement>('inspecto-pen-field')).find((h) =>
            (h.querySelector('span')?.textContent ?? '').trim().startsWith(label),
        ) ?? null
    );
}

/** The text a closed pen field shows (its value, else its ghost default / placeholder). */
export function penText(host: HTMLElement): string {
    return (host.querySelector('button')?.textContent ?? '').trim();
}

/** The error line a pen field shows, or ''. */
export function penError(host: HTMLElement): string {
    return (host.querySelector('p[role="alert"]')?.textContent ?? '').trim();
}

/** Open a pen field and return its editor (an `<input>`, or a `<textarea>` for a multiline field). */
export function penOpen(fixture: ComponentFixture<unknown>, host: HTMLElement): HTMLInputElement | HTMLTextAreaElement {
    if (!host.querySelector('input, textarea')) {
        const pencil =
            host.querySelector<HTMLButtonElement>('button.pen-pencil') ??
            host.querySelector<HTMLButtonElement>('button');
        pencil!.click();
        fixture.detectChanges();
    }
    return host.querySelector<HTMLInputElement | HTMLTextAreaElement>('input, textarea')!;
}

/** Edit a pen field as a person does: open it, type `value`, commit with Enter. */
export function penEdit(fixture: ComponentFixture<unknown>, host: HTMLElement, value: string): void {
    const box = penOpen(fixture, host);
    box.value = value;
    box.dispatchEvent(new Event('input'));
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    fixture.detectChanges();
}

/**
 * Re-render an `OnPush` component after a spec changed its form FROM CODE (`patchValue`, `submit()`): a real edit is a
 * template event, which marks the view dirty on its own; a programmatic change is not, so the view must be marked.
 */
export function refresh(fixture: ComponentFixture<unknown>): void {
    fixture.debugElement.injector.get(ChangeDetectorRef).markForCheck();
    fixture.detectChanges();
}

/**
 * Set an `<inspecto-option-picker>` as a pick in its dialog would: the picker's own value changes and its control is
 * told. `selector` names the picker's host element (e.g. `[data-test=edit-severity]`).
 */
export function pickerSet(fixture: ComponentFixture<unknown>, selector: string, value: string): void {
    const picker = fixture.debugElement
        .queryAll(By.directive(InspectoOptionPickerComponent))
        .find((d) => (d.nativeElement as HTMLElement).matches(selector));
    if (!picker) throw new Error('no option picker matches ' + selector);
    const cmp = picker.componentInstance as unknown as { value: { set(v: string): void }; onChange(v: string): void };
    cmp.value.set(value);
    cmp.onChange(value);
    fixture.detectChanges();
}
