import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { switchMap } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import type { EntityNormaliser } from '@inspecto/core/graph';
import {
    LinkAnalysisLimits,
    LinkAnalysisSettingsService,
} from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';
import {
    NORMALISER_OPTIONS,
    entityTypeIdFor,
    exampleKey,
    newEntityType,
    newEntityTypeProblem,
    settingsBodyWithTypes,
} from './entity-columns';

/**
 * **New Entity Type** (LA-ONBOARD-1 phase 5, UC-07 step 3) for a kind of thing the pack does not know: a name, an
 * example value and how values are compared. It is added to the Space's Link Analysis settings (`entityTypes`, the
 * stated list that replaces the defaults: the types in force plus this one), with the column classification it claims
 * being its id in upper case. The example is not stored; it shows how a value would be kept.
 */
@Component({
    selector: 'inspecto-la-new-entity-type',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    template: `
        <form
            class="bg-card flex flex-col gap-3 rounded-2xl p-5 shadow"
            aria-labelledby="new-entity-type-title"
            data-testid="new-entity-type"
            [formGroup]="form"
            (ngSubmit)="save()"
        >
            <h2 id="new-entity-type-title" class="m-0 text-base font-semibold">New Entity Type</h2>
            <div class="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2">
                <div>
                    <inspecto-pen-field
                        label="Name"
                        placeholder="For example Vehicle plate"
                        data-testid="net-name"
                        formControlName="name"
                        [required]="true"
                        [maxlength]="60"
                        [error]="nameError()"
                    ></inspecto-pen-field>
                    @if (idHint(); as h) {
                        <p class="text-secondary m-0 text-xs" data-testid="net-id">{{ h }}</p>
                    }
                </div>
                <div>
                    <inspecto-pen-field
                        label="Example value"
                        placeholder="One value as it appears in your data"
                        data-testid="net-example"
                        formControlName="example"
                        [maxlength]="200"
                    ></inspecto-pen-field>
                    @if (keptAs(); as k) {
                        <p class="text-secondary m-0 text-xs" data-testid="net-kept">{{ k }}</p>
                    }
                </div>
                <inspecto-option-picker
                    label="Compare values as"
                    data-testid="net-normaliser"
                    formControlName="normaliser"
                    [options]="normalisers"
                    [required]="true"
                ></inspecto-option-picker>
            </div>
            @if (saveError(); as e) {
                <inspecto-alert variant="error" data-testid="net-error">{{ e }}</inspecto-alert>
            }
            <div class="flex justify-end gap-2">
                <button mat-button type="button" data-testid="net-cancel" (click)="cancelled.emit()">Cancel</button>
                <button mat-flat-button color="primary" type="submit" data-testid="net-save" [disabled]="saving()">
                    Add Entity Type
                </button>
            </div>
        </form>
    `,
})
export class NewEntityTypeComponent {
    private readonly settings = inject(LinkAnalysisSettingsService);

    /** The type was written; its id. */
    readonly added = output<string>();
    readonly cancelled = output<void>();

    readonly normalisers = [...NORMALISER_OPTIONS];
    readonly form = new FormGroup({
        name: new FormControl('', { nonNullable: true }),
        example: new FormControl('', { nonNullable: true }),
        normaliser: new FormControl<string>('upper-trim', { nonNullable: true }),
    });
    private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
    readonly saving = signal(false);
    readonly saveError = signal('');
    private readonly submitted = signal(false);

    /** The early check against the types in force (the server repeats it): shown once there is a name or a submit. */
    readonly nameError = computed(() => {
        const name = this.value().name ?? '';
        if (!name.trim() && !this.submitted()) return '';
        return newEntityTypeProblem(name, this.settings.limits().entityTypesInForce ?? []);
    });
    readonly idHint = computed(() => {
        const id = entityTypeIdFor(this.value().name ?? '');
        return id ? `Columns classified ${id.toUpperCase()} hold it.` : '';
    });
    readonly keptAs = computed(() => {
        const v = this.value();
        const example = v.example ?? '';
        if (!example.trim()) return '';
        const key = exampleKey(example, (v.normaliser || 'upper-trim') as EntityNormaliser);
        return key ? `Kept as ${key}` : 'This value would be empty when compared this way.';
    });

    save(): void {
        this.submitted.set(true);
        if (this.saving() || this.nameError()) return;
        const v = this.form.getRawValue();
        this.saving.set(true);
        this.saveError.set('');
        // Read the settings again first: the PUT replaces the document, so it must start from what is stored now.
        this.settings
            .get()
            .pipe(
                switchMap((served) => {
                    const problem = newEntityTypeProblem(v.name, served.entityTypesInForce ?? []);
                    if (problem) throw new Error(problem);
                    const type = newEntityType(v.name, v.normaliser as EntityNormaliser);
                    const body = settingsBodyWithTypes(served, [...(served.entityTypesInForce ?? []), type]);
                    return this.settings.save(body as unknown as LinkAnalysisLimits);
                }),
            )
            .subscribe({
                next: () => {
                    this.saving.set(false);
                    this.added.emit(entityTypeIdFor(v.name));
                },
                error: (e) => {
                    this.saving.set(false);
                    this.saveError.set(
                        e instanceof Error ? e.message : apiErrorMessage(e, 'The Entity Type could not be added.'),
                    );
                },
            });
    }
}
