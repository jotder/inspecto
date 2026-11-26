import { ChangeDetectionStrategy, Component, ViewChild, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { columnsOfRole } from 'app/inspecto/api/column-roles';
import { ComponentDef, ComponentsService } from 'app/inspecto/api/components.service';
import { InsightPreview, InsightsService } from 'app/inspecto/api/insights.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { datasetOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { InspectoOptionPickerComponent } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import {
    CalendarEntry,
    KNOWN_INSIGHT_TYPES,
    calendarEntries,
    insightProfileAttributes,
    insightProfileInitial,
    toInsightProfileContent,
} from 'app/inspecto/insights/insight-profile-form';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { datasetColumnOptionLoader } from 'app/modules/admin/studio/datasets/dataset-column-option-loader';
import { InsightPreviewComponent } from './insight-preview.component';

export interface InsightProfileFormData {
    existing?: ComponentDef;
}

export interface InsightProfileFormResult {
    saved?: ComponentDef;
}

const MODES = [
    { value: 'expected', label: 'Expected — a finding in this period is dropped' },
    { value: 'exclude', label: 'Exclude — the period is left out of every series' },
];

/**
 * Create / edit an Insight Profile (BI-INSIGHTS-1 P3, plan §7). A schema form for the flat fields, a business calendar
 * row editor, and "Preview on history" (`POST /insights/preview`) over the UNSAVED content — nothing is written until
 * Save, which goes through the generic component route (`PUT` with `If-Match` on edit). A 422 is shown verbatim.
 */
