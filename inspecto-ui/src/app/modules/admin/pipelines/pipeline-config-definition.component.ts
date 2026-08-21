import {
    ChangeDetectionStrategy,
    Component,
    HostListener,
    ViewChild,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
    viewChild,
} from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule, ValidatorFn } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { map } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    AuthoredNode,
    CatalogService,
    ComponentsService,
    ConfigService,
    LensService,
    SpacesService,
} from 'app/inspecto/api';
import { AttributeSpec, KEY_SEP, flattenBlock, nestKeys } from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { pipelineOptionLoader, referenceOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { EnrichmentEditorComponent } from 'app/inspecto/enrichment/enrichment-editor.component';
import { ENRICHMENT_WIRING_ATTRIBUTES } from 'app/inspecto/enrichment/enrichment-attributes';
import { enrichmentWiringDefaults } from 'app/inspecto/enrichment/enrichment-wiring';
import { buildConfiguredNode, splitNodeConfig } from './node-config-build';
import { groupByValidator, measuresValidator } from './measure-grammar';
import { nodeAttributesFor } from './node-attributes';

/** What the HOST pipeline can tell an `enrichment` node about itself, so its wiring form seeds
 *  derived values instead of asking the author to retype them (definition-surface P6-c). */
export interface EnrichmentHostPipeline {
    /** The engine's normalized pipeline id — what `BatchEvent.pipeline()` carries. */
    pipelineId: string;
    /** Its output store, when exactly ONE destination could be resolved (see the editor's derivation). */
    inputDatabase?: string;
    inputFormat?: string;
}

/**
 * How many captured rows an inline test posts. The sample thread stores the parse preview UNCAPPED, while
 * both the route and its offline arm only ever answer with `rows.slice(0, 20)`.
 */
const MAX_TEST_ROWS = 50;

/**
 * The **generic config definition pane** (canvas-UX compaction S2) — every canvas node kind that used to
 * open `NodeConfigDialog` in a 680px popup now defines itself here, inside
 * `<inspecto-definition-drawer>`, so the canvas stays visible while it is configured (principle 1: the
 * canvas is the hero). Schema-driven config for the type's published vocabulary, the collapsed
 * free-form escape hatch for keys outside it, the inline "Test this Step", and — for `enrichment` — the
 * shared enrichment editor with its own companion write path.
 *
 * <p>Two deliberate omissions relative to the dialog it replaces:
 * <ul>
 *   <li>**No Name/Description** — identity is asked once, on the inspector's rename pencil (principle 5).
 *       Both are carried through {@link submit} verbatim, because `buildConfiguredNode` rebuilds the node
 *       from scratch and would otherwise DROP a name nothing passed it.</li>
 *   <li>**No component-binding half** (picker · "New &lt;kind&gt;" · "Test &lt;component&gt;…") — dead in
 *       production since `bindKindFor` only answers for PARSE and PARSE never reached this surface
 *       (D5, BACKLOG §6). It is deleted with the dialog rather than carried here.</li>
 * </ul>
 *
 * <p>`acquisition`, the per-format parse nodes and `transform.map` are NOT served here: each has its own
 * pane, and this one is what every remaining kind shares.
 *
 * <p>**Pure**: nothing about the node is persisted (D2) — {@link submit} emits and the host patches its
 * in-memory model. The one exception is the enrichment companion config, whose write+register is the
 * whole point of that surface (the Parse pane set that precedent).
 */
@Component({
    selector: 'app-pipeline-config-definition',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
        EnrichmentEditorComponent,
    ],
    template: `
        <!-- Enrichment (W4b): the node authors the REAL companion config through the shared editor —
             written via POST /config/write + registered via POST /enrichment (the per-batch path),
             never a full-recompute job. The node itself only carries the "use: enrichment/name"
             binding; the companion file is the single truth. -->
        @if (isEnrichment()) {
            <div class="mb-1 text-xs font-semibold uppercase opacity-70">Enrichment</div>
            @if (enrichSource() === 'loading') {
                <div class="flex items-center gap-2 py-3">
                    <mat-spinner diameter="18"></mat-spinner>
                    <span class="text-sm opacity-70">Loading the bound enrichment…</span>
                </div>
            } @else if (enrichUneditable()) {
                <inspecto-alert variant="warning" title="Hand-authored transform file">
                    This enrichment uses <code>transform_file</code> — edit its TOON directly; applying here would
                    overwrite the file reference with inline SQL.
                </inspecto-alert>
            } @else {
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Enrichment name</mat-label>
                    <input matInput [formControl]="enrichName" [placeholder]="node().id" />
                </mat-form-field>
                <inspecto-schema-form
                    #wiring
                    [specs]="wiringSpecs"
                    [initial]="wiringInitial()"
                    [optionLoaders]="wiringLoaders"
                    (submitted)="submit()"
                ></inspecto-schema-form>
                <inspecto-enrichment-editor [referenceOptions]="refOptions()" />
            }
        } @else if (specs().length) {
            <!-- Schema-driven config for known node types (required up front, rest behind disclosure). -->
            <div class="mb-1 text-xs font-semibold uppercase opacity-70">Config</div>
            <inspecto-schema-form
                #config
                [specs]="specs()"
                [initial]="split().schemaInitial"
                [optionLoaders]="configLoaders"
                [extraValidators]="configValidators"
                (submitted)="submit()"
            ></inspecto-schema-form>
        }

        <!-- Additional / free-form config: the primary editor for unknown types, else a collapsed
             escape hatch for keys outside the schema. Its own <form>: the schema form owns one, and
             nesting forms is invalid. -->
        <form [formGroup]="form" (ngSubmit)="submit()">
            <div class="mb-1 mt-2 flex items-center justify-between">
                <button
                    type="button"
                    class="flex items-center gap-1 text-xs font-semibold uppercase opacity-70"
                    [attr.aria-expanded]="freeFormOpen()"
                    (click)="freeFormOpen.set(!freeFormOpen())"
                >
                    <mat-icon
                        class="icon-size-4"
                        [svgIcon]="
                            freeFormOpen() ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'
                        "
                    ></mat-icon>
                    {{ specs().length || isEnrichment() ? 'Additional config' : 'Config' }}
                    @if (configRows.length) {
                        <span class="opacity-60">({{ configRows.length }})</span>
                    }
                </button>
                @if (freeFormOpen()) {
                    <button mat-stroked-button type="button" (click)="addConfigRow()">
                        <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                        <span class="ml-1">Add</span>
                    </button>
                }
            </div>
            @if (freeFormOpen()) {
                <div formArrayName="config" class="space-y-2">
                    @for (row of configRows.controls; track $index) {
                        <div class="flex items-center gap-1" [formGroupName]="$index">
                            <mat-form-field subscriptSizing="dynamic" class="flex-1">
                                <mat-label>Key</mat-label>
                                <input matInput formControlName="key" />
                            </mat-form-field>
                            <mat-form-field subscriptSizing="dynamic" class="flex-1">
                                <mat-label>Value</mat-label>
                                <input matInput formControlName="value" />
                            </mat-form-field>
                            <button
                                mat-icon-button
                                type="button"
                                (click)="removeConfigRow($index)"
                                aria-label="Remove config entry"
                            >
                                <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                            </button>
                        </div>
                    }
                    @if (!configRows.length) {
                        <p class="text-sm opacity-60">No extra config — add a key/value entry above.</p>
                    }
                </div>
            }
        </form>

        <!-- The INLINE test: the config on screen, over the tab's own parsed rows. Offered only when
             there are rows — a test with no data would report success over nothing. -->
        @if (canTestInline()) {
            <div class="pt-2">
                <button type="button" mat-stroked-button [disabled]="testing()" (click)="runInlineTest()">
                    <mat-icon class="icon-size-5" svgIcon="heroicons_outline:bolt"></mat-icon>
                    <span class="ml-1">Test this Step</span>
                </button>
                <div class="text-secondary mt-1 text-sm">
                    Runs these settings over the {{ testRows().length }} row(s) your parse step produced. Nothing is
                    written.
                </div>
                @if (testError(); as e) {
                    <p class="text-warn m-0 mt-2 text-sm" role="alert">{{ e }}</p>
                }
                @if (testResult(); as lines) {
                    <ul class="m-0 mt-2 list-none p-0 text-sm" role="status" aria-live="polite">
                        @for (line of lines; track $index) {
                            <li>{{ line }}</li>
                        }
                    </ul>
                }
            </div>
        }
        @if (savingEnrichment()) {
            <div class="flex items-center gap-2 pt-2">
                <mat-spinner diameter="16"></mat-spinner>
                <span class="text-sm opacity-70">Saving the enrichment…</span>
            </div>
        }
    `,
})
export class PipelineConfigDefinitionComponent {
    private fb = inject(FormBuilder);
    private components = inject(ComponentsService);
    private configApi = inject(ConfigService);
    private catalog = inject(CatalogService);
    private lens = inject(LensService);
    private spaces = inject(SpacesService);
    private toastr = inject(ToastrService);

