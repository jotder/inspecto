import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
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
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex flex-wrap items-end gap-x-3 gap-y-1" role="group" aria-label="Reconciliation view">
            <mat-form-field class="w-40" subscriptSizing="dynamic">
                <mat-label>Day</mat-label>
                <mat-select
                    [value]="day()"
                    (selectionChange)="dayChange.emit($event.value)"
                    [disabled]="!availableDays().length"
                >
                    @for (d of availableDays(); track d) {
                        <mat-option [value]="d">{{ d }}</mat-option>
                    }
                </mat-select>
            </mat-form-field>
            @if (showFilter()) {
                <mat-form-field class="w-40" subscriptSizing="dynamic">
                    <mat-label>Show</mat-label>
                    <mat-select [value]="filter()" (selectionChange)="filterChange.emit($event.value)">
                        @for (f of filters(); track f) {
                            <mat-option [value]="f">{{ filterLabels[f] }}</mat-option>
                        }
                    </mat-select>
                </mat-form-field>
            }
            @if (showSample()) {
                <mat-form-field class="w-36" subscriptSizing="dynamic">
                    <mat-label>Sample keys</mat-label>
                    <input
                        matInput
                        type="number"
                        min="1"
                        [formControl]="sampleControl"
                        placeholder="All"
                        (keydown.enter)="applySample()"
                        (blur)="applySample()"
                    />
                </mat-form-field>
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
                <mat-form-field class="w-40" subscriptSizing="dynamic">
                    <mat-label>OK below (%)</mat-label>
                    <input matInput type="number" min="0" max="100" formControlName="okBelow" />
                    @if (bandsForm.controls.okBelow.invalid) {
                        <mat-error>A percentage from 0 to 100.</mat-error>
                    }
                </mat-form-field>
                <mat-form-field class="w-40" subscriptSizing="dynamic">
                    <mat-label>Breach above (%)</mat-label>
                    <input matInput type="number" min="0" max="100" formControlName="warnAbove" />
                    @if (bandsForm.controls.warnAbove.invalid) {
                        <mat-error>A percentage from 0 to 100.</mat-error>
                    }
                </mat-form-field>
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

    readonly filterLabels = RECON_FILTER_LABELS;
    readonly editing = signal(false);
    readonly filters = computed(() => RECON_FILTERS.filter((f) => f !== 'missing_c' || this.threeWay()));

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
