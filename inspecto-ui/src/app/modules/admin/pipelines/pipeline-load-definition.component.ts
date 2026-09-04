import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthoredNode, ConfigService, apiErrorMessage } from 'app/inspecto/api';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';
import { FILENAME_DATE_TARGET, TRANSFORM_TYPES } from 'app/inspecto/mapping';
import { DerivedSchemaPanelComponent } from 'app/inspecto/schema';
import { schemaNameFromPath } from 'app/inspecto/segments';

/**
 * The transform types this pane AUTHORS — all four `TransformCompiler` recognises
 * (`TransformCompiler.TRANSFORM_TYPES`). `CONCAT_DT` and `FILENAME_DATE` were withheld until
 * 2026-08-17 because they carry specialised source semantics; they are now authored structurally.
 *
 * ⚠ **There is no per-rule `format` or `pattern` field to put them in.** A rule is exactly
 * `{targetColumn, sourceExpression, transformType}` — `MappingCsv` drops every other key — so both
 * types pack their parameters into `sourceExpression` as `|`-delimited positions. This pane composes
 * and decomposes that string; it never invents a fourth key.
 *
 * A rule carrying a type this pane does not offer is still preserved and shown, never silently
 * rewritten: not offering a type is a UI limit and must not become a data loss.
 */
const OFFERED_TRANSFORMS = TRANSFORM_TYPES;

/** One shared array so `transformsFor` can hand back a stable reference on the common path. */
const OFFERED_LIST: string[] = [...OFFERED_TRANSFORMS];

/**
 * How many `|` positions each structured type must always emit. `CONCAT_DT` keeps both even when one
 * is blank — the compiler reads `parts[1]` unconditionally — while `FILENAME_DATE` keeps only the
 * column, so an unset prefix/format stays *missing* and the compiler's own defaults govern.
 */
const MIN_SOURCE_POSITIONS: Record<string, number> = { CONCAT_DT: 2, FILENAME_DATE: 1 };

const FILENAME_DATE_TYPE = 'FILENAME_DATE';

