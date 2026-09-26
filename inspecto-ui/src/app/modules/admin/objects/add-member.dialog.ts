import { Component, inject, signal, ChangeDetectionStrategy, computed } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, OperationalObject, SessionService } from 'app/inspecto/api';
import { currentOperator, displayStatus } from './mail-model';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';

/**
 * Add a member Incident to a Case's Contents (C1) — picks a not-yet-contained incident and creates
 * the {@code CONTAINS} edge. Closes with `true` when a member was added.
 */
@Component({
    selector: 'app-add-member-dialog',
    standalone: true,
    imports: [InspectoOptionPickerComponent, ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Add member incident</h2>
        <mat-dialog-content class="pt-2">
            <inspecto-option-picker
                class="w-full"
                label="Incident"
                [options]="candidateOptions()"
                [formControl]="pick"
            />
            @if (pick.hasError('required') && pick.touched) {
                <p class="text-warn m-0 text-xs" role="alert">Pick an incident.</p>
            }
            @if (!candidates().length) {
                <p class="text-secondary text-sm">Every incident is already contained by this case (or none exist).</p>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="false">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving() || !candidates().length" (click)="add()">
                Add
            </button>
        </mat-dialog-actions>
    `,
})
export class AddMemberDialog {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<AddMemberDialog>);
    private toastr = inject(ToastrService);
    private session = inject(SessionService);
    readonly data = inject<{ caseId: string; exclude: string[] }>(MAT_DIALOG_DATA);

    readonly displayStatus = displayStatus;
    readonly pick = new FormControl<string | null>(null, Validators.required);
    readonly candidates = signal<OperationalObject[]>([]);
    readonly candidateOptions = computed<PickerOption[]>(() =>
        this.candidates().map((i) => ({ value: i.id, label: `[${displayStatus(i)}] ${i.title} (${i.id})` })),
    );
    readonly saving = signal(false);

    constructor() {
        const excluded = new Set(this.data.exclude);
        this.api.list({ type: 'INCIDENT', limit: 500 }).subscribe({
            next: (all) => this.candidates.set(all.filter((o) => !excluded.has(o.id))),
            error: () => this.candidates.set([]),
        });
    }

    add(): void {
        if (this.pick.invalid) {
            this.pick.markAsTouched();
            return;
        }
        this.saving.set(true);
        this.api.link(this.data.caseId, this.pick.value!, 'CONTAINS', currentOperator(this.session.actor())).subscribe({
            next: () => {
                this.toastr.success('Member added');
                this.ref.close(true);
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(e, 'Add failed'));
            },
        });
    }
}