    /** The node being configured (identity fixed; config/use editable). */
    readonly node = input.required<AuthoredNode>();
    /**
     * The type's config vocabulary as published by the server (`GET /pipelines/node-types`).
     * `undefined` ⇒ the catalog has not resolved — fall back to the local table. A served EMPTY array is
     * honoured as "the server says this type has no schema" (§3.1) and must NOT re-enable the fallback.
     */
    readonly attributes = input<AttributeSpec[] | undefined>(undefined);
    /** Pipeline-derived facts for a fresh `enrichment` node's wiring seed; `undefined` ⇒ open blank. */
    readonly enrichmentHost = input<EnrichmentHostPipeline | undefined>(undefined);
    /** The rows the tab's sample thread parsed — what the inline test runs over (read, never written). */
    readonly sampleRows = input<Record<string, unknown>[] | undefined>(undefined);

    /** The edited node, rebuilt by {@link submit} — the host applies it to the in-memory model. */
    readonly applied = output<AuthoredNode>();
    /** Whether the pane holds edits since creation / the last successful submit. */
    readonly dirtyChange = output<boolean>();

    @ViewChild('config') private schemaForm?: InspectoSchemaFormComponent;
    @ViewChild('wiring') private wiringForm?: InspectoSchemaFormComponent;
    private readonly enrichEditor = viewChild(EnrichmentEditorComponent);

