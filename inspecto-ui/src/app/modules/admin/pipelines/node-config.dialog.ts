import { Component, computed, effect, inject, signal, viewChild, ViewChild } from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule, ValidatorFn } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    AuthoredNode,
    CatalogService,
    ComponentDef,
    ComponentsService,
    ComponentTestResult,
    ComponentType,
    ConfigService,
    LensService,
    PipelinesService,
} from 'app/inspecto/api';
import { CollectorConfigComponent } from 'app/inspecto/collector/collector-config.component';
import { AttributeSpec, KEY_SEP, flattenBlock, nestKeys } from 'app/inspecto/component-model';
import { buildConfiguredNode, splitNodeConfig } from './node-config-build';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { pipelineOptionLoader, referenceOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { EnrichmentEditorComponent } from 'app/inspecto/enrichment/enrichment-editor.component';
import { ENRICHMENT_WIRING_ATTRIBUTES } from 'app/inspecto/enrichment/enrichment-attributes';
import { ComponentFormDialog, ComponentFormResult } from 'app/modules/admin/components/component-form.dialog';
import { groupByValidator, measuresValidator } from './measure-grammar';
import { nodeAttributesFor } from './node-attributes';
import { environment } from 'environments/environment';

/**
 * Dialog data: the node to configure, its (already-resolved) type/category labels for the header, and the
 * registry kind this node binds (`grammar` for a parser, `transform`, `sink`) — drives the in-graph
 * choose-or-create component picker. `null` ⇒ no registry binding (free-text `use` only).
 */
export interface NodeConfigData {
    node: AuthoredNode;
    typeLabel: string;
    categoryLabel: string;
    bindKind?: ComponentType | null;
    /**
     * The node type's config vocabulary as **published by the server** (§3.1) — the host reads it off the
     * `GET /pipelines/node-types` catalog it already loads for the palette.
     *
     * `undefined` ⇒ the catalog has not resolved (or this host does not supply it), so the dialog falls
     * back to the local `node-attributes.ts` table. An empty array is NOT the same thing: it is the server
     * stating this type has no schema, and it must not silently re-enable the fallback — otherwise a type
     * the server deliberately unspecced would keep drawing a stale client form.
     */
    attributes?: AttributeSpec[];
}

/** Dialog close payload: the edited node (absent ⇒ the user cancelled). */
export interface NodeConfigResult {
    node: AuthoredNode;
}

/**
 * Per-processor configuration popup (NiFi "Configure Processor"). Opened by double-clicking a node on the
 * flow-editor canvas (or the inspector's Configure button); edits a single {@link AuthoredNode}'s
 * name/description/component-ref + config and returns the updated node. Identity (`id`/`type`) is fixed.
 *
 * Config is **schema-driven** when the node type has a declared attribute schema
 * ({@link nodeAttributesFor}): the shared `<inspecto-schema-form>` renders required/optional/advanced
 * tiers. Any config key outside that schema — and every key for a plugin/unknown type with no schema —
 * lives in the collapsed **Additional config** free-form key/value editor, so nothing is ever lost
 * (review: `docs/superpower/reviews/node-config.md`).
 */
@Component({
    selector: 'app-node-config-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatDialogModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoDialogResizeDirective,
        InspectoSchemaFormComponent,
        CollectorConfigComponent,
        EnrichmentEditorComponent,
    ],
    template: `
        <h2 mat-dialog-title class="flex items-center gap-2" inspectoDialogResize #chrome="inspectoDialogResize">
            <span class="min-w-0 truncate">Configure · {{ data.node.id }}</span>
            <span class="flex-1"></span>
            <button
                mat-icon-button
                type="button"
                [attr.aria-label]="chrome.maximized() ? 'Exit full screen' : 'Full screen'"
                [matTooltip]="chrome.maximized() ? 'Exit full screen' : 'Full screen'"
                (click)="chrome.toggleMaximize()"
            >
                <mat-icon
                    class="icon-size-5"
                    [svgIcon]="chrome.maximized() ? 'heroicons_outline:arrows-pointing-in' : 'heroicons_outline:arrows-pointing-out'"
                ></mat-icon>
            </button>
        </h2>
        <form [formGroup]="form" (ngSubmit)="save()">
            <mat-dialog-content class="space-y-1">
                <p class="mb-2 text-xs opacity-70">{{ data.typeLabel }} · {{ data.categoryLabel }}</p>

                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Name</mat-label>
                    <input matInput formControlName="name" [placeholder]="data.node.id" cdkFocusInitial />
                </mat-form-field>
                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Description</mat-label>
                    <input matInput formControlName="description" />
                </mat-form-field>
                <!-- Not for enrichment nodes: their "use" is the enrichment/name binding below. -->
                @if (data.bindKind && !isEnrichment) {
                    <div class="mb-1">
                        <div class="flex items-end gap-2">
                            <mat-form-field class="flex-1" subscriptSizing="dynamic">
                                <mat-label>{{ bindLabel }}</mat-label>
                                <mat-select [value]="selectedComponentId()"
                                            (selectionChange)="selectComponent($event.value)">
                                    <mat-option [value]="null">— none —</mat-option>
                                    @for (o of componentOptions(); track o.name) {
                                        <mat-option [value]="o.name">{{ o.name }}</mat-option>
                                    }
                                </mat-select>
                            </mat-form-field>
                            <button mat-stroked-button type="button" class="mb-1" (click)="createComponent()">
                                <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                                <span class="ml-1">New {{ data.bindKind }}</span>
                            </button>
                        </div>
                        <p class="text-xs opacity-60">
                            Choose a reusable {{ data.bindKind }} or create one inline · bound as
                            <span class="font-mono">{{ form.value.use || '—' }}</span>
                        </p>
                    </div>
                } @else if (!isEnrichment && !isAcquisition) {
                    <!-- Acquisition owns "use" through its Connection attribute (see isAcquisition), so
                         it must not also get a free-text box — two controls writing one field. -->
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>Use (component ref)</mat-label>
                        <input matInput formControlName="use" placeholder="transform/my_component" />
                    </mat-form-field>
                }

                <!-- Enrichment (W4b): the node authors the REAL companion config through the shared
                     editor — written via POST /config/write + registered via POST /enrichment (the
                     per-batch path), never a full-recompute job. The node itself only carries the
                     "use: enrichment/name" binding; the companion file is the single truth. -->
                @if (isEnrichment) {
                    <div class="mb-1 mt-2 text-xs font-semibold uppercase opacity-70">Enrichment</div>
                    @if (enrichSource() === 'loading') {
                        <div class="flex items-center gap-2 py-3">
                            <mat-spinner diameter="18"></mat-spinner>
                            <span class="text-sm opacity-70">Loading the bound enrichment…</span>
                        </div>
                    } @else if (enrichUneditable()) {
                        <inspecto-alert variant="warning" title="Hand-authored transform file">
                            This enrichment uses <code>transform_file</code> — edit its TOON directly;
                            saving here would overwrite the file reference with inline SQL.
                        </inspecto-alert>
                    } @else {
                        <mat-form-field class="w-full" subscriptSizing="dynamic">
                            <mat-label>Enrichment name</mat-label>
                            <input matInput [formControl]="enrichName" [placeholder]="data.node.id" />
                        </mat-form-field>
                        <inspecto-schema-form [specs]="wiringSpecs" [initial]="wiringInitial()"
                                              [optionLoaders]="wiringLoaders"></inspecto-schema-form>
                        <inspecto-enrichment-editor [referenceOptions]="refOptions()" />
                    }
                }

                <!-- Acquisition authors the collector block, so it renders the SAME shared surface
                     Onboarding's Collection stage renders — mode toggle, Test connection, create a
                     Connection in place, derived connector — instead of a bare schema form. -->
                @if (isAcquisition) {
                    <div class="mb-1 mt-2 text-xs font-semibold uppercase opacity-70">Config</div>
                    <inspecto-collector-config
                        [specs]="specs()"
                        [initial]="schemaInitial"
                        [storedConnector]="storedConnector"
                        (submitted)="save()"
                    />
                } @else if (specs().length) {
                    <!-- Schema-driven config for known node types (required up front, rest behind disclosure). -->
                    <div class="mb-1 mt-2 text-xs font-semibold uppercase opacity-70">Config</div>
                    <inspecto-schema-form
                        [specs]="specs()"
                        [initial]="schemaInitial"
                        [optionLoaders]="configLoaders"
                        [extraValidators]="configValidators"
                    ></inspecto-schema-form>
                }

                <!-- Additional / free-form config: the primary editor for unknown types, else a collapsed
                     escape hatch for keys outside the schema. -->
                <div class="mb-1 mt-2 flex items-center justify-between">
                    <button type="button" class="flex items-center gap-1 text-xs font-semibold uppercase opacity-70"
                            [attr.aria-expanded]="freeFormOpen()" (click)="freeFormOpen.set(!freeFormOpen())">
                        <mat-icon class="icon-size-4" [svgIcon]="freeFormOpen() ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'"></mat-icon>
                        {{ specs().length ? 'Additional config' : 'Config' }}
                        @if (configRows.length) { <span class="opacity-60">({{ configRows.length }})</span> }
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
                                <button mat-icon-button type="button" (click)="removeConfigRow($index)"
                                        aria-label="Remove config entry">
                                    <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                                </button>
                            </div>
                        }
                        @if (!configRows.length) {
                            <p class="text-sm opacity-60">No extra config — add a key/value entry above.</p>
                        }
                    </div>
                }

                <!-- Run just this processor over a bounded sample (no production write) -->
                @if (testAvailable) {
                <div class="pt-2">
                    <button type="button" mat-stroked-button (click)="test()" [disabled]="testing">
                        @if (testing) { <mat-spinner diameter="16" class="mr-2"></mat-spinner> }
                        <mat-icon class="icon-size-5" svgIcon="heroicons_outline:bolt"></mat-icon>
                        <span class="ml-1">Test processor</span>
                    </button>
                    @if (testResult; as r) {
                        <inspecto-alert
                            class="mt-2 block"
                            [variant]="r.ok ? 'success' : 'error'"
                            [icon]="r.ok ? 'heroicons_outline:check-circle' : 'heroicons_outline:x-circle'"
                        >
                            <span class="font-semibold">{{ r.ok ? 'Passed' : 'Failed' }}</span> · {{ r.rowCount }} row(s)
                            <div class="text-secondary mt-0.5">{{ r.detail }}</div>
                            @if (r.rows.length) {
                                <pre class="mt-1 max-h-32 overflow-auto rounded p-1 text-xs"
                                     style="background: var(--gamma-bg-default)">{{ preview(r.rows) }}</pre>
                            }
                        </inspecto-alert>
                    }
                </div>
                }
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button type="button" mat-button mat-dialog-close>Cancel</button>
                <button type="submit" mat-flat-button color="primary" [disabled]="savingEnrichment()">
                    @if (savingEnrichment()) { <mat-spinner diameter="16" class="mr-2"></mat-spinner> }
                    Save
                </button>
            </mat-dialog-actions>
        </form>
    `,
})
export class NodeConfigDialog {
    private fb = inject(FormBuilder);
    private api = inject(PipelinesService);
    private components = inject(ComponentsService);
    private configApi = inject(ConfigService);
    private catalog = inject(CatalogService);
    private lens = inject(LensService);
    private toastr = inject(ToastrService);
    private dialog = inject(MatDialog);
    private ref = inject(MatDialogRef<NodeConfigDialog, NodeConfigResult>);
    readonly data = inject<NodeConfigData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) private schemaForm?: InspectoSchemaFormComponent;
    /**
     * The acquisition branch's config surface. A view query does not cross a component boundary, so
     * the schema form INSIDE this component is invisible to the query above — the two never collide,
     * and {@link configForm} picks whichever one this node actually rendered.
     */
    @ViewChild(CollectorConfigComponent) private collector?: CollectorConfigComponent;

    // ── enrichment nodes (W4b): the dialog authors the REAL companion `*_enrich.toon` ──
    readonly isEnrichment = this.data.node.type === 'enrichment';
    readonly wiringSpecs = ENRICHMENT_WIRING_ATTRIBUTES;
    readonly wiringLoaders = { triggers__on_pipeline: pipelineOptionLoader() };

    /** Loaders for the schema-driven config form's entity-reference keys (join's Reference picker). */
    readonly configLoaders = { reference: referenceOptionLoader() };
    /** The bound companion config: `'loading'` while reading, `null` = authoring fresh. */
    readonly enrichSource = signal<'loading' | Record<string, unknown> | null>(null);
    /** A hand-authored `transform_file` config must not be overwritten with inline SQL. */
    readonly enrichUneditable = computed(() => {
        const s = this.enrichSource();
        return typeof s === 'object' && s !== null && 'transform_file' in s;
    });
    readonly wiringInitial = computed<Record<string, unknown>>(() => {
        const s = this.enrichSource();
        if (!s || s === 'loading') return {};
        return flattenBlock({
            input: (s['input'] as Record<string, unknown>) ?? {},
            output: (s['output'] as Record<string, unknown>) ?? {},
            triggers: (s['triggers'] as Record<string, unknown>) ?? {},
        });
    });
    /** Produced Reference Datasets bindable by name (same source the Onboarding stage uses). */
    readonly refOptions = signal<{ id: string; label: string }[]>([]);
    readonly enrichName = this.fb.control('', { nonNullable: true });
    readonly savingEnrichment = signal(false);
    private readonly enrichEditor = viewChild(EnrichmentEditorComponent);

    /** Existing components of the bound kind (the picker's options); empty until loaded / when not binding. */
    readonly componentOptions = signal<ComponentDef[]>([]);
    /** Title-cased label for the binding field ("Grammar" / "Transform" / "Sink"). */
    readonly bindLabel = this.data.bindKind
        ? this.data.bindKind.charAt(0).toUpperCase() + this.data.bindKind.slice(1)
        : '';

    /**
     * An acquisition node's Connection is a **binding, not config** (D3-remainder, closed 2026-08-04).
     * `PipelineEditable` carries it on `use: connection/<name>` and strips a cfg-level one on both lift
     * (`:125`) and lower (`:248`), so the declared `connection` attribute used to be discarded on every
     * save while the operator had nothing else to set — `bindKindFor('SOURCE')` is `null`, so the dialog
     * showed only a free-text `use` box.
     *
     * <p>⚠ It is deliberately NOT wired as a `bindKind`: the component picker calls
     * `GET /components/{kind}`, and a Connection is not a `ComponentType` — it has its own service and
     * route. So the {@link CollectorConfigComponent} picker stays the surface, and this flag makes it
     * write `use:` instead of cfg. The attribute also stays in the shared `COLLECTOR_ATTRIBUTES` because
     * Onboarding authors the `collector:` block directly, where `connection` IS a real key.
     *
     * <p>Since 2026-08-04 the whole surface is that shared component, so this node gets Onboarding's
     * mode toggle, Test connection and create-a-Connection affordances, and — the presentation half of
     * the unification — writes the DERIVED `collector.connector` it was previously leaving to whatever
     * the file already had.
     */
    readonly isAcquisition = this.data.node.type === 'acquisition';
    // The `use: connection/<name>` prefix is spelled once, in `node-config-build.ts` (CONNECTION_REF).
    /** The node's stored `connector`, so the shared component can grandfather a hand-authored one. */
    readonly storedConnector = String(this.data.node.config?.['connector'] ?? '');

    /**
     * The node type's declared attribute schema (empty ⇒ free-form editor only).
     *
     * <p>§3.1: the SERVED vocabulary wins. The local table is the fallback for before the catalog resolves
     * and for the offline build. `?? ` — not `||` or a length check — so a served empty array is honoured
     * as "the server says this type has no schema" rather than falling through to the client copy.
     */
    readonly specs = computed<AttributeSpec[]>(
        () => this.data.attributes ?? nodeAttributesFor(this.data.node.type) ?? [],
    );
    /** Schema-form seed: the node's config entries whose key the schema knows. */
    readonly schemaInitial: Record<string, unknown> = {};
    /**
     * Per-key validators for rules the published spec cannot express. Both belong to
     * `transform.summarize`: the pipeline never parses that block (summarize is authoring-only until the
     * executor arms it) and the Job that does runs on its own schedule, so an unvalidated typo in either
     * field surfaces far away from the form that caused it. See `measure-grammar.ts`.
     *
     * <p>A plain field, not a computed — `specs` re-applies it on every spec swap, and the object is
     * keyed by attribute so a node type carrying neither key simply matches nothing.
     */
    readonly configValidators: Record<string, ValidatorFn[]> = {
        measures: [measuresValidator()],
        group_by: [groupByValidator()],
    };
    /** Free-form editor open state — open by default when there's no schema, or when extra keys exist. */
    readonly freeFormOpen = signal(false);

    testing = false;
    testResult: ComponentTestResult | null = null;
    /**
     * `POST /components/{type}/{id}/test` exists only in the offline mock — the real ControlApi has
     * no `/components` surface at all, so an authored node's dotted type (`transform.filter`) has
     * nothing to hit. Hide the action rather than ship a button that 404s.
     */
    readonly testAvailable = environment.mockPipelines;

    readonly form = this.fb.group({
        name: this.fb.control(''),
        description: this.fb.control(''),
        use: this.fb.control(''),
        config: this.fb.array<ReturnType<NodeConfigDialog['configRow']>>([]),
    });

    constructor() {
        const n = this.data.node;
        this.form.patchValue({
            name: n.name ?? '',
            description: n.description ?? '',
            use: n.use ?? '',
        });
        // Split the stored config: schema-known keys seed the schema-form; the rest become free-form
        // rows. The subtle parts (flat-vs-nested key comparison, the `use:` Connection seed, the
        // derived-connector skip) live in `splitNodeConfig` — shared with the definition drawer.
        const split = splitNodeConfig(n, this.specs(), this.isAcquisition);
        Object.assign(this.schemaInitial, split.schemaInitial);
        for (const row of split.extraRows) this.configRows.push(this.configRow(row.key, row.value));
        const extraKeys = split.extraRows.length;
        // Show the free-form editor up front only when it's the primary surface or already carries
        // keys. For an enrichment node the primary surface is the shared editor (W4b), so free-form
        // only opens when legacy keys are actually present.
        this.freeFormOpen.set(this.isEnrichment ? extraKeys > 0 : this.specs().length === 0 || extraKeys > 0);
        if (this.data.bindKind) this.loadComponents();
        if (this.isEnrichment) this.initEnrichment();
    }

    /** Load the bound companion (when `use` names one) + the bindable Reference Datasets, and
     *  hydrate the shared editor once both it and the config exist (the read is async). */
    private initEnrichment(): void {
        const use = this.data.node.use ?? '';
        const bound = use.startsWith('enrichment/') ? use.slice('enrichment/'.length) : null;
        this.enrichName.setValue(bound ?? this.data.node.id);
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
            next: (nodes) => this.refOptions.set(nodes
                .filter((n) => typeof n.attrs?.['pipeline'] === 'string')
                .map((n) => ({ id: String(n.attrs!['pipeline']), label: n.label }))),
            error: () => this.refOptions.set([]),
        });
        effect(() => {
            const editor = this.enrichEditor();
            const src = this.enrichSource();
            if (editor && src && src !== 'loading' && !editor.isDirty()) editor.hydrate(src);
        });
    }

    private loadComponents(): void {
        this.components.list(this.data.bindKind!).subscribe({
            next: (list) => this.componentOptions.set(list),
            error: () => this.componentOptions.set([]),
        });
    }

    /** The currently-bound component id, parsed out of the `<kind>/<id>` ref in `use` (null when unbound). */
    selectedComponentId(): string | null {
        const use = this.form.get('use')!.value ?? '';
        const prefix = `${this.data.bindKind}/`;
        return use.startsWith(prefix) ? use.slice(prefix.length) : null;
    }

    /** Bind (or clear) the node's `use` to the chosen component of the bound kind. */
    selectComponent(id: string | null): void {
        this.form.patchValue({ use: id ? `${this.data.bindKind}/${id}` : '' });
    }

    /** Author a new component of the bound kind inline (reuses the registry form), then bind it. */
    createComponent(): void {
        if (!this.data.bindKind) return;
        const ref = this.dialog.open(ComponentFormDialog, {
            width: '560px',
            autoFocus: false,
            data: { kind: this.data.bindKind },
        });
        ref.afterClosed().subscribe((res?: ComponentFormResult) => {
            if (!res?.saved) return;
            this.componentOptions.update((opts) =>
                opts.some((o) => o.name === res.saved!.name) ? opts : [...opts, res.saved!]);
            this.selectComponent(res.saved.name);
        });
    }

    get configRows(): FormArray {
        return this.form.get('config') as FormArray;
    }

    addConfigRow(): void {
        this.configRows.push(this.configRow('', ''));
    }

    removeConfigRow(i: number): void {
        this.configRows.removeAt(i);
    }

    /** Run just this processor over a bounded sample through the production logic (scratch-only, no write). */
    test(): void {
        this.testing = true;
        this.testResult = null;
        this.api.testNode(this.data.node.type, this.data.node.id).subscribe({
            next: (r) => {
                this.testing = false;
                this.testResult = r;
            },
            error: (e) => {
                this.testing = false;
                this.testResult = {
                    type: this.data.node.type,
                    id: this.data.node.id,
                    ok: false,
                    detail: apiErrorMessage(e, 'Test failed'),
                    rowCount: 0,
                    rows: [],
                };
            },
        });
    }

    /** A compact JSON preview of the first few sample rows the test produced. */
    preview(rows: Record<string, unknown>[]): string {
        return rows.slice(0, 3).map((r) => JSON.stringify(r)).join('\n');
    }

    /**
     * Whichever config surface this node actually rendered: the shared collector component for an
     * acquisition node, else the generic schema form. Both expose the same `validate()`/`value()`
     * pair, so the save path below stays one path.
     */
    private configForm(): { validate(): boolean; value(): Record<string, unknown> } | undefined {
        return this.isAcquisition ? this.collector : this.schemaForm;
    }

    save(): void {
        if (this.isEnrichment) {
            this.saveEnrichment();
            return;
        }
        const configForm = this.configForm();
        if (configForm && !configForm.validate()) return;
        // Resolve the derived connector BEFORE building anything — a refusal (an unsaved Connection
        // id, or Connection mode with nothing picked) explains itself in the component and aborts.
        const connector = this.isAcquisition ? this.collector?.resolveConnector() ?? null : null;
        if (this.isAcquisition && !connector) return;
        const v = this.form.getRawValue();
        // The assembly — nest-then-merge over the prior config, literal free-form rows last, the
        // acquisition Connection→`use:` move — is `buildConfiguredNode`, shared with the definition
        // drawer (see `node-config-build.ts` for the D4 nesting + unmodeled-sub-key rationale).
        const node = buildConfiguredNode({
            node: this.data.node,
            specs: this.specs(),
            formValues: configForm ? configForm.value() : null,
            freeRows: v.config as { key: string; value: string }[],
            name: v.name ?? undefined,
            description: v.description ?? undefined,
            use: v.use ?? undefined,
            isAcquisition: this.isAcquisition,
            connector,
        });
        this.ref.close({ node });
    }

    private configRow(key: string, value: string) {
        return this.fb.group({
            key: this.fb.control(key, { nonNullable: true }),
            value: this.fb.control(value, { nonNullable: true }),
        });
    }

    // ── enrichment save (W4b): write the companion, register it, bind the node by reference ──

    /**
     * The registered-enrichment write path, IDENTICAL to the Onboarding stage's: `POST /config/write
     * type=enrichment` (fileBase writes `<name>_enrich.toon`) then `POST /enrichment` — the per-batch
     * partition-scoped path. ⚠ Never author through a `*_job.toon` `enrich` job: that is a FULL
     * recompute (plan §6). The node closes carrying only `use: enrichment/<name>` — the companion
     * file stays the single truth, never mirrored into `node.config` (the D7 split-brain lesson).
     */
    private saveEnrichment(): void {
        if (this.enrichUneditable()) {
            // Nothing here may write; still allow closing with the node's name/description edits.
            this.closeWithBinding(this.enrichName.value.trim() || this.data.node.id);
            return;
        }
        if (!this.lens.canAuthorWorkbench()) {
            this.toastr.warning('Your lens is read-only.');
            return;
        }
        if (this.schemaForm && !this.schemaForm.validate()) return;
        const parts = this.enrichEditor()?.build();
        if (!parts) return;
        const name = this.enrichName.value.trim() || this.data.node.id;

        // Overlay the asked wiring onto the existing config's blocks: keys this form OWNS are
        // replaced wholesale (a cleared field deletes its key, never resurrects the old value),
        // while unmodeled keys — `partitions` lists above all (no AttributeSpec list type) —
        // survive the save verbatim.
        const wiring = nestKeys(compact(this.schemaForm?.value() ?? {})) as Record<string, Record<string, unknown>>;
        const existing = this.enrichSource();
        const ownedLeaves = (root: string): string[] => this.wiringSpecs
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
        const draft: Record<string, unknown> = {
            name,
            input: block('input'),
            output: block('output'),
            transform: parts.transform,
        };
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
                        this.closeWithBinding(name);
                    },
                    error: (e) => {
                        this.toastr.warning(apiErrorMessage(e,
                            'Saved, but registering failed — it will load on the next service restart.'));
                        this.closeWithBinding(name);
                    },
                });
            },
            error: (e) => {
                this.savingEnrichment.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not save the enrichment.'));
            },
        });
    }

    /** Close returning the node bound to the companion by reference (plus any legacy free-form keys). */
    private closeWithBinding(name: string): void {
        this.savingEnrichment.set(false);
        const v = this.form.getRawValue();
        const config: Record<string, unknown> = {};
        for (const row of v.config as { key: string; value: string }[]) {
            if (row.key && row.key.trim()) config[row.key.trim()] = row.value;
        }
        this.ref.close({
            node: {
                id: this.data.node.id,
                type: this.data.node.type,
                name: v.name?.trim() || undefined,
                description: v.description?.trim() || undefined,
                use: `enrichment/${name}`,
                config: Object.keys(config).length ? config : undefined,
            },
        });
    }
}

/** Drop blank/null entries so the wiring overlay never writes an empty key over a real one. */
function compact(m: Record<string, unknown>): Record<string, unknown> {
    return Object.fromEntries(Object.entries(m).filter(([, v]) => v !== '' && v != null));
}
