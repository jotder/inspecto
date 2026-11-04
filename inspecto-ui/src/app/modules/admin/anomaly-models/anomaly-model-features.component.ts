import { ChangeDetectionStrategy, Component, Input, OnInit, inject, input, signal } from '@angular/core';
import { AbstractControl, FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import {
    ANOMALY_DIRECTIONS,
    AnomalyFeatureDraft,
    FEATURE_FILTER_OPS,
    FeatureFilterDraft,
    MAX_FEATURES,
    NO_VALUE_OPS,
    emptyFeature,
    emptyFilter,
} from 'app/inspecto/anomaly/anomaly-model-form';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { measureError } from 'app/inspecto/query/measure-grammar';
import { ColumnMeta } from 'app/inspecto/query/query-types';

const measureValidator = (c: AbstractControl) => {
    const e = measureError(String(c.value ?? ''));
    return e ? { measure: e } : null;
};

/** `AnomalyModel` refuses `unit <= 0`; blank is allowed (no key = the default floor of 1). */
const unitValidator = (c: AbstractControl) => {
    const t = String(c.value ?? '').trim();
    return t === '' || (/^\d*\.?\d+$/.test(t) && Number(t) > 0) ? null : { unit: true };
};

/** TIMESTAMP / DATE columns only — `requireStorable` refuses any other `time` column. */
const isTimeType = (t: string): boolean => /time|date/i.test(t);

/**
 * The Feature row editor of the Anomaly Model form (design §6 / §13.1): one card per Feature with the same Dataset
 * picker and `count | agg(column)` Measure grammar the Risk Score Factor editor uses. Presentational: the host
 * supplies the Dataset choices and a column source, reads {@link value} on save and places server refusals through
 * {@link setRowError}. Each row also edits the Feature's `filters` (`{field, op, value}`, ANDed before the Measure) and
 * its `unit` (the absolute spread floor); keys the editor does not model ride through each row untouched.
 */
@Component({
    selector: 'app-anomaly-model-features',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatIconModule, InspectoOptionPickerComponent, PenFieldComponent],
    template: `
        <div class="flex flex-col gap-3">
            @for (g of rows.controls; track g; let i = $index) {
                <fieldset class="rounded-lg border p-3" [formGroup]="g" [attr.aria-label]="'Feature ' + (i + 1)">
                    <legend class="px-1 text-sm font-semibold">Feature {{ i + 1 }}</legend>
                    @if (rowErrors()[i]; as e) {
                        <p class="text-warn mb-2 text-xs" role="alert" data-testid="feature-error">{{ e }}</p>
                    }
                    <div class="grid grid-cols-1 gap-x-8 sm:grid-cols-2">
                        <inspecto-pen-field
                            label="Id"
                            formControlName="id"
                            [required]="true"
                            [error]="
                                fieldError(g.controls['id'], {
                                    required: 'Id is required',
                                    pattern: 'Letters, digits and _ only',
                                })
                            "
                        ></inspecto-pen-field>
                        <inspecto-pen-field
                            label="Label"
                            formControlName="label"
                            [error]="fieldError(g.controls['label'], {})"
                        ></inspecto-pen-field>
                        <inspecto-option-picker
                            label="Dataset"
                            formControlName="dataset"
                            [options]="datasetOptions()"
                            help="Pick a Dataset with a readable Schema."
                        ></inspecto-option-picker>
                        @if (unreadable(i)) {
                            <p class="text-warn text-xs sm:col-span-2" role="alert" data-testid="no-schema">
                                This Dataset has no readable Schema — choose another.
                            </p>
                        }
                        <inspecto-option-picker
                            label="Entity key column"
                            formControlName="key"
                            [options]="columnOptions(i)"
                        ></inspecto-option-picker>
                        <inspecto-option-picker
                            label="Time column"
                            formControlName="time"
                            [options]="timeOptions(i)"
                            help="A TIMESTAMP or DATE column; rows are bucketed by day (UTC)."
                        ></inspecto-option-picker>
                        <inspecto-pen-field
                            label="Measure"
                            formControlName="measure"
                            placeholder="count or sum(field)"
                            [mono]="true"
                            [required]="true"
                            [error]="
                                fieldError(g.controls['measure'], { required: 'Measure is required', measure: '' })
                            "
                        ></inspecto-pen-field>
                        <inspecto-option-picker
                            label="Direction"
                            formControlName="direction"
                            [options]="directions"
                            help="Up: higher than usual is unusual. Down: lower than usual is unusual."
                        ></inspecto-option-picker>
                        <inspecto-pen-field
                            label="Weight"
                            formControlName="weight"
                            [required]="true"
                            [error]="
                                fieldError(g.controls['weight'], {
                                    required: 'Weight is required',
                                    pattern: 'Weight must be a number above 0',
                                })
                            "
                        ></inspecto-pen-field>
                        <inspecto-pen-field
                            label="Spread floor (unit)"
                            formControlName="unit"
                            placeholder="1"
                            help="The smallest spread a change is measured against. Blank = 1."
                            [error]="fieldError(g.controls['unit'], { unit: 'Spread floor must be a number above 0' })"
                        ></inspecto-pen-field>
                    </div>
                    <div class="mt-3" formArrayName="filters">
                        <p class="text-sm font-semibold">Filters</p>
                        <p class="text-secondary text-xs">Only rows matching every filter are measured.</p>
                        @for (fg of filtersOf(g).controls; track fg; let j = $index) {
                            <div
                                class="mt-2 grid grid-cols-1 items-start gap-x-4 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]"
                                role="group"
                                [formGroupName]="j"
                                [attr.aria-label]="'Feature ' + (i + 1) + ' filter ' + (j + 1)"
                            >
                                <inspecto-option-picker
                                    label="Column"
                                    formControlName="field"
                                    [options]="columnOptions(i)"
                                ></inspecto-option-picker>
                                <inspecto-option-picker
                                    label="Operator"
                                    formControlName="op"
                                    [options]="filterOps"
                                ></inspecto-option-picker>
                                <!-- Value and the remove button go on the next line, so Operator keeps half the width ("equals (=)" clipped at 1024 px) -->
                                @if (takesValue(fg)) {
                                    <inspecto-pen-field
                                        class="sm:col-span-2"
                                        label="Value"
                                        formControlName="value"
                                        [placeholder]="fg.value['op'] === 'in' ? 'a, b, c' : ''"
                                        [error]="fieldError(fg.controls['value'], { required: 'Value is required' })"
                                    ></inspecto-pen-field>
                                } @else {
                                    <span class="sm:col-span-2"></span>
                                }
                                <button
                                    mat-icon-button
                                    type="button"
                                    class="self-center"
                                    [attr.aria-label]="'Remove filter ' + (j + 1) + ' of feature ' + (i + 1)"
                                    (click)="removeFilter(g, j)"
                                >
                                    <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                                </button>
                                @if (filterMissing(fg).length) {
                                    <p class="text-warn text-xs sm:col-span-3" role="alert">
                                        Choose: {{ filterMissing(fg).join(', ') }}
                                    </p>
                                }
                            </div>
                        }
                        <button
                            mat-stroked-button
                            type="button"
                            class="mt-2"
                            [attr.aria-label]="'Add filter to feature ' + (i + 1)"
                            (click)="addFilter(g)"
                        >
                            Add filter
                        </button>
                    </div>
                    @if (missing(g).length) {
                        <p class="text-warn mt-1 text-xs" role="alert">Choose: {{ missing(g).join(', ') }}</p>
                    }
                    <div class="mt-2 flex justify-end">
                        <button
                            mat-stroked-button
                            type="button"
                            [disabled]="rows.length === 1"
                            [attr.aria-label]="'Remove feature ' + (i + 1)"
                            (click)="remove(i)"
                        >
                            Remove
                        </button>
                    </div>
                </fieldset>
            }
            <div>
                <button mat-stroked-button type="button" [disabled]="rows.length >= maxFeatures" (click)="add()">
                    Add Feature
                </button>
                <span class="text-secondary ml-2 text-xs">Up to {{ maxFeatures }}.</span>
            </div>
        </div>
    `,
})
export class AnomalyModelFeaturesComponent implements OnInit {
    private fb = inject(FormBuilder);