    readonly isEnrichment = computed(() => this.node().type === 'enrichment');
    readonly specs = computed<AttributeSpec[]>(() => this.attributes() ?? nodeAttributesFor(this.node().type) ?? []);
    /** Schema seed + free-form rows — the same split the dialog ran (`node-config-build.ts`). */
    readonly split = computed(() => splitNodeConfig(this.node(), this.specs(), false));

    /** Loaders for the schema-driven config form's entity-reference keys (join's Reference picker). */
    readonly configLoaders = { reference: referenceOptionLoader() };
    /**
     * Per-key validators for rules the published spec cannot express. Both belong to
     * `transform.summarize`: the pipeline never parses that block and the Job that does runs on its own
     * schedule, so an unvalidated typo surfaces far away from the form that caused it.
     */
    readonly configValidators: Record<string, ValidatorFn[]> = {
        measures: [measuresValidator()],
        group_by: [groupByValidator()],
    };
    readonly freeFormOpen = signal(false);

    readonly form = this.fb.group({
        config: this.fb.array<ReturnType<PipelineConfigDefinitionComponent['configRow']>>([]),
    });

    // ── enrichment nodes (W4b): the pane authors the REAL companion `*_enrich.toon` ──
    readonly wiringSpecs = ENRICHMENT_WIRING_ATTRIBUTES;
    readonly wiringLoaders = { triggers__on_pipeline: pipelineOptionLoader() };
    /** The bound companion config: `'loading'` while reading, `null` = authoring fresh. */
    readonly enrichSource = signal<'loading' | Record<string, unknown> | null>(null);
    /** A hand-authored `transform_file` config must not be overwritten with inline SQL. */
    readonly enrichUneditable = computed(() => {
        const s = this.enrichSource();
        return typeof s === 'object' && s !== null && 'transform_file' in s;
    });
    /** Produced Reference Datasets bindable by name (same source the Onboarding stage uses). */
    readonly refOptions = signal<{ id: string; label: string }[]>([]);
    readonly enrichName = this.fb.control('', { nonNullable: true });
    readonly savingEnrichment = signal(false);

