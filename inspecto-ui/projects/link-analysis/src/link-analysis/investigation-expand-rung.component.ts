import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import type { ExpandRung } from '@inspecto/link-analysis/api/inv.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import type { DegreePresets } from '@inspecto/link-analysis/graph/domain-profile';
import { FourEyesLimits, expandApproval } from './investigate-number';
import { InvestigationWindowFieldsComponent } from './investigation-window-fields.component';
import { laLimit } from './la-limits';
import {
    EXPAND_DIRECTIONS,
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
        PenFieldComponent,
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatIconModule,
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
            <inspecto-pen-field
                label="Row budget"
                formControlName="budget"
                type="number"
                placeholder="2000"
                [error]="form.controls.budget.invalid ? 'A whole number from 1 to ' + (maxBudget()) + '.' : ''"
            />
            <p class="text-secondary m-0 text-xs">Rows read; a breach marks the step incomplete. At most {{ maxBudget() }}.</p>
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
            <inspecto-pen-field
                label="Link kinds"
                formControlName="linkKinds"
                placeholder="call, sms"
                [error]="form.controls.linkKinds.invalid ? 'At most ' + (maxKinds()) + ' kinds.' : ''"
            />
            <p class="text-secondary m-0 text-xs">Comma-separated; blank = all kinds. Needs the Investigation's link-kind column.</p>
            <inspecto-pen-field
                label="Minimum events per link"
                formControlName="minEvents"
                type="number"
                placeholder="1"
                [error]="form.controls.minEvents.invalid ? 'A whole number of at least 1.' : ''"
            />
            <inspecto-pen-field
                label="Minimum distinct days"
                formControlName="minDistinctDays"
                type="number"
                [error]="form.controls.minDistinctDays.invalid ? 'A whole number of at least 1.' : ''"
            />
            <p class="text-secondary m-0 text-xs">Needs the Investigation's time column.</p>
            <inspecto-pen-field
                label="Candidate degree — minimum"
                formControlName="candidateDegreeMin"
                type="number"
                [error]="form.controls.candidateDegreeMin.invalid ? 'A whole number of at least 0.' : ''"
            />
            <inspecto-pen-field
                label="Candidate degree — maximum"
                formControlName="candidateDegreeMax"
                type="number"
                [error]="form.controls.candidateDegreeMax.invalid ? 'A whole number of at least 1.' : ''"
            />
            <p class="text-secondary m-0 text-xs">Skips hubs: a neighbour with more links in the window is not admitted.</p>
            @if (form.hasError('degreeOrder') && form.touched) {
                <p class="text-warn m-0 text-xs" role="alert">The minimum degree must not exceed the maximum.</p>
            }
            <inspecto-pen-field
                label="Maximum fan-out per entity"
                formControlName="maxFanOut"
                type="number"
                [error]="form.controls.maxFanOut.invalid ? 'A whole number of at least 1.' : ''"
            />
            <p class="text-secondary m-0 text-xs">Strongest links first; blank = unbounded.</p>
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
    readonly maxBudget = computed(() => laLimit('investigation.expand_budget_max'));
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
    readonly maxKinds = computed(() => laLimit('input.link_kinds'));
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
