import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthoredNode, ConfigService, apiErrorMessage } from 'app/inspecto/api';
import { schemaNameFromPath } from 'app/inspecto/segments';

/**
 * The transform types this pane AUTHORS. `TransformCompiler` recognises four
 * ({@code DIRECT, EXPR, CONCAT_DT, FILENAME_DATE}), but the latter two carry specialised source
 * semantics this grid has no affordance for — so they are not offered. A rule already carrying one is
 * preserved and shown, never silently rewritten to DIRECT: not offering a type is a UI limit, and must
 * not become a data loss.
 */
const OFFERED_TRANSFORMS = ['DIRECT', 'EXPR'] as const;

const IDENTIFIER_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

interface RuleRow {
    targetColumn: string;
    sourceExpression: string;
    transformType: string;
}

/**
 * The **Load definition pane** (definition-surface unification P4-2b) — the mapping half of the Load
 * stage, hosted in the right-dock drawer on a `transform.map` node.
 *
 * <p><b>Scope is exactly `processing.map`'s authored keys.</b> `MAP_AUTHORED` is `{columns, rules}` and
 * nothing else (pinned in `PipelineEditable.java` and mirrored in the mock); `schema` and `csv` on a map
 * node are `MAP_DERIVED`, resolved by the engine and dropped by `lower`. This pane therefore authors
 * `rules` and leaves `columns` untouched.
 *
 * <p><b>Where the fields come from.</b> ⛔ Not from a live sample: `DefinitionStateService` is
 * onboarding-only, and the editor has no sample thread (grounded 2026-08-16). The rules map FROM the
 * schema the Parse drawer authored, so the host passes that node's `schema_file` in as read-only
 * context and this pane reads the field list off it — the same "context in, one rebuilt node out" shape
 * the Parse pane uses for `[templates]`. Operator decision, same day.
 *
 * <p><b>Pure</b> (D2): Apply emits a rebuilt node and the toolbar Save persists. Unlike the Parse pane
 * this one writes nothing of its own — a mapping has no companion artifact.
 */
@Component({
    selector: 'app-pipeline-load-definition',
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
    ],
    template: `
        <form [formGroup]="form" (ngSubmit)="submit()" class="space-y-1">
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Name</mat-label>
                <input matInput formControlName="name" [placeholder]="node().id" />
            </mat-form-field>
            <mat-form-field class="w-full" subscriptSizing="dynamic">
                <mat-label>Description</mat-label>
                <input matInput formControlName="description" />
            </mat-form-field>

            <div class="mb-1 mt-2 flex items-center gap-2">
                <span class="text-xs font-semibold uppercase opacity-70">Mapping</span>
                @if (loading()) {
                    <span class="text-secondary text-xs">Loading the schema's fields…</span>
                }
            </div>

            @if (error()) {
                <p class="text-warn m-0 text-sm" role="alert">{{ error() }}</p>
            }

            @if (!ruleRows.length) {
                <p class="text-secondary m-0 text-sm">
                    @if (schemaFile()) {
                        This pipeline's schema has no fields yet — define the output schema on the parse
                        step first, then map it here.
                    } @else {
                        Define the output schema on the parse step first. A mapping projects that
                        schema's fields onto the columns that reach the table.
                    }
                </p>
            } @else {
                <table class="w-full border-collapse">
                    <thead>
                        <tr class="text-secondary text-left text-xs font-semibold uppercase tracking-wider">
                            <th scope="col" class="pb-1">Target column</th>
                            <th scope="col" class="w-32 pb-1">Rule</th>
                            <th scope="col" class="pb-1">Source</th>
                        </tr>
                    </thead>
                    <tbody>
                        @for (g of ruleRows.controls; track $index) {
                            <tr [formGroup]="g" class="border-t align-middle">
                                <td class="py-1 pr-2">
                                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                                        <input matInput formControlName="targetColumn" aria-label="Target column" />
                                        @if (g.get('targetColumn')?.invalid && g.get('targetColumn')?.touched) {
                                            <mat-error>Letters, digits, _ — start with a letter or _.</mat-error>
                                        }
                                    </mat-form-field>
                                </td>
                                <td class="w-32 py-1 pr-2">
                                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                                        <mat-select formControlName="transformType" aria-label="Rule type">
                                            @for (t of transformsFor(g); track t) {
                                                <mat-option [value]="t">{{ t }}</mat-option>
                                            }
                                        </mat-select>
                                    </mat-form-field>
                                </td>
                                <td class="py-1">
                                    @if (g.get('transformType')?.value === 'DIRECT') {
                                        <mat-form-field class="w-full" subscriptSizing="dynamic">
                                            <mat-select formControlName="sourceExpression" aria-label="Source field">
                                                @for (f of fields(); track f) {
                                                    <mat-option [value]="f">{{ f }}</mat-option>
                                                }
                                            </mat-select>
                                        </mat-form-field>
                                    } @else {
                                        <mat-form-field class="w-full" subscriptSizing="dynamic">
                                            <input
                                                matInput
                                                formControlName="sourceExpression"
                                                aria-label="Source expression"
                                                matTooltip="A per-row DuckDB scalar expression, emitted verbatim — you own any explicit cast, e.g. TRY_CAST(amt AS DOUBLE) / 100."
                                            />
                                        </mat-form-field>
                                    }
                                </td>
                            </tr>
                        }
                    </tbody>
                </table>
                <p class="text-secondary m-0 mt-2 text-xs">
                    An expression is passed to DuckDB verbatim over VARCHAR source columns — cast
                    explicitly (<code>TRY_CAST(amt AS DOUBLE) * 2</code>), or the run refuses it.
                </p>
            }
        </form>
    `,
})
export class PipelineLoadDefinitionComponent {
    private fb = inject(FormBuilder);
    private configApi = inject(ConfigService);