    readonly wiringInitial = computed<Record<string, unknown>>(() => {
        const s = this.enrichSource();
        if (s === 'loading') return {};
        // Authoring fresh: derive what the host pipeline already knows instead of presenting empty
        // required fields (P6-c). Read once — a seed that moved under an edited form would clobber it.
        if (!s) return this.freshWiring();
        const input = (s['input'] as Record<string, unknown>) ?? {};
        const output = (s['output'] as Record<string, unknown>) ?? {};
        const flat = flattenBlock({
            input,
            output,
            triggers: (s['triggers'] as Record<string, unknown>) ?? {},
        });
        // `flattenBlock` joins any list to a comma string, but a `type: 'list'` control holds a real
        // array — so hand the two partition chips their arrays back. `output.partitions` may carry
        // `{column, source}` map entries; the chips are string[]-only, so show the columns and
        // re-marry `source` in saveEnrichment().
        flat[`input${KEY_SEP}partitions`] = authoredList(input['partitions']);
        flat[`output${KEY_SEP}partitions`] = partitionColumns(
            Array.isArray(output['partitions']) ? output['partitions'] : [],
        );
        return flat;
    });

    // ── the inline test ──
    /** The inline-preview family this node belongs to, or null. Keyed on the node's own TYPE. */
    readonly testFamily = computed(() => ComponentsService.previewFamilyFor(this.node().type));
    /** The rows a test posts. Capped: the preview answers 20 either way, and the thread is uncapped. */
    readonly testRows = computed(() => (this.sampleRows() ?? []).slice(0, MAX_TEST_ROWS));
    readonly canTestInline = computed(() => !!this.testFamily() && this.testRows().length > 0);
    readonly testing = signal(false);
    /** The last inline preview, as lines to render — one shape for both families, only text is shown. */
    readonly testResult = signal<string[] | null>(null);
    readonly testError = signal<string | null>(null);

    private lastDirty = false;
    private enrichInitFor: string | null = null;

    constructor() {
        // Seed from the node input. The host recreates this component per node (and on Discard), so
        // this runs once per instance — but an input swap without recreation re-seeds correctly too.
        effect(() => {
            const n = this.node();
            this.configRows.clear();
            for (const row of this.split().extraRows) this.configRows.push(this.configRow(row.key, row.value));
            // Show the free-form editor up front only when it's the primary surface or already carries
            // keys. For an enrichment node the primary surface is the shared editor (W4b).
            const extras = this.split().extraRows.length;
            this.freeFormOpen.set(this.isEnrichment() ? extras > 0 : this.specs().length === 0 || extras > 0);
            this.form.markAsPristine();
            this.lastDirty = false;
            this.dirtyChange.emit(false);
            if (this.isEnrichment() && this.enrichInitFor !== n.id) {
                this.enrichInitFor = n.id;
                this.initEnrichment(n);
            }
        });
        // Hydrate the shared enrichment editor once both it and the config exist (the read is async).
        effect(() => {
            const editor = this.enrichEditor();
            const src = this.enrichSource();
            if (editor && src && src !== 'loading' && !editor.isDirty()) editor.hydrate(src);
        });
    }

