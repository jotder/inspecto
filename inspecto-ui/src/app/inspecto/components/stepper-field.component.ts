import { ChangeDetectionStrategy, Component, computed, forwardRef, input, signal } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

let nextId = 0;

/**
 * **Stepper field** — one compact property row for a small whole number: `label · − 3 +`. The value is a
 * `role="spinbutton"` (arrow keys step it, Home / End jump to the bounds); the − / + buttons step it with the mouse.
 * At a bound the button stays focusable but inert (`aria-disabled`) and carries the host's reason as its tooltip; a
 * press against the bound shows that reason as one line under the row.
 *
 * A `ControlValueAccessor` holding a number, like `<inspecto-pen-field>` and `<inspecto-option-picker>` it renders its
 * own label; do not wrap it in a `mat-form-field`.
 */
@Component({
    selector: 'inspecto-stepper-field',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatIconModule, MatTooltipModule],
    providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => StepperFieldComponent), multi: true }],
    host: { class: 'block min-w-0' },
    template: `
        <div class="flex min-h-8 min-w-0 items-center justify-between gap-3">
            <span class="text-secondary min-w-0 text-sm" [id]="labelId">{{ label() }}</span>
            <div class="flex shrink-0 items-center gap-1">
                <button
                    type="button"
                    class="hover:bg-hover flex h-7 w-7 items-center justify-center rounded border focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                    style="border-color: var(--gamma-border)"
                    data-testid="stepper-decrement"
                    [class.opacity-50]="atMin() || disabled()"
                    [attr.aria-label]="decrementLabel()"
                    [attr.aria-disabled]="atMin() || disabled() ? 'true' : null"
                    [matTooltip]="atMin() ? minReason() : ''"
                    (click)="step(-1)"
                >
                    <mat-icon class="icon-size-4" svgIcon="heroicons_outline:minus" aria-hidden="true"></mat-icon>
                </button>
                <span
                    role="spinbutton"
                    class="min-w-8 rounded px-1 text-center text-sm font-semibold tabular-nums focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                    data-testid="stepper-value"
                    [attr.tabindex]="disabled() ? -1 : 0"
                    [attr.aria-labelledby]="labelId"
                    [attr.aria-valuenow]="current()"
                    [attr.aria-valuemin]="min()"
                    [attr.aria-valuemax]="max()"
                    [attr.aria-valuetext]="valueText()?.(current()) ?? null"
                    [attr.aria-disabled]="disabled() ? 'true' : null"
                    [attr.aria-describedby]="bounced() ? hintId : null"
                    (keydown)="onKey($event)"
                    (blur)="onTouched()"
                    >{{ shown() }}</span
                >
                <button
                    type="button"
                    class="hover:bg-hover flex h-7 w-7 items-center justify-center rounded border focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1"
                    style="border-color: var(--gamma-border)"
                    data-testid="stepper-increment"
                    [class.opacity-50]="atMax() || disabled()"
                    [attr.aria-label]="incrementLabel()"
                    [attr.aria-disabled]="atMax() || disabled() ? 'true' : null"
                    [matTooltip]="atMax() ? maxReason() : ''"
                    (click)="step(1)"
                >
                    <mat-icon class="icon-size-4" svgIcon="heroicons_outline:plus" aria-hidden="true"></mat-icon>
                </button>
            </div>
        </div>
        @if (bounced(); as why) {
            <p class="text-secondary m-0 text-right text-xs" role="status" [id]="hintId" data-testid="stepper-reason">{{ why }}</p>
        }
    `,
})
export class StepperFieldComponent implements ControlValueAccessor {
    readonly label = input.required<string>();
    readonly min = input(0);
    readonly max = input(10);
    /** The − button's accessible name, e.g. "Fewer degrees". */
    readonly decrementLabel = input('Decrease');
    /** The + button's accessible name, e.g. "More degrees". */
    readonly incrementLabel = input('Increase');
    /** Why − does nothing at the lower bound (its tooltip, and the line shown after a press against it). */
    readonly minReason = input('');
    /** Why + does nothing at the upper bound. */
    readonly maxReason = input('');
    /** Words for a value (also its `aria-valuetext`), e.g. 0 = "Don't expand"; null = the bare number. */
    readonly valueText = input<((n: number) => string) | null>(null);

    readonly labelId = `inspecto-stepper-${++nextId}`;
    readonly hintId = `${this.labelId}-why`;
    protected readonly value = signal(0);
    protected readonly disabled = signal(false);
    /** The bound reason after a press against it; cleared by the next real step. */
    protected readonly bounced = signal('');

    /** The value held inside the bounds (a stored value past a lowered maximum reads as the maximum). */
    readonly current = computed(() => Math.min(Math.max(this.value(), this.min()), Math.max(this.min(), this.max())));
    readonly atMin = computed(() => this.current() <= this.min());
    readonly atMax = computed(() => this.current() >= this.max());
    protected readonly shown = computed(() => this.valueText()?.(this.current()) ?? String(this.current()));

    private onChange: (v: number) => void = () => undefined;
    onTouched: () => void = () => undefined;

    writeValue(v: number | string | null): void {
        const n = Number(v);
        this.value.set(Number.isFinite(n) ? Math.trunc(n) : this.min());
        this.bounced.set('');
    }
    registerOnChange(fn: (v: number) => void): void {
        this.onChange = fn;
    }
    registerOnTouched(fn: () => void): void {
        this.onTouched = fn;
    }
    setDisabledState(d: boolean): void {
        this.disabled.set(d);
    }

    /** Move by `delta`; at a bound nothing changes and the bound's reason is shown. */
    step(delta: number): void {
        this.set(this.current() + delta);
    }

    private set(n: number): void {
        if (this.disabled()) return;
        const lo = this.min();
        const hi = Math.max(lo, this.max());
        if (n < lo || n > hi) {
            this.bounced.set((n < lo ? this.minReason() : this.maxReason()) || '');
            n = Math.min(Math.max(n, lo), hi);
        } else {
            this.bounced.set('');
        }
        if (n === this.value()) return;
        this.value.set(n);
        this.onChange(n);
        this.onTouched();
    }

    protected onKey(e: KeyboardEvent): void {
        const by: Record<string, () => void> = {
            ArrowUp: () => this.step(1),
            ArrowRight: () => this.step(1),
            ArrowDown: () => this.step(-1),
            ArrowLeft: () => this.step(-1),
            Home: () => this.set(this.min()),
            End: () => this.set(this.max()),
        };
        const act = by[e.key];
        if (!act) return;
        e.preventDefault();
        act();
    }
}
