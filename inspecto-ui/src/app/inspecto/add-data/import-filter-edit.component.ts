import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import {
    ColumnType,
    ImportFilterState,
    ROW_OP_LABELS,
    RowCondition,
    RowConditionState,
    RowOp,
    TYPE_LABELS,
    buildFilter,
    conditionProblem,
    conditionState,
    conditionValueType,
    keepsRowsLine,
    rowOps,
} from './add-data.model';
import { OnboardService } from './onboard.service';

type ConditionGroup = FormGroup<{
    column: FormControl<string>;
    op: FormControl<RowOp>;
    value: FormControl<string>;
    min: FormControl<string>;
    max: FormControl<string>;
    matchCase: FormControl<boolean>;
}>;

/**
 * **Edit filter** on an import Dataset's card (`ADD-DATA-FILTER-EDIT-1`): the review step's condition rows over the
 * Dataset's own columns, saved through `PUT /datasets/{id}/filter`. The new conditions apply to the files added from now
 * on; the rows already imported stay, and the panel says so. A Postgres snapshot (`snapshot`) is copied again whole on
 * Refresh now, so there the new filter applies to every row at the next refresh.
 *
 * @example <inspecto-import-filter-edit [datasetId]="d.id" [locked]="locked()" (saved)="conditions = $event" />
 */
@Component({
    selector: 'inspecto-import-filter-edit',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatIconModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    host: { class: 'flex flex-col gap-2' },
    template: `
        @if (!state()) {
            <button
                mat-stroked-button
                type="button"
                class="self-start"
                data-testid="edit-filter"
                [disabled]="working() || busy() || !!locked()"
                (click)="open()"
            >
                <mat-icon svgIcon="heroicons_outline:funnel"></mat-icon>
                Edit filter
            </button>
        } @else {
            <section class="flex flex-col gap-2" [attr.aria-labelledby]="ids + '-title'" data-testid="filter-editor">
                <h4 class="m-0 text-sm font-semibold" [id]="ids + '-title'">Keep only some rows</h4>
                <p class="text-secondary m-0 text-xs" data-testid="filter-applies">
                    {{ appliesLine() }}
                </p>
                @if (groups().length) {
                    <ul class="m-0 flex list-none flex-col gap-2 p-0">
                        @for (g of groups(); track g; let i = $index) {
                            <li
                                class="flex flex-col gap-1 rounded-lg border p-2"
                                [formGroup]="g"
                                [attr.data-condition]="i"
                            >
                                <div class="grid grid-cols-1 items-center gap-2 sm:grid-cols-[2fr_2fr_3fr_auto]">
                                    <inspecto-option-picker
                                        label="Column"
                                        formControlName="column"
                                        placeholder="Choose a column"
                                        data-testid="condition-column"
                                        [options]="columnOptions()"
                                    ></inspecto-option-picker>
                                    <inspecto-option-picker
                                        label="Condition"
                                        formControlName="op"
                                        data-testid="condition-op"
                                        [options]="opOptions(g.controls.column.value)"
                                    ></inspecto-option-picker>
                                    @if (g.controls.op.value === 'range') {
                                        <div class="grid grid-cols-2 gap-2">
                                            <inspecto-pen-field
                                                label="From"
                                                formControlName="min"
                                                placeholder="Any"
                                                data-testid="condition-min"
                                                [type]="valueType(g.controls.column.value)"
                                            ></inspecto-pen-field>
                                            <inspecto-pen-field
                                                label="To"
                                                formControlName="max"
                                                placeholder="Any"
                                                data-testid="condition-max"
                                                [type]="valueType(g.controls.column.value)"
                                            ></inspecto-pen-field>
                                        </div>
                                    } @else {
                                        <inspecto-pen-field
                                            label="Value"
                                            formControlName="value"
                                            data-testid="condition-value"
                                            [type]="valueType(g.controls.column.value)"
                                        ></inspecto-pen-field>
                                    }
                                    <button
                                        mat-icon-button
                                        type="button"
                                        data-testid="remove-condition"
                                        [attr.aria-label]="'Remove condition ' + (i + 1)"
                                        (click)="remove(i)"
                                    >
                                        <mat-icon svgIcon="heroicons_outline:x-mark" aria-hidden="true"></mat-icon>
                                    </button>
                                </div>
                                @if (g.controls.op.value === 'contains') {
                                    <mat-checkbox formControlName="matchCase" data-testid="condition-match-case"
                                        >Match case</mat-checkbox
                                    >
                                }
                                @if (problems()[i]; as why) {
                                    <p class="text-warn m-0 text-xs" role="alert">{{ why }}</p>
                                }
                            </li>
                        }
                    </ul>
                } @else {
                    <p class="m-0 text-sm" data-testid="every-row">Every row is kept.</p>
                }
                <div>
                    <button mat-button type="button" data-testid="add-condition" (click)="add()">
                        <mat-icon svgIcon="heroicons_outline:plus" aria-hidden="true"></mat-icon>
                        Add a condition
                    </button>
                </div>
                @if (keepsLine()) {
                    <p class="m-0 text-sm" data-testid="filter-keeps-line">{{ keepsLine() }}.</p>
                }
                <div class="flex flex-wrap gap-2">
                    <button
                        mat-flat-button
                        color="primary"
                        type="button"
                        data-testid="save-filter"
                        [disabled]="!canSave()"
                        (click)="save()"
                    >
                        Save filter
                    </button>
                    <button mat-button type="button" data-testid="cancel-filter" (click)="close()">Cancel</button>
                </div>
            </section>
        }
        @if (note(); as n) {
            <inspecto-alert [variant]="n.variant" data-testid="filter-note">{{ n.text }}</inspecto-alert>
        }
    `,
})
export class ImportFilterEditComponent {
    private static nextId = 0;
    private readonly onboard = inject(OnboardService);
    private readonly destroyRef = inject(DestroyRef);

