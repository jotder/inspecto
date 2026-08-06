import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import { Observable, catchError, map, of } from 'rxjs';
import { apiErrorMessage, ComponentDef, ComponentsService, Finding } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import {
    CellFinding, CsvImport, EditableGridColumn, EditableGridComponent,
} from 'app/inspecto/components/editable-grid.component';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { ComponentFormResult } from './component-form.dialog';

/** Dialog data: `def` set ⇒ edit an existing mapping; absent ⇒ create. */
export interface MappingEditorData {
    def?: ComponentDef;
}

/** The inline notice shown after an Import CSV. */
interface ImportNote {
    variant: 'success' | 'warning' | 'error';
    title: string;
    message: string;
}

/** One row of the import diff, keyed by target column (a mapping's natural key). */
interface RuleDiff {
    change: 'Added' | 'Removed' | 'Changed';
    targetColumn: string;
    before: string;
    after: string;
}

/** How a rule reads in the diff — its source expression plus a non-DIRECT transform type. */
function ruleText(r: Record<string, string> | undefined): string {
    if (!r) return '';
    const type = (r['transformType'] ?? '').trim().toUpperCase();
    const source = (r['sourceExpression'] ?? '').trim();
    return type && type !== 'DIRECT' ? `${type}(${source})` : source;
}

/**
 * Diff two rule lists by target column. Deliberately a RULE diff, not an output-row diff: showing the
 * rows this mapping would produce needs a dry-run over real sample data, which is a separate backend
 * capability (see the S6b note in the ELT amendment UI plan).
 */
