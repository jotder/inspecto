import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    ElementRef,
    OnChanges,
    OnInit,
    computed,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, debounceTime, merge } from 'rxjs';
import type { ExpandRung } from '@inspecto/link-analysis/api/inv.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { StepperFieldComponent } from '@inspecto/core/components/stepper-field.component';
import type { DegreePresets } from '@inspecto/link-analysis/graph/domain-profile';
import {
    ExpandFrom,
    GROW_DIRECTIONS,
    GROW_GUIDE,
    GrowContext,
    GrowDirection,
    GrowRequest,
    growIds,
    hintSeen,
    markHintSeen,
    previewSentence,
} from './grow-exclude';
import { DISABLED_REASON, mostDegrees } from './disabled-reasons';
import { GrowExcludeStore } from './grow-exclude.store';
import { FourEyesLimits, expandApproval } from './investigate-number';
import { parseKinds, rungForm, rungOf, windowForm } from './investigation-rung-form';
import { InvestigationWindowFieldsComponent } from './investigation-window-fields.component';
import { LaFoldComponent } from './la-fold.component';
import { laLimit } from './la-limits';

/** What the Expand dialog hands the screen: the start it was run from, and the choice. */
export interface ExpandRun {
    ctx: GrowContext;
    req: GrowRequest;
    /** The "From" chosen (an unsaved Investigation records an outer-ring Expand without naming ids). */
    from: ExpandFrom | null;
}

/** LA-DEGREES-TRIM-1: the stepper, bound to the last Expand, was moved to `degrees` (with the form's request as it stands). */
export interface ExpandDegreesChange {
    degrees: number;
    req: GrowRequest;
}

/**
 * LA-STUDIO-UI-REORG-1 §5.4 (UIR-D5) — the **Expand** form: one form for every multi-degree expand. In order of
 * importance: From, Degrees (a − N + stepper), Direction (a popup choice), the Expand button with a preview line of
 * degree 1, then the filters in closed folds (Links · Time · Limits) that each say what is set. It only ASKS: `(expand)`
 * hands the choice to the screen, which walks it degree by degree (recorded `expand` steps in an Investigation,
 * neighbours reads on the explorer graph).
 *
 * `embedded` (LA-INV-EXPAND-1): for an open Investigation the form is a section of the Investigation tab, with no dialog
 * chrome; the explorer graph (nothing recorded) keeps it as a popup over the canvas.
 */
