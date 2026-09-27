import { ChangeDetectionStrategy, Component, inject, ViewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { AttributeSpec } from 'app/inspecto/component-model';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { columnOptionLoader, datasetOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { Requirement, RequirementKpiBody } from 'app/inspecto/requirement';

/**
 * What `POST /requirements/{id}/kpi` needs from a Builder — and only that (ask the minimum): the Measure and the
 * period. The Requirement already carries the title, target, unit and good direction, and the KPI id defaults to
 * the Requirement's id, so none of those is asked; the rest is editable later on the KPI definition itself.
 */
export const REQUIREMENT_KPI_ATTRIBUTES: AttributeSpec[] = [
    { key: 'dataset', label: 'Dataset', type: 'autocomplete', tier: 'required' },
    {
        key: 'measure',
        label: 'Measure',
        type: 'string',
        tier: 'required',
        pattern: '(count|(count|countDistinct|sum|avg|min|max)\\([A-Za-z_][A-Za-z0-9_]*\\))',
        placeholder: 'sum(amount)',
        help: 'count, or agg(column) with agg one of count, countDistinct, sum, avg, min, max.',
    },
    {
        key: 'timeField',
        label: 'Date column',
        type: 'autocomplete',
        tier: 'required',
        help: 'The column a period is cut on.',
    },
    {
        key: 'grain',
        label: 'Period',
        type: 'select',
        tier: 'required',
        default: 'month',
        options: [
            { value: 'day', label: 'Day' },
            { value: 'week', label: 'Week (from Monday)' },
            { value: 'month', label: 'Month' },
            { value: 'quarter', label: 'Quarter' },
            { value: 'year', label: 'Year' },
        ],
    },
];

/**
 * Create a KPI definition from a delivered `kpi` Requirement (ASSURE-KPI-DEFINITIONS-1). Closes with the body for
 * `RequirementsService.createKpi`; the host makes the call, so the server's refusal reaches the user as a toast.
 */
@Component({
    selector: 'app-requirement-kpi-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, InspectoSchemaFormComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Create KPI from "{{ data.title }}"</h2>
        <mat-dialog-content>
            <p class="text-secondary mb-4 text-sm">
                The title, target, unit and good direction come from this requirement.
            </p>
            <inspecto-schema-form
                [specs]="attributes"
                [optionLoaders]="optionLoaders"
                (submitted)="create()"
            ></inspecto-schema-form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="create()">Create KPI</button>
        </mat-dialog-actions>
    `,
})
export class RequirementKpiDialog {
    private ref = inject(MatDialogRef<RequirementKpiDialog, RequirementKpiBody | undefined>);
    private confirm = inject(InspectoConfirmService);
    readonly data = inject<Requirement>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    readonly requestClose = guardDirtyClose(this.ref, () => this.schemaForm?.isDirty() ?? false, this.confirm);
    readonly attributes = REQUIREMENT_KPI_ATTRIBUTES;
    readonly optionLoaders = { dataset: datasetOptionLoader(), timeField: columnOptionLoader('dataset') };

    create(): void {
        if (!this.schemaForm.validate()) return;
        const v = this.schemaForm.value();
        this.ref.close({
            dataset: String(v['dataset'] ?? '').trim(),
            measure: String(v['measure'] ?? '').trim(),
            timeField: String(v['timeField'] ?? '').trim(),
            grain: (v['grain'] as RequirementKpiBody['grain']) ?? 'month',
        });
    }
}
