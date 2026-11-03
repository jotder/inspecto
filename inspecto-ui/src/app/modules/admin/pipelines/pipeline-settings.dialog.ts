import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { InspectoSampleValueDirective } from 'app/inspecto/components/sample-value.directive';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import type { PipelineReferenceSettings, PipelineSettings } from 'app/inspecto/api/pipelines.service';

export interface PipelineSettingsData {
    id: string;
    settings: PipelineSettings;
}

/**
 * D8 (pipeline-graph backlog): the pipeline-level `produces`/`reference` block finally gets an editor
 * surface. Until this dialog, `reference: {load: upsert|scd2, key: [...]}` could only be authored by
 * hand-editing the `.toon` file — the flat graph carries it as opaque passthrough and there is no
 * node to put it on (a Reference dataset is a property of the whole pipeline, not one sink).
 */
@Component({
    selector: 'app-pipeline-settings-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        PenFieldComponent,
        InspectoAlertComponent,
        InspectoDialogResizeDirective,
        InspectoOptionPickerComponent,
        InspectoSampleValueDirective,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title inspectoDialogResize>Pipeline settings</h2>
        <mat-dialog-content>
            <inspecto-alert variant="info" title="What this produces">
                A Stream is the default output. A Reference is a versioned dataset other pipelines' enrichments can bind
                to by name — pick a load mode to make one.
            </inspecto-alert>
            <form [formGroup]="form" (ngSubmit)="save()" class="mt-4 flex flex-col gap-3">
                <inspecto-pen-field
                    label="Description"
                    [multiline]="true"
                    help="Optional — leaving it blank clears the stored description."
                    formControlName="description"
                ></inspecto-pen-field>
                <inspecto-option-picker
                    class="block w-full"
                    label="Produces"
                    formControlName="produces"
                    cdkFocusInitial
                    [options]="PRODUCES"
                />
                @if (form.controls.produces.value === 'reference') {
                    <inspecto-option-picker
                        class="block w-full"
                        label="Load mode"
                        formControlName="load"
                        [options]="LOAD_MODES"
                    />
                    <inspecto-pen-field
                        label="Key columns"
                        placeholder="e.g. msisdn, event_date"
                        [help]="
                            form.controls.load.value !== 'replace'
                                ? 'Comma-separated. Required for upsert/scd2 — this is the identity the dataset dedupes on.'
                                : ''
                        "
                        formControlName="key"
                        [error]="keyError()"
                    ></inspecto-pen-field>
                    @if (form.controls.load.value !== 'replace') {
                        <inspecto-pen-field
                            label="Order by column"
                            placeholder="e.g. updated_at"
                            help="Optional. Within one batch, the row with the greatest value wins for each key."
                            formControlName="orderBy"
                        ></inspecto-pen-field>
                        <inspecto-pen-field
                            label="Delete marker column"
                            placeholder="e.g. op"
                            help="Optional. A row whose marker matches a delete value removes its key. The marker is not stored."
                            formControlName="deleteColumn"
                            [error]="deleteColumnError()"
                        ></inspecto-pen-field>
                        <inspecto-pen-field
                            label="Delete values"
                            placeholder="e.g. D, DELETE"
                            help="Comma-separated marker values that mean delete."
                            formControlName="deleteValues"
                            [error]="deleteValuesError()"
                        ></inspecto-pen-field>
                        @if (deleteWithoutOrderBy()) {
                            <inspecto-alert variant="warning">
                                Without an order by column, an upsert and a delete for the same key in one batch resolve
                                arbitrarily.
                            </inspecto-alert>
                        }
                    }
                    <inspecto-pen-field
                        label="Refresh seconds"
                        type="number"
                        [min]="0"
                        help="0 = every run."
                        formControlName="refreshSeconds"
                    ></inspecto-pen-field>
                }
                @if (error()) {
                    <inspecto-alert variant="error" [title]="error()!" />
                }
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class PipelineSettingsDialog {
    private fb = inject(FormBuilder);
    private ref = inject(MatDialogRef<PipelineSettingsDialog, PipelineSettings>);
    private confirm = inject(InspectoConfirmService);
    readonly data = inject<PipelineSettingsData>(MAT_DIALOG_DATA);

    /** The two output shapes, and the load modes a Reference supports. */
    readonly PRODUCES: PickerOption[] = [
        { value: 'stream', label: 'Stream' },
        { value: 'reference', label: 'Reference' },
    ];
    readonly LOAD_MODES: PickerOption[] = [
        { value: 'replace', label: 'Replace', hint: 'Full replace each run' },
        { value: 'upsert', label: 'Upsert', hint: 'Latest version wins by key' },
        { value: 'scd2', label: 'SCD2', hint: 'Keep slowly-changing history' },
    ];

    readonly error = signal<string | null>(null);

    readonly form = this.fb.group({
        description: [this.data.settings.description ?? ''],
        produces: [this.data.settings.produces],
        load: [this.data.settings.reference?.load ?? 'replace'],
        key: [(this.data.settings.reference?.key ?? []).join(', ')],
        refreshSeconds: [this.data.settings.reference?.refresh_seconds ?? 0],
        orderBy: [this.data.settings.reference?.order_by ?? ''],
        deleteColumn: [this.data.settings.reference?.delete?.column ?? ''],
        // TOON may hold non-string markers (`values: [true]`); the engine compares them as text.
        deleteValues: [(this.data.settings.reference?.delete?.values ?? []).map(String).join(', ')],
    });

    private readonly formValue = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

    /** The validator's WARNING (`reference-delete-without-order-by`), shown before save rather than after. */
    readonly deleteWithoutOrderBy = computed(() => {
        const v = this.formValue();
        return String(v.deleteColumn ?? '').trim() !== '' && String(v.orderBy ?? '').trim() === '';
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    /** The error lines save() raises, shown on their rows once the row was left or Save was pressed. */
    keyError(): string {
        const c = this.form.controls.key;
        return c.touched && c.hasError('required') ? 'upsert/scd2 requires at least one key column.' : '';
    }

    deleteColumnError(): string {
        const c = this.form.controls.deleteColumn;
        if (!c.touched) return '';
        const lines: string[] = [];
        if (c.hasError('required')) lines.push('Name the column that carries the delete marker.');
        if (c.hasError('isKey')) lines.push('The delete marker column cannot be a key column.');
        return lines.join(' ');
    }

    deleteValuesError(): string {
        const c = this.form.controls.deleteValues;
        return c.touched && c.hasError('required') ? 'Give at least one marker value that means delete.' : '';
    }

    save(): void {
        this.error.set(null);
        const v = this.form.getRawValue();
        const produces = (v.produces ?? 'stream') as PipelineSettings['produces'];
        // Always sent: the POST contract clears a stored description on empty/blank.
        const description = String(v.description ?? '').trim();
        if (produces === 'stream') {
            this.ref.close({ produces, reference: null, description });
            return;
        }
        const load = (v.load ?? 'replace') as 'replace' | 'upsert' | 'scd2';
        const key = splitList(v.key);
        // delete/order_by apply only to upsert/scd2 (the validator refuses them on replace), so a
        // replace save drops them along with their hidden fields.
        const versioned = load !== 'replace';
        const orderBy = versioned ? String(v.orderBy ?? '').trim() : '';
        const deleteColumn = versioned ? String(v.deleteColumn ?? '').trim() : '';
        const deleteValues = versioned ? splitList(v.deleteValues) : [];
        let invalid = false;
        if (versioned && key.length === 0) {
            this.form.controls.key.setErrors({ required: true });
            invalid = true;
        }
        if (deleteValues.length > 0 && !deleteColumn) {
            this.form.controls.deleteColumn.setErrors({ required: true });
            invalid = true;
        } else if (deleteColumn && key.includes(deleteColumn)) {
            this.form.controls.deleteColumn.setErrors({ isKey: true });
            invalid = true;
        }
        if (deleteColumn && deleteValues.length === 0) {
            this.form.controls.deleteValues.setErrors({ required: true });
            invalid = true;
        }
        if (invalid) {
            this.form.markAllAsTouched();
            return;
        }
        // Keep keys this dialog does not edit: the route replaces the whole block.
        const {
            delete: _delete,
            order_by: _orderBy,
            ...kept
        } = this.data.settings.reference ?? ({} as Partial<PipelineReferenceSettings>);
        this.ref.close({
            produces,
            reference: {
                ...kept,
                load,
                key,
                refresh_seconds: Number(v.refreshSeconds) || 0,
                ...(orderBy ? { order_by: orderBy } : {}),
                ...(deleteColumn ? { delete: { column: deleteColumn, values: deleteValues } } : {}),
            },
            description,
        });
    }
}

/** Split a comma-separated field into trimmed, non-empty entries. */
function splitList(raw: unknown): string[] {
    return String(raw ?? '')
        .split(',')
        .map((k) => k.trim())
        .filter((k) => k.length > 0);
}