@Component({
    selector: 'inspecto-link-analysis-expand',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    // Escape closes the popup (the tab section has nothing to close)
    host: { '(keydown.escape)': 'embedded() || closed.emit()' },
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatIconModule,
        MatTooltipModule,
        InspectoAlertComponent,
        ChipComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
        StepperFieldComponent,
        LaFoldComponent,
        InvestigationWindowFieldsComponent,
    ],
    template: `
        <section
            class="flex min-h-0 flex-col gap-3"
            [class]="embedded() ? '' : 'max-h-[75vh] w-[28rem] max-w-[calc(100vw-2rem)] overflow-y-auto rounded-lg border p-3 shadow-lg bg-card'"
            [style.border-color]="embedded() ? null : 'var(--gamma-border)'"
            [attr.role]="embedded() ? null : 'dialog'"
            [attr.aria-label]="embedded() ? 'Expand' : null"
            [attr.aria-labelledby]="embedded() ? null : 'la-expand-title'"
            [attr.data-testid]="embedded() ? 'la-expand-section' : 'la-expand-dialog'"
        >
            @if (!embedded()) {
                <div class="flex items-center gap-2">
                    <mat-icon svgIcon="heroicons_outline:arrows-pointing-out" aria-hidden="true"></mat-icon>
                    <h2 id="la-expand-title" class="m-0 text-sm font-semibold">Expand</h2>
                    <span class="text-secondary text-xs">({{ ctx().mode === 'investigation' ? 'recorded' : 'not recorded' }})</span>
                    <button mat-icon-button class="ml-auto" type="button" aria-label="Close Expand" (click)="closed.emit()">
                        <mat-icon svgIcon="heroicons_outline:x-mark" aria-hidden="true"></mat-icon>
                    </button>
                </div>
            }

            @if (showHint()) {
                <div class="rounded-md border p-2 text-xs" style="border-color: var(--gamma-border)" data-testid="la-expand-hint">
                    <p class="m-0">Adds linked items one degree at a time. You see a preview first; one Undo (Ctrl+Z) reverses it.</p>
                    <p class="text-secondary m-0" data-testid="la-hint-guide">More in the user guide: <cite>{{ guide.chapter }}</cite> ({{ guide.where }}).</p>
                    <button mat-button type="button" class="mt-1" (click)="dismissHint()">Got it</button>
                </div>
            }

            <form [formGroup]="form" class="flex flex-col gap-1" (ngSubmit)="submit()">
                <!-- 1. From: the start entities -->
                @if (fromOptions().length > 1) {
                    <inspecto-option-picker
                        label="From"
                        [options]="fromOptions()"
                        formControlName="from"
                        data-testid="la-expand-from"
                    ></inspecto-option-picker>
                }
                <p class="text-secondary m-0 text-xs" data-testid="la-expand-from-line">From {{ active().from }}</p>

                <!-- 2. Degrees: − N + -->
                <inspecto-stepper-field
                    label="Degrees"
                    formControlName="degrees"
                    decrementLabel="Fewer degrees"
                    incrementLabel="More degrees"
                    [min]="1"
                    [max]="maxDegree()"
                    [minReason]="fewestDegrees"
                    [maxReason]="mostDegreesReason()"
                    data-testid="la-expand-degrees"
                ></inspecto-stepper-field>
                @if (lastExpand() !== null) {
                    <p class="text-secondary m-0 text-xs" data-testid="la-expand-degrees-bound">
                        Degrees follows the last Expand: fewer removes its outermost degrees from the Working Set, more expands further.
                    </p>
                }
                @if (formValue().degrees > 1) {
                    <p class="text-secondary m-0 text-xs">
                        Stops early if a degree would expand from more than {{ ctx().frontierCap.toLocaleString('en-GB') }} {{ t().nodes }}.
                    </p>
                }

                <!-- 3. Direction: a popup choice, like From -->
                <inspecto-option-picker
                    label="Direction"
                    formControlName="direction"
                    [options]="directionOptions()"
                    data-testid="la-expand-direction"
                ></inspecto-option-picker>
            </form>

            <!-- 4. The Expand button with its preview line -->
            <div role="status" aria-live="polite" class="text-xs" data-testid="la-expand-preview">
                @switch (pv().state) {
                    @case ('loading') {
                        <span class="text-secondary">Checking what degree 1 would add…</span>
                    }
                    @case ('ready') {
                        <p class="m-0">{{ sentence() }}</p>
                        @if (pv().preview?.excludedByLists?.length) {
                            <div class="mt-1 flex flex-wrap gap-1" aria-label="Exclusion lists in force" role="list" data-testid="la-expand-lists">
                                @for (l of pv().preview!.excludedByLists; track l.listId) {
                                    <span role="listitem">
                                        <inspecto-chip variant="soft" [matTooltip]="'Exclusion list: removes ' + l.removed + ' from this step'"
                                            >{{ l.title }} · {{ l.removed.toLocaleString('en') }}</inspecto-chip
                                        >
                                    </span>
                                }
                            </div>
                        }
                    }
                    @case ('unavailable') {
                        <span class="text-secondary" data-testid="la-expand-no-preview"
                            >No preview on this server. The result is shown after it runs, with Undo.</span
                        >
                    }
                    @case ('error') {
                        <inspecto-alert variant="warning" title="The preview was refused">{{ pv().message }}</inspecto-alert>
                    }
                }
            </div>

            @if (approval(); as a) {
                <p class="text-secondary m-0 text-xs" data-testid="la-expand-effective">
                    Rows read per degree, at most {{ a.budget.toLocaleString('en-GB') }}. Neighbours per entity:
                    {{ a.fanOut === null ? 'no limit' : 'at most ' + a.fanOut.toLocaleString('en-GB') }}.
                </p>
                @if (a.exceeded.length) {
                    <p class="text-warn m-0 text-xs" role="status" data-testid="la-expand-needs-approval">
                        Each degree will need approval from a second person: {{ a.exceeded.join('; ') }}. The walk stops at
                        the first degree held for approval.
                    </p>
                }
            }

            @if (active().blocked; as why) {
                <inspecto-alert id="la-expand-blocked-why" variant="info" data-testid="la-expand-blocked">{{ why }}</inspecto-alert>
            }

            <div class="flex flex-wrap items-center gap-2" [class.justify-end]="!embedded()">
                @if (!embedded()) {
                    <button mat-button type="button" (click)="closed.emit()">Cancel</button>
                }
                <button
                    mat-flat-button
                    color="primary"
                    type="button"
                    data-testid="la-expand-go"
                    [disabled]="!!goWhyNot()"
                    [disabledInteractive]="true"
                    [matTooltip]="goWhyNot() || ''"
                    [attr.aria-describedby]="goWhyNot() ? (active().blocked ? 'la-expand-blocked-why' : 'la-expand-why') : null"
                    (click)="submit()"
                >
                    Expand{{ formValue().degrees > 1 ? ' ' + formValue().degrees + ' degrees' : '' }}
                </button>
            </div>
            <!-- T-193: a blocked start already says why in the alert above; the reason is never shown twice -->
            @if (goWhyNot(); as why) {
                @if (!active().blocked) {
                    <p id="la-expand-why" class="text-secondary m-0 text-xs" [class.text-right]="!embedded()">{{ why }}</p>
                }
            }

            <!-- 5. The filters: foldable groups, closed, each saying what is set -->
            <div class="flex flex-col gap-2" data-testid="la-expand-filters">
                <la-fold title="Links" [hint]="linksSummary()" [forceOpen]="tried() && linksInvalid()" data-testid="la-expand-fold-links">
                    @if (ctx().linkKinds.length) {
                        <div class="flex flex-col gap-1" role="group" aria-labelledby="la-expand-kinds">
                            <span class="text-xs font-semibold" id="la-expand-kinds">{{ linkKindsLabel }}</span>
                            @for (k of ctx().linkKinds; track k) {
                                <mat-checkbox [checked]="kindOn(k)" (change)="toggleKind(k, $event.checked)">
                                    <span class="text-xs">{{ k }}</span>
                                </mat-checkbox>
                            }
                        </div>
                    } @else if (ctx().mode === 'investigation') {
                        <ng-container [formGroup]="rung">
                            <inspecto-pen-field
                                label="Link kinds"
                                formControlName="linkKinds"
                                placeholder="e.g. call, transfer"
                                [error]="rung.controls.linkKinds.invalid ? 'At most ' + maxKinds() + ' kinds.' : ''"
                            />
                        </ng-container>
                        <p class="text-secondary m-0 text-xs">Comma-separated; blank means all kinds. Needs the Investigation's link kind column.</p>
                    } @else {
                        <p class="text-secondary m-0 text-xs">This graph has one kind of {{ t().link }}, so there is nothing to choose.</p>
                    }
                    @if (ctx().mode === 'investigation') {
                        <div class="flex flex-col gap-2" [formGroup]="rung">
                            <inspecto-pen-field
                                label="Minimum events per link"
                                formControlName="minEvents"
                                type="number"
                                placeholder="1"
                                [error]="rung.controls.minEvents.invalid ? 'A whole number of at least 1.' : ''"
                            />
                            <inspecto-pen-field
                                label="Minimum distinct days"
                                formControlName="minDistinctDays"
                                type="number"
                                [error]="rung.controls.minDistinctDays.invalid ? 'A whole number of at least 1.' : ''"
                            />
                            <p class="text-secondary m-0 text-xs">Distinct days need the Investigation's time column. A blank field uses the Industry pack's setting.</p>
                        </div>
                    }
                </la-fold>

                @if (ctx().mode === 'investigation' && ctx().hasWindow) {
                    <la-fold title="Time" [hint]="timeSummary()" [forceOpen]="tried() && timeInvalid()" data-testid="la-expand-fold-time">
                        <div class="flex flex-col gap-2" [formGroup]="rung">
                            <inspecto-option-picker label="Time window" formControlName="window" [options]="windowOptions" />
                            @if (rung.controls.window.value === 'override') {
                                <div class="rounded-md border p-2" style="border-color: var(--gamma-border)">
                                    <p class="text-secondary m-0 mb-1 text-xs">This Expand only; the Investigation's own window is not changed.</p>
                                    <inspecto-la-window-fields [form]="override"></inspecto-la-window-fields>
                                </div>
                            }
                        </div>
                    </la-fold>
                }

                <la-fold title="Limits" [hint]="limitsSummary()" [forceOpen]="tried() && limitsInvalid()" data-testid="la-expand-fold-limits">
                    <div class="flex flex-col gap-1" [formGroup]="form">
                        <mat-checkbox
                            formControlName="hold"
                            [matTooltip]="'Busy ' + t().nodes + ' stay on the graph and go to the Held tray; they are not expanded'"
                            data-testid="la-expand-hold"
                        >
                            <span class="text-xs">Hold busy entities (more than {{ thresholdLabel() }} linked)</span>
                        </mat-checkbox>
                        @if (form.controls.hold.value) {
                            <inspecto-pen-field
                                class="ml-8"
                                label="Busy limit"
                                type="number"
                                [min]="1"
                                [default]="ctx().hubThreshold"
                                [placeholder]="ctx().hubThreshold.toLocaleString('en-GB') + ' (the limit in force)'"
                                formControlName="threshold"
                                [error]="thresholdError()"
                            ></inspecto-pen-field>
                        } @else {
                            <p class="text-secondary m-0 text-xs" data-testid="la-expand-no-hold">
                                Busy {{ t().nodes }} will be expanded through too, recorded as an override in the step log.
                            </p>
                        }
                    </div>
                    @if (ctx().mode === 'investigation') {
                        <div class="flex flex-col gap-2" [formGroup]="rung" data-testid="la-expand-rung">
                            <inspecto-pen-field
                                label="Fewest links a neighbour may have"
                                formControlName="candidateDegreeMin"
                                type="number"
                                [error]="rung.controls.candidateDegreeMin.invalid ? 'A whole number of at least 0.' : ''"
                            />
                            <inspecto-pen-field
                                label="Most links a neighbour may have"
                                formControlName="candidateDegreeMax"
                                type="number"
                                [error]="rung.controls.candidateDegreeMax.invalid ? 'A whole number of at least 1.' : ''"
                            />
                            @if (rung.hasError('degreeOrder')) {
                                <p class="text-warn m-0 text-xs" role="alert">The minimum degree must not exceed the maximum.</p>
                            }
                            <inspecto-pen-field
                                label="Most neighbours per entity"
                                formControlName="maxFanOut"
                                type="number"
                                [error]="rung.controls.maxFanOut.invalid ? 'A whole number of at least 1.' : ''"
                            />
                            <p class="text-secondary m-0 text-xs">Strongest first. Blank means no limit.</p>
                            <inspecto-pen-field
                                label="Row limit per degree"
                                formControlName="budget"
                                type="number"
                                [placeholder]="(presets().budget ?? defaultBudget()).toString()"
                                [error]="rung.controls.budget.invalid ? 'A whole number from 1 to ' + maxBudget() + '.' : ''"
                            />
                            <p class="text-secondary m-0 text-xs">Stops at this many rows; the step is marked incomplete.</p>
                        </div>
                    } @else {
                        <p class="text-secondary m-0 text-xs" data-testid="la-expand-basic-note">
                            More options appear when an Investigation is open.
                        </p>
                    }
                </la-fold>
            </div>
        </section>
    `,
})
export class LinkAnalysisExpandComponent implements OnInit, OnChanges {
    readonly ctx = input.required<GrowContext>();
    /** The profile's expand presets (window, budget, caps) every degree is laid over. */
    readonly presets = input<DegreePresets>({});
    /** The Space's four-eyes thresholds; null = not known (no approval note). */
    readonly limits = input<FourEyesLimits | null>(null);
    /** A section of the Investigation tab (no dialog chrome, no Cancel, no focus grab) rather than the canvas popup. */
    readonly embedded = input(false);
    /** The tab section is on screen (the toolbox is open on the Investigation tab): previews are read only then. */
    readonly onScreen = input(true);
    /**
     * LA-DEGREES-TRIM-1: the Degrees of the last Expand while the newest step IS an Expand (null otherwise). The stepper then
     * shows it and a change is handed to the screen (`degreesChange`) rather than waiting for Expand.
     */
    readonly lastExpand = input<number | null>(null);
    readonly expand = output<ExpandRun>();
    readonly degreesChange = output<ExpandDegreesChange>();
    readonly closed = output<void>();

