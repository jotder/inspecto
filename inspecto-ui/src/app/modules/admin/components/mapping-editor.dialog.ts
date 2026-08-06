import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ComponentDef, ComponentsService } from 'app/inspecto/api';
import { EditableGridComponent, EditableGridColumn } from 'app/inspecto/components/editable-grid.component';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { ComponentFormResult } from './component-form.dialog';

/** Dialog data: `def` set ⇒ edit an existing mapping; absent ⇒ create. */
export interface MappingEditorData {
    def?: ComponentDef;
}

/** One mapping rule row — MappingCsv's canonical columns, verbatim (attribute key = config key). */
const COLUMNS: EditableGridColumn[] = [
    { key: 'targetColumn', label: 'Target column' },
    { key: 'sourceExpression', label: 'Source expression' },
    // TransformCompiler's vocabulary: DIRECT (or blank), EXPR, CONCAT_DT, FILENAME_DATE.
    { key: 'transformType', label: 'Transform type', options: ['DIRECT', 'EXPR', 'CONCAT_DT', 'FILENAME_DATE'] },
];

/**
 * The mapping-CSV grid editor (ELT amendment UI plan §2.4, S5): a `mapping` component's
 * `rules[{targetColumn, sourceExpression, transformType}]` edited as a flat grid over the shared
 * `<inspecto-editable-grid>` — never TOON/JSON text. Saves through the generic component CRUD
 * (`POST/PUT /components/mapping/{id}`) with the rules as JSON; the server transcodes to the
 * mapping CSV at the file layer (`ComponentStore`/`MappingCsv`). The BACKWARD compatibility gate
 * does NOT fire on this surface (it is `/config/write type=schema` only — S5 grounding note).
 */
@Component({
    selector: 'app-mapping-editor-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule,
        MatIconModule, MatInputModule, EditableGridComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title class="flex min-w-0 items-center gap-2">
            <mat-icon class="shrink-0" svgIcon="heroicons_outline:table-cells"></mat-icon>
            <span class="min-w-0 truncate">{{ isEdit ? 'Edit Mapping · ' + data.def!.name : 'New Mapping' }}</span>
        </h2>
        <mat-dialog-content>
            @if (!isEdit) {
                <mat-form-field class="w-full" appearance="outline" subscriptSizing="dynamic">
                    <mat-label>Id</mat-label>
                    <input matInput [formControl]="id" cdkFocusInitial />
                    @if (id.hasError('required') && id.touched) {
                        <mat-error>An id is required.</mat-error>
                    }
                    <mat-hint>Unique — this is the mapping id pipeline Steps reference.</mat-hint>
                </mat-form-field>
            }
            <p class="text-secondary mb-2 mt-3 text-sm">
                One row per target column. Transform type DIRECT (or blank) copies the source column;
                EXPR treats the source expression as a DuckDB scalar expression, emitted verbatim.
            </p>
            <inspecto-editable-grid
                [columns]="columns"
                [rows]="rows()"
                [csvName]="(data.def?.name ?? 'mapping') + '.csv'"
                (rowsChange)="onRows($event)"
            ></inspecto-editable-grid>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-stroked-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" type="button" (click)="save()" [disabled]="saving()">Save</button>
        </mat-dialog-actions>
    `,
})
export class MappingEditorDialog {
    private readonly api = inject(ComponentsService);
    private readonly toast = inject(ToastrService);
    private readonly fb = inject(FormBuilder);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly ref = inject(MatDialogRef<MappingEditorDialog, ComponentFormResult>);
    readonly data = inject<MappingEditorData>(MAT_DIALOG_DATA);

    readonly isEdit = !!this.data.def;
    readonly columns = COLUMNS;
    readonly id = this.fb.nonNullable.control(this.data.def?.name ?? '', Validators.required);
    readonly saving = signal(false);

    /** The rule rows as strings — verbatim from the component content, unknown keys preserved on save. */
    readonly rows = signal<Record<string, string>[]>(
        ((this.data.def?.content?.['rules'] as Record<string, unknown>[] | undefined) ?? [])
            .map((r) => ({
                targetColumn: String(r['targetColumn'] ?? ''),
                sourceExpression: String(r['sourceExpression'] ?? ''),
                transformType: String(r['transformType'] ?? ''),
            })),
    );

    private dirty = false;

    readonly requestClose = guardDirtyClose(this.ref, () => this.dirty, this.confirm);

    onRows(rows: Record<string, string>[]): void {
        this.rows.set(rows);
        this.dirty = true;
    }

    save(): void {
        this.id.markAsTouched();
        if (!this.isEdit && this.id.invalid) return;
        const rules = this.rows()
            .filter((r) => Object.values(r).some((v) => v.trim().length))
            .map((r) => ({
                targetColumn: r.targetColumn.trim(),
                sourceExpression: r.sourceExpression.trim(),
                transformType: r.transformType.trim(),
            }));
        if (!rules.length) {
            this.toast.error('A mapping needs at least one rule row.');
            return;
        }
        // preserve any content keys beyond rules (verbatim-sections rule)
        const content = { ...(this.data.def?.content ?? {}), rules };
        const id = this.isEdit ? this.data.def!.name : this.id.value.trim();
        const req$ = this.isEdit
            ? this.api.update('mapping', id, content)
            : this.api.create('mapping', { id, ...content });
        this.saving.set(true);
        req$.subscribe({
            next: (saved) => {
                this.toast.success(`Saved mapping '${saved.name}'`);
                this.dirty = false;
                this.ref.close({ saved });
            },
            error: (err) => {
                this.saving.set(false);
                if ((err as { status?: number })?.status === 503) {
                    this.ref.close({ writesDisabled: true });
                    return;
                }
                this.toast.error(apiErrorMessage(err, 'Could not save the mapping'));
            },
        });
    }
}
