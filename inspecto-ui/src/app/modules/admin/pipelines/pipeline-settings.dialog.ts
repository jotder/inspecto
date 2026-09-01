import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import type { PipelineSettings } from 'app/inspecto/api/pipelines.service';

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
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoDialogResizeDirective,
        InspectoOptionPickerComponent,
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
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Description</mat-label>
                    <textarea matInput rows="2" formControlName="description"></textarea>
                    <mat-hint>Optional — leaving it blank clears the stored description.</mat-hint>
                </mat-form-field>
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
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Key columns</mat-label>
                        <input matInput formControlName="key" placeholder="e.g. msisdn, event_date" />
                        @if (form.controls.load.value !== 'replace') {
                            <mat-hint
                                >Comma-separated. Required for upsert/scd2 — this is the identity the dataset dedupes
                                on.</mat-hint
                            >
                        }
                        @if (form.controls.key.hasError('required')) {
                            <mat-error>upsert/scd2 requires at least one key column.</mat-error>
                        }
                    </mat-form-field>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Refresh seconds</mat-label>
                        <input matInput type="number" min="0" formControlName="refreshSeconds" />
                        <mat-hint>0 = every run.</mat-hint>
                    </mat-form-field>
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
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

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
        const key = String(v.key ?? '')
            .split(',')
            .map((k) => k.trim())
            .filter((k) => k.length > 0);
        if (load !== 'replace' && key.length === 0) {
            this.form.controls.key.setErrors({ required: true });
            this.form.markAllAsTouched();
            return;
        }
        this.ref.close({
            produces,
            reference: { load, key, refresh_seconds: Number(v.refreshSeconds) || 0 },
            description,
        });
    }
}
