import { ChangeDetectionStrategy, Component, inject, signal, ViewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ComponentDef, ComponentsService, KpiDefinition } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { columnOptionLoader, datasetOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import {
    fromKpiContent,
    KPI_DEFINITION_ATTRIBUTES,
    kpiIdFor,
    toKpiContent,
} from 'app/inspecto/kpi/kpi-definition-attributes';

/** Dialog input: an existing KPI definition to edit; absent ⇒ create. */
export interface KpiDefinitionData {
    existing?: ComponentDef;
}

/**
 * Create or edit a KPI definition (ASSURE-KPI-DEFINITIONS-1) — the `kpi` registry component a KPI tile binds to by
 * `kpiId`. Saves through `/components/kpi`, so the server's fail-closed gate (the Measure exists and is readable by
 * you, ordered bands, a known period) and the maker-checker hold apply; its message is shown as the server gives it.
 */
@Component({
    selector: 'app-kpi-definition-dialog',
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, InspectoAlertComponent, InspectoSchemaFormComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ existing ? 'Edit KPI definition "' + existing.name + '"' : 'New KPI definition' }}</h2>
        <mat-dialog-content>
            @if (refusal(); as r) {
                <inspecto-alert class="mb-4 block" variant="error" title="Not saved">{{ r }}</inspecto-alert>
            }
            @if (held()) {
                <inspecto-alert class="mb-4 block" variant="info" title="Waiting for approval">
                    This change needs an approver — it is in Pending Changes and nothing was written yet.
                </inspecto-alert>
            }
            <inspecto-schema-form
                [specs]="attributes"
                [initial]="initialValue"
                [optionLoaders]="optionLoaders"
                (submitted)="save()"
            ></inspecto-schema-form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button [disabled]="saving()" (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" [disabled]="saving()" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class KpiDefinitionDialog {
    private api = inject(ComponentsService);
    private ref = inject(MatDialogRef<KpiDefinitionDialog, ComponentDef | undefined>);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    readonly existing = inject<KpiDefinitionData | null>(MAT_DIALOG_DATA, { optional: true })?.existing;

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    readonly requestClose = guardDirtyClose(this.ref, () => this.schemaForm?.isDirty() ?? false, this.confirm);
    readonly attributes = KPI_DEFINITION_ATTRIBUTES;
    readonly optionLoaders = { dataset: datasetOptionLoader(), timeField: columnOptionLoader('dataset') };
    readonly initialValue = this.existing
        ? fromKpiContent(this.existing.content as unknown as KpiDefinition)
        : undefined;
    readonly saving = signal(false);
    readonly refusal = signal<string | null>(null);
    readonly held = signal(false);

    save(): void {
        if (!this.schemaForm.validate()) return;
        const content = toKpiContent(this.schemaForm.value());
        // Keys this form does not ask (format, owner, shares, requirement, …) ride through an edit untouched.
        const kept = { ...(this.existing?.content ?? {}) };
        for (const k of ['title', 'bands', 'target', 'unit', 'timezone']) delete kept[k];
        const body = { ...kept, ...content } as Record<string, unknown>;
        this.saving.set(true);
        this.refusal.set(null);
        const call = this.existing
            ? this.api.update('kpi', this.existing.name, body, { ifMatch: this.existing.contentHash })
            : this.api.create('kpi', { id: kpiIdFor(content.title ?? content.measure), ...body });
        call.subscribe({
            next: (saved) => {
                this.saving.set(false);
                // 202: held by maker-checker — say so, and keep the dialog open.
                if ((saved as unknown as { status?: string })?.status === 'pending') this.held.set(true);
                else this.ref.close(saved);
            },
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 422 || e?.status === 409)
                    this.refusal.set(apiErrorMessage(e, 'The KPI definition was refused.'));
                else this.toastr.error(apiErrorMessage(e, 'Could not save the KPI definition.'));
            },
        });
    }
}
