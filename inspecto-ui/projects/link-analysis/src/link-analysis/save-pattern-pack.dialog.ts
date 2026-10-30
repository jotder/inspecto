import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage, ComponentsService, heldChange, PendingChange } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { guardDirtyClose } from '@inspecto/core/dialog-dirty-guard';
import { PatternPack, patternPackId, patternPackToContent } from './pattern-packs';

export interface SavePatternPackData {
    /** The motif in the builder: steps, or branching stages. */
    steps: PatternPack['steps'];
    stages: PatternPack['stages'] | null;
    /** Every pack id already in the picker — a new name must not take one (a built-in would be silently overridden). */
    takenIds: string[];
}

const NOT_BLANK = /\S/;
/** What the dialog closes with: the pack, and the held change when a maker-checker policy held the write. */
export interface SavePatternPackResult {
    pack: PatternPack;
    held: PendingChange | null;
}

const CATEGORIES: PickerOption[] = [
    { value: 'money', label: 'Money' },
    { value: 'telecom', label: 'Telecom' },
    { value: 'identity', label: 'Identity' },
];

/**
 * `LA-PRODUCT-NEXT-1` phase 5b — **Save as pattern pack**: the builder's motif becomes a `pattern-pack` component of this
 * Space (`POST /components/pattern-pack`, `canAuthorWorkbench`), with a name, a category and a description, so it is in
 * every analyst's Pattern picker. The dialog makes the call and closes with the saved pack; a refusal stays in the
 * dialog.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    template: `
        <h2 mat-dialog-title>Save as pattern pack</h2>
        <mat-dialog-content class="flex flex-col gap-3 text-sm">
            <p class="text-secondary m-0">
                Everyone in this Space can then pick it from the Pattern list. It saves
                {{ data.stages?.length ? data.stages!.length + ' branching stages' : data.steps.length + ' steps' }}.
            </p>
            <form [formGroup]="form" (ngSubmit)="submit()" class="flex flex-col gap-3">
                <inspecto-pen-field
                    label="Name"
                    formControlName="label"
                    [maxlength]="80"
                    [error]="labelError()"
                    data-testid="pack-name"
                ></inspecto-pen-field>
                <inspecto-option-picker
                    label="Category"
                    [options]="categories"
                    formControlName="category"
                ></inspecto-option-picker>
                <inspecto-pen-field
                    label="Description"
                    formControlName="description"
                    [maxlength]="500"
                    [error]="descriptionError()"
                    data-testid="pack-description"
                ></inspecto-pen-field>
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Not saved">{{ error() }}</inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button
                mat-flat-button
                color="primary"
                type="button"
                data-testid="pack-save"
                [disabled]="busy()"
                (click)="submit()"
            >
                Save pack
            </button>
        </mat-dialog-actions>
    `,
})
export class SavePatternPackDialog {
    readonly data = inject<SavePatternPackData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<SavePatternPackDialog, SavePatternPackResult | undefined>);
    private confirm = inject(InspectoConfirmService);
    private components = inject(ComponentsService);
    readonly categories = CATEGORIES;

    readonly form = new FormGroup({
        label: new FormControl('', { nonNullable: true }),
        category: new FormControl<PatternPack['category']>('money', { nonNullable: true }),
        description: new FormControl('', { nonNullable: true }),
    });
    private readonly submitted = signal(false);
    private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
    readonly busy = signal(false);
    readonly error = signal('');

    readonly labelError = computed(() => {
        if (!this.submitted()) return '';
        const label = this.value().label ?? '';
        if (!NOT_BLANK.test(label)) return 'Name the pack.';
        const id = patternPackId(label);
        if (!id) return 'Use at least one letter or digit.';
        return this.data.takenIds.includes(id) ? 'A pack with this name exists. Choose another name.' : '';
    });
    readonly descriptionError = computed(() =>
        this.submitted() && !NOT_BLANK.test(this.value().description ?? '') ? 'Say what the pattern looks for.' : '',
    );
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    async submit(): Promise<void> {
        if (this.busy()) return;
        this.submitted.set(true);
        this.form.markAllAsTouched();
        if (this.labelError() || this.descriptionError()) return;
        const v = this.form.getRawValue();
        const pack: PatternPack = {
            id: patternPackId(v.label),
            label: v.label.trim(),
            category: v.category,
            description: v.description.trim(),
            steps: this.data.stages?.length ? [] : this.data.steps.map((s) => ({ ...s })),
            ...(this.data.stages?.length ? { stages: this.data.stages.map((s) => ({ ...s })) } : {}),
        };
        this.busy.set(true);
        this.error.set('');
        try {
            const res = await firstValueFrom(this.components.create('pattern-pack', patternPackToContent(pack)));
            // Under a maker-checker policy for pattern packs the write is HELD (202): nothing is saved yet.
            this.ref.close({ pack, held: heldChange(res) });
        } catch (err) {
            this.error.set(apiErrorMessage(err, 'The pack could not be saved.'));
        } finally {
            this.busy.set(false);
        }
    }
}
