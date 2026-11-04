import { ChangeDetectionStrategy, Component, DestroyRef, Input, OnInit, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { validateGroup } from 'app/inspecto/query/condition-rules';
import { QueryConditionGroupComponent } from 'app/inspecto/query/query-condition-group.component';
import { ColumnMeta, ConditionGroup, emptyGroup } from 'app/inspecto/query/query-types';
import {
    MAX_EVIDENCE,
    MAX_FACTORS,
    RISK_FILTER_OPS,
    RiskFactorDraft,
    RiskFilterDraft,
    emptyFactor,
    isEditableGroup,
} from 'app/inspecto/risk/risk-score-form';

/**
 * The Factor row editor of the Risk Score form (spec §3, D-RP7 (b) bespoke cards). One card per factor, with a
 * flat filter list (an AND of `{field, op, value}`) plus a collapsed "Advanced filter" that mounts the condition-group
 * editor for the factor's optional `when` tree (ANDed after the flat filters by `MeasureCompiler`).
 * Presentational: the host supplies the Dataset choices and a column source, reads {@link value} on save, and
 * places server refusals through {@link setRowError}.
 */
@Component({
    selector: 'app-risk-score-factors',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        InspectoOptionPickerComponent,
        PenFieldComponent,
        QueryConditionGroupComponent,
    ],
    template: `
        <div class="flex flex-col gap-3">
            @for (g of rows.controls; track g; let i = $index) {
                <fieldset class="rounded-lg border p-3" [formGroup]="g" [attr.aria-label]="'Factor ' + (i + 1)">
                    <legend class="px-1 text-sm font-semibold">Factor {{ i + 1 }}</legend>
                    @if (rowErrors()[i]; as e) {
                        <p class="text-warn mb-2 text-xs" role="alert" data-testid="factor-error">{{ e }}</p>
                    }
                    <div class="grid grid-cols-1 gap-x-8 sm:grid-cols-2">
                        <inspecto-pen-field
                            label="Id"
                            [required]="true"
                            formControlName="id"
                            [error]="fieldError(g, 'id', { required: 'Id is required', pattern: 'Id may hold only letters, digits and _' })"
                        ></inspecto-pen-field>
                        <inspecto-pen-field label="Label" formControlName="label" [error]="fieldError(g, 'label', {})"></inspecto-pen-field>
                        <inspecto-option-picker
                            label="Dataset"
                            formControlName="dataset"
                            [options]="datasetOptions()"
                            help="Only Datasets with a readable Schema."
                        ></inspecto-option-picker>
                        <inspecto-option-picker
                            label="Entity key column"
                            formControlName="key"
                            [options]="columnOptions(i)"
                        ></inspecto-option-picker>
                        <inspecto-pen-field
                            label="Measure"
                            [required]="true"
                            [mono]="true"
                            placeholder="count or sum(field)"
                            formControlName="measure"
                            [error]="fieldError(g, 'measure', { required: 'Measure is required' })"
                        ></inspecto-pen-field>
                        <inspecto-pen-field
                            label="Weight"
                            [required]="true"
                            formControlName="weight"
                            help="Negative = protective"
                            [error]="fieldError(g, 'weight', { required: 'Weight is required', pattern: 'Weight must be a number' })"
                        ></inspecto-pen-field>
                        <inspecto-pen-field
                            label="Cap"
                            formControlName="cap"
                            [error]="fieldError(g, 'cap', { pattern: 'Cap must be a number ≥ 0' })"
                        ></inspecto-pen-field>
                        <!-- form-allow: a multiple choice (the evidence columns), not one --><mat-form-field
                            subscriptSizing="dynamic"
                            class="sm:col-span-2"
                        >
                            <mat-label>Evidence columns (up to {{ maxEvidence }})</mat-label>
                            <!-- form-allow: a multiple choice, not one --><mat-select
                                formControlName="evidence"
                                multiple
                            >
                                @for (c of columnOptions(i); track c.value) {
                                    <mat-option [value]="c.value">{{ c.label }}</mat-option>
                                }
                            </mat-select>
                            @if (g.controls['evidence'].hasError('max')) {
                                <mat-error>At most {{ maxEvidence }} evidence columns</mat-error>
                            }
                        </mat-form-field>
                    </div>

                    <div class="mt-2" formArrayName="filters">
                        <span class="text-secondary text-xs">Filters (all must hold)</span>
                        @for (fg of filtersOf(i).controls; track fg; let j = $index) {
                            <div class="flex flex-wrap items-center gap-2" [formGroupName]="j">
                                <!-- form-allow: a table-cell select of a repeating row (one filter) --><mat-form-field
                                    subscriptSizing="dynamic"
                                    class="w-40"
                                >
                                    <mat-label>Column</mat-label>
                                    <!-- form-allow: a table-cell select of a repeating row (one filter) --><mat-select
                                        formControlName="field"
                                    >
                                        @for (c of columnOptions(i); track c.value) {
                                            <mat-option [value]="c.value">{{ c.label }}</mat-option>
                                        }
                                    </mat-select>
                                </mat-form-field>
                                <!-- form-allow: a table-cell select of a repeating row (one filter) --><mat-form-field
                                    subscriptSizing="dynamic"
                                    class="w-36"
                                >
                                    <mat-label>Operator</mat-label>
                                    <!-- form-allow: a table-cell select of a repeating row (one filter) --><mat-select
                                        formControlName="op"
                                    >
                                        @for (o of ops; track o.op) {
                                            <mat-option [value]="o.op">{{ o.label }}</mat-option>
                                        }
                                    </mat-select>
                                </mat-form-field>
                                @if (arity(fg) !== 0) {
                                    <!-- form-allow: a table-cell field of a repeating row (one filter) --><mat-form-field
                                        subscriptSizing="dynamic"
                                        class="w-40"
                                    >
                                        <mat-label>Value</mat-label>
                                        <input matInput formControlName="value" />
                                    </mat-form-field>
                                }
                                <button
                                    mat-icon-button
                                    type="button"
                                    [attr.aria-label]="'Remove filter ' + (j + 1) + ' of factor ' + (i + 1)"
                                    (click)="removeFilter(i, j)"
                                >
                                    <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                                </button>
                            </div>
                        }
                        <button mat-button type="button" (click)="addFilter(i)">Add filter</button>
                    </div>

                    <div class="mt-2">
                        <button
                            type="button"
                            mat-button
                            [attr.aria-expanded]="whenOpen(g)"
                            [attr.aria-controls]="'risk-when-' + i"
                            [attr.aria-describedby]="whenShowsErrors(g) ? 'risk-when-err-' + i : null"
                            (click)="toggleWhen(g)"
                            data-testid="when-toggle"
                        >
                            <mat-icon
                                [svgIcon]="
                                    whenOpen(g) ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'
                                "
                            ></mat-icon>
                            <span class="ml-1">Advanced filter (condition tree)</span>
                            @if (whenCount(g); as n) {
                                <span class="text-secondary ml-1 text-xs">({{ n }})</span>
                            }
                        </button>
                        @if (whenShowsErrors(g)) {
                            <ul
                                class="text-warn mt-1 list-none text-xs"
                                role="alert"
                                [id]="'risk-when-err-' + i"
                                data-testid="when-error"
                            >
                                @for (m of whenIssues(g); track m) {
                                    <li>{{ m }}</li>
                                }
                            </ul>
                        }
                        <div
                            [id]="'risk-when-' + i"
                            role="region"
                            [attr.aria-label]="'Advanced filter of factor ' + (i + 1)"
                            [hidden]="!whenOpen(g)"
                        >
                            @if (whenIsOpaque(g)) {
                                <p class="text-secondary text-xs" data-testid="when-opaque">
                                    This factor has an advanced filter authored outside this editor. It is kept as is on
                                    save and cannot be edited here.
                                </p>
                            } @else {
                                <p class="text-secondary mb-2 text-xs">
                                    The flat filters above are a plain AND of simple tests. Use the advanced filter for
                                    OR, NOT, nested groups, case-insensitive text, a pattern match or a comparison
                                    between two columns. Both apply together: a row must pass the flat filters and the
                                    advanced filter.
                                </p>
                                @if (whenOpen(g)) {
                                    <inspecto-query-condition-group
                                        [group]="whenGroup(g)"
                                        [columns]="columnMeta(i)"
                                        [root]="true"
                                        (changed)="whenChanged(g)"
                                    />
                                }
                            }
                        </div>
                    </div>

                    <div class="mt-2 flex gap-1">
                        <button
                            mat-icon-button
                            type="button"
                            [attr.aria-label]="'Move factor ' + (i + 1) + ' up'"
                            [disabled]="i === 0"
                            (click)="move(i, -1)"
                        >
                            <mat-icon svgIcon="heroicons_outline:arrow-up"></mat-icon>
                        </button>
                        <button
                            mat-icon-button
                            type="button"
                            [attr.aria-label]="'Move factor ' + (i + 1) + ' down'"
                            [disabled]="i === rows.length - 1"
                            (click)="move(i, 1)"
                        >
                            <mat-icon svgIcon="heroicons_outline:arrow-down"></mat-icon>
                        </button>
                        <button
                            mat-icon-button
                            type="button"
                            [attr.aria-label]="'Remove factor ' + (i + 1)"
                            (click)="remove(i)"
                        >
                            <mat-icon svgIcon="heroicons_outline:trash"></mat-icon>
                        </button>
                    </div>
                </fieldset>
            }
            @if (rows.length === 0 && rows.touched) {
                <p class="text-warn text-xs" role="alert">Add at least one factor.</p>
            }
            <div>
                <button mat-stroked-button type="button" [disabled]="rows.length >= maxFactors" (click)="add()">
                    Add factor
                </button>
            </div>
        </div>
    `,
})
export class RiskScoreFactorsComponent implements OnInit {
    private fb = inject(FormBuilder);
    private destroyRef = inject(DestroyRef);

