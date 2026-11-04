import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
    AbstractControl,
    FormControl,
    FormGroup,
    FormsModule,
    ReactiveFormsModule,
    ValidatorFn,
    Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { statusEdgeClasses, statusIconClasses, statusRowClasses } from 'app/inspecto/components/status-badge.component';
import { BAND_ICON, bandLegend, bandStatusTone, RECON_FILTER_LABELS, RECON_FILTERS, ReconFilter } from './recon-board';
import { DEFAULT_BANDS, ReconBands } from './reconciliation-types';

/** 0 <= ok <= warn <= 100 — the server's rule (a 422 otherwise), checked before the save is sent. */
const bandsOrdered: ValidatorFn = (g) => {
    const ok = g.get('okBelow')?.value as number | null;
    const warn = g.get('warnAbove')?.value as number | null;
    if (ok === null || warn === null) return null;
    return ok <= warn ? null : { order: true };
};

/**
 * The Reconciliation view toolbar (RECON-PERF-1, operator 2026-10-09): the day (limited to the days that hold data),
 * the Break filter, an optional key sample, the band legend with its thresholds, and — for an author only — the
 * band editor, which is the ONE thing here that is saved (`(bandsSave)`). Day, filter and sample are view state:
 * the host keeps them in its URL or its own signals and re-requests on change.
 */
