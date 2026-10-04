import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
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
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';

/** `InvestigationRoutes.MAX_NOTE_LENGTH` — the server refuses a longer snapshot label. */
export const MAX_SNAPSHOT_LABEL = 2000;

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

export function thresholdForm() {
    return new FormGroup(
        {
            min: new FormControl<string | number | null>(null, [Validators.pattern(/^\d+$/)]),
            max: new FormControl<string | number | null>(null, [Validators.pattern(/^\d+$/), Validators.min(1)]),
        },
        { validators: bandOrder },
    );
}

/**
 * LA-INVESTIGATION-OPS-DEFERRED-1 — appends a `threshold` op: keep the entities whose degree (distinct
 * counterparties among the Working Set's links) falls in `[min, max)`; the rest are excluded exactly as `exclude`
 * does (a `keep` protects), once, with no cascade. The server's 422 is shown verbatim beside the form.
 */
@Component({
    selector: 'inspecto-la-threshold-op',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, InspectoAlertComponent],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2"
            style="border-color: var(--gamma-border)"
            aria-labelledby="la-threshold-op-title"
        >
            <h3 id="la-threshold-op-title" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Degree threshold
            </h3>
            <form class="flex flex-col gap-2" [formGroup]="form" (ngSubmit)="submit()">
                <div class="flex gap-2">
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Minimum degree</mat-label>
                        <input matInput inputmode="numeric" formControlName="min" placeholder="2" />
                        <mat-hint>Kept at or above this.</mat-hint>
                        @if (form.controls.min.hasError('pattern')) {
                            <mat-error>A whole number, 0 or more.</mat-error>
                        }
                    </mat-form-field>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Maximum degree</mat-label>
                        <input matInput inputmode="numeric" formControlName="max" placeholder="50" />
                        <mat-hint>Kept below this.</mat-hint>
                        @if (form.controls.max.hasError('pattern') || form.controls.max.hasError('min')) {
                            <mat-error>A whole number, 1 or more.</mat-error>
                        }
                    </mat-form-field>
                </div>
                @if (form.hasError('empty') && form.touched) {
                    <p class="text-warn m-0 text-xs" role="alert">Set a minimum, a maximum or both.</p>
                }
                @if (form.hasError('order') && form.touched) {
                    <p class="text-warn m-0 text-xs" role="alert">The minimum must be less than the maximum.</p>
                }
                <button mat-stroked-button type="submit" class="self-start" [disabled]="store.busy()">
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
    readonly form = thresholdForm();
    readonly error = signal('');

    async submit(): Promise<void> {
        this.error.set('');
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const { min, max } = this.form.getRawValue();
        const ok = await this.store.apply({
            op: 'threshold',
            ...(blank(min) ? {} : { min: Number(min) }),
            ...(blank(max) ? {} : { max: Number(max) }),
        });
        if (ok) this.form.reset();
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
    imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, InspectoAlertComponent],
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
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Label (optional)</mat-label>
                    <input matInput formControlName="label" placeholder="Before the weekend burst" />
                    <mat-hint>Marks this position in the log; the Working Set does not change.</mat-hint>
                    @if (form.controls.label.hasError('maxlength')) {
                        <mat-error>At most {{ max }} characters.</mat-error>
                    }
                </mat-form-field>
                <button mat-stroked-button type="submit" class="self-start" [disabled]="store.busy()">
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
    readonly max = MAX_SNAPSHOT_LABEL;
    readonly form = new FormGroup({
        label: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(MAX_SNAPSHOT_LABEL)] }),
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