    /** Datasets a factor may read — the host passes only those with a readable Schema (D-RP8). */
    readonly datasetOptions = input<PickerOption[]>([]);
    /** Column source for a Dataset id; a failure resolves to `[]`. */
    @Input() columnsFor: (dataset: string) => Promise<string[]> = async () => [];
    /** Typed columns for the advanced filter; defaults to the names of {@link columnsFor} read as text. */
    @Input() columnMetaFor?: (dataset: string) => Promise<ColumnMeta[]>;
    @Input() set factors(v: RiskFactorDraft[]) {
        this.rows.clear();
        v.forEach((f) => this.rows.push(this.row(f)));
        this.rowErrors.set({});
        v.forEach((f) => this.loadColumns(f.dataset));
    }

    readonly ops = RISK_FILTER_OPS;
    readonly maxFactors = MAX_FACTORS;
    readonly maxEvidence = MAX_EVIDENCE;
    readonly rows: FormArray<FormGroup> = this.fb.array<FormGroup>([]);
    /** A server refusal per row (index = the server's `factors[i]`), shown verbatim. */
    readonly rowErrors = signal<Record<number, string>>({});
    private readonly columns = signal<Record<string, ColumnMeta[]>>({});

    ngOnInit(): void {
        for (const g of this.rows.controls) this.loadColumns(String(g.value['dataset'] ?? ''));
    }