    readonly store = inject(GrowExcludeStore);
    private destroyRef = inject(DestroyRef);
    /**
     * The tab section outlives selections and steps, so a change of start re-reads the preview once things settle
     * (300 ms), not on every selection click; the popup reads at once, as it opens.
     */
    private readonly startChanged = new Subject<void>();
    private readonly debouncedPreview = this.startChanged
        .pipe(debounceTime(300), takeUntilDestroyed())
        .subscribe(() => {
            if (this.previewKey() !== this.previewedKey) this.refresh();
        });
    private host: ElementRef<HTMLElement> = inject(ElementRef);

    readonly guide = GROW_GUIDE;
    readonly windowOptions: PickerOption[] = [
        { value: 'inherit', label: 'Investigation window', hint: 'The window set for this Investigation' },
        { value: 'full', label: 'All available data', hint: 'Ignore the window for this Expand' },
        { value: 'override', label: 'Custom window', hint: 'A window for this Expand only' },
    ];
    /** An Expand was asked for with a filter in error: the fold holding it opens (an error behind a closed fold is invisible). */
    readonly tried = signal(false);
    readonly showHint = signal(!hintSeen('grow'));
    readonly t = computed(() => this.ctx().terms);
    readonly pv = this.store.preview;
    readonly maxKinds = computed(() => laLimit('input.link_kinds'));
    readonly maxBudget = computed(() => laLimit('investigation.expand_budget_max'));
    /** The server's row limit for a degree that states none: the field's placeholder and the summary agree (T-162). */
    readonly defaultBudget = computed(() => laLimit('investigation.expand_budget_default'));

