import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { map, Observable } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ComponentDef, ComponentsService, ConfigService, Finding } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import {
    CellFinding,
    EditableGridComponent,
    EditableGridColumn,
} from 'app/inspecto/components/editable-grid.component';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { ComponentFormResult } from './component-form.dialog';

/** Dialog data: `def` set ⇒ edit an existing schema; absent ⇒ create. `sampleRows` (already-parsed
 *  rows, e.g. a parse dialog's test-parse output) arms the "Suggest from sample" draft inference. */
export interface SchemaEditorData {
    def?: ComponentDef;
    sampleRows?: Record<string, unknown>[];
}

/** One typed field row — `ConfigSpecs.schema()`'s `raw.fields[]` keys, verbatim. */
const COLUMNS: EditableGridColumn[] = [
    { key: 'name', label: 'Field name' },
    { key: 'selector', label: 'Selector' },
    // Free-text DuckDB SQL type — the server enforces no enum, only the BACKWARD widening lattice
    // on EDIT, so a select here would refuse types the server accepts (mock-strictness, inverted).
    { key: 'type', label: 'Type' },
    { key: 'description', label: 'Description' },
    { key: 'unit', label: 'Unit' },
    { key: 'classification', label: 'Classification' },
];

/**
 * The schema grid editor (ELT amendment UI plan §2.4, S5b): a `schema` component's `raw.fields[]`
 * edited as a flat grid over the shared `<inspecto-editable-grid>`.
 *
 * An EDIT saves through `PUT /components/schema/{id}` — the registry component this dialog was opened
 * over — carrying the list's `contentHash` as an `If-Match` precondition, so a concurrent edit is
 * refused (409) rather than silently clobbered. A CREATE still goes to `POST /config/write
 * type=schema`, which is what the parse editor's "author a schema for this pipeline" path wants.
 *
 * 🔴 These two routes write DIFFERENT FILES, which is why the edit case moved (SCHEMA-DIALOG-IFMATCH-1):
 * `/config/write` with no subdir lands at `<write-root>/<name>.toon`, never at
 * `registry/schemas/<id>.toon`, so edits used to be written beside the component and lost.
 *
 * A 422 refusal carries cell-anchored findings (`raw.fields[NAME]` / `.type` / `.selector`) which this
 * dialog translates onto grid cells by field NAME, plus a `role="alert"` summary; the deliberate escape
 * hatch is a confirmed re-save with `compatibility: "none"` (a query param on the component route, a
 * body key on `/config/write` — the service hides that difference).
 */
