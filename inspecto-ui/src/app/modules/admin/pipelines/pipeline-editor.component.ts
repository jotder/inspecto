import {
    ChangeDetectionStrategy,
    Component,
    Input,
    OnInit,
    ViewChild,
    ViewEncapsulation,
    computed,
    effect,
    inject,
    input,
    signal,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { catchError, firstValueFrom, forkJoin, map, of, switchMap } from 'rxjs';
import {
    AuthoredPipeline,
    ConfigImpact,
    AuthoredNode,
    ComponentDef,
    ComponentsService,
    ConfigService,
    DatasetRegistrationService,
    PipelineRefusal,
    PipelineRunResult,
    PipelinesService,
    PipelineSummary,
    ProvenanceBatch,
    IconMap,
    IconMapService,
    LensService,
    apiErrorMessage,
    datasetManualHint,
    SpacesService,
} from 'app/inspecto/api';
import {
    type AttributeSpec,
    derivedPipelineId,
    parseUseRef,
    pipelineId,
    pipelineScaffold,
} from 'app/inspecto/component-model';
import { AiAssistComponent } from 'app/inspecto/ai-assist/ai-assist.component';
import { AiDraft } from 'app/inspecto/ai-assist/ai-draft';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { companionSchemaName, schemaNameFromPath, segmentPathsOf } from 'app/inspecto/segments';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { DefinitionDrawerComponent } from 'app/inspecto/components/definition-drawer.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSplitDirective } from 'app/inspecto/components/split.directive';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';
import { grammarContentAsParsingBlock, nonDelimitedGrammarBlock } from 'app/inspecto/grammar';
import { TransferMenuComponent } from 'app/inspecto/transfer';
import { StreamTransferService } from 'app/inspecto/transfer/stream-transfer.service';
import { G6GraphData } from 'app/modules/admin/catalog/catalog-graph';
import { PipelineDryRunPanelComponent } from './pipeline-dry-run-panel.component';
import { PipelineEditorGraphComponent } from './pipeline-editor-graph.component';
import { PipelineInspectorComponent } from './pipeline-inspector.component';
import { PipelinePaletteComponent } from './pipeline-palette.component';
import { PipelineGuaranteesPanelComponent } from './pipeline-guarantees-panel.component';
import { PipelineStepCardsComponent } from './pipeline-step-cards.component';
import { PipelineCollectionDefinitionComponent } from './pipeline-collection-definition.component';
import {
    PARSE_NODE_FRONTENDS,
    PipelineParseDefinitionComponent,
    isParseNodeType,
} from './pipeline-parse-definition.component';
import { EnrichmentHostPipeline, PipelineConfigDefinitionComponent } from './pipeline-config-definition.component';
import { PipelineLoadDefinitionComponent } from './pipeline-load-definition.component';
import { GrammarEditorDialog } from './grammar-editor.dialog';
import { PipelineOpenDialog } from './pipeline-open.dialog';
import { PipelineChangeIdDialog, PipelineChangeIdResultData } from './pipeline-change-id.dialog';
import { PipelineRenameDialog, PipelineRenameResultData } from './pipeline-rename.dialog';
import { PipelineSettingsDialog } from './pipeline-settings.dialog';
import type { PipelineSettings } from 'app/inspecto/api/pipelines.service';
import { PipelineTemplateDialog, PipelineTemplateResultData } from './pipeline-template.dialog';
import { RunToHereDialog } from './run-to-here.dialog';
import { ViewPreviewDialog } from './view-preview.dialog';
import {
    PipelineFinding,
    NodeStatus,
    NodeTypeGroup,
    TestOutcome,
    addEdgeToModel,
    addNodeToModel,
    applyNodePatchInModel,
    authoredToG6,
    candidateRelsFor,
    categoryLabel,
    categoryVisualKind,
    computeNodeStatus,
    decodeEdgeId,
    detectStepChain,
    encodeEdgeId,
    findingIcon,
    findingTint,
    flattenStepChain,
    groupByCategory,
    RECIPE_VERBS,
    addRouteBranch,
    insertRouteAfter,
    insertStepAfter,
    moveStepInChain,
    removeRouteBranch,
    removeStepFromChain,
    setRouteBranchWhere,
    setRouteDefault,
    nodeConfigEntries,
    nodeLastRunTotal,
    provenanceCounts,
    removeEdgeFromModel,
    removeNodeFromModel,
    setEdgeRelInModel,
    statusLabel,
    typeCategoryMap,
    typeLabelMap,
    uniqueNodeId,
    validatePipeline,
} from './pipeline-graph';
import { PipelineChecklistComponent } from './pipeline-checklist.component';
import { incompleteStages, pipelineLifecycle, PipelineStageId, StageChip, stageChecklist } from './pipeline-stages';

/** The `use:` prefix a Grammar component is referenced by — also how its ref is keyed in `validRefs`. */
const GRAMMAR_REF_PREFIX = 'grammar/';

/**
 * "2 datasets, 1 widget" — the impact report as a phrase for a confirm dialog. Names kinds and
 * counts rather than listing every id: the dialog has to be readable, and the operator only needs
 * to know the shape of what breaks before deciding. (From the onboarding shell, P6-e.)
 */
function describeDependents(impact: ConfigImpact): string {
    return Object.entries(impact.dependents)
        .map(([kind, items]) => `${items.length} ${kind}${items.length === 1 ? '' : 's'}`)
        .join(', ');
}

/**
 * Pipeline editor (T32, build-side NiFi UX) — author/edit a pipeline on an interactive G6 canvas; Save
 * lowers the graph to the canonical flat `*_pipeline.toon` (no UI surface writes `*_flow.toon`):
 * drag node types from the palette, click two nodes to connect, edit node config in the inspector, dry-run a
 * sample, and Save (PUT). The {@link AuthoredPipeline} signal is the logical truth; the canvas owns layout. Loaded
 * losslessly via {@code GET …/raw} so node config round-trips. Reached via the Pipelines pane's `editor` mode.
 *
 * The palette and the property drawer are their own presentational components
 * ({@link PipelinePaletteComponent}, {@link PipelineInspectorComponent}); every model mutation (add/remove
 * node or edge, re-label a relationship) is a pure reducer in `pipeline-graph.ts` — this container's job is
 * CRUD orchestration, canvas event wiring, and keeping the canvas in sync with the model (review:
 * `docs/superpower/reviews/pipeline-editor.md`).
 */
/**
 * The properties dock width a TABBED definition pane needs to render whole (S4). Below it the four
 * option tabs truncate with scroll arrows and the schema toolbar stacks. Transient: `ensureAtLeast`
 * never persists it, so a stored preference (wider or narrower) is the operator's to keep.
 */
const TABBED_PANE_WIDTH = 420;

@Component({
    selector: 'app-pipeline-editor',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatMenuModule,
        MatTooltipModule,
        InspectoAlertComponent,
        DefinitionDrawerComponent,
        PipelineCollectionDefinitionComponent,
        PipelineParseDefinitionComponent,
        PipelineConfigDefinitionComponent,
        PipelineLoadDefinitionComponent,
        PipelineDryRunPanelComponent,
        PipelineEditorGraphComponent,
        PipelineChecklistComponent,
        PipelineInspectorComponent,
        PipelinePaletteComponent,
        PipelineGuaranteesPanelComponent,
        PipelineStepCardsComponent,
        InspectoEmptyStateComponent,
        InspectoSplitDirective,
        TransferMenuComponent,
        AiAssistComponent,
    ],
    templateUrl: './pipeline-editor.component.html',
    // The editor is a full-bleed shell: it fills whatever the route gives it, and the canvas takes
    // whatever the docks don't. `min-h-0` is what lets the inner scroll regions actually scroll.
    host: { class: 'flex min-h-0 flex-1 flex-col' },
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class PipelineEditorComponent implements OnInit {
    private api = inject(PipelinesService);
    private configApi = inject(ConfigService);
    private transfer = inject(StreamTransferService);
    private components = inject(ComponentsService);
    private datasets = inject(DatasetRegistrationService);
    private iconMapApi = inject(IconMapService);
    private spaces = inject(SpacesService);
    private fb = inject(FormBuilder);
    private toast = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);
    private dialog = inject(MatDialog);
    /** Business lens = read-only across the Workbench (Wave-1 interview decision) — hides authoring. */
    protected lens = inject(LensService);

    @ViewChild(PipelineEditorGraphComponent) private canvas?: PipelineEditorGraphComponent;

    /**
     * View mode: the identical shell with every mutating affordance withheld. Distinct from the
     * Business-lens gate (a permission) — this is the user choosing to look rather than author, so both
     * are consulted through {@link canAuthor}, and neither alone is trusted.
     */
    @Input() readOnly = false;

    /**
     * Guided mode (P6-d): show the stage checklist strip. Host-supplied because "this pipeline came
     * from Onboard" is a routing fact, not a property of the graph — P6-a's redirect is what will set
     * it. Off by default, so the plain editor is unchanged for everyone who did not come that way.
     */
    readonly guided = input(false);

    /**
     * Open this pipeline as a tab on arrival (P6-a): the deep-link handshake the retired
     * `/catalog/onboard/:name` route redirects into. Empty ⇒ nothing is opened, exactly as before.
     */
    readonly openId = input('');

    /** Which checklist chip to land on, carried from the retired route's `:stage` segment. */
    readonly focusStage = input<PipelineStageId | ''>('');

    /**
     * Consume {@link openId} once per id — `select()` is a load, not an idempotent setter, so an
     * effect that re-ran on any unrelated signal would refetch the graph and discard the tab's edits.
     */
    private readonly openFromUrl = effect(() => {
        const id = this.openId();
        if (!id || id === this.openedFromUrl) return;
        this.openedFromUrl = id;
        this.select(id);
    });
    private openedFromUrl = '';

    /**
     * Land on the stage the deep link named, once its graph is in. Waits for the model because the
     * chip's node does not exist until then; fires once, so it never fights the operator's own
     * navigation afterwards.
     */
    private readonly focusFromUrl = effect(() => {
        const stage = this.focusStage();
        const loaded = this.model() && this.selectedId() === this.openId();
        if (!stage || !loaded || this.focusedFromUrl) return;
        this.focusedFromUrl = true;
        const chip = this.checklist().find((c) => c.id === stage);
        if (chip) this.openStage(chip);
    });
    private focusedFromUrl = false;

    readonly flows = signal<PipelineSummary[]>([]);

    // ── open set (tabs) ───────────────────────────────────────────────────────────────────────────
    /** The open tabs, in strip order. Nothing is open until the user opens something. */
    readonly openIds = signal<string[]>([]);
    /**
     * Per-tab graph + dirty state, so switching tabs never silently discards unsaved edits. The active
     * tab's graph lives in {@link model} as before; this is where the inactive ones wait.
     */
    private readonly cachedModels = new Map<string, AuthoredPipeline>();
    /**
     * The tab the most recent load was issued FOR. Every path that changes the active tab stamps it,
     * so a response that arrives after the operator has moved on can recognise itself as superseded
     * and drop out instead of overwriting the graph they are now editing.
     */
    private pendingSelect: string | null = null;
    private readonly dirtyIds = signal<ReadonlySet<string>>(new Set());
    /**
     * The sample thread, ONE PER TAB (operator-decided 2026-08-16; the wizard's D5 thread re-homed
     * after P6-e). 🔴 It is a Map here rather than a `providers: []` entry for the reason
     * {@link DefinitionStateService} states: providers are static per component instance and this
     * editor is a single instance hosting every tab, so one provider would leak one sample across
     * every open pipeline. Same shape as {@link cachedModels}, and dropped by the same
     * {@link forgetTab}.
     */
    private readonly sampleThreads = new Map<string, DefinitionStateService>();

    /**
     * Mirror the active tab's `dirty` into the per-tab set. One effect rather than touching the dozen
     * scattered `dirty.set(...)` call sites — those all describe *what changed*, and none of them should
     * have to know about tabs.
     */
    private readonly trackDirtyPerTab = effect(() => {
        const id = this.selectedId();
        const isDirty = this.dirty();
        if (!id) return;
        this.dirtyIds.update((s) => {
            if (s.has(id) === isDirty) return s; // no-op keeps the signal from re-notifying
            const next = new Set(s);
            if (isDirty) next.add(id);
            else next.delete(id);
            return next;
        });
    });
    /** Configurable processor icons/colours (empty until loaded → fall back to the per-kind glyph). */
    readonly iconMap = signal<IconMap>({});
    readonly selectedId = signal<string | null>(null);
    /** In-flight guard for the Pipeline Document export — the generator re-reads config on every call. */
    readonly exportingDocument = signal(false);
    /** A stream-config export is in flight ({@link exportConfig}) — a separate flag from the document
     *  export, which is a different format on a different route. */
    readonly exportingConfig = signal(false);
    readonly model = signal<AuthoredPipeline | null>(null);

    /**
     * The active tab's sample thread, or null before anything is open. ⚠ Reading the Map inside a
     * computed does not track it — deliberately: an entry is created once when its tab opens and never
     * replaced, so `selectedId` is the only dependency that can change the answer.
     */
    readonly sampleThread = computed(() => {
        const id = this.selectedId();
        return id ? (this.sampleThreads.get(id) ?? null) : null;
    });

    /** The list row for the selected pipeline — carries the `template` flag and the display name. */
    readonly selectedSummary = computed(() => {
        const id = this.selectedId();
        return id ? (this.flows().find((f) => f.name === id) ?? null) : null;
    });
    /** True when the selection is a non-runnable authoring template — it must not offer Activate. */
    readonly isTemplate = computed(() => this.selectedSummary()?.template === true);
    /** The selected pipeline as a transfer reference — what the export/import menu offers. */
    readonly transferItems = computed(() => {
        const id = this.selectedId();
        return id ? [{ kind: 'authored-pipeline' as const, id }] : [];
    });
    readonly paletteGroups = signal<NodeTypeGroup[]>([]);
    /** Public (template use only, e.g. the recipe view's category label): not otherwise part of the API. */
    readonly typeCat = signal<Map<string, string>>(new Map());
    /** node-type → per-type display label, so a Step card can say 'Join' rather than 'Transformer'. */
    readonly typeLabel = signal<Map<string, string>>(new Map());
    /** type → whether a save can lower it, from `GET /pipelines/node-types` (see G2/G5). */
    private readonly typeLowerable = signal<Map<string, boolean>>(new Map());
    /**
     * type → the config vocabulary the SERVER publishes (§3.1). Handed to the node dialog so the form is
     * driven by one definition; empty until the catalog resolves, at which point the dialog falls back to
     * the local `node-attributes.ts` table.
     */
    private readonly typeAttributes = signal<Map<string, AttributeSpec[]>>(new Map());

    readonly selectedNode = signal<AuthoredNode | null>(null);
    readonly selectedEdgeId = signal<string | null>(null);
    /** Two-click edge creation: the first node clicked, awaiting a target. */
    readonly connectFrom = signal<string | null>(null);

    readonly dirty = signal(false);
    readonly saving = signal(false);
    readonly loading = signal(false);
    /** Set when a write returns 503 (no `-Dassist.write.root`) — the editor is read-only. */
    readonly unavailable = signal(false);

    /**
     * The bottom dock's active tab, or `null` when it is collapsed. Dry-run output and validation
     * findings share one dock (they are both "output of the thing on the canvas"), so opening one
     * closes the other rather than stacking two bands under the graph.
     */
    /**
     * Run-to-here works against a real server since 2026-08-14 (Build→Test→Run Step 5c):
     * `POST /pipelines/authored/{id}/run?to=` is registered, runs the picked inbox files through the
     * real ingest path into a scratch root, and writes nothing to production. Ungated — it was
     * `environment.mockPipelines` only while the route was reserved-but-unregistered.
     *
     * ⚠ The `to=` cutoff itself is still unbuilt (Step 5b): the server runs the whole graph and says so
     * in `warnings`, which the dock renders. Do not re-gate this on the cutoff landing.
     */
    readonly scratchRunAvailable = true;

    readonly bottomTab = signal<'dryrun' | 'validation' | null>(null);

    // ── canvas status (Stage 2) + validation/activation (Stage 4) ──
    /** Known registry refs (`grammar/x`, `transform/y`, …) — drives dangling detection once loaded. */
    private readonly validRefs = signal<Set<string>>(new Set());
    /** Stored Grammar components, offered by the Parse drawer as starting points (copies, not binds). */
    readonly grammarTemplates = signal<ComponentDef[]>([]);
    private readonly refsLoaded = signal(false);
    /** Per-node test outcome from the last run-to-here (`tested` / `rejects`). */
    private readonly testedStatus = signal<Map<string, TestOutcome>>(new Map());
    // ── T17 live last-run overlay: the flow's most recent real run, from the durable provenance store ──
    /** The most recent recorded run of the selected flow (`null` = none yet, or provenance backend unset). */
    readonly lastRunBatch = signal<ProvenanceBatch | null>(null);
    /** `nodeId|rel` → row count for {@link lastRunBatch} — paints edge weights and the inspector's node total. */
    private readonly lastRunCounts = signal<Map<string, number>>(new Map());
    /** node-type → emitted relationships, for the edge relationship picker. */
    private readonly typeEmits = signal<Map<string, string[]>>(new Map());
    readonly findings = signal<PipelineFinding[]>([]);
    readonly activating = signal(false);
    readonly statusLabel = statusLabel;
    readonly findingIcon = findingIcon;
    readonly findingTint = findingTint;

    readonly creating = signal(false);
    readonly newName = this.fb.control('', { nonNullable: true, validators: [Validators.required] });

    /** The right dock (properties / assist) is collapsible to a rail — the canvas takes the space back. */
    readonly inspectorOpen = signal(true);
    /** The left dock (the step palette) is collapsible to a rail, independently of the right one. */
    readonly paletteOpen = signal(true);
    /** Which surface the right dock shows: the selection's properties, or the AI authoring surfaces. */
    readonly rightTab = signal<'properties' | 'assist'>('properties');

    // ── definition drawer (definition-surface P1 — collector path only for now) ────────────────────
    /** The node open for DEFINITION in the right-dock drawer (null = drawer closed, inspector shows). */
    readonly definitionNode = signal<AuthoredNode | null>(null);
    /**
     * Bumped to recreate the drawer's content pane. Discard = recreate-from-model rather than an
     * in-place reset, so the shared collector surface's mode/seed state is trivially correct.
     */
    readonly definitionEpoch = signal(0);
    /** Unapplied edits inside the drawer — reported by the pane, drives the badge + close guards. */
    readonly definitionDirty = signal(false);
    @ViewChild(PipelineCollectionDefinitionComponent) private definitionPane?: PipelineCollectionDefinitionComponent;
    @ViewChild(PipelineParseDefinitionComponent) private parseDefinitionPane?: PipelineParseDefinitionComponent;
    @ViewChild(PipelineLoadDefinitionComponent) private loadDefinitionPane?: PipelineLoadDefinitionComponent;
    @ViewChild(PipelineConfigDefinitionComponent) private configDefinitionPane?: PipelineConfigDefinitionComponent;
    /** The properties dock's resize handle — S4 asks it for room when a TABBED pane opens. */
    @ViewChild('inspectorSplit') private inspectorSplitRef?: InspectoSplitDirective;

    /**
     * Whether the drawer's node is a per-format parse node — which pane the template mounts. A method on
     * the node TYPE, never an enumeration in the template (S2 widened the drawer to every kind, so the
     * template now needs the discriminator the routing rule already uses).
     */
    isParseNode(node: AuthoredNode): boolean {
        return isParseNodeType(node.type);
    }

    /** Served specs for the drawer's node type (`undefined` until the catalog resolves — the pane falls back). */
    definitionAttributes(): AttributeSpec[] | undefined {
        const n = this.definitionNode();
        return n ? this.typeAttributes().get(n.type) : undefined;
    }
    /** Hover preview: the node under the cursor + its viewport position (null when not hovering). */
    readonly hoverTip = signal<{
        name: string;
        type: string;
        category: string;
        config: { k: string; v: string }[];
        x: number;
        y: number;
    } | null>(null);
    /** Whether a node or edge is selected — gates the toolbar Delete action (selection-scoped). */
    readonly hasSelection = computed(() => !!this.selectedNode() || !!this.selectedEdgeId());

    toggleInspector(): void {
        this.inspectorOpen.update((o) => !o);
    }

    togglePalette(): void {
        this.paletteOpen.update((o) => !o);
    }

    /** Reveal the right dock on the given tab (an already-showing tab collapses the dock again). */
    showRightTab(tab: 'properties' | 'assist'): void {
        if (this.inspectorOpen() && this.rightTab() === tab) {
            this.inspectorOpen.set(false);
            return;
        }
        this.rightTab.set(tab);
        this.inspectorOpen.set(true);
    }

    /** The selected flow's editable model mapped to G6 data — fed to the host only on a flow switch. */
    readonly g6Data = computed<G6GraphData | null>(() => {
        const m = this.model();
        return m
            ? authoredToG6(m, this.typeCat(), (n) => this.statusOf(n), this.iconMap(), this.lastRunCounts())
            : null;
    });

    // ── Recipe view (ELT amendment UI plan §1, S1) ──────────────────────────────────────────────────
    /** The device's Recipe/Canvas preference — a UI preference, not config (mirrors the lens/space
     *  localStorage pattern). Recipe is the default; a graph the recipe cannot express forces Canvas
     *  regardless (see {@link effectiveMode}), so a stale 'recipe' preference never traps a user on a
     *  blank view. */
    private static readonly VIEW_MODE_KEY = 'inspecto.pipelines.viewMode';
    readonly viewMode = signal<'recipe' | 'canvas'>(
        (localStorage.getItem(PipelineEditorComponent.VIEW_MODE_KEY) as 'recipe' | 'canvas' | null) ?? 'recipe',
    );

    setViewMode(mode: 'recipe' | 'canvas'): void {
        this.viewMode.set(mode);
        localStorage.setItem(PipelineEditorComponent.VIEW_MODE_KEY, mode);
    }

    /** `null` when the open graph is not recipe-expressible (§1 — no dual model, no migration risk). */
    readonly stepChain = computed(() => {
        const m = this.model();
        return m ? detectStepChain(m) : null;
    });

    /** The chain flattened to indented rows for the step-cards view. */
    readonly stepRows = computed(() => {
        const chain = this.stepChain();
        return chain ? flattenStepChain(chain) : [];
    });

    /** The mode actually shown: the user's preference, unless the open graph forces Canvas. */
    readonly effectiveMode = computed<'recipe' | 'canvas'>(() => (this.stepChain() ? this.viewMode() : 'canvas'));

    /** Whether the preference is Recipe but the open graph forced a Canvas fallback — drives the alert. */
    readonly forcedToCanvas = computed(() => this.viewMode() === 'recipe' && !this.stepChain() && !!this.model());

    /**
     * Steps with no edge at either end. The overwhelmingly common reason the Recipe view gives up is
     * NOT a branch — it is a Step added from the palette, which lands unconnected. Blaming "a branch
     * this view can't represent" sent a builder hunting for a branch that was never there
     * (BUILDER-1c, found by driving the real UI 2026-08-17).
     */
    readonly danglingStepNames = computed(() => {
        const m = this.model();
        if (!m) return [];
        const wired = new Set<string>();
        for (const e of m.edges) {
            wired.add(e.from);
            wired.add(e.to);
        }
        return m.nodes.filter((n) => !wired.has(n.id)).map((n) => n.name || n.id);
    });

    /** The served recipe-verb palette (S4), `null` until it loads / on an old server. */
    private readonly servedVerbs = signal<{ type: string; label: string }[] | null>(null);

    /** What the Add-Step menu offers: the served step-types, else the client verb map (dual-read). */
    readonly recipeVerbs = computed<readonly { type: string; label: string }[]>(
        () => this.servedVerbs() ?? RECIPE_VERBS,
    );

    // ── Recipe editing (S2) — pure reducers over the same model; Save is the unchanged PUT ─────────

    /** Insert a new Step of `type` after `afterId` (null = new entry), then open its config dialog. */
    onRecipeInsert(e: { type: string; afterId: string | null }): void {
        if (!this.canAuthor()) return; // defense in depth, not just the hidden affordance
        const m = this.model();
        if (!m) return;
        // A route Step splices differently (S3): its downstream edge becomes its first branch, so it
        // needs a downstream to route to — never insertable at the tail or as the entry.
        if (e.type === 'transform.route') {
            if (e.afterId === null) {
                this.toast.warning('A Route Step needs something to route to — add it after a Step, not at the start.');
                return;
            }
            const node: AuthoredNode = {
                id: uniqueNodeId(m, e.type),
                type: e.type,
                config: { mode: 'case', branches: [{ key: 'branch_1' }] },
            };
            const next = insertRouteAfter(m, node, e.afterId);
            if (!next) {
                this.toast.warning(
                    'Cannot insert a Route here — it needs exactly one downstream Step. Use the Canvas.',
                );
                return;
            }
            this.model.set(next);
            this.dirty.set(true);
            return;
        }
        const node: AuthoredNode = { id: uniqueNodeId(m, e.type), type: e.type };
        const next = insertStepAfter(m, node, e.afterId);
        if (!next) {
            this.toast.warning('Cannot insert here — this Step is wired beyond the linear chain. Use the Canvas.');
            return;
        }
        this.model.set(next);
        this.dirty.set(true);
        this.openNodeConfig(node);
    }

    /** Remove a trunk Step, reconnecting its neighbours. */
    onRecipeRemove(id: string): void {
        if (!this.canAuthor()) return;
        const m = this.model();
        if (!m) return;
        const next = removeStepFromChain(m, id);
        if (!next) {
            this.toast.warning('Cannot remove here — this Step is wired beyond the linear chain. Use the Canvas.');
            return;
        }
        this.model.set(next);
        this.clearSelection();
        this.dirty.set(true);
    }

    /** Swap a trunk Step with its neighbour. A refused move (chain end / non-linear) is a silent no-op. */
    onRecipeMove(e: { id: string; dir: 'up' | 'down' }): void {
        if (!this.canAuthor()) return;
        const m = this.model();
        if (!m) return;
        const next = moveStepInChain(m, e.id, e.dir);
        if (!next) return;
        this.model.set(next);
        this.dirty.set(true);
    }

    // ── Route branch editing (S3, §2.6) — the same pure-reducer pattern ────────────────────────────

    /** Add a named branch (+ its unconfigured sink) to a route Step. */
    onRecipeAddBranch(e: { routeId: string; key: string }): void {
        if (!this.canAuthor()) return;
        const m = this.model();
        if (!m) return;
        const next = addRouteBranch(m, e.routeId, e.key);
        if (!next) {
            this.toast.warning(`Cannot add branch '${e.key}' — a branch with that key already exists.`);
            return;
        }
        this.model.set(next);
        this.dirty.set(true);
    }

    /** Remove a branch and its downstream Steps. */
    onRecipeRemoveBranch(e: { routeId: string; key: string }): void {
        if (!this.canAuthor()) return;
        const m = this.model();
        if (!m) return;
        const next = removeRouteBranch(m, e.routeId, e.key);
        if (!next) {
            this.toast.warning('Cannot remove this branch — its Steps are wired beyond the branch. Use the Canvas.');
            return;
        }
        this.model.set(next);
        this.clearSelection();
        this.dirty.set(true);
    }

    /** Set/clear a branch's `when` predicate. */
    onRecipeBranchWhere(e: { routeId: string; key: string; where: string }): void {
        if (!this.canAuthor()) return;
        const m = this.model();
        if (!m) return;
        const next = setRouteBranchWhere(m, e.routeId, e.key, e.where);
        if (!next) return;
        this.model.set(next);
        this.dirty.set(true);
    }

    /** Mark/clear the route's default branch (zero-or-one — the engine's real contract). */
    onRecipeSetDefault(e: { routeId: string; key: string | null }): void {
        if (!this.canAuthor()) return;
        const m = this.model();
        if (!m) return;
        const next = setRouteDefault(m, e.routeId, e.key);
        if (!next) return;
        this.model.set(next);
        this.dirty.set(true);
    }

    /** Flip the route's `case|clone` mode on its own config. */
    onRecipeModeChange(e: { routeId: string; mode: 'case' | 'clone' }): void {
        if (!this.canAuthor()) return;
        const m = this.model();
        const route = m?.nodes.find((n) => n.id === e.routeId);
        if (!m || !route) return;
        this.model.set(applyNodePatchInModel(m, { ...route, config: { ...route.config, mode: e.mode } }));
        this.dirty.set(true);
    }

    /** The selected node's last-run output (T17), or `null` when that run recorded nothing for it. */
    selectedNodeLastRun(): { rowCount: number; runTs: string } | null {
        return this.nodeLastRun(this.selectedNode());
    }

    /** A given node's last-run output — the drawer strip asks for ITS node, which can outlive the selection. */
    nodeLastRun(node: AuthoredNode | null): { rowCount: number; runTs: string } | null {
        const batch = this.lastRunBatch();
        if (!node || !batch) return null;
        const rowCount = nodeLastRunTotal(node.id, this.lastRunCounts());
        return rowCount == null ? null : { rowCount, runTs: batch.runTs };
    }

    /** A node's authoring status — the canvas outline cue and the inspector chip. */
    statusOf(n: AuthoredNode): NodeStatus {
        return computeNodeStatus(
            n,
            this.typeCategory(n.type),
            this.validRefs(),
            this.testedStatus(),
            this.refsLoaded(),
        );
    }

    /** Bound reference to {@link statusOf} for the step-cards `@Input` (a plain method reference would lose `this`). */
    readonly boundStatusOf = (n: AuthoredNode): NodeStatus => this.statusOf(n);

    // ── guided mode (definition-surface P6-d): the wizard's stage rail as toolbar chips ──

    /**
     * The guided checklist over the CURRENT graph. Deliberately live rather than a snapshot of
     * {@link findings}: the dock shows the last Validate the operator asked for, while a chip is a
     * status light and would be lying the moment a node changed. Same pure validator either way, so
     * the two can never disagree about a given graph.
     */
    readonly checklist = computed<StageChip[]>(() => {
        const m = this.model();
        const findings = m ? validatePipeline(m, this.typeCat(), this.validRefs(), this.testedStatus()) : [];
        return stageChecklist(m, this.typeCat(), (n) => this.statusOf(n), findings);
    });

    /** Draft → Ready → Live, shown beside the chips. */
    readonly lifecycle = computed(() => pipelineLifecycle(this.checklist(), this.isActive()));

    /**
     * A chip click reveals that stage's Step, then opens the surface that owns it (drawer or dialog).
     *
     * ⚠ Selecting FIRST is not decoration: {@link openNodeConfig} is gated on {@link canAuthor}, so in
     * View mode (or the Business lens) it is a no-op — and a chip that does literally nothing is the
     * "affordance that can only fail" this editor avoids elsewhere. Selecting always works, so the
     * chip reveals the Step in the inspector either way and only the editing half is withheld.
     */
    openStage(chip: StageChip): void {
        const node = this.model()?.nodes.find((n) => n.id === chip.nodeId);
        if (!node) return;
        this.onNodeSelected(node.id);
        this.openNodeConfig(node);
    }

    /** Push every node's current status to the canvas (after the registry refs or test outcomes change). */
    private refreshAllStatuses(): void {
        for (const n of this.model()?.nodes ?? []) this.canvas?.setNodeStatus(n.id, this.statusOf(n));
    }

    /** The palette category for a node type (drives the inspector's category label + colour). */
    typeCategory(type: string): string {
        return this.typeCat().get(type) ?? '';
    }

    /** Relationships the selected edge may carry — the inspector's picker options. */
    candidateRels(): string[] {
        const id = this.selectedEdgeId();
        return id ? candidateRelsFor(this.model(), id, this.typeEmits()) : [];
    }

    /** The relationship the selected edge currently carries. */
    selectedEdgeRel(): string | null {
        const id = this.selectedEdgeId();
        return id ? (decodeEdgeId(id)?.rel ?? null) : null;
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        // W5: the editor lists REGISTERED pipelines (the canonical *_pipeline.toon), not authored flows.
        this.api.list().subscribe({
            next: (fs) => {
                this.flows.set(fs);
                this.loading.set(false);
                // Deliberately opens nothing. Listing is cheap; lifting a graph is not, and auto-opening
                // one arbitrary pipeline both cost a fetch nobody asked for and made the tab strip lie
                // about what the user had chosen. The Open dialog is the only thing that opens a tab.
                // Re-listing (after a save/import) must not disturb tabs already open.
                this.openIds.update((ids) => ids.filter((id) => fs.some((f) => f.name === id)));
            },
            error: () => {
                this.flows.set([]);
                this.loading.set(false);
            },
        });
        this.api.nodeTypes().subscribe({
            next: (ts) => {
                // The palette offers only AUTHORABLE types — a step that can never be saved to this
                // pipeline format (adapter, the unspecced transform.*, sink.materialized/view, alert,
                // event) is not offered at all, and neither is a retired-but-still-lowerable one
                // (transform.dedup.marker since P5-a). ⚠ Filtering on `lowerable` would put those two
                // groups in conflict: the marker node must keep lowering so an editor opened before
                // the fold can still save. An older server omitting the flag keeps the old behaviour.
                // The full catalog still feeds the maps below, so a grandfathered graph carrying such
                // a node keeps rendering + flagging (unsupported()).
                this.paletteGroups.set(groupByCategory(ts.filter((t) => t.authorable ?? t.lowerable)));
                this.typeCat.set(typeCategoryMap(ts));
                this.typeLabel.set(typeLabelMap(ts));
                this.typeEmits.set(new Map(ts.map((t) => [t.type, t.emits])));
                this.typeLowerable.set(new Map(ts.map((t) => [t.type, t.lowerable])));
                // Only types the server actually specced — a type whose `attributes` the payload omits
                // entirely (an older server) must stay absent so the dialog uses its fallback table.
                this.typeAttributes.set(
                    new Map(ts.filter((t) => t.attributes !== undefined).map((t) => [t.type, t.attributes!])),
                );
            },
            error: () => this.paletteGroups.set([]),
        });
        // S4 dual-read: the served recipe-verb palette, falling back to the client verb map
        // (RECIPE_VERBS) on an old server — mirroring how typeAttributes tolerates one.
        this.api.stepTypes().subscribe({
            next: (sts) => {
                const verbs = sts.filter((s) => s.lowerable).map((s) => ({ type: s.type, label: s.label }));
                // an empty palette is never what a real server means — treat it as "not served"
                this.servedVerbs.set(verbs.length ? verbs : null);
            },
            error: () => this.servedVerbs.set(null),
        });
        this.loadComponentRefs();
        this.iconMapApi.get().subscribe({
            next: (m) => this.iconMap.set(m),
            error: () => this.iconMap.set({}),
        });
    }

    /** Load existing registry refs (grammar/transform/sink) so the canvas can flag dangling `use` bindings. */
    private loadComponentRefs(): void {
        const refs = new Set<string>();
        const kinds = ['grammar', 'transform', 'sink'] as const;
        let pending = kinds.length;
        const done = () => {
            if (--pending > 0) return;
            this.validRefs.set(refs);
            this.refsLoaded.set(true);
            this.refreshAllStatuses();
        };
        for (const k of kinds) {
            this.components.list(k).subscribe({
                next: (list) => {
                    for (const c of list) refs.add(c.ref);
                    // The same fetch feeds the Parse drawer's "Start from a template" picker — the
                    // pane is pure, so the host supplies the list rather than the pane fetching it.
                    if (k === 'grammar') this.grammarTemplates.set(list);
                    done();
                },
                error: () => done(),
            });
        }
    }

    /** Whether this session may mutate: an authoring lens AND not the read-only View. */
    canAuthor(): boolean {
        return this.lens.canAuthorWorkbench() && !this.readOnly;
    }

    /** Tab strip rows — open ids resolved to their list entry, with per-tab dirty state. */
    readonly tabs = computed(() =>
        this.openIds().map((id) => {
            const f = this.flows().find((x) => x.name === id);
            return { id, label: f?.displayName || id, template: f?.template === true, dirty: this.dirtyIds().has(id) };
        }),
    );

    /** Any tab with unsaved edits — drives the leave/close warnings. */
    readonly anyDirty = computed(() => this.dirtyIds().size > 0);

    /** Search-and-choose the open set. Returns the FULL desired set, so unticking closes a tab. */
    openPipelines(): void {
        this.dialog
            .open(PipelineOpenDialog, {
                width: '30rem',
                data: { pipelines: this.flows(), open: this.openIds() },
            })
            .afterClosed()
            .subscribe((next?: string[]) => {
                if (!next) return;
                const keep = new Set(next);
                // Closing a dirty tab from here would discard edits invisibly — keep those open and say so.
                const refused = this.openIds().filter((id) => !keep.has(id) && this.dirtyIds().has(id));
                for (const id of refused) keep.add(id);
                if (refused.length) {
                    this.toast.warning(
                        `Kept ${refused.length} tab(s) with unsaved changes open — save or close them individually.`,
                    );
                }
                for (const id of this.openIds()) if (!keep.has(id)) this.forgetTab(id);
                this.openIds.set(
                    next.filter((id) => keep.has(id)).concat([...keep].filter((id) => !next.includes(id))),
                );

                const active = this.selectedId();
                if (!active || !keep.has(active)) {
                    const first = this.openIds()[0];
                    if (first) this.activateTab(first);
                    else this.clearActive();
                }
            });
    }

    /**
     * Make an already-open tab the active one, parking the outgoing tab's graph and dirty flag.
     * Named `activateTab`, not `activate` — {@link activate} already means "arm this pipeline to run".
     */
    async activateTab(id: string): Promise<void> {
        if (this.selectedId() === id) return;
        // The definition drawer's edits live outside the parked graph — switching away loses them,
        // so it gets the same courtesy the model's dirty flag gets.
        if (this.definitionDirty()) {
            const ok = await this.confirm.confirmDestructive(
                'The open definition has edits that have not been applied. Switching tabs discards them.',
                { title: 'Discard unapplied edits?', confirmText: 'Discard' },
            );
            if (!ok) return;
        }
        this.closeDefinition();
        this.parkCurrent();
        this.clearSelection();
        const cached = this.cachedModels.get(id);
        if (cached) {
            this.pendingSelect = id; // an in-flight load for another tab must not land over this one
            this.model.set(cached);
            this.selectedId.set(id);
            this.dirty.set(this.dirtyIds().has(id));
            this.loadLastRun(id);
            return;
        }
        this.select(id);
    }

    /** Close one tab. A dirty tab asks first — this is the only path that can discard edits. */
    async closeTab(id: string): Promise<void> {
        if (this.dirtyIds().has(id) || (id === this.selectedId() && this.definitionDirty())) {
            const ok = await this.confirm.confirmDestructive(
                `'${id}' has edits that have not been saved. Closing the tab discards them.`,
                { title: 'Discard unsaved changes?', confirmText: 'Discard' },
            );
            if (!ok) return;
        }
        this.forgetTab(id);
        this.openIds.update((ids) => ids.filter((x) => x !== id));
        if (this.selectedId() !== id) return;
        this.clearActive();
        const next = this.openIds()[0];
        if (next) this.activateTab(next);
    }

    private forgetTab(id: string): void {
        this.cachedModels.delete(id);
        // The thread is session state about a tab that no longer exists — reopening starts clean, the
        // same way the graph is refetched rather than restored.
        this.sampleThreads.delete(id);
        this.dirtyIds.update((s) => {
            const next = new Set(s);
            next.delete(id);
            return next;
        });
    }

    /** No active tab — the empty canvas state. */
    private clearActive(): void {
        this.pendingSelect = null;
        this.closeDefinition();
        this.clearSelection();
        this.selectedId.set(null);
        this.model.set(null);
        this.dirty.set(false);
        this.lastRunBatch.set(null);
    }

    /**
     * Park the active tab's graph so switching away from it cannot lose edits. Every path that changes
     * the active tab goes through here — {@link select} included, which is how the first version leaked.
     */
    private parkCurrent(): void {
        const current = this.selectedId();
        if (!current) return;
        const m = this.model();
        if (m) this.cachedModels.set(current, m);
    }

    /** Fetch and open a pipeline's graph, adding a tab for it if it is not already open. */
    select(id: string): void {
        if (this.selectedId() !== id) {
            this.parkCurrent();
            this.closeDefinition(); // the drawer's node belongs to the outgoing graph
        }
        this.clearSelection();
        if (!this.openIds().includes(id)) this.openIds.update((ids) => [...ids, id]);
        // Every path that opens a tab lands here, so this is the one place a thread is born.
        if (!this.sampleThreads.has(id)) this.sampleThreads.set(id, new DefinitionStateService());
        // The tab this load is FOR. ⚠ `activateTab` returns immediately when `selectedId()` already
        // matches, and `select()` only moves `selectedId` when its response lands — so clicking an
        // uncached tab C and then going back to A left A legitimately editable while C's fetch was still
        // in flight. C then landed and did `model.set(flow)` unconditionally: A's edits since the switch
        // were destroyed, `selectedId` jumped to C, and `dirty.set(false)` erased the unsaved marker.
        // They were not recoverable from `cachedModels` either — `parkCurrent()` had cached the OLDER A.
        this.pendingSelect = id;
        // W5: the editor edits the CANONICAL *_pipeline.toon — lift it to the editable graph.
        this.api.pipelineGraphRaw(id).subscribe({
            next: (flow) => {
                if (this.pendingSelect !== id) return; // superseded — the operator moved on
                this.model.set(flow);
                this.selectedId.set(id); // drives the host rebuild (graphKey)
                this.dirty.set(false);
            },
            error: (err) => {
                if (this.pendingSelect === id) this.toast.error(apiErrorMessage(err, 'Could not load the pipeline'));
            },
        });
        this.loadLastRun(id);
    }

    /**
     * T17 live last-run overlay: fetch the flow's most recent real run from the durable provenance store
     * (`/provenance/batches` + `/provenance`) and paint it onto the canvas edges + inspector. Degrades
     * silently to "no overlay" both when the flow has no recorded run yet (empty batch list) and when no
     * provenance backend is configured (404, `-Dprovenance.backend` unset) — this is a read-only enhancement,
     * never worth blocking or erroring the editor over.
     */
    private loadLastRun(id: string): void {
        this.lastRunBatch.set(null);
        this.lastRunCounts.set(new Map());
        this.api.provenanceBatches(id).subscribe({
            next: (batches) => {
                const latest = batches[0] ?? null;
                this.lastRunBatch.set(latest);
                if (!latest) return;
                this.api.provenance(id, latest.batchId).subscribe({
                    next: (rows) => this.lastRunCounts.set(provenanceCounts(rows)),
                    error: () => this.lastRunCounts.set(new Map()),
                });
            },
            error: () => this.lastRunBatch.set(null),
        });
    }

    // ── create / save / delete ──

    startNew(): void {
        this.creating.set(true);
        this.newName.reset('');
    }

    createPipeline(): void {
        if (this.newName.invalid) {
            this.newName.markAsTouched();
            return;
        }
        const name = this.newName.value.trim();
        // ⚠ The typed value is the DISPLAY name; the pipeline is registered under the narrowed id the
        // scaffold stamps ("my-pipe" → "my_pipe"). Every route below the write is keyed by that id, so
        // selecting by the typed name opens a tab on a pipeline the server has never heard of.
        const id = pipelineId(name);
        // W5: a new pipeline IS a canonical draft — write the space-convention scaffold (inactive,
        // with the parser-required dirs) and register it, then load its lifted graph.
        this.configApi.write('pipeline', pipelineScaffold(name, { space: this.spaces.currentSpaceId() })).subscribe({
            next: (written) => {
                this.configApi.registerPipeline(written.path).subscribe({
                    next: () => {
                        this.creating.set(false);
                        this.flows.update((fs) => [
                            ...fs,
                            {
                                name: id,
                                ...(name === id ? {} : { displayName: name }),
                                active: false,
                                nodeCount: 0,
                                edgeCount: 0,
                                produces: [],
                                consumes: [],
                            },
                        ]);
                        this.select(id);
                    },
                    error: () => {
                        // the file exists; the row appears after the next rescan
                        this.creating.set(false);
                        this.select(id);
                    },
                });
            },
            error: (err) => {
                if ((err as { status?: number })?.status === 409) this.newName.setErrors({ duplicate: true });
                else this.onWriteError(err, 'Could not create the pipeline');
            },
        });
    }

    /**
     * The deterministic check's arguments. The tool takes the graph under a `flow` key — passing
     * `{name, nodes, edges}` flat, as this pane did before A5.3, made the real backend answer
     * "flow is required and must be an object" on every call; only the offline mock accepted it.
     */
    aiPipelineArgs(): Record<string, unknown> {
        const m = this.model();
        // `active` travels too: the tool echoes the parsed graph, which always carries it, so omitting it
        // here makes the diff claim the check wants to deactivate a live pipeline. (Applying never could —
        // applyPipelineDraft keeps the current lifecycle — but a diff that lies is a diff nobody reads.)
        return m ? { flow: { name: m.name, active: m.active, nodes: m.nodes, edges: m.edges } } : {};
    }

    /**
     * The NL instance's arguments — deliberately EMPTY (AGT-6a A5.3).
     *
     * Pane args are merged last and win, so passing the open graph here would overwrite the topology the
     * sentence just produced and the draft would silently equal what is already on screen. The pipeline's
     * identity is preserved where it belongs instead: {@link applyPipelineDraft} keeps the open `name` and
     * `active` no matter what the model called its graph.
     */
    aiPromptArgs(): Record<string, unknown> {
        return {};
    }

    /**
     * AGT-6a A2: the working model as the diff baseline for the inline surface. `pipeline_author` echoes
     * back a parsed `flow`, so the comparison is draft-vs-current on the same shape.
     */
    aiCurrentPipeline(): Record<string, unknown> | null {
        const m = this.model();
        return m ? ({ ...m } as unknown as Record<string, unknown>) : null;
    }

    /**
     * Adopt a checked topology into the working model (AGT-6a A2). It stops here — `dirty` is set and
     * the operator presses the existing Save, so the write still goes through `replaceAuthored` with the
     * human as the audited actor (decision D2). Nothing is persisted by applying.
     */
    applyPipelineDraft(draft: AiDraft): void {
        if (!this.canAuthor()) return; // defense in depth, not just the button
        const flow = draft.config as unknown as AuthoredPipeline;
        if (!flow?.nodes || !flow?.edges) return;
        const current = this.model();
        // Keep the open pipeline's identity and active state — the tool echoes the graph, not the
        // lifecycle, and adopting a draft must never silently activate or rename a live pipeline.
        this.model.set({ ...flow, name: current?.name ?? flow.name, active: current?.active ?? false });
        this.dirty.set(true);
        this.selectedNode.set(null);
        this.selectedEdgeId.set(null);
    }

    async save(): Promise<void> {
        const m = this.model();
        const id = this.selectedId();
        if (!m || !id) return;
        // ⚠ The definition drawer's edits live OUTSIDE the graph until Apply, and every other transition
        // that could lose them stops and asks — switching tabs (activateTab), closing one (closeTab),
        // moving the drawer to another node. Save was the exception, and the worst place for it: it
        // persisted the model without the drawer's work and then cleared `dirty`, so the surface
        // asserted everything was saved while the edits sat unapplied in the pane.
        //
        // Proceeding does NOT clear `definitionDirty` — the drawer keeps its edits and its own guard, so
        // the operator can still Apply and save again.
        if (this.definitionDirty()) {
            const ok = await this.confirm.confirmDestructive(
                'The open definition has edits that have not been applied. Saving now writes the pipeline without them.',
                { title: 'Save without the unapplied edits?', confirmText: 'Save anyway' },
            );
            if (!ok) return;
        }
        this.saving.set(true);
        this.api.savePipelineGraph(id, m).subscribe({
            next: () => {
                this.saving.set(false);
                this.dirty.set(false);
                this.toast.success(`Saved pipeline '${id}'`);
            },
            error: (err) => {
                this.saving.set(false);
                if (!this.showRefusals(err)) this.onWriteError(err, 'Save failed');
            },
        });
    }

    /**
     * Surface named lower-refusals (UNSUPPORTED_NODE / MULTI_PARSER / NO_*) in the Validation dock, or
     * false if the error carried none. Every refusal is listed and stays put — a first-only transient
     * toast turned an n-problem graph into n save→fix→save cycles.
     */
    private showRefusals(err: unknown): boolean {
        const refusals = (err as { error?: { error?: { details?: { refusals?: PipelineRefusal[] } } } })?.error?.error
            ?.details?.refusals;
        if (!refusals?.length) return false;
        this.findings.set(
            refusals.map((r) => ({
                severity: 'error' as const,
                nodeId: r.nodeId,
                message: r.message,
            })),
        );
        this.bottomTab.set('validation');
        this.toast.error(
            refusals.length === 1
                ? 'Cannot save: 1 problem — see Validation.'
                : `Cannot save: ${refusals.length} problems — see Validation.`,
        );
        return true;
    }

    /**
     * Copy the selected pipeline into a non-runnable template. The server does the neutralising (dirs,
     * stream, collector id, schema copy) — the UI only names it, then selects the result so the operator
     * lands in the copy rather than having to hunt for it.
     */
    saveAsTemplate(): void {
        const id = this.selectedId();
        if (!id) return;
        this.dialog
            .open(PipelineTemplateDialog, {
                width: '32rem',
                data: { source: id, existingNames: this.flows().map((f) => f.name) },
            })
            .afterClosed()
            .subscribe((res?: PipelineTemplateResultData) => {
                if (!res) return;
                this.api.saveAsTemplate(id, res.id, res.displayName).subscribe({
                    next: (written) => {
                        this.flows.update((fs) => [
                            ...fs,
                            {
                                name: res.id,
                                active: false,
                                template: true,
                                displayName: res.displayName,
                                nodeCount: 0,
                                edgeCount: 0,
                                produces: [],
                                consumes: [],
                            },
                        ]);
                        this.select(res.id); // reloads the real graph, correcting the optimistic counts
                        this.toast.success(`Created template '${res.id}'`);
                        // e.g. the schema could not be copied — the operator must repoint it before editing.
                        written.notes?.forEach((n) => this.toast.info(n));
                    },
                    error: (err) => this.onWriteError(err, 'Could not create the template'),
                });
            });
    }

    /**
     * Export the selected pipeline's **Pipeline Document** (ELT amendment §5.1) as a Markdown file, and
     * report the config fingerprint it was stamped with — that hash is what ties a business sign-off to
     * the configuration that produced it.
     *
     * A read, not an authoring action: it is deliberately NOT gated on `canAuthorWorkbench()` (a
     * Business-lens reviewer is exactly who needs it), and a failure goes through a plain toast rather
     * than {@link onWriteError}, which would wrongly latch the editor into its writes-disabled state.
     */
    exportDocument(): void {
        const id = this.selectedId();
        if (!id || this.exportingDocument()) return;
        this.exportingDocument.set(true);
        this.api.document(id).subscribe({
            next: (res) => {
                this.exportingDocument.set(false);
                if (!res.body) {
                    this.toast.error('The server returned an empty document');
                    return;
                }
                this.downloadBlob(res.body, `${id}.md`);
                const fingerprint = this.api.documentFingerprint(res);
                this.toast.success(
                    fingerprint
                        ? `Exported '${id}.md' — config fingerprint ${fingerprint.slice(0, 12)}`
                        : `Exported '${id}.md'`,
                );
            },
            error: (err) => {
                this.exportingDocument.set(false);
                this.toast.error(apiErrorMessage(err, 'Could not export the document'));
            },
        });
    }

    /**
     * Export this pipeline's whole configuration as a portable `inspecto-stream-config` bundle — the
     * pipeline body plus its schema, any per-segment plugin schemas and the `<id>_enrich` companion.
     * The onboarding shell's toolbar export, re-homed in P6-e: ⛔ deleting the shell without it would
     * have left the format **import-only**, since the create dialog still reads a bundle and nothing
     * would produce one.
     *
     * <p>⛔ Not the Metadata Bundle the transfer menu offers, and the two must not be merged: that one
     * carries Studio registry artifacts addressed by **id**, while a pipeline and its satellites live
     * in the **config** namespace addressed by **path** — and they collide on the word *schema*.
     *
     * <p>⚠ What travels is the **server-held** config, read back here rather than lowered from the
     * open graph. So a tab with unapplied edits refuses: an export that silently carries the last
     * saved state while showing something else is worse than no export. Read-only, so every lens gets
     * it — a Business-lens operator handing a config to support is the point.
     */
    exportConfig(): void {
        const id = this.selectedId();
        if (!id || this.exportingConfig()) return;
        if (this.dirty()) {
            this.toast.warning('Apply this pipeline before exporting — an export carries the saved configuration.');
            return;
        }
        this.exportingConfig.set(true);
        this.configApi
            .read('pipeline', id)
            .pipe(
                // Stream-vs-reference off the CONFIG's own `produces` — the same read the shell used.
                // ⛔ Not PipelineSummary.produces, which is the list of stores the pipeline produces.
                switchMap((r) =>
                    this.transfer.buildExport(
                        id,
                        String(r.config['produces'] ?? '') === 'reference' ? 'reference' : 'stream',
                        r.config,
                    ),
                ),
            )
            .subscribe({
                next: ({ bundle, missing }) => {
                    this.exportingConfig.set(false);
                    this.transfer.download(bundle);
                    // A satellite that could not be read is named, not swallowed — the file downloaded,
                    // but it is incomplete and re-importing it would silently lose that piece.
                    if (missing.length)
                        this.toast.warning(`Exported without ${missing.join(', ')} — could not be read.`);
                    else this.toast.success(`Exported "${id}" configuration`);
                },
                error: (err) => {
                    this.exportingConfig.set(false);
                    this.toast.error(apiErrorMessage(err, 'Could not export the configuration.'));
                },
            });
    }

    /** Trigger a browser download for a fetched blob (object URL, revoked after the click). */
    private downloadBlob(blob: Blob, filename: string): void {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        a.click();
        URL.revokeObjectURL(url);
    }

    /**
     * Relabel the selected pipeline. Its identity is unchanged, so {@link selectedId} still addresses it
     * and only the list label is patched.
     */
    renamePipeline(): void {
        const id = this.selectedId();
        if (!id) return;
        this.dialog
            .open(PipelineRenameDialog, {
                width: '32rem',
                data: { id, displayName: this.selectedSummary()?.displayName ?? id },
            })
            .afterClosed()
            .subscribe((res?: PipelineRenameResultData) => {
                if (!res) return;
                this.api.label(id, res.name).subscribe({
                    next: () => {
                        this.flows.update((fs) => fs.map((f) => (f.name === id ? { ...f, displayName: res.name } : f)));
                        this.toast.success(`Renamed to '${res.name}'`);
                    },
                    error: (err) => this.onWriteError(err, 'Rename failed'),
                });
            });
    }

    /**
     * The full identity migration (T3) — distinct from {@link renamePipeline}'s display-only relabel.
     * Moves the id itself, so every route addressing the pipeline by name must switch to `newId`
     * afterwards: update `selectedId`/the flow list entry, and reselect so the editor keeps pointing at
     * the (now-renamed) pipeline instead of 404ing on its old id.
     */
    changePipelineId(): void {
        const id = this.selectedId();
        if (!id) return;
        this.dialog
            .open(PipelineChangeIdDialog, {
                width: '32rem',
                data: {
                    id,
                    displayName: this.selectedSummary()?.displayName ?? id,
                    existingNames: this.flows().map((f) => f.name),
                },
            })
            .afterClosed()
            .subscribe((res?: PipelineChangeIdResultData) => {
                if (!res) return;
                // The server keeps the display name unless told otherwise. For a pipeline that was never
                // relabelled the display name IS the old id, so keeping it would leave every list/tab
                // captioned with an identity that no longer exists — follow the id instead. An explicit
                // label survives the migration untouched.
                const hasOwnLabel = !!this.selectedSummary()?.displayName;
                this.api.rename(id, res.newId, hasOwnLabel ? {} : { newName: res.newId }).subscribe({
                    next: (written) => {
                        this.flows.update((fs) =>
                            fs.map((f) =>
                                f.name === id
                                    ? {
                                          ...f,
                                          name: written.id,
                                          // mirror configSummary: displayName only when it differs from the id
                                          displayName: written.name === written.id ? undefined : written.name,
                                      }
                                    : f,
                            ),
                        );
                        this.openIds.update((ids) => ids.map((x) => (x === id ? written.id : x)));
                        this.select(written.id); // reload the graph under the new id — the old one is gone
                        this.toast.success(`Pipeline id changed to '${written.id}'`);
                    },
                    error: (err) => this.onWriteError(err, 'Change id failed'),
                });
            });
    }

    /**
     * D8 (pipeline-graph backlog): edit the pipeline-level `produces`/`reference` block. Opaque to
     * {@link PipelineGraph} — this dialog has its own read/write pair rather than riding through
     * `PUT .../graph`.
     */
    pipelineSettings(): void {
        const id = this.selectedId();
        if (!id) return;
        this.api.settings(id).subscribe({
            next: (settings) => {
                this.dialog
                    .open(PipelineSettingsDialog, { width: '32rem', data: { id, settings } })
                    .afterClosed()
                    .subscribe((res?: PipelineSettings) => {
                        if (!res) return;
                        this.api.saveSettings(id, res).subscribe({
                            next: () => this.toast.success('Pipeline settings saved'),
                            error: (err) => this.onWriteError(err, 'Save settings failed'),
                        });
                    });
            },
            error: (err) => this.toast.error(apiErrorMessage(err, 'Could not load pipeline settings')),
        });
    }

    /**
     * Delete the authored pipeline, after telling the operator what it would break — the wizard's
     * "Discard draft" re-homed here in P6-e, since this editor is now the only surface that deletes
     * a guided pipeline. Three parts the bare `remove()` did not have:
     *
     * <ul>
     *   <li>the <b>impact read</b> — advisory only, so a failed read still lets the delete proceed
     *       and be refused by the server, which re-checks on its own;</li>
     *   <li><b>force</b>, sent only when dependents were shown: the operator has seen the list and
     *       chosen anyway (it does NOT bypass the separate active-pipeline refusal);</li>
     *   <li>the <b>companion cascade</b>, so a deleted pipeline leaves no orphan `<id>_schema` /
     *       `<id>_enrich` behind. Best-effort by design: the pipeline is already gone, and a 404 just
     *       means that companion was never authored. ⚠ Per-SEGMENT schemas (`<id>_<segmentKey>`) are
     *       not swept — the wizard never swept them either, and enumerating them needs the parsed
     *       block the delete no longer has.</li>
     * </ul>
     */
    /**
     * The per-segment schemas (`<id>_<segmentKey>`) this pipeline OWNS, for the delete cascade.
     *
     * <p>⛔ The backlog's stated cause for leaving these behind — "enumerating them needs the parsed
     * block the delete no longer has" — was wrong (grounded 2026-08-17). A segment's schema path is
     * AUTHORED config, stored on the parse node as `parsing.<asn1|plugin>.segments` = `{key: path}`;
     * the editor holds that graph in memory, so nothing has to be re-parsed to enumerate them.
     *
     * <p>🔴 **Only names inside this pipeline's own `<id>_` namespace are swept.** A hand-authored path
     * pointing somewhere else may be shared with another pipeline, and deleting it would orphan that
     * one — the same convention boundary the parse pane already respects when it refuses to re-derive
     * over a foreign `schema_file`. Not sweeping a foreign schema leaves at worst an unreferenced file;
     * sweeping one breaks a pipeline nobody asked us to touch.
     */
    private ownedSegmentSchemas(id: string): string[] {
        const prefix = companionSchemaName(id, '');
        const names = new Set<string>();
        for (const node of this.model()?.nodes ?? []) {
            for (const path of Object.values(segmentPathsOf(node.config))) {
                const name = schemaNameFromPath(path);
                if (name && name.startsWith(prefix)) names.add(name);
            }
        }
        return [...names];
    }

    async deletePipeline(): Promise<void> {
        const id = this.selectedId();
        if (!id) return;
        const impact = await firstValueFrom(this.configApi.impact(id).pipe(catchError(() => of(null))));
        const breaks = impact?.total ?? 0;
        // The delete has three separable parts, so it ASKS rather than assuming. ⚠ The companion
        // schema defaults ON (it is this pipeline's own config, useless once the pipeline is gone),
        // while the DATA defaults OFF — it is the only part that destroys something unrecoverable, and
        // an operator confirming quickly must not lose their output by default.
        const choice = await this.confirm.confirmDestructiveWith(
            breaks === 0
                ? `Permanently delete the authored pipeline '${id}'?`
                : `'${id}' is still referenced by ${breaks} config${breaks === 1 ? '' : 's'}: ` +
                      `${describeDependents(impact!)}. Deleting it leaves ` +
                      `${breaks === 1 ? 'that reference' : 'those references'} pointing at nothing. ` +
                      `Delete anyway? This cannot be undone.`,
            {
                title: 'Delete pipeline',
                confirmText: 'Delete',
                checkboxes: [
                    {
                        key: 'schema',
                        label: 'Also delete its schema and enrichment',
                        hint: `The companions this pipeline owns (${id}_schema, ${id}_enrich and any segment schemas).`,
                        checked: true,
                    },
                    {
                        key: 'data',
                        label: 'Also delete its data',
                        hint: 'Everything written under this pipeline’s database, backup, errors, quarantine, markers, status and log directories. The inbox is never touched, and a directory another pipeline shares is kept.',
                        checked: false,
                    },
                ],
            },
        );
        if (!choice.ok) return;
        const alsoSchema = choice.checked['schema'] === true;
        const alsoData = choice.checked['data'] === true;
        // W5: deleting a registered pipeline discards its canonical config (the server refuses an
        // active pipeline — deactivate first).
        const companion = (suffix: string): string => companionSchemaName(id, suffix);
        // Read the per-segment schemas off the graph BEFORE the delete clears it.
        const segmentSchemas = this.ownedSegmentSchemas(id).filter((n) => n !== companion('schema'));
        this.configApi
            .remove('pipeline', id, undefined, breaks > 0, alsoData)
            .pipe(
                switchMap((res) =>
                    // Companion cleanup is opt-in now. Each is still best-effort: a companion that was
                    // never created 404s, and that must not fail a delete that already succeeded.
                    (alsoSchema
                        ? forkJoin([
                              this.configApi.remove('schema', companion('schema')).pipe(catchError(() => of(null))),
                              this.configApi.remove('enrichment', companion('enrich')).pipe(catchError(() => of(null))),
                              ...segmentSchemas.map((name) =>
                                  this.configApi.remove('schema', name).pipe(catchError(() => of(null))),
                              ),
                          ])
                        : of([])
                    ).pipe(map(() => res)),
                ),
            )
            .subscribe({
                next: (res) => {
                    // ⚠ Report what the data delete KEPT. A directory another pipeline also declares is
                    // deliberately retained, and an operator who ticked "delete its data" would
                    // otherwise believe it all went — then find the files still there and distrust the
                    // whole action. Silence about a deliberate refusal is the same defect as a silent
                    // failure.
                    const retained = Object.entries(res?.dataRetained ?? {});
                    if (alsoData && retained.length)
                        this.toast.warning(
                            `Deleted '${id}'. ${retained.length} data ${
                                retained.length === 1 ? 'directory was' : 'directories were'
                            } kept: ${retained.map(([dir, why]) => `${dir} (${why})`).join('; ')}`,
                        );
                    else this.toast.success(`Deleted '${id}'`);
                    this.flows.update((fs) => fs.filter((f) => f.name !== id));
                    // ⚠ The same teardown `closeTab` does, and for the same reason. Dropping the pipeline
                    // from `flows` alone left its TAB alive: `tabs` maps `openIds`, not `flows`, and
                    // `cachedModels` still held the graph. So a deleted pipeline kept a clickable tab —
                    // labelled with its raw id, because the list lookup now missed — `activateTab`
                    // restored it from cache as if it were live, and a Save then PUT it straight back,
                    // resurrecting the pipeline the operator had just confirmed deleting.
                    this.forgetTab(id);
                    this.openIds.update((ids) => ids.filter((x) => x !== id));
                    this.clearActive();
                    // Fall back to the next OPEN tab, as closing does — not `flows()[0]`, which opened a
                    // brand-new tab on an unrelated pipeline the operator had never asked to see.
                    const next = this.openIds()[0];
                    if (next) void this.activateTab(next);
                },
                error: (err) => this.onWriteError(err, 'Delete failed'),
            });
    }

    // ── canvas events ──

    onNodeSelected(id: string): void {
        const from = this.connectFrom();
        if (from && from !== id) {
            this.addEdge(from, id, 'data');
            this.connectFrom.set(null);
            return;
        }
        const node = this.model()?.nodes.find((n) => n.id === id) ?? null;
        this.selectedEdgeId.set(null);
        this.selectedNode.set(node);
        if (node) this.inspectorOpen.set(true); // reveal the property panel on selection
        if (node) void this.followSelectionIntoDefinition(node);
    }

    /**
     * S3's follow rule, RE-SCOPED AGAIN by the operator 2026-08-22 (third flip — see the memory trail):
     * selecting a Step opens its CONFIGURATION directly in the Properties dock; the slim summary
     * renders only where the pane cannot serve (read-only lens, dialog-custody parse nodes). A DIRTY
     * pane keeps `openDefinition`'s confirm; declining leaves the previous pane in place. Identity
     * (Name/Description) lives as fields inside the pane's identity strip, so nothing is lost by
     * skipping the summary.
     */
    private async followSelectionIntoDefinition(node: AuthoredNode): Promise<void> {
        if (!this.canAuthor() || !this.isDrawerKind(node)) return;
        if (this.definitionNode()?.id === node.id) return;
        await this.openDefinition(node);
    }

    /**
     * Whether this node's configuration is served by the definition drawer — the same gate
     * {@link openNodeConfig} routes by, spelled once. False = the Grammar dialog's custody cases.
     */
    private isDrawerKind(node: AuthoredNode): boolean {
        return !isParseNodeType(node.type) || this.isDrawerParse(node);
    }

    /**
     * The node the INSPECTOR SUMMARY should render, or null for the idle hint. Since the 2026-08-22
     * re-flip, selection opens the config pane directly, so the summary only shows where the pane
     * cannot serve: the read-only lens and dialog-custody parse nodes. The template prefers
     * `definitionNode()`, so this only decides what shows when NO pane is open.
     */
    readonly inspectorSummaryNode = computed<AuthoredNode | null>(() => this.selectedNode());

    /** Double-click a node (or the inspector's Configure button) → open the per-processor config popup. */
    onNodeOpen(id: string): void {
        if (this.connectFrom()) return; // mid-connection: a double-click shouldn't pop the editor
        const node = this.model()?.nodes.find((n) => n.id === id);
        if (node) this.openNodeConfig(node);
    }

    openNodeConfig(node: AuthoredNode): void {
        if (!this.canAuthor()) return; // read-only (Business lens or View mode): no-op — double-click/Configure can't mutate
        // Definition-surface P1/P3a: the collector path and the delimited-parser path open in the
        // right-dock definition drawer, not a popup. Every other kind keeps its dialog until its own
        // slice lands (P3b–P3d, P4). A grammar-BOUND parser node stays on the dialog even when it is
        // delimited: updating a reusable Grammar component is its own write route, which the dialog
        // owns — the drawer's Apply is an in-memory patch only (D2).
        // S2: EVERY kind now defines in the right-dock drawer — the canvas stays visible while it is
        // configured, and Apply/Discard means one thing everywhere. The ONE surface still on a popup is
        // the Grammar editor, for the parse nodes the drawer deliberately refuses (a grammar-BOUND node
        // — updating a reusable Grammar component is its own write route — a DANGLING binding, and
        // binary fixed-width; see isDrawerParse).
        // ⚠ The predicate is the node TYPE (`isParseNodeType`), never the served category: the catalog
        // may not have resolved, and a category of '' would route a generic `parser` — the one node
        // kind that MUST keep the dialog — into the drawer.
        if (!isParseNodeType(node.type) || this.isDrawerParse(node)) {
            void this.openDefinition(node);
            return;
        }
        const category = this.typeCategory(node.type);
        this.dialog
            .open(GrammarEditorDialog, {
                width: '1100px',
                maxWidth: '95vw',
                autoFocus: false,
                data: { node, typeLabel: node.type, categoryLabel: categoryLabel(category) },
            })
            .afterClosed()
            .subscribe((res?: { node?: AuthoredNode }) => {
                if (res?.node) this.applyNodePatch(res.node);
            });
    }

    /**
     * What this pipeline can tell a fresh `enrichment` node about itself, so its wiring form seeds
     * derived values instead of empty required fields (definition-surface P6-c — the Onboarding stage
     * derives the same facts from its draft's `dirs`/`output` blocks; here they live on the sink node).
     *
     * ⚠ The Stage-1 output travels only when exactly ONE destination declares a `database`: a
     * multi-destination pipeline has no single "the output", and seeding one of them would quietly
     * point the transform at a store the author never chose. Quarantine is SINK-category too and
     * carries only `dir`, which is why the filter keys on the config, not the category alone.
     */
    enrichmentHost(): EnrichmentHostPipeline | undefined {
        const id = this.selectedId();
        if (!id) return undefined;
        const only = this.primaryOutputSink()?.config ?? null;
        return {
            pipelineId: id,
            inputDatabase: only ? String(only['database']) : undefined,
            inputFormat: only && typeof only['format'] === 'string' ? only['format'] : undefined,
        };
    }

    /**
     * The pipeline's single unambiguous output SINK, or `null` when there is none or more than one —
     * the same guard {@link enrichmentHost} already applied, factored out so
     * {@link filenameColumnTarget} can share it rather than re-deriving a second "the output" rule
     * that could quietly drift from the first.
     */
    private primaryOutputSink(): AuthoredNode | null {
        const stores = (this.model()?.nodes ?? []).filter(
            (n) => this.typeCategory(n.type) === 'SINK' && typeof n.config?.['database'] === 'string',
        );
        return stores.length === 1 ? stores[0] : null;
    }

    /**
     * The Parse pane's cross-node field (operator ask 2026-08-22): `output.filename_column` lives on
     * the SINK node, so this names it from here — `null` when {@link primaryOutputSink} is ambiguous,
     * in which case the pane renders nothing rather than guess which sink the operator meant.
     */
    filenameColumnTarget(): { value: string; target: string } | null {
        const sink = this.primaryOutputSink();
        if (!sink) return null;
        return { value: String(sink.config?.['filename_column'] ?? ''), target: sink.name || sink.id };
    }

    /**
     * Commit from the Parse pane's filename-column field straight onto the sink node — bypassing this
     * drawer's own Apply/Discard, the same immediate-write precedent {@link renameNode} set for
     * cross-node identity edits. Routed through the existing {@link applyNodePatch} so a config change
     * here gets the same treatment any other config edit does (dirty, invalidated test outcome, canvas
     * status refresh) rather than a hand-rolled partial update.
     */
    onSinkFilenameColumnChange(value: string | null): void {
        const sink = this.primaryOutputSink();
        if (!sink || !this.canAuthor()) return;
        const current = String(sink.config?.['filename_column'] ?? '') || null;
        if (value === current) return;
        const cfg = { ...(sink.config ?? {}) };
        if (value) cfg['filename_column'] = value;
        else delete cfg['filename_column'];
        this.applyNodePatch({ ...sink, config: cfg });
    }

    // ── definition drawer lifecycle (definition-surface P1) ────────────────────────────────────────

    /**
     * Whether this parse node defines in the drawer. Since S3 that is EVERY per-format parse node
     * ({@link PARSE_NODE_FRONTENDS}), bound or not — a bound one is materialised into an inline copy
     * by {@link definitionDraft}.
     *
     * Two exceptions stay on the dialog:
     * <ul>
     *   <li>a **dangling** binding — with nothing to resolve there is no faithful copy to migrate to,
     *       and seeding the drawer with defaults would replace the operator's (broken) reference with
     *       a silently invented Grammar. The dialog can at least show the binding;</li>
     *   <li>**binary** fixed-width ({@link isBinaryFixedWidth}) — see below.</li>
     * </ul>
     */
    private isDrawerParse(node: AuthoredNode): boolean {
        const effectiveType = node.type === 'parser' ? this.retypedParserType(node) : node.type;
        if (!effectiveType || !(effectiveType in PARSE_NODE_FRONTENDS)) return false;
        if (this.isBinaryFixedWidth(node)) return false;
        const id = this.boundGrammarId(node);
        return id === null || this.grammarTemplates().some((t) => t.name === id);
    }

    /**
     * S5/D8 — the per-format type a legacy generic `parser` node's own config maps to, or `null` for
     * the dialog. The shipped example pipeline carries this node, so most users' first contact with
     * "configure a parse Step" was the one surface with none of the parse loop on it.
     *
     * <p>Grounded (2026-08-21) before this migration was allowed: the engine merges the legacy
     * `csv_settings` map and the unified `parsing:` block into ONE map before building the delimited
     * grammar (`PipelineConfigParser.mergeParsing` — `parsing:` keys win), the editable lift carries
     * both spellings verbatim, and a lowered node whose config holds only `parsing.delimited.*` keeps
     * its dialect on the next read. So converging on the unified spelling loses nothing — but the seed
     * MUST fold `csv_settings` in ({@link definitionDraft}), or the drawer shows defaults over the
     * node's real dialect, masked whenever the real delimiter happens to equal the default.
     *
     * <p>Refusals (fail closed to the dialog): a node with any `use:` (bound or dangling — custody /
     * nothing faithful to copy), and a config whose normalized frontend is not a built-in the drawer
     * serves. A config-less placeholder maps to nothing and keeps the dialog too — the palette's
     * parse-slot rule is the intended way to give it a format.
     */
    private retypedParserType(node: AuthoredNode): string | null {
        if (node.use) return null;
        const cfg = node.config ?? {};
        const parsing = cfg['parsing'];
        if (parsing && typeof parsing === 'object' && !Array.isArray(parsing)) {
            const block = grammarContentAsParsingBlock(parsing as Record<string, unknown>);
            const nonDelimited = nonDelimitedGrammarBlock(block);
            const frontend = nonDelimited === null ? 'delimited' : nonDelimited;
            return `parser.${frontend}` in PARSE_NODE_FRONTENDS ? `parser.${frontend}` : null;
        }
        const csv = cfg['csv_settings'];
        return csv && typeof csv === 'object' && !Array.isArray(csv) ? 'parser.delimited' : null;
    }

    /**
     * A `record: bytes` fixed-width node. It lifts to `parser.fixedwidth` like any other — the node
     * TYPE spans the format (P3b operator decision) — but its field geometry lives in
     * `processing.ingester_config` and is executed by `FixedWidthRecordIngester`, not by the
     * `fixedwidth.fields[]` slices this pane authors. Routing it to the drawer would show a slice
     * table that governs nothing, so it keeps the dialog until a binary pane exists.
     */
    private isBinaryFixedWidth(node: AuthoredNode): boolean {
        const parsing = node.config?.['parsing'] as Record<string, unknown> | undefined;
        const fw = parsing?.['fixedwidth'] as Record<string, unknown> | undefined;
        return (
            String(fw?.['record'] ?? '')
                .trim()
                .toLowerCase() === 'bytes'
        );
    }

    /** The `grammar/<id>` this node binds, or `null` when its Grammar is already inline. */
    private boundGrammarId(node: AuthoredNode): string | null {
        return node.use?.startsWith(GRAMMAR_REF_PREFIX) ? node.use.slice(GRAMMAR_REF_PREFIX.length) : null;
    }

    /**
     * The node as the drawer should present it: a bound node becomes an inline COPY of its Grammar
     * component (D4 — editing migrates it). Nothing is written here and the model is untouched; the
     * migration only lands if the operator edits and Applies, which is exactly "migrates on edit".
     * The `use:` itself is dropped by the pane on submit, so only one place decides that.
     */
    private definitionDraft(node: AuthoredNode): AuthoredNode {
        // S5: a legacy generic `parser` node is presented as its per-format subtype, seeded from
        // whichever spelling the file used — `parsing:` keys win over `csv_settings`, mirroring the
        // engine's own mergeParsing precedence. The legacy map is folded in and DROPPED from the
        // draft: the model is untouched until Apply, and an applied node whose config carries only
        // the unified block round-trips fully (grounded — see retypedParserType). This is B6's
        // convergence through ordinary editing: Apply re-types the node to `parser.<frontend>`.
        if (node.type === 'parser') {
            const retyped = this.retypedParserType(node);
            if (!retyped) return node; // isDrawerParse already kept it off the drawer
            const { csv_settings: legacyCsv, parsing: storedParsing, ...cfg } = node.config ?? {};
            const stored = grammarContentAsParsingBlock((storedParsing as Record<string, unknown>) ?? {});
            const legacy = (legacyCsv as Record<string, unknown>) ?? {};
            const delimited = { ...legacy, ...((stored['delimited'] as Record<string, unknown>) ?? {}) };
            const parsing: Record<string, unknown> = {
                frontend: retyped.slice('parser.'.length),
                ...stored,
                ...(Object.keys(delimited).length ? { delimited } : {}),
            };
            return { ...node, type: retyped, config: { ...cfg, parsing } };
        }
        const id = this.boundGrammarId(node);
        if (id === null) return node;
        const component = this.grammarTemplates().find((t) => t.name === id);
        if (!component) return node; // dangling — isDrawerParse already kept it off the drawer
        return {
            ...node,
            config: { ...(node.config ?? {}), parsing: grammarContentAsParsingBlock(component.content ?? {}) },
        };
    }

    /** Open a node for definition in the right dock, guarding another node's unapplied edits. */
    async openDefinition(node: AuthoredNode): Promise<void> {
        const open = this.definitionNode();
        if (open && open.id !== node.id && this.definitionDirty()) {
            const ok = await this.confirm.confirmDestructive(
                `'${open.id}' has edits that have not been applied. Opening another definition discards them.`,
                { title: 'Discard unapplied edits?', confirmText: 'Discard' },
            );
            if (!ok) return;
        }
        this.definitionNode.set(this.definitionDraft(node));
        this.definitionEpoch.update((e) => e + 1);
        this.definitionDirty.set(false);
        this.rightTab.set('properties');
        this.inspectorOpen.set(true);
        // S4: a parse pane's option set renders as TABS, and at the dock's 300px default the labels
        // truncated to "Dialect | Typ…" with scroll arrows while the schema toolbar stacked — the pane
        // only breathed after a maximize. Ask for room, transiently: a wider stored preference wins and
        // nothing is persisted, so the operator's own width survives the visit.
        if (isParseNodeType(node.type)) this.inspectorSplitRef?.ensureAtLeast(TABBED_PANE_WIDTH);
    }

    /**
     * The parser node's `schema_file` — read-only context for the Load pane, whose mapping rules map FROM
     * that schema's fields. The key lives on the PARSER node (`PipelineLift` puts it there with
     * `csv_settings`/`schemas`/`segments`), never on `transform.map`, whose only authored keys are
     * `{columns, rules}` — so the host, which holds the whole graph, is the one place that can supply it.
     */
    readonly parserSchemaFile = computed(() => {
        const parser = (this.model()?.nodes ?? []).find((n) => isParseNodeType(n.type));
        return String(parser?.config?.['schema_file'] ?? '').trim();
    });

    /**
     * The definition drawer's header identity for a node — the GLOSSARY's definition stages
     * (**Collector · Parse · Load**), which is exactly the set `openDefinition` admits.
     *
     * <p>⚠ It is a THREE-way choice. The template used to inline a two-way
     * `acquisition ? 'Collector' : 'Parser'` ternary, which labelled the Load drawer **"PARSER"** and
     * gave it the parse icon — invisible to every unit test, found by driving the preview. Any new node
     * kind that reaches this drawer must be added here, not re-inlined at the binding.
     */
    definitionKind(node: AuthoredNode): { label: string; icon: string } {
        if (node.type === 'acquisition') return { label: 'Collector', icon: 'heroicons_outline:inbox-arrow-down' };
        if (node.type === 'transform.map') return { label: 'Load', icon: 'heroicons_outline:arrows-right-left' };
        if (isParseNodeType(node.type)) return { label: 'Parse', icon: 'heroicons_outline:document-text' };
        // S2 generalised the table: every other kind now reaches the drawer too, and there is no
        // definition-stage name for them — so the header states the node's own CATEGORY, which is what
        // the retired dialog's subtitle showed. ⛔ Do not fall through to 'Parse': that is exactly the
        // bug the three-way choice replaced, one kind wider.
        return { label: categoryLabel(this.typeCategory(node.type)), icon: this.categoryIcon(node.type) };
    }

    /** The drawer header glyph for a non-stage kind — the palette's own category icon. */
    private categoryIcon(type: string): string {
        switch (this.typeCategory(type)) {
            case 'TRANSFORM':
                return 'heroicons_outline:funnel';
            case 'ENRICH':
                return 'heroicons_outline:sparkles';
            case 'SINK':
                return 'heroicons_outline:circle-stack';
            case 'CONTROL':
                return 'heroicons_outline:adjustments-horizontal';
            default:
                return 'heroicons_outline:cube';
        }
    }

    /** Drawer Apply: ask the pane to rebuild the node (it emits `applied` → {@link onDefinitionApplied}). */
    applyDefinition(): void {
        (
            this.definitionPane ??
            this.parseDefinitionPane ??
            this.loadDefinitionPane ??
            this.configDefinitionPane
        )?.submit();
    }

    /** The pane's rebuilt node — an in-memory patch (D2), persisted only by the toolbar Save. */
    onDefinitionApplied(node: AuthoredNode): void {
        this.applyNodePatch(node);
        this.definitionNode.set(node);
        this.definitionDirty.set(false);
    }

    // U4: saveGrammarAsTemplate + GrammarTemplateDialog are gone — the Grammar CSV export is the
    // portable template now; stored grammar templates are created in the Components registry.

    /** Drawer Discard: recreate the pane from the model — the epoch is what the `@for` tracks. */
    discardDefinition(): void {
        this.definitionEpoch.update((e) => e + 1);
        this.definitionDirty.set(false);
    }

    /** U5: the drawer's full-width state, mirrored from `(maximizedChange)` — the dock binds its
     *  width to 100% over the canvas while set; the split handle stays MOUNTED, only hidden. */
    readonly drawerMaximized = signal(false);

    /** Close the drawer (the shell already dirty-confirmed); the inspector summary returns. */
    closeDefinition(): void {
        this.definitionNode.set(null);
        this.definitionDirty.set(false);
        this.drawerMaximized.set(false);
    }

    /** Preview a `sink.view` node's data: bounded rows from its captured `derived_sql` (T32 follow-up). */
    openViewPreview(node: AuthoredNode): void {
        this.dialog.open(ViewPreviewDialog, {
            width: '760px',
            autoFocus: false,
            data: { viewName: node.name || node.id },
        });
    }

    /** Open the run-to-here loop for a node: pick inbox files → run the subgraph up to it → see Parquet. */
    openRunToHere(node: AuthoredNode): void {
        const pipelineId = this.selectedId();
        const m = this.model();
        if (!pipelineId || !m) return;
        // Through `parseUseRef` — the ONE derivation of what a binding references — rather than a
        // hand-rolled prefix match. The hand-rolled one tested for the plural `connections/` while
        // the backend lowers and the dialog writes the singular `connection/`, so until 2026-08-04
        // it never found a binding authored through the dialog.
        const src = m.nodes.find(
            (n) => this.typeCategory(n.type) === 'SOURCE' && parseUseRef(n.use)?.kind === 'connection',
        );
        const connectionId = parseUseRef(src?.use)?.id ?? null;
        const ref = this.dialog.open(RunToHereDialog, {
            width: '760px',
            autoFocus: false,
            data: { pipelineId, node, connectionId },
        });
        ref.afterClosed().subscribe((r?: PipelineRunResult) => {
            if (r) this.applyRunOutcomes(r);
        });
    }

    /** Mark the run's nodes tested (canvas ✓), or `rejects` (✕) for any with unmatched rows. */
    private applyRunOutcomes(r: PipelineRunResult): void {
        const inRun = new Set(r.relations.map((rel) => rel.node));
        const rejected = new Set(
            r.relations.filter((rel) => rel.rel === 'unmatched' && rel.rowCount > 0).map((rel) => rel.node),
        );
        const next = new Map(this.testedStatus());
        for (const id of inRun) next.set(id, rejected.has(id) ? 'rejects' : 'tested');
        this.testedStatus.set(next);
        for (const id of inRun) {
            const node = this.model()?.nodes.find((n) => n.id === id);
            if (node) this.canvas?.setNodeStatus(id, this.statusOf(node));
        }
    }

    onEdgeSelected(id: string): void {
        this.selectedNode.set(null);
        this.connectFrom.set(null);
        this.selectedEdgeId.set(id);
        this.inspectorOpen.set(true);
    }

    onBackgroundClick(): void {
        this.clearSelection();
    }

    /** Drag-to-draw: G6 already drew the edge, so only record it in the model (default `data` relationship). */
    onEdgeCreated(e: { source: string; target: string }): void {
        if (!this.canAuthor()) return; // read-only (Business lens or View mode): canvas drag-to-draw can't mutate
        if (e.source === e.target) return;
        this.addEdge(e.source, e.target, 'data', { skipCanvas: true });
    }

    /** Hover preview: resolve the hovered node from the live model into a tooltip (or clear on leave). */
    onNodeHover(h: { id: string; x: number; y: number } | null): void {
        if (!h) {
            this.hoverTip.set(null);
            return;
        }
        const n = this.model()?.nodes.find((x) => x.id === h.id);
        if (!n) {
            this.hoverTip.set(null);
            return;
        }
        this.hoverTip.set({
            name: n.name || n.id,
            type: n.type,
            category: categoryLabel(this.typeCat().get(n.type) ?? ''),
            config: nodeConfigEntries(n).slice(0, 5),
            x: h.x,
            y: h.y,
        });
    }

    /** Palette drag-drop: place the new node where it was dropped. */
    onDropAdd(e: { type: string; x: number; y: number }): void {
        if (!this.canAuthor() || !this.model()) return; // read-only (Business lens or View mode): palette drag can't mutate
        if (this.claimParseSlot(e.type)) return;
        const node = this.insertNode(e.type);
        this.canvas?.addNode(node.id, node.id, this.visualKind(e.type), e.x, e.y);
        this.selectNewNode(node);
    }

    /** Palette click / keyboard (Enter): add the node at the canvas centre — the no-mouse path to add. */
    addFromPalette(type: string): void {
        if (!this.canAuthor() || !this.model()) return; // read-only (Business lens or View mode): palette click can't mutate
        if (this.claimParseSlot(type)) return;
        const node = this.insertNode(type);
        this.canvas?.addNodeAtCenter(node.id, node.id, this.visualKind(type));
        this.selectNewNode(node);
    }

    /**
     * The palette's **parse-slot** rule, and the reason it exists: the flat pipeline config has ONE
     * `parsing:` block, so a second parse node of EITHER spelling is refused at lowering with
     * `MULTI_PARSER`. Every new pipeline lifts with a GENERIC `parser` placeholder nobody authored, so
     * the builder's natural first move — "I want CSV, I'll click Delimited" — used to drop a floating
     * second parse Step and dead-end at Save on an internal node id.
     *
     * <p>So: adding a parse Step **re-types the untouched placeholder in place**, keeping its id and
     * both its edges (the visual kind is PARSE either way, so the canvas node needs no rebuild). A parse
     * Step that carries config is authored work — that one is REFUSED, pointing at the drawer that can
     * change its format, rather than adding a node that could never be saved.
     *
     * <p>Owns its own outcome — the toast AND the selection — so both palette entry points are one line.
     *
     * @returns whether the slot claim HANDLED the add (re-typed or refused); `false` = insert a new node.
     */
    private claimParseSlot(type: string): boolean {
        if (!isParseNodeType(type)) return false;
        const held = (this.model()?.nodes ?? []).find((n) => isParseNodeType(n.type));
        if (!held) return false;
        const labels = this.typeLabel();
        if (held.type !== 'parser' || Object.keys(held.config ?? {}).length > 0) {
            const heldLabel = labels.get(held.type) ?? held.type;
            this.toast.warning(
                `This pipeline already has a Parse Step (${heldLabel}) and it has room for one. ` +
                    `Open it to change its format, or delete it first.`,
            );
            return true;
        }
        // The placeholder's display name is the GENERIC label ("Parser"); carrying it onto a re-typed node
        // would leave a Delimited Step calling itself Parser in every inspector. An authored name stays.
        const label = labels.get(type) ?? type;
        const generic = !held.name?.trim() || held.name.trim() === (labels.get(held.type) ?? 'Parser');
        const retyped: AuthoredNode = { ...held, type, ...(generic ? { name: label } : {}) };
        this.model.update((m) => (m ? applyNodePatchInModel(m, retyped) : m));
        this.toast.info(`Set the Parse Step '${held.id}' to ${label}.`);
        this.selectNewNode(retyped);
        return true;
    }

    private visualKind(type: string): ReturnType<typeof categoryVisualKind> {
        return categoryVisualKind(this.typeCat().get(type) ?? 'TRANSFORM');
    }

    private insertNode(type: string): AuthoredNode {
        const id = uniqueNodeId(this.model(), type);
        const node: AuthoredNode = { id, type };
        this.model.update((m) => (m ? addNodeToModel(m, node) : m));
        return node;
    }

    /**
     * S3 — a Step added from the palette lands in its own configuration pane, the parity the Recipe
     * view's insert already had (`onRecipeInsert` has always ended in `openNodeConfig`). On canvas a
     * "Needs config" Step used to cost an extra click every single time.
     */
    private selectNewNode(node: AuthoredNode): void {
        this.canvas?.setNodeStatus(node.id, this.statusOf(node)); // a new node starts unconfigured/configured
        // Re-typing the parse placeholder renames the node ("Parser" → "Delimited") and reaches here, but
        // only the STATUS was pushed — `g6Data` is re-fed to the host on a graphKey (tab) change alone, so
        // the canvas kept the stale label until the tab was reopened. Every other rename path goes through
        // `applyNodePatch`, which does call updateNodeLabel.
        if (node.name) this.canvas?.updateNodeLabel?.(node.id, node.name);
        this.dirty.set(true);
        this.selectedNode.set(node);
        this.inspectorOpen.set(true);
        this.openNodeConfig(node);
    }

    onDeleteKey(): void {
        if (!this.canAuthor()) return; // read-only (Business lens or View mode): the canvas Delete key can't mutate
        const edgeId = this.selectedEdgeId();
        const node = this.selectedNode();
        if (edgeId) {
            this.removeEdgeById(edgeId);
        } else if (node) {
            this.removeNode(node.id);
        }
    }

    /**
     * The inspector's inline rename (the canvas rename affordance — the only rename path for
     * drawer-parse nodes). Deliberately NOT {@link applyNodePatch}: a rename changes no config, so it
     * must not invalidate the node's test outcome the way a config edit does.
     */
    renameSelected(v: { name: string; description: string }): void {
        this.renameNode(this.selectedNode(), v);
    }

    /**
     * Identity edit for a NAMED node — the drawer strip's Name/Description fields commit here with
     * the node THEY render ({@link definitionNode}), which is not always the selection (a pane can
     * be opened by a stage chip or a recipe insert without selecting). Routing the drawer's commit
     * through the selection made it a silent no-op exactly then — found driving the preview
     * 2026-08-22. ⚠ The MODEL node is what gets patched: the drawer's draft may be the S5 re-typed
     * presentation, so only identity moves onto the draft.
     */
    renameNode(node: AuthoredNode | null, v: { name: string; description: string }): void {
        const n = node && this.model()?.nodes.find((x) => x.id === node.id);
        if (!n || !this.canAuthor()) return;
        const updated: AuthoredNode = { ...n, name: v.name || undefined, description: v.description || undefined };
        this.model.update((m) => (m ? applyNodePatchInModel(m, updated) : m));
        if (this.selectedNode()?.id === updated.id) this.selectedNode.set(updated);
        this.definitionNode.update((d) =>
            d && d.id === updated.id ? { ...d, name: updated.name, description: updated.description } : d,
        );
        this.canvas?.updateNodeLabel(updated.id, updated.name || updated.id);
        this.dirty.set(true);
    }

    /**
     * Whether `node` may be switched off (Phase 4 S4 / D-13). The engine parks at exactly one shape —
     * a sink fed by a route BRANCH — so the test here is structural over the graph on screen: a sink
     * Step whose inbound edge from a `transform.route` Step carries a `route:<key>` relation.
     *
     * ⚠ The RELATION is the whole test, not just the source node. A destination no branch names (the
     * primary at `dirs.database`, in a config that also declares branches) hangs off the route node
     * too — but by a plain `data` edge, because the lift pairs branches to sinks BY DATABASE and finds
     * no key for it. Keying on "inbound from the route node" alone therefore offered the switch on a
     * Step whose disable `StepDisableArming` refuses; found driving the preview, 2026-08-29.
     *
     * Deliberately NOT a
     * mirror of `StepDisableArming.parkableSinkIds`' `sink__d<i>` id grammar, which is a lift-time
     * spelling this model never sees; the save gate remains the authority and its refusals surface
     * through the PUT's `refusals[]`.
     */
    parkableNode(node: AuthoredNode | null): boolean {
        const m = this.model();
        if (!node || !m) return false;
        // The served step-type catalog is the category authority, but it may not have arrived yet (and
        // never does in a unit test), so the built-in `sink.` type prefix stands in for it. Both agree
        // in production; without the fallback the switch would simply be absent until the catalog loads.
        if (this.typeCategory(node.type) !== 'SINK' && !node.type.startsWith('sink.')) return false;
        const routeIds = new Set(m.nodes.filter((n) => n.type === 'transform.route').map((n) => n.id));
        if (!routeIds.size) return false;
        return m.edges.some((e) => e.to === node.id && routeIds.has(e.from) && e.rel?.startsWith('route:'));
    }

    /**
     * The per-Step switch. Writes the node's own `enabled` config key — the SAME key the lift overlays
     * from `processing.disabled_steps` and `PipelineEditable` derives that list back from on save, so
     * this needs no new wire shape. `true` DELETES the key rather than storing it: the engine's default
     * is enabled, and an explicit `enabled: true` in every sink would be noise in the saved config.
     */
    setNodeEnabled(node: AuthoredNode | null, enabled: boolean): void {
        const n = node && this.model()?.nodes.find((x) => x.id === node.id);
        if (!n || !this.canAuthor()) return;
        const config: Record<string, unknown> = { ...(n.config ?? {}) };
        if (enabled) delete config['enabled'];
        else config['enabled'] = false;
        const updated: AuthoredNode = { ...n, config };
        this.model.update((m) => (m ? applyNodePatchInModel(m, updated) : m));
        if (this.selectedNode()?.id === updated.id) this.selectedNode.set(updated);
        this.definitionNode.update((d) => (d && d.id === updated.id ? { ...d, config } : d));
        this.dirty.set(true);
    }

    /** Arm two-click edge creation from the inspector's selected node. */
    armConnect(): void {
        const n = this.selectedNode();
        if (n) this.connectFrom.set(n.id);
    }

    // ── node config apply (from the popup) ──

    /** Replace a node in the model with the popup's edited version, refreshing its canvas label + status. */
    private applyNodePatch(updated: AuthoredNode): void {
        const m = this.model();
        if (!m) return;
        this.model.set(applyNodePatchInModel(m, updated));
        if (this.selectedNode()?.id === updated.id) this.selectedNode.set(updated);
        this.canvas?.updateNodeLabel(updated.id, updated.name || updated.id);
        // A freshly chosen/created ref is valid by construction; editing config invalidates a prior test.
        if (updated.use) this.validRefs.update((s) => new Set(s).add(updated.use!));
        this.clearTested(updated.id);
        this.canvas?.setNodeStatus(updated.id, this.statusOf(updated));
        this.dirty.set(true);
    }

    private clearTested(id: string): void {
        if (!this.testedStatus().has(id)) return;
        this.testedStatus.update((m) => {
            const next = new Map(m);
            next.delete(id);
            return next;
        });
    }

    // ── dry-run ──

    toggleDryRun(): void {
        this.bottomTab.update((t) => (t === 'dryrun' ? null : 'dryrun'));
    }

    // ── edge relationship (Stage 2) ──

    /** Re-label the selected edge with a different relationship (the canvas edge id encodes the rel). */
    setEdgeRel(rel: string): void {
        const id = this.selectedEdgeId();
        const m = this.model();
        const p = id ? decodeEdgeId(id) : null;
        if (!id || !m || !p) return;
        const next = setEdgeRelInModel(m, p.from, p.to, p.rel, rel);
        if (!next) return; // unchanged or would collide with an existing edge
        this.model.set(next);
        this.canvas?.removeElement(id);
        const newId = encodeEdgeId(p.from, p.to, rel);
        this.canvas?.addEdge(newId, p.from, p.to, rel);
        this.selectedEdgeId.set(newId);
        this.dirty.set(true);
    }

    // ── validate & activate (Stage 4) ──

    /** Whether the selected flow is currently active. */
    isActive(): boolean {
        return this.model()?.active ?? false;
    }

    toggleValidate(): void {
        if (this.bottomTab() === 'validation') this.bottomTab.set(null);
        else this.validate();
    }

    /** Walk the flow for activation-blocking issues; opens the findings panel. */
    validate(): PipelineFinding[] {
        const m = this.model();
        const f = m ? validatePipeline(m, this.typeCat(), this.validRefs(), this.testedStatus()) : [];
        this.findings.set(f);
        this.bottomTab.set('validation');
        return f;
    }

    /**
     * G5 one-way door: a grandfathered `*_flow.toon` always *opens* (the lift is total) but a save
     * refuses every node the flat config has no home for. Surfaced on load, before the user invests
     * edits — deliberately a warning, not read-only, because deleting the offending node is exactly
     * how you make the pipeline saveable again, and read-only would block that repair.
     */
    readonly unsupportedNodes = computed<AuthoredNode[]>(() => {
        const lowerable = this.typeLowerable();
        if (!lowerable.size) return []; // catalog not in yet — don't cry wolf
        return (this.model()?.nodes ?? []).filter((n) => lowerable.get(n.type) === false);
    });

    /** The distinct offending types, for the banner text. */
    readonly unsupportedTypeList = computed(() => [...new Set(this.unsupportedNodes().map((n) => n.type))].join(', '));

    /** Click a finding to select its node on the canvas. */
    selectFinding(nodeId?: string): void {
        if (nodeId) this.onNodeSelected(nodeId);
    }

    /**
     * Go live (P6-b: the wizard's Publish stage as a toolbar action). Three things the bare `active`
     * flag write did not do, all of them the wizard's: the validate gate stays, a confirm names the
     * consequence, and going live registers the Dataset over the landed store.
     */
    async activate(): Promise<void> {
        const m = this.model();
        const id = this.selectedId();
        if (!m || !id) return;
        // The readiness gate P6-b left behind (P6-d): a MISSING stage produces no per-node error, so
        // the validator alone would let a guided pipeline with no parser at all go live. Named, never
        // silent. ⛔ GUIDED ONLY — the wizard's five stages are the Stream contract, not the editor's:
        // `validatePipeline` deliberately does not require a parse node, and a hand-built graph that
        // collects and sinks is legitimate. Applying this to every pipeline would refuse those.
        // ⚠ And only once the node-type catalog is in: every stage is derived through `typeCat`, so an
        // unresolved catalog reads as five empty stages — the gate would refuse a perfectly ready
        // pipeline and name every stage as missing. Same "don't cry wolf" posture as `unsupportedNodes`.
        const gated = this.guided() && this.typeCat().size > 0;
        const incomplete = gated ? incompleteStages(this.checklist()) : [];
        if (incomplete.length) {
            this.validate();
            this.toast.error(`Not ready to go live — ${incomplete.join(', ')} still needs work.`);
            return;
        }
        if (this.validate().some((f) => f.severity === 'error')) {
            this.toast.error('Fix the errors below before activating.');
            return;
        }
        const ok = await this.confirm.confirm(
            `Activate '${id}'? The collector starts picking up files on its next poll cycle.`,
            'Activate',
        );
        if (!ok) return;
        this.setActive(id, { ...m, active: true }, `Activated '${id}'`, true);
    }

    async deactivate(): Promise<void> {
        const m = this.model();
        const id = this.selectedId();
        if (!m || !id) return;
        const ok = await this.confirm.confirm(
            `Take '${id}' offline? Collection stops after the current cycle; the Dataset stays registered.`,
            'Take offline',
        );
        if (!ok) return;
        this.setActive(id, { ...m, active: false }, `Deactivated '${id}'`, false);
    }

    private setActive(id: string, updated: AuthoredPipeline, ok: string, goingLive: boolean): void {
        this.activating.set(true);
        this.api.savePipelineGraph(id, updated).subscribe({
            next: () => {
                this.activating.set(false);
                this.model.set(updated);
                this.dirty.set(false);
                // The list row carries the `active` chip the Open dialog shows, and it is loaded once —
                // without this a pipeline activated in this session still read "inactive" everywhere but
                // the toolbar until a reload. Same in-place list patch as rename and delete.
                this.flows.update((fs) => fs.map((f) => (f.name === id ? { ...f, active: goingLive } : f)));
                this.toast.success(ok);
                if (goingLive) this.ensureDataset(id, updated.name || id);
            },
            error: (err) => {
                this.activating.set(false);
                if (!this.showRefusals(err)) this.onWriteError(err, 'Update failed');
            },
        });
    }

    /**
     * The Stream→Dataset hop the wizard's go-live has always performed, via the shared
     * {@link DatasetRegistrationService}. ⚠ Streams only — a Reference's store is consumed by name in
     * enrichments, not queried as a raw Dataset — and 🔴 the stream/reference answer comes from the
     * D8 `settings` endpoint, NOT from `PipelineSummary.produces`: that field is the list of STORES
     * the pipeline produces, a different concept wearing the same word. Never blocks or reverses the
     * activation, which has already succeeded by the time this runs.
     */
    private ensureDataset(id: string, display: string): void {
        const store = derivedPipelineId(id);
        this.api.settings(id).subscribe({
            next: (settings) => {
                if (settings.produces === 'reference') return;
                this.datasets.ensure(store, display).subscribe((res) => {
                    if (res.status === 'created')
                        this.toast.success(`Dataset "${store}" registered — queryable under Catalog ▸ Datasets`);
                    else if (res.status === 'failed')
                        this.toast.warning(
                            `The pipeline is live, but its Dataset could not be registered — ${datasetManualHint(store)}`,
                        );
                });
            },
            // Unknown kind ⇒ do NOT guess. Registering a Dataset over a Reference's store would put a
            // row set in the Catalog that nothing should query there.
            error: () =>
                this.toast.warning(
                    `The pipeline is live, but its kind could not be read, so no Dataset was registered — ${datasetManualHint(store)}`,
                ),
        });
    }

    // ── helpers ──

    /** Append `(from, to, rel)` to the model, syncing the canvas unless it already drew the edge itself. */
    private addEdge(from: string, to: string, rel: string, opts: { skipCanvas?: boolean } = {}): void {
        const m = this.model();
        if (!m) return;
        const next = addEdgeToModel(m, from, to, rel);
        if (!next) return; // duplicate — no-op
        this.model.set(next);
        if (!opts.skipCanvas) this.canvas?.addEdge(encodeEdgeId(from, to, rel), from, to, rel);
        this.dirty.set(true);
    }

    private removeNode(id: string): void {
        const m = this.model();
        if (!m) return;
        this.model.set(removeNodeFromModel(m, id));
        this.canvas?.removeElement(id);
        // ⚠ `clearSelection()` touches selectedNode/selectedEdgeId/connectFrom only, so the definition
        // drawer kept rendering the node that had just been deleted. Its Apply then reached a reducer
        // whose `map` matches nothing — a silent no-op — while still setting dirty and clearing
        // definitionDirty, i.e. the pane reported "applied" over work it had thrown away.
        if (this.definitionNode()?.id === id) this.closeDefinition();
        this.clearSelection();
        this.dirty.set(true);
    }

    private removeEdgeById(g6EdgeId: string): void {
        const m = this.model();
        const p = decodeEdgeId(g6EdgeId);
        if (m && p) {
            this.model.set(removeEdgeFromModel(m, p.from, p.to, p.rel));
            this.dirty.set(true);
        }
        this.canvas?.removeElement(g6EdgeId);
        this.selectedEdgeId.set(null);
    }

    private clearSelection(): void {
        this.selectedNode.set(null);
        this.selectedEdgeId.set(null);
        this.connectFrom.set(null);
    }

    private onWriteError(err: unknown, fallback: string): void {
        if ((err as { status?: number })?.status === 503) {
            this.unavailable.set(true);
            this.toast.error('Editing is read-only — the server has no write root (-Dassist.write.root).');
            return;
        }
        this.toast.error(apiErrorMessage(err, fallback));
    }
}
