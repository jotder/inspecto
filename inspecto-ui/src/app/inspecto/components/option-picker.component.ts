import { ChangeDetectionStrategy, Component, computed, forwardRef, inject, input, signal } from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

/** One offered choice. Same shape the `AttributeSpec.options` list already carries. */
export interface PickerOption {
    value: string;
    label: string;
    /** Optional one-line explanation, shown under the label in the popup only. */
    hint?: string;
}

interface PickerData {
    title: string;
    options: PickerOption[];
    current: string | null;
}

/** How many choices are worth a filter box — below this the list is scannable as-is. */
const SEARCH_THRESHOLD = 8;

/**
 * The popup half of {@link InspectoOptionPickerComponent} — a dialog rather than an overlay menu, so
 * every choice gets room for a full label and a hint, and the list can be filtered. Deliberately not
 * exported: the picker is the only way in, so a caller cannot end up with a popup that has no control
 * behind it.
 */
@Component({
    selector: 'inspecto-option-picker-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [FormsModule, MatButtonModule, MatDialogModule, MatIconModule],
    template: `
        <h2 mat-dialog-title class="truncate">{{ data.title }}</h2>
        <mat-dialog-content class="min-w-80">
            @if (data.options.length >= threshold) {
                <input
                    class="bg-default mb-2 w-full rounded border p-2 text-sm"
                    type="search"
                    cdkFocusInitial
                    [(ngModel)]="query"
                    (ngModelChange)="filter.set($event)"
                    [attr.aria-label]="'Filter ' + data.title"
                    placeholder="Filter…"
                />
            }
            <div role="listbox" [attr.aria-label]="data.title" (keydown)="onKeydown($event)">
                @for (opt of shown(); track opt.value) {
                    <button
                        type="button"
                        role="option"
                        class="hover:bg-hover flex w-full items-start gap-2 rounded px-2 py-2 text-left"
                        [attr.aria-selected]="opt.value === data.current"
                        [attr.cdkFocusInitial]="
                            data.options.length < threshold && opt.value === data.current ? '' : null
                        "
                        (click)="ref.close(opt.value)"
                    >
                        <mat-icon
                            class="icon-size-5 shrink-0"
                            [class.opacity-0]="opt.value !== data.current"
                            svgIcon="heroicons_outline:check"
                        ></mat-icon>
                        <span class="min-w-0">
                            <span class="block text-sm">{{ opt.label }}</span>
                            @if (opt.hint) {
                                <span class="text-secondary block text-xs">{{ opt.hint }}</span>
                            }
                        </span>
                    </button>
                }
                @if (!shown().length) {
                    <p class="text-secondary m-0 px-2 py-3 text-sm">No choice matches “{{ filter() }}”.</p>
                }
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-stroked-button type="button" (click)="ref.close()">Cancel</button>
        </mat-dialog-actions>
    `,
})
export class OptionPickerDialog {
    readonly data = inject<PickerData>(MAT_DIALOG_DATA);
    readonly ref = inject<MatDialogRef<OptionPickerDialog, string | undefined>>(MatDialogRef);
    readonly threshold = SEARCH_THRESHOLD;

    query = '';
    readonly filter = signal('');
    readonly shown = computed(() => {
        const q = this.filter().trim().toLowerCase();
        if (!q) return this.data.options;
        return this.data.options.filter((o) => o.label.toLowerCase().includes(q) || o.value.toLowerCase().includes(q));
    });

