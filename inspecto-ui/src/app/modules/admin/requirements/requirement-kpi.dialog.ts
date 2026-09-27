import { ChangeDetectionStrategy, Component, inject, signal, ViewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { AttributeSpec } from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { columnOptionLoader, datasetOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { Requirement, RequirementKpiBody, RequirementKpiResult, RequirementsService } from 'app/inspecto/requirement';
import { KPI_DEFINITION_ATTRIBUTES } from 'app/inspecto/kpi/kpi-definition-attributes';

/** The keys `POST /requirements/{id}/kpi` needs from a Builder — the KPI editor's own specs for them. */
const ASKED = ['dataset', 'measure', 'timeField', 'grain'];

/**
 * What the create-KPI action asks — and only that (ask the minimum): the Measure and the period. The Requirement
 * already carries the title, target, unit and good direction, and the KPI id defaults to the Requirement's id, so
 * none of those is asked; the rest is editable later on the KPI definition itself.
 */
export const REQUIREMENT_KPI_ATTRIBUTES: AttributeSpec[] = KPI_DEFINITION_ATTRIBUTES.filter((a) =>
    ASKED.includes(a.key),
);

/**
 * Create a KPI definition from a delivered `kpi` Requirement (ASSURE-KPI-DEFINITIONS-1). Makes the call itself and
 * stays open on a refusal (422/409 shown in place, anything else toasted), so the answers are never lost; closes
 * with the server's answer — the KPI written, or the maker-checker hold.
 */
@Component({
    selector: 'app-requirement-kpi-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, InspectoAlertComponent, InspectoSchemaFormComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Create KPI from "{{ data.title }}"</h2>
        <mat-dialog-content>
            @if (refusal(); as r) {
                <inspecto-alert class="mb-4 block" variant="error" title="Not created">{{ r }}</inspecto-alert>
            }
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
            <button type="button" mat-button [disabled]="saving()" (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" [disabled]="saving()" (click)="create()">
                Create KPI
            </button>
        </mat-dialog-actions>
    `,
})
export class RequirementKpiDialog {
    private api = inject(RequirementsService);
    private ref = inject(MatDialogRef<RequirementKpiDialog, RequirementKpiResult | undefined>);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    readonly data = inject<Requirement>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    readonly requestClose = guardDirtyClose(this.ref, () => this.schemaForm?.isDirty() ?? false, this.confirm);
    readonly attributes = REQUIREMENT_KPI_ATTRIBUTES;
    readonly optionLoaders = { dataset: datasetOptionLoader(), timeField: columnOptionLoader('dataset') };
    readonly saving = signal(false);
    readonly refusal = signal<string | null>(null);

    create(): void {
        if (!this.schemaForm.validate()) return;
        const v = this.schemaForm.value();
        const body: RequirementKpiBody = {
            dataset: String(v['dataset'] ?? '').trim(),
            measure: String(v['measure'] ?? '').trim(),
            timeField: String(v['timeField'] ?? '').trim(),
            grain: (v['grain'] as RequirementKpiBody['grain']) ?? 'month',
        };
        this.saving.set(true);
        this.refusal.set(null);
        this.api.createKpi(this.data.id, body).subscribe({
            next: (res) => {
                this.saving.set(false);
                this.ref.close(res);
            },
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 422 || e?.status === 409)
                    this.refusal.set(apiErrorMessage(e, 'The KPI was refused.'));
                else this.toastr.error(apiErrorMessage(e, `Could not create a KPI from "${this.data.title}"`));
            },
        });
    }
}
