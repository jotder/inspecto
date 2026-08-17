import {
    Component,
    computed,
    effect,
    inject,
    signal,
    viewChild,
    ViewChild,
    ChangeDetectionStrategy,
} from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule, ValidatorFn } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { map } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    AuthoredNode,
    CatalogService,
    ComponentDef,
    ComponentsService,
    ComponentType,
    ConfigService,
    LensService,
    SpacesService,
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
import { enrichmentWiringDefaults } from 'app/inspecto/enrichment/enrichment-wiring';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { ComponentFormDialog, ComponentFormResult } from 'app/modules/admin/components/component-form.dialog';
import { groupByValidator, measuresValidator } from './measure-grammar';
import { nodeAttributesFor } from './node-attributes';

/** The component families `ComponentRoutes` can dry-run — `schema`/`mapping` have no `/test` route. */
const TESTABLE_KINDS: ComponentType[] = ['transform', 'grammar', 'sink'];

/**
 * How many captured rows an inline test posts. The sample thread stores the parse preview UNCAPPED, while
 * both the route and its offline arm only ever answer with `rows.slice(0, 20)` — so sending the whole
 * sample buys a bigger request body and nothing else.
 */
const MAX_TEST_ROWS = 50;

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
    /**
     * What the HOST pipeline can tell an `enrichment` node about itself (definition-surface P6-c), so
     * the wiring form seeds derived values instead of asking the author to retype them. Only the
     * pipeline-derived facts travel — the conventions built on top of them live in
     * `enrichmentWiringDefaults`, shared with the Onboarding stage.
     *
     * `undefined` ⇒ this host has no pipeline context (or the node is not an enrichment): the form
     * opens blank, exactly as it did before.
     */
    enrichmentHost?: EnrichmentHostPipeline;
    /**
     * The rows the tab's sample thread parsed — what an inline test runs over. Plain rows, not the
     * thread itself: the dialog reads and never writes them, and the thread belongs to the TAB. Absent
     * or empty ⇒ no test is offered, because a test with no data is a lie.
     */
    sampleRows?: Record<string, unknown>[];
}