    /**
     * Arrow keys walk the list, as a `role="listbox"` must. The dialog itself traps focus and Escape
     * closes it, so this is the only key handling the popup owns.
     */
    onKeydown(event: KeyboardEvent): void {
        if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
        const items = Array.from(
            (event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('button[role="option"]'),
        );
        if (!items.length) return;
        const at = items.indexOf(document.activeElement as HTMLButtonElement);
        const next = event.key === 'ArrowDown' ? at + 1 : at - 1;
        items[(next + items.length) % items.length].focus();
        event.preventDefault();
    }
}

/**
 * A single-choice control that asks in a POPUP instead of a dropdown (operator ask 2026-08-22). Same
 * job as `mat-select` and a drop-in for it — a `ControlValueAccessor`, so `formControlName` /
 * `[(ngModel)]` bind unchanged — but the choices open in a dialog: full-length labels, a per-option
 * hint, and a filter box once there are enough of them to scan.
 *
 * <p>⚠ It renders its OWN label and error line rather than living inside a `mat-form-field`. A
 * `mat-form-field` derives its error state from an `NgControl` on the projected input, and this
 * control's value lives on the component — so a `<mat-error>` here could never fire, exactly the trap
 * `schema-form`'s `list` type hit. The error is therefore an explicit `role="alert"` line, and a spec
 * must assert the RENDERED element, never that a getter returned the string.
 */
@Component({
    selector: 'inspecto-option-picker',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatIconModule],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => InspectoOptionPickerComponent),
            multi: true,
        },
    ],
    template: `
        <div class="flex flex-col">
            <!--
                A compact PROPERTY ROW, not a boxed field (operator ask 2026-08-23): the label and the
                current value share one line, and the value itself — with a small chevron as the "this
                is selectable" hint — is the trigger. No border box below the label; that geometry cost
                a full extra row per field and made dense option sheets (the 4-tab Grammar form) tall.
                The trigger is a plain button, not a mat-*-button: Material's own button lays its
                content out internally, so a full-width trigger came out with the chevron at the far
                LEFT and the value pushed to the right edge.
            -->
            <div class="flex min-h-8 w-full items-center justify-between gap-3">
                <span class="text-secondary min-w-0 text-sm" [id]="labelId">
                    {{ label() }}
                    @if (required()) {
                        <span class="text-warn" aria-hidden="true">*</span>
                    }
                </span>
                <button
                    type="button"
                    class="hover:bg-hover -my-0.5 flex min-w-0 items-center gap-1 rounded px-2 py-1 disabled:opacity-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2"
                    style="max-width: 70%"
                    [disabled]="disabled()"
                    [attr.aria-labelledby]="labelId"
                    [attr.aria-describedby]="invalid() ? errorId : null"
                    (click)="open()"
                    (blur)="onTouched()"
                >
                    <span class="min-w-0 truncate text-sm" [class.text-secondary]="!value()">{{ display() }}</span>
                    <mat-icon
                        class="icon-size-4 text-secondary shrink-0"
                        svgIcon="heroicons_outline:chevron-down"
                    ></mat-icon>
                </button>
            </div>
            @if (invalid()) {
                <p class="text-warn m-0 text-xs" role="alert" [id]="errorId">{{ label() }} is required</p>
            } @else if (help()) {
                <p class="text-secondary m-0 text-xs">{{ help() }}</p>
            }
        </div>
    `,
})
export class InspectoOptionPickerComponent implements ControlValueAccessor {
    readonly label = input('');
    readonly options = input<PickerOption[]>([]);
    /** Shown on the trigger while nothing is chosen. */
    readonly placeholder = input('Select');
    readonly help = input('');
    readonly required = input(false);

    private dialog = inject(MatDialog);

    readonly value = signal<string | null>(null);
    readonly disabled = signal(false);
    /** Set once the control has been visited, so a fresh form does not open red. */
    private readonly touched = signal(false);

    private static seq = 0;
    private readonly uid = `option-picker-${InspectoOptionPickerComponent.seq++}`;
    readonly labelId = `${this.uid}-label`;
    readonly errorId = `${this.uid}-error`;

    /**
     * A value with no matching option is shown VERBATIM, never as "Choose…" — a stored config can name
     * a choice this deployment no longer offers, and blanking it in the trigger would read as unset
     * and invite an accidental overwrite.
     */
    readonly display = computed(() => {
        const v = this.value();
        if (v === null || v === '') return this.placeholder();
        return this.options().find((o) => o.value === v)?.label ?? v;
    });

    readonly invalid = computed(() => this.required() && this.touched() && !this.value());

    private onChange: (v: string | null) => void = () => {};
    onTouched: () => void = () => {};

    open(): void {
        this.touched.set(true);
        this.onTouched();
        this.dialog
            .open(OptionPickerDialog, {
                data: { title: this.label(), options: this.options(), current: this.value() },
                autoFocus: true,
                restoreFocus: true,
            })
            .afterClosed()
            .subscribe((picked) => {
                if (picked === undefined) return; // dismissed — never write a null over a stored value
                this.value.set(picked);
                this.onChange(picked);
            });
    }

    writeValue(v: unknown): void {
        this.value.set(v === null || v === undefined ? null : String(v));
    }
    registerOnChange(fn: (v: string | null) => void): void {
        this.onChange = fn;
    }
    registerOnTouched(fn: () => void): void {
        this.onTouched = fn;
    }
    setDisabledState(isDisabled: boolean): void {
        this.disabled.set(isDisabled);
    }
}
