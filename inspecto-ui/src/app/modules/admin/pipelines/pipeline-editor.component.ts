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
import {
    AuthoredPipeline,
    AuthoredNode,
    ComponentsService,
    ConfigService,
    PipelineRefusal,
    PipelineRunResult,
    PipelinesService,
    PipelineSummary,
    ProvenanceBatch,
    IconMap,
    IconMapService,
    LensService,
    apiErrorMessage,
} from 'app/inspecto/api';
import { type AttributeSpec, pipelineScaffold } from 'app/inspecto/component-model';
import { AiAssistComponent } from 'app/inspecto/ai-assist/ai-assist.component';
import { AiDraft } from 'app/inspecto/ai-assist/ai-draft';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSplitDirective } from 'app/inspecto/components/split.directive';
import { TransferMenuComponent } from 'app/inspecto/transfer';
import { G6GraphData } from 'app/modules/admin/catalog/catalog-graph';
import { PipelineDryRunPanelComponent } from './pipeline-dry-run-panel.component';
import { PipelineEditorGraphComponent } from './pipeline-editor-graph.component';
import { PipelineInspectorComponent } from './pipeline-inspector.component';
import { PipelinePaletteComponent } from './pipeline-palette.component';
import { NodeConfigDialog, NodeConfigResult } from './node-config.dialog';
import { ParserConfigDialog } from './parser-config.dialog';
import { PipelineOpenDialog } from './pipeline-open.dialog';
import { PipelineRenameDialog, PipelineRenameResultData } from './pipeline-rename.dialog';
import { PipelineTemplateDialog, PipelineTemplateResultData } from './pipeline-template.dialog';
import { RunToHereDialog } from './run-to-here.dialog';
import { ViewPreviewDialog } from './view-preview.dialog';
import { environment } from 'environments/environment';
import {
    PipelineFinding,
    NodeStatus,
    NodeTypeGroup,
    TestOutcome,
    addEdgeToModel,
    addNodeToModel,
    applyNodePatchInModel,
    authoredToG6,
    bindKindFor,
    candidateRelsFor,
    categoryLabel,
    categoryVisualKind,
    computeNodeStatus,
    decodeEdgeId,
    encodeEdgeId,
    findingIcon,
    findingTint,
    groupByCategory,
    nodeConfigEntries,
    nodeLastRunTotal,
    provenanceCounts,
    removeEdgeFromModel,
    removeNodeFromModel,
    setEdgeRelInModel,
    statusLabel,
    typeCategoryMap,
    uniqueNodeId,
    validatePipeline,
} from './pipeline-graph';

