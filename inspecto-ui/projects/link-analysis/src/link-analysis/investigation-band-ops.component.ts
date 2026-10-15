import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { LensService } from '@inspecto/core/api';
import {
    AbstractControl,
    FormControl,
    FormGroup,
    ReactiveFormsModule,
    ValidationErrors,
    ValidatorFn,
    Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { laLimit } from './la-limits';

const blank = (v: unknown): boolean => v === null || v === undefined || String(v).trim() === '';

/**
 * Mirrors the server's 422s for `threshold` (`InvestigationRoutes`): at least one of min / max, `min` an integer
 * >= 0, `max` an integer >= 1, and `min < max` (max is exclusive).
 */
const bandOrder: ValidatorFn = (g: AbstractControl): ValidationErrors | null => {
    const min = g.get('min')?.value;
    const max = g.get('max')?.value;
    if (blank(min) && blank(max)) return { empty: true };
    return !blank(min) && !blank(max) && Number(min) >= Number(max) ? { order: true } : null;
};

/** The `threshold` measures (`InvestigationEvaluator.MEASURES`); `degree` is the default and is not sent. */
export const THRESHOLD_MEASURES = [
    { value: 'degree', label: 'Degree (distinct counterparties)' },
    { value: 'weightedDegree', label: 'Weighted degree (links, each kind and direction)' },
    { value: 'eventCount', label: 'Event count (events on its links)' },
] as const;
export type ThresholdMeasure = (typeof THRESHOLD_MEASURES)[number]['value'];

export function thresholdForm() {
    return new FormGroup(
        {
            measure: new FormControl<ThresholdMeasure>('degree', { nonNullable: true }),
            min: new FormControl<string | number | null>(null, [Validators.pattern(/^\d+$/)]),
            max: new FormControl<string | number | null>(null, [Validators.pattern(/^\d+$/), Validators.min(1)]),
        },
        { validators: bandOrder },
    );
}

/**
 * LA-INVESTIGATION-OPS-DEFERRED-1 — appends a `threshold` op: keep the entities whose measure (degree by default;
 * weighted degree or event count on choice) falls in `[min, max)`; the rest are excluded exactly as `exclude`
 * does (a `keep` protects), once, with no cascade. The server's 422 is shown verbatim beside the form.
 */
@Component({
    selector: 'inspecto-la-threshold-op',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        InspectoOptionPickerComponent,
        PenFieldComponent,
        ReactiveFormsModule,
        MatButtonModule,
        InspectoAlertComponent,
    ],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2"
            style="border-color: var(--gamma-border)"
            aria-labelledby="la-threshold-op-title"
        >
            <h3 id="la-threshold-op-title" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Threshold
            </h3>
            <form class="flex flex-col gap-2" [formGroup]="form" (ngSubmit)="submit()">
                <inspecto-option-picker label="Measure" formControlName="measure" [options]="measures" />
                <div class="flex flex-col gap-2">
                    <inspecto-pen-field
                        label="Minimum"
                        formControlName="min"
                        type="number"
                        placeholder="2"
                        [error]="form.controls.min.hasError('pattern') ? 'A whole number, 0 or more.' : ''"
                    />
                    <p class="text-secondary m-0 text-xs">Kept at or above this.</p>
                    <inspecto-pen-field
                        label="Maximum"
                        formControlName="max"
                        type="number"
                        placeholder="50"
                        [error]="
                            form.controls.max.hasError('pattern') || form.controls.max.hasError('min')
                                ? 'A whole number, 1 or more.'
                                : ''
                        "
                    />
                    <p class="text-secondary m-0 text-xs">Kept below this.</p>
                </div>
                @if (form.hasError('empty') && form.touched) {
                    <p class="text-warn m-0 text-xs" role="alert">Set a minimum, a maximum or both.</p>
                }
                @if (form.hasError('order') && form.touched) {
                    <p class="text-warn m-0 text-xs" role="alert">The minimum must be less than the maximum.</p>
                }
                <button mat-stroked-button type="submit" class="self-start" [disabled]="!canWrite() || store.busy()">
                    Apply threshold
                </button>
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Threshold refused">{{ error() }}</inspecto-alert>
            }
        </section>
    `,
})
export class InvestigationThresholdOpComponent {
    readonly store = inject(InvestigationSessionStore);
    private readonly lens = inject(LensService);
    readonly canWrite = computed(() => this.lens.canManageIncidents());
    readonly form = thresholdForm();
    readonly measures: PickerOption[] = THRESHOLD_MEASURES.map((m) => ({ value: m.value, label: m.label }));
    readonly error = signal('');

    async submit(): Promise<void> {
        this.error.set('');
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const { measure, min, max } = this.form.getRawValue();
        const ok = await this.store.apply({
            op: 'threshold',
            ...(measure === 'degree' ? {} : { measure }),
            ...(blank(min) ? {} : { min: Number(min) }),
            ...(blank(max) ? {} : { max: Number(max) }),
        });
        if (ok) this.form.reset({ measure: 'degree' });
        else this.error.set(this.store.error());
    }
}

/**
 * LA-INVESTIGATION-OPS-DEFERRED-1 — appends a `snapshot` op: a named marker in the log. It changes nothing in the
 * Working Set and seals no file; the position is the artifact.
 */
@Component({
    selector: 'inspecto-la-snapshot-op',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, PenFieldComponent, InspectoAlertComponent],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2"
            style="border-color: var(--gamma-border)"
            aria-labelledby="la-snapshot-op-title"
        >
            <h3 id="la-snapshot-op-title" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Log marker
            </h3>
            <form class="flex flex-col gap-2" [formGroup]="form" (ngSubmit)="submit()">
                <inspecto-pen-field
                    label="Label (optional)"
                    formControlName="label"
                    placeholder="Before the weekend burst"
                    [error]="form.controls.label.hasError('maxlength') ? 'At most ' + max() + ' characters.' : ''"
                />
                <p class="text-secondary m-0 text-xs">
                    Marks this position in the log; the Working Set does not change.
                </p>
                <button mat-stroked-button type="submit" class="self-start" [disabled]="!canWrite() || store.busy()">
                    Mark this position
                </button>
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Marker refused">{{ error() }}</inspecto-alert>
            }
        </section>
    `,
})
export class InvestigationSnapshotOpComponent {
    readonly store = inject(InvestigationSessionStore);
    private readonly lens = inject(LensService);
    readonly canWrite = computed(() => this.lens.canManageIncidents());
    /** `input.note_length` — the server refuses a longer snapshot label (`InvestigationRoutes.MAX_NOTE_LENGTH`). */
    readonly max = computed(() => laLimit('input.note_length'));
    readonly form = new FormGroup({
        label: new FormControl('', { nonNullable: true, validators: [(c) => Validators.maxLength(this.max())(c)] }),
    });
    readonly error = signal('');

    async submit(): Promise<void> {
        this.error.set('');
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const label = this.form.controls.label.value.trim();
        if (await this.store.apply({ op: 'snapshot', ...(label ? { label } : {}) })) this.form.reset();
        else this.error.set(this.store.error());
    }
}