    private row(f: RiskFactorDraft): FormGroup {
        const num = Validators.pattern(/^\s*-?\d+(\.\d+)?\s*$/);
        const g = this.fb.group({
            id: [f.id, [Validators.required, Validators.pattern(/^[A-Za-z0-9_]+$/)]],
            label: [f.label],
            dataset: [f.dataset, Validators.required],
            key: [f.key, Validators.required],
            measure: [f.measure, Validators.required],
            weight: [f.weight, [Validators.required, num]],
            cap: [f.cap, Validators.pattern(/^\s*\d+(\.\d+)?\s*$/)],
            when: [
                f.when && isEditableGroup(f.when) ? f.when : emptyGroup('AND'),
                (c: { value: unknown }) => (validateGroup(c.value as ConditionGroup).length ? { when: true } : null),
            ],
            extra: [{ ...(f.extra ?? {}) }],
            whenOpen: [!!f.when?.items.length],
            evidence: [
                [...f.evidence],
                (c: { value: unknown }) =>
                    Array.isArray(c.value) && c.value.length > MAX_EVIDENCE ? { max: true } : null,
            ],
            filters: this.fb.array(f.filters.map((x) => this.filter(x))),
        });
        g.controls['dataset'].valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((ds) => {
            g.controls['key'].setValue('');
            g.controls['evidence'].setValue([]);
            this.loadColumns(String(ds ?? ''));
        });
        return g;
    }

    private filter(x: RiskFilterDraft): FormGroup {
        return this.fb.group({ field: [x.field, Validators.required], op: [x.op], value: [x.value] });
    }

    private loadColumns(ds: string): void {
        if (!ds || this.columns()[ds]) return;
        const metas = this.columnMetaFor
            ? this.columnMetaFor(ds)
            : this.columnsFor(ds).then((cols) => cols.map((name): ColumnMeta => ({ name, type: 'string' })));
        metas.then((cols) => {
            if (cols.length) this.columns.update((m) => ({ ...m, [ds]: cols }));
        });
    }

    /**
     * A card row's error line, once the control was left (or validate() ran): the message of the first of its
     * validators that fails, in the order given. A row has no red outline, so every validator needs its line, or Save
     * refuses with nothing on screen. A field the server refused (setRowError) points at the card's message rather
     * than repeating it; any edit re-runs the validators and clears it. A local error wins: it is the one to fix first.
     */
    fieldError(g: FormGroup, name: string, messages: Record<string, string>): string {
        const c = g.controls[name];
        if (c.touched) for (const [error, message] of Object.entries(messages)) if (c.hasError(error)) return message;
        return c.hasError('server') ? 'Refused: see the message above.' : '';
    }

