import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import {
    apiErrorMessage,
    inputLabel,
    inputMaxLength,
    ObjectsService,
    OperationalObject,
    RegulatoryReportDetail,
    RegulatoryReportsService,
    ReportTemplate,
    requiredInputs,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

/** What the pane hands the dialog: the loaded templates, and an optional Case / Incident to start from. */
export interface RegulatoryReportDraftData {
    templates: ReportTemplate[];
    subjectKind?: 'case' | 'incident';
    subjectId?: string;
}

/**
 * Draft a **Regulatory Report** — `POST /regulatory-reports`. Asks the template, the Case or Incident it is raised
 * from, and the values the template reads from its maker (the narrative…). The server renders it once; a refusal
 * (a required field empty, a value over its limit, a Case with Incidents outside your scope) is shown in its own
 * words and nothing is saved.
 */
@Component({
    selector: 'app-regulatory-report-draft-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>New regulatory report</h2>
        <mat-dialog-content class="flex flex-col gap-3 pt-2" style="min-width: 32rem">
            <form [formGroup]="form" class="flex flex-col gap-3" (ngSubmit)="save()">
                <div>
                    <inspecto-option-picker
                        label="Report Template"
                        [options]="templateOptions"
                        formControlName="template"
                    />
                    @if (attempted() && form.controls.template.hasError('required')) {
                        <p class="text-warn m-0 text-xs" role="alert">Pick the Report Template to file.</p>
                    }
                </div>
                @if (template(); as t) {
                    <p class="text-secondary m-0 text-sm">
                        {{ t.description || t.title }} · {{ t.format.toUpperCase() }} · dropped in {{ t.delivery.dir }}
                    </p>
                }

                <mat-button-toggle-group
                    class="self-start"
                    aria-label="Raised from"
                    [value]="form.controls.subjectKind.value"
                    (change)="setKind($event.value)"
                >
                    <mat-button-toggle value="case">From a Case</mat-button-toggle>
                    <mat-button-toggle value="incident">From an Incident</mat-button-toggle>
                </mat-button-toggle-group>
                <div>
                    <inspecto-option-picker
                        [label]="form.controls.subjectKind.value === 'case' ? 'Case' : 'Incident'"
                        [options]="subjectOptions()"
                        formControlName="subjectId"
                    />
                    @if (attempted() && form.controls.subjectId.hasError('required')) {
                        <p class="text-warn m-0 text-xs" role="alert">
                            Pick the {{ form.controls.subjectKind.value === 'case' ? 'Case' : 'Incident' }} the report
                            is filed from.
                        </p>
                    }
                </div>

                @for (key of inputKeys(); track key) {
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>{{ label(key) }}</mat-label>
                        <textarea matInput rows="3" [formControl]="inputControl(key)"></textarea>
                        @if (inputControl(key).hasError('required')) {
                            <mat-error>The template requires {{ label(key) }}.</mat-error>
                        } @else if (inputControl(key).hasError('maxlength')) {
                            <mat-error
                                >At most
                                {{ inputControl(key).getError('maxlength').requiredLength }} characters.</mat-error
                            >
                        }
                    </mat-form-field>
                }

                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Reason (optional)</mat-label>
                    <input matInput formControlName="reason" maxlength="500" />
                    @if (form.controls.reason.hasError('maxlength')) {
                        <mat-error>At most 500 characters.</mat-error>
                    }
                </mat-form-field>
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Not drafted">{{ error() }}</inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving()" (click)="save()">Draft</button>
        </mat-dialog-actions>
    `,
})
export class RegulatoryReportDraftDialog implements OnInit {
    private readonly api = inject(RegulatoryReportsService);
    private readonly objects = inject(ObjectsService);
    private readonly ref = inject(MatDialogRef<RegulatoryReportDraftDialog, RegulatoryReportDetail>);
    private readonly confirm = inject(InspectoConfirmService);
    readonly data = inject<RegulatoryReportDraftData>(MAT_DIALOG_DATA);

    readonly form = new FormGroup({
        template: new FormControl(this.data.templates.length === 1 ? this.data.templates[0].id : '', {
            nonNullable: true,
            validators: [Validators.required],
        }),
        subjectKind: new FormControl<'case' | 'incident'>(this.data.subjectKind ?? 'case', { nonNullable: true }),
        subjectId: new FormControl(this.data.subjectId ?? '', { nonNullable: true, validators: [Validators.required] }),
        reason: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
        inputs: new FormGroup<Record<string, FormControl<string>>>({}),
    });

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    readonly templateOptions: PickerOption[] = this.data.templates.map((t) => ({
        value: t.id,
        label: t.title,
        hint: `${t.format.toUpperCase()} · ${t.origin === 'space' ? 'this Space' : 'built-in'}`,
    }));
    private readonly templateId = signal(this.form.controls.template.value);
    readonly template = computed(() => this.data.templates.find((t) => t.id === this.templateId()) ?? null);
    readonly inputKeys = signal<string[]>([]);
    private readonly candidates = signal<OperationalObject[]>([]);
    private readonly kind = signal(this.form.controls.subjectKind.value);
    readonly subjectOptions = computed<PickerOption[]>(() =>
        this.candidates()
            .filter((o) => o.objectType.toLowerCase() === this.kind())
            .map((o) => ({ value: o.id, label: o.title || o.id, hint: `${o.id} · ${o.status}` })),
    );
    readonly saving = signal(false);
    readonly error = signal<string | null>(null);
    /** Set by the first submit: the pickers' own errors show from then on, never while one is merely opened. */
    readonly attempted = signal(false);
    readonly label = inputLabel;

    ngOnInit(): void {
        this.applyTemplate();
        this.form.controls.template.valueChanges.subscribe((id) => {
            this.templateId.set(id);
            this.applyTemplate();
        });
        this.loadSubjects();
    }

    setKind(kind: 'case' | 'incident'): void {
        this.form.controls.subjectKind.setValue(kind);
        this.form.controls.subjectId.setValue('');
        this.form.markAsDirty();
        this.kind.set(kind);
        this.loadSubjects();
    }

    inputControl(key: string): FormControl<string> {
        return this.form.controls.inputs.controls[key];
    }

    /** Rebuild the input controls for the chosen template, keeping what was typed under a key both read. */
    private applyTemplate(): void {
        const t = this.template();
        const group = this.form.controls.inputs;
        const keep: Record<string, string> = {};
        for (const k of Object.keys(group.controls)) keep[k] = group.controls[k].value;
        for (const k of Object.keys(group.controls)) group.removeControl(k);
        if (!t) {
            this.inputKeys.set([]);
            return;
        }
        const required = requiredInputs(t);
        for (const k of t.inputs) {
            const validators = [];
            if (required.has(k)) validators.push(Validators.required);
            const max = inputMaxLength(t, k);
            if (max > 0) validators.push(Validators.maxLength(max));
            group.addControl(k, new FormControl(keep[k] ?? '', { nonNullable: true, validators }));
        }
        this.inputKeys.set([...t.inputs]);
    }

    private loadSubjects(): void {
        this.objects.list({ type: this.kind().toUpperCase(), limit: 200 }).subscribe({
            next: (os) => this.candidates.set(os),
            error: () => this.candidates.set([]),
        });
    }

    save(): void {
        if (this.form.invalid) {
            this.attempted.set(true);
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        const inputs: Record<string, string> = {};
        for (const [k, val] of Object.entries(v.inputs)) if (val.trim()) inputs[k] = val;
        this.saving.set(true);
        this.error.set(null);
        this.api
            .draft({
                template: v.template,
                ...(v.subjectKind === 'case' ? { caseId: v.subjectId } : { incidentId: v.subjectId }),
                inputs,
                reason: v.reason.trim() || undefined,
            })
            .subscribe({
                next: (d) => this.ref.close(d),
                error: (e) => {
                    this.saving.set(false);
                    // The server's own words: which field is empty or too long, or why the Case was refused.
                    this.error.set(apiErrorMessage(e, 'The report could not be drafted'));
                },
            });
    }
}
