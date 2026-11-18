import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
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

export interface DataFolderData {
    space: Space;
}

/**
 * **Change data folder** (SPACE-LOCATIONS-1 D5): point a Space that holds no data yet at another Data folder, or put
 * its data back in the Space folder. A new folder is checked first; Save is enabled only after that check passed for
 * the folder as it stands. The server restarts the Space. Closes with the updated {@link Space}.
 */
@Component({
    selector: 'app-data-folder-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatRadioModule,
        InspectoAlertComponent,
        PenFieldComponent,
        LocationChecksComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Change data folder</h2>
        <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-dialog-content class="flex flex-col gap-3">
                @if (spaces.locationsDisabled()) {
                    <inspecto-alert variant="info" data-testid="locations-disabled">{{
                        disabledMessage
                    }}</inspecto-alert>
                } @else {
                    <p class="text-secondary m-0 text-sm">
                        Space <span class="font-mono">{{ space.id }}</span> holds no data yet, so its Data folder can
                        change. The Space restarts.
                        @if (space.location?.dataFolder) {
                            Now: <span class="font-mono break-all">{{ space.location?.dataFolder }}</span>
                        }
                    </p>
                    <span id="data-folder-where" class="sr-only">Where the data goes</span>
                    <mat-radio-group
                        formControlName="where"
                        aria-labelledby="data-folder-where"
                        class="flex flex-col gap-1"
                    >
                        <mat-radio-button value="folder">Use another Data folder</mat-radio-button>
                        <mat-radio-button value="space">Keep the data in the Space folder</mat-radio-button>
                    </mat-radio-group>
                    @if (where() === 'folder') {
                        <inspecto-pen-field
                            label="Data folder"
                            [required]="true"
                            [mono]="true"
                            formControlName="path"
                            help="The full path of an empty folder, or one already used by this Space."
                        ></inspecto-pen-field>
                        <div>
                            <button
                                type="button"
                                mat-stroked-button
                                data-testid="data-check"
                                [disabled]="!path() || checking()"
                                (click)="check()"
                            >
                                Check
                            </button>
                        </div>
                        @if (checkError()) {
                            <p class="text-warn m-0 text-sm" role="alert">{{ checkError() }}</p>
                        }
                        <app-location-checks heading="Data folder" [result]="current()" />
                    }
                    @if (saveError()) {
                        <p class="text-warn m-0 text-sm" role="alert">{{ saveError() }}</p>
                    }
                }
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button type="button" mat-button (click)="requestClose()">Cancel</button>
                @if (!spaces.locationsDisabled()) {
                    <button
                        type="submit"
                        mat-flat-button
                        color="primary"
                        data-testid="data-save"
                        [disabled]="!canSave() || saving()"
                    >
                        Save
                    </button>
                }
            </mat-dialog-actions>
        </form>
    `,
})
export class DataFolderDialog {
    protected readonly spaces = inject(SpacesService);
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<DataFolderDialog, Space>);
    private confirm = inject(InspectoConfirmService);
    protected readonly space = inject<DataFolderData>(MAT_DIALOG_DATA).space;

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);
    protected readonly disabledMessage = SPACE_LOCATIONS_DISABLED_MESSAGE;

    readonly form = this.fb.nonNullable.group({ where: 'folder' as 'folder' | 'space', path: '' });
    private readonly values = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
    readonly where = computed(() => this.values().where ?? 'folder');
    readonly path = computed(() => String(this.values().path ?? '').trim());

    readonly checking = signal(false);
    readonly saving = signal(false);
    readonly checkError = signal('');
    readonly saveError = signal('');
    private readonly checked = signal<{ path: string; result: SpaceLocationCheckResult } | null>(null);

    /** The last check, only while it describes the folder as it stands. */
    readonly current = computed(() => {
        const c = this.checked();
        return c && c.path === this.path() ? c.result : null;
    });
    /** Back to the Space folder needs no check; a new Data folder needs one that passed. */
    readonly canSave = computed(() => this.where() === 'space' || this.current()?.ok === true);

    check(): void {
        const path = this.path();
        this.checking.set(true);
        this.checkError.set('');
        this.spaces.checkLocation(path, 'data', this.space.id).subscribe({
            next: (result) => {
                this.checking.set(false);
                this.checked.set({ path, result });
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
        if (!this.canSave() || this.saving()) return;
        const target = this.where() === 'space' ? null : this.path();
        this.saving.set(true);
        this.saveError.set('');
        this.spaces.setDataRoot(this.space.id, target).subscribe({
            next: (space) => {
                this.saving.set(false);
                this.toastr.success(
                    target
                        ? `Space "${this.space.id}" now keeps its data in ${target}`
                        : `Space "${this.space.id}" keeps its data in its Space folder`,
                );
                this.ref.close(space);
            },
            error: (e) => {
                this.saving.set(false);
                if (!this.spaces.locationsDisabled())
                    this.saveError.set(apiErrorMessage(e, 'The Data folder could not be changed.'));
            },
        });
    }
}
