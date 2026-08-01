import { ChangeDetectionStrategy, Component, Input, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { SqlCodemirrorComponent } from 'app/inspecto/data-table';

const IDENTIFIER_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

interface ReferenceRow {
    name: string;
    mode: 'ref' | 'path';
    ref: string;
    path: string;
    format: string;
    asOf: string;
}

/** The parts of an `EnrichmentConfig` this editor authors (the host derives/asks the rest). */
export interface EnrichmentEditorValue {
    /** The `references:` map (alias → binding); omitted from the config when empty. */
    references: Record<string, Record<string, string>>;
    /** The transform SQL (always non-blank when {@link EnrichmentEditorComponent.build} succeeds). */
    transform: string;
}

/**
 * ONE enrichment authoring editor (W4b/U-B) — the reference bindings + transform SQL of an
 * `EnrichmentConfig`, shared by the Onboarding Enrichment stage and the Pipelines editor's
 * `enrichment` node dialog. Both surfaces author the same companion `*_enrich.toon` on the same
 * engine (`EnrichmentService`), so this is one component adopted twice, never forked — the
 * collector-table lesson (U-D) applied to a bespoke pane.
 *
 * <p>Hosts own everything around it: loading `referenceOptions`, deriving or asking the
 * `input`/`output`/`triggers` wiring, previewing, and the save itself — which MUST be
 * `POST /config/write type=enrichment` **then** `POST /enrichment` (register: enrichments do not
 * hot-reload by mtime). ⚠ Never save through job creation: a `*_job.toon` `enrich` job is a FULL
 * recompute, while the registered path recomputes only the partitions each committed batch wrote
 * (`EnrichmentService.onBatchEvent`). Relocating authoring must not silently convert that
 * incremental cost into a full rescan (plan §6).
 *
 * <p>Per-entry keys mirror `EnrichmentConfig.fromMap` exactly: `ref` | `path`+`format`, plus
 * `as_of` (SCD2 producers). `as_of` used to be DROPPED by the onboarding pane's hydrate→save
 * round-trip — carried since the extraction.
 */
@Component({
    selector: 'inspecto-enrichment-editor',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        SqlCodemirrorComponent,
    ],
    template: `
        <div class="flex flex-col gap-4">
            <!-- References -->
            <div class="flex flex-col gap-2">
                <div class="flex items-center gap-2">
                    <h2 class="m-0 text-lg font-semibold">References</h2>
                    <span class="flex-1"></span>
                    <button mat-stroked-button type="button" (click)="addReference()">
                        <mat-icon svgIcon="heroicons_outline:plus" class="icon-size-4"></mat-icon>
                        <span class="ml-1">Add reference</span>
                    </button>
                </div>

                @if (referenceRows.length === 0) {
                    <p class="text-secondary m-0 text-sm">
                        None yet — pure aggregations over <code>input</code> don't need one.
                    </p>
                }

                <form [formGroup]="referencesForm">
                    <div formArrayName="references" class="flex flex-col gap-2">
                        @for (g of referenceRows.controls; track $index; let i = $index) {
                            <div [formGroupName]="i" class="flex flex-wrap items-center gap-2 rounded border p-2">
                                <mat-form-field class="w-40" subscriptSizing="dynamic">
                                    <mat-label>Alias in SQL</mat-label>
                                    <input matInput formControlName="name" placeholder="region_dim" />
                                    @if (g.get('name')?.hasError('pattern') && g.get('name')?.touched) {
                                        <mat-error>Letters, digits, _ — start with a letter or _.</mat-error>
                                    }
                                </mat-form-field>
                                <mat-form-field class="w-36" subscriptSizing="dynamic">
                                    <mat-label>Bind</mat-label>
                                    <mat-select formControlName="mode">
                                        <mat-option value="ref">By name</mat-option>
                                        <mat-option value="path">File path</mat-option>
                                    </mat-select>
                                </mat-form-field>
                                @if (g.get('mode')?.value === 'ref') {
                                    <mat-form-field class="min-w-52 flex-1" subscriptSizing="dynamic">
                                        <mat-label>Published Reference</mat-label>
                                        <mat-select formControlName="ref">
                                            @for (o of referenceOptions; track o.id) {
                                                <mat-option [value]="o.id">{{ o.label }}</mat-option>
                                            }
                                        </mat-select>
                                        @if (referenceOptions.length === 0) {
                                            <mat-hint>No live Reference Datasets published yet.</mat-hint>
                                        }
                                    </mat-form-field>
                                    <mat-form-field class="w-36" subscriptSizing="dynamic">
                                        <mat-label>As-of column</mat-label>
                                        <input matInput formControlName="asOf"
                                               matTooltip="SCD2 producers only — the event-time column to join as-of" />
                                    </mat-form-field>
                                } @else {
                                    <mat-form-field class="min-w-52 flex-1" subscriptSizing="dynamic">
                                        <mat-label>File path</mat-label>
                                        <input matInput formControlName="path" placeholder="spaces/demo/data/ref/region_dim.csv" />
                                    </mat-form-field>
                                    <mat-form-field class="w-32" subscriptSizing="dynamic">
                                        <mat-label>Format</mat-label>
                                        <mat-select formControlName="format">
                                            <mat-option value="CSV">CSV</mat-option>
                                            <mat-option value="PARQUET">Parquet</mat-option>
                                        </mat-select>
                                    </mat-form-field>
                                }
                                <button
                                    mat-icon-button
                                    type="button"
                                    (click)="removeReference(i)"
                                    [attr.aria-label]="'Remove reference ' + (g.get('name')?.value || i + 1)"
                                    matTooltip="Remove"
                                >
                                    <mat-icon svgIcon="heroicons_outline:trash" class="icon-size-4"></mat-icon>
                                </button>
                            </div>
                        }
                    </div>
                </form>
            </div>

            <!-- Transform SQL -->
            <div class="flex flex-col gap-2">
                <h2 class="m-0 text-lg font-semibold">Transform</h2>
                <p class="text-secondary m-0 text-sm">
                    Views available: @for (v of availableViews(); track v; let last = $last) {<code>{{ v }}</code>@if (!last) {, }}.
                </p>
                <div class="rounded border" role="group" aria-label="Transform SQL editor">
                    <inspecto-sql-codemirror [value]="sql()" (valueChange)="onSqlChange($event)" />
                </div>
            </div>
        </div>
    `,
})
export class EnrichmentEditorComponent {
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);

    /** Produced Reference Datasets bindable by name (host-loaded; id = the producer's pipeline id). */
    @Input() referenceOptions: { id: string; label: string }[] = [];

    readonly referencesForm: FormGroup = this.fb.group({ references: this.fb.array<FormGroup>([]) });
    get referenceRows(): FormArray<FormGroup> {
        return this.referencesForm.controls['references'] as FormArray<FormGroup>;
    }
    readonly sql = signal('SELECT * FROM input');
    private savedSql = this.sql();

    /** The DuckDB views the transform can select from: `input` + each reference alias. */
    readonly availableViews = computed(() => {
        void this.viewsTick();
        return ['input', ...this.referenceRows.controls
            .map((g) => String(g.getRawValue()['name'] ?? '').trim())
            .filter((n) => IDENTIFIER_RE.test(n))];
    });
    /** Bumped on reference-row edits so `availableViews` recomputes (forms aren't signals). */
    private readonly viewsTick = signal(0);

    isDirty(): boolean {
        return this.referencesForm.dirty || this.sql() !== this.savedSql;
    }

    /** After a successful host save: the current value becomes the pristine baseline. */
    markSaved(): void {
        this.savedSql = this.sql();
        this.referencesForm.markAsPristine();
    }

    addReference(): void {
        this.addRow({ name: '', mode: 'ref', ref: '', path: '', format: 'CSV', asOf: '' });
        this.referencesForm.markAsDirty();
    }

    removeReference(i: number): void {
        this.referenceRows.removeAt(i);
        this.referencesForm.markAsDirty();
        this.viewsTick.update((n) => n + 1);
    }

    onSqlChange(value: string): void {
        this.sql.set(value);
    }

    /** Seed from a server-held `EnrichmentConfig` map (references + transform), pristine after. */
    hydrate(cfg: Record<string, unknown>): void {
        const refs = (cfg['references'] ?? {}) as Record<string, Record<string, unknown>>;
        this.referenceRows.clear();
        for (const [name, r] of Object.entries(refs)) {
            const byName = typeof r['ref'] === 'string' && String(r['ref']).trim() !== '';
            this.addRow({
                name,
                mode: byName ? 'ref' : 'path',
                ref: byName ? String(r['ref']) : '',
                path: byName ? '' : String(r['path'] ?? ''),
                format: String(r['format'] ?? 'CSV'),
                asOf: String(r['as_of'] ?? ''),
            });
        }
        this.sql.set(String(cfg['transform'] ?? this.sql()));
        this.savedSql = this.sql();
        this.referencesForm.markAsPristine();
        this.viewsTick.update((n) => n + 1);
    }

    /**
     * Validate and assemble this editor's parts of the config, or null on a blocking problem
     * (named via toast, exactly the checks `EnrichmentConfig.fromMap` would hard-fail on later).
     */
    build(): EnrichmentEditorValue | null {
        const references = this.buildReferences();
        if (references === null) return null;
        if (!this.sql().trim()) {
            this.toastr.warning('The transform SQL is required — it defines the enriched output.');
            return null;
        }
        return { references, transform: this.sql().trim() };
    }

    /** Rows validated into the config's `references:` map, or null on a blocking problem. */
    private buildReferences(): Record<string, Record<string, string>> | null {
        if (this.referenceRows.invalid) {
            this.referenceRows.controls.forEach((g) => g.markAllAsTouched());
            this.toastr.warning('Every reference alias must be a valid identifier (letters, digits, _).');
            return null;
        }
        const rows = this.referenceRows.controls.map((g) => g.getRawValue() as ReferenceRow);
        const names = rows.map((r) => r.name.trim());
        const dup = names.find((n, i) => names.indexOf(n) !== i);
        if (dup) {
            this.toastr.warning(`Duplicate reference alias "${dup}" — aliases must be unique.`);
            return null;
        }
        const out: Record<string, Record<string, string>> = {};
        for (const r of rows) {
            if (r.mode === 'ref') {
                if (!r.ref.trim()) {
                    this.toastr.warning(`Reference "${r.name}" needs a published Reference to bind.`);
                    return null;
                }
                out[r.name.trim()] = { ref: r.ref.trim() };
                if (r.asOf.trim()) out[r.name.trim()]['as_of'] = r.asOf.trim();
            } else {
                if (!r.path.trim()) {
                    this.toastr.warning(`Reference "${r.name}" needs a file path.`);
                    return null;
                }
                out[r.name.trim()] = { path: r.path.trim(), format: r.format };
            }
        }
        return out;
    }

    private addRow(row: ReferenceRow): void {
        const g = this.fb.group({
            name: [row.name, [Validators.required, Validators.pattern(IDENTIFIER_RE)]],
            mode: [row.mode],
            ref: [row.ref],
            path: [row.path],
            format: [row.format],
            asOf: [row.asOf],
        });
        g.valueChanges.subscribe(() => this.viewsTick.update((n) => n + 1));
        this.referenceRows.push(g);
    }
}
