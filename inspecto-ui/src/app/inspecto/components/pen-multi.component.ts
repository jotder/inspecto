import { ChangeDetectionStrategy, Component, forwardRef, input, signal } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';

export interface PenMultiOption {
    value: string;
    label: string;
}

/**
 * **Pen-style multi-choice** — the `label · value · ✎` row of `<inspecto-pen-field>` for a `string[]`. The value reads
 * as a comma list; the pencil opens the choices as toggle chips beneath the row (no overlay, so a host polling state on
 * clicks still sees every change) and the check closes them. A `ControlValueAccessor`.
 */
@Component({
    selector: 'inspecto-pen-multi',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatIconModule],
    providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => PenMultiComponent), multi: true }],
    host: { class: 'block min-w-0' },
    template: `
        <div class="flex min-h-8 min-w-0 items-center gap-2">
            <span class="text-secondary shrink-0 text-xs">{{ label() }}</span>
            <button
                type="button"
                class="hover:bg-hover min-w-0 flex-1 truncate rounded px-1 py-0.5 text-left text-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                [disabled]="disabled()"
                [attr.aria-label]="'Edit ' + label()"
                (click)="open.set(!open())"
            >
                @if (value().length) {
                    {{ value().join(', ') }}
                } @else {
                    <span class="text-secondary">{{ placeholder() }}</span>
                }
            </button>
            @if (!disabled()) {
                <button
                    type="button"
                    class="text-secondary hover:bg-hover flex h-7 w-7 shrink-0 items-center justify-center rounded focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                    [attr.aria-label]="(open() ? 'Done editing ' : 'Edit ') + label()"
                    (click)="open.set(!open())"
                >
                    <mat-icon
                        class="icon-size-4"
                        [svgIcon]="open() ? 'heroicons_outline:check' : 'heroicons_outline:pencil'"
                    ></mat-icon>
                </button>
            }
        </div>
        @if (open()) {
            <div class="flex flex-wrap gap-1 pb-1" role="group" [attr.aria-label]="label()">
                @for (o of options(); track o.value) {
                    <button
                        type="button"
                        class="rounded-full border px-2 py-0.5 text-xs focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                        [class.bg-primary]="isOn(o.value)"
                        [class.text-on-primary]="isOn(o.value)"
                        [attr.aria-pressed]="isOn(o.value)"
                        (click)="toggle(o.value)"
                    >
                        {{ o.label }}
                    </button>
                } @empty {
                    <span class="text-secondary text-xs">No choices yet.</span>
                }
            </div>
        }
    `,
})
export class PenMultiComponent implements ControlValueAccessor {
    readonly label = input('');
    readonly options = input<PenMultiOption[]>([]);
    readonly placeholder = input('—');

    protected readonly value = signal<string[]>([]);
    protected readonly disabled = signal(false);
    protected readonly open = signal(false);

    private onChange: (v: string[]) => void = () => undefined;
    private onTouched: () => void = () => undefined;

    writeValue(v: string[] | null): void {
        this.value.set(v ?? []);
    }
    registerOnChange(fn: (v: string[]) => void): void {
        this.onChange = fn;
    }
    registerOnTouched(fn: () => void): void {
        this.onTouched = fn;
    }
    setDisabledState(d: boolean): void {
        this.disabled.set(d);
    }

    protected isOn(v: string): boolean {
        return this.value().includes(v);
    }

    protected toggle(v: string): void {
        const next = this.isOn(v) ? this.value().filter((x) => x !== v) : [...this.value(), v];
        this.value.set(next);
        this.onChange(next);
        this.onTouched();
    }
}