    /** The `transform.map` node being defined. */
    readonly node = input.required<AuthoredNode>();
    /**
     * The PARSER node's `schema_file` — read-only context the host supplies, because the fields these
     * rules map FROM live on that node, not this one.
     */
    readonly schemaFile = input('');

    readonly applied = output<AuthoredNode>();
    readonly dirtyChange = output<boolean>();

    readonly form: FormGroup = this.fb.group({
        name: [''],
        description: [''],
        rules: this.fb.array<FormGroup>([]),
    });
    get ruleRows(): FormArray<FormGroup> {
        return this.form.controls['rules'] as FormArray<FormGroup>;
    }

    /** The schema's field names — the source options a DIRECT rule picks from. */
    readonly fields = signal<string[]>([]);
    readonly loading = signal(false);
    readonly error = signal<string | null>(null);

    /** The rules already authored on this node, if any. */
    private authoredRules = computed<RuleRow[]>(() => {
        const rules = this.node().config?.['rules'];
        if (!Array.isArray(rules)) return [];
        return rules
            .filter((r): r is Record<string, unknown> => !!r && typeof r === 'object')
            .map((r) => ({
                targetColumn: String(r['targetColumn'] ?? ''),
                sourceExpression: String(r['sourceExpression'] ?? ''),
                // Blank/omitted IS DIRECT — TransformCompiler's own default, mirrored so a legacy
                // rule does not render as an empty select.
                transformType: String(r['transformType'] ?? '').trim().toUpperCase() || 'DIRECT',
            }));
    });

    private lastDirty = false;

    constructor() {
        effect(() => {
            const n = this.node();
            this.form.patchValue({ name: n.name ?? '', description: n.description ?? '' });
            this.form.markAsPristine();
            this.error.set(null);
            this.seedRules(this.authoredRules());
            this.emitDirty();
            this.loadSchemaFields();
        });
    }

    /** The type options for a row: the two offered, plus whatever it already carries (never dropped). */
    transformsFor(g: FormGroup): string[] {
        const current = String(g.get('transformType')?.value ?? '');
        const offered: string[] = [...OFFERED_TRANSFORMS];
        return offered.includes(current) || !current ? offered : [...offered, current];
    }

    /**
     * Read the parser's schema and keep its field names. When the node has no rules yet, seed one
     * identity rule per field — the same straight-through mapping `suggest/schema` proposes, so a
     * freshly-parsed pipeline maps end to end without hand-typing.
     */
    private loadSchemaFields(): void {
        const path = this.schemaFile().trim();
        this.fields.set([]);
        if (!path) return;
        const name = schemaNameFromPath(path);
        if (!name) return;
        this.loading.set(true);
        this.configApi.read('schema', name).subscribe({
            next: (r) => {
                this.loading.set(false);
                const raw = (r.config?.['raw'] ?? {}) as Record<string, unknown>;
                const fields = Array.isArray(raw['fields']) ? (raw['fields'] as Record<string, unknown>[]) : [];
                const names = fields.map((f) => String(f['name'] ?? '')).filter((n) => n !== '');
                this.fields.set(names);
                if (!this.ruleRows.length && names.length)
                    this.seedRules(
                        names.map((n) => ({ targetColumn: n, sourceExpression: n, transformType: 'DIRECT' })),
                    );
            },
            error: (e) => {
                this.loading.set(false);
                // Not fatal: the node's own rules still edit. Only the source PICKER loses its options.
                this.error.set(apiErrorMessage(e, 'Could not read the schema — source fields are unavailable.'));
            },
        });
    }

    private seedRules(rows: RuleRow[]): void {
        this.ruleRows.clear();
        for (const r of rows) {
            this.ruleRows.push(
                this.fb.group({
                    targetColumn: [r.targetColumn, [Validators.required, Validators.pattern(IDENTIFIER_RE)]],
                    sourceExpression: [r.sourceExpression, [Validators.required]],
                    transformType: [r.transformType],
                }),
            );
        }
        this.form.markAsPristine();
    }

    private emitDirty(): void {
        const dirty = this.form.dirty;
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    /** Dirty is derived on interaction, not streamed — the Collection/Parse pane contract. */
    onInteraction(): void {
        this.emitDirty();
    }

    /** Validate and emit the rebuilt node. A map node with no rules lowers to no `processing.map`. */
    submit(): void {
        this.error.set(null);
        if (this.ruleRows.invalid) {
            this.ruleRows.controls.forEach((g) => g.markAllAsTouched());
            this.error.set('Every rule needs a valid target column and a source.');
            return;
        }
        const seen = new Set<string>();
        for (const g of this.ruleRows.controls) {
            const t = String(g.get('targetColumn')?.value ?? '').trim();
            if (seen.has(t)) {
                this.error.set(`Duplicate target column "${t}" — one rule per output column.`);
                return;
            }
            seen.add(t);
        }

        const v = this.form.getRawValue();
        const rules = this.ruleRows.controls.map((g) => {
            const r = g.getRawValue() as RuleRow;
            return {
                targetColumn: r.targetColumn.trim(),
                sourceExpression: r.sourceExpression.trim(),
                transformType: r.transformType,
            };
        });
        const n = this.node();
        const node: AuthoredNode = {
            ...n,
            name: (v.name ?? '').trim() || n.name,
            description: (v.description ?? '').trim() || undefined,
            // `columns` is left exactly as it was: this pane authors `rules` and nothing else.
            config: { ...(n.config ?? {}), ...(rules.length ? { rules } : {}) },
        };
        this.form.markAsPristine();
        this.emitDirty();
        this.applied.emit(node);
    }
}
