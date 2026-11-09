import {
    ChangeDetectionStrategy,
    Component,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
    untracked,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage, ComponentsService, heldChange } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { DataTableComponent } from '@inspecto/core/data-table';
import { guardDirtyClose } from '@inspecto/core/dialog-dirty-guard';
import { BuilderQuery, BuilderQueryResult, InvService, PresetList } from '@inspecto/link-analysis/api/inv.service';
import type { PresetDraw } from './link-analysis-presets.component';
import { presetColumns, presetEntityValues } from './presets';
import {
    BY_OPTIONS,
    RANK_OPTIONS,
    builderQuery,
    builderSentence,
    measureOptions,
    savedPresetId,
    timeOptions,
} from './query-builder';

const NUMBER = /^-?\d+(\.\d+)?$/;
/** 1 to 500, the server's row cap (PresetQueries.MAX_ROWS). */
const WHOLE = /^([1-9]\d{0,1}|[1-4]\d{2}|500)$/;
const DAY_OR_INSTANT = /^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}(:\d{2})?Z)?$/;

/**
 * LA-QUERY-BUILDER-1 **Build a question**: a form over the Dataset's roles - group by From or To, rank by links, distinct
 * counterparts or the total of a Measure, keep one From or To, some kinds of link, a window on a Time column, a range on a
 * Measure, a minimum, how many rows - answered as a table, with *Draw these* and *Save as preset* (a Space preset everyone
 * here can pick). Declarative: the server refuses any field the Dataset cannot answer.
 */