    columnOptions(i: number): PickerOption[] {
        return this.columnMeta(i).map((c) => ({ value: c.name, label: c.name }));
    }

    columnMeta(i: number): ColumnMeta[] {
        return this.columns()[String(this.rows.at(i).value['dataset'] ?? '')] ?? [];
    }

    whenOpen(g: FormGroup): boolean {
        return g.controls['whenOpen'].value === true;
    }

    toggleWhen(g: FormGroup): void {
        g.controls['whenOpen'].setValue(!this.whenOpen(g));
    }

    whenGroup(g: FormGroup): ConditionGroup {
        return g.controls['when'].value as ConditionGroup;
    }

    /** A `when` kept verbatim because the editor cannot model its shape. */
    whenIsOpaque(g: FormGroup): boolean {
        return !!(g.controls['extra'].value as Record<string, unknown>)['when'];
    }

    /** Refusal reasons of the factor's condition tree, empty when it would save. */
    whenIssues(g: FormGroup): string[] {
        return validateGroup(this.whenGroup(g));
    }

    whenShowsErrors(g: FormGroup): boolean {
        return g.controls['when'].touched && this.whenIssues(g).length > 0;
    }

    /** How many top-level conditions the tree holds, for the disclosure's hint. */
    whenCount(g: FormGroup): number {
        return this.whenGroup(g).items.length;
    }

    /** The editor mutates the tree in place and emits `changed`: re-run the validator and dirty the form. */
    whenChanged(g: FormGroup): void {
        g.controls['when'].updateValueAndValidity();
        g.controls['when'].markAsTouched();
        g.controls['when'].markAsDirty();
        this.rows.markAsDirty();
    }

    filtersOf(i: number): FormArray<FormGroup> {
        return this.rows.at(i).get('filters') as FormArray<FormGroup>;
    }

    arity(fg: FormGroup): 0 | 1 | 'list' {
        return RISK_FILTER_OPS.find((o) => o.op === fg.value['op'])?.arity ?? 1;
    }

    add(): void {
        this.rows.push(this.row(emptyFactor(this.rows.length + 1)));
        this.rows.markAsDirty();
    }

    remove(i: number): void {
        this.rows.removeAt(i);
        this.rows.markAsDirty();
        this.clearServerMarks();
    }

    move(i: number, by: -1 | 1): void {
        const g = this.rows.at(i);
        this.rows.removeAt(i);
        this.rows.insert(i + by, g);
        this.rows.markAsDirty();
        this.clearServerMarks();
    }

    /** The card messages are keyed by position, so a remove or move drops them - and the row marks that point at them. */
    private clearServerMarks(): void {
        this.rowErrors.set({});
        for (const g of this.rows.controls)
            for (const c of Object.values(g.controls)) if (c.hasError('server')) c.updateValueAndValidity({ emitEvent: false });
    }

    addFilter(i: number): void {
        this.filtersOf(i).push(this.filter({ field: '', op: '=', value: '' }));
        this.rows.markAsDirty();
    }

    removeFilter(i: number, j: number): void {
        this.filtersOf(i).removeAt(j);
        this.rows.markAsDirty();
    }

    /** Place a server refusal on row `i`; a named field also turns that control red. */
    setRowError(i: number, message: string, field?: string): void {
        this.rowErrors.update((m) => ({ ...m, [i]: message }));
        const c = field ? this.rows.at(i)?.get(field) : null;
        if (c) {
            c.setErrors({ server: true });
            c.markAsTouched();
        }
    }

    validate(): boolean {
        this.rows.markAllAsTouched();
        // An error renders only where its control renders: open the advanced filter that blocks the save.
        for (const g of this.rows.controls) if (g.controls['when'].invalid) g.controls['whenOpen'].setValue(true);
        return this.rows.valid && this.rows.length > 0;
    }

    isDirty(): boolean {
        return this.rows.dirty;
    }

    value(): RiskFactorDraft[] {
        return this.rows.controls.map((g) => {
            const v = g.getRawValue();
            return {
                id: String(v.id ?? ''),
                label: String(v.label ?? ''),
                dataset: String(v.dataset ?? ''),
                key: String(v.key ?? ''),
                measure: String(v.measure ?? ''),
                weight: String(v.weight ?? ''),
                cap: String(v.cap ?? ''),
                evidence: Array.isArray(v.evidence) ? v.evidence.map(String) : [],
                when: v.when as ConditionGroup,
                extra: v.extra as Record<string, unknown>,
                filters: (v.filters as RiskFilterDraft[]).map((x) => ({
                    field: String(x.field ?? ''),
                    op: String(x.op ?? '='),
                    value: String(x.value ?? ''),
                })),
            };
        });
    }
}
