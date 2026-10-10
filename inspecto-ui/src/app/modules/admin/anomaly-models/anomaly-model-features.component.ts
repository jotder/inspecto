import { ChangeDetectionStrategy, Component, Input, OnInit, inject, input, signal } from '@angular/core';
import { AbstractControl, FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import {
    ANOMALY_DIRECTIONS,
    AnomalyFeatureDraft,
    MAX_FEATURES,
    emptyFeature,
} from 'app/inspecto/anomaly/anomaly-model-form';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { measureError } from 'app/inspecto/query/measure-grammar';
import { ColumnMeta } from 'app/inspecto/query/query-types';

const measureValidator = (c: AbstractControl) => {
    const e = measureError(String(c.value ?? ''));
    return e ? { measure: e } : null;
};

/** TIMESTAMP / DATE columns only — `requireStorable` refuses any other `time` column. */
const isTimeType = (t: string): boolean => /time|date/i.test(t);

/**
 * The Feature row editor of the Anomaly Model form (design §6 / §13.1): one card per Feature with the same Dataset
 * picker and `count | agg(column)` Measure grammar the Risk Score Factor editor uses. Presentational: the host
 * supplies the Dataset choices and a column source, reads {@link value} on save and places server refusals through
 * {@link setRowError}. Keys the editor does not model (`filters`, `unit`) ride through each row untouched.
 */
@Component({
    selector: 'app-anomaly-model-features',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        InspectoOptionPickerComponent,
    ],
    template: `
        <div class="flex flex-col gap-3">
            @for (g of rows.controls; track g; let i = $index) {
                <fieldset class="rounded-lg border p-3" [formGroup]="g" [attr.aria-label]="'Feature ' + (i + 1)">
                    <legend class="px-1 text-sm font-semibold">Feature {{ i + 1 }}</legend>
                    @if (rowErrors()[i]; as e) {
                        <p class="text-warn mb-2 text-xs" role="alert" data-testid="feature-error">{{ e }}</p>
                    }
                    <div class="grid grid-cols-1 gap-x-3 sm:grid-cols-3">
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Id</mat-label>
                            <input matInput formControlName="id" />
                            @if (g.controls['id'].hasError('required')) {
                                <mat-error>Id is required</mat-error>
                            }
                            @if (g.controls['id'].hasError('pattern')) {
                                <mat-error>Letters, digits and _ only</mat-error>
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
                        <inspecto-option-picker
                            label="Time column"
                            formControlName="time"
                            [options]="timeOptions(i)"
                            help="A TIMESTAMP or DATE column; rows are bucketed by day (UTC)."
                        ></inspecto-option-picker>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Measure</mat-label>
                            <input matInput formControlName="measure" placeholder="count or sum(field)" />
                            @if (g.controls['measure'].hasError('required')) {
                                <mat-error>Measure is required</mat-error>
                            } @else if (g.controls['measure'].hasError('measure')) {
                                <mat-error>{{ g.controls['measure'].getError('measure') }}</mat-error>
                            }
                        </mat-form-field>
                        <inspecto-option-picker
                            label="Direction"
                            formControlName="direction"
                            [options]="directions"
                        ></inspecto-option-picker>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Weight</mat-label>
                            <input matInput formControlName="weight" inputmode="decimal" />
                            @if (g.controls['weight'].hasError('required')) {
                                <mat-error>Weight is required</mat-error>
                            } @else if (g.controls['weight'].hasError('pattern')) {
                                <mat-error>Weight must be a number above 0</mat-error>
                            }
                        </mat-form-field>
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
            extra: [{ ...(f.extra ?? {}) }],
        });
        g.controls['dataset'].valueChanges.subscribe((d) => this.loadColumns(String(d ?? '')));
        return g;
    }

    private loadColumns(dataset: string): void {
        if (!dataset || this.columns()[dataset]) return;
        this.columnMetaFor(dataset).then((cols) => this.columns.update((m) => ({ ...m, [dataset]: cols })));
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
                extra: (v.extra ?? {}) as Record<string, unknown>,
            };
        });
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