/** The host pipeline's identity plus where its Stage-1 output lands. */
export interface EnrichmentHostPipeline {
    /** The engine's normalized pipeline id — what `BatchEvent.pipeline()` carries. */
    pipelineId: string;
    /** Its output store, when exactly ONE destination could be resolved (see the editor's derivation). */
    inputDatabase?: string;
    inputFormat?: string;
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
    changeDetection: ChangeDetectionStrategy.Eager,
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
                    [svgIcon]="
                        chrome.maximized()
                            ? 'heroicons_outline:arrows-pointing-in'
                            : 'heroicons_outline:arrows-pointing-out'
                    "
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
                                <mat-select
                                    [value]="selectedComponentId()"
                                    (selectionChange)="selectComponent($event.value)"
                                >
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
                }
                <!-- No free-text "Use (component ref)" box, and no picker on a transform/sink.
                     (⚠ no backticks in this comment: it lives inside a template literal.)
                     Acquisition owns "use" through its Connection attribute (see isAcquisition) and a
                     parser through the Grammar editor — neither opens this dialog. That leaves the
                     kinds the flat config has NO home for, so every ref typed here was refused at save
                     (UNSUPPORTED_BINDING, AUTHOR-1(b)); its placeholder even advertised the refused
                     "transform/my_component" shape. The control itself stays, unrendered, so a ref an
                     existing file already carries is preserved and refused by name rather than silently
                     stripped here. -->

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
                            This enrichment uses <code>transform_file</code> — edit its TOON directly; saving here would
                            overwrite the file reference with inline SQL.
                        </inspecto-alert>
                    } @else {
                        <mat-form-field class="w-full" subscriptSizing="dynamic">
                            <mat-label>Enrichment name</mat-label>
                            <input matInput [formControl]="enrichName" [placeholder]="data.node.id" />
                        </mat-form-field>
                        <inspecto-schema-form
                            [specs]="wiringSpecs"
                            [initial]="wiringInitial()"
                            [optionLoaders]="wiringLoaders"
                        ></inspecto-schema-form>
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
                        {{ specs().length ? 'Additional config' : 'Config' }}
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

                <!-- Testing a processor needs a sample, and the component editor is where that lives. -->
                @if (testableComponentId()) {
                    <div class="pt-2">
                        <button type="button" mat-stroked-button (click)="openComponentTest()">
                            <mat-icon class="icon-size-5" svgIcon="heroicons_outline:bolt"></mat-icon>
                            <span class="ml-1">Test {{ testableComponentId() }}…</span>
                        </button>
                        <div class="text-secondary mt-1 text-sm">
                            Opens the {{ data.bindKind }} editor, where you can run it over a sample.
                        </div>
                    </div>
                }

                <!-- The INLINE test: the config on screen, over the tab's own parsed rows. Offered only
                     when there are rows — a test with no data would report success over nothing. -->
                @if (canTestInline) {
                    <div class="pt-2">
                        <button type="button" mat-stroked-button [disabled]="testing()" (click)="runInlineTest()">
                            <mat-icon class="icon-size-5" svgIcon="heroicons_outline:bolt"></mat-icon>
                            <span class="ml-1">Test this Step</span>
                        </button>
                        <div class="text-secondary mt-1 text-sm">
                            Runs these settings over the {{ testRows.length }} row(s) your parse step
                            produced. Nothing is written.
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
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button type="button" mat-button (click)="requestClose()">Cancel</button>
                <button type="submit" mat-flat-button color="primary" [disabled]="savingEnrichment()">
                    @if (savingEnrichment()) {
                        <mat-spinner diameter="16" class="mr-2"></mat-spinner>
                    }
                    Save
                </button>
            </mat-dialog-actions>
        </form>
    `,
})
export class NodeConfigDialog {
    private fb = inject(FormBuilder);
    private components = inject(ComponentsService);
    private configApi = inject(ConfigService);
    private catalog = inject(CatalogService);
    private lens = inject(LensService);
    private spaces = inject(SpacesService);
    private toastr = inject(ToastrService);
    private dialog = inject(MatDialog);
    private ref = inject(MatDialogRef<NodeConfigDialog, NodeConfigResult>);
    private confirm = inject(InspectoConfirmService);

    /**
     * Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). ⚠ The union of ALL
     * FOUR surfaces this dialog can render: its own header fields plus whichever config editor the
     * node type mounted. Asking only `configForm()` would miss the enrichment editor entirely, which
     * `configForm()` deliberately does not cover — and this is the dialog where a discard costs most,
     * since a node's config is the raw config-file section verbatim.
     */
    readonly requestClose = guardDirtyClose(
        this.ref,
        () =>
            this.form.dirty ||
            (this.schemaForm?.isDirty() ?? false) ||
            (this.collector?.isDirty() ?? false) ||
            (this.enrichEditor()?.isDirty() ?? false),
        this.confirm,
    );
    readonly data = inject<NodeConfigData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent)
    private schemaForm?: InspectoSchemaFormComponent;
    /**
     * The acquisition branch's config surface. A view query does not cross a component boundary, so
     * the schema form INSIDE this component is invisible to the query above — the two never collide,
     * and {@link configForm} picks whichever one this node actually rendered.
     */
    @ViewChild(CollectorConfigComponent)
    private collector?: CollectorConfigComponent;

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
        if (s === 'loading') return {};
        // Authoring fresh (no binding, or a binding that could not be read): derive what the host
        // pipeline already knows instead of presenting empty required fields — the same conventions
        // the Onboarding stage derives silently (P6-c). Read once: `enrichName` is a form control, so
        // this deliberately does NOT re-derive as the author renames — a seed that moved under an
        // edited form would clobber it.
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
        // `{column, source}` map entries (the sink shape, 2026-08-11); the chips are string[]-only, so
        // show the columns and re-marry `source` in saveEnrichment().
        flat[`input${KEY_SEP}partitions`] = authoredList(input['partitions']);
        flat[`output${KEY_SEP}partitions`] = partitionColumns(
            Array.isArray(output['partitions']) ? output['partitions'] : [],
        );
        return flat;
    });

    /** The derived seed for a fresh companion — empty when this host supplied no pipeline context. */
    private freshWiring(): Record<string, unknown> {
        const host = this.data.enrichmentHost;
        if (!host) return {};
        const w = enrichmentWiringDefaults({
            enrichName: this.enrichName.value.trim() || this.data.node.id,
            pipelineId: host.pipelineId,
            base: this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.',
            inputDatabase: host.inputDatabase,
            inputFormat: host.inputFormat,
        });
        const flat = flattenBlock({ input: w.input, output: w.output, triggers: w.triggers });
        // Same as above: the two partition chips hold real arrays, not `flattenBlock`'s comma string.
        flat[`input${KEY_SEP}partitions`] = w.input['partitions'];
        flat[`output${KEY_SEP}partitions`] = w.output['partitions'];
        return flat;
    }
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

    /**
     * The registered component this node binds, when it is one of the families the backend can test
     * (`ComponentRoutes` registers `POST /components/{transform|grammar|sink}/{id}/test`). Null for an
     * unbound node — one holding inline config binds no registered component.
     */
    testableComponentId(): string | null {
        if (!this.data.bindKind || !TESTABLE_KINDS.includes(this.data.bindKind)) return null;
        return this.selectedComponentId();
    }

    /**
     * The inline-preview family this node belongs to, or null. Keyed on the node's own TYPE — ⛔ not on
     * `data.bindKind`, which is `bindKindFor(category)` and therefore `'grammar'` for PARSE and **null
     * for everything else**; since `openNodeConfig` routes PARSE to the grammar editor, every node that
     * reaches this dialog had a null bindKind, and the Test affordance below could never render at all.
     *
     * <p>`grammar` is deliberately absent: a parse node never reaches this dialog.
     */
    readonly testFamily = ComponentsService.previewFamilyFor(this.data.node.type);

    /**
     * Whether an inline test can run: a supported family AND rows to run it over. Plain FIELDS, not
     * methods or `computed`s — both derive from `MAT_DIALOG_DATA`, which cannot change for the dialog's
     * lifetime, so a template-bound method would re-answer the same question every change-detection pass.
     */
    readonly canTestInline = !!this.testFamily && !!this.data.sampleRows?.length;
    /** The rows a test posts. Capped: the preview answers 20 either way, and the sample thread is uncapped. */
    readonly testRows = (this.data.sampleRows ?? []).slice(0, MAX_TEST_ROWS);

    readonly testing = signal(false);
    /** The last inline preview, as lines to render — one shape for both families, since only text is shown. */
    readonly testResult = signal<string[] | null>(null);
    readonly testError = signal<string | null>(null);

    /**
     * Test the config **being edited** through `POST /components/{family}/preview`, over the tab's own
     * parsed rows. Reads the live config surface rather than `node.config`, so it tests what is on screen
     * — an unsaved edit is exactly what an operator wants to try. ⚠ The transform arm must send the
     * node's `type` inside `config`: the route 422s a config that is not `transform.*`.
     */
    runInlineTest(): void {
        const family = this.testFamily;
        const rows = this.testRows;
        if (!family || !rows.length) return;
        const surface = this.configForm();
        if (surface && !surface.validate()) return;
        // Assemble through the SAME `buildConfiguredNode` the save path uses, so the test runs over the
        // config that would actually be written — nesting, free-form rows and all. A second, simpler
        // merge here would drift from the save and test a config that never ships.
        const config =
            buildConfiguredNode({
                node: this.data.node,
                specs: this.specs(),
                formValues: surface ? surface.value() : null,
                freeRows: this.form.getRawValue().config as { key: string; value: string }[],
                isAcquisition: false,
                connector: null,
            }).config ?? {};
        this.testing.set(true);
        this.testError.set(null);
        this.testResult.set(null);
        // One observable per family, then ONE subscribe: the arms differ only in the call and how the
        // response reads as text, so a third family adds a `map`, not another handler pair.
        const lines$ =
            family === 'transform'
                ? this.components.previewTransform({ ...config, type: this.data.node.type }, rows).pipe(
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
            next: (nodes) =>
                this.refOptions.set(
                    nodes
                        .filter((n) => typeof n.attrs?.['pipeline'] === 'string')
                        .map((n) => ({ id: String(n.attrs!['pipeline']), label: n.label })),
                ),
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
                opts.some((o) => o.name === res.saved!.name) ? opts : [...opts, res.saved!],
            );
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

    /**
     * Open the bound component in its own editor, where the sample-driven test already lives
     * (`ComponentFormDialog.runTest`). The node dialog deliberately does **not** grow its own test:
     * a real test needs sample rows / sample text, and that editor is the one place that collects them.
     */
    openComponentTest(): void {
        const id = this.testableComponentId();
        if (!id) return;
        this.components.get(this.data.bindKind!, id).subscribe({
            next: (def) =>
                this.dialog.open(ComponentFormDialog, {
                    width: '560px',
                    autoFocus: false,
                    data: { kind: this.data.bindKind, def },
                }),
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not open the component.')),
        });
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
        const connector = this.isAcquisition ? (this.collector?.resolveConnector() ?? null) : null;
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
        // while unmodeled keys survive the save verbatim.
        const formValue = compact(this.schemaForm?.value() ?? {});
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
        // array — and an absent `partitions` makes `EnrichmentConfig.fromMap` throw, so a grain-less
        // enrichment must still write `partitions: []`.
        const input = block('input');
        input['partitions'] = authoredList(formValue[`input${KEY_SEP}partitions`]);
        const output = block('output');
        output['partitions'] = remarryPartitionSources(authoredList(formValue[`output${KEY_SEP}partitions`]), existing);
        const draft: Record<string, unknown> = {
            name,
            input,
            output,
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
                        this.toastr.warning(
                            apiErrorMessage(
                                e,
                                'Saved, but registering failed — it will load on the next service restart.',
                            ),
                        );
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
 * a removed one takes its `source` with it. Returns the authored list unchanged when nothing declared
 * a `source`, so an enrichment that never used the map form keeps its plain shape.
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
