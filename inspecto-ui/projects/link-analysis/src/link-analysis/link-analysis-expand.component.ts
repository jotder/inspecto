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
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { debounceTime, merge } from 'rxjs';
import type { ExpandRung } from '@inspecto/link-analysis/api/inv.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
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
import { GrowExcludeStore } from './grow-exclude.store';
import { FourEyesLimits, expandApproval } from './investigate-number';
import { rungForm, rungOf, windowForm } from './investigation-rung-form';
import { InvestigationWindowFieldsComponent } from './investigation-window-fields.component';
import { laLimit } from './la-limits';

/** What the Expand dialog hands the screen: the start it was run from, and the choice. */
export interface ExpandRun {
    ctx: GrowContext;
    req: GrowRequest;
}

/**
 * LA-STUDIO-UI-REORG-1 §5.4 (UIR-D5) — the **Expand** dialog: one form for every multi-degree expand. Basic fields
 * (From, Direction, Degrees 1…N, Hold supernodes), the read settings under Advanced, and a preview line of degree 1
 * before anything changes. It only ASKS: `(expand)` hands the choice to the screen, which walks it degree by degree
 * (recorded `expand` steps in an Investigation, neighbours reads on the explorer graph).
 */
@Component({
    selector: 'inspecto-link-analysis-expand',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatCheckboxModule,
        MatIconModule,
        MatTooltipModule,
        InspectoAlertComponent,
        ChipComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
        InvestigationWindowFieldsComponent,
    ],
    template: `
        <section
            class="flex max-h-[75vh] w-[28rem] max-w-[calc(100vw-2rem)] flex-col gap-3 overflow-y-auto rounded-lg border p-3 shadow-lg"
            style="background: var(--gamma-bg-card); border-color: var(--gamma-border)"
            role="dialog"
            aria-labelledby="la-expand-title"
            data-testid="la-expand-dialog"
            (keydown.escape)="closed.emit()"
        >
            <div class="flex items-center gap-2">
                <mat-icon svgIcon="heroicons_outline:arrows-pointing-out" aria-hidden="true"></mat-icon>
                <h2 id="la-expand-title" class="m-0 text-sm font-semibold">Expand</h2>
                <span class="text-secondary text-xs">({{ ctx().mode === 'investigation' ? 'recorded' : 'not recorded' }})</span>
                <button mat-icon-button class="ml-auto" type="button" aria-label="Close Expand" (click)="closed.emit()">
                    <mat-icon svgIcon="heroicons_outline:x-mark" aria-hidden="true"></mat-icon>
                </button>
            </div>

            @if (showHint()) {
                <div class="rounded-md border p-2 text-xs" style="border-color: var(--gamma-border)" data-testid="la-expand-hint">
                    <p class="m-0">Expand adds the {{ t().nodes }} linked to what is on the graph, one degree at a time.</p>
                    <p class="m-0">You see what degree 1 would add before anything changes.</p>
                    <p class="m-0">One Undo (Ctrl+Z) takes back every degree of one Expand.</p>
                    <p class="text-secondary m-0" data-testid="la-hint-guide">More in the user guide: <cite>{{ guide.chapter }}</cite> ({{ guide.where }}).</p>
                    <button mat-button type="button" class="mt-1" (click)="dismissHint()">Got it</button>
                </div>
            }

            <form [formGroup]="form" class="flex flex-col gap-3" (ngSubmit)="submit()">
                @if (fromOptions().length > 1) {
                    <inspecto-option-picker
                        label="From"
                        [options]="fromOptions()"
                        formControlName="from"
                        data-testid="la-expand-from"
                    ></inspecto-option-picker>
                }
                <p class="text-secondary m-0 text-xs" data-testid="la-expand-from-line">From {{ active().from }}</p>

                <div class="flex flex-col gap-1">
                    <span class="text-xs font-semibold" id="la-expand-direction">Direction</span>
                    <mat-button-toggle-group formControlName="direction" aria-labelledby="la-expand-direction" class="w-full">
                        @for (d of directions(); track d.value) {
                            <mat-button-toggle class="flex-1" [value]="d.value" [matTooltip]="d.hint">
                                <span class="inline-flex items-center gap-1 text-xs">
                                    <mat-icon class="icon-size-4" [svgIcon]="d.icon" aria-hidden="true"></mat-icon>
                                    {{ d.label }}
                                </span>
                            </mat-button-toggle>
                        }
                    </mat-button-toggle-group>
                </div>

                <div class="flex flex-col gap-1">
                    <span class="text-xs font-semibold" id="la-expand-degrees">Degrees</span>
                    <mat-button-toggle-group formControlName="degrees" aria-labelledby="la-expand-degrees" data-testid="la-expand-degrees">
                        @for (d of degreeChoices(); track d) {
                            <mat-button-toggle [value]="d" [matTooltip]="d === 1 ? 'One ring (one expand step)' : d + ' rings: ' + d + ' expand steps, undone together'">
                                <span class="text-xs">{{ d }}</span>
                            </mat-button-toggle>
                        }
                    </mat-button-toggle-group>
                    @if (formValue().degrees > 1) {
                        <p class="text-secondary m-0 text-xs">
                            Stops early if a degree would grow from more than {{ ctx().frontierCap.toLocaleString('en') }} {{ t().nodes }}.
                        </p>
                    }
                </div>

                <div class="flex flex-col gap-1">
                    <mat-checkbox
                        formControlName="hold"
                        [matTooltip]="'Busy ' + t().nodes + ' are supernodes (hub threshold): shown and sent to the Held tray, not expanded further'"
                        data-testid="la-expand-hold"
                    >
                        <span class="text-xs">Hold supernodes with more than {{ thresholdLabel() }} {{ t().links }}</span>
                    </mat-checkbox>
                    @if (form.controls.hold.value) {
                        <inspecto-pen-field
                            class="ml-8"
                            label="Hub threshold"
                            type="number"
                            [min]="1"
                            [default]="ctx().hubThreshold"
                            [placeholder]="ctx().hubThreshold.toLocaleString('en') + ' (the threshold in force)'"
                            formControlName="threshold"
                            [error]="thresholdError()"
                        ></inspecto-pen-field>
                    } @else {
                        <p class="text-secondary m-0 text-xs" data-testid="la-expand-no-hold">
                            Busy {{ t().nodes }} will be expanded through too, recorded as an override in the log.
                        </p>
                    }
                </div>

                <button
                    mat-button
                    type="button"
                    class="self-start"
                    [attr.aria-expanded]="moreOpen()"
                    aria-controls="la-expand-advanced"
                    data-testid="la-expand-advanced-toggle"
                    (click)="moreOpen.set(!moreOpen())"
                >
                    <mat-icon
                        [svgIcon]="moreOpen() ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'"
                        aria-hidden="true"
                    ></mat-icon>
                    Advanced
                </button>
                <div id="la-expand-advanced" class="flex flex-col gap-2 pl-2" [class.hidden]="!moreOpen()">
                    @if (ctx().linkKinds.length) {
                        <div class="flex flex-col gap-1" role="group" aria-labelledby="la-expand-kinds">
                            <span class="text-xs font-semibold" id="la-expand-kinds">{{ linkKindsLabel() }}</span>
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
                                placeholder="kind_a, kind_b"
                                [error]="rung.controls.linkKinds.invalid ? 'At most ' + maxKinds() + ' kinds.' : ''"
                            />
                        </ng-container>
                        <p class="text-secondary m-0 text-xs">Comma-separated; blank = all kinds. Needs the Investigation's link-kind column.</p>
                    } @else {
                        <p class="text-secondary m-0 text-xs">This graph has one kind of {{ t().link }}, so there is nothing to choose.</p>
                    }
                    @if (ctx().mode === 'investigation') {
                        <div class="flex flex-col gap-2" [formGroup]="rung" data-testid="la-expand-rung">
                            <p class="text-secondary m-0 text-xs">Blank = the pack's preset, else the server default.</p>
                            @if (ctx().hasWindow) {
                                <inspecto-option-picker label="Time window" formControlName="window" [options]="windowOptions" />
                                @if (rung.controls.window.value === 'override') {
                                    <div class="rounded-md border p-2" style="border-color: var(--gamma-border)">
                                        <p class="text-secondary m-0 mb-1 text-xs">This Expand only; the Investigation's own window is not changed.</p>
                                        <inspecto-la-window-fields [form]="override"></inspecto-la-window-fields>
                                    </div>
                                }
                            }
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
                            <p class="text-secondary m-0 text-xs">Needs the Investigation's time column.</p>
                            <inspecto-pen-field
                                label="Candidate degree, minimum"
                                formControlName="candidateDegreeMin"
                                type="number"
                                [error]="rung.controls.candidateDegreeMin.invalid ? 'A whole number of at least 0.' : ''"
                            />
                            <inspecto-pen-field
                                label="Candidate degree, maximum"
                                formControlName="candidateDegreeMax"
                                type="number"
                                [error]="rung.controls.candidateDegreeMax.invalid ? 'A whole number of at least 1.' : ''"
                            />
                            <p class="text-secondary m-0 text-xs">Skips hubs: a neighbour with more links in the window is not admitted.</p>
                            @if (rung.hasError('degreeOrder')) {
                                <p class="text-warn m-0 text-xs" role="alert">The minimum degree must not exceed the maximum.</p>
                            }
                            <inspecto-pen-field
                                label="Maximum fan-out per entity"
                                formControlName="maxFanOut"
                                type="number"
                                [error]="rung.controls.maxFanOut.invalid ? 'A whole number of at least 1.' : ''"
                            />
                            <p class="text-secondary m-0 text-xs">Strongest links first; blank = unbounded.</p>
                            <inspecto-pen-field
                                label="Row budget"
                                formControlName="budget"
                                type="number"
                                [placeholder]="(presets().budget ?? 2000).toString()"
                                [error]="rung.controls.budget.invalid ? 'A whole number from 1 to ' + maxBudget() + '.' : ''"
                            />
                            <p class="text-secondary m-0 text-xs">Rows read per degree; a breach marks the step incomplete.</p>
                        </div>
                    } @else {
                        <p class="text-secondary m-0 text-xs" data-testid="la-expand-basic-note">
                            Time window, minimum events, degree band, fan-out and row budget apply to an Investigation's recorded steps.
                        </p>
                    }
                </div>
            </form>

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
                    Each degree reads at most {{ a.budget.toLocaleString('en') }} rows, fan-out
                    {{ a.fanOut === null ? 'unbounded' : 'capped at ' + a.fanOut.toLocaleString('en') }}.
                </p>
                @if (a.exceeded.length) {
                    <p class="text-warn m-0 text-xs" role="status" data-testid="la-expand-needs-approval">
                        Each degree will need approval from a second person: {{ a.exceeded.join('; ') }}. The walk stops at
                        the first degree held for approval.
                    </p>
                }
            }

            @if (active().blocked; as why) {
                <inspecto-alert variant="info" data-testid="la-expand-blocked">{{ why }}</inspecto-alert>
            }

            <div class="flex items-center justify-end gap-2">
                <button mat-button type="button" (click)="closed.emit()">Cancel</button>
                <button
                    mat-flat-button
                    color="primary"
                    type="button"
                    data-testid="la-expand-go"
                    [disabled]="!!goWhyNot()"
                    [disabledInteractive]="true"
                    [matTooltip]="goWhyNot() || ''"
                    [attr.aria-describedby]="goWhyNot() ? 'la-expand-why' : null"
                    (click)="submit()"
                >
                    Expand{{ formValue().degrees > 1 ? ' ' + formValue().degrees + ' degrees' : '' }}
                </button>
            </div>
            @if (goWhyNot(); as why) {
                <p id="la-expand-why" class="text-secondary m-0 text-right text-xs">{{ why }}</p>
            }
        </section>
    `,
})
export class LinkAnalysisExpandComponent implements OnInit, OnChanges {
    readonly ctx = input.required<GrowContext>();
    /** The profile's expand presets (window, budget, caps) every degree is laid over. */
    readonly presets = input<DegreePresets>({});
    /** The Space's four-eyes thresholds; null = not known (no approval note). */
    readonly limits = input<FourEyesLimits | null>(null);
    readonly expand = output<ExpandRun>();
    readonly closed = output<void>();