    readonly datasetId = input.required<string>();
    /** The card is running an import. */
    readonly busy = input(false);
    /** Why the person may not change data here, or null. */
    readonly locked = input<string | null>(null);
    /** A Postgres snapshot: Refresh now copies the whole table again, so the filter applies to every row from then on. */
    readonly snapshot = input(false);
    /** The conditions now in force, after a save. */
    readonly saved = output<RowCondition[]>();

    protected readonly ids = `inspecto-filter-edit-${ImportFilterEditComponent.nextId++}`;
    /** The read answer while the editor is open; null = closed. */
    protected readonly state = signal<ImportFilterState | null>(null);
    protected readonly working = signal(false);
    protected readonly note = signal<{ variant: 'info' | 'error'; text: string } | null>(null);
    protected readonly form = new FormArray<ConditionGroup>([]);
    /** Bumped on every form change, so the computed rows below re-read the form. */
    private readonly tick = signal(0);

    protected readonly groups = computed(() => {
        this.tick();
        return this.form.controls;
    });
    private readonly states = computed<RowConditionState[]>(() => {
        this.tick();
        return this.form.getRawValue();
    });
    private readonly columns = computed(() =>
        (this.state()?.columns ?? []).map((c) => ({ name: c.name, type: c.type, drop: false })),
    );
    protected readonly columnOptions = computed<PickerOption[]>(() =>
        this.columns().map((c) => ({ value: c.name, label: c.name, hint: TYPE_LABELS[c.type as ColumnType] })),
    );
    protected readonly problems = computed(() => this.states().map((c) => conditionProblem(c, this.columns())));
    protected readonly keepsLine = computed(() => {
        const ok = this.states().filter((_, i) => !this.problems()[i]);
        return keepsRowsLine(buildFilter(ok));
    });
    protected readonly canSave = computed(
        () => !this.working() && !this.busy() && !this.locked() && this.problems().every((p) => !p),
    );
    protected readonly appliesLine = computed(() =>
        this.snapshot()
            ? 'Applies at the next Refresh now, which copies the table again and replaces every row.'
            : 'Applies to files added from now on; rows already imported stay.',
    );

    constructor() {
        this.form.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.tick.update((n) => n + 1));
    }

    protected open(): void {
        this.note.set(null);
        this.working.set(true);
        this.onboard
            .importFilter(this.datasetId())
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (s) => {
                    this.working.set(false);
                    this.form.clear();
                    for (const c of s.filter) this.add(conditionState(c));
                    this.state.set(s);
                },
                error: (err: unknown) => {
                    this.working.set(false);
                    this.note.set({ variant: 'error', text: apiErrorMessage(err, 'The filter could not be read.') });
                },
            });
    }

    protected close(): void {
        this.state.set(null);
        this.form.clear();
    }

    protected add(seed?: RowConditionState): void {
        const g: ConditionGroup = new FormGroup({
            column: new FormControl(seed?.column ?? '', { nonNullable: true }),
            op: new FormControl<RowOp>(seed?.op ?? 'eq', { nonNullable: true }),
            value: new FormControl(seed?.value ?? '', { nonNullable: true }),
            min: new FormControl(seed?.min ?? '', { nonNullable: true }),
            max: new FormControl(seed?.max ?? '', { nonNullable: true }),
            matchCase: new FormControl(seed?.matchCase ?? false, { nonNullable: true }),
        });
        // another column may not offer the chosen condition (contains is for text): back to "is"
        g.controls.column.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((c) => {
            if (!rowOps(this.columnType(c)).includes(g.controls.op.value)) g.controls.op.setValue('eq');
        });
        this.form.push(g);
    }

    protected remove(i: number): void {
        this.form.removeAt(i);
    }

    protected opOptions(column: string): PickerOption[] {
        return rowOps(this.columnType(column)).map((op) => ({ value: op, label: ROW_OP_LABELS[op] }));
    }

    protected valueType(column: string): 'text' | 'number' | 'date' | 'datetime-local' {
        return conditionValueType(this.columnType(column));
    }

    private columnType(column: string): string {
        return this.columns().find((c) => c.name === column)?.type ?? '';
    }

    protected save(): void {
        if (!this.canSave()) return;
        this.note.set(null);
        this.working.set(true);
        this.onboard
            .setImportFilter(this.datasetId(), buildFilter(this.states()))
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (s) => {
                    this.working.set(false);
                    this.close();
                    this.saved.emit(s.filter);
                    this.note.set({
                        variant: 'info',
                        text: s.filter.length
                            ? `Saved. ${keepsRowsLine(s.filter)}. ${this.appliesLine()}`
                            : `Saved. Every row is kept. ${this.appliesLine()}`,
                    });
                },
                error: (err: unknown) => {
                    this.working.set(false);
                    this.note.set({ variant: 'error', text: apiErrorMessage(err, 'The filter was not saved.') });
                },
            });
    }
}
