import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { ConnectionsService } from 'app/inspecto/api/connections.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

export interface InsightActionRequestData {
    /** The Insight's sentence (or type and slice), shown so the reader knows what the request is about. */
    title: string;
}

/** What the dialog hands back: the https Connection to send to and an optional reason for the approver. */
export interface InsightActionRequestResult {
    connection: string;
    reason: string;
}

/**
 * "Request action" on an Insight (BI-INSIGHTS-1 I-17): asks only what `POST /action-requests` cannot derive — the
 * https Connection and a reason. The Insight's evidence becomes the payload; the feed opens the Case it is raised from.
 */
@Component({
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Request action</h2>
        <mat-dialog-content class="flex w-96 max-w-full flex-col gap-3">
            <p class="text-secondary text-sm">
                A Case is opened with this Insight's evidence, and an Action Request is raised from it. A second person
                approves the request before anything is sent.
            </p>
            <p class="text-sm font-medium">{{ data.title }}</p>
            @if (loadError(); as e) {
                <inspecto-alert variant="error">{{ e }}</inspecto-alert>
            } @else if (!loading() && options().length === 0) {
                <inspecto-alert variant="info"
                    >No https Connection exists in this Space. Add one in Settings ▸ Connections first.</inspecto-alert
                >
            }
            <form [formGroup]="form" class="flex flex-col gap-2" (ngSubmit)="submit()">
                <inspecto-option-picker label="Send to Connection" [options]="options()" formControlName="connection" />
                @if (showError()) {
                    <p class="text-warn text-xs" role="alert">Choose the Connection to send to.</p>
                }
                <inspecto-pen-field
                    label="Reason (optional)"
                    [multiline]="true"
                    [maxlength]="500"
                    placeholder="What should happen — shown to the approver"
                    formControlName="reason"
                ></inspecto-pen-field>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" type="button" (click)="submit()">Request action</button>
        </mat-dialog-actions>
    `,
})
export class InsightActionRequestDialog {
    readonly data = inject<InsightActionRequestData>(MAT_DIALOG_DATA);
    private ref = inject(MatDialogRef<InsightActionRequestDialog, InsightActionRequestResult>);
    private confirm = inject(InspectoConfirmService);

    readonly loading = signal(true);
    readonly loadError = signal<string | null>(null);
    readonly options = signal<PickerOption[]>([]);
    readonly showError = signal(false);

    readonly form = inject(FormBuilder).nonNullable.group({
        connection: ['', Validators.required],
        reason: [''],
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    constructor() {
        inject(ConnectionsService)
            .list()
            .subscribe({
                next: (all) => {
                    this.options.set(
                        all
                            .filter((c) => c.connector === 'https')
                            .map((c) => ({ value: c.id, label: c.id, hint: c.description })),
                    );
                    this.loading.set(false);
                },
                error: (e) => {
                    this.loadError.set(apiErrorMessage(e, 'The Connections could not be read.'));
                    this.loading.set(false);
                },
            });
    }

    submit(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            this.showError.set(true);
            return;
        }
        const v = this.form.getRawValue();
        this.ref.close({ connection: v.connection, reason: v.reason.trim() });
    }
}