    readonly store = inject(GrowExcludeStore);
    private destroyRef = inject(DestroyRef);
    private host: ElementRef<HTMLElement> = inject(ElementRef);

    readonly guide = GROW_GUIDE;
    readonly windowOptions: PickerOption[] = [
        { value: 'inherit', label: 'The Investigation’s window', hint: 'The window set for this Investigation' },
        { value: 'full', label: 'All available data', hint: 'Ignore the window for this Expand' },
        { value: 'override', label: 'A window for this Expand…' },
    ];
    readonly moreOpen = signal(false);
    readonly showHint = signal(!hintSeen('grow'));
    readonly t = computed(() => this.ctx().terms);
    readonly pv = this.store.preview;
    readonly maxKinds = computed(() => laLimit('input.link_kinds'));
    readonly maxBudget = computed(() => laLimit('investigation.expand_budget_max'));

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

    readonly directions = computed(() =>
        GROW_DIRECTIONS.filter((d) => d.value !== 'reciprocal' || this.ctx().mode === 'investigation'),
    );
    readonly degreeChoices = computed(() => Array.from({ length: Math.max(1, this.ctx().maxDegree) }, (_, i) => i + 1));
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
    readonly linkKindsLabel = computed(() => {
        const l = this.t().links;
        return l.charAt(0).toUpperCase() + l.slice(1) + ' to follow';
    });
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
                ? `Every ${t.node} at the start is a held supernode. Untick “Hold supernodes” to expand through them, or use Expand anyway in the Held tray.`
                : `There is nothing to expand from yet.`;
        }
        if (this.kinds()?.length === 0) return `Choose at least one kind of ${this.t().link} to follow.`;
        if (this.thresholdError()) return 'Fix the hub threshold first.';
        if (this.rungInvalid()) return 'Fix the Advanced fields first.';
        return null;
    });

    ngOnInit(): void {
        this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.formValue.set(this.form.getRawValue()));
        this.rung.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.rungValue.set(this.rung.getRawValue()));
        this.override.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.rungValue.set(this.rung.getRawValue()));
        // The preview covers degree 1, so "Degrees" alone never re-reads it.
        merge(this.form.valueChanges, this.rung.valueChanges, this.override.valueChanges)
            .pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef)).subscribe(() => {
                if (this.previewKey() !== this.previewedKey) this.refresh();
            });
        queueMicrotask(() => this.host.nativeElement.querySelector<HTMLElement>('mat-button-toggle button')?.focus());
    }

    private previewedKey = '';
    private previewKey(): string {
        const { degrees: _degrees, ...rest } = this.request();
        const a = this.active();
        return JSON.stringify([rest, a.frontier, a.heldFrontier, a.blocked, a.mode]);
    }

    ngOnChanges(): void {
        // the default "From": the first start that can be used
        if (!this.form.controls.from.value || !this.ctx().sources?.some((s) => s.value === this.form.controls.from.value)) {
            const first = this.ctx().sources?.find((s) => !s.blocked) ?? this.ctx().sources?.[0];
            this.form.controls.from.setValue(first?.value ?? null, { emitEvent: false });
        }
        const max = Math.max(1, this.ctx().maxDegree);
        if (this.form.controls.degrees.value > max) this.form.controls.degrees.setValue(max, { emitEvent: false });
        this.formValue.set(this.form.getRawValue());
        if (this.previewKey() !== this.previewedKey) this.refresh();
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
        if (this.rungInvalid()) {
            this.rung.markAllAsTouched();
            this.override.markAllAsTouched();
            this.moreOpen.set(true);
        }
        if (this.goWhyNot()) return;
        this.expand.emit({ ctx: this.active(), req: this.request() });
    }
}