    /** The derived seed for a fresh companion — empty when this host supplied no pipeline context. */
    private freshWiring(): Record<string, unknown> {
        const host = this.enrichmentHost();
        if (!host) return {};
        const w = enrichmentWiringDefaults({
            enrichName: this.enrichName.value.trim() || this.node().id,
            pipelineId: host.pipelineId,
            base: this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.',
            inputDatabase: host.inputDatabase,
            inputFormat: host.inputFormat,
        });
        const flat = flattenBlock({ input: w.input, output: w.output, triggers: w.triggers });
        flat[`input${KEY_SEP}partitions`] = w.input['partitions'];
        flat[`output${KEY_SEP}partitions`] = w.output['partitions'];
        return flat;
    }

    /** Load the bound companion (when `use` names one) + the bindable Reference Datasets. */
    private initEnrichment(node: AuthoredNode): void {
        const use = node.use ?? '';
        const bound = use.startsWith('enrichment/') ? use.slice('enrichment/'.length) : null;
        this.enrichName.setValue(bound ?? node.id);
        if (bound) {
            this.enrichSource.set('loading');
            this.configApi.read('enrichment', bound).subscribe({
                next: (r) => this.enrichSource.set(r.config),
                error: () => {
                    this.enrichSource.set(null);
                    this.toastr.warning(`Could not read "${bound}" — authoring a fresh enrichment.`);
                },
            });
        }
        this.catalog.references().subscribe({
            next: (nodes) =>
                this.refOptions.set(
                    nodes
                        .filter((n) => typeof n.attrs?.['pipeline'] === 'string')
                        .map((n) => ({ id: String(n.attrs!['pipeline']), label: n.label })),
                ),
            error: () => this.refOptions.set([]),
        });
    }

    /** Dirty is derived on interaction, not streamed — the Collection/Parse pane contract. */
    @HostListener('input')
    @HostListener('click')
    onInteraction(): void {
        this.emitDirty();
    }

