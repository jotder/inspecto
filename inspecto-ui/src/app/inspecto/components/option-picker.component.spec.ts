import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoOptionPickerComponent, OptionPickerDialog, PickerOption } from './option-picker.component';

const OPTIONS: PickerOption[] = [
    { value: 'delimited', label: 'Delimited text', hint: 'CSV, TSV, pipe-separated' },
    { value: 'json', label: 'JSON' },
    { value: 'fixedwidth', label: 'Fixed width' },
];

/**
 * Host so `formControlName` binds through the CVA, which is the whole point of the component.
 *
 * <p>⚠ `Eager` on purpose: under Angular 22 an unspecified strategy is OnPush, so a test that mutates
 * a host field AFTER the first `detectChanges` (the blank-option case below) would never re-render and
 * the spec would fail while the real screen is correct.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [ReactiveFormsModule, InspectoOptionPickerComponent],
    template: `
        <inspecto-option-picker [formControl]="control" label="Format" [options]="options()" [required]="true" />
    `,
})
class HostComponent {
    control = new FormControl<string | null>('json', Validators.required);
    // Zoneless CD: signal so the mutated-options test marks the host dirty (no NG0100 on verify).
    options = signal(OPTIONS);
}

/** What the popup returned, per test — `undefined` is a dismissal. */
let picked: string | undefined;

function create(initial: string | null = 'json') {
    picked = undefined;
    const open = vi.fn(() => ({ afterClosed: () => of(picked) }));
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [provideNoopAnimations()],
    });
    TestBed.overrideProvider(MatDialog, { useValue: { open } });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.control.setValue(initial);
    fixture.detectChanges();
    return { fixture, open };
}

function trigger(fixture: ComponentFixture<HostComponent>): HTMLButtonElement {
    return fixture.nativeElement.querySelector('button');
}

describe('InspectoOptionPickerComponent', () => {
    it('shows the chosen option’s LABEL, not its stored value', () => {
        const { fixture } = create('delimited');
        expect(trigger(fixture).textContent).toContain('Delimited text');
    });

    /**
     * 🔴 A stored config can name a choice this deployment no longer offers. Blanking it in the
     * trigger would read as unset and invite an accidental overwrite of a working config.
     */
    it('shows an unknown stored value verbatim rather than as unset', () => {
        const { fixture } = create('avro_v3');
        expect(trigger(fixture).textContent).toContain('avro_v3');
        expect(trigger(fixture).textContent).not.toContain('Select');
    });

    /**
     * 🔴 An option whose value is BLANK is a real, named choice — the idiom for "the engine default,
     * written as no key at all" (the Grammar editor's Parse engine ▸ Auto). Falling through to the
     * placeholder there showed "Select" on a field that was in fact set to Auto, which is the reason
     * this case is pinned: the value is indistinguishable from unset, only the option list says
     * otherwise.
     */
    it('shows a blank-valued option’s label instead of the placeholder', () => {
        const { fixture } = create(null);
        fixture.componentInstance.options.set([{ value: '', label: 'Auto — engine default' }, ...OPTIONS]);
        fixture.detectChanges();
        expect(trigger(fixture).textContent).toContain('Auto — engine default');
        expect(trigger(fixture).textContent).not.toContain('Select');
    });

    /**
     * The trigger is a compact PROPERTY ROW (operator ask 2026-08-23): label and value share one
     * line, and the trigger is BORDERLESS — the chevron is the selectability hint, not a box below
     * the label. jsdom cannot measure layout, so this pins the class contract; the preview is the
     * visual proof.
     */
    it('renders as a borderless label/value row, not a boxed field below the label', () => {
        const { fixture } = create('json');
        const btn = trigger(fixture);
        expect(btn.classList.contains('border')).toBe(false);
        const row = btn.parentElement as HTMLElement;
        expect(row.className).toContain('justify-between');
        expect(row.querySelector('[id$="-label"]')).toBeTruthy();
    });

    it('writes the picked value back through the form control', () => {
        const { fixture, open } = create('json');
        picked = 'fixedwidth';
        trigger(fixture).click();
        expect(open).toHaveBeenCalled();
        expect(fixture.componentInstance.control.value).toBe('fixedwidth');
    });

    /** 🔴 Dismissing must not write: a Cancel that nulled the control would destroy a stored value. */
    it('leaves the value untouched when the popup is dismissed', () => {
        const { fixture } = create('json');
        picked = undefined;
        trigger(fixture).click();
        expect(fixture.componentInstance.control.value).toBe('json');
    });

    /**
     * ⚠ The error is an explicit `role="alert"` line, NOT a `<mat-error>` — there is no `NgControl` on
     * a projected input here, so a mat-error could never fire (the trap the `list` type hit). Assert
     * the RENDERED element, never that a getter returned the string.
     */
    it('renders a required error only after the field has been visited', () => {
        const { fixture } = create(null);
        expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();

        trigger(fixture).click();
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Format is required');
    });

    it('has no a11y violations', async () => {
        const { fixture } = create('json');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('OptionPickerDialog', () => {
    function createDialog(options: PickerOption[], current: string | null = null) {
        const close = vi.fn();
        TestBed.configureTestingModule({
            imports: [OptionPickerDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MAT_DIALOG_DATA, useValue: { title: 'Format', options, current } },
                { provide: MatDialogRef, useValue: { close } },
            ],
        });
        const fixture = TestBed.createComponent(OptionPickerDialog);
        fixture.detectChanges();
        return { fixture, close };
    }

    it('offers a filter box only once the list is long enough to need one', () => {
        const { fixture } = createDialog(OPTIONS);
        expect(fixture.nativeElement.querySelector('input[type="search"]')).toBeNull();
    });

    it('filters on label and on the stored value', () => {
        const many = Array.from({ length: 9 }, (_, i) => ({ value: `v${i}`, label: `Option ${i}` }));
        const { fixture } = createDialog(many);
        expect(fixture.nativeElement.querySelector('input[type="search"]')).toBeTruthy();

        fixture.componentInstance.filter.set('v7');
        fixture.detectChanges();
        const shown = Array.from(fixture.nativeElement.querySelectorAll('button[role="option"]'));
        expect(shown).toHaveLength(1);
        expect((shown[0] as HTMLElement).textContent).toContain('Option 7');
    });

    it('marks the current choice as selected and closes with the picked value', () => {
        const { fixture, close } = createDialog(OPTIONS, 'json');
        const options = Array.from(
            fixture.nativeElement.querySelectorAll('button[role="option"]'),
        ) as HTMLButtonElement[];
        expect(options.map((o) => o.getAttribute('aria-selected'))).toEqual(['false', 'true', 'false']);

        options[2].click();
        expect(close).toHaveBeenCalledWith('fixedwidth');
    });

    it('has no a11y violations', async () => {
        const { fixture } = createDialog(OPTIONS, 'json');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
