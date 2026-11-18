import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    Space,
    SPACE_LOCATIONS_DISABLED_MESSAGE,
    SpaceLocationCheckResult,
    SpacesService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { LocationChecksComponent } from 'app/inspecto/spaces/location-checks.component';

/**
 * **Attach existing Space** (SPACE-LOCATIONS-1 D3): name a Space folder kept outside the Spaces root, check it, and
 * attach it; the server starts the Space at once. Attach is enabled only after a check of the folder as it stands
 * passed. Closes with the attached {@link Space}.
 */
@Component({
    selector: 'app-attach-space-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        InspectoAlertComponent,
        PenFieldComponent,
        LocationChecksComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Attach existing Space</h2>
        <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-dialog-content class="flex flex-col gap-3">
                @if (spaces.locationsDisabled()) {
                    <inspecto-alert variant="info" data-testid="locations-disabled">{{
                        disabledMessage
                    }}</inspecto-alert>
                } @else {
                    <p class="text-secondary m-0 text-sm">
                        A Space folder kept outside the Spaces root, such as one copied from another server. It is
                        started at once; its files stay where they are.
                    </p>
                    <inspecto-pen-field
                        cdkFocusInitial
                        label="Space folder"
                        [required]="true"
                        [mono]="true"
                        formControlName="path"
                        help="The full path of the folder. Its name is the Space's id."
                        [error]="pathError()"
                    ></inspecto-pen-field>
                    <inspecto-pen-field
                        label="Id"
                        [mono]="true"
                        formControlName="id"
                        help="Optional. When given, it must be the folder's name."
                        [error]="idError()"
                    ></inspecto-pen-field>
                    <div>
                        <button
                            type="button"
                            mat-stroked-button
                            data-testid="attach-check"
                            [disabled]="!path() || form.invalid || checking()"
                            (click)="check()"
                        >
                            Check
                        </button>
                    </div>
                    @if (checkError()) {
                        <p class="text-warn m-0 text-sm" role="alert">{{ checkError() }}</p>
                    }
                    <app-location-checks heading="Space folder" [result]="current()" />
                }
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button type="button" mat-button (click)="requestClose()">Cancel</button>
                @if (!spaces.locationsDisabled()) {
                    <button
                        type="submit"
                        mat-flat-button
                        color="primary"
                        data-testid="attach-save"
                        [disabled]="!passed() || saving()"
                    >
                        Attach
                    </button>
                }
            </mat-dialog-actions>
        </form>
    `,
})
export class AttachSpaceDialog {
    protected readonly spaces = inject(SpacesService);
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<AttachSpaceDialog, Space>);
    private confirm = inject(InspectoConfirmService);

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);
    protected readonly disabledMessage = SPACE_LOCATIONS_DISABLED_MESSAGE;

    readonly form = this.fb.nonNullable.group({
        path: ['', [Validators.required]],
        id: ['', [Validators.pattern(/^[a-z0-9][a-z0-9-]{0,62}$/)]],
    });
    private readonly values = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
    readonly path = computed(() => String(this.values().path ?? '').trim());
    private readonly id = computed(() => String(this.values().id ?? '').trim());

    readonly checking = signal(false);
    readonly saving = signal(false);
    readonly checkError = signal('');
    private readonly checked = signal<{ key: string; result: SpaceLocationCheckResult } | null>(null);

    private key(): string {
        return `${this.path()}\n${this.id()}`;
    }

    /** The last check, only while it describes the folder and id as they stand. */
    readonly current = computed(() => {
        const c = this.checked();
        return c && c.key === this.key() ? c.result : null;
    });
    readonly passed = computed(() => this.current()?.ok === true);

    pathError(): string {
        const c = this.form.controls.path;
        return c.touched && c.hasError('required') ? 'The Space folder is required.' : '';
    }

    idError(): string {
        const c = this.form.controls.id;
        return c.touched && c.hasError('pattern')
            ? 'Use a–z, 0–9, hyphen; start with a letter or digit; max 63 chars.'
            : '';
    }

    check(): void {
        const key = this.key();
        this.checking.set(true);
        this.checkError.set('');
        this.spaces.checkLocation(this.path(), 'space', this.id() || undefined).subscribe({
            next: (result) => {
                this.checking.set(false);
                this.checked.set({ key, result });
            },
            error: (e) => {
                this.checking.set(false);
                this.checked.set(null);
                if (!this.spaces.locationsDisabled())
                    this.checkError.set(apiErrorMessage(e, 'The folder could not be checked.'));
            },
        });
    }

    submit(): void {
        if (!this.passed() || this.saving()) return;
        this.saving.set(true);
        this.spaces.attach(this.path(), this.id() || undefined).subscribe({
            next: (space) => {
                this.saving.set(false);
                this.toastr.success(`Space "${space.id}" attached`);
                this.ref.close(space);
            },
            error: (e) => {
                this.saving.set(false);
                if (!this.spaces.locationsDisabled())
                    this.toastr.error(apiErrorMessage(e, 'The Space could not be attached.'));
            },
        });
    }
}