    readonly datasetOptions = input<PickerOption[]>([]);
    /** Column source per Dataset id — the same `rowsApi.columns` read `requireStorable` needs. */
    @Input() columnMetaFor: (dataset: string) => Promise<ColumnMeta[]> = async () => [];
    @Input() set features(v: AnomalyFeatureDraft[]) {
        this.rows.clear();
        for (const f of v) this.rows.push(this.row(f));
        this.rows.markAsPristine();
    }

    readonly maxFeatures = MAX_FEATURES;
    readonly directions: PickerOption[] = ANOMALY_DIRECTIONS;
    readonly filterOps: PickerOption[] = FEATURE_FILTER_OPS;
    readonly rows: FormArray<FormGroup> = this.fb.array<FormGroup>([]);
    readonly rowErrors = signal<Record<number, string>>({});
    /** Pickers render their own error only on interaction; a submit shows this line instead (angular-ui §4). */
    readonly submitted = signal(false);
    private readonly columns = signal<Record<string, ColumnMeta[]>>({});

    ngOnInit(): void {
        for (const g of this.rows.controls) this.loadColumns(String(g.value['dataset'] ?? ''));
    }

    private row(f: AnomalyFeatureDraft): FormGroup {
        const g = this.fb.group({
            id: [f.id, [Validators.required, Validators.pattern(/^[A-Za-z0-9_]+$/)]],
            label: [f.label],
            dataset: [f.dataset, Validators.required],
            key: [f.key, Validators.required],
            time: [f.time, Validators.required],
            measure: [f.measure, [Validators.required, measureValidator]],
            direction: [f.direction || 'up', Validators.required],
            weight: [f.weight, [Validators.required, Validators.pattern(/^\s*\d*\.?\d+\s*$/)]],
            unit: [f.unit, unitValidator],
            storedUnit: [f.storedUnit],
            filters: this.fb.array<FormGroup>(f.filters.map((d) => this.filterRow(d))),
            extra: [{ ...(f.extra ?? {}) }],
        });
        g.controls['dataset'].valueChanges.subscribe((d) => this.loadColumns(String(d ?? '')));
        return g;
    }

