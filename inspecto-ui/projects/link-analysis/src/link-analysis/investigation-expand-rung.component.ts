import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import type { ExpandRung } from '@inspecto/link-analysis/api/inv.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import type { DegreePresets } from '@inspecto/link-analysis/graph/domain-profile';
import { FourEyesLimits, expandApproval } from './investigate-number';
import { InvestigationWindowFieldsComponent } from './investigation-window-fields.component';
import {
    EXPAND_DIRECTIONS,
    MAX_EXPAND_BUDGET,
    MAX_LINK_KINDS,
    rungForm,
    rungOf,
    windowForm,
} from './investigation-rung-form';

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
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        InvestigationWindowFieldsComponent,
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
            @if (form.controls.window.value === 'override') {
                <div class="rounded-md border p-2" style="border-color: var(--gamma-border)">
                    <p class="text-secondary m-0 mb-1 text-xs">
                        This rung only — the Investigation's own window is not changed.
                    </p>
                    <inspecto-la-window-fields [form]="override"></inspecto-la-window-fields>
                </div>
            }
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Link kinds</mat-label>
                <input matInput formControlName="linkKinds" placeholder="call, sms" />
                <mat-hint>Comma-separated; blank = all kinds. Needs the Investigation's link-kind column.</mat-hint>
                @if (form.controls.linkKinds.invalid) {
                    <mat-error>At most {{ maxKinds }} kinds.</mat-error>
                }
            </mat-form-field>
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
        <!-- Supernode suppression override: outside the collapsed section, so the opt-in is never hidden. -->
        <mat-checkbox [formControl]="includeHubs" data-testid="include-hubs">
            Include hubs — expand through high-connectivity entities
        </mat-checkbox>
        @if (includeHubs.value) {
            <p class="text-secondary m-0 text-xs" data-testid="include-hubs-note">
                An expand that reaches a flagged entity asks you to confirm first; the override is sealed in that step and
                shown in the log and the Dossier.
            </p>
        }
        @if (approval(); as a) {
            <p class="text-secondary m-0 text-xs" data-testid="expand-effective">
                This expand reads at most {{ a.budget.toLocaleString('en') }} rows, fan-out
                {{ a.fanOut === null ? 'unbounded' : 'capped at ' + a.fanOut.toLocaleString('en') }}.
            </p>
            @if (a.exceeded.length) {
                <p class="text-warn m-0 text-xs" role="status" data-testid="expand-needs-approval">
                    Will need approval from a second person: {{ a.exceeded.join('; ') }}. Lower it here, or change the
                    limit in Settings &gt; Link Analysis.
                </p>
            }
        }
        @if (error()) {
            <inspecto-alert variant="error" title="Expand refused">{{ error() }}</inspecto-alert>
        }
    `,
})
export class InvestigationExpandRungComponent {
    readonly form = rungForm();
    /** The profile's preset budget and fan-out cap: they fill whatever the form leaves blank, as the host sends them. */
    readonly presets = input<DegreePresets>({});
    /** The Space's four-eyes thresholds; null = not known yet (no estimate is shown). */
    readonly limits = input<FourEyesLimits | null>(null);
    private readonly raw = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
    /** What the next expand will read and whether the Space will hold it for a second approver. */
    readonly approval = computed(() => {
        const limits = this.limits();
        if (!limits) return null;
        const v = this.raw();
        const pick = (typed: unknown, preset: number | undefined): number | undefined => {
            const n = typed === null || typed === undefined || String(typed).trim() === '' ? NaN : Number(typed);
            return Number.isFinite(n) ? n : preset;
        };
        return expandApproval(
            { budget: pick(v.budget, this.presets().budget), maxFanOut: pick(v.maxFanOut, this.presets().maxFanOut) },
            limits,
        );
    });
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
        { value: 'override', label: 'A window for this rung…' },
    ];
    /** The rung's own window (`window: {from, to, slot, days, timezone}`), read only while the picker says so. */
    readonly override = windowForm();
    readonly maxKinds = MAX_LINK_KINDS;
    /** The analyst's opt-in to expand THROUGH flagged entities (`includeHubs: true`) — off by default, every time. */
    readonly includeHubs = new FormControl(false, { nonNullable: true });
    /** {@link includeHubs} as a signal, for the host's "Expand next degree" frontier. */
    readonly includeHubsOn = signal(false);

    constructor() {
        this.includeHubs.valueChanges.pipe(takeUntilDestroyed()).subscribe((v) => this.includeHubsOn.set(v));
    }

    /** The rung to send, or null when a field is out of the server's bounds (the section opens on the errors). */
    rung(): ExpandRung | null {
        this.error.set('');
        const overriding = this.form.controls.window.value === 'override';
        if (this.form.invalid || (overriding && this.override.invalid)) {
            this.form.markAllAsTouched();
            if (overriding) this.override.markAllAsTouched();
            this.open.set(true);
            return null;
        }
        const rung = rungOf(this.form, this.override);
        return this.includeHubs.value ? { ...rung, includeHubs: true } : rung;
    }

    /** The server's refusal, verbatim. */
    fail(message: string): void {
        this.error.set(message);
    }
}
