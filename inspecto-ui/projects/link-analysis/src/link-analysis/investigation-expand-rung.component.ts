import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import type { ExpandRung } from '@inspecto/link-analysis/api/inv.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { EXPAND_DIRECTIONS, MAX_EXPAND_BUDGET, rungForm, rungOf } from './investigation-rung-form';

/**
 * LA-SPA-OWED-SURFACES-1 — the expand form's **Advanced** section: the hop-ladder rung fields (plan §2.4) the
 * server accepts on an `expand` op. Collapsed by default; every field blank = the server default. The host's two
 * expand buttons read {@link rung} (null = invalid, the section is opened so the errors are on screen) and hand a
 * failed append's message back through {@link fail}, rendered verbatim here.
 */
@Component({
    selector: 'inspecto-la-expand-rung',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
    ],
    host: { class: 'block' },
    template: `
        <button
            mat-button
            type="button"
            [attr.aria-expanded]="open()"
            aria-controls="la-expand-rung-fields"
            (click)="open.set(!open())"
        >
            <mat-icon
                [svgIcon]="open() ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'"
            ></mat-icon>
            Advanced expand settings
        </button>
        <div id="la-expand-rung-fields" class="flex flex-col gap-2" [hidden]="!open()" [formGroup]="form">
            <p class="text-secondary m-0 text-xs">Blank = the server default. Applies to both expand buttons.</p>
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Row budget</mat-label>
                <input matInput type="number" formControlName="budget" placeholder="2000" />
                <mat-hint>Rows read; a breach marks the step incomplete. At most {{ maxBudget }}.</mat-hint>
                @if (form.controls.budget.invalid) {
                    <mat-error>A whole number from 1 to {{ maxBudget }}.</mat-error>
                }
            </mat-form-field>
            <inspecto-option-picker label="Direction" formControlName="direction" [options]="directionOptions" />
            <inspecto-option-picker label="Time window" formControlName="window" [options]="windowOptions" />
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Minimum events per link</mat-label>
                <input matInput type="number" formControlName="minEvents" placeholder="1" />
                @if (form.controls.minEvents.invalid) {
                    <mat-error>A whole number of at least 1.</mat-error>
                }
            </mat-form-field>
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Minimum distinct days</mat-label>
                <input matInput type="number" formControlName="minDistinctDays" />
                <mat-hint>Needs the Investigation's time column.</mat-hint>
                @if (form.controls.minDistinctDays.invalid) {
                    <mat-error>A whole number of at least 1.</mat-error>
                }
            </mat-form-field>
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Candidate degree — minimum</mat-label>
                <input matInput type="number" formControlName="candidateDegreeMin" />
                @if (form.controls.candidateDegreeMin.invalid) {
                    <mat-error>A whole number of at least 0.</mat-error>
                }
            </mat-form-field>
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Candidate degree — maximum</mat-label>
                <input matInput type="number" formControlName="candidateDegreeMax" />
                <mat-hint>Skips hubs: a neighbour with more links in the window is not admitted.</mat-hint>
                @if (form.controls.candidateDegreeMax.invalid) {
                    <mat-error>A whole number of at least 1.</mat-error>
                }
            </mat-form-field>
            @if (form.hasError('degreeOrder') && form.touched) {
                <p class="text-warn m-0 text-xs" role="alert">The minimum degree must not exceed the maximum.</p>
            }
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Maximum fan-out per entity</mat-label>
                <input matInput type="number" formControlName="maxFanOut" />
                <mat-hint>Strongest links first; blank = unbounded.</mat-hint>
                @if (form.controls.maxFanOut.invalid) {
                    <mat-error>A whole number of at least 1.</mat-error>
                }
            </mat-form-field>
        </div>
        @if (error()) {
            <inspecto-alert variant="error" title="Expand refused">{{ error() }}</inspecto-alert>
        }
    `,
})
export class InvestigationExpandRungComponent {
    readonly form = rungForm();
    readonly open = signal(false);
    readonly error = signal('');
    readonly maxBudget = MAX_EXPAND_BUDGET;
    readonly directionOptions: PickerOption[] = EXPAND_DIRECTIONS.map((d) => ({
        value: d,
        label: { either: 'Either way', out: 'Outgoing', in: 'Incoming', reciprocal: 'Reciprocal (both ways)' }[d],
    }));
    readonly windowOptions: PickerOption[] = [
        { value: 'inherit', label: "The Investigation's window" },
        { value: 'full', label: 'All time' },
    ];

    /** The rung to send, or null when a field is out of the server's bounds (the section opens on the errors). */
    rung(): ExpandRung | null {
        this.error.set('');
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            this.open.set(true);
            return null;
        }
        return rungOf(this.form);
    }

    /** The server's refusal, verbatim. */
    fail(message: string): void {
        this.error.set(message);
    }
}