    /** One filter row; `value` is required unless the op takes none (isNull / notNull). */
    private filterRow(d: FeatureFilterDraft): FormGroup {
        const g = this.fb.group({
            field: [d.field, Validators.required],
            op: [d.op, Validators.required],
            value: [d.value],
            raw: [d.raw],
        });
        const sync = (op: unknown) => {
            const v = g.controls['value'];
            v.setValidators(NO_VALUE_OPS.includes(String(op ?? '')) ? null : Validators.required);
            v.updateValueAndValidity({ emitEvent: false });
        };
        sync(d.op);
        g.controls['op'].valueChanges.subscribe(sync);
        return g;
    }

    filtersOf(g: FormGroup): FormArray<FormGroup> {
        return g.controls['filters'] as FormArray<FormGroup>;
    }

    takesValue(fg: FormGroup): boolean {
        return !NO_VALUE_OPS.includes(String(fg.value['op'] ?? ''));
    }

    /** Filter pickers left blank, named for the submit-time alert line (pickers show no error on submit). */
    filterMissing(fg: FormGroup): string[] {
        if (!this.submitted()) return [];
        const names: Record<string, string> = { field: 'Column', op: 'Operator' };
        return Object.keys(names)
            .filter((k) => !fg.controls[k].value)
            .map((k) => names[k]);
    }

    addFilter(g: FormGroup): void {
        this.filtersOf(g).push(this.filterRow(emptyFilter()));
        this.rows.markAsDirty();
    }

    removeFilter(g: FormGroup, j: number): void {
        this.filtersOf(g).removeAt(j);
        this.rows.markAsDirty();
    }