@Component({
    selector: 'app-schema-editor-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        EditableGridComponent,
        InspectoAlertComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title class="flex min-w-0 items-center gap-2">
            <mat-icon class="shrink-0" svgIcon="heroicons_outline:table-cells"></mat-icon>
            <span class="min-w-0 truncate">{{ isEdit ? 'Edit Schema · ' + data.def!.name : 'New Schema' }}</span>
        </h2>
        <mat-dialog-content>
            @if (!isEdit) {
                <mat-form-field class="w-full" appearance="outline" subscriptSizing="dynamic">
                    <mat-label>Name</mat-label>
                    <input matInput [formControl]="name" cdkFocusInitial />
                    @if (name.hasError('required') && name.touched) {
                        <mat-error>A name is required.</mat-error>
                    }
                    <mat-hint>Unique — this is the schema's identity (raw.name).</mat-hint>
                </mat-form-field>
            }
            <p class="text-secondary mb-2 mt-3 text-sm">
                One row per typed field. Selector is the raw column the field reads from; type is a DuckDB SQL type
                (VARCHAR, INTEGER, BIGINT, DOUBLE, DATE, TIMESTAMP, …).
            </p>
            <inspecto-editable-grid
                [columns]="columns"
                [rows]="rows()"
                [findings]="cellFindings()"
                [csvName]="(data.def?.name ?? 'schema') + '-fields.csv'"
                (rowsChange)="onRows($event)"
            ></inspecto-editable-grid>
            @if (findings().length) {
                <div role="alert" class="mt-3">
                    <inspecto-alert variant="error" title="Not saved — this edit is not BACKWARD-compatible">
                        <ul class="list-disc pl-5">
                            @for (f of findings(); track $index) {
                                <li>{{ f.message }}</li>
                            }
                        </ul>
                    </inspecto-alert>
                </div>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            @if (data.sampleRows?.length) {
                <button mat-stroked-button type="button" (click)="suggestFromSample()" [disabled]="suggesting()">
                    <mat-icon class="icon-size-4 mr-1" svgIcon="heroicons_outline:sparkles"></mat-icon>
                    Suggest from sample
                </button>
            }
            @if (refused()) {
                <button mat-stroked-button type="button" (click)="saveAnyway()">
                    Save anyway (skip compatibility check)
                </button>
            }
            <span class="flex-1"></span>
            <button mat-stroked-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" type="button" (click)="save()" [disabled]="saving()">Save</button>
        </mat-dialog-actions>
    `,
})
export class SchemaEditorDialog {
    private readonly config = inject(ConfigService);
    private readonly components = inject(ComponentsService);
    private readonly toast = inject(ToastrService);
    private readonly fb = inject(FormBuilder);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly ref = inject(MatDialogRef<SchemaEditorDialog, ComponentFormResult>);
    readonly data = inject<SchemaEditorData>(MAT_DIALOG_DATA);

    readonly isEdit = !!this.data.def;
    readonly columns = COLUMNS;
    readonly name = this.fb.nonNullable.control(this.data.def?.name ?? '', Validators.required);
    readonly saving = signal(false);
    /** The last 422 refusal's findings — cleared on any edit or successful save. */
    readonly findings = signal<Finding[]>([]);
    /** True while the last save was REFUSED (422) — arms the compatibility:"none" affordance. */
    readonly refused = signal(false);

    /** The field rows as strings — from `raw.fields[]`, verbatim keys. */
    readonly rows = signal<Record<string, string>[]>(
        (
            ((this.data.def?.content?.['raw'] as Record<string, unknown> | undefined)?.['fields'] as
                | Record<string, unknown>[]
                | undefined) ?? []
        ).map((f) => Object.fromEntries(COLUMNS.map((c) => [c.key, String(f[c.key] ?? '')]))),
    );

    /** Findings translated onto grid cells (`"<rowIndex>|<colKey>"`) by field NAME. */
    readonly cellFindings = computed<ReadonlyMap<string, CellFinding>>(() => {
        const map = new Map<string, CellFinding>();
        const rows = this.rows();
        for (const f of this.findings()) {
            const m = /^raw\.fields\[(.+?)\](?:\.(\w+))?$/.exec(f.fieldPath);
            if (!m) continue;
            const rowIndex = rows.findIndex((r) => r['name'].trim() === m[1]);
            if (rowIndex < 0) continue; // e.g. a removed field — no cell to anchor to
            const col = m[2] && COLUMNS.some((c) => c.key === m[2]) ? m[2] : 'name';
            map.set(`${rowIndex}|${col}`, {
                severity: f.severity === 'ERROR' ? 'error' : 'warning',
                message: f.message,
            });
        }
        return map;
    });

    /** True while the suggest route is in flight — gates the button, never the grid. */
    readonly suggesting = signal(false);

    private dirty = false;

    readonly requestClose = guardDirtyClose(this.ref, () => this.dirty, this.confirm);

    /**
     * Fill the grid with a DRAFT inferred from the dialog's `sampleRows` (`POST
     * /config/suggest/schema`, TRY_CAST voting). The draft only seeds the grid — the human still
     * reviews and saves; nothing is written here. Confirms first when it would replace named rows.
     */
    async suggestFromSample(): Promise<void> {
        const sampleRows = this.data.sampleRows ?? [];
        if (!sampleRows.length) return;
        if (this.rows().some((r) => r['name'].trim().length)) {
            const ok = await this.confirm.confirmDestructive(
                'Replace the current field rows with types suggested from the sample? Nothing is saved ' +
                    'until you review and press Save.',
                { title: 'Suggest from sample' },
            );
            if (!ok) return;
        }
        this.suggesting.set(true);
        this.config.suggestSchema(sampleRows).subscribe({
            next: (s) => {
                this.suggesting.set(false);
                this.onRows(
                    s.fields.map((f) =>
                        Object.fromEntries(
                            COLUMNS.map((c) => [c.key, String((f as unknown as Record<string, unknown>)[c.key] ?? '')]),
                        ),
                    ),
                );
                this.toast.success(`Suggested ${s.fields.length} typed field(s) — review, then Save.`);
            },
            error: (err) => {
                this.suggesting.set(false);
                this.toast.error(apiErrorMessage(err, 'Could not suggest a schema from the sample'));
            },
        });
    }

    onRows(rows: Record<string, string>[]): void {
        this.rows.set(rows);
        this.dirty = true;
        this.refused.set(false);
        this.findings.set([]);
    }

    save(): void {
        this.doSave(false);
    }

    /** The deliberate escape hatch: re-save with `compatibility: "none"` after a confirmed prompt. */
    async saveAnyway(): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            'Skip the BACKWARD compatibility check and save anyway? Already-written data or raw files ' +
                'may no longer read back correctly.',
            { title: 'Skip compatibility check' },
        );
        if (ok) this.doSave(true);
    }

    private doSave(overrideCompatibility: boolean): void {
        this.name.markAsTouched();
        if (!this.isEdit && this.name.invalid) return;
        const fields = this.rows()
            .filter((r) => r['name'].trim().length)
            .map((r) =>
                Object.fromEntries(
                    COLUMNS.map((c): [string, string] => [c.key, r[c.key]?.trim() ?? ''])
                        // name/selector/type always travel; the optional columns only when non-blank
                        .filter(([k, v]) => v.length || k === 'name' || k === 'selector' || k === 'type'),
                ),
            );
        if (!fields.length) {
            this.toast.error('A schema needs at least one named field.');
            return;
        }
        const content = this.data.def?.content ?? {};
        const rawIn = (content['raw'] as Record<string, unknown> | undefined) ?? {};
        const name = this.isEdit ? this.data.def!.name : this.name.value.trim();
        // preserve non-fields raw keys (format, …) and non-raw content sections (mapping) verbatim
        const config = { ...content, raw: { ...rawIn, name, fields } };
        this.saving.set(true);
        // An EDIT goes to the registry component this dialog was opened OVER, not to /config/write.
        // 🔴 SCHEMA-DIALOG-IFMATCH-1: those are different files. A registry schema is
        // `registry/schemas/<id>.toon`; a `/config/write type=schema` with no subdir lands at
        // `<write-root>/<name>.toon`, so every edit made here wrote a second, unrelated config beside
        // the registry — the listed component was untouched, the pane's reload showed the PRE-edit
        // content, and the engine (which resolves `schema/<id>` against the registry) never saw it.
        // Proven by `ControlApiComponentsTest.configWriteSchemaDoesNotUpdateTheRegistryComponentOfTheSameName`.
        //
        // ⚠ The comment this replaces said the component CRUD "bypasses the BACKWARD compatibility
        // gate". That WAS true and is no longer: `PUT /components/schema/{id}` now runs the structural
        // + safety gate (JAVA-6), the mapping-drift and BACKWARD gates, and `If-Match` → 409 — full
        // parity with `/config/write`, on the right file, with a concurrency handle the list already
        // serves. Hence the precondition here costs no extra read.
        //
        // CREATE is deliberately unchanged: this dialog is also opened with no `def` from the parse
        // editor to author a pipeline's satellite schema, where a write-root config IS the intent.
        // Whether the pane's own "create schema" should make a registry component instead is a
        // separate question — filed, not silently widened into this fix.
        // Both arms yield the saved doc plus any WARNING-level findings, so the two routes' different
        // response shapes are normalised once instead of forking the success handler.
        const save: Observable<{ def: ComponentDef; findings: Finding[] }> = this.isEdit
            ? this.components
                  .update('schema', this.data.def!.name, config, {
                      ...(this.data.def!.contentHash ? { ifMatch: this.data.def!.contentHash } : {}),
                      ...(overrideCompatibility ? { compatibility: 'none' as const } : {}),
                  })
                  .pipe(map((def) => ({ def, findings: [] })))
            : this.config
                  .write('schema', config, {
                      overwrite: true,
                      ...(overrideCompatibility ? { compatibility: 'none' as const } : {}),
                  })
                  .pipe(
                      map((res) => ({
                          def: {
                              type: 'schema',
                              name: res.name,
                              ref: `schema/${res.name}`,
                              content: config,
                          } as ComponentDef,
                          findings: res.findings ?? [],
                      })),
                  );
        save.subscribe({
            next: ({ def, findings }) => {
                this.dirty = false;
                if (findings.length)
                    this.toast.warning(
                        `Saved schema '${def.name}' with ${findings.length} warning(s): ${findings[0].message}`,
                    );
                else this.toast.success(`Saved schema '${def.name}'`);
                // Close with the SERVER's doc (it carries the post-save contentHash), falling back to
                // the content just sent — so a caller reopening the dialog holds a fresh handle.
                this.ref.close({ saved: { ...def, content: def.content ?? config } });
            },
            error: (err) => {
                this.saving.set(false);
                const status = (err as { status?: number })?.status;
                if (status === 503) {
                    this.ref.close({ writesDisabled: true });
                    return;
                }
                if (status === 409) {
                    // The precondition refused it: someone else saved this schema since it was opened.
                    // Not recoverable in place — the grid holds rows built from the stale content.
                    this.toast.error(
                        'This schema changed since you opened it. Close and reopen the editor to ' +
                            'see the current fields, then re-apply your edit.',
                    );
                    return;
                }
                const findings = (err as { error?: { error?: { details?: { findings?: Finding[] } } } })?.error?.error
                    ?.details?.findings;
                if (status === 422 && findings?.length) {
                    this.findings.set(findings);
                    this.refused.set(true);
                    return;
                }
                this.toast.error(apiErrorMessage(err, 'Could not save the schema'));
            },
        });
    }
}