const IDENTIFIER_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** How many mapped rows the drawer shows — the server already caps its own response at 200. */
const MAX_PREVIEW_ROWS = 20;

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
 * node are `MAP_DERIVED`, resolved by the engine and dropped by `lower`. This pane authors `rules`; it
 * READS `columns` (a legacy-lifted node's projection) to seed the grid, and an Apply that writes rules
 * REPLACES `columns`, because `RowShaper.columnsOf` prefers `columns` and would otherwise run the old
 * projection over the operator's saved edit.
 *
 * <p><b>Where the fields come from.</b> ⛔ Not from the sample: the rules map FROM the schema the Parse
 * drawer authored, so the host passes that node's `schema_file` in as read-only context and this pane
 * reads the field list off it — the same "context in, one rebuilt node out" shape the Parse pane uses.
 * Operator decision, 2026-08-16.
 *
 * <p><b>The sample is for TESTING only (B1).</b> `[sample]` is the tab's thread; *Test mapping* posts
 * the rules being edited over the rows the Parse drawer already parsed and renders the mapped output —
 * the thread's cast hop, which is why the result is mirrored back into it. Authoring never depends on
 * it: with no sample the grid still edits, it just cannot be tested.
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
        DerivedSchemaPanelComponent,
    ],
    template: `
        <form [formGroup]="form" (ngSubmit)="submit()" class="space-y-1">
            <!-- S2/principle 5: identity is asked ONCE, on the inspector's rename pencil — never
                 re-asked inside a definition pane. -->
            <div class="mb-1 flex items-center gap-2">
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
                        This pipeline's schema has no fields yet — define the output schema on the parse step first,
                        then map it here.
                    } @else {
                        Define the output schema on the parse step first. A mapping projects that schema's fields onto
                        the columns that reach the table.
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
                                    } @else if (g.get('transformType')?.value === 'CONCAT_DT') {
                                        <!-- Source is date|time: two raw column names, joined by the compiler. -->
                                        <div class="flex items-center gap-2">
                                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                                <mat-label>Date column</mat-label>
                                                <mat-select
                                                    [value]="sourcePart(g, 0)"
                                                    (valueChange)="setSourcePart(g, 0, $event)"
                                                    aria-label="Date column"
                                                >
                                                    @for (f of fields(); track f) {
                                                        <mat-option [value]="f">{{ f }}</mat-option>
                                                    }
                                                </mat-select>
                                            </mat-form-field>
                                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                                <mat-label>Time column</mat-label>
                                                <mat-select
                                                    [value]="sourcePart(g, 1)"
                                                    (valueChange)="setSourcePart(g, 1, $event)"
                                                    aria-label="Time column"
                                                >
                                                    @for (f of fields(); track f) {
                                                        <mat-option [value]="f">{{ f }}</mat-option>
                                                    }
                                                </mat-select>
                                            </mat-form-field>
                                        </div>
                                    } @else if (g.get('transformType')?.value === 'FILENAME_DATE') {
                                        <!-- Source is column|prefix|strptime; the 8-digit group is fixed. -->
                                        <div class="flex items-center gap-2">
                                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                                <mat-label>File name column</mat-label>
                                                <mat-select
                                                    [value]="sourcePart(g, 0)"
                                                    (valueChange)="setSourcePart(g, 0, $event)"
                                                    aria-label="File name column"
                                                >
                                                    @for (f of fields(); track f) {
                                                        <mat-option [value]="f">{{ f }}</mat-option>
                                                    }
                                                </mat-select>
                                            </mat-form-field>
                                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                                <mat-label>Prefix</mat-label>
                                                <input
                                                    matInput
                                                    [value]="sourcePart(g, 1)"
                                                    (input)="setSourcePart(g, 1, $any($event.target).value)"
                                                    aria-label="File name prefix"
                                                    matTooltip="Literal text before the 8-digit date, e.g. cbs_cdr_vou_ — spliced into the extract pattern."
                                                />
                                            </mat-form-field>
                                            <mat-form-field class="w-full" subscriptSizing="dynamic">
                                                <mat-label>Format</mat-label>
                                                <input
                                                    matInput
                                                    [value]="sourcePart(g, 2)"
                                                    (input)="setSourcePart(g, 2, $any($event.target).value)"
                                                    placeholder="%Y%m%d"
                                                    aria-label="Date format"
                                                />
                                            </mat-form-field>
                                        </div>
                                    } @else {
                                        <mat-form-field class="w-full" subscriptSizing="dynamic">
                                            <input
                                                matInput
                                                formControlName="sourceExpression"
                                                aria-label="Source expression"
                                                matTooltip="A per-row scalar expression, emitted verbatim — you own any explicit cast, e.g. TRY_CAST(amt AS DOUBLE) / 100."
                                            />
                                        </mat-form-field>
                                    }
                                    @if (ruleProblem(g); as problem) {
                                        <p class="text-warn m-0 text-xs" role="alert">{{ problem }}</p>
                                    }
                                </td>
                            </tr>
                        }
                    </tbody>
                </table>
                <p class="text-secondary m-0 mt-2 text-xs">
                    An expression is passed verbatim over VARCHAR source columns — cast explicitly (<code
                        >TRY_CAST(amt AS DOUBLE) * 2</code
                    >), or the run refuses it.
                </p>

                <!--
                    Mapped output (B1): the thread's cast hop. The rows are the PARSE drawer's parsed
                    sample run through these rules server-side — never a client-side evaluation, since an
                    EXPR is DuckDB's to compile.
                -->
                <div class="mb-1 mt-4 flex items-center gap-2">
                    <span class="text-xs font-semibold uppercase opacity-70">Mapped output</span>
                    <button mat-stroked-button type="button" (click)="testMapping()" [disabled]="!canTest()">
                        Test mapping
                    </button>
                    @if (testing()) {
                        <span class="text-secondary text-xs">Mapping the sample…</span>
                    }
                </div>

                @if (!parsedRows().length) {
                    <p class="text-secondary m-0 text-sm">
                        Capture a sample on the parse step and run Test parse — the mapped rows are those parsed rows
                        put through these rules.
                    </p>
                } @else if (mapError()) {
                    <p class="text-warn m-0 text-sm" role="alert">{{ mapError() }}</p>
                } @else if (mapped(); as m) {
                    <p class="text-secondary m-0 mb-1 text-xs">
                        {{ m.mappedCount }} rows mapped from {{ parsedRows().length }} parsed{{
                            m.rejectedCount > 0 ? ' · ' + m.rejectedCount + ' rejected by the cast' : ''
                        }}
                    </p>
                    @if (m.mappedRows?.length) {
                        <div class="overflow-x-auto">
                            <table class="w-full border-collapse text-xs">
                                <thead>
                                    <tr class="text-secondary text-left font-semibold uppercase tracking-wider">
                                        @for (c of m.mappedColumns ?? []; track c) {
                                            <th scope="col" class="whitespace-nowrap pb-1 pr-3">{{ c }}</th>
                                        }
                                    </tr>
                                </thead>
                                <tbody>
                                    @for (row of shownRows(); track $index) {
                                        <tr class="border-t">
                                            @for (c of m.mappedColumns ?? []; track c) {
                                                <td class="whitespace-nowrap py-1 pr-3 font-mono">{{ row[c] }}</td>
                                            }
                                        </tr>
                                    }
                                </tbody>
                            </table>
                        </div>
                        @if ((m.mappedRows?.length ?? 0) > shownRows().length) {
                            <p class="text-secondary m-0 mt-1 text-xs">
                                Showing the first {{ shownRows().length }} of {{ m.mappedRows?.length }}.
                            </p>
                        }
                    } @else {
                        <p class="text-secondary m-0 text-sm">No row survived the cast — nothing to map.</p>
                    }
                }
            }
        </form>

        <!--
            S5: the engine already knows what this pipeline writes, so show it beside the mapping the
            author is editing rather than making them restate it. Read-only, outside the <form> (the
            form owns the authored side; this panel has no write path at all).
            ⚠ Only for a SAVED pipeline — a name is what the route resolves, and a draft has none.
        -->
        @if (pipeline()) {
            <div class="mt-4 border-t pt-3">
                <inspecto-derived-schema-panel [pipeline]="pipeline()" />
            </div>
        }
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

    /**
     * The directory the open pipeline's own config file lives in, relative to the write root (`''` at the
     * root), supplied by the host. This pane only READS the schema, but it must read the SAME file the
     * Parse pane writes — see `PipelineEditorComponent.configSubdir` and BACKLOG SATELLITE-WRITE-1. A
     * blank stays `undefined` so the server's fallback scan remains available for a root-level pipeline.
     */
    readonly configSubdir = input('');
    /**
     * The saved pipeline's name, for the read-only derived-schema panel (S5). Blank for a pipeline
     * that has never been saved — the route resolves a name, and there is nothing to derive without one.
     */
    readonly pipeline = input('');
    /**
     * This tab's sample thread, or null when the host keeps none — in which case the mapping still
     * edits and only *Test mapping* is unavailable. Same input shape as the Parse pane's, and for the
     * same reason: the thread is per TAB, so it is handed down rather than injected.
     */
    readonly sample = input<DefinitionStateService | null>(null);

    readonly applied = output<AuthoredNode>();
    readonly dirtyChange = output<boolean>();

    readonly form: FormGroup = this.fb.group({
        rules: this.fb.array<FormGroup>([]),
    });
    get ruleRows(): FormArray<FormGroup> {
        return this.form.controls['rules'] as FormArray<FormGroup>;
    }

    /** The schema's field names — the source options a DIRECT rule picks from. */
    readonly fields = signal<string[]>([]);
    readonly loading = signal(false);
    readonly error = signal<string | null>(null);

    /** The schema's `raw` block as read — posted verbatim as the draft the mapping is tested against. */
    private readonly schemaRaw = signal<Record<string, unknown> | null>(null);
    readonly testing = signal(false);

    /** The parsed rows the thread carries — the input a mapping test runs over. */
    readonly parsedRows = computed<Record<string, unknown>[]>(() => this.sample()?.parsedRows() ?? []);
    /** The thread's cast hop, but only once it carries the mapped half this pane asked for. */
    readonly mapped = computed(() => {
        const p = this.sample()?.schemaPreview();
        return p?.mappedColumns ? p : null;
    });
    readonly mapError = computed(() => this.sample()?.schemaError() ?? null);
    /** Rows are a preview, not a grid — enough to see the projection, never the whole sample. */
    readonly shownRows = computed(() => (this.mapped()?.mappedRows ?? []).slice(0, MAX_PREVIEW_ROWS));
    /** ⚠ Deliberately signal-only: the button lives inside the "has rules" arm, so rule count is given. */
    readonly canTest = computed(() => !this.testing() && this.parsedRows().length > 0);

    /** The rules already authored on this node, if any. */
    private authoredRules = computed<RuleRow[]>(() => {
        const rules = this.node().config?.['rules'];
        if (Array.isArray(rules)) {
            return rules
                .filter((r): r is Record<string, unknown> => !!r && typeof r === 'object')
                .map((r) => ({
                    targetColumn: String(r['targetColumn'] ?? ''),
                    sourceExpression: String(r['sourceExpression'] ?? ''),
                    // Blank/omitted IS DIRECT — TransformCompiler's own default, mirrored so a legacy
                    // rule does not render as an empty select.
                    transformType:
                        String(r['transformType'] ?? '')
                            .trim()
                            .toUpperCase() || 'DIRECT',
                }));
        }
        // A node lifted from a legacy config carries its projection as `columns: [{name, expr}]` and no
        // rules; before 2026-09-04 this pane showed it as an EMPTY grid, so the operator saw no mapping
        // where the engine ran one. Read them as DIRECT rules — `submit()` writes them back as rules.
        const columns = this.node().config?.['columns'];
        if (Array.isArray(columns)) {
            return columns
                .filter((c): c is Record<string, unknown> => !!c && typeof c === 'object')
                .map((c) => ({
                    targetColumn: String(c['name'] ?? c['targetColumn'] ?? ''),
                    sourceExpression: String(c['expr'] ?? c['sourceExpression'] ?? c['name'] ?? ''),
                    transformType: 'DIRECT',
                }))
                .filter((r) => r.targetColumn.trim().length > 0);
        }
        return [];
    });

    private lastDirty = false;

    constructor() {
        effect(() => {
            this.node();
            this.form.markAsPristine();
            this.error.set(null);
            this.seedRules(this.authoredRules());
            this.lastDirty = false;
            this.dirtyChange.emit(false);
            this.loadSchemaFields();
        });
        // Any rule edit invalidates a mapped preview taken from the rules as they were.
        this.ruleRows.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.invalidateMapping());
        // …and arms the drawer's Apply. ⚠ Without this the pane emitted `dirtyChange` only from the node
        // effect and from `submit()` itself, so EVERY mapping edit left Apply greyed out: a builder could
        // author rules, press Apply, and watch the Map Step stay "Needs config" — the same dead end
        // BUILDER-1a closed on the Parse drawer. `dirty` is a form flag, so seeding AUTHORED rules (which
        // ends `markAsPristine`) reports false and cannot arm it spuriously; a DERIVED proposal arms it
        // deliberately — see `seedRules`.
        this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.emitDirty());
    }

    /**
     * The type options for a row: the offered set, plus whatever it already carries (never dropped).
     * Returns the SAME array reference on the common path — this is a template call, so allocating a
     * fresh copy per row per change-detection cycle would also churn `@for`'s identity.
     */
    transformsFor(g: FormGroup): string[] {
        const current = String(g.get('transformType')?.value ?? '');
        if (!current || OFFERED_LIST.includes(current)) return OFFERED_LIST;
        return [...OFFERED_LIST, current];
    }

    private sourceParts(g: FormGroup): string[] {
        return String(g.get('sourceExpression')?.value ?? '').split('|');
    }

    /** One `|`-delimited position of a structured `sourceExpression`, for a template binding. */
    sourcePart(g: FormGroup, index: number): string {
        return this.sourceParts(g)[index] ?? '';
    }

    /**
     * Write one `|` position of a structured source, keeping the type's minimum arity.
     *
     * `CONCAT_DT` keeps **both** positions even when one is blank: the compiler reads `parts[1]`
     * unconditionally, so a bare column is an `ArrayIndexOutOfBounds` at run time — which is why
     * `MappingRules` refuses it up front. ⚠ `FILENAME_DATE`, by contrast, drops trailing blanks rather
     * than emitting them: the compiler defaults a *missing* position (`""` prefix, `%Y%m%d` format),
     * but an empty one interpolates into the SQL — `col||` would silently give `TRY_STRPTIME(…, '')`.
     */
    setSourcePart(g: FormGroup, index: number, value: string): void {
        const type = String(g.get('transformType')?.value ?? '');
        const min = MIN_SOURCE_POSITIONS[type] ?? 1;
        const parts = this.sourceParts(g);
        while (parts.length <= index) parts.push('');
        parts[index] = value;
        while (parts.length > min && !parts[parts.length - 1].trim()) parts.pop();
        while (parts.length < min) parts.push('');
        g.patchValue({ sourceExpression: parts.join('|') });
        // ⚠ `patchValue` does NOT mark the form dirty, and the five CONCAT_DT / FILENAME_DATE controls
        // bind [value]/(valueChange) rather than formControlName — so nothing else armed the flag either.
        // Unlike the Parse and Collection panes this component has no `onInteraction` host listener, so
        // editing only a Date/Time/File-name column, Prefix or Format left Apply greyed out until the
        // operator happened to poke an unrelated field.
        g.markAsDirty();
        this.emitDirty();
    }

    /** The refusal the engine would raise for this row, or `null`. Mirrors `MappingRules`. */
    ruleProblem(g: FormGroup): string | null {
        // Read the type FIRST: this runs per row per change detection, and the overwhelming majority
        // of rows are DIRECT — they must not pay for a split whose result is never read.
        const type = String(g.get('transformType')?.value ?? '');
        if (type !== 'CONCAT_DT' && type !== FILENAME_DATE_TYPE) return null;

        const parts = this.sourceParts(g);
        if (type === 'CONCAT_DT') {
            return !parts[0]?.trim() || !parts[1]?.trim() ? 'Needs a date column and a time column.' : null;
        }
        const target = String(g.get('targetColumn')?.value ?? '')
            .trim()
            .toUpperCase();
        if (target !== FILENAME_DATE_TARGET) return `Can only write ${FILENAME_DATE_TARGET}.`;
        return !parts[0]?.trim() ? 'Needs the column holding the file name.' : null;
    }

    /**
     * Read the parser's schema and keep its field names. When the node has no rules yet, seed one
     * identity rule per field — the same straight-through mapping `suggest/schema` proposes, so a
     * freshly-parsed pipeline maps end to end without hand-typing.
     */
    private loadSchemaFields(): void {
        const path = this.schemaFile().trim();
        this.fields.set([]);
        this.schemaRaw.set(null);
        if (!path) return;
        const name = schemaNameFromPath(path);
        if (!name) return;
        this.loading.set(true);
        this.configApi.read('schema', name, this.configSubdir().trim() || undefined).subscribe({
            next: (r) => {
                this.loading.set(false);
                const raw = (r.config?.['raw'] ?? {}) as Record<string, unknown>;
                this.schemaRaw.set(raw);
                const fields = Array.isArray(raw['fields']) ? (raw['fields'] as Record<string, unknown>[]) : [];
                const names = fields.map((f) => String(f['name'] ?? '')).filter((n) => n !== '');
                this.fields.set(names);
                if (!this.ruleRows.length && names.length)
                    this.seedRules(
                        names.map((n) => ({ targetColumn: n, sourceExpression: n, transformType: 'DIRECT' })),
                        true,
                    );
            },
            error: (e) => {
                this.loading.set(false);
                // Not fatal: the node's own rules still edit. Only the source PICKER loses its options.
                this.error.set(apiErrorMessage(e, 'Could not read the schema — source fields are unavailable.'));
            },
        });
    }

    /**
     * Fill the rule rows.
     *
     * <p>⚠ `proposal` is the difference between AUTHORED and DERIVED rows, and it decides whether Apply
     * is reachable. Rules read off the node are authored config: seeding them leaves the form PRISTINE, so
     * re-opening a configured Step does not read as unsaved. The straight-through rows `loadSchemaFields`
     * derives from the parser's schema are a *proposal* — nothing is authored yet — and seeding those
     * pristine greyed Apply out over a complete, correct mapping: the Step stayed "Needs config" and the
     * only way forward was to fake an edit on a field that already held the right value. That defeated the
     * derivation's own stated purpose ("a freshly-parsed pipeline maps end to end without hand-typing") and
     * was the first wall a new author hit. A proposal therefore arms Apply, so accepting it is one click.
     */
    private seedRules(rows: RuleRow[], proposal = false): void {
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
        if (proposal) this.form.markAsDirty();
        else this.form.markAsPristine();
        // The `form.valueChanges` subscription already fired for every `push` above — while the form was
        // still pristine — so the flag flipped after it and nothing has told the drawer yet.
        this.emitDirty();
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

    /**
     * Run the rules being edited over the thread's parsed rows and show what reaches the table (B1).
     *
     * <p>⚠ It posts the FORM's rules, not the node's — the point is to test an edit before applying it.
     * The draft is the parser's schema `raw` plus those rules, which is exactly the shape
     * `POST /config/preview/schema` reads; without the `raw` half the server has no types to cast
     * against and answers 422, so an unreadable schema disables nothing here — it simply reports.
     *
     * <p>⚠ The result is written back into the thread (its cast hop), so the Parse drawer's sample strip
     * shows the same "cast · N ok" the mapping just produced, and a later re-parse invalidates it there.
     * The FAILURE path clears the preview before setting the error — a stale mapped grid must never
     * stand over rules the server just refused.
     */
    testMapping(): void {
        const thread = this.sample();
        const rows = this.parsedRows();
        if (!thread || !rows.length) return;
        const rules = this.ruleRows.controls.map((g) => {
            const r = g.getRawValue() as RuleRow;
            return {
                targetColumn: r.targetColumn.trim(),
                sourceExpression: r.sourceExpression.trim(),
                transformType: r.transformType,
            };
        });
        this.testing.set(true);
        thread.schemaError.set(null);
        this.configApi.previewSchema({ raw: this.schemaRaw() ?? {}, mapping: { rules } }, rows).subscribe({
            next: (p) => {
                this.testing.set(false);
                thread.schemaPreview.set(p);
            },
            error: (e) => {
                this.testing.set(false);
                thread.schemaPreview.set(null);
                thread.schemaError.set(apiErrorMessage(e, 'The sample does not map with these rules.'));
            },
        });
    }

    /**
     * An edited rule invalidates the mapped grid — the same rule as the parse hop: a result may never
     * outlive the config it was produced from. Cheaper and more honest than a "stale" badge.
     *
     * <p>⚠ `seedRules` emits `valueChanges` from inside the node effect, so this runs there too — probed
     * 2026-08-16: the reads below do NOT become dependencies of that effect (Angular's forms emit outside
     * the reactive context), so no `untracked` is needed and the effect does not re-run on a test result.
     */
    private invalidateMapping(): void {
        const thread = this.sample();
        if (!thread) return;
        if (thread.schemaPreview()) thread.schemaPreview.set(null);
        if (thread.schemaError()) thread.schemaError.set(null);
    }

    /** Validate and emit the rebuilt node. A map node with no rules lowers to no `processing.map`. */
    submit(): void {
        this.error.set(null);
        if (this.ruleRows.invalid) {
            this.ruleRows.controls.forEach((g) => g.markAllAsTouched());
            this.error.set('Every rule needs a valid target column and a source.');
            return;
        }
        // Mirror the engine's own per-type refusals rather than posting a guaranteed 422.
        for (const g of this.ruleRows.controls) {
            const problem = this.ruleProblem(g as FormGroup);
            if (problem) {
                const t = String(g.get('targetColumn')?.value ?? '').trim() || '(unnamed)';
                this.error.set(`${String(g.get('transformType')?.value)} rule "${t}": ${problem}`);
                return;
            }
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
        // ⚠ `columns` and `rules` are the SAME projection, and `columns` WINS at execution
        // (`RowShaper.columnsOf` returns it before it ever looks at rules). This pane seeds its grid from
        // `columns` when a node carries no rules, so writing rules while leaving `columns` in place would
        // save an edit the engine never runs — the "written but never read" trap. Applying real rules
        // therefore takes ownership: the equivalent rules replace `columns`. An EMPTY grid deletes
        // nothing — a stray Apply must not wipe an authored projection.
        const config = { ...(n.config ?? {}) };
        if (rules.length) {
            config['rules'] = rules;
            delete config['columns'];
        }
        const node: AuthoredNode = { ...n, config };
        this.form.markAsPristine();
        this.emitDirty();
        this.applied.emit(node);
    }
}