@Component({
    selector: 'app-insight-profile-form-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        FormsModule,
        MatButtonModule,
        MatDialogModule,
        MatIconModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
        PenFieldComponent,
        InspectoOptionPickerComponent,
        InsightPreviewComponent,
    ],
    template: `
        <h2 mat-dialog-title>{{ isEdit ? 'Edit Insight Profile — ' + data.existing!.name : 'New Insight Profile' }}</h2>
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
            <div class="mt-2 flex flex-wrap items-center gap-2">
                <button mat-stroked-button type="button" (click)="proposeDimensions()">
                    Propose dimensions from column roles
                </button>
                @if (proposal(); as p) {
                    <span class="text-secondary text-xs" role="status">{{ p }}</span>
                }
            </div>
            <p class="text-secondary mt-1 text-xs">Insight Types this screen knows: {{ knownTypes }}.</p>

            <h3 class="mt-4 mb-1 text-sm font-semibold">Business calendar</h3>
            <p class="text-secondary text-xs">Holidays, promotions and closures the scan should know about.</p>
            @for (e of calendar(); track $index; let i = $index) {
                <div
                    class="mt-2 grid grid-cols-1 gap-x-6 rounded-lg border p-2 sm:grid-cols-2"
                    data-testid="calendar-row"
                >
                    <inspecto-pen-field
                        label="From"
                        type="date"
                        [ngModel]="e.from"
                        (ngModelChange)="patch(i, 'from', $event)"
                    />
                    <inspecto-pen-field
                        label="To"
                        type="date"
                        [ngModel]="e.to"
                        (ngModelChange)="patch(i, 'to', $event)"
                    />
                    <inspecto-pen-field label="Label" [ngModel]="e.label" (ngModelChange)="patch(i, 'label', $event)" />
                    <inspecto-option-picker
                        label="Mode"
                        [options]="modes"
                        [ngModel]="e.mode"
                        (ngModelChange)="patch(i, 'mode', $event)"
                    />
                    <div class="sm:col-span-2">
                        <button
                            mat-button
                            type="button"
                            [attr.aria-label]="'Remove calendar entry ' + (i + 1)"
                            (click)="removeEntry(i)"
                        >
                            Remove
                        </button>
                    </div>
                </div>
            }
            <button mat-stroked-button type="button" class="mt-2" (click)="addEntry()">Add calendar entry</button>

            <h3 class="mt-6 mb-1 text-sm font-semibold">Preview on history</h3>
            <p class="text-secondary text-xs">
                Replays the Profile as it is now over past periods, as you. Nothing is saved.
            </p>
            <div class="mt-2 flex flex-wrap items-end gap-3">
                <div class="w-48">
                    <inspecto-pen-field label="Periods" type="number" [min]="4" [max]="52" [(ngModel)]="periods" />
                </div>
                <button mat-stroked-button type="button" [disabled]="previewing()" (click)="runPreview()">
                    Preview on history
                </button>
            </div>
            @if (previewError(); as pe) {
                <inspecto-alert variant="error" title="Preview refused">{{ pe }}</inspecto-alert>
            }
            @if (preview(); as pv) {
                <app-insight-preview [preview]="pv" />
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" type="button" [disabled]="saving()" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class InsightProfileFormDialog {
    readonly data = inject<InsightProfileFormData>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<InsightProfileFormDialog, InsightProfileFormResult>);
    private components = inject(ComponentsService);
    private insights = inject(InsightsService);
    private datasets = inject(DatasetsService);
    private confirm = inject(InspectoConfirmService);

    @ViewChild(InspectoSchemaFormComponent) schemaForm?: InspectoSchemaFormComponent;

    readonly isEdit = !!this.data.existing;
    readonly attributes = insightProfileAttributes(this.isEdit);
    readonly initial = insightProfileInitial(this.data.existing?.content ?? {});
    readonly loaders = {
        dataset: datasetOptionLoader(),
        measureField: datasetColumnOptionLoader('dataset'),
        field: datasetColumnOptionLoader('dataset'),
    };
    readonly knownTypes = KNOWN_INSIGHT_TYPES.map((t) => t.value).join(', ');
    readonly modes = MODES;
    readonly calendar = signal<CalendarEntry[]>(calendarEntries(this.data.existing?.content ?? {}));
    private calendarDirty = false;
    periods = 12;
    readonly saving = signal(false);
    readonly refusal = signal<string | null>(null);
    readonly proposal = signal<string | null>(null);
    readonly previewing = signal(false);
    readonly previewError = signal<string | null>(null);
    readonly preview = signal<InsightPreview | null>(null);

    readonly requestClose = guardDirtyClose(
        this.ref,
        () => !!this.schemaForm?.isDirty() || this.calendarDirty,
        this.confirm,
    );

    addEntry(): void {
        this.calendarDirty = true;
        this.calendar.update((l) => [...l, { from: '', to: '', label: '', mode: 'expected' }]);
    }

    removeEntry(i: number): void {
        this.calendarDirty = true;
        this.calendar.update((l) => l.filter((_, j) => j !== i));
    }

    patch(i: number, key: keyof CalendarEntry, value: unknown): void {
        this.calendarDirty = true;
        this.calendar.update((l) =>
            l.map((e, j) => (j === i ? { ...e, [key]: value == null ? '' : String(value) } : e)),
        );
    }

    /** Fill Dimensions (and an empty Date column) from the Dataset's column roles. */
    async proposeDimensions(): Promise<void> {
        const form = this.schemaForm;
        const id = String(form?.form.get('dataset')?.value ?? '').trim();
        if (!form || !id) {
            this.proposal.set('Pick a Dataset first.');
            return;
        }
        try {
            const ds = await firstValueFrom(this.datasets.get(id));
            const dims = columnsOfRole(ds.columns, 'dimension').slice(0, 12);
            const dates = columnsOfRole(ds.columns, 'temporal');
            if (!dims.length) {
                this.proposal.set('The Dataset marks no column as a dimension.');
                return;
            }
            form.form.get('dimensions')?.setValue(dims);
            const field = form.form.get('field');
            if (field && !field.value && dates.length) field.setValue(dates[0]);
            form.form.markAsDirty();
            this.proposal.set(`Proposed ${dims.length} dimension${dims.length === 1 ? '' : 's'}: ${dims.join(', ')}.`);
        } catch (e) {
            this.proposal.set(apiErrorMessage(e, 'Could not read the Dataset.'));
        }
    }

    private content(): { id: string; body: Record<string, unknown> } | null {
        const form = this.schemaForm;
        if (!form || !form.validate()) return null;
        const v = form.value();
        const id = this.isEdit ? this.data.existing!.name : String(v['id'] ?? '').trim();
        return { id, body: toInsightProfileContent(id, v, this.calendar(), this.data.existing?.content ?? {}) };
    }

    runPreview(): void {
        const c = this.content();
        if (!c) return;
        const periods = Math.min(52, Math.max(4, Math.round(Number(this.periods) || 12)));
        this.previewing.set(true);
        this.previewError.set(null);
        this.insights.preview(c.body, periods).subscribe({
            next: (p) => {
                this.preview.set(p);
                this.previewing.set(false);
            },
            error: (e) => {
                this.preview.set(null);
                this.previewError.set(apiErrorMessage(e, 'The preview did not run.'));
                this.previewing.set(false);
            },
        });
    }

    save(): void {
        const c = this.content();
        if (!c) return;
        this.saving.set(true);
        this.refusal.set(null);
        const call = this.isEdit
            ? this.components.update('insight-profile', c.id, c.body, { ifMatch: this.data.existing!.contentHash })
            : this.components.create('insight-profile', c.body);
        call.subscribe({
            next: (saved) => {
                this.saving.set(false);
                this.ref.close({ saved: saved ?? { type: 'insight-profile', name: c.id, ref: '', content: c.body } });
            },
            error: (e) => {
                this.saving.set(false);
                this.refusal.set(apiErrorMessage(e, 'The Insight Profile was not saved.'));
            },
        });
    }
}
