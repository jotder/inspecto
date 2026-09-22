import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ChipComponent } from './chip.component';

export type FilterValue = string | number | null;

/** One field of an `<inspecto-filter-bar>`. */
export interface FilterField {
    key: string;
    label: string;
    type: 'text' | 'select' | 'number';
    options?: { value: FilterValue; label: string }[];
    placeholder?: string;
    /** The value that counts as "no filter" for this field; it draws no chip. Defaults to `null`/`''`. */
    defaultValue?: FilterValue;
    /** Tailwind width class for the control, e.g. `w-40`. */
    width?: string;
}

export type FilterValues = Record<string, FilterValue>;

/**
 * The ONE filter bar for list panes (UI consolidation plan UI-04). Collapsed by default to a single
 * row — a Filter toggle carrying the active-filter count, one removable chip per active filter, and
 * Reset — with the fields in a panel that opens beneath it. Events went from three toolbar rows to
 * one; Diagnoses' lone `Limit` input leaves its header.
 *
 * Contract: `value` is a flat record keyed by `FilterField.key`. Editing a field emits `valueChange`
 * immediately; `apply` fires when the operator presses Apply, hits Enter in a text field, changes a
 * select, removes a chip or resets — the host runs its query on `apply`, not on every keystroke.
 *
 * The selects here stay `mat-select` on purpose: the skill exempts grid-toolbar selects from the
 * option-picker rule (a modal per filter pick is worse than the dropdown it replaces).
 */
@Component({
    selector: 'inspecto-filter-bar',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        ChipComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block' },
    template: `
        <div class="flex flex-wrap items-center gap-2">
            <button
                mat-stroked-button
                type="button"
                class="relative"
                [attr.aria-expanded]="open()"
                aria-controls="inspecto-filter-panel"
                (click)="open.set(!open())"
            >
                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:funnel"></mat-icon>
                <span class="ml-1">Filter</span>
                @if (active.length) {
                    <inspecto-chip class="ml-1.5" variant="soft" tone="primary">
                        <span class="tabular-nums">{{ active.length }}</span>
                    </inspecto-chip>
                }
            </button>
            @for (a of active; track a.key) {
                <inspecto-chip
                    variant="soft"
                    tone="primary"
                    removable
                    [removeLabel]="'Clear ' + a.label"
                    (removed)="clearOne(a.key)"
                >
                    <span class="text-secondary">{{ a.label }}:</span> {{ a.display }}
                </inspecto-chip>
            }
            @if (active.length) {
                <button mat-button type="button" (click)="reset()">Reset</button>
            }
            <span class="flex-auto"></span>
            <ng-content select="[end]"></ng-content>
        </div>
        @if (open()) {
            <!-- A real <form>, not a div with a keyup handler: Enter in any field then submits
                 natively through the Apply button, with no keyboard trap and nothing to make
                 focusable by hand (@angular-eslint interactive-supports-focus). -->
            <form
                id="inspecto-filter-panel"
                class="bg-card mt-2 flex flex-wrap items-end gap-3 rounded-lg border p-3"
                (submit)="$event.preventDefault(); apply.emit(value)"
            >
                @for (f of fields; track f.key) {
                    <mat-form-field class="gamma-mat-dense" [class]="f.width || 'w-44'" subscriptSizing="dynamic">
                        <mat-label>{{ f.label }}</mat-label>
                        @switch (f.type) {
                            @case ('select') {
                                <mat-select
                                    [ngModel]="value[f.key] ?? null"
                                    [ngModelOptions]="{ standalone: true }"
                                    (ngModelChange)="set(f.key, $event, true)"
                                    [attr.aria-label]="f.label"
                                >
                                    @for (o of f.options ?? []; track o.value) {
                                        <mat-option [value]="o.value">{{ o.label }}</mat-option>
                                    }
                                </mat-select>
                            }
                            @case ('number') {
                                <input
                                    matInput
                                    type="number"
                                    [ngModel]="value[f.key] ?? null"
                                    [ngModelOptions]="{ standalone: true }"
                                    (ngModelChange)="set(f.key, $event === '' ? null : $event)"
                                    [placeholder]="f.placeholder || ''"
                                />
                            }
                            @default {
                                <input
                                    matInput
                                    [ngModel]="value[f.key] ?? ''"
                                    [ngModelOptions]="{ standalone: true }"
                                    (ngModelChange)="set(f.key, $event)"
                                    [placeholder]="f.placeholder || ''"
                                />
                            }
                        }
                    </mat-form-field>
                }
                <button mat-flat-button color="primary" type="submit">Apply</button>
                <button mat-button type="button" (click)="reset()">Reset</button>
            </form>
        }
    `,
})
export class InspectoFilterBarComponent {
    @Input({ required: true }) fields: FilterField[] = [];
    @Input() value: FilterValues = {};
    /** Every edit. */
    @Output() readonly valueChange = new EventEmitter<FilterValues>();
    /** The host should run its query on this — Apply, Enter, a select change, a chip removal, Reset. */
    @Output() readonly apply = new EventEmitter<FilterValues>();

    readonly open = signal(false);

    /** Fields whose value differs from their "no filter" default, with a display string for the chip. */
    get active(): { key: string; label: string; display: string }[] {
        return this.fields
            .filter((f) => !this.isDefault(f, this.value[f.key]))
            .map((f) => ({ key: f.key, label: f.label, display: this.display(f, this.value[f.key]) }));
    }

    set(key: string, v: FilterValue, applyNow = false): void {
        this.value = { ...this.value, [key]: v };
        this.valueChange.emit(this.value);
        if (applyNow) {
            this.apply.emit(this.value);
        }
    }

    clearOne(key: string): void {
        const f = this.fields.find((x) => x.key === key);
        this.set(key, f?.defaultValue ?? null, true);
    }

    reset(): void {
        const next: FilterValues = {};
        for (const f of this.fields) {
            next[f.key] = f.defaultValue ?? null;
        }
        this.value = next;
        this.valueChange.emit(next);
        this.apply.emit(next);
    }

    private isDefault(f: FilterField, v: FilterValue | undefined): boolean {
        const empty = v === null || v === undefined || v === '';
        if (f.defaultValue === undefined || f.defaultValue === null || f.defaultValue === '') {
            return empty;
        }
        return v === f.defaultValue;
    }

    private display(f: FilterField, v: FilterValue | undefined): string {
        if (f.type === 'select') {
            const o = f.options?.find((x) => x.value === v);
            if (o) {
                return o.label;
            }
        }
        return String(v ?? '');
    }
}
