import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    ElementRef,
    OnChanges,
    OnInit,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
    untracked,
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
import type { PickerOption } from '@inspecto/core/components/option-picker.component';
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
import {
    FILTER_DEFAULTS,
    FILTER_GROUP_FIELDS,
    FilterGroupId,
    FilterValues,
    FilterWindow,
    QUICK_WINDOW_DAYS,
    changedGroups,
} from './investigation-filter';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
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
 * LA-STUDIO-UI-REORG-1 §5.4 (UIR-D5), reworked by LA-INVESTIGATE-UX-1 — the **Expand** form: one form for every
 * multi-degree expand. In order of importance: From (one press each), the Expand button with a preview line of degree 1,
 * then the **Expand filter** as foldable groups (Direction · Depth · Link kinds · Time · Strength · Fan-out and busy),
 * each saying what is set, marked when it differs from the default, with its own reset and one Reset to defaults. No
 * group opens a popup. The filter is published to the store, so every canvas expand uses it. It only ASKS: `(expand)`
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
        PenFieldComponent,
        StepperFieldComponent,
        LaFoldComponent,
        InvestigationWindowFieldsComponent,
    ],
    template: `
        <section
            class="flex min-h-0 flex-col gap-2"
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
                    <p class="m-0">
                        Adds linked items one degree at a time, with the filter below. Double-click an entity on the canvas, or
                        right-click it, to expand just that one the same way. One Undo (Ctrl+Z) reverses it.
                    </p>
                    <p class="text-secondary m-0" data-testid="la-hint-guide">More in the user guide: <cite>{{ guide.chapter }}</cite> ({{ guide.where }}).</p>
                    <button mat-button type="button" class="mt-1" (click)="dismissHint()">Got it</button>
                </div>
            }

            <!-- 1. From: one press, no popup (LA-INVESTIGATE-UX-1) -->
            @if (fromOptions().length > 1) {
                <div class="flex flex-wrap items-center gap-1" role="group" aria-label="Expand from" data-testid="la-expand-from">
                    <span class="text-secondary text-xs">From</span>
                    @for (o of fromOptions(); track o.value) {
                        <button
                            type="button"
                            class="la-toggle"
                            [attr.aria-pressed]="formValue().from === o.value"
                            [matTooltip]="o.hint ?? ''"
                            [attr.data-testid]="'la-expand-from-' + o.value"
                            (click)="setFrom(o.value)"
                        >
                            <inspecto-chip [variant]="formValue().from === o.value ? 'soft' : 'outline'" [tone]="formValue().from === o.value ? 'primary' : 'neutral'">{{
                                o.label
                            }}</inspecto-chip>
                        </button>
                    }
                </div>
            }
            <p class="text-secondary m-0 text-xs" data-testid="la-expand-from-line">From {{ active().from }}</p>

            <!-- 2. The Expand button with its preview line -->
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
            <!-- T-193: a blocked start already says why in the alert below; the reason is never shown twice -->
            @if (goWhyNot(); as why) {
                @if (!active().blocked) {
                    <p id="la-expand-why" class="text-secondary m-0 text-xs" [class.text-right]="!embedded()">{{ why }}</p>
                }
            }

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

            <!-- 3. The Expand filter: foldable groups, each saying what is set, a dot when changed, its own reset -->
            <div class="flex flex-col" data-testid="la-expand-filters">
                <div class="flex items-center gap-2 border-t pt-2" style="border-color: var(--gamma-border)">
                    <h3 class="m-0 whitespace-nowrap text-sm font-semibold">{{ embedded() ? 'Expand filter' : 'Filter' }}</h3>
                    @if (changed().length) {
                        <span class="text-secondary whitespace-nowrap text-xs" data-testid="la-expand-changed">{{ changed().length }} changed</span>
                        <button
                            type="button"
                            class="la-reset text-primary ml-auto whitespace-nowrap text-xs underline"
                            data-testid="la-expand-reset"
                            (click)="resetAll()"
                        >
                            Reset to defaults
                        </button>
                    } @else {
                        <span class="text-secondary text-xs">Defaults</span>
                    }
                </div>
                @if (embedded()) {
                    <p class="text-secondary m-0 mb-1 text-xs">Every expand uses it: this button, a double-click, the right-click menu and G.</p>
                }

                <la-fold title="Direction" [dense]="true" [startOpen]="true" rememberKey="expand.direction" [hint]="directionSummary()" [changed]="isChanged('direction')" data-testid="la-expand-fold-direction">
                    <div class="flex flex-wrap gap-1" role="group" aria-label="Direction" data-testid="la-expand-direction">
                        @for (d of directionOptions(); track d.value) {
                            <button
                                type="button"
                                class="la-toggle"
                                [attr.aria-pressed]="formValue().direction === d.value"
                                [matTooltip]="d.hint ?? ''"
                                [attr.data-testid]="'la-expand-direction-' + d.value"
                                (click)="setDirection(d.value)"
                            >
                                <inspecto-chip [variant]="formValue().direction === d.value ? 'soft' : 'outline'" [tone]="formValue().direction === d.value ? 'primary' : 'neutral'">{{
                                    d.label
                                }}</inspecto-chip>
                            </button>
                        }
                    </div>
                </la-fold>

                <la-fold title="Depth" [dense]="true" [startOpen]="true" rememberKey="expand.depth" [hint]="depthSummary()" [changed]="isChanged('depth')" data-testid="la-expand-fold-depth">
                    <form [formGroup]="form" (ngSubmit)="submit()">
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
                    </form>
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
                </la-fold>

                <la-fold title="Link kinds" [dense]="true" rememberKey="expand.kinds" [hint]="kindsSummary()" [changed]="isChanged('kinds')" [forceOpen]="tried() && kindsInvalid()" data-testid="la-expand-fold-links">
                    @if (isChanged('kinds')) {
                        <button type="button" class="la-reset text-primary self-start text-xs underline" (click)="resetGroup('kinds')">Reset link kinds</button>
                    }
                    @if (ctx().linkKinds.length) {
                        <div class="flex flex-wrap gap-1" role="group" aria-labelledby="la-expand-kinds">
                            <span class="sr-only" id="la-expand-kinds">{{ linkKindsLabel }}</span>
                            @for (k of ctx().linkKinds; track k) {
                                <button type="button" class="la-toggle" [attr.aria-pressed]="kindOn(k)" (click)="toggleKind(k, !kindOn(k))">
                                    <inspecto-chip [variant]="kindOn(k) ? 'soft' : 'outline'" [tone]="kindOn(k) ? 'primary' : 'neutral'">{{ k }}</inspecto-chip>
                                </button>
                            }
                        </div>
                        @if (kinds()?.length === 0) {
                            <p class="text-warn m-0 text-xs" role="alert">Choose at least one link kind to follow.</p>
                        }
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
                </la-fold>

                @if (ctx().mode === 'investigation' && ctx().hasWindow) {
                    <la-fold title="Time" [dense]="true" rememberKey="expand.time" [hint]="timeSummary()" [changed]="isChanged('time')" [forceOpen]="tried() && timeInvalid()" data-testid="la-expand-fold-time">
                        <div class="flex flex-wrap gap-1" role="group" aria-label="Time window" data-testid="la-expand-window">
                            @for (o of windowOptions; track o.value) {
                                <button
                                    type="button"
                                    class="la-toggle"
                                    [attr.aria-pressed]="rungValue().window === o.value"
                                    [matTooltip]="o.hint"
                                    [attr.data-testid]="'la-expand-window-' + o.value"
                                    (click)="setWindow(o.value)"
                                >
                                    <inspecto-chip [variant]="rungValue().window === o.value ? 'soft' : 'outline'" [tone]="rungValue().window === o.value ? 'primary' : 'neutral'">{{
                                        o.label
                                    }}</inspecto-chip>
                                </button>
                            }
                        </div>
                        @if (quickDays() && !windowEnd()) {
                            <p class="text-secondary m-0 text-xs" role="status" data-testid="la-expand-window-no-end">
                                {{ endLoading() ? 'Reading the latest event time…' : 'The latest event time is not known, so this Expand reads the Investigation window.' }}
                            </p>
                        } @else if (quickDays(); as days) {
                            <p class="text-secondary m-0 text-xs">The {{ days }} days up to the latest event ({{ windowEnd() }}). This Expand only.</p>
                        }
                        @if (rungValue().window === 'override') {
                            <div class="rounded-md border p-2" style="border-color: var(--gamma-border)">
                                <p class="text-secondary m-0 mb-1 text-xs">This Expand only; the Investigation's own window is not changed.</p>
                                <inspecto-la-window-fields [form]="override"></inspecto-la-window-fields>
                            </div>
                        }
                    </la-fold>
                }

                @if (ctx().mode === 'investigation') {
                    <la-fold title="Strength" [dense]="true" rememberKey="expand.strength" [hint]="strengthSummary()" [changed]="isChanged('strength')" [forceOpen]="tried() && strengthInvalid()" data-testid="la-expand-fold-strength">
                        @if (isChanged('strength')) {
                            <button type="button" class="la-reset text-primary self-start text-xs underline" (click)="resetGroup('strength')">Reset strength</button>
                        }
                        <div class="flex flex-col gap-2" [formGroup]="rung">
                            <inspecto-pen-field
                                label="Minimum events per link"
                                formControlName="minEvents"
                                type="number"
                                [placeholder]="presets().minEvents?.toString() ?? '1'"
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
                    </la-fold>
                }

                <la-fold title="Fan-out and busy" [dense]="true" rememberKey="expand.limits" [hint]="limitsSummary()" [changed]="isChanged('limits')" [forceOpen]="tried() && limitsInvalid()" data-testid="la-expand-fold-limits">
                    @if (isChanged('limits')) {
                        <button type="button" class="la-reset text-primary self-start text-xs underline" (click)="resetGroup('limits')">Reset fan-out and busy</button>
                    }
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
                                label="Most neighbours per entity"
                                formControlName="maxFanOut"
                                type="number"
                                [placeholder]="presets().maxFanOut?.toString() ?? ''"
                                [error]="rung.controls.maxFanOut.invalid ? 'A whole number of at least 1.' : ''"
                            />
                            <p class="text-secondary m-0 text-xs">Strongest first. Blank means no limit.</p>
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
                                label="Row limit per degree"
                                formControlName="budget"
                                type="number"
                                [placeholder]="(presets().budget ?? defaultBudget()).toString()"
                                [error]="rung.controls.budget.invalid ? 'A whole number from 1 to ' + maxBudget() + '.' : ''"
                            />
                            <p class="text-secondary m-0 text-xs">Stops at this many rows; the step is marked incomplete.</p>
                        </div>
                        @if (approval(); as a) {
                            <p class="text-secondary m-0 text-xs" data-testid="la-expand-effective">
                                Rows read per degree, at most {{ a.budget.toLocaleString('en-GB') }}. Neighbours per entity:
                                {{ a.fanOut === null ? 'no limit' : 'at most ' + a.fanOut.toLocaleString('en-GB') }}.
                            </p>
                        }
                    } @else {
                        <p class="text-secondary m-0 text-xs" data-testid="la-expand-basic-note">
                            More options appear when an Investigation is open.
                        </p>
                    }
                </la-fold>
            </div>
        </section>
    `,
    styles: `
        .la-toggle {
            cursor: pointer;
            border: 0;
            background: transparent;
            padding: 0;
            border-radius: 9999px;
        }
        .la-reset {
            cursor: pointer;
            border: 0;
            background: transparent;
            padding: 0;
        }
        .la-toggle:focus-visible,
        .la-reset:focus-visible {
            outline: 2px solid var(--gamma-primary);
            outline-offset: 2px;
        }
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
    /** Another Investigation opened while the form shows: its filter starts at the defaults (the store drops the old one). */
    readonly reopenWatch = (() => {
        let seen: string | null | undefined;
        effect(() => {
            const id = this.inv$?.activeId() ?? null;
            if (seen !== undefined && seen !== id) untracked(() => this.resetForOther());
            seen = id;
        });
    })();
    /** The open Investigation (absent in a bare test bed): the end the "last N days" chips count back from. */
    private readonly inv$ = inject(InvestigationSessionStore, { optional: true });
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
    readonly windowOptions: { value: FilterWindow; label: string; hint: string }[] = [
        { value: 'inherit', label: 'Investigation window', hint: 'The window set for this Investigation' },
        { value: 'full', label: 'All data', hint: 'Ignore the window for this Expand' },
        { value: 'last7', label: '7 days', hint: 'The 7 days up to the latest event, for this Expand only' },
        { value: 'last30', label: '30 days', hint: 'The 30 days up to the latest event, for this Expand only' },
        { value: 'last90', label: '90 days', hint: 'The 90 days up to the latest event, for this Expand only' },
        { value: 'override', label: 'Custom', hint: 'A window for this Expand only' },
    ];
    /** The latest event the quick chips count back from (the preset window end, else the Dataset's latest event). */
    readonly windowEnd = computed(() => this.inv$?.windowEnd() ?? this.inv$?.latestEvent() ?? null);
    readonly endLoading = signal(false);
    readonly quickDays = computed(() => QUICK_WINDOW_DAYS[this.rungValue().window] ?? null);
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
    readonly kinds = signal<string[] | null>(null);
    readonly formValue = signal(this.form.getRawValue());
    readonly rungValue = signal(this.rung.getRawValue());

    /** Direction as one-press toggles (Both ways / Outgoing / Incoming, and Both ways, mutual in an Investigation). */
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
    readonly kindsInvalid = computed(() => this.kinds()?.length === 0 || this.bad('linkKinds'));
    readonly strengthInvalid = computed(() => this.bad('minEvents', 'minDistinctDays'));
    readonly linksInvalid = computed(() => this.kindsInvalid() || this.strengthInvalid());
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
    /** "All link kinds" / "2 of 5 link kinds" / the kinds typed. */
    readonly kindsSummary = computed(() => {
        const v = this.rungValue();
        const all = this.ctx().linkKinds;
        const on = this.kinds();
        const typed = this.inv() && !all.length ? parseKinds(String(v.linkKinds ?? '')) : [];
        return all.length && on !== null ? `${on.length} of ${all.length} link kinds` : typed.length ? typed.join(', ') : 'All link kinds';
    });
    /** "At least 3 events · on 2+ days", or what the pack sets. */
    readonly strengthSummary = computed(() => {
        const v = this.rungValue();
        const ev = whole(v.minEvents) ?? this.presets().minEvents ?? null;
        const days = whole(v.minDistinctDays);
        const parts = [ev !== null && ev > 1 ? `At least ${ev} events` : 'Any link'];
        if (days !== null) parts.push(`on ${days}+ days`);
        return parts.join(' · ');
    });
    readonly directionSummary = computed(() => GROW_DIRECTIONS.find((d) => d.value === this.formValue().direction)?.label ?? '');
    readonly depthSummary = computed(() => {
        const d = this.formValue().degrees;
        return `${d} ${d === 1 ? 'degree' : 'degrees'}`;
    });

    // ── what differs from the defaults: a dot per group, "N changed", and the resets ──

    readonly filterValues = computed<FilterValues>(() => {
        const f = this.formValue();
        const r = this.rungValue();
        return {
            direction: f.direction,
            degrees: f.degrees,
            kinds: this.kinds(),
            typedKinds: r.linkKinds,
            window: r.window,
            minEvents: r.minEvents,
            minDistinctDays: r.minDistinctDays,
            hold: f.hold,
            threshold: f.threshold,
            candidateDegreeMin: r.candidateDegreeMin,
            candidateDegreeMax: r.candidateDegreeMax,
            maxFanOut: r.maxFanOut,
            budget: r.budget,
        };
    });
    /** Depth follows the last Expand while that is the newest step, so it counts as changed only outside it. */
    readonly changed = computed(() =>
        changedGroups(this.filterValues()).filter((g) => g !== 'depth' || this.lastExpand() === null),
    );
    isChanged(g: FilterGroupId): boolean {
        return this.changed().includes(g);
    }

    /** Put one group back on its defaults (the pack presets apply again). */
    resetGroup(g: FilterGroupId): void {
        const fields: readonly string[] = FILTER_GROUP_FIELDS[g];
        const defaults = (keys: (keyof FilterValues)[]) =>
            Object.fromEntries(keys.filter((k) => fields.includes(k)).map((k) => [k, FILTER_DEFAULTS[k]]));
        this.form.patchValue(defaults(['direction', 'degrees', 'hold', 'threshold']));
        this.rung.patchValue({
            ...defaults(['window', 'minEvents', 'minDistinctDays', 'candidateDegreeMin', 'candidateDegreeMax', 'maxFanOut', 'budget']),
            ...(fields.includes('typedKinds') ? { linkKinds: '' } : {}),
        });
        if (fields.includes('window')) this.override.reset();
        if (fields.includes('kinds')) this.kinds.set(null);
        this.publish();
        this.refresh();
    }

    /** Set once the remembered filter is restored: a publish before that would overwrite it with the defaults. */
    private ready = false;

    private resetForOther(): void {
        for (const g of Object.keys(FILTER_GROUP_FIELDS) as FilterGroupId[]) if (g !== 'depth') this.resetGroup(g);
    }

    resetAll(): void {
        for (const g of this.changed()) this.resetGroup(g);
    }

    setFrom(value: unknown): void {
        this.form.controls.from.setValue(value as ExpandFrom);
    }

    setDirection(value: unknown): void {
        this.form.controls.direction.setValue(value as GrowDirection);
    }

    /** A time chip; a "last N days" chip reads the Dataset's latest event once when no end is known yet. */
    async setWindow(value: FilterWindow): Promise<void> {
        this.rung.controls.window.setValue(value);
        if (QUICK_WINDOW_DAYS[value] === undefined || this.windowEnd() || !this.inv$) return;
        this.endLoading.set(true);
        try {
            await this.inv$.loadLatestEvent();
        } finally {
            this.endLoading.set(false);
        }
        this.publish();
        this.refresh();
    }

    /**
     * Hand the filter to the store: what the canvas expands with, and what the tab shows again after it was closed. Only
     * the Investigation tab's form is the filter; the explorer's popup keeps its own choice.
     */
    private publish(): void {
        if (!this.embedded() || !this.ready) return;
        this.store.filterRequest.set(this.request());
        this.store.filterSnapshot.set({
            form: this.form.getRawValue(),
            rung: this.rung.getRawValue(),
            override: this.override.getRawValue(),
            kinds: this.kinds(),
        } satisfies FilterSnapshot);
    }

    /** Take the filter back from the store (the tab was closed and is shown again); From follows the selection. */
    private restore(): void {
        const snap = this.store.filterSnapshot() as FilterSnapshot | null;
        if (!snap || !this.embedded()) return;
        const { from: _from, ...form } = snap.form;
        this.form.patchValue(form, { emitEvent: false });
        this.rung.patchValue(snap.rung, { emitEvent: false });
        this.override.patchValue(snap.override, { emitEvent: false });
        this.kinds.set(snap.kinds);
        this.formValue.set(this.form.getRawValue());
        this.rungValue.set(this.rung.getRawValue());
        this.syncDegrees(); // Degrees follows the last Expand over what was remembered
    }

    readonly timeSummary = computed(() => {
        const w = this.rungValue().window;
        const label = this.windowOptions.find((o) => o.value === w)?.label ?? '';
        return QUICK_WINDOW_DAYS[w] !== undefined ? `Last ${label}` : label;
    });
    /** "Busy over 50 links · 20 per entity · 5,000 rows". */
    readonly limitsSummary = computed(() => {
        const f = this.formValue();
        const parts = [f.hold ? `Busy over ${this.thresholdLabel()} links` : 'Busy expanded through'];
        if (this.inv()) {
            const v = this.rungValue();
            const fan = whole(v.maxFanOut) ?? this.presets().maxFanOut ?? null;
            if (fan !== null) parts.push(`${fan.toLocaleString('en-GB')} per entity`);
            const lo = whole(v.candidateDegreeMin);
            const hi = whole(v.candidateDegreeMax);
            if (lo !== null || hi !== null) parts.push(`neighbours with ${lo ?? 0}–${hi ?? 'any'} links`);
            const budget = whole(v.budget);
            if (budget !== null) parts.push(`${budget.toLocaleString('en-GB')} rows`);
        }
        return parts.join(' · ');
    });

    /** Bring the form into view and put focus on its first control (every Expand entry point lands here). */
    focus(): void {
        const el = this.host.nativeElement;
        el.scrollIntoView?.({ block: 'start', behavior: 'smooth' });
        el.querySelector<HTMLElement>('[data-testid="la-expand-go"]')?.focus();
    }

    ngOnInit(): void {
        this.restore();
        this.ready = true;
        merge(this.form.valueChanges, this.rung.valueChanges, this.override.valueChanges)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.publish());
        this.publish();
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
        this.publish();
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
        this.publish(); // a double-click walks the Degrees the tab shows
    }

    /** The Advanced read settings as the server takes them; `{}` on the explorer graph. */
    private advancedRung(): ExpandRung {
        if (this.ctx().mode !== 'investigation') return {};
        const { direction: _d, ...r } = rungOf(this.rung, this.override, this.windowEnd());
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
        this.publish();
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

/** The form's raw values as the store keeps them between showings of the tab. */
interface FilterSnapshot {
    form: ReturnType<LinkAnalysisExpandComponent['form']['getRawValue']>;
    rung: ReturnType<LinkAnalysisExpandComponent['rung']['getRawValue']>;
    override: ReturnType<LinkAnalysisExpandComponent['override']['getRawValue']>;
    kinds: string[] | null;
}

/** A typed whole number, or null for a blank or non-number field (the fold summaries say only what is set). */
function whole(v: unknown): number | null {
    if (v === null || v === undefined || String(v).trim() === '') return null;
    const n = Number(v);
    return Number.isInteger(n) ? n : null;
}
