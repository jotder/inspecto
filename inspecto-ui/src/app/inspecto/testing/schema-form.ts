import { ComponentFixture } from '@angular/core/testing';

/**
 * Spec helpers for `<inspecto-schema-form>` (UI-FORM-ROWS-1 P1): every field is a property row whose real control (an
 * input, a textarea, a list draft) exists only while the row is open. A spec that used to query the input straight
 * away first opens the row the way a person does - a click on its value - and then finds the control inside it.
 */

/** The row of `key` (first match under `root`), or null. */
export function sfRow(root: ParentNode, key: string): HTMLElement | null {
    return root.querySelector<HTMLElement>(`.sf-row[data-key="${key}"]`);
}

/**
 * Open the row of `key` for editing (a click on its value), run change detection, and return the row. A choice or a
 * toggle row has no closed state and is returned as it is.
 */
export function sfOpen(fixture: ComponentFixture<unknown>, key: string): HTMLElement | null {
    const root = fixture.nativeElement as HTMLElement;
    const value = sfRow(root, key)?.querySelector<HTMLButtonElement>('button.sf-value');
    value?.click();
    fixture.detectChanges();
    return sfRow(root, key);
}

/** The help line a row shows under it, or ''. */
export function sfHelp(root: ParentNode, key: string): string {
    return (root.querySelector(`[data-help-for="${key}"]`)?.textContent ?? '').trim();
}
