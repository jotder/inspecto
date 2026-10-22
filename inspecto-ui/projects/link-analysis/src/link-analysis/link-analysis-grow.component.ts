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
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { debounceTime } from 'rxjs';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import type { DegreePresets } from '@inspecto/link-analysis/graph/domain-profile';
import {
    GROW_DIRECTIONS,
    GROW_GUIDE,
    GrowContext,
    GrowDirection,
    GrowRequest,
    growIds,
    hintSeen,
    markHintSeen,
    previewSentence,
    secondStepWhyNot,
} from './grow-exclude';
import { GrowExcludeStore } from './grow-exclude.store';

/**
 * LA-GROW-EXCLUDE-1 §3.1 — the **Grow** panel: one primary action with sensible defaults (Both ways, 1 step, busy items
 * held), the rest under "More options", and a dry-run preview before anything changes. It only ASKS: `(grow)` hands the
 * choice to the screen, which records it as one `expand` (Investigation) or grows the explorer graph (Basic).
 */
@Component({
    selector: 'inspecto-link-analysis-grow',
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
    ],
    template: `
        <section
            class="flex max-h-[70vh] w-[22rem] max-w-[calc(100vw-2rem)] flex-col gap-3 overflow-y-auto rounded-lg border p-3 shadow-lg"
            style="background: var(--gamma-bg-card); border-color: var(--gamma-border)"
            role="dialog"
            aria-labelledby="la-grow-title"
            data-testid="la-grow-panel"
            (keydown.escape)="closed.emit()"
        >
            <div class="flex items-center gap-2">
                <mat-icon svgIcon="heroicons_outline:arrows-pointing-out" aria-hidden="true"></mat-icon>
                <h2
                    id="la-grow-title"
                    class="m-0 text-sm font-semibold"
                    [matTooltip]="'Grow is an expand: one step that adds the next ring of linked ' + t().nodes"
                >
                    Grow
                </h2>
                <span class="text-secondary text-xs">({{ ctx().mode === 'investigation' ? 'recorded' : 'not recorded' }})</span>
                <button mat-icon-button class="ml-auto" type="button" aria-label="Close Grow" (click)="closed.emit()">
                    <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                </button>
            </div>

            @if (showHint()) {
                <div class="rounded-md border p-2 text-xs" style="border-color: var(--gamma-border)" data-testid="la-grow-hint">
                    <p class="m-0">Grow adds the {{ t().nodes }} linked to what is on the graph — one ring at a time.</p>
                    <p class="m-0">You see what a step would add before anything changes.</p>
                    <p class="m-0">Every Grow can be undone (Undo, or Ctrl+Z).</p>
                    <p class="text-secondary m-0" data-testid="la-hint-guide">More in the user guide: <cite>{{ guide.chapter }}</cite> ({{ guide.where }}).</p>
                    <button mat-button type="button" class="mt-1" (click)="dismissHint()">Got it</button>
                </div>
            }

            <p class="text-secondary m-0 text-xs" data-testid="la-grow-from">From {{ ctx().from }}</p>

            <form [formGroup]="form" class="flex flex-col gap-3" (ngSubmit)="submit()">
                <div class="flex flex-col gap-1">
                    <span class="text-xs font-semibold" id="la-grow-direction">Direction</span>
                    <mat-button-toggle-group formControlName="direction" aria-labelledby="la-grow-direction" class="w-full">
                        @for (d of directions; track d.value) {
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
                    <span class="text-xs font-semibold" id="la-grow-steps">How far</span>
                    <mat-button-toggle-group formControlName="steps" aria-labelledby="la-grow-steps">
                        <mat-button-toggle [value]="1" [matTooltip]="'One ring of linked ' + t().nodes + ' (one expand step)'">
                            <span class="text-xs">1 step</span>
                        </mat-button-toggle>
                        <mat-button-toggle
                            [value]="2"
                            [disabled]="!!stepsWhyNot()"
                            [disabledInteractive]="true"
                            [matTooltip]="stepsWhyNot() || 'Two rings: two recorded expand steps'"
                            data-testid="la-grow-two-steps"
                        >
                            <span class="text-xs">2 steps</span>
                        </mat-button-toggle>
                    </mat-button-toggle-group>
                    @if (stepsWhyNot(); as why) {
                        <p class="text-secondary m-0 text-xs" data-testid="la-grow-steps-why">{{ why }}</p>
                    }
                </div>

                <div class="flex flex-col gap-1">
                    <mat-checkbox
                        formControlName="hold"
                        [matTooltip]="'Busy ' + t().nodes + ' are super nodes (hub threshold): shown, not grown further'"
                        data-testid="la-grow-hold"
                    >
                        <span class="text-xs">Hold busy {{ t().nodes }} with more than {{ thresholdLabel() }} contacts</span>
                    </mat-checkbox>
                    @if (form.controls.hold.value) {
                        <inspecto-pen-field
                            class="ml-8"
                            label="Contacts limit"
                            type="number"
                            [min]="1"
                            [default]="ctx().hubThreshold"
                            [placeholder]="ctx().hubThreshold.toLocaleString('en') + ' (the Space default)'"
                            formControlName="threshold"
                            [error]="thresholdError()"
                        ></inspecto-pen-field>
                    } @else {
                        <p class="text-secondary m-0 text-xs" data-testid="la-grow-no-hold">
                            Busy {{ t().nodes }} will be grown through too — recorded as an override in the log.
                        </p>
                    }
                </div>

                <button
                    mat-button
                    type="button"
                    class="self-start"
                    [attr.aria-expanded]="moreOpen()"
                    aria-controls="la-grow-more"
                    (click)="moreOpen.set(!moreOpen())"
                >
                    <mat-icon
                        [svgIcon]="moreOpen() ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'"
                        aria-hidden="true"
                    ></mat-icon>
                    More options
                </button>
                <div id="la-grow-more" class="flex flex-col gap-2 pl-2" [class.hidden]="!moreOpen()">
                    @if (ctx().linkKinds.length) {
                        <div class="flex flex-col gap-1" role="group" aria-labelledby="la-grow-kinds">
                            <span class="text-xs font-semibold" id="la-grow-kinds">{{ linkKindsLabel() }}</span>
                            @for (k of ctx().linkKinds; track k) {
                                <mat-checkbox [checked]="kindOn(k)" (change)="toggleKind(k, $event.checked)">
                                    <span class="text-xs">{{ k }}</span>
                                </mat-checkbox>
                            }
                        </div>
                    } @else {
                        <p class="text-secondary m-0 text-xs">This graph has one kind of {{ t().link }}, so there is nothing to choose.</p>
                    }
                    @if (ctx().hasWindow) {
                        <inspecto-option-picker
                            label="Time window"
                            [options]="windowOptions"
                            formControlName="window"
                        ></inspecto-option-picker>
                    }
                </div>
            </form>

            <div role="status" aria-live="polite" class="text-xs" data-testid="la-grow-preview">
                @switch (pv().state) {
                    @case ('loading') {
                        <span class="text-secondary">Checking what this step would add…</span>
                    }
                    @case ('ready') {
                        <p class="m-0">{{ sentence() }}</p>
                        @if (pv().preview?.excludedByLists?.length) {
                            <div class="mt-1 flex flex-wrap gap-1" aria-label="Exclusion lists in force" role="list" data-testid="la-grow-lists">
                                @for (l of pv().preview!.excludedByLists; track l.listId) {
                                    <span role="listitem">
                                        <inspecto-chip variant="soft" [matTooltip]="'Exclusion list — removes ' + l.removed + ' from this step'"
                                            >{{ l.title }} · {{ l.removed.toLocaleString('en') }}</inspecto-chip
                                        >
                                    </span>
                                }
                            </div>
                        }
                    }
                    @case ('unavailable') {
                        <span class="text-secondary" data-testid="la-grow-no-preview"
                            >No preview on this server — the step's result is shown after it runs, with Undo.</span
                        >
                    }
                    @case ('error') {
                        <inspecto-alert variant="warning" title="The preview was refused">{{ pv().message }}</inspecto-alert>
                    }
                }
            </div>

            @if (ctx().blocked; as why) {
                <inspecto-alert variant="info" data-testid="la-grow-blocked">{{ why }}</inspecto-alert>
            }

            <div class="flex items-center justify-end gap-2">
                <button mat-button type="button" (click)="closed.emit()">Cancel</button>
                <button
                    mat-flat-button
                    color="primary"
                    type="button"
                    data-testid="la-grow-go"
                    [disabled]="!!growWhyNot()"
                    [disabledInteractive]="true"
                    [matTooltip]="growWhyNot() || ''"
                    [attr.aria-describedby]="growWhyNot() ? 'la-grow-why' : null"
                    (click)="submit()"
                >
                    Grow
                </button>
            </div>
            @if (growWhyNot(); as why) {
                <p id="la-grow-why" class="text-secondary m-0 text-right text-xs">{{ why }}</p>
            }
        </section>
    `,
})
export class LinkAnalysisGrowComponent implements OnInit, OnChanges {
    readonly ctx = input.required<GrowContext>();
    /** The profile's expand presets (window, budget, caps) the step is laid over. */
    readonly presets = input<DegreePresets>({});
    readonly grow = output<GrowRequest>();
    readonly closed = output<void>();

