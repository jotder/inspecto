import { ChangeDetectionStrategy, Component, inject, OnInit, signal, computed } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import {
    InspectoOptionPickerComponent,
    PickerOption,
    pickerOptions,
} from 'app/inspecto/components/option-picker.component';

/** Create a correlation link from one object to another — POST /objects/{id}/links. */
@Component({
    selector: 'app-object-link-dialog',
    standalone: true,
    imports: [InspectoOptionPickerComponent, ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Link this {{ data.fromType }}</h2>
        <mat-dialog-content class="flex flex-col gap-3 pt-2" style="min-width: 26rem">
            <form [formGroup]="form" class="flex flex-col gap-3">
                <inspecto-option-picker label="Relationship" [options]="relationships" formControlName="relationship" />
                <div>
                    <inspecto-option-picker label="Target object" [options]="candidateOptions()" formControlName="to" />
                    @if (form.controls.to.hasError('required') && form.controls.to.touched) {
                        <p class="text-warn m-0 text-xs" role="alert">Pick a target object to link to.</p>
                    }
                </div>
            </form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="saving()" (click)="save()">Link</button>
        </mat-dialog-actions>
    `,
})
export class ObjectLinkDialog implements OnInit {
    private api = inject(ObjectsService);
    private ref = inject(MatDialogRef<ObjectLinkDialog>);
    private confirm = inject(InspectoConfirmService);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);
    readonly data = inject<{ fromId: string; fromType: string }>(MAT_DIALOG_DATA);

    readonly candidates = signal<OperationalObject[]>([]);
    readonly candidateOptions = computed<PickerOption[]>(() =>
        this.candidates().map((o) => ({ value: o.id, label: `${o.objectType} · ${o.title || o.id} (${o.status})` })),
    );
    readonly relationships = pickerOptions(['CONTAINS', 'ESCALATED_FROM', 'CAUSED_BY', 'RELATED_TO']);
    readonly saving = signal(false);
    readonly form = this.fb.group({
        relationship: ['RELATED_TO', Validators.required],
        to: ['', Validators.required],
    });

    ngOnInit(): void {
        this.api.list({ limit: 200 }).subscribe({
            next: (os) => this.candidates.set(os.filter((o) => o.id !== this.data.fromId)),
            error: () => this.candidates.set([]),
        });
    }

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.saving.set(true);
        const { to, relationship } = this.form.getRawValue();
        this.api.link(this.data.fromId, to, relationship).subscribe({
            next: (l) => {
                this.toastr.success(`Linked: ${l.relationship} → ${l.to}`);
                this.ref.close(l);
            },
            error: (e) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(e, 'Link failed'));
            },
        });
    }
}