export function diffRules(
    before: Record<string, string>[],
    after: Record<string, string>[],
): RuleDiff[] {
    const key = (r: Record<string, string>): string => (r['targetColumn'] ?? '').trim();
    const beforeBy = new Map(before.filter((r) => key(r)).map((r) => [key(r), r]));
    const afterBy = new Map(after.filter((r) => key(r)).map((r) => [key(r), r]));
    const out: RuleDiff[] = [];
    for (const [target, r] of afterBy) {
        const prior = beforeBy.get(target);
        if (!prior) out.push({ change: 'Added', targetColumn: target, before: '', after: ruleText(r) });
        else if (ruleText(prior) !== ruleText(r))
            out.push({ change: 'Changed', targetColumn: target, before: ruleText(prior), after: ruleText(r) });
    }
    for (const [target, r] of beforeBy) {
        if (!afterBy.has(target))
            out.push({ change: 'Removed', targetColumn: target, before: ruleText(r), after: '' });
    }
    return out;
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
        MatIconModule, MatInputModule, EditableGridComponent, InspectoAlertComponent, ChipComponent,
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
            @if (importNote(); as note) {
                <inspecto-alert class="mb-3 block" [variant]="note.variant" [title]="note.title">
                    {{ note.message }}
                </inspecto-alert>
            }
            @if (diff().length) {
                <div class="mb-3">
                    <h3 class="mb-1 text-sm font-medium">What this import changes</h3>
                    <div class="max-h-48 overflow-auto">
                        <table class="w-full text-sm">
                            <caption class="sr-only">Mapping rule changes from the imported file</caption>
                            <thead>
                                <tr class="text-secondary text-left">
                                    <th scope="col" class="py-1 pr-2 font-medium">Change</th>
                                    <th scope="col" class="py-1 pr-2 font-medium">Target column</th>
                                    <th scope="col" class="py-1 pr-2 font-medium">Before</th>
                                    <th scope="col" class="py-1 font-medium">After</th>
                                </tr>
                            </thead>
                            <tbody>
                                @for (d of diff(); track d.targetColumn) {
                                    <tr class="border-t">
                                        <td class="py-1 pr-2"><inspecto-chip variant="soft">{{ d.change }}</inspecto-chip></td>
                                        <td class="py-1 pr-2 font-mono">{{ d.targetColumn }}</td>
                                        <td class="text-secondary py-1 pr-2 font-mono">{{ d.before || '—' }}</td>
                                        <td class="py-1 font-mono">{{ d.after || '—' }}</td>
                                    </tr>
                                }
                            </tbody>
                        </table>
                    </div>
                </div>
            }
            @if (findings().length) {
                <div class="mb-3" role="alert">
                    <h3 class="mb-1 text-sm font-medium">{{ findings().length }} problem(s) in these rules</h3>
                    <ul class="list-disc pl-5 text-sm">
                        @for (f of findings(); track $index) {
                            <li>{{ f.message }}</li>
                        }
                    </ul>
                </div>
            }
            <inspecto-editable-grid
                [columns]="columns"
                [rows]="rows()"
                [findings]="cellFindings()"
                [csvName]="(data.def?.name ?? 'mapping') + '.csv'"
                (rowsChange)="onRows($event)"
                (imported)="onImported($event)"
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

    /** Server findings for the current rows, anchored `rules[N].<key>` (empty until a validate runs). */
    readonly findings = signal<Finding[]>([]);
    /** The last import's header outcome, shown as an inline alert. */
    readonly importNote = signal<ImportNote | null>(null);
    /** Rule-level diff of the last import against the rows it replaced. */
    readonly diff = signal<RuleDiff[]>([]);

    /**
     * Findings mapped onto grid cells. `rules[N].<key>` is already row-index-anchored, so unlike the
     * schema editor (which resolves `raw.fields[NAME]` by name) this is a direct translation.
     */
    readonly cellFindings = computed<ReadonlyMap<string, CellFinding>>(() => {
        const map = new Map<string, CellFinding>();
        for (const f of this.findings()) {
            const m = /^rules\[(\d+)]\.(\w+)$/.exec(f.fieldPath);
            if (!m) continue;   // a whole-set finding has no cell to mark; the list above still shows it
            map.set(`${m[1]}|${m[2]}`, {
                severity: f.severity === 'ERROR' ? 'error' : 'warning',
                message: f.message,
            });
        }
        return map;
    });

    private dirty = false;
    /** The rules as they stood before the last import — the left side of the diff. */
    private beforeImport: Record<string, string>[] = this.rows().map((r) => ({ ...r }));

    readonly requestClose = guardDirtyClose(this.ref, () => this.dirty, this.confirm);

    onRows(rows: Record<string, string>[]): void {
        this.rows.set(rows);
        this.dirty = true;
        this.findings.set([]);   // the rows moved on; stale cell marks would point at the wrong cells
    }

    /**
     * One Import CSV: report what the header matched, diff the new rules against the old, and validate
     * them server-side. The rows are already in the grid (the grid applies the import) — this is the
     * review step, and Save is what commits.
     */
    onImported(imported: CsvImport): void {
        if (!imported.applied) {
            this.diff.set([]);
            this.importNote.set({
                variant: 'error',
                title: `Nothing imported from ${imported.fileName}`,
                message: `No column of that file matched this mapping. Expected a header with `
                    + `${this.columns.map((c) => c.key).join(', ')}. The rules were left unchanged.`,
            });
            return;
        }
        this.diff.set(diffRules(this.beforeImport, imported.rows));
        this.beforeImport = imported.rows.map((r) => ({ ...r }));
        const problems = [
            imported.unknownHeaders.length
                ? `ignored unrecognised column(s): ${imported.unknownHeaders.join(', ')}`
                : '',
            imported.missingColumns.length
                ? `imported blank for absent column(s): ${imported.missingColumns.join(', ')}`
                : '',
        ].filter(Boolean);
        this.importNote.set({
            variant: problems.length ? 'warning' : 'success',
            title: `Imported ${imported.rows.length} rule(s) from ${imported.fileName}`,
            message: problems.length
                ? `Review before saving — ${problems.join('; ')}.`
                : 'Review the changes below, then Save to apply them.',
        });
        this.validate().subscribe();
    }

    /** Validate the current rows server-side, storing the findings. Never throws; transport errors warn. */
    private validate(): Observable<boolean> {
        return this.api.validateMapping(this.ruleRows()).pipe(
            map((res) => {
                this.findings.set(res.findings);
                return res.clean;
            }),
            catchError((err: unknown) => {
                // Advisory only: `PUT /components/mapping/{id}` runs the SAME gate server-side, so a
                // mapping that fails it still cannot be saved — the operator just loses the preview.
                this.findings.set([]);
                this.toast.warning(apiErrorMessage(err, 'Could not check the rules; Save will still check them'));
                return of(true);
            }),
        );
    }

    /** The non-empty rows as trimmed rule objects — what both validate and save send. */
    private ruleRows(): Record<string, string>[] {
        return this.rows()
            .filter((r) => Object.values(r).some((v) => v.trim().length))
            .map((r) => ({
                targetColumn: r.targetColumn.trim(),
                sourceExpression: r.sourceExpression.trim(),
                transformType: r.transformType.trim(),
            }));
    }

    save(): void {
        this.id.markAsTouched();
        if (!this.isEdit && this.id.invalid) return;
        const rules = this.ruleRows();
        if (!rules.length) {
            this.toast.error('A mapping needs at least one rule row.');
            return;
        }
        this.saving.set(true);
        this.validate().subscribe((clean) => {
            if (!clean) {
                this.saving.set(false);
                this.toast.error('Fix the highlighted rules before saving.');
                return;
            }
            this.write(rules);
        });
    }

    private write(rules: Record<string, string>[]): void {
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