    readonly form = new FormGroup({
        from: new FormControl<ExpandFrom | null>(null),
        direction: new FormControl<GrowDirection>('either', { nonNullable: true }),
        degrees: new FormControl(1, { nonNullable: true }),
        hold: new FormControl(true, { nonNullable: true }),
        threshold: new FormControl<number | null>(null),
    });
    /** The Advanced read settings (Investigation only); its `direction` field is unused — Direction is basic. */
    readonly rung = rungForm();
    /** The Advanced "A window for this Expand…" fields. */
    readonly override = windowForm();
    /** null = every link kind. */
    private readonly kinds = signal<string[] | null>(null);
    readonly formValue = signal(this.form.getRawValue());
    private readonly rungValue = signal(this.rung.getRawValue());

    /** Direction as a popup choice (Both ways / Outgoing / Incoming, and Both ways, mutual in an Investigation). */
    readonly directionOptions = computed<PickerOption[]>(() =>
        GROW_DIRECTIONS.filter((d) => d.value !== 'reciprocal' || this.ctx().mode === 'investigation').map((d) => ({
            value: d.value,
            label: d.label,
            hint: d.hint,
        })),
    );
    /** The Degrees stepper's top: the Industry pack's maximum degree. */
    readonly maxDegree = computed(() => Math.max(1, this.ctx().maxDegree));
    readonly fewestDegrees = DISABLED_REASON.fewestDegrees;
    readonly mostDegreesReason = computed(() => mostDegrees(this.maxDegree()));
    readonly fromOptions = computed<PickerOption[]>(() =>
        (this.ctx().sources ?? []).map((s) => ({ value: s.value, label: s.label, hint: s.blocked ?? s.from })),
    );
    /** The context of the chosen "From": its frontier and blocked reason over the host's defaults. */
    readonly active = computed<GrowContext>(() => {
        const ctx = this.ctx();
        const pick = this.formValue().from;
        const s = ctx.sources?.find((x) => x.value === pick);
        if (!s) return ctx;
        return {
            ...ctx,
            frontier: s.frontier,
            heldFrontier: s.heldFrontier,
            from: s.from,
            blocked: ctx.blocked ?? s.blocked,
        };
    });
    readonly sentence = computed(() => {
        const p = this.pv().preview;
        return p ? previewSentence(p, this.t(), this.formValue().degrees) : '';
    });
    readonly thresholdLabel = computed(() => (this.formValue().threshold ?? this.ctx().hubThreshold).toLocaleString('en'));
    readonly thresholdError = computed(() => {
        const v = this.formValue().threshold;
        return v != null && !(Number.isInteger(v) && v >= 1) ? 'A whole number of at least 1.' : '';
    });
    /** The canonical word on every surface (T-164, G6): the legend says Link kinds too. */
    readonly linkKindsLabel = 'Link kinds to follow';
    /** What each degree will read and whether the Space holds it for a second approver (Investigation only). */
    readonly approval = computed(() => {
        const limits = this.limits();
        if (!limits || this.ctx().mode !== 'investigation') return null;
        const v = this.rungValue();
        const pick = (typed: unknown, preset: number | undefined): number | undefined => {
            const n = typed === null || typed === undefined || String(typed).trim() === '' ? NaN : Number(typed);
            return Number.isFinite(n) ? n : preset;
        };
        return expandApproval(
            { budget: pick(v.budget, this.presets().budget), maxFanOut: pick(v.maxFanOut, this.presets().maxFanOut) },
            limits,
        );
    });
    private readonly rungInvalid = computed(() => {
        this.rungValue();
        return (
            this.ctx().mode === 'investigation' &&
            (this.rung.invalid || (this.rung.controls.window.value === 'override' && this.override.invalid))
        );
    });
    /** Why the primary action is not possible now — said beside the button, never a silent grey. */
    readonly goWhyNot = computed(() => {
        const ctx = this.active();
        if (ctx.blocked) return ctx.blocked;
        if (this.store.busy()) return 'Another change is still running.';
        if (!growIds(ctx, { hold: this.formValue().hold }).length) {
            const t = this.t();
            return ctx.heldFrontier.length
                ? `${
                      ctx.heldFrontier.length === 1
                          ? `The ${t.node} to expand from is held.`
                          : `All ${ctx.heldFrontier.length.toLocaleString('en-GB')} ${t.nodes} to expand from are held.`
                  } Untick “Hold busy entities” or use Expand anyway.`
                : `There is nothing to expand from yet.`;
        }
        if (this.kinds()?.length === 0) return 'Choose at least one link kind to follow.';
        if (this.thresholdError()) return 'Fix the busy limit first.';
        if (this.rungInvalid()) return 'Fix the filters first.';
        return null;
    });