    private emitDirty(): void {
        const dirty =
            this.form.dirty ||
            (this.schemaForm?.isDirty() ?? false) ||
            (this.wiringForm?.isDirty() ?? false) ||
            this.enrichName.dirty ||
            (this.enrichEditor()?.isDirty() ?? false);
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    get configRows(): FormArray {
        return this.form.get('config') as FormArray;
    }

    addConfigRow(): void {
        this.configRows.push(this.configRow('', ''));
        this.form.markAsDirty();
        this.emitDirty();
    }

    removeConfigRow(i: number): void {
        this.configRows.removeAt(i);
        this.form.markAsDirty();
        this.emitDirty();
    }

    /**
     * Test the config **being edited** through `POST /components/{family}/preview`, over the tab's own
     * parsed rows. Reads the live config surface rather than `node.config`, so it tests what is on
     * screen. ⚠ The transform arm must send the node's `type` inside `config`: the route 422s a config
     * that is not `transform.*`.
     */
    runInlineTest(): void {
        const family = this.testFamily();
        const rows = this.testRows();
        if (!family || !rows.length) return;
        if (this.schemaForm && !this.schemaForm.validate()) return;
        // Assemble through the SAME `buildConfiguredNode` the apply path uses, so the test runs over
        // the config that would actually be written — nesting, free-form rows and all.
        const config = this.buildNode().config ?? {};
        this.testing.set(true);
        this.testError.set(null);
        this.testResult.set(null);
        const lines$ =
            family === 'transform'
                ? this.components
                      .previewTransform({ ...config, type: this.node().type }, rows)
                      .pipe(
                          map((p) => [
                              `in: ${p.inputColumns.length} column(s) over ${rows.length} row(s)`,
                              ...p.relations.map((r) => `out '${r.rel}': ${r.rowCount} row(s)`),
                          ]),
                      )
                : this.components
                      .previewSink(config, rows)
                      .pipe(
                          map((p) => [
                              `store: ${p.store ?? '(none declared)'} — ${p.rowCount} row(s) would be written`,
                              ...p.warnings,
                          ]),
                      );
        lines$.subscribe({
            next: (lines) => {
                this.testing.set(false);
                this.testResult.set(lines);
            },
            error: (e) => {
                this.testing.set(false);
                this.testError.set(apiErrorMessage(e, 'The test failed.'));
            },
        });
    }

    /**
     * Rebuild the node from the surfaces on screen. ⚠ `name`/`description` are passed through from the
     * node itself: this pane deliberately does not ask for them (the inspector rename owns identity),
     * and `buildConfiguredNode` rebuilds the node from scratch — so omitting them would DELETE a name
     * every time a config was applied.
     */
    private buildNode(): AuthoredNode {
        const n = this.node();
        return buildConfiguredNode({
            node: n,
            specs: this.specs(),
            formValues: this.schemaForm ? this.schemaForm.value() : null,
            freeRows: this.form.getRawValue().config as { key: string; value: string }[],
            name: n.name,
            description: n.description,
            use: n.use,
            isAcquisition: false,
            connector: null,
        });
    }

    /** Apply: validate, rebuild the node, emit. Enrichment writes its companion first. */
    submit(): void {
        if (this.isEnrichment()) {
            this.saveEnrichment();
            return;
        }
        if (this.schemaForm && !this.schemaForm.validate()) return;
        const node = this.buildNode();
        this.markPristine();
        this.applied.emit(node);
    }

    private markPristine(): void {
        this.form.markAsPristine();
        this.schemaForm?.form.markAsPristine();
        this.wiringForm?.form.markAsPristine();
        this.enrichName.markAsPristine();
        this.emitDirty();
    }

    // ── enrichment save (W4b): write the companion, register it, bind the node by reference ──

    /**
     * The registered-enrichment write path, IDENTICAL to the Onboarding stage's: `POST /config/write
     * type=enrichment` then `POST /enrichment` — the per-batch partition-scoped path. ⚠ Never author
     * through a `*_job.toon` `enrich` job: that is a FULL recompute. The node carries only
     * `use: enrichment/<name>` — the companion file stays the single truth, never mirrored into
     * `node.config` (the D7 split-brain lesson).
     */
    private saveEnrichment(): void {
        const name = this.enrichName.value.trim() || this.node().id;
        if (this.enrichUneditable()) {
            // Nothing here may write; still let Apply carry the binding through.
            this.emitBinding(name);
            return;
        }
        if (!this.lens.canAuthorWorkbench()) {
            this.toastr.warning('Your lens is read-only.');
            return;
        }
        if (this.wiringForm && !this.wiringForm.validate()) return;
        const parts = this.enrichEditor()?.build();
        if (!parts) return;

        // Overlay the asked wiring onto the existing config's blocks: keys this form OWNS are replaced
        // wholesale (a cleared field deletes its key, never resurrects the old value), while unmodeled
        // keys survive the save verbatim.
        const formValue = compact(this.wiringForm?.value() ?? {});
        const wiring = nestKeys(formValue) as Record<string, Record<string, unknown>>;
        const existing = this.enrichSource();
        const ownedLeaves = (root: string): string[] =>
            this.wiringSpecs
                .map((s) => s.key.split(KEY_SEP))
                .filter(([r]) => r === root)
                .map(([, leaf]) => leaf);
        const block = (root: string): Record<string, unknown> => {
            const base: Record<string, unknown> =
                typeof existing === 'object' && existing?.[root] && typeof existing[root] === 'object'
                    ? { ...(existing[root] as Record<string, unknown>) }
                    : {};
            for (const leaf of ownedLeaves(root)) delete base[leaf];
            return { ...base, ...(wiring[root] ?? {}) };
        };
        // Both partition keys are set explicitly rather than left to `nestKeys`, which PRUNES an empty
        // array — and an absent `partitions` makes `EnrichmentConfig.fromMap` throw.
        const input = block('input');
        input['partitions'] = authoredList(formValue[`input${KEY_SEP}partitions`]);
        const output = block('output');
        output['partitions'] = remarryPartitionSources(authoredList(formValue[`output${KEY_SEP}partitions`]), existing);
        const draft: Record<string, unknown> = { name, input, output, transform: parts.transform };
        const triggers = block('triggers');
        if (Object.keys(triggers).length > 0) draft['triggers'] = triggers;
        if (Object.keys(parts.references).length > 0) draft['references'] = parts.references;

        this.savingEnrichment.set(true);
        this.configApi.write('enrichment', draft, { overwrite: true }).subscribe({
            next: (written) => {
                // Register every save: enrichments do NOT hot-reload by mtime.
                this.configApi.registerEnrichment(written.path).subscribe({
                    next: () => {
                        this.toastr.success('Enrichment saved — runs after every committed batch');
                        this.emitBinding(name);
                    },
                    error: (e) => {
                        this.toastr.warning(
                            apiErrorMessage(
                                e,
                                'Saved, but registering failed — it will load on the next service restart.',
                            ),
                        );
                        this.emitBinding(name);
                    },
                });
            },
            error: (e) => {
                this.savingEnrichment.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save the enrichment.'));
            },
        });
    }

    /** Emit the node bound to the companion by reference (plus any legacy free-form keys). */
    private emitBinding(name: string): void {
        this.savingEnrichment.set(false);
        const n = this.node();
        const config: Record<string, unknown> = {};
        for (const row of this.form.getRawValue().config as { key: string; value: string }[]) {
            if (row.key && row.key.trim()) config[row.key.trim()] = row.value;
        }
        this.markPristine();
        this.applied.emit({
            id: n.id,
            type: n.type,
            name: n.name,
            description: n.description,
            use: `enrichment/${name}`,
            config: Object.keys(config).length ? config : undefined,
        });
    }

    private configRow(key: string, value: string) {
        return this.fb.group({
            key: this.fb.control(key, { nonNullable: true }),
            value: this.fb.control(value, { nonNullable: true }),
        });
    }
}

/** Drop blank/null entries so the wiring overlay never writes an empty key over a real one. */
function compact(m: Record<string, unknown>): Record<string, unknown> {
    return Object.fromEntries(Object.entries(m).filter(([, v]) => v !== '' && v != null));
}

/** A `type: 'list'` control's value as a real string list. Tolerates the comma string `flattenBlock`
 *  produces for any list it was handed, so a value that skipped the chips control still lands right. */
function authoredList(v: unknown): string[] {
    if (Array.isArray(v)) return v.map((e) => String(e ?? '').trim()).filter((e) => e !== '');
    if (typeof v === 'string')
        return v
            .split(',')
            .map((s) => s.trim())
            .filter((s) => s !== '');
    return [];
}

/** The column names of `partitions` entries, which may be bare names or `{column, source}` maps —
 *  the read half of the map-entry round-trip (mirrors `EnrichmentConfig.partitionColumns`). */
function partitionColumns(entries: readonly unknown[]): string[] {
    return entries
        .map((e) => (e && typeof e === 'object' ? ((e as Record<string, unknown>)['column'] ?? '') : e))
        .map((c) => String(c ?? '').trim())
        .filter((c) => c !== '');
}

/**
 * Re-attach the `source` an existing `output.partitions` entry declared, so authoring the grain
 * through string-only chips cannot silently drop the event-time declaration that drives the recorded
 * bounds. A column the operator kept keeps its map form; a newly-added one is written as a bare name;
 * a removed one takes its `source` with it.
 */
function remarryPartitionSources(authored: unknown, existingConfig: unknown): unknown {
    if (!Array.isArray(authored)) return authored;
    const output =
        existingConfig && typeof existingConfig === 'object'
            ? (existingConfig as Record<string, unknown>)['output']
            : null;
    const prior = output && typeof output === 'object' ? (output as Record<string, unknown>)['partitions'] : null;
    if (!Array.isArray(prior)) return authored;

    const sources = new Map<string, unknown>();
    for (const e of prior) {
        if (!e || typeof e !== 'object') continue;
        const entry = e as Record<string, unknown>;
        const column = String(entry['column'] ?? '').trim();
        if (column !== '' && entry['source'] != null) sources.set(column, entry['source']);
    }
    if (sources.size === 0) return authored;

    return authored.map((c) => {
        const column = String(c ?? '').trim();
        return sources.has(column) ? { column, source: sources.get(column) } : c;
    });
}
