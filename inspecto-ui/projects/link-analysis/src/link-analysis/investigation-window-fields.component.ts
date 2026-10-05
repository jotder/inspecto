import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { timeZoneOptions } from '@inspecto/core/schema/time-zones';
import { WINDOW_DAYS, WindowForm, crossesMidnight } from './investigation-rung-form';

/**
 * LA-SPA-OWED-SURFACES-1 — the fields of one time window (LA-13, `InvestigationTime.window`): an ISO `[from, to)`
 * range WITH an offset, an intraday slot that may cross midnight, a day mask and the IANA zone a slot or mask
 * needs. Shared by the `window` op form and the expand rung's window override, so both are the same object the
 * server validates. The host owns the {@link windowForm} and its submit; the cross-field errors surface here once
 * the form is touched.
 */
@Component({
    selector: 'inspecto-la-window-fields',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoOptionPickerComponent,
    ],
    host: { class: 'block' },
    template: `
        <div class="flex flex-col gap-2" [formGroup]="form()">
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>From</mat-label>
                <input matInput formControlName="from" placeholder="2026-09-01T00:00:00Z" />
                <mat-hint>An ISO instant with an offset or Z; the range is [from, to).</mat-hint>
                @if (form().controls.from.hasError('pattern')) {
                    <mat-error>Use an ISO instant WITH an offset or Z, e.g. 2026-09-01T00:00:00Z.</mat-error>
                }
            </mat-form-field>
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>To</mat-label>
                <input matInput formControlName="to" placeholder="2026-10-01T00:00:00Z" />
                @if (form().controls.to.hasError('pattern')) {
                    <mat-error>Use an ISO instant WITH an offset or Z, e.g. 2026-10-01T00:00:00Z.</mat-error>
                }
            </mat-form-field>
            @if (form().hasError('inverted') && form().touched) {
                <p class="text-warn m-0 text-xs" role="alert">'From' must be before 'To'.</p>
            }
            <div class="flex gap-2">
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Slot start</mat-label>
                    <input matInput formControlName="slotStart" placeholder="22:00" />
                    @if (form().controls.slotStart.hasError('pattern')) {
                        <mat-error>HH:mm, e.g. 22:00.</mat-error>
                    }
                </mat-form-field>
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Slot end</mat-label>
                    <input matInput formControlName="slotEnd" placeholder="06:00" />
                    @if (form().controls.slotEnd.hasError('pattern')) {
                        <mat-error>HH:mm, e.g. 06:00.</mat-error>
                    }
                </mat-form-field>
            </div>
            @if (midnight()) {
                <p class="text-secondary m-0 text-xs">
                    Crosses midnight: {{ form().controls.slotStart.value }} to {{ form().controls.slotEnd.value }} the
                    next day.
                </p>
            }
            @if (form().hasError('slotHalf') && form().touched) {
                <p class="text-warn m-0 text-xs" role="alert">A slot needs both a start and an end.</p>
            }
            @if (form().hasError('slotEqual') && form().touched) {
                <p class="text-warn m-0 text-xs" role="alert">
                    The slot's start equals its end — use a range or no slot.
                </p>
            }
            <fieldset class="m-0 flex flex-wrap gap-x-2 border-0 p-0" formGroupName="days">
                <legend class="text-secondary mb-1 text-xs">Days (blank = every day)</legend>
                @for (d of days; track d) {
                    <mat-checkbox [formControlName]="d">{{ d }}</mat-checkbox>
                }
            </fieldset>
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Excluded dates (holidays)</mat-label>
                <textarea
                    matInput
                    rows="2"
                    formControlName="exclude"
                    data-test="window-exclude"
                    placeholder="2026-12-25, Winter break=2026-12-24..2026-12-26"
                ></textarea>
                <mat-hint>Comma or line separated: DATE or FROM..TO (inclusive), optional "Name=" first.</mat-hint>
            </mat-form-field>
            @if (form().hasError('exclude') && form().touched) {
                <p class="text-warn m-0 text-xs" role="alert">
                    Excluded dates are YYYY-MM-DD or YYYY-MM-DD..YYYY-MM-DD (the end not before the start).
                </p>
            }
            <inspecto-option-picker label="Timezone" formControlName="timezone" [options]="zones" />
            <p class="text-secondary m-0 text-xs">
                Slot, days and excluded dates are wall-clock time in this zone — required with either; there is no default.
            </p>
            @if (form().hasError('zone') && form().touched) {
                <p class="text-warn m-0 text-xs" role="alert">A slot, day mask or excluded date needs a timezone.</p>
            }
            @if (form().hasError('empty') && form().touched) {
                <p class="text-warn m-0 text-xs" role="alert">Set at least one of From, To, a slot, days or excluded dates.</p>
            }
        </div>
    `,
})
export class InvestigationWindowFieldsComponent {
    readonly form = input.required<WindowForm>();
    readonly days = WINDOW_DAYS;
    readonly zones: PickerOption[] = timeZoneOptions('No timezone');

    midnight(): boolean {
        const f = this.form().controls;
        return crossesMidnight(f.slotStart.value, f.slotEnd.value);
    }
}
