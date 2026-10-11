import { ChangeDetectionStrategy, Component, OnInit, ViewChild, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import {
    ANOMALY_ENTITY_TYPES,
    AnomalyFeatureDraft,
    anomalyModelAttributes,
    anomalyModelInitial,
    emptyFeature,
    featureDrafts,
    mapAnomalyRefusal,
    toAnomalyModelContent,
} from 'app/inspecto/anomaly/anomaly-model-form';
import { AnomalyModelsService } from 'app/inspecto/api/anomaly-models.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { ComponentDef } from 'app/inspecto/api/components.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { PickerOption } from 'app/inspecto/components/option-picker.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { ColumnMeta } from 'app/inspecto/query/query-types';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { AnomalyModelFeaturesComponent } from './anomaly-model-features.component';
import { AnomalyModelPreviewComponent } from './anomaly-model-preview.component';

export interface AnomalyModelFormData {
    existing?: ComponentDef;
}

/** `saved` = written; `held` = a maker-checker hold (202), carrying the Pending Change summary. */
export interface AnomalyModelFormResult {
    saved?: ComponentDef;
    held?: { id?: string; [k: string]: unknown };
}

/** The 202 body `PendingChanges.hold` answers with. */
export function heldAnomalyChange(res: unknown): AnomalyModelFormResult['held'] | null {
    const r = res as { status?: string; pendingChange?: Record<string, unknown> } | null;
    return r?.status === 'pending' ? ((r.pendingChange ?? {}) as AnomalyModelFormResult['held']) : null;
}

/**
 * Create / edit an Anomaly Model (ANOMALY-DETECTION-1 S5, design §13.1): a schema-form for the flat fields (window,
 * seasonality, thresholds, Peer Group, Entity Lists) plus the Feature row editor. Saves through the generic
 * component CRUD — `PUT` with `If-Match` on edit — and lands a 422 on the field its message names, verbatim. The
 * preview box below scores the UNSAVED content (`POST /anomaly-scores/preview` with `content`), nothing written.
 */
@Component({
    selector: 'app-anomaly-model-form-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatDialogModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
        AnomalyModelFeaturesComponent,
        AnomalyModelPreviewComponent,
    ],
    template: `
        <h2 mat-dialog-title>{{ isEdit ? 'Edit Anomaly Model — ' + data.existing!.name : 'New Anomaly Model' }}</h2>
        <mat-dialog-content>
            @if (refusal(); as r) {
                <inspecto-alert variant="error" title="Not saved">{{ r }}</inspecto-alert>
            }
            <inspecto-schema-form
                [specs]="attributes"
                [initial]="initial"
                [optionLoaders]="loaders"
                (submitted)="save()"
            ></inspecto-schema-form>
            <h3 class="mt-4 mb-2 text-sm font-semibold">Features</h3>
            <app-anomaly-model-features
                [datasetOptions]="datasets()"
                [columnMetaFor]="columnMetaFor"
                [features]="features"
            ></app-anomaly-model-features>
            <p class="text-secondary mt-4 text-xs">
                Derived Datasets (written by the anomaly.score Job, never authored): anomaly_scores_&lt;id&gt; and
                anomaly_scores_&lt;id&gt;_latest.
            </p>
            @if (lens.canWorkIncidents()) {
                <h3 class="mt-4 text-sm font-semibold">Preview the draft</h3>
                <p class="text-secondary text-xs">Scores one entity under the form as it is now, before saving.</p>
                <app-anomaly-model-preview [draft]="draftContent"></app-anomaly-model-preview>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" type="button" [disabled]="saving()" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class AnomalyModelFormDialog implements OnInit {
    readonly data = inject<AnomalyModelFormData>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<AnomalyModelFormDialog, AnomalyModelFormResult>);
    private api = inject(AnomalyModelsService);
    private datasetsApi = inject(DatasetsService);
    private rowsApi = inject(DatasetRowsService);
    private confirm = inject(InspectoConfirmService);
    /** The draft preview is gated like the saved one (`canWorkIncidents`); the server also wants authoring here. */
    readonly lens = inject(LensService);

    @ViewChild(InspectoSchemaFormComponent) schemaForm?: InspectoSchemaFormComponent;
    @ViewChild(AnomalyModelFeaturesComponent) featureEditor?: AnomalyModelFeaturesComponent;

    readonly isEdit = !!this.data.existing;
    readonly attributes = anomalyModelAttributes(this.isEdit);
    readonly initial = this.data.existing ? anomalyModelInitial(this.data.existing.content ?? {}) : null;
    readonly features: AnomalyFeatureDraft[] = this.data.existing
        ? featureDrafts(this.data.existing.content ?? {})
        : [emptyFeature(1)];
    readonly loaders = {
        entityType: async () => ANOMALY_ENTITY_TYPES.map((t) => ({ value: t, label: t })),
    };
    readonly saving = signal(false);
    readonly refusal = signal<string | null>(null);
    readonly datasets = signal<PickerOption[]>([]);
    private readonly columnCache = new Map<string, Promise<ColumnMeta[]>>();

    readonly requestClose = guardDirtyClose(
        this.ref,
        () => !!this.schemaForm?.isDirty() || !!this.featureEditor?.isDirty(),
        this.confirm,
    );

    readonly columnMetaFor = (id: string): Promise<ColumnMeta[]> => {
        let p = this.columnCache.get(id);
        if (!p) {
            p = firstValueFrom(this.datasetsApi.get(id))
                .then((ds) => this.rowsApi.columns(ds))
                .catch(() => [] as ColumnMeta[]);
            this.columnCache.set(id, p);
        }
        return p;
    };

    async ngOnInit(): Promise<void> {
        let ids: string[];
        try {
            ids = (await firstValueFrom(this.datasetsApi.list())).map((d) => d.id);
        } catch {
            ids = [];
        }
        const readable = await Promise.all(ids.map(async (id) => ((await this.columnMetaFor(id)).length ? id : null)));
        this.datasets.set(readable.filter((x): x is string => !!x).map((id) => ({ value: id, label: id })));
    }

    /** The content the form would save now, or `null` (errors shown) while it is invalid — save and draft preview. */
    private content(): { id: string; body: Record<string, unknown> } | null {
        const form = this.schemaForm;
        const rows = this.featureEditor;
        if (!form || !rows) return null;
        const topOk = form.validate();
        const rowsOk = rows.validate();
        if (!topOk || !rowsOk) return null;
        const top = form.value();
        const id = this.isEdit ? this.data.existing!.name : String(top['id'] ?? '').trim();
        return { id, body: toAnomalyModelContent(id, top, rows.value(), this.data.existing?.content ?? {}) };
    }

    readonly draftContent = (): Record<string, unknown> | null => this.content()?.body ?? null;

    save(): void {
        const c = this.content();
        if (!c) return;
        const { id, body } = c;
        this.saving.set(true);
        this.refusal.set(null);
        const call = this.isEdit ? this.api.update(id, body, this.data.existing!.contentHash) : this.api.create(body);
        call.subscribe({
            next: (res) => {
                this.saving.set(false);
                const held = heldAnomalyChange(res);
                this.ref.close(held ? { held } : { saved: res });
            },
            error: (e) => {
                this.saving.set(false);
                const msg = apiErrorMessage(e, 'The Anomaly Model was not saved.');
                this.refusal.set(msg);
                if (e?.status === 422) this.place(msg);
            },
        });
    }

    /** Put a 422 on the field it names; the banner keeps the full message either way. */
    private place(msg: string): void {
        const t = mapAnomalyRefusal(msg);
        if (t.feature != null) this.featureEditor?.setRowError(t.feature, msg, t.featureField);
        else if (t.field) {
            const c = this.schemaForm?.form.get(t.field);
            c?.setErrors({ message: msg });
            c?.markAsTouched();
        }
    }
}