    private loadColumns(dataset: string): void {
        if (!dataset || this.columns()[dataset]) return;
        this.columnMetaFor(dataset).then((cols) => this.columns.update((m) => ({ ...m, [dataset]: cols })));
    }

    /** True once the row's Dataset was probed and offered no column. */
    unreadable(i: number): boolean {
        const ds = String(this.rows.at(i).value['dataset'] ?? '');
        return !!ds && this.columns()[ds]?.length === 0;
    }

    columnOptions(i: number): PickerOption[] {
        const ds = String(this.rows.at(i).value['dataset'] ?? '');
        return (this.columns()[ds] ?? []).map((c) => ({ value: c.name, label: `${c.name} (${c.type})` }));
    }

    timeOptions(i: number): PickerOption[] {
        const ds = String(this.rows.at(i).value['dataset'] ?? '');
        return (this.columns()[ds] ?? [])
            .filter((c) => isTimeType(String(c.type)))
            .map((c) => ({ value: c.name, label: `${c.name} (${c.type})` }));
    }

    /** Required pickers left blank, named for the submit-time alert line. */
    missing(g: FormGroup): string[] {
        if (!this.submitted()) return [];
        const names: Record<string, string> = { dataset: 'Dataset', key: 'Entity key column', time: 'Time column' };
        return Object.keys(names)
            .filter((k) => !g.controls[k].value)
            .map((k) => names[k]);
    }

    add(): void {
        this.rows.push(this.row(emptyFeature(this.rows.length + 1)));
        this.rows.markAsDirty();
    }

    remove(i: number): void {
        this.rows.removeAt(i);
        this.rows.markAsDirty();
        this.rowErrors.set({});
        // The row pointers ("see the message above") go with the messages they point at: re-validating drops a
        // `server` mark, which no validator sets.
        for (const g of this.rows.controls)
            for (const c of Object.values(g.controls)) if (c.hasError('server')) c.updateValueAndValidity({ emitEvent: false });
    }

    isDirty(): boolean {
        return this.rows.dirty;
    }

    validate(): boolean {
        this.submitted.set(true);
        this.rows.markAllAsTouched();
        return this.rows.valid;
    }

    value(): AnomalyFeatureDraft[] {
        return this.rows.controls.map((g) => {
            const v = g.getRawValue();
            return {
                id: String(v.id ?? ''),
                label: String(v.label ?? ''),
                dataset: String(v.dataset ?? ''),
                key: String(v.key ?? ''),
                time: String(v.time ?? ''),
                measure: String(v.measure ?? ''),
                direction: String(v.direction ?? 'up'),
                weight: String(v.weight ?? ''),
                filters: ((v.filters ?? []) as Record<string, unknown>[]).map((f) => ({
                    field: String(f['field'] ?? ''),
                    op: String(f['op'] ?? ''),
                    value: String(f['value'] ?? ''),
                    raw: (f['raw'] ?? {}) as Record<string, unknown>,
                })),
                unit: String(v.unit ?? ''),
                storedUnit: v.storedUnit,
                extra: (v.extra ?? {}) as Record<string, unknown>,
            };
        });
    }

    /**
     * A typed row's error line once it was left or the form validated: the first failed check's message (`''` = the
     * error's own text, as the Measure grammar gives it), else a pointer to a server refusal placed by
     * {@link setRowError} (a boxed field only turned red for it; a row has no outline, so it says so).
     */
    fieldError(c: AbstractControl, messages: Record<string, string>): string {
        if (!c.touched) return '';
        for (const [k, m] of Object.entries(messages)) if (c.hasError(k)) return m || String(c.getError(k));
        return c.hasError('server') ? 'Refused: see the message above.' : '';
    }

    /** Show a server refusal on row `i`, and on its named field when the editor has one. */
    setRowError(i: number, message: string, field?: string): void {
        this.rowErrors.update((m) => ({ ...m, [i]: message }));
        const c = field ? this.rows.at(i)?.get(field) : null;
        if (c) {
            c.setErrors({ server: message });
            c.markAsTouched();
        }
    }
}