    // ── the filter folds: what each one has set, and whether it holds an error ──

    private readonly inv = computed(() => this.ctx().mode === 'investigation');
    private bad(...names: (keyof typeof this.rung.controls)[]): boolean {
        this.rungValue();
        return this.inv() && names.some((n) => this.rung.controls[n].invalid);
    }
    readonly linksInvalid = computed(() => this.kinds()?.length === 0 || this.bad('linkKinds', 'minEvents', 'minDistinctDays'));
    readonly timeInvalid = computed(() => {
        this.rungValue();
        return this.inv() && this.rung.controls.window.value === 'override' && this.override.invalid;
    });
    readonly limitsInvalid = computed(
        () =>
            !!this.thresholdError() ||
            this.bad('candidateDegreeMin', 'candidateDegreeMax', 'maxFanOut', 'budget') ||
            (this.inv() && this.rung.hasError('degreeOrder')),
    );
    /** "All link kinds · at least 3 events · on 2+ days". */
    readonly linksSummary = computed(() => {
        const v = this.rungValue();
        const all = this.ctx().linkKinds;
        const on = this.kinds();
        const typed = this.inv() && !all.length ? parseKinds(String(v.linkKinds ?? '')) : [];
        const parts = [
            all.length && on !== null
                ? `${on.length} of ${all.length} link kinds`
                : typed.length
                  ? typed.join(', ')
                  : 'All link kinds',
        ];
        if (this.inv()) {
            const ev = whole(v.minEvents);
            const days = whole(v.minDistinctDays);
            if (ev !== null) parts.push(`at least ${ev} events`);
            if (days !== null) parts.push(`on ${days}+ days`);
        }
        return parts.join(' · ');
    });
    readonly timeSummary = computed(() => {
        const w = this.rungValue().window;
        return this.windowOptions.find((o) => o.value === w)?.label ?? '';
    });
    /** "Busy over 50 links · 20 per entity · 5,000 rows". */
    readonly limitsSummary = computed(() => {
        const f = this.formValue();
        const parts = [f.hold ? `Busy over ${this.thresholdLabel()} links` : 'Busy expanded through'];
        if (this.inv()) {
            const v = this.rungValue();
            const lo = whole(v.candidateDegreeMin);
            const hi = whole(v.candidateDegreeMax);
            if (lo !== null || hi !== null) parts.push(`neighbours with ${lo ?? 0}–${hi ?? 'any'} links`);
            const fan = whole(v.maxFanOut);
            if (fan !== null) parts.push(`${fan.toLocaleString('en-GB')} per entity`);
            const budget = whole(v.budget);
            if (budget !== null) parts.push(`${budget.toLocaleString('en-GB')} rows`);
        }
        return parts.join(' · ');
    });

