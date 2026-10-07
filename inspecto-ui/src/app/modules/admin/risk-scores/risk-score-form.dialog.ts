import { ChangeDetectionStrategy, Component, OnInit, ViewChild, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { ComponentDef, ComponentsService } from 'app/inspecto/api/components.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { PickerOption } from 'app/inspecto/components/option-picker.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import {
    RISK_ENTITY_TYPES,
    RiskFactorDraft,
    emptyFactor,
    factorDrafts,
    mapRiskRefusal,
    riskScoreAttributes,
    riskScoreInitial,
    toRiskScoreContent,
} from 'app/inspecto/risk/risk-score-form';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { ColumnMeta } from 'app/inspecto/query/query-types';
import { RiskScoreFactorsComponent } from './risk-score-factors.component';

/** Open with an existing model to edit it; without one to create. */
export interface RiskScoreFormData {
    existing?: ComponentDef;
}

/** `saved` = written; `held` = a maker-checker hold (202), carrying the Pending Change summary. */
export interface RiskScoreFormResult {
    saved?: ComponentDef;
    held?: { id?: string; [k: string]: unknown };
}

/** The 202 body `PendingChanges.hold` answers with. */
export function heldChange(res: unknown): RiskScoreFormResult['held'] | null {
    const r = res as { status?: string; pendingChange?: Record<string, unknown> } | null;
    return r?.status === 'pending' ? ((r.pendingChange ?? {}) as RiskScoreFormResult['held']) : null;
}

/**
 * Create / edit a Risk Score model (ASSURE-RISK-SCORE-RESIDUALS-1 (2), slice S2a; D-RP2 schema-form for the flat
 * fields + the Factor row editor). Saves through the generic component CRUD — `PUT` with `If-Match` on edit —
 * and lands a 422 on the field its message names (`mapRiskRefusal`), the message shown verbatim.
 */
@Component({
    selector: 'app-risk-score-form-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatDialogModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
        RiskScoreFactorsComponent,
    ],
    template: `
        <h2 mat-dialog-title>{{ isEdit ? 'Edit Risk Score — ' + data.existing!.name : 'New Risk Score' }}</h2>
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
            <h3 class="mt-4 mb-2 text-sm font-semibold">Factors</h3>
            <app-risk-score-factors
                [datasetOptions]="datasets()"
                [columnsFor]="columnsFor"
                [columnMetaFor]="columnMetaFor"
                [factors]="factors"
            ></app-risk-score-factors>
            <p class="text-secondary mt-4 text-xs">
                Derived Datasets (written by the risk.score Job, never authored): risk_scores_&lt;id&gt; and
                risk_scores_&lt;id&gt;_latest.
            </p>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" type="button" [disabled]="saving()" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class RiskScoreFormDialog implements OnInit {
    readonly data = inject<RiskScoreFormData>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<RiskScoreFormDialog, RiskScoreFormResult>);
    private components = inject(ComponentsService);
    private datasetsApi = inject(DatasetsService);
    private rowsApi = inject(DatasetRowsService);
    private confirm = inject(InspectoConfirmService);

    @ViewChild(InspectoSchemaFormComponent) schemaForm?: InspectoSchemaFormComponent;
    @ViewChild(RiskScoreFactorsComponent) factorEditor?: RiskScoreFactorsComponent;

    readonly isEdit = !!this.data.existing;
    readonly attributes = riskScoreAttributes(this.isEdit);
    readonly initial = this.data.existing ? riskScoreInitial(this.data.existing.content ?? {}) : null;
    readonly factors: RiskFactorDraft[] = this.data.existing
        ? factorDrafts(this.data.existing.content ?? {})
        : [emptyFactor(1)];
    readonly loaders = {
        entityType: async () => RISK_ENTITY_TYPES.map((t) => ({ value: t, label: t })),
    };
    readonly saving = signal(false);
    readonly refusal = signal<string | null>(null);
    /** Datasets whose Schema resolves to at least one column (D-RP8 a) — the same read `requireStorable` needs. */
    readonly datasets = signal<PickerOption[]>([]);
    private readonly columnCache = new Map<string, Promise<ColumnMeta[]>>();

    readonly requestClose = guardDirtyClose(
        this.ref,
        () => !!this.schemaForm?.isDirty() || !!this.factorEditor?.isDirty(),
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

    readonly columnsFor = async (id: string): Promise<string[]> => (await this.columnMetaFor(id)).map((c) => c.name);

    async ngOnInit(): Promise<void> {
        let ids: string[];
        try {
            ids = (await firstValueFrom(this.datasetsApi.list())).map((d) => d.id);
        } catch {
            ids = [];
        }
        const readable = await Promise.all(ids.map(async (id) => ((await this.columnsFor(id)).length ? id : null)));
        this.datasets.set(readable.filter((x): x is string => !!x).map((id) => ({ value: id, label: id })));
    }

    save(): void {
        const form = this.schemaForm;
        const rows = this.factorEditor;
        if (!form || !rows) return;
        const topOk = form.validate();
        const rowsOk = rows.validate();
        if (!topOk || !rowsOk) return;
        const top = form.value();
        const id = this.isEdit ? this.data.existing!.name : String(top['id'] ?? '').trim();
        const body = toRiskScoreContent(id, top, rows.value(), this.data.existing?.content ?? {});
        this.saving.set(true);
        this.refusal.set(null);
        const call = this.isEdit
            ? this.components.update('risk-score', id, body, { ifMatch: this.data.existing!.contentHash })
            : this.components.create('risk-score', body);
        call.subscribe({
            next: (res) => {
                this.saving.set(false);
                const held = heldChange(res);
                this.ref.close(held ? { held } : { saved: res });
            },
            error: (e) => {
                this.saving.set(false);
                const msg = apiErrorMessage(e, 'The Risk Score was not saved.');
                this.refusal.set(msg);
                if (e?.status === 422) this.place(msg);
            },
        });
    }

    /** Put a 422 on the field it names; the banner keeps the full message either way. */
    private place(msg: string): void {
        const t = mapRiskRefusal(msg);
        if (t.factor != null) this.factorEditor?.setRowError(t.factor, msg, t.factorField);
        else if (t.field) {
            const c = this.schemaForm?.form.get(t.field);
            c?.setErrors({ message: msg });
            c?.markAsTouched();
        }
    }
}
