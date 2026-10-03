import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { timeZoneOptions } from '@inspecto/core/schema/time-zones';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { WINDOW_DAYS, crossesMidnight, windowForm, windowOf } from './investigation-rung-form';

/**
 * LA-SPA-OWED-SURFACES-1 — appends a `window` op (LA-13, `InvestigationTime.window`): an absolute `[from, to)`
 * range of ISO instants WITH an offset, an intraday slot `[start, end)` that may cross midnight, and a day mask.
 * The timezone contract is explicit: a slot or mask is wall-clock time and the server refuses it without an IANA
 * zone — there is no default, because the default would be the host's. "All time" sends `window: 'full'`.
 * The server's 422 is shown verbatim beside the form.
 */
@Component({
    selector: 'inspecto-la-window-op',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
    ],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2"
            style="border-color: var(--gamma-border)"
            aria-labelledby="la-window-op-title"
        >
            <h3 id="la-window-op-title" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Time window
            </h3>
            <form class="flex flex-col gap-2" [formGroup]="form" (ngSubmit)="submit()">
                <mat-checkbox formControlName="full">All time (clear the window)</mat-checkbox>
                @if (!form.controls.full.value) {
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>From</mat-label>
                        <input matInput formControlName="from" placeholder="2026-09-01T00:00:00Z" />
                        <mat-hint>An ISO instant with an offset or Z; the range is [from, to).</mat-hint>
                        @if (form.controls.from.hasError('pattern')) {
                            <mat-error>Use an ISO instant WITH an offset or Z, e.g. 2026-09-01T00:00:00Z.</mat-error>
                        }
                    </mat-form-field>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>To</mat-label>
                        <input matInput formControlName="to" placeholder="2026-10-01T00:00:00Z" />
                        @if (form.controls.to.hasError('pattern')) {
                            <mat-error>Use an ISO instant WITH an offset or Z, e.g. 2026-10-01T00:00:00Z.</mat-error>
                        }
                    </mat-form-field>
                    @if (form.hasError('inverted') && form.touched) {
                        <p class="text-warn m-0 text-xs" role="alert">'From' must be before 'To'.</p>
                    }
                    <div class="flex gap-2">
                        <mat-form-field class="w-full" subscriptSizing="dynamic">
                            <mat-label>Slot start</mat-label>
                            <input matInput formControlName="slotStart" placeholder="22:00" />
                            @if (form.controls.slotStart.hasError('pattern')) {
                                <mat-error>HH:mm, e.g. 22:00.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field class="w-full" subscriptSizing="dynamic">
                            <mat-label>Slot end</mat-label>
                            <input matInput formControlName="slotEnd" placeholder="06:00" />
                            @if (form.controls.slotEnd.hasError('pattern')) {
                                <mat-error>HH:mm, e.g. 06:00.</mat-error>
                            }
                        </mat-form-field>
                    </div>
                    @if (midnight()) {
                        <p class="text-secondary m-0 text-xs">
                            Crosses midnight: {{ form.controls.slotStart.value }} to
                            {{ form.controls.slotEnd.value }} the next day.
                        </p>
                    }
                    @if (form.hasError('slotHalf') && form.touched) {
                        <p class="text-warn m-0 text-xs" role="alert">A slot needs both a start and an end.</p>
                    }
                    @if (form.hasError('slotEqual') && form.touched) {
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
                    <inspecto-option-picker label="Timezone" formControlName="timezone" [options]="zones" />
                    <p class="text-secondary m-0 text-xs">
                        Slot and days are wall-clock time in this zone — required with either; there is no default.
                    </p>
                    @if (form.hasError('zone') && form.touched) {
                        <p class="text-warn m-0 text-xs" role="alert">A slot or day mask needs a timezone.</p>
                    }
                    @if (form.hasError('empty') && form.touched) {
                        <p class="text-warn m-0 text-xs" role="alert">
                            Set at least one of From, To, a slot or days — or choose All time.
                        </p>
                    }
                }
                <button mat-stroked-button type="submit" class="self-start" [disabled]="store.busy()">
                    Apply window
                </button>
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Window refused">{{ error() }}</inspecto-alert>
            }
        </section>
    `,
})
export class InvestigationWindowOpComponent {
    readonly store = inject(InvestigationSessionStore);
    readonly form = windowForm();
    readonly days = WINDOW_DAYS;
    readonly zones: PickerOption[] = timeZoneOptions('No timezone');
    readonly error = signal('');

    midnight(): boolean {
        return crossesMidnight(this.form.controls.slotStart.value, this.form.controls.slotEnd.value);
    }

    async submit(): Promise<void> {
        this.error.set('');
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        if (await this.store.apply({ op: 'window', window: windowOf(this.form) })) this.form.reset();
        else this.error.set(this.store.error());
    }
}