/**
 * Pipeline editor (T32, build-side NiFi UX) — author/edit a `*_flow.toon` pipeline on an interactive G6 canvas:
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
        PipelineDryRunPanelComponent,
        PipelineEditorGraphComponent,
        PipelineInspectorComponent,
        PipelinePaletteComponent,
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
    private components = inject(ComponentsService);
    private iconMapApi = inject(IconMapService);
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

    readonly flows = signal<PipelineSummary[]>([]);

    // ── open set (tabs) ───────────────────────────────────────────────────────────────────────────
    /** The open tabs, in strip order. Nothing is open until the user opens something. */
    readonly openIds = signal<string[]>([]);
    /**
     * Per-tab graph + dirty state, so switching tabs never silently discards unsaved edits. The active
     * tab's graph lives in {@link model} as before; this is where the inactive ones wait.
     */
    private readonly cachedModels = new Map<string, AuthoredPipeline>();
    private readonly dirtyIds = signal<ReadonlySet<string>>(new Set());

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
    readonly model = signal<AuthoredPipeline | null>(null);

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
    private readonly typeCat = signal<Map<string, string>>(new Map());
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
     * `POST /pipelines/authored/{id}/run?to=` has no real route — PipelineRoutes reserves the path
     * and never registers it, so run-to-here only works against the offline mock. Gate, don't 404.
     */
    readonly scratchRunAvailable = environment.mockPipelines;

    readonly bottomTab = signal<'dryrun' | 'validation' | null>(null);

    // ── canvas status (Stage 2) + validation/activation (Stage 4) ──
    /** Known registry refs (`grammar/x`, `transform/y`, …) — drives dangling detection once loaded. */
    private readonly validRefs = signal<Set<string>>(new Set());
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
        return m ? authoredToG6(m, this.typeCat(), (n) => this.statusOf(n), this.iconMap(), this.lastRunCounts()) : null;
    });

    /** The selected node's last-run output (T17), or `null` when that run recorded nothing for it. */
    selectedNodeLastRun(): { rowCount: number; runTs: string } | null {
        const node = this.selectedNode();
        const batch = this.lastRunBatch();
        if (!node || !batch) return null;
        const rowCount = nodeLastRunTotal(node.id, this.lastRunCounts());
        return rowCount == null ? null : { rowCount, runTs: batch.runTs };
    }

    /** A node's authoring status — the canvas outline cue and the inspector chip. */
    statusOf(n: AuthoredNode): NodeStatus {
        return computeNodeStatus(n, this.typeCategory(n.type), this.validRefs(), this.testedStatus(), this.refsLoaded());
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
                this.paletteGroups.set(groupByCategory(ts));
                this.typeCat.set(typeCategoryMap(ts));
                this.typeEmits.set(new Map(ts.map((t) => [t.type, t.emits])));
                this.typeLowerable.set(new Map(ts.map((t) => [t.type, t.lowerable])));
                // Only types the server actually specced — a type whose `attributes` the payload omits
                // entirely (an older server) must stay absent so the dialog uses its fallback table.
                this.typeAttributes.set(new Map(
                    ts.filter((t) => t.attributes !== undefined).map((t) => [t.type, t.attributes!])));
            },
            error: () => this.paletteGroups.set([]),
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
                this.openIds.set(next.filter((id) => keep.has(id)).concat([...keep].filter((id) => !next.includes(id))));

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
    activateTab(id: string): void {
        if (this.selectedId() === id) return;
        this.parkCurrent();
        this.clearSelection();
        const cached = this.cachedModels.get(id);
        if (cached) {
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
        if (this.dirtyIds().has(id)) {
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
        this.dirtyIds.update((s) => {
            const next = new Set(s);
            next.delete(id);
            return next;
        });
    }

    /** No active tab — the empty canvas state. */
    private clearActive(): void {
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
        if (this.selectedId() !== id) this.parkCurrent();
        this.clearSelection();
        if (!this.openIds().includes(id)) this.openIds.update((ids) => [...ids, id]);
        // W5: the editor edits the CANONICAL *_pipeline.toon — lift it to the editable graph.
        this.api.pipelineGraphRaw(id).subscribe({
            next: (flow) => {
                this.model.set(flow);
                this.selectedId.set(id); // drives the host rebuild (graphKey)
                this.dirty.set(false);
            },
            error: (err) => this.toast.error(apiErrorMessage(err, 'Could not load the pipeline')),
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
        // W5: a new pipeline IS a canonical draft — write the space-convention scaffold (inactive,
        // with the parser-required dirs) and register it, then load its lifted graph.
        this.configApi.write('pipeline', pipelineScaffold(name)).subscribe({
            next: (written) => {
                this.configApi.registerPipeline(written.path).subscribe({
                    next: () => {
                        this.creating.set(false);
                        this.flows.update((fs) => [...fs, { name, active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] }]);
                        this.select(name);
                    },
                    error: () => {
                        // the file exists; the row appears after the next rescan
                        this.creating.set(false);
                        this.select(name);
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

    save(): void {
        const m = this.model();
        const id = this.selectedId();
        if (!m || !id) return;
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
     * Surface named lower-refusals (UNSUPPORTED_NODE / MULTI_SINK / NO_*) in the Validation dock, or
     * false if the error carried none. Every refusal is listed and stays put — a first-only transient
     * toast turned an n-problem graph into n save→fix→save cycles.
     */
    private showRefusals(err: unknown): boolean {
        const refusals = (err as { error?: { error?: { details?: { refusals?: PipelineRefusal[] } } } })
            ?.error?.error?.details?.refusals;
        if (!refusals?.length) return false;
        this.findings.set(refusals.map((r) => ({
            severity: 'error' as const,
            nodeId: r.nodeId,
            message: r.message,
        })));
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
                        this.select(res.id);   // reloads the real graph, correcting the optimistic counts
                        this.toast.success(`Created template '${res.id}'`);
                        // e.g. the schema could not be copied — the operator must repoint it before editing.
                        written.notes?.forEach((n) => this.toast.info(n));
                    },
                    error: (err) => this.onWriteError(err, 'Could not create the template'),
                });
            });
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
                        this.flows.update((fs) =>
                            fs.map((f) => (f.name === id ? { ...f, displayName: res.name } : f)),
                        );
                        this.toast.success(`Renamed to '${res.name}'`);
                    },
                    error: (err) => this.onWriteError(err, 'Rename failed'),
                });
            });
    }

    async deletePipeline(): Promise<void> {
        const id = this.selectedId();
        if (!id) return;
        const ok = await this.confirm.confirmDestructive(
            `Permanently delete the authored pipeline '${id}'?`,
            { title: 'Delete pipeline', confirmText: 'Delete' },
        );
        if (!ok) return;
        // W5: deleting a registered pipeline discards its canonical config (the server refuses an
        // active pipeline — deactivate first).
        this.configApi.remove('pipeline', id).subscribe({
            next: () => {
                this.flows.update((fs) => fs.filter((f) => f.name !== id));
                this.model.set(null);
                this.selectedId.set(null);
                this.clearSelection();
                const next = this.flows()[0];
                if (next) this.select(next.name);
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
    }

    /** Double-click a node (or the inspector's Configure button) → open the per-processor config popup. */
    onNodeOpen(id: string): void {
        if (this.connectFrom()) return; // mid-connection: a double-click shouldn't pop the editor
        const node = this.model()?.nodes.find((n) => n.id === id);
        if (node) this.openNodeConfig(node);
    }

    openNodeConfig(node: AuthoredNode): void {
        if (!this.canAuthor()) return; // read-only (Business lens or View mode): no-op — double-click/Configure can't mutate
        const category = this.typeCategory(node.type);
        // Parsers get the rich multi-pane parser editor; every other category uses the generic config popup.
        const ref =
            category === 'PARSE'
                ? this.dialog.open(ParserConfigDialog, {
                      width: '1100px',
                      maxWidth: '95vw',
                      autoFocus: false,
                      data: { node, typeLabel: node.type, categoryLabel: categoryLabel(category) },
                  })
                : this.dialog.open(NodeConfigDialog, {
                      width: '520px',
                      autoFocus: false,
                      data: {
                          node,
                          typeLabel: node.type,
                          categoryLabel: categoryLabel(category),
                          bindKind: bindKindFor(category),
                          attributes: this.typeAttributes().get(node.type),
                      },
                  });
        ref.afterClosed().subscribe((res?: NodeConfigResult) => {
            if (res?.node) this.applyNodePatch(res.node);
        });
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
        const src = m.nodes.find((n) => this.typeCategory(n.type) === 'SOURCE' && n.use?.startsWith('connections/'));
        const connectionId = src?.use ? src.use.slice('connections/'.length) : null;
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
        const node = this.insertNode(e.type);
        this.canvas?.addNode(node.id, node.id, this.visualKind(e.type), e.x, e.y);
        this.selectNewNode(node);
    }

    /** Palette click / keyboard (Enter): add the node at the canvas centre — the no-mouse path to add. */
    addFromPalette(type: string): void {
        if (!this.canAuthor() || !this.model()) return; // read-only (Business lens or View mode): palette click can't mutate
        const node = this.insertNode(type);
        this.canvas?.addNodeAtCenter(node.id, node.id, this.visualKind(type));
        this.selectNewNode(node);
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

    private selectNewNode(node: AuthoredNode): void {
        this.canvas?.setNodeStatus(node.id, this.statusOf(node)); // a new node starts unconfigured/configured
        this.dirty.set(true);
        this.selectedNode.set(node);
        this.inspectorOpen.set(true);
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
    readonly unsupportedTypeList = computed(() =>
        [...new Set(this.unsupportedNodes().map((n) => n.type))].join(', '));

    /** Click a finding to select its node on the canvas. */
    selectFinding(nodeId?: string): void {
        if (nodeId) this.onNodeSelected(nodeId);
    }

    activate(): void {
        const m = this.model();
        const id = this.selectedId();
        if (!m || !id) return;
        if (this.validate().some((f) => f.severity === 'error')) {
            this.toast.error('Fix the errors below before activating.');
            return;
        }
        this.setActive(id, { ...m, active: true }, `Activated '${id}'`);
    }

    deactivate(): void {
        const m = this.model();
        const id = this.selectedId();
        if (!m || !id) return;
        this.setActive(id, { ...m, active: false }, `Deactivated '${id}'`);
    }

    private setActive(id: string, updated: AuthoredPipeline, ok: string): void {
        this.activating.set(true);
        this.api.savePipelineGraph(id, updated).subscribe({
            next: () => {
                this.activating.set(false);
                this.model.set(updated);
                this.dirty.set(false);
                this.toast.success(ok);
            },
            error: (err) => {
                this.activating.set(false);
                if (!this.showRefusals(err)) this.onWriteError(err, 'Update failed');
            },
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