    readonly store = inject(GrowExcludeStore);
    private destroyRef = inject(DestroyRef);
    private host: ElementRef<HTMLElement> = inject(ElementRef);

    readonly directions = GROW_DIRECTIONS;
    readonly guide = GROW_GUIDE;
    readonly windowOptions: PickerOption[] = [
        { value: 'inherit', label: 'The Investigation’s window', hint: 'The window set for this Investigation' },
        { value: 'full', label: 'All available data', hint: 'Ignore the window for this step' },
    ];
    readonly moreOpen = signal(false);
    readonly showHint = signal(!hintSeen('grow'));
    readonly t = computed(() => this.ctx().terms);
    readonly pv = this.store.preview;
    readonly sentence = computed(() => {
        const p = this.pv().preview;
        return p ? previewSentence(p, this.t()) : '';
    });

    readonly form = new FormGroup({
        direction: new FormControl<GrowDirection>('either', { nonNullable: true }),
        steps: new FormControl<1 | 2>(1, { nonNullable: true }),
        hold: new FormControl(true, { nonNullable: true }),
        threshold: new FormControl<number | null>(null),
        window: new FormControl<'inherit' | 'full'>('inherit', { nonNullable: true }),
    });
    /** null = every link kind. */
    private readonly kinds = signal<string[] | null>(null);
    private readonly formValue = signal(this.form.getRawValue());