    /** Bring the form into view and put focus on its first control (every Expand entry point lands here). */
    focus(): void {
        const el = this.host.nativeElement;
        el.scrollIntoView?.({ block: 'start', behavior: 'smooth' });
        el.querySelector<HTMLElement>('[data-testid="la-expand-from"] button, [role="spinbutton"]')?.focus();
    }

    ngOnInit(): void {
        this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.formValue.set(this.form.getRawValue()));
        this.rung.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.rungValue.set(this.rung.getRawValue()));
        this.override.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.rungValue.set(this.rung.getRawValue()));
        // The preview covers degree 1, so "Degrees" alone never re-reads it.
        merge(this.form.valueChanges, this.rung.valueChanges, this.override.valueChanges)
            .pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef)).subscribe(() => {
                if (this.previewKey() !== this.previewedKey) this.refresh();
            });
        // the stepper settled (+/- clicks batch) on a size other than the last Expand's: the screen resizes that Expand
        this.form.controls.degrees.valueChanges.pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef)).subscribe((d) => {
            const last = this.lastExpand();
            if (last !== null && d !== last) this.degreesChange.emit({ degrees: d, req: this.request() });
        });
        // the popup takes focus when it opens; the tab section is focused by the screen, only when Expand is asked for
        if (!this.embedded()) queueMicrotask(() => this.focus());
    }

    private previewedKey = '';
    private sourceKeys: string | null = null;
    private previewKey(): string {
        const { degrees: _degrees, ...rest } = this.request();
        const a = this.active();
        return JSON.stringify([rest, a.frontier, a.heldFrontier, a.blocked, a.mode]);
    }

    ngOnChanges(): void {
        // the default "From": the first start that can be used. The tab section outlives a selection, so a new set of
        // starts (a selection made or cleared) picks the default again - a right-click on a node starts from it.
        const keys = (this.ctx().sources ?? []).map((s) => s.value).join();
        if (keys !== this.sourceKeys || !this.ctx().sources?.some((s) => s.value === this.form.controls.from.value)) {
            const first = this.ctx().sources?.find((s) => !s.blocked) ?? this.ctx().sources?.[0];
            this.form.controls.from.setValue(first?.value ?? null, { emitEvent: false });
        }
        this.sourceKeys = keys;
        const max = Math.max(1, this.ctx().maxDegree);
        if (this.form.controls.degrees.value > max) this.form.controls.degrees.setValue(max, { emitEvent: false });
        if (this.lastExpand() !== this.seenLastExpand) {
            this.seenLastExpand = this.lastExpand();
            this.syncDegrees();
        }
        this.formValue.set(this.form.getRawValue());
        if (this.embedded()) this.startChanged.next();
        else if (this.previewKey() !== this.previewedKey) this.refresh();
    }

    private seenLastExpand: number | null = null;

    /** Put the stepper back on the last Expand's Degrees (it moved, or the screen refused to resize it). */
    syncDegrees(): void {
        const last = this.lastExpand();
        if (last === null) return;
        this.form.controls.degrees.setValue(Math.min(last, Math.max(1, this.ctx().maxDegree)), { emitEvent: false });
        this.formValue.set(this.form.getRawValue());
    }

    /** The Advanced read settings as the server takes them; `{}` on the explorer graph. */
    private advancedRung(): ExpandRung {
        if (this.ctx().mode !== 'investigation') return {};
        const { direction: _d, ...r } = rungOf(this.rung, this.override);
        return r;
    }

    /** The choice as the screen acts on it. */
    request(): GrowRequest {
        const v = this.form.getRawValue();
        const rung = this.advancedRung();
        return {
            direction: v.direction,
            degrees: Math.min(Math.max(1, v.degrees), Math.max(1, this.ctx().maxDegree)),
            linkKinds: this.kinds(),
            hold: v.hold,
            threshold: v.threshold ?? this.ctx().hubThreshold,
            ...(Object.keys(rung).length ? { rung } : {}),
        };
    }

    /** Re-read the preview for the current choice (it covers degree 1). */
    refresh(): void {
        // the tab section reads nothing while it is off screen, or while an Expand walks (the next change after does)
        if (this.embedded() && (!this.onScreen() || this.store.busy())) return;
        this.previewedKey = this.previewKey();
        if (this.thresholdError() || this.kinds()?.length === 0 || this.rungInvalid()) {
            this.store.resetPreview();
            return;
        }
        void this.store.runPreview(this.active(), { ...this.request(), degrees: 1 }, this.presets());
    }

    kindOn(k: string): boolean {
        const on = this.kinds();
        return on === null || on.includes(k);
    }

    toggleKind(k: string, on: boolean): void {
        const all = this.ctx().linkKinds;
        const cur = this.kinds() ?? all;
        const next = on ? [...new Set([...cur, k])] : cur.filter((x) => x !== k);
        this.kinds.set(next.length === all.length ? null : next);
        this.refresh();
    }

    dismissHint(): void {
        markHintSeen('grow');
        this.showHint.set(false);
    }

    submit(): void {
        if (this.rungInvalid() || this.linksInvalid() || this.limitsInvalid()) {
            this.rung.markAllAsTouched();
            this.override.markAllAsTouched();
            this.tried.set(true);
        }
        if (this.goWhyNot()) return;
        this.expand.emit({ ctx: this.active(), req: this.request(), from: this.formValue().from });
    }
}

/** A typed whole number, or null for a blank or non-number field (the fold summaries say only what is set). */
function whole(v: unknown): number | null {
    if (v === null || v === undefined || String(v).trim() === '') return null;
    const n = Number(v);
    return Number.isInteger(n) ? n : null;
}