@Component({
    selector: 'inspecto-recon-toolbar',
    standalone: true,
    imports: [
        FormsModule,
        ReactiveFormsModule,
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex flex-wrap items-end gap-x-3 gap-y-1" role="group" aria-label="Reconciliation view">
            <inspecto-option-picker
                class="block w-48"
                label="Day"
                [options]="dayOptions()"
                [ngModel]="day()"
                (ngModelChange)="pickDay($event)"
                [disabled]="!availableDays().length"
            />
            @if (showFilter()) {
                <inspecto-option-picker
                    class="block w-48"
                    label="Show"
                    [options]="filterOptions()"
                    [ngModel]="filter()"
                    (ngModelChange)="pickFilter($event)"
                />
            }
            @if (showSample()) {
                <inspecto-pen-field
                    class="block w-44"
                    label="Sample keys"
                    type="number"
                    [min]="1"
                    placeholder="All"
                    [formControl]="sampleControl"
                ></inspecto-pen-field>
            }

            <ul class="ml-auto flex flex-wrap items-center gap-2 text-xs" aria-label="Band legend">
                @for (l of legend(); track l.band) {
                    <li class="inline-flex items-center gap-1 rounded px-1.5 py-0.5 {{ l.rowClass }} {{ l.edgeClass }}">
                        <mat-icon
                            class="icon-size-4 {{ l.iconClass }}"
                            [svgIcon]="l.icon"
                            aria-hidden="true"
                        ></mat-icon>
                        <span>{{ l.label }} {{ l.range }}</span>
                    </li>
                }
            </ul>
            @if (canEditBands()) {
                <button
                    mat-icon-button
                    type="button"
                    (click)="editing.set(!editing())"
                    [attr.aria-expanded]="editing()"
                    matTooltip="Edit tolerance bands"
                    aria-label="Edit tolerance bands"
                >
                    <mat-icon svgIcon="heroicons_outline:adjustments-horizontal"></mat-icon>
                </button>
            }
        </div>
        @if (canEditBands() && editing()) {
            <form
                class="mt-2 flex flex-wrap items-start gap-3"
                [formGroup]="bandsForm"
                (ngSubmit)="saveBands()"
                aria-label="Tolerance bands"
            >
                <div class="grid min-w-0 flex-1 grid-cols-1 gap-x-8 sm:grid-cols-2">
                    <inspecto-pen-field
                        label="OK below (%)"
                        type="number"
                        [min]="0"
                        [max]="100"
                        [required]="true"
                        formControlName="okBelow"
                        [error]="percentError(bandsForm.controls.okBelow)"
                    ></inspecto-pen-field>
                    <inspecto-pen-field
                        label="Breach above (%)"
                        type="number"
                        [min]="0"
                        [max]="100"
                        [required]="true"
                        formControlName="warnAbove"
                        [error]="percentError(bandsForm.controls.warnAbove)"
                    ></inspecto-pen-field>
                </div>
                <button mat-flat-button color="primary" type="submit" [disabled]="saving()">Save bands</button>
                @if (bandsForm.touched && bandsForm.hasError('order')) {
                    <p class="text-warn w-full text-xs" role="alert">"OK below" must not be above "Breach above".</p>
                }
            </form>
        }
    `,
})
export class ReconToolbarComponent {
    readonly availableDays = input<string[]>([]);
    readonly day = input<string | null | undefined>(null);
    readonly filter = input<ReconFilter>('all');
    readonly sample = input<number | null | undefined>(null);
    readonly threeWay = input(false);
    /** The Breaks page shows only the day and the legend. */
    readonly showFilter = input(true);
    readonly showSample = input(true);
    readonly bands = input<ReconBands | undefined>(undefined);
    /** The author capability that already gates editing the Reconciliation. */
    readonly canEditBands = input(false);
    readonly saving = input(false);

    readonly dayChange = output<string>();
    readonly filterChange = output<ReconFilter>();
    readonly sampleChange = output<number | null>();
    readonly bandsSave = output<ReconBands>();

    readonly editing = signal(false);
    readonly filters = computed(() => RECON_FILTERS.filter((f) => f !== 'missing_c' || this.threeWay()));
    readonly dayOptions = computed<PickerOption[]>(() => this.availableDays().map((d) => ({ value: d, label: d })));
    readonly filterOptions = computed<PickerOption[]>(() =>
        this.filters().map((f) => ({ value: f, label: RECON_FILTER_LABELS[f] })),
    );

    readonly sampleControl = new FormControl<number | null>(null, [Validators.min(1)]);
    readonly bandsForm = new FormGroup(
        {
            okBelow: new FormControl<number | null>(null, [
                Validators.required,
                Validators.min(0),
                Validators.max(100),
            ]),
            warnAbove: new FormControl<number | null>(null, [
                Validators.required,
                Validators.min(0),
                Validators.max(100),
            ]),
        },
        { validators: bandsOrdered },
    );

    readonly legend = computed(() =>
        bandLegend(this.bands() ?? DEFAULT_BANDS).map((l) => {
            const tone = bandStatusTone(l.band);
            return {
                ...l,
                icon: BAND_ICON[l.band],
                rowClass: statusRowClasses(tone),
                edgeClass: statusEdgeClasses(tone),
                iconClass: statusIconClasses(tone),
            };
        }),
    );

    constructor() {
        effect(() => this.sampleControl.setValue(this.sample() ?? null, { emitEvent: false }));
        effect(() => {
            const b = this.bands() ?? DEFAULT_BANDS;
            this.bandsForm.setValue({ okBelow: b.warnPct, warnAbove: b.breachPct }, { emitEvent: false });
        });
        // The row commits on Enter or when it is left - the moments the old box applied the sample on.
        this.sampleControl.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.applySample());
    }

    /** The picker reports a re-pick of the shown day too; only a different day re-requests (as the select did). */
    pickDay(d: string): void {
        if (d !== this.day()) this.dayChange.emit(d);
    }

    pickFilter(f: string): void {
        if (f !== this.filter()) this.filterChange.emit(f as ReconFilter);
    }

    /** A band row's error line, once the row was left or Save pressed. */
    percentError(c: AbstractControl): string {
        return c.touched && c.invalid ? 'A percentage from 0 to 100.' : '';
    }

    applySample(): void {
        const v = this.sampleControl.value;
        const next = v && v >= 1 ? Math.floor(v) : null;
        if (next !== (this.sample() ?? null)) this.sampleChange.emit(next);
    }

    saveBands(): void {
        if (this.bandsForm.invalid) {
            this.bandsForm.markAllAsTouched();
            return;
        }
        const { okBelow, warnAbove } = this.bandsForm.getRawValue();
        this.bandsSave.emit({ warnPct: okBelow!, breachPct: warnAbove! });
        this.editing.set(false);
    }
}