    readonly thresholdLabel = computed(() => (this.formValue().threshold ?? this.ctx().hubThreshold).toLocaleString('en'));
    readonly thresholdError = computed(() => {
        const v = this.formValue().threshold;
        return v != null && !(Number.isInteger(v) && v >= 1) ? 'A whole number of at least 1.' : '';
    });
    readonly linkKindsLabel = computed(() => {
        const l = this.t().links;
        return l.charAt(0).toUpperCase() + l.slice(1) + ' to follow';
    });
    readonly stepsWhyNot = computed(() =>
        secondStepWhyNot(this.pv().preview, this.pv().state, this.ctx().secondStepCap, this.t()),
    );
    /** Why the primary action is not possible now — said beside the button, never a silent grey. */
    readonly growWhyNot = computed(() => {
        if (this.ctx().blocked) return this.ctx().blocked;
        if (this.store.busy()) return 'Another change is still running.';
        if (!growIds(this.ctx(), { hold: this.formValue().hold }).length) {
            const t = this.t();
            return this.ctx().heldFrontier.length
                ? `Every ${t.node} at the edge is busy and held. Untick “Hold busy ${t.nodes}” to grow through them, or use Expand anyway in the Held tray.`
                : `There is nothing to grow from yet.`;
        }
        if (this.kinds()?.length === 0) return `Choose at least one kind of ${this.t().link} to follow.`;
        if (this.thresholdError()) return 'Fix the contacts limit first.';
        return null;
    });

    constructor() {
        // The preview can arrive (and forbid a second step) after the analyst chose 2: fall back to 1 step.
        effect(() => {
            if (this.stepsWhyNot() && untracked(() => this.form.controls.steps.value) === 2)
                untracked(() => this.form.controls.steps.setValue(1));
        });
    }

    ngOnInit(): void {
        this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
            this.formValue.set(this.form.getRawValue());
            // a choice the preview no longer supports falls back to the safe default
            if (this.form.controls.steps.value === 2 && this.stepsWhyNot()) this.form.controls.steps.setValue(1);
        });
        // The preview covers the first step, so "How far" alone never re-reads it.
        this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef)).subscribe(() => {
            if (this.previewKey() !== this.previewedKey) this.refresh();
        });
        queueMicrotask(() => this.host.nativeElement.querySelector<HTMLElement>('mat-button-toggle button')?.focus());
    }

    private previewedKey = '';
    private previewKey(): string {
        const { steps: _steps, ...rest } = this.request();
        return JSON.stringify([rest, this.ctx().frontier, this.ctx().blocked, this.ctx().mode]);
    }

    ngOnChanges(): void {
        if (this.previewKey() !== this.previewedKey) this.refresh();
    }

    /** The choice as the screen acts on it. */
    request(): GrowRequest {
        const v = this.form.getRawValue();
        return {
            direction: v.direction,
            steps: v.steps === 2 && !this.stepsWhyNot() ? 2 : 1,
            linkKinds: this.kinds(),
            window: v.window,
            hold: v.hold,
            threshold: v.threshold ?? this.ctx().hubThreshold,
        };
    }

    /** Re-read the preview for the current choice (the preview covers the first step). */
    refresh(): void {
        this.previewedKey = this.previewKey();
        if (this.thresholdError() || this.kinds()?.length === 0) {
            this.store.resetPreview();
            return;
        }
        void this.store.runPreview(this.ctx(), { ...this.request(), steps: 1 }, this.presets());
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
        if (this.growWhyNot()) return;
        this.grow.emit(this.request());
    }
}
