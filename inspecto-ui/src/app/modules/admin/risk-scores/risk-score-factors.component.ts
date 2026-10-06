import { ChangeDetectionStrategy, Component, DestroyRef, Input, OnInit, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import {
    MAX_EVIDENCE,
    MAX_FACTORS,
    RISK_FILTER_OPS,
    RiskFactorDraft,
    RiskFilterDraft,
    emptyFactor,
} from 'app/inspecto/risk/risk-score-form';

/**
 * The Factor row editor of the Risk Score form (spec §3, D-RP7 (b) bespoke cards). One card per factor, with a
 * flat filter list — a factor's `filters` are an AND of `{field, op, value}`, so the Query panel's nested
 * AND/OR condition-group editor would author shapes `MeasureCompiler` cannot take (see the spec's spike note).
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
    ],
    template: `
        <div class="flex flex-col gap-3">
            @for (g of rows.controls; track g; let i = $index) {
                <fieldset class="rounded-lg border p-3" [formGroup]="g" [attr.aria-label]="'Factor ' + (i + 1)">
                    <legend class="px-1 text-sm font-semibold">Factor {{ i + 1 }}</legend>
                    @if (rowErrors()[i]; as e) {
                        <p class="text-warn mb-2 text-xs" role="alert" data-testid="factor-error">{{ e }}</p>
                    }
                    <div class="grid grid-cols-1 gap-x-3 sm:grid-cols-3">
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Id</mat-label>
                            <input matInput formControlName="id" />
                            @if (g.controls['id'].hasError('required')) {
                                <mat-error>Id is required</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Label</mat-label>
                            <input matInput formControlName="label" />
                        </mat-form-field>
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
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Measure</mat-label>
                            <input matInput formControlName="measure" placeholder="count or sum(field)" />
                            @if (g.controls['measure'].hasError('required')) {
                                <mat-error>Measure is required</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Weight</mat-label>
                            <input matInput formControlName="weight" inputmode="decimal" />
                            <mat-hint>Negative = protective</mat-hint>
                            @if (g.controls['weight'].hasError('pattern')) {
                                <mat-error>Weight must be a number</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Cap</mat-label>
                            <input matInput formControlName="cap" inputmode="decimal" />
                            @if (g.controls['cap'].hasError('pattern')) {
                                <mat-error>Cap must be a number ≥ 0</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic" class="sm:col-span-2">
                            <mat-label>Evidence columns (up to {{ maxEvidence }})</mat-label>
                            <mat-select formControlName="evidence" multiple>
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
                                <mat-form-field subscriptSizing="dynamic" class="w-40">
                                    <mat-label>Column</mat-label>
                                    <mat-select formControlName="field">
                                        @for (c of columnOptions(i); track c.value) {
                                            <mat-option [value]="c.value">{{ c.label }}</mat-option>
                                        }
                                    </mat-select>
                                </mat-form-field>
                                <mat-form-field subscriptSizing="dynamic" class="w-36">
                                    <mat-label>Operator</mat-label>
                                    <mat-select formControlName="op">
                                        @for (o of ops; track o.op) {
                                            <mat-option [value]="o.op">{{ o.label }}</mat-option>
                                        }
                                    </mat-select>
                                </mat-form-field>
                                @if (arity(fg) !== 0) {
                                    <mat-form-field subscriptSizing="dynamic" class="w-40">
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
    private readonly columns = signal<Record<string, PickerOption[]>>({});

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
        this.columnsFor(ds).then((cols) => {
            if (cols.length) this.columns.update((m) => ({ ...m, [ds]: cols.map((c) => ({ value: c, label: c })) }));
        });
    }

    columnOptions(i: number): PickerOption[] {
        return this.columns()[String(this.rows.at(i).value['dataset'] ?? '')] ?? [];
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
        this.rowErrors.set({});
    }

    move(i: number, by: -1 | 1): void {
        const g = this.rows.at(i);
        this.rows.removeAt(i);
        this.rows.insert(i + by, g);
        this.rows.markAsDirty();
        this.rowErrors.set({});
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
                filters: (v.filters as RiskFilterDraft[]).map((x) => ({
                    field: String(x.field ?? ''),
                    op: String(x.op ?? '='),
                    value: String(x.value ?? ''),
                })),
            };
        });
    }
}