@Component({
    selector: 'inspecto-link-analysis-query-builder',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
        DataTableComponent,
    ],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2 text-sm" aria-labelledby="la-builder-title" data-testid="la-builder">
            <h3 id="la-builder-title" class="m-0 text-base font-semibold">Build a question</h3>
            <form class="flex flex-col gap-2" [formGroup]="form" (ngSubmit)="ask()" data-testid="la-builder-form">
                <div class="grid grid-cols-1 gap-x-8 sm:grid-cols-2">
                    <inspecto-option-picker
                        label="Rank"
                        [options]="byOptions"
                        formControlName="by"
                        data-testid="la-builder-by"
                    />
                    <inspecto-option-picker
                        label="By"
                        [options]="rankOptions()"
                        formControlName="rank"
                        data-testid="la-builder-rank"
                    />
                    @if (form.controls.rank.value === 'sum') {
                        <inspecto-option-picker
                            label="Measure to total"
                            [options]="measures()"
                            formControlName="sumColumn"
                            data-testid="la-builder-sum"
                        />
                    }
                    <inspecto-pen-field
                        label="From is"
                        formControlName="from"
                        help="One entity, or leave blank"
                        data-testid="la-builder-from"
                    />
                    <inspecto-pen-field
                        label="To is"
                        formControlName="to"
                        help="One entity, or leave blank"
                        data-testid="la-builder-to"
                    />
                    @if (list()?.roles?.kind) {
                        <inspecto-pen-field
                            label="Kinds of link"
                            formControlName="kinds"
                            help="Comma list; blank = every kind"
                            data-testid="la-builder-kinds"
                        />
                    }
                    @if (times().length) {
                        @if (times().length > 1) {
                            <inspecto-option-picker label="When, by" [options]="times()" formControlName="timeColumn" />
                        }
                        <inspecto-pen-field
                            label="From day"
                            formControlName="after"
                            help="2026-10-01, or an instant ending in Z"
                            [error]="fieldError('after')"
                            data-testid="la-builder-after"
                        />
                        <inspecto-pen-field
                            label="Before day"
                            formControlName="before"
                            help="Not included"
                            [error]="fieldError('before')"
                            data-testid="la-builder-before"
                        />
                    }
                    @if (measures().length) {
                        <inspecto-option-picker
                            label="Measure range on"
                            [options]="measures()"
                            formControlName="measureColumn"
                        />
                        <inspecto-pen-field
                            label="At least"
                            formControlName="measureMin"
                            [error]="fieldError('measureMin')"
                        />
                        <inspecto-pen-field
                            label="At most"
                            formControlName="measureMax"
                            [error]="fieldError('measureMax')"
                        />
                    }
                    <inspecto-pen-field
                        label="Keep groups with at least"
                        formControlName="min"
                        help="Of the ranked value"
                        [error]="fieldError('min')"
                    />
                    <inspecto-pen-field
                        label="How many rows"
                        formControlName="top"
                        [error]="fieldError('top')"
                        data-testid="la-builder-top"
                    />
                </div>
                @if (sumWhyNot()) {
                    <p class="text-warn m-0 text-xs" role="alert">{{ sumWhyNot() }}</p>
                }
                <button
                    mat-flat-button
                    color="primary"
                    type="submit"
                    class="self-start"
                    [disabled]="busy() || !dataset()"
                    data-testid="la-builder-ask"
                >
                    Ask
                </button>
            </form>
            @if (error()) {
                <inspecto-alert variant="error">{{ error() }}</inspecto-alert>
            }
            @if (note()) {
                <inspecto-alert variant="success">{{ note() }}</inspecto-alert>
            }
            @if (result(); as r) {
                <div class="flex items-center gap-2">
                    <h4 class="m-0 flex-auto text-sm font-semibold">Answer</h4>
                    <button mat-stroked-button type="button" (click)="result.set(null)" data-testid="la-builder-answer-close">
                        Close the answer
                    </button>
                </div>
                @if (r.truncated) {
                    <inspecto-alert variant="warning" title="Answer cut short">
                        Only the first {{ r.count }} rows are shown. Narrow the question or ask for fewer rows.
                    </inspecto-alert>
                }
                <inspecto-data-table
                    [rows]="r.rows"
                    [columns]="columns()"
                    noRowsTitle="Nothing matches this question"
                    exportName="query"
                ></inspecto-data-table>
                <div class="flex flex-wrap gap-2">
                    <button
                        mat-stroked-button
                        type="button"
                        data-testid="la-builder-draw"
                        [disabled]="!drawValues().length || !!drawWhyNot()"
                        [disabledInteractive]="true"
                        [attr.aria-describedby]="drawWhyNot() ? 'la-builder-draw-why' : null"
                        (click)="drawThese()"
                    >
                        Draw these
                    </button>
                    <button mat-stroked-button type="button" data-testid="la-builder-save" (click)="saveAsPreset()">
                        Save as preset
                    </button>
                </div>
                @if (drawWhyNot()) {
                    <p id="la-builder-draw-why" class="text-secondary m-0 text-xs">{{ drawWhyNot() }}</p>
                }
            }
        </section>
    `,
})
export class LinkAnalysisQueryBuilderComponent {
    private readonly inv = inject(InvService);
    private readonly dialog = inject(MatDialog);

    /** The Dataset asked (the presets panel's). */
    readonly dataset = input<string | null>(null);
    /** That Dataset's presets list: its roles and the role columns the builder may name. */
    readonly list = input<PresetList | null>(null);
    /** Why *Draw these* cannot run here; '' = it can. */
    readonly drawWhyNot = input('');
    /** The ids of the Space presets already saved (a new name must not reuse one). */
    readonly takenIds = input<string[]>([]);

    readonly draw = output<PresetDraw>();
    /** A preset was saved (or held for approval): the host re-reads its list. */
    readonly saved = output<void>();

    readonly form = new FormGroup({
        by: new FormControl<string | null>('from'),
        rank: new FormControl<string | null>('links'),
        sumColumn: new FormControl<string | null>(null),
        from: new FormControl<string | null>(null),
        to: new FormControl<string | null>(null),
        kinds: new FormControl<string | null>(null),
        timeColumn: new FormControl<string | null>(null),
        after: new FormControl<string | null>(null, Validators.pattern(DAY_OR_INSTANT)),
        before: new FormControl<string | null>(null, Validators.pattern(DAY_OR_INSTANT)),
        measureColumn: new FormControl<string | null>(null),
        measureMin: new FormControl<string | null>(null, Validators.pattern(NUMBER)),
        measureMax: new FormControl<string | null>(null, Validators.pattern(NUMBER)),
        min: new FormControl<string | null>(null, Validators.pattern(/^\d+(\.\d+)?$/)),
        top: new FormControl<string | null>('50', Validators.pattern(WHOLE)),
    });

    readonly byOptions = BY_OPTIONS;
    readonly measures = computed(() => measureOptions(this.list()));
    readonly times = computed(() => timeOptions(this.list()));
    /** Total of a Measure is offered only when the Dataset has one. */
    readonly rankOptions = computed(() =>
        this.measures().length ? RANK_OPTIONS : RANK_OPTIONS.filter((o) => o.value !== 'sum'),
    );
    readonly busy = signal(false);
    readonly error = signal('');
    readonly note = signal('');
    readonly result = signal<BuilderQueryResult | null>(null);
    private readonly submitted = signal(false);
    readonly columns = computed(() => presetColumns(this.result()));
    readonly drawValues = computed(() => presetEntityValues(this.result()));
    /** The last question asked - what *Save as preset* saves. */
    private asked: BuilderQuery | null = null;

    /** "Largest total" without a Measure picked: the picker's own line shows only once opened, so the form says it. */
    readonly sumWhyNot = computed(() =>
        this.submitted() && this.form.controls.rank.value === 'sum' && !this.form.controls.sumColumn.value
            ? 'Pick the Measure to total.'
            : '',
    );

    constructor() {
        // the Measure and Time pickers start on the Dataset's first (the primary) one, so a range or a total needs no extra pick
        effect(() => {
            const m = this.measures()[0]?.value ?? null;
            const t = this.times()[0]?.value ?? null;
            untracked(() => {
                const c = this.form.controls;
                if (!this.measures().some((o) => o.value === c.sumColumn.value)) c.sumColumn.setValue(m);
                if (!this.measures().some((o) => o.value === c.measureColumn.value)) c.measureColumn.setValue(m);
                if (!this.times().some((o) => o.value === c.timeColumn.value)) c.timeColumn.setValue(t);
                if (!m && c.rank.value === 'sum') c.rank.setValue('links');
                this.result.set(null);
            });
        });
    }

    protected fieldError(name: 'after' | 'before' | 'measureMin' | 'measureMax' | 'min' | 'top'): string {
        const c = this.form.controls[name];
        if (!c.touched || !c.hasError('pattern')) return '';
        if (name === 'after' || name === 'before') return 'A day like 2026-10-01, or an instant ending in Z.';
        if (name === 'top') return 'A whole number, 1 to 500.';
        return 'A number.';
    }

    ask(): void {
        const ds = this.dataset();
        this.submitted.set(true);
        if (!ds) return;
        if (this.form.invalid || this.sumWhyNot()) {
            this.form.markAllAsTouched();
            return;
        }
        const q = builderQuery(this.form.getRawValue());
        this.busy.set(true);
        this.error.set('');
        this.note.set('');
        this.inv.runQuery(ds, q).subscribe({
            next: (r) => {
                this.busy.set(false);
                this.asked = q;
                this.result.set(r);
            },
            error: (e) => {
                this.busy.set(false);
                this.result.set(null);
                this.error.set(apiErrorMessage(e, 'The question could not be answered.'));
            },
        });
    }

    drawThese(): void {
        const ds = this.dataset();
        const values = this.drawValues();
        if (!ds || !values.length || this.drawWhyNot() || !this.asked) return;
        this.draw.emit({ datasetId: ds, values, what: `the answer to "${builderSentence(this.asked)}"` });
    }

    saveAsPreset(): void {
        const q = this.asked;
        if (!q) return;
        this.dialog
            .open<SaveQueryPresetDialog, SaveQueryPresetData, SaveQueryPresetResult>(SaveQueryPresetDialog, {
                data: { query: q, takenIds: this.takenIds() },
                width: '32rem',
            })
            .afterClosed()
            .subscribe((r) => {
                if (!r) return;
                this.note.set(
                    r.held
                        ? `"${r.label}" waits for approval before it is saved.`
                        : `Saved "${r.label}": everyone in this Space can pick it from the questions.`,
                );
                this.saved.emit();
            });
    }
}

/** What the save dialog is opened with. */
export interface SaveQueryPresetData {
    query: BuilderQuery;
    takenIds: string[];
}

/** What it closes with after a save (or a held change under maker-checker). */
export interface SaveQueryPresetResult {
    label: string;
    held: boolean;
}

/** *Save as preset*: names the question and stores it as a `preset-query` component of this Space. */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, InspectoAlertComponent, PenFieldComponent],
    template: `
        <h2 mat-dialog-title>Save as preset</h2>
        <mat-dialog-content class="flex flex-col gap-3 text-sm">
            <p class="text-secondary m-0">
                Everyone in this Space can then pick it from the questions, over any Dataset that has its columns.
            </p>
            <form [formGroup]="form" (ngSubmit)="submit()" class="flex flex-col gap-3">
                <inspecto-pen-field
                    label="Name"
                    formControlName="label"
                    [maxlength]="80"
                    [error]="labelError()"
                    data-testid="preset-name"
                />
                <inspecto-pen-field
                    label="Description"
                    formControlName="description"
                    [maxlength]="500"
                    [placeholder]="sentence"
                    help="Blank = the question in words"
                    data-testid="preset-description"
                />
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Not saved">{{ error() }}</inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button
                mat-flat-button
                color="primary"
                type="button"
                data-testid="preset-save"
                [disabled]="busy()"
                (click)="submit()"
            >
                Save preset
            </button>
        </mat-dialog-actions>
    `,
})
export class SaveQueryPresetDialog {
    readonly data = inject<SaveQueryPresetData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<SaveQueryPresetDialog, SaveQueryPresetResult | undefined>);
    private readonly components = inject(ComponentsService);
    private readonly confirm = inject(InspectoConfirmService);

    readonly form = new FormGroup({
        label: new FormControl('', { nonNullable: true }),
        description: new FormControl('', { nonNullable: true }),
    });
    readonly sentence = builderSentence(this.data.query);
    readonly busy = signal(false);
    readonly error = signal('');
    private readonly submitted = signal(false);
    private readonly label = signal('');
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    constructor() {
        this.form.controls.label.valueChanges.subscribe((v) => this.label.set(v));
    }

    readonly labelError = computed(() => {
        if (!this.submitted()) return '';
        const id = savedPresetId(this.label());
        if (!this.label().trim()) return 'Name the preset.';
        if (!id) return 'Use at least one letter or digit.';
        return this.data.takenIds.includes(id) ? 'A preset with this name exists. Choose another name.' : '';
    });

    async submit(): Promise<void> {
        if (this.busy()) return;
        this.submitted.set(true);
        this.form.markAllAsTouched();
        if (this.labelError()) return;
        const v = this.form.getRawValue();
        const label = v.label.trim();
        this.busy.set(true);
        this.error.set('');
        try {
            const res = await firstValueFrom(
                this.components.create('preset-query', {
                    id: savedPresetId(label),
                    label,
                    description: v.description.trim() || this.sentence,
                    query: this.data.query,
                }),
            );
            this.ref.close({ label, held: !!heldChange(res) });
        } catch (err) {
            this.error.set(apiErrorMessage(err, 'The preset could not be saved.'));
        } finally {
            this.busy.set(false);
        }
    }
}
