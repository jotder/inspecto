import { TermHelpComponent } from '@inspecto/core/onboarding/term-help.component';
import {
    ChangeDetectionStrategy,
    ChangeDetectorRef,
    Component,
    DestroyRef,
    ElementRef,
    Injector,
    OnDestroy,
    OnInit,
    ViewChild,
    afterNextRender,
    computed,
    effect,
    inject,
    signal,
    untracked,
} from '@angular/core';
import {
    FormBuilder,
    FormControl,
    FormsModule,
    ReactiveFormsModule,
    Validators,
    AbstractControl,
    ValidatorFn,
} from '@angular/forms';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { EntityProfileService, EntityTypeSummary } from '@inspecto/link-analysis/api/entity-profile.service';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule, MatMenuTrigger } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { FormMenuDirective } from './form-menu.directive';
import { MatSliderModule } from '@angular/material/slider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, UrlTree } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { firstValueFrom } from 'rxjs';
import { distinctUntilChanged, map } from 'rxjs/operators';
import {
    ComponentsService,
    ExchangeService,
    LensService,
    PipelineSummary,
    PipelinesService,
    SpacesService,
    apiErrorMessage,
} from '@inspecto/core/api';
import { GraphRunsService } from '@inspecto/link-analysis/api/graph-runs.service';
import { indexSourceNote } from './index-source';
import { IndexMappingTarget } from './link-analysis-index-build.component';
import {
    EntityListSummary,
    IndexSource,
    InvService,
    MaskingNote,
    MultiProjectionMappingSummary,
} from '@inspecto/link-analysis/api/inv.service';
import { OfferShareDialog, OfferShareResult } from '@inspecto/core/components/offer-share.dialog';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ComponentHistoryDialog } from '@inspecto/core/components/component-history.dialog';
import { LinkAnalysisStartBoxComponent, StartNotice } from './link-analysis-start-box.component';
import {
    START_MAX_SEEDS,
    StartDataset,
    StartRequest,
    datasetForType,
    genericBinding,
    hasRoute,
    seedNodeOf,
    startCatalogue,
    startOutcome,
    someValues,
} from './start-from';
import { QueuedSeed, pickFollowTheMoney } from './la-starter';
import { CANVAS_MODES, CanvasMode, canvasInteractionFor, canvasModeForKey, canvasModeOptions } from './canvas-mode';
import { DatasetRowsService } from '@inspecto/core/viz/dataset-rows.service';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import {
    InvestigateRequest,
    PendingInvestigation,
    investigateBinding,
    latestTimeOf,
    latestTimeSql,
    missingMappedColumns,
    normaliseMsisdn,
    seedError,
    parseInvestigateParams,
    sameInvestigateRequest,
} from './investigate-number';
import { degreeState } from './investigate-number';
import { GrowExcludeStore } from './grow-exclude.store';
import {
    ExpandSource,
    GrowContext,
    GrowRequest,
    drawnValues,
    frontierCapStop,
    filterExpansion,
    growKeepsEdge,
    hiddenByListsLine,
    neighboursPreviewBody,
    packTerms,
    removedRows,
    withUndrawnLinks,
} from './grow-exclude';
import { ExpandRun, LinkAnalysisExpandComponent } from './link-analysis-expand.component';
import { HeldItem, LinkAnalysisHeldTrayComponent } from './link-analysis-held-tray.component';
import { LinkAnalysisExclusionsComponent } from './link-analysis-exclusions.component';
import { ExcludeDialogData, ExcludeDialogResult, LinkAnalysisExcludeDialog } from './link-analysis-exclude.dialog';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { DataTableComponent } from '@inspecto/core/data-table';
import { G6GraphData, EntityProjection, GraphSource, GraphSourceId, GraphSourceQuery, NodeRule, toGraphml } from '@inspecto/core/graph';
import { LinkAnalysisGlossaryComponent } from './link-analysis-glossary.component';
import { LinkAnalysisDisplayRulesComponent } from './link-analysis-display-rules.component';
import {
    analysisNodeCapValue,
    browserAlgorithmCapValue,
    louvainCommunities,
    filterByKinds,
    filterByTime,
    isForest,
    mergeGraphs,
    searchNodes,
    aggregateSuperNodes,
    GraphSelection,
    SUPER_NODE_KIND,
} from '@inspecto/link-analysis/graph/graph-analysis';
import {
    HistoryStack,
    emptyHistory,
    pushHistory,
    redo as redoHistory,
    undo as undoHistory,
} from '@inspecto/link-analysis/graph/graph-history';
import { workingSetStats, attrColumns, isTemporalColumn } from '@inspecto/link-analysis/graph/working-set-stats';
import {
    DEFAULT_MAX_DEGREE,
    DomainProfileId,
    activeDomainProfileId,
    domainProfile,
    domainProfiles,
    profileForEntityType,
    workingSetOptionsFor,
} from '@inspecto/link-analysis/graph/domain-profile';
import { DomainProfileService } from '@inspecto/link-analysis/api/domain-profile.service';
import {
    EndpointColumns,
    cloneGroup,
    filterEdgesByPredicate,
    hasConditions,
    markStranded,
    predicateColumns,
} from '@inspecto/link-analysis/graph/graph-filter';
import { ConditionGroup, emptyGroup } from '@inspecto/core/query/query-types';
import { LinkAnalysisFilterComponent, LocalMatch } from './link-analysis-filter.component';
import { LinkAnalysisNodeListComponent } from './link-analysis-node-list.component';
import {
    LinkAnalysisAdvancedSearchDialog,
    LinkAnalysisAdvancedSearchData,
} from './link-analysis-advanced-search.dialog';
import {
    AttachCaseDialogData,
    AttachCaseResult,
    LinkAnalysisAttachCaseDialog,
    LinkAnalysisSnapshotDialog,
    SnapshotDialogData,
} from './link-analysis-evidence.dialogs';
import { LinkAnalysisSnapshotsService } from './link-analysis-snapshots.service';
import { EntityIdMapping } from '@inspecto/core/graph';
import { GeoLinkBrushService, nodeIdsForKeys } from '@inspecto/link-analysis/graph/geo-link-brush';
import { GraphSnapshot } from '@inspecto/link-analysis/graph/graph-snapshot';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { InspectoSplitDirective } from '@inspecto/core/components/split.directive';
import {
    LegendItem,
    hideLinkKinds,
    legendEdgeKindsFor,
    legendItemsFor,
    toggleHiddenKind,
    CanvasOverlay,
    LinkAnalysisLegendComponent,
    LinkAnalysisWorkingSetComponent,
    overlayStartsOpen,
    rememberOverlayOpen,
    storedOverlayOpen,
} from './link-analysis-overlays.component';
import { ICON_COLOR_SWATCHES } from '@inspecto/core/theme/chart-tokens';
import {
    DENSE_EDGE_LABEL_CAP,
    EdgePattern,
    GRAPH_EDGE_PATTERNS,
    GRAPH_DEFAULT_LAYOUT_SPACING,
    GRAPH_DEFAULT_NODE_SIZE,
    GRAPH_EDGE_SIZES,
    GRAPH_LAYOUT_SPACINGS,
    GRAPH_LAYOUTS,
    GRAPH_NODE_SHAPES,
    GRAPH_NODE_SIZES,
    GraphDisplayOptions,
    GraphEmphasis,
    GraphLayoutId,
    GraphViewComponent,
    GraphViewInteraction,
    GraphViewPlugins,
    baseEdgeKind,
    edgeLabelsHiddenByDensity,
    graphSignature,
    stableKey,
} from '@inspecto/core/graph/graph-view.component';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';
import {
    ElementDetailDialog,
    ElementDetailResult,
    ElementObjectRef,
} from '@inspecto/link-analysis/investigation/element-detail.dialog';
import { PivotService } from '@inspecto/link-analysis/investigation/pivot.service';
import type { LaDataset } from '@inspecto/link-analysis/la-host';
import {
    LA_AI_ASSIST,
    LA_DATASETS,
    LA_FEATURES,
    LA_TAGS,
    LA_TRANSFER,
    LaHostSlotComponent,
    investigationsLockedReason,
} from '@inspecto/link-analysis/la-host';
import { LockedActionComponent } from '@inspecto/core/components/locked-action.component';
import type { LaImportDraft } from '@inspecto/link-analysis/la-host';
import { draftSavedWarning, recheckDraft } from '@inspecto/link-analysis/investigation/draft-recheck';
import {
    MultiProjectedGraph,
    ProjectedGraph,
    ServerPathsState,
    ServerPatternState,
    branchingResultToGraph,
    invErrorMessage,
    projectionNodeCapValue,
    recursivePathsToGraph,
    resolveRunQuery,
    splitIdentityGroups,
} from './entity-projection';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisCommentsDialog } from './link-analysis-comments.dialog';
import {
    CaseStart,
    LinkAnalysisService,
    LinkAnalysisView,
    LinkAnalysisViewOptions,
    SAVED_VIEW_NOT_EVIDENCE,
} from './link-analysis.service';
import { FoundAction, LinkAnalysisDiscoverComponent } from './link-analysis-discover.component';
import { AddToListData, AddToListResult, LinkAnalysisAddToListDialog } from './link-analysis-add-to-list.dialog';
import { LinkAnalysisTemporalFindingsComponent, TemporalRunRequest } from './link-analysis-temporal-findings.component';
import { LinkAnalysisTimelinePlaybackComponent } from './link-analysis-timeline-playback.component';
import { TemporalFindingsState, allTemporalSelection, temporalResultToState } from './temporal-findings';
import {
    FindPathsRequest,
    InvestigationPatternTarget,
    LinkAnalysisToolboxComponent,
    ServerPatternRequest,
} from './link-analysis-toolbox.component';
import { LinkAnalysisQueryPanelComponent, QuerySummaryItem } from './link-analysis-query-panel.component';
import { LinkAnalysisInvestigationComponent } from './link-analysis-investigation.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { LinkAnalysisCapsComponent } from './link-analysis-caps.component';
import { CleanupApply, LinkAnalysisCleanupComponent } from './link-analysis-cleanup.component';
import { foldBranches, foldableOf } from '@inspecto/link-analysis/graph/graph-cleanup';
import { autoStyle } from './auto-style';
import { LinkAnalysisModulesComponent } from './link-analysis-modules.component';
import { buildServerIdMap } from './graph-run-apply';
import { idsInWorkingSet, mergePairOf, rawIdsOf } from './investigation-state';
import { NodeInsightComponent, NodeInsightContext } from './node-insight.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { EntityListReasonData, EntityListReasonDialog } from './link-analysis-entity-lists.dialogs';
import { laLayoutCaps, laLimit, layoutCapNoticeText, layoutOverCapReason, overCapReason } from './la-limits';
import { LaLimitsService } from './la-limits.service';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { closedBySpaceSwitchMessage, rememberOpenWork, takeClosedBySpaceSwitch } from './open-work';
// eslint-disable-next-line no-restricted-imports -- only `actor`, so the Space-switch notice never names another user's item; LA_FEATURES carries module flags, not identity
import { SessionService } from '@inspecto/core/api';
import { DatePipe } from '@angular/common';
import { GraphQueryCache, PUSH_FILTER_DEBOUNCE_MS } from './graph-query-cache';
import { deepEqual } from '@inspecto/link-analysis/api/stable-json';

/** Inline duplicate-name guard (house form rule) — blocks saving a view under a taken name. */
function uniqueNameValidator(taken: () => string[]): ValidatorFn {
    return (c: AbstractControl) => {
        const v = String(c.value ?? '')
            .trim()
            .toLowerCase();
        return taken().some((t) => t.trim().toLowerCase() === v) ? { duplicate: true } : null;
    };
}

/** Undo/redo scope (Phase G): presentation state only — search/filter, display options, layout, and
 *  collapsed branches. NOT the query or the loaded graph itself (a heavier, separate feature). */
interface PresentationSnapshot {
    search: string;
    kindFilter: string[];
    timeColumn: string;
    timeCutoff: number | null;
    collapsedRoots: string[];
    /** Nodes taken off the exploration canvas (Hide on canvas / Hide selected): presentation only, so undoable. */
    hiddenNodeIds: string[];
    nodeLabels: boolean;
    edgeLabels: boolean;
    allEdgeLabels: boolean;
    nodeColors: Record<string, string>;
    edgeColors: Record<string, string>;
    nodeShapes: Record<string, string>;
    edgePatterns: Record<string, EdgePattern>;
    edgeSizes: Record<string, number>;
    nodeRules: NodeRule[];
    nodeSize: number;
    layoutSpacing: number;
    layoutId: GraphLayoutId;
}

/**
 * **Link Analysis Studio** (C5, plan: docs/superpower/link-analysis-studio-plan.md §3 P3) — pick a
 * GraphSource, shape a query, render through the shared {@link GraphViewComponent}, analyze with the
 * pure `graph-analysis` library, and save the investigation as a `link-analysis-view` Component.
 */
/** LA-11: paths asked for per search — a readable list; the server says `truncated` when more exist. */
const SERVER_PATH_LIMIT = 100;

/** Below this viewport width the Toolbox dock starts collapsed to its rail (canvas width); wider windows start open. */
export const TOOLBOX_NARROW_PX = 1300;
/** The narrowest canvas zone worth keeping beside the docks; below it a dock collapses to its rail. */
export const CANVAS_MIN_PX = 320;
const TOOLBOX_OPEN_KEY = 'inspecto.la.toolboxOpen';

/**
 * The Toolbox dock's starting state: the user's explicit open/close from earlier this session (sessionStorage) wins;
 * otherwise collapsed below {@link TOOLBOX_NARROW_PX} and open at or above it. A browser without `matchMedia` counts as wide.
 */
function initialToolboxOpen(): boolean {
    try {
        const saved = sessionStorage.getItem(TOOLBOX_OPEN_KEY);
        if (saved === 'true' || saved === 'false') return saved === 'true';
    } catch {
        /* storage blocked — fall through to the viewport rule */
    }
    return !(window.matchMedia?.(`(max-width: ${TOOLBOX_NARROW_PX - 1}px)`).matches ?? false);
}

/**
 * The snapshot dialog's suggested title — never a bare "— N nodes": "Investigation <title> — N nodes" on an
 * Investigation canvas, "<source> — N nodes" for a query graph, else "Graph — N nodes".
 */
export function snapshotTitle(onInvestigation: boolean, invTitle: string | undefined, source: string, nodes: number): string {
    const n = `${nodes} ${nodes === 1 ? 'node' : 'nodes'}`;
    if (onInvestigation) return invTitle?.trim() ? `Investigation ${invTitle.trim()} — ${n}` : `Investigation — ${n}`;
    return `${source.trim() || 'Graph'} — ${n}`;
}

/** PERF-P10a-2: a reset to an equal list is no change (a re-run that folds to the same graph must not redraw the canvas). */
function sameStrings(a: readonly string[], b: readonly string[]): boolean {
    return a.length === b.length && a.every((v, i) => v === b[i]);
}

/**
 * The layout a fresh graph opens with when the analyst has not picked one: Layered (dagre LR) draws a big or
 * hub-shaped graph as a thin column, so an Investigation canvas, a graph over `display.layered_max_nodes` nodes or a
 * hub-shaped one opens in Force (d3-force; never force-atlas2, which froze the tab on ~1.6k nodes).
 */
export function defaultLayoutFor(g: G6GraphData | null, onInvestigation: boolean): GraphLayoutId {
    if (!g) return 'dagre';
    if (onInvestigation || g.nodes.length > laLimit('display.layered_max_nodes')) return 'force';
    const deg = new Map<string, number>();
    for (const e of g.edges) {
        deg.set(e.source, (deg.get(e.source) ?? 0) + 1);
        deg.set(e.target, (deg.get(e.target) ?? 0) + 1);
    }
    const max = Math.max(0, ...deg.values());
    return g.nodes.length >= 8 && max >= g.nodes.length / 2 ? 'force' : 'dagre';
}

@Component({
    selector: 'inspecto-link-analysis',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        DatePipe,
        TermHelpComponent,
        FormMenuDirective,
        LinkAnalysisDiscoverComponent,
        LinkAnalysisGlossaryComponent,
        LinkAnalysisDisplayRulesComponent,
        LinkAnalysisCapsComponent,
        LinkAnalysisCleanupComponent,
        LinkAnalysisModulesComponent,
        ChipComponent,
        FormsModule,
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatCheckboxModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatMenuModule,
        MatDividerModule,
        MatSliderModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        LinkAnalysisStartBoxComponent,
        InspectoSkeletonComponent,
        GraphViewComponent,
        DataTableComponent,
        LaHostSlotComponent,
        LinkAnalysisToolboxComponent,
        LinkAnalysisTemporalFindingsComponent,
        LinkAnalysisTimelinePlaybackComponent,
        LinkAnalysisQueryPanelComponent,
        LinkAnalysisLegendComponent,
        LinkAnalysisWorkingSetComponent,
        LinkAnalysisNodeListComponent,
        LinkAnalysisFilterComponent,
        InspectoSplitDirective,
        InspectoOptionPickerComponent,
        LinkAnalysisInvestigationComponent,
        LinkAnalysisExpandComponent,
        LinkAnalysisHeldTrayComponent,
        LinkAnalysisExclusionsComponent,
        LockedActionComponent,
    ],
    // LA-10: the Investigation session outlives the toolbox dock, which destroys its panel on collapse.
    // LA-GROW-EXCLUDE-1: Grow / Held / Exclude state sits beside it, for the same reason.
    providers: [InvestigationSessionStore, GrowExcludeStore],
    templateUrl: './link-analysis.component.html',
    host: {
        '(document:fullscreenchange)': 'onFullscreenChange()',
        '(document:keydown)': 'onHistoryKeydown($event)',
    },
})
export class LinkAnalysisComponent implements OnInit, OnDestroy {
    private fb = inject(FormBuilder);
    /**
     * `bootstrap.features.ops` — cross-entity tags and comments are
     * operational-object edges, so they live in the optional inspecto-ops module
     * (EDITIONS CP-11, EDG-01 cell 7). The menu action is HIDDEN when absent, the
     * geoLink precedent: an affordance that can only 503 is worse than none.
     */
    readonly opsEnabled = inject(LA_FEATURES).ops;
    private toastr = inject(ToastrService);
    private dialog = inject(MatDialog);
    private readonly tags = inject(LA_TAGS);
    /** The host's Import/export menu + draft banner and AI explain, rendered through `<inspecto-la-host-slot>`. */
    readonly transfer = inject(LA_TRANSFER);
    readonly aiExplain = inject(LA_AI_ASSIST).explain;
    readonly transferMenuOutputs = {
        draftImported: (d: LaImportDraft) => void this.onDraftImported(d),
        changed: () => this.reloadViews(),
    };
    readonly transferBannerOutputs = { discard: () => void this.discardDraft() };
    private router = inject(Router);
    private route = inject(ActivatedRoute);
    private destroyRef = inject(DestroyRef);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly injector = inject(Injector);
    private datasetRows = inject(DatasetRowsService);
    /**
     * The Case this analysis was opened from (`/studio/link-analysis?case=<id>`, the link on a Case page),
     * pre-selected in the save dialog. ⚠ Deliberately NOT stripped after use: unlike the `?create=1`
     * handshake this is the address of a Case-scoped investigation, so it has to survive a reload and a
     * bookmark — the same rule the Pipelines `?open=` deep link follows.
     */
    readonly deepLinkedCaseId = toSignal(this.route.queryParamMap.pipe(map((p) => p.get('case') ?? '')), {
        initialValue: '',
    });
    private pivotService = inject(PivotService);
    private graphSources = inject(GraphSourcesService);
    private inv = inject(InvService);
    /** LA-22: the shared Geo ↔ Link brush. */
    private brush = inject(GeoLinkBrushService);
    private spaces = inject(SpacesService);
    private readonly session = inject(SessionService);
    private lens = inject(LensService);
    private exchange = inject(ExchangeService);
    /**
     * Offering is gated on the multi-space Exchange being present AND `canOfferDatasets` — the server gates
     * `POST /exchange/offers` on that same capability (D14: cross-space data exposure is admin-tier), and a
     * shared view exposes its datasets' rows, so it is the same authorization question.
     */
    private readonly exchangeEnabled = inject(LA_FEATURES).exchange;
    readonly canShare = computed(() => this.exchangeEnabled() && this.lens.canOfferDatasets());
    private datasetsService = inject(LA_DATASETS);
    private readonly limits = inject(LinkAnalysisSettingsService);
    /** Installs the Space's Industry packs into the Domain Profile registry (LA-EDITIONS-1). */
    private readonly domainProfiles = inject(DomainProfileService);
    /**
     * Injected for its side effect, not for a value: constructing it loads the active space's limits into the store
     * every limit is read from (`laLimit`). Without an injector asking for it, a `providedIn: 'root'` service is never
     * constructed and the limits would silently stay at the shipped defaults.
     */
    private readonly laLimits = inject(LaLimitsService);
    private pipelinesService = inject(PipelinesService);
    private viewsService = inject(LinkAnalysisService);
    private components = inject(ComponentsService);
    /** Evidence snapshots (UI-first, session-scoped — see the service). */
    readonly snapshots = inject(LinkAnalysisSnapshotsService);
    /** The most recent snapshot of the current graph, if any — what Attach to Case offers first. */
    readonly latestSnapshot = signal<GraphSnapshot | null>(null);

    readonly sources = this.graphSources.sources;

    @ViewChild(GraphViewComponent) private graphView?: GraphViewComponent;
    @ViewChild(LinkAnalysisToolboxComponent) private toolbox?: LinkAnalysisToolboxComponent;
    @ViewChild(LinkAnalysisDiscoverComponent) private discover?: LinkAnalysisDiscoverComponent;
    @ViewChild(LinkAnalysisQueryPanelComponent) private queryPanel?: LinkAnalysisQueryPanelComponent;
    @ViewChild('studioRoot') private studioRoot?: ElementRef<HTMLElement>;
    @ViewChild('canvasZone') private canvasZone?: ElementRef<HTMLElement>;
    @ViewChild('graphRegion') private graphRegion?: ElementRef<HTMLElement>;
    @ViewChild('saveTrigger') private saveTrigger?: MatMenuTrigger;
    @ViewChild('viewsTrigger') private viewsTrigger?: MatMenuTrigger;

    // ── workspace layout: [query dock] │ canvas │ [toolbox dock], Data as a bottom strip ──
    /** Full query form vs its collapsed selected-values summary (auto-collapses after a run). */
    readonly queryOpen = signal(true);
    /** The left dock (Query); collapses to an icon rail. */
    readonly queryDockOpen = signal(true);
    /** The right dock (Analysis | Investigation toolbox); collapses to an icon rail. */
    readonly toolboxDockOpen = signal(initialToolboxOpen());
    private toolboxSeen = false;
    /** Remember an explicit open/close for the session (the initial value is not an explicit choice). */
    private readonly rememberToolbox = effect(() => {
        const open = this.toolboxDockOpen();
        if (!this.toolboxSeen) {
            this.toolboxSeen = true;
            return;
        }
        try {
            sessionStorage.setItem(TOOLBOX_OPEN_KEY, String(open));
        } catch {
            /* storage blocked - the choice just is not remembered */
        }
    });
    readonly toolboxTab = signal<'analysis' | 'investigation'>('analysis');
    @ViewChild('toolboxScroll') private toolboxScroll?: ElementRef<HTMLElement>;
    /** The tabs share one scroll container; a new tab must open at its top, not where the last one was scrolled to. */
    private readonly resetToolboxScroll = effect(() => {
        this.toolboxTab();
        queueMicrotask(() => {
            const el = this.toolboxScroll?.nativeElement;
            if (el) el.scrollTop = 0;
        });
    });
    /**
     * LA-EDITIONS-1 ED-4: the Investigation surface is included here. False on the Explorer level (Basic, or a sign-in
     * install without the Investigations add-on, LA-ONBOARD-1 OB-5), whose Investigation routes answer 503: the tab and its
     * openers stay, and the Investigation actions are shown LOCKED with {@link investigationsLockedReason} (OB-10).
     */
    private readonly laFeatures = inject(LA_FEATURES);
    readonly investigationsEnabled = this.laFeatures.investigations;
    /** Why an Investigation action is locked on the Explorer level (`/bootstrap` `featureReasons.investigations`). */
    readonly investigationsLockedReason = computed(() => investigationsLockedReason(this.laFeatures));
    private readonly allToolboxTabs: readonly { id: 'analysis' | 'investigation'; label: string }[] = [
        { id: 'analysis', label: 'Analysis' },
        { id: 'investigation', label: 'Investigation' },
    ];
    /** The toolbox tablist, in order (WAI-ARIA tabs pattern; the panes are its tabpanels) - the same on every level. */
    get toolboxTabs(): readonly { id: 'analysis' | 'investigation'; label: string }[] {
        return this.allToolboxTabs;
    }
    /** The graph canvas's text alternative: the displayed graph as a keyboard-navigable node / link list. */
    readonly listMode = signal(false);
    /** LA-10: the open Investigation (op log + Working Set); the Investigation tab renders it. */
    readonly investigation = inject(InvestigationSessionStore);
    private readonly cdr = inject(ChangeDetectorRef);
    private readonly graphRuns = inject(GraphRunsService);
    /**
     * Canvas overlays — each minimises to a pill so the graph gets the space. The user's own choice in this browser
     * wins; otherwise the first measurement of the canvas decides ({@link fitOverlaysToWidth}).
     */
    readonly legendOpen = signal(storedOverlayOpen('legend') ?? true);
    readonly workingSetOpen = signal(storedOverlayOpen('workingSet') ?? true);
    /** The canvas zone's last measured width; 0 = not measured yet (no layout, or jsdom), which counts as wide. */
    private canvasWidth = 0;
    /** What the loaded saved view states about the overlays (`undefined` = it does not say). */
    private overlaysStated: { legend?: boolean; workingSet?: boolean } = {};
    /**
     * Keep the canvas usable on a narrow page that has not stacked yet (the stack breakpoint is the VIEWPORT's
     * 960px, so a ~800px page beside an open nav stayed side-by-side and squeezed the canvas to ~15px): when the
     * canvas zone drops below {@link CANVAS_MIN_PX}, collapse the Toolbox dock, then the Query dock, to their rails.
     */
    private readonly watchCanvasWidth = afterNextRender(() => {
        const zone = this.canvasZone?.nativeElement;
        if (!zone || typeof ResizeObserver === 'undefined') return;
        const ro = new ResizeObserver((entries) => {
            const width = entries[0]?.contentRect.width ?? 0;
            this.fitOverlaysToWidth(width);
            this.fitDocksToWidth(width);
        });
        ro.observe(zone);
        this.destroyRef.onDestroy(() => ro.disconnect());
    });

    /**
     * LA-ONBOARD-FIXES-1 (F-11): on a narrow canvas (a laptop's ~800 px) the open legend and Working set covered most
     * of the graph, so below {@link OVERLAY_COLLAPSE_BELOW_PX} they START as their pills. Decided on the FIRST
     * measurement (a later resize never collapses what is open) and again when a saved view is loaded.
     */
    fitOverlaysToWidth(zoneWidth: number): void {
        if (zoneWidth <= 0) return;
        const first = this.canvasWidth === 0;
        this.canvasWidth = zoneWidth;
        if (first) this.applyOverlayStart();
    }

    /**
     * The overlays' starting state: a saved view that states `false` keeps that one collapsed; otherwise the choice this
     * browser remembers, else the width rule. A view's `true` (whoever saved it, on whatever screen) never forces a box
     * open over a narrow canvas.
     */
    private applyOverlayStart(): void {
        const start = (which: CanvasOverlay): boolean =>
            this.overlaysStated[which] === false
                ? false
                : overlayStartsOpen(storedOverlayOpen(which), this.canvasWidth || Number.POSITIVE_INFINITY);
        this.legendOpen.set(start('legend'));
        this.workingSetOpen.set(start('workingSet'));
    }

    /** The user opened or minimised an overlay (its pill / minimise button, or View ▸ Overlays): remembered per browser. */
    setOverlayOpen(which: CanvasOverlay, open: boolean): void {
        (which === 'legend' ? this.legendOpen : this.workingSetOpen).set(open);
        rememberOverlayOpen(which, open);
    }

    /** Collapse one dock when the canvas zone is narrower than {@link CANVAS_MIN_PX} (side-by-side layout only). */
    fitDocksToWidth(zoneWidth: number): void {
        if (zoneWidth <= 0 || zoneWidth >= CANVAS_MIN_PX) return;
        if (window.matchMedia?.('(max-width: 959px)').matches) return; // stacked: the canvas has the full width
        if (this.toolboxDockOpen()) this.toolboxDockOpen.set(false);
        else if (this.queryDockOpen()) this.queryDockOpen.set(false);
    }
    /** The docks give the canvas their width in one step (and take it back). */
    readonly canvasMaximized = computed(() => !this.queryDockOpen() && !this.toolboxDockOpen());

    // ── View toolbox: canvas plugins and behaviors (each a G6 v5 built-in), persisted with a saved view ──
    // Fisheye, Link filter, Snap lines and Hover activate all start OFF (operator 2026-10-26).
    readonly viewOptions = signal<NonNullable<LinkAnalysisViewOptions['plugins']>>({
        minimap: true,
        behaviors: [],
    });
    readonly canvasPlugins = computed<GraphViewPlugins>(
        () => {
            const o = this.viewOptions();
            const mode = this.canvasMode();
            return {
                ...o,
                behaviors: canvasModeOptions(o).behaviors, // no rectangle brush, no second lasso
                // The lenses follow the one Canvas mode (UIR-D3), so a Fisheye drag never fights the lasso.
                fisheye: mode === 'fisheye',
                edgeFilterLens: mode === 'linkFilter',
                hulls: o.hulls ? this.communityHulls() : null,
            };
        },
        // LA-05: both this and `displayOptions` build a fresh object on every evaluation, so without a
        // value comparator the canvas host sees a changed `@Input` whenever any unrelated signal moves.
        { equal: (a, b) => stableKey(a) === stableKey(b) },
    );
    /** Louvain communities of the displayed graph as hull member lists; empty above the analysis cap. */
    private readonly communityHulls = computed<Map<string, string[]>>(() => {
        const g = this.displayed();
        const out = new Map<string, string[]>();
        if (!g) return out;
        // Louvain over the whole graph is the costly part: reuse it for a graph content already seen.
        const sig = `${graphSignature(g)}|${browserAlgorithmCapValue('louvainCommunities')}`; // a changed cap is a different answer
        const hit = this.hullMemo.get(sig);
        if (hit) {
            this.hullMemo.delete(sig); // LRU: re-insert as the newest
            this.hullMemo.set(sig, hit);
            return hit;
        }
        try {
            for (const [node, community] of this.louvainOf(g))
                out.set(community, [...(out.get(community) ?? []), node]);
        } catch {
            return out; // the cap is a refusal (spec §4.1) — no hulls rather than a throw in a template
        }
        if (this.hullMemo.size >= 4) this.hullMemo.delete(this.hullMemo.keys().next().value as string);
        this.hullMemo.set(sig, out);
        return out;
    });
    /** The one Louvain call of the hulls (a seam so a spec can count the runs). */
    private louvainOf(g: G6GraphData): Map<string, string> {
        return louvainCommunities(g);
    }
    /** Community assignment per graph content signature (last few), so returning to a graph already seen costs nothing. */
    private readonly hullMemo = new Map<string, Map<string, string[]>>();

    // ── Canvas modes (LA-STUDIO-UI-REORG-1 §5.2/§5.3, UIR-D2/D3): one owns the drag at a time, Esc returns to Pan ──

    readonly canvasModes = CANVAS_MODES;
    /** Fisheye and Link filter are kept as the saved view's plugin flags; Lasso is a session-only mode. */
    private readonly lassoMode = signal(false);
    readonly canvasMode = computed<CanvasMode>(() => {
        const o = this.viewOptions();
        return o.fisheye ? 'fisheye' : o.edgeFilterLens ? 'linkFilter' : this.lassoMode() ? 'lasso' : 'pan';
    });

    setCanvasMode(mode: CanvasMode): void {
        this.lassoMode.set(mode === 'lasso');
        this.viewOptions.update((o) => ({ ...o, fisheye: mode === 'fisheye', edgeFilterLens: mode === 'linkFilter' }));
    }

    toggleViewFlag(
        flag:
            | 'minimap'
            | 'gridLine'
            | 'edgeBundling'
            | 'hulls'
            | 'bubbleSets'
            | 'snapline',
        on: boolean,
    ): void {
        this.viewOptions.update((o) => ({ ...o, [flag]: on }));
    }

    toggleBehavior(id: NonNullable<GraphViewPlugins['behaviors']>[number], on: boolean): void {
        this.viewOptions.update((o) => {
            const rest = (o.behaviors ?? []).filter((b) => b !== id);
            return { ...o, behaviors: on ? [...rest, id] : rest };
        });
    }

    hasBehavior(id: NonNullable<GraphViewPlugins['behaviors']>[number]): boolean {
        return (this.viewOptions().behaviors ?? []).includes(id);
    }

    // ── query source (the form itself lives in the query-panel child) ──
    readonly sourceId = signal<GraphSourceId>('entity-projection');
    readonly datasets = signal<LaDataset[]>([]);
    readonly pipelines = signal<PipelineSummary[]>([]);

    // ── result state ──
    readonly loading = signal(false);
    readonly loadError = signal('');
    readonly graph = signal<G6GraphData | null>(null);
    readonly truncated = signal(false);
    /** The query behind the rendered graph — what the collapsed form + status bar summarize. */
    /** `idMappings`: what the run minted node ids with (D-M6 column types resolved) — brush + path hops reuse it. */
    readonly lastRun = signal<{
        sourceId: GraphSourceId;
        query: GraphSourceQuery;
        idMappings?: EntityIdMapping[];
    } | null>(null);
    /** LA-08: each mapping's row count + own `truncated`, from a multi-Dataset projection (empty otherwise). */
    readonly mappingSummary = signal<MultiProjectionMappingSummary[]>([]);
    /** LA-11: the last server traversal, mapped onto the graph; reset with every fresh graph. */
    readonly serverPaths = signal<ServerPathsState | null>(null);
    readonly serverPathsBusy = signal(false);
    /** DR-U4: where the last incremental expand read its neighbours from (the link index or the Dataset); null = none yet. */
    readonly expandSource = signal<IndexSource | null>(null);
    readonly expandSourceNote = computed(() => indexSourceNote(this.expandSource()));
    /** LA-14b: the last whole-Dataset branching pattern search. */
    readonly serverPattern = signal<ServerPatternState | null>(null);
    readonly serverPatternBusy = signal(false);
    /** LA-INVESTIGATION-OPS-DEFERRED-1: the last burst / periodicity scan, placed on the canvas (never added to it). */
    readonly temporalFindings = signal<TemporalFindingsState | null>(null);
    readonly temporalBusy = signal(false);
    readonly temporalError = signal('');
    /** The scan the shown findings came from (what `sealTemporal` sends), and what the last seal answered. */
    private lastTemporalRun: TemporalRunRequest | null = null;
    readonly temporalSealed = signal<{
        step: number;
        count: number;
        outsideWorkingSet: number;
        fingerprint: string;
    } | null>(null);
    readonly temporalSealError = signal('');

    /**
     * LA-11: the edge mappings of the loaded query a server traversal can walk — one per single-Dataset mapping
     * (with its `entityType`, so path ids match the drawn ones) or per LA-08 edge mapping (unscoped, as that
     * projection mints them). The walk takes one `filter`: the pushed predicate, AND a mapping's own.
     */
    readonly traversalTargets = computed(() => {
        const run = this.lastRun();
        if (!run) return [];
        const q = run.query;
        const dsName = (id: string) => this.datasets().find((d) => d.id === id)?.name ?? id;
        if (run.sourceId === 'entity-projection') {
            const ps = q.projections?.length ? q.projections : q.projection ? [q.projection] : [];
            return ps.map((p) => ({
                dataset: p.datasetId,
                sourceCol: p.sourceCol,
                targetCol: p.targetCol,
                entityType: p.entityType,
                idMappings: [p] as (EntityIdMapping | undefined)[],
                linkKindCol: p.linkKindCol || undefined,
                filter: q.filter,
                label: `${dsName(p.datasetId)}: ${p.sourceCol} → ${p.targetCol}`,
            }));
        }
        if (run.sourceId === 'entity-projection-multi') {
            return (q.multi?.edges ?? []).map((e) => ({
                dataset: e.dataset,
                sourceCol: e.sourceColumn,
                targetCol: e.targetColumn,
                entityType: undefined,
                idMappings: (run.idMappings ?? []) as (EntityIdMapping | undefined)[],
                linkKindCol: undefined,
                filter:
                    q.filter && e.filter
                        ? { kind: 'group' as const, op: 'AND' as const, items: [q.filter, e.filter] }
                        : (e.filter ?? q.filter),
                label: `${dsName(e.dataset)}: ${e.sourceColumn} → ${e.targetColumn}`,
            }));
        }
        return [];
    });
    /** DR-U5: the same edge mappings, as the Edge index tool judges them (served from an index, or flat). */
    readonly indexMappings = computed<IndexMappingTarget[]>(() =>
        this.traversalTargets().map((t) => ({
            dataset: t.dataset,
            sourceCol: t.sourceCol,
            targetCol: t.targetCol,
            kindCol: t.linkKindCol,
            label: t.label,
        })),
    );
    readonly traversalMappingOptions = computed<PickerOption[]>(() =>
        this.traversalTargets().map((t, i) => ({ value: String(i), label: t.label })),
    );

    readonly sourceLabel = computed<string>(() => {
        const run = this.lastRun();
        return run ? (this.sources.find((s) => s.id === run.sourceId)?.label ?? run.sourceId) : '';
    });
    readonly querySummary = computed<QuerySummaryItem[]>(() => {
        const run = this.lastRun();
        if (!run) return [];
        const q = run.query;
        switch (run.sourceId) {
            case 'entity-projection': {
                if (q.projections?.length) {
                    return q.projections.map((p) => {
                        const ds = this.datasets().find((d) => d.id === p.datasetId);
                        return {
                            icon: 'heroicons_outline:table-cells',
                            label: p.entityType ?? 'Mapping',
                            value: `${ds?.name ?? p.datasetId}: ${p.sourceCol} → ${p.targetCol}`,
                        };
                    });
                }
                const p = q.projection;
                if (!p) return [];
                const ds = this.datasets().find((d) => d.id === p.datasetId);
                const items: QuerySummaryItem[] = [
                    { icon: 'heroicons_outline:table-cells', label: 'Dataset', value: ds?.name ?? p.datasetId },
                    {
                        icon: 'heroicons_outline:arrow-long-right',
                        label: 'Mapping',
                        value: `${p.sourceCol} → ${p.targetCol}`,
                    },
                ];
                if (p.linkKindCol)
                    items.push({ icon: 'heroicons_outline:hashtag', label: 'Link type', value: p.linkKindCol });
                if (p.attrCols?.length)
                    items.push({ icon: 'heroicons_outline:tag', label: 'Attributes', value: p.attrCols.join(', ') });
                return items;
            }
            case 'entity-projection-multi': {
                const name = (id: string) => this.datasets().find((d) => d.id === id)?.name ?? id;
                return [
                    ...(q.multi?.nodes ?? []).map((m) => ({
                        icon: 'heroicons_outline:identification',
                        label: m.category ?? 'Nodes',
                        value: `${name(m.dataset)}: ${m.idColumn}`,
                    })),
                    ...(q.multi?.edges ?? []).map((m) => ({
                        icon: 'heroicons_outline:arrow-long-right',
                        label: m.type ?? 'Links',
                        value: `${name(m.dataset)}: ${m.sourceColumn} → ${m.targetColumn}`,
                    })),
                ];
            }
            case 'lineage':
                return [
                    {
                        icon: 'heroicons_outline:viewfinder-circle',
                        label: q.roots?.length ? 'Roots' : 'Root',
                        value: q.roots?.length ? q.roots.join(', ') : q.from || 'whole graph',
                    },
                    { icon: 'heroicons_outline:hashtag', label: 'Depth', value: String(q.depth ?? 2) },
                    {
                        icon: 'heroicons_outline:arrows-right-left',
                        label: 'Direction',
                        value: q.direction === 'in' ? 'Upstream' : q.direction === 'out' ? 'Downstream' : 'Both',
                    },
                ];
            case 'provenance': {
                const items: QuerySummaryItem[] = [
                    {
                        icon: 'heroicons_outline:queue-list',
                        label: q.roots?.length ? 'Pipelines' : 'Pipeline',
                        value: q.roots?.length ? q.roots.join(', ') : (q.from ?? ''),
                    },
                ];
                if (q.counts)
                    items.push({
                        icon: 'heroicons_outline:chart-bar',
                        label: 'Edges',
                        value: 'weighted by record counts',
                    });
                return items;
            }
            default:
                return [{ icon: 'heroicons_outline:share', label: 'Scope', value: 'every registered component' }];
        }
    });

    // ── search & kind filtering ──
    readonly search = signal('');
    readonly kindFilter = signal<string[]>([], { equal: sameStrings }); // empty = all
    readonly nodeKinds = computed<string[]>(() => {
        const g = this.investigation.canvas() ?? this.graph();
        return g ? [...new Set(g.nodes.map((n) => n.data.kind))].sort() : [];
    });
    readonly edgeKinds = computed<string[]>(() => legendEdgeKindsFor(this.investigation.canvas() ?? this.graph()));
    /** Collapsed branch roots — their downstream nodes are hidden until expanded. */
    readonly collapsedRoots = signal<string[]>([], { equal: sameStrings });

    // ── timeline filter (BACKLOG V2 §3: a time slider filtering edges by a temporal `attrs` column) ──
    /** The `attrs` column to filter on; '' = timeline off. */
    readonly timeColumn = signal('');
    /** The timeline picker's choices — a blank-valued 'Off' is the named no-column choice, not unset. */
    readonly timeColumnOptions = computed<PickerOption[]>(() => [
        { value: '', label: 'Off' },
        ...this.attrColumns().map((c) => ({ value: c, label: c })),
    ]);
    /**
     * The default event time of a new Investigation: the timeline's column when one is on, else the profile's
     * time hint, else the first edge-attribute column whose every value parses as a date (what the working-set
     * tiles use). '' when the loaded graph carries none.
     */
    readonly investigationTimeColumn = computed(() => {
        const g = this.graph();
        if (this.timeColumn() || !g) return this.timeColumn();
        const cols = this.attrColumns();
        return workingSetOptionsFor(this.profile(), cols).timeColumn ?? cols.find((c) => isTemporalColumn(g, c)) ?? '';
    });
    /**
     * {@link investigationTimeColumn}. Never the picked Dataset's `date` column: the client type is coarse (DATE and
     * TIMESTAMP both read 'date') and the server refuses a DATE as event time (LA-EXPLORE-TIMECOL-DATE-1), so a
     * Dataset-declared guess made the first Start Investigation press fail. Blank = no event time; the analyst picks one.
     */
    investigationTimeDefault(): string {
        return this.investigationTimeColumn();
    }
    /** Cutoff (epoch millis) — edges dated after this are hidden; `null` until the slider is touched. */
    readonly timeCutoff = signal<number | null>(null);
    /** Every edge-`attrs` key present anywhere in the loaded graph — the column picker's options. */
    readonly attrColumns = computed<string[]>(() => {
        const g = this.graph();
        if (!g) return [];
        return [...new Set(g.edges.flatMap((e) => Object.keys(e.data.attrs ?? {})))].sort();
    });
    /** [min, max] epoch millis of every parseable date in the selected column — the slider's rail. */
    readonly timeExtent = computed<[number, number] | null>(() => {
        const g = this.graph();
        const col = this.timeColumn();
        if (!g || !col) return null;
        const times = g.edges.map((e) => Date.parse(e.data.attrs?.[col] ?? '')).filter((t) => Number.isFinite(t));
        return times.length ? [Math.min(...times), Math.max(...times)] : null;
    });
    /** The kind- and time-filtered graph BEFORE branch collapsing (collapse/expand decisions read this). */
    private readonly baseGraph = computed<G6GraphData | null>(() => {
        const g = this.graph();
        if (!g) return null;
        const kindFiltered = filterByKinds(g, this.kindFilter(), []);
        const lf = this.localFilter();
        const predicateFiltered = lf ? filterEdgesByPredicate(kindFiltered, lf, this.endpointColumns()) : kindFiltered;
        const col = this.timeColumn();
        const cutoff = this.timeCutoff();
        return col && cutoff != null ? filterByTime(predicateFiltered, col, cutoff) : predicateFiltered;
    });

    // ── the two-stage query loop (spec §3.7 / plan S1.4): one predicate tree, two evaluators ──
    /** The tree being edited (deep-cloned on apply — the editor mutates in place). */
    readonly filterWhere = signal<ConditionGroup>(emptyGroup());
    /** Stage 1: the predicate currently applied to the working set in the browser; null = none. */
    readonly localFilter = signal<ConditionGroup | null>(null);
    /** Stage 2 status line for the panel. */
    readonly pushState = signal('not pushed');
    readonly pushing = signal(false);
    /** The projection's endpoint column names — the tree's node conditions are ordinary leaves on these. */
    readonly endpointColumns = computed<EndpointColumns>(() => {
        const p = this.lastRun()?.query.projection;
        return { sourceCol: p?.sourceCol || 'source', targetCol: p?.targetCol || 'target' };
    });
    readonly filterColumns = computed(() => {
        const g = this.graph();
        return g ? predicateColumns(g, this.endpointColumns()) : [];
    });
    /** What the tree as edited would keep of the loaded links — live, before Apply. */
    readonly localMatch = computed<LocalMatch | null>(() => {
        const g = this.graph();
        if (!g) return null;
        const w = this.filterWhere();
        const matched = hasConditions(w)
            ? filterEdgesByPredicate(g, w, this.endpointColumns()).edges.length
            : g.edges.length;
        return { matched, total: g.edges.length };
    });
    readonly strandedCount = computed(() => this.graph()?.nodes.filter((n) => n.data.missing).length ?? 0);
    /**
     * LA-06 clause 3 — fold a hub's pendant leaves into one super-node once there are at least this
     * many. `0` is off, and off is the default: the transform changes what the canvas MEANS, so it is
     * the analyst's call, not a silent optimisation. ⚠ Only true pendants fold, so no path is ever lost.
     */
    readonly superNodeThreshold = signal(0);
    /** Hubs the analyst has expanded again; their leaves stay individual. */
    readonly expandedSuperHubs = signal<string[]>([]);
    /** The collapsed graph BEFORE super-node folding — what the analyst actually has in hand. */
    private readonly collapsedGraph = computed<G6GraphData | null>(() => {
        const g = this.baseGraph();
        return g ? foldBranches(g, this.collapsedRoots()) : null;
    });
    readonly displayed = computed<G6GraphData | null>(() => {
        const g = this.collapsedGraph();
        if (!g) return null;
        const threshold = this.superNodeThreshold();
        return threshold >= 2 ? aggregateSuperNodes(g, threshold, this.expandedSuperHubs()) : g;
    });
    /** Link kinds the legend's chips hide on the canvas — client-side, on the query graph and the Working Set alike. */
    readonly hiddenLinkKinds = signal<string[]>([]);
    /** An open Investigation's Working Set, else the query graph — before the legend's link-kind hiding. */
    private readonly canvasSource = computed<G6GraphData | null>(() => {
        const inv = this.investigation.canvas();
        // §5.5: Collapse is display state on the Working Set too — no op, the Working Set is unchanged.
        if (inv) return foldBranches(inv, this.collapsedRoots());
        const g = this.displayed();
        const done = this.expandedNodeIds();
        // LA-UNEXPANDED-MARKER-1 (4): without an Investigation, the merged expand history is the browser's own record. Marked
        // only once a node has been expanded, so a fresh query result is not dotted all over.
        if (!g || !done.length) return g;
        const seen = new Set(done);
        return { ...g, nodes: g.nodes.map((n) => (seen.has(n.id) ? n : { ...n, data: { ...n.data, unexpanded: true } })) };
    });
    /** Ids of the exploration canvas nodes an expand has followed since the last run (no Investigation: nothing is sealed). */
    readonly expandedNodeIds = signal<string[]>([], { equal: sameStrings });
    /** The link kinds the legend offers as chips: those of what the canvas draws, hidden ones included. */
    readonly legendLinkKinds = computed(() => legendEdgeKindsFor(this.canvasSource()));
    /** Nodes the node detail dialog's "Hide on canvas" took off the exploration canvas (presentation only, never a query). */
    readonly hiddenNodeIds = signal<string[]>([], { equal: sameStrings });
    /** What the canvas draws: {@link canvasSource} without the hidden link kinds and the hidden nodes (with their links). */
    readonly canvasData = computed<G6GraphData | null>(() => {
        const g = hideLinkKinds(this.canvasSource(), this.hiddenLinkKinds());
        const hidden = new Set(this.hiddenNodeIds());
        // LA-UNEXPANDED-MARKER-1 (b): "Only unexpanded" keeps just the frontier (and the picked node, so a pick never vanishes)
        if (g && this.onlyUnexpanded()) {
            const keep = g.nodes.filter((n) => n.data.unexpanded || n.id === this.investigation.selected()?.id);
            if (keep.some((n) => n.data.unexpanded))
                for (const n of g.nodes) if (!keep.includes(n)) hidden.add(n.id);
        }
        if (!g || !hidden.size) return this.markHeld(g);
        return this.markHeld({
            ...g,
            nodes: g.nodes.filter((n) => !hidden.has(n.id)),
            edges: g.edges.filter((e) => !hidden.has(e.source) && !hidden.has(e.target)),
        });
    });
    /** The legend's "Only unexpanded" switch: draw just the Working Set's unexpanded frontier. */
    readonly onlyUnexpanded = signal(false);

    /** "Next unexpanded": pick the next unexpanded Working Set node (by id, wrapping) so Expand one hop acts on it. */
    selectNextUnexpanded(): void {
        const frontier = (this.canvasSource()?.nodes ?? [])
            .filter((n) => n.data.unexpanded)
            .sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
        if (!frontier.length) return;
        const at = frontier.findIndex((n) => n.id === this.investigation.selected()?.id);
        const next = frontier[(at + 1) % frontier.length];
        if (this.investigation.canvas()) this.investigation.selected.set(next);
        this.focusNode(next.id);
    }

    toggleLinkKind(kind: string): void {
        this.hiddenLinkKinds.update((hidden) => toggleHiddenKind(hidden, kind));
    }
    /** True while the canvas draws an open Investigation's Working Set, not the query graph. */
    readonly workingSetCanvas = computed(() => this.investigation.canvas() !== null);
    /** Why the query-graph-only tools are disabled on an Investigation canvas (they would silently do nothing). */
    readonly queryGraphOnlyReason = 'Applies to the query graph — the Investigation canvas draws its Working Set.';
    /** Working Set links the canvas left off at the render ceiling (0 = all drawn). */
    readonly omittedLinks = computed(() => this.investigation.canvas()?.omittedLinks ?? 0);
    /**
     * LA-10: an Investigation binds ONE Dataset + source/target columns, so it can start only from a last run
     * of a single Entity/Link mapping. `investigationIssue` says why not otherwise.
     */
    readonly investigationProjection = computed<EntityProjection | null>(() => {
        const run = this.lastRun();
        return run?.sourceId === 'entity-projection' && run.query.projection && !run.query.projections?.length
            ? run.query.projection
            : null;
    });
    readonly investigationIssue = computed(() =>
        this.lastRun()?.query.projections?.length
            ? 'An Investigation binds one Dataset mapping — this query has several. Run a single Entity/Link mapping.'
            : 'Run an Entity/Link query over one Dataset first — the Investigation binds that Dataset and its source and target columns.',
    );
    /** True while any super-node is on the canvas — the footer says so, since counts then differ. */
    readonly hasSuperNodes = computed(
        () => this.displayed()?.nodes.some((n) => n.data.kind === SUPER_NODE_KIND) ?? false,
    );

    /**
     * Turn grouping on or off. Re-enabling CLEARS the expanded set, which is the analyst's way back:
     * once a group is opened its stand-in is gone from the canvas, so there is nothing left to click to
     * re-fold it. Off-then-on is the reset.
     */
    setSuperNodeGrouping(on: boolean): void {
        this.expandedSuperHubs.set([]);
        this.superNodeThreshold.set(on ? 10 : 0);
    }

    /** Expand one super-node back into its members (or re-fold an expanded hub). */
    toggleSuperNode(nodeId: string): void {
        const hub = nodeId.startsWith('__super__:') ? nodeId.slice('__super__:'.length) : nodeId;
        this.expandedSuperHubs.update((h) => (h.includes(hub) ? h.filter((x) => x !== hub) : [...h, hub]));
    }

    // ── display options (persisted with a saved view; applied on load) ──
    /** Node and link labels start OFF (operator 2026-10-15): a fresh graph is the shapes; labels are one checkbox away. */
    readonly nodeLabels = signal(false);
    readonly edgeLabels = signal(false);
    /** Override the density rule: label every link even on a dense graph (persisted as `display.allEdgeLabels`). */
    readonly allEdgeLabels = signal(false);
    readonly denseEdgeLabelCap = DENSE_EDGE_LABEL_CAP;
    /** True while the density rule is hiding link labels (drives the toolbox hint). */
    readonly edgeLabelsDense = computed(() =>
        edgeLabelsHiddenByDensity(this.displayOptions(), this.displayed()?.edges.length ?? 0),
    );
    readonly nodeColors = signal<Record<string, string>>({});
    readonly edgeColors = signal<Record<string, string>>({});
    readonly nodeShapes = signal<Record<string, string>>({});
    readonly edgePatterns = signal<Record<string, EdgePattern>>({});
    readonly edgeSizes = signal<Record<string, number>>({});
    /** Conditional formatting: ordered attribute-predicate → node-colour rules (first match wins); saved with a view. */
    readonly nodeRules = signal<NodeRule[]>([]);
    /** Every node-`attrs` key present in the drawn graph — the rule builder's attribute options. */
    readonly nodeAttributeKeys = computed<string[]>(() => {
        const g = this.investigation.canvas() ?? this.graph();
        return g ? [...new Set(g.nodes.flatMap((n) => Object.keys(n.data.attrs ?? {})))].sort() : [];
    });
    // ── level of detail (mockup review point 5): labels are the first thing to go on a big canvas ──
    // Starts OFF (operator 2026-10-26), like Community hulls: switch it on to have labels dropped on a big canvas.
    readonly levelOfDetail = signal(false);
    /** Above this many displayed nodes, labels are dropped while level of detail is on (`display.lod_label_cap`). */
    readonly lodLabelCap = computed(() => laLimit('display.lod_label_cap'));
    readonly lodLabelsOff = computed(
        () => this.levelOfDetail() && (this.displayed()?.nodes.length ?? 0) > this.lodLabelCap(),
    );
    /** The published size limits the footer states — the server-side projection cap and the browser analysis cap. */
    /**
     * The limits currently IN FORCE, read live rather than captured, so a deployment's override reaches
     * the footer (both read the limits store, a signal, so the template re-renders when they change).
     */
    get caps(): { projection: number; analysis: number } {
        return { projection: projectionNodeCapValue(), analysis: analysisNodeCapValue() };
    }

    /** A flagged node's Connectivity row — the same threshold, count and override state the Investigation panel shows. */
    hubBadgeFor(serverId: string, contacts?: number | null): string {
        return this.investigation.hubBadge(serverId, contacts, this.limits.limits().hubThresholdInForce);
    }

    /** D-4 step 7: the server's hard ceiling on a graph run (null until `GET /inv/graph/algorithms` answers). */
    readonly serverRunCeiling = computed(() => this.graphRuns.catalogue()?.ceilings?.maxNodes ?? null);
    /** The server's per-algorithm `inlineNodeCeiling`, by algorithm id - what lowers the selection algorithms' browser threshold. */
    readonly serverAlgorithmCeilings = computed(() => {
        const algorithms = this.graphRuns.catalogue()?.algorithms;
        return algorithms ? Object.fromEntries(algorithms.map((a) => [a.id, a.inlineNodeCeiling])) : null;
    });
    /** A server run is over the Working Set, so it needs an open Investigation AND the canvas drawing that set. */
    readonly graphRunInvestigationId = computed(() =>
        this.investigation.canvas() ? this.investigation.activeId() : null,
    );
    /** Nodes in the Working Set the canvas draws (hidden ones left out), or null while the canvas shows a query graph. */
    readonly workingSetNodeCount = computed(() => {
        const ws = this.investigation.workingSet();
        return this.investigation.canvas() && ws ? ws.entities.filter((e) => !e.hidden).length : null;
    });
    readonly canRunGraphOnServer = computed(() => this.lens.canRunLinkGraphAnalysis());
    readonly canBuildLinkIndex = computed(() => this.lens.canBuildLinkIndex());
    /** The server's node/edge ids translated to the canvas's, rebuilt whenever the Working Set changes. */
    readonly serverIds = computed(() => {
        const ws = this.investigation.workingSet();
        const canvas = this.investigation.canvas();
        return ws && canvas ? buildServerIdMap(ws, this.investigation.binding() ?? undefined, canvas) : null;
    });

    /** Node size in px: small by default, never scaled by degree or value; changed only from the Display menu. */
    readonly nodeSize = signal(GRAPH_DEFAULT_NODE_SIZE);
    /** How far the layout spreads nodes (a multiplier on its distances); wide by default; changed from the Display menu. */
    readonly layoutSpacing = signal(GRAPH_DEFAULT_LAYOUT_SPACING);

    /**
     * What the analyst chose, and only that: this is what a saved view keeps. The automatic look ({@link displayOptions})
     * is derived from the drawn graph every time, so it is never written into a view.
     */
    readonly chosenDisplay = computed<GraphDisplayOptions>(
        () => ({
            nodeLabels: this.nodeLabels() && !this.lodLabelsOff(),
            edgeLabels: this.edgeLabels() && !this.lodLabelsOff(),
            ...(this.allEdgeLabels() ? { allEdgeLabels: true } : {}),
            nodeColors: this.nodeColors(),
            edgeColors: this.edgeColors(),
            nodeShapes: this.nodeShapes(),
            edgePatterns: this.edgePatterns(),
            edgeSizes: this.edgeSizes(),
            ...(this.nodeRules().length ? { nodeRules: this.nodeRules() } : {}),
            ...(this.nodeSize() !== GRAPH_DEFAULT_NODE_SIZE ? { nodeSize: this.nodeSize() } : {}),
            ...(this.layoutSpacing() !== GRAPH_DEFAULT_LAYOUT_SPACING ? { layoutSpacing: this.layoutSpacing() } : {}),
        }),
        { equal: (a, b) => stableKey(a) === stableKey(b) },
    );

    /**
     * The automatic colour, shape and line pattern per node type and link type of the graph the canvas DRAWS from (an
     * open Investigation's Working Set, else the query graph; before the legend hides a link kind, so hiding one does not
     * re-colour the others). The most common of each stays neutral. It sits UNDER the analyst's own per-kind choices.
     */
    readonly autoStyleOf = computed(() => autoStyle(this.canvasSource()), {
        equal: (a, b) => stableKey(a) === stableKey(b),
    });

    /** What the canvas and the legend are given: the analyst's choices over the automatic look, with the size and spacing. */
    readonly displayOptions = computed<GraphDisplayOptions>(
        () => {
            const chosen = this.chosenDisplay();
            const auto = this.autoStyleOf();
            return {
                ...chosen,
                nodeColors: { ...auto.nodeColors, ...chosen.nodeColors },
                edgeColors: { ...auto.edgeColors, ...chosen.edgeColors },
                nodeShapes: { ...auto.nodeShapes, ...chosen.nodeShapes },
                edgePatterns: { ...auto.edgePatterns, ...chosen.edgePatterns },
                nodeSize: this.nodeSize(),
                layoutSpacing: this.layoutSpacing(),
                layoutCaps: laLayoutCaps(),
            };
        },
        { equal: (a, b) => stableKey(a) === stableKey(b) },
    );
    readonly swatches = ICON_COLOR_SWATCHES;
    readonly shapeOptions = GRAPH_NODE_SHAPES;
    readonly patternOptions = GRAPH_EDGE_PATTERNS;
    readonly sizeOptions = GRAPH_EDGE_SIZES;
    readonly nodeSizeOptions = GRAPH_NODE_SIZES;
    readonly spacingOptions = GRAPH_LAYOUT_SPACINGS;
    /** True when any display option deviates from the defaults (tints the paint-brush button). */
    readonly displayCustomized = computed<boolean>(
        () =>
            this.nodeLabels() ||
            this.edgeLabels() ||
            this.allEdgeLabels() ||
            this.nodeSize() !== GRAPH_DEFAULT_NODE_SIZE ||
            this.layoutSpacing() !== GRAPH_DEFAULT_LAYOUT_SPACING ||
            Object.keys(this.nodeColors()).length > 0 ||
            Object.keys(this.edgeColors()).length > 0 ||
            Object.keys(this.nodeShapes()).length > 0 ||
            Object.keys(this.edgePatterns()).length > 0 ||
            Object.keys(this.edgeSizes()).length > 0 ||
            this.nodeRules().length > 0,
    );

    // ── layout (the Layout toolbox; persisted with a saved view) ──
    /** The analyst's explicit layout (toolbox pick, undo, saved view); null = none, so {@link defaultLayoutFor} decides. */
    readonly pickedLayout = signal<GraphLayoutId | null>(null);
    readonly layoutId = computed<GraphLayoutId>(() => {
        const picked = this.pickedLayout();
        const g = this.canvasData();
        // Radial, Information density and Clustering force (force-atlas2) block the tab for 20 s to minutes on ~800 nodes:
        // above their cap they are disabled in the menus (UIR-D11); a pick restored over it (saved view / undo / the
        // graph grew) draws the default layout, and {@link layoutCapNotice} says why.
        if (!picked || layoutOverCapReason(picked, g?.nodes.length ?? 0))
            return defaultLayoutFor(g, this.investigation.canvas() !== null);
        return picked;
    });
    /** Why a layout button is disabled on this graph (tree shape or node cap); null when it can be picked. */
    layoutDisabledReason(l: { id: GraphLayoutId; tree?: boolean }): string | null {
        if (l.tree && !this.isTreeShaped()) return 'needs a tree-shaped graph';
        return layoutOverCapReason(l.id, this.canvasData()?.nodes.length ?? 0);
    }
    /** Some layout is off for its node cap: the menus add a "reduce first" hint. */
    readonly anyLayoutOverCap = computed(() => {
        const n = this.canvasData()?.nodes.length ?? 0;
        return GRAPH_LAYOUTS.some((l) => layoutOverCapReason(l.id, n) !== null);
    });
    /** Why Community hulls are off on this graph: Louvain over its browser cap draws none. */
    readonly hullsDisabledReason = computed(() =>
        overCapReason(browserAlgorithmCapValue('louvainCommunities'), this.displayed()?.nodes.length ?? 0),
    );
    /** "Reduce first": open the Filter editor in the data strip, so the analyst can cut the graph under a cap. */
    openReduce(): void {
        this.bottomOpen.set(true);
        this.filterOpen.set(true);
    }
    /** On-screen notice while a pick is shown in the default layout because the graph is over that layout's cap. */
    readonly layoutCapNotice = computed<string | null>(() =>
        layoutCapNoticeText(this.pickedLayout(), this.canvasData()?.nodes.length ?? 0),
    );
    readonly layouts = GRAPH_LAYOUTS;
    /** Whether the current graph is tree/forest-shaped — gates the tree layouts. */
    readonly isTreeShaped = computed<boolean>(() => {
        const g = this.displayed();
        return !!g && isForest(g);
    });

    // ── fullscreen (whole studio or just the canvas zone) + the Data strip below the workspace ──
    readonly fullscreen = signal<'app' | 'graph' | null>(null);
    readonly bottomOpen = signal(false);
    /** The Filter predicate editor inside the data strip; collapsed so the strip stays short. */
    readonly filterOpen = signal(false);
    readonly tableMode = signal<'links' | 'nodes'>('links');

    // ── canvas overlays: legend (kind → colour → count) and the working set tiles ──
    // The legend names the colour the canvas paints: the analyst's choice, else the automatic one.
    readonly legendItems = computed<LegendItem[]>(() =>
        legendItemsFor(this.displayed(), this.displayOptions().nodeColors),
    );
    /** Entity Type id → its registry label, for the legend rows; empty until (and unless) the registry answers. */
    readonly entityTypeLabels = signal<Record<string, string>>({});
    private entityTypeLabelsAsked = false;
    /** Read the Entity Types registry once, the first time an Investigation canvas draws typed nodes. */
    private readonly loadEntityTypeLabels = effect(() => {
        if (this.entityTypeLabelsAsked || !this.investigation.canvas()?.nodes.some((n) => !!n.data.entityType)) return;
        this.entityTypeLabelsAsked = true;
        try {
            this.injector
                .get(EntityProfileService)
                .entityTypes()
                .pipe(takeUntilDestroyed(this.destroyRef))
                .subscribe({
                    next: (l) => this.entityTypeLabels.set(Object.fromEntries((l?.items ?? []).map((t) => [t.id, t.label]))),
                    error: () => undefined, // the legend falls back to the pack's term, else the id
                });
        } catch {
            // no registry in this host: the legend keeps the pack's term, else the id
        }
    });
    /** Drawn Working Set entities flagged high connectivity — the legend's ringed-node row. */
    readonly hubsDrawn = computed(
        () => this.investigation.canvas()?.nodes.filter((n) => n.data.highConnectivity).length ?? 0,
    );
    /** Drawn Working Set entities no expand has followed yet (LA-UNEXPANDED-MARKER-1) — the legend's dotted-outline row. */
    readonly unexpandedDrawn = computed(
        () => this.canvasSource()?.nodes.filter((n) => n.data.unexpanded).length ?? 0,
    );
    // ── domain profile: what the tiles are called, which columns are measure/time, which tools are suggested ──
    readonly profileControl = new FormControl<DomainProfileId>('generic', { nonNullable: true });
    /** `generic` plus every Industry pack the server installed (reactive: the packs arrive after the first render). */
    readonly profileOptions = computed(() =>
        domainProfiles().map((p) => ({ value: p.id, label: p.label, hint: p.description })),
    );
    readonly profileId = toSignal(this.profileControl.valueChanges, { initialValue: this.profileControl.value });
    readonly profile = computed(() => domainProfile(this.profileId()));
    /** A reloaded / adopted Investigation brings back the domain profile it was opened under. */
    /** LA-SEED-CONNECT-1 (b): a clicked Connect pair row emphasises its route's nodes and the links between consecutive ones. */
    private readonly showConnectRoute = effect(() => {
        const route = this.investigation.highlightRoute();
        const g = untracked(() => this.canvasSource());
        if (!route || !g) return;
        const idOf = (raw: string) => g.nodes.find((n) => rawIdsOf(n).includes(raw) || n.id === raw)?.id;
        const ids = route.map(idOf).filter((x): x is string => !!x);
        const pair = new Set(ids.slice(1).map((b, i) => [ids[i], b].sort().join('|')));
        this.emphasis.set({
            nodeIds: ids,
            edgeIds: g.edges.filter((e) => pair.has([e.source, e.target].sort().join('|'))).map((e) => e.id),
        });
    });
    /**
     * The Space's selected Domain Profile is the default reading: applied once, when the packs arrive, unless a view, a
     * case or an Investigation has already chosen a profile (the control then no longer reads `generic`).
     */
    private spaceProfileApplied = false;
    private readonly applySpaceProfile = effect(() => {
        const id = activeDomainProfileId();
        if (this.spaceProfileApplied || id === 'generic') return;
        this.spaceProfileApplied = true;
        untracked(() => {
            if (this.profileControl.value === 'generic') this.profileControl.setValue(id);
        });
    });
    private readonly restoreBoundProfile = effect(() => {
        const p = this.investigation.boundProfileId();
        if (p && p !== this.profileControl.value) this.profileControl.setValue(p);
    });
    readonly workingSet = computed(() => {
        const g = this.graph();
        // ⚠ Measured on the PRE-aggregation graph on purpose. A super-node folds N real entities into one
        // mark, so counting the displayed graph would report "43 nodes" for a working set of 240 — the
        // tiles answer "how much am I looking at", never "how many shapes are on the canvas".
        return workingSetStats(this.collapsedGraph(), g, g ? workingSetOptionsFor(this.profile(), attrColumns(g)) : {});
    });
    /**
     * Identities whose raw spellings the normalised entity id folded together (D-S4). Measured on the same
     * pre-aggregation graph as the tiles, and REPORTED so the analyst can see a fold happened.
     */
    readonly splitIdentities = computed(() => splitIdentityGroups(this.graph()));
    readonly splitIdentityHint = computed(() => {
        const groups = this.splitIdentities();
        if (!groups.length) return '';
        const shown = groups.slice(0, 3).map((g) => g.spellings.join(' / '));
        const rest = groups.length - shown.length;
        return (
            'These spellings differ only by case, spacing or trailing punctuation, so they are counted as ONE ' +
            'entity in degree, communities and every ranking — check they really are the same: ' +
            shown.join('; ') +
            (rest > 0 ? `; and ${rest} more` : '')
        );
    });

    /** The displayed graph as rows — search-narrowed, so canvas and table show the same result. */
    readonly tableRows = computed<Record<string, unknown>[]>(() => {
        const g = this.displayed();
        if (!g) return [];
        const q = this.search().trim();
        const match = q ? new Set(searchNodes(g, q)) : null;
        if (this.tableMode() === 'nodes') {
            return g.nodes
                .filter((n) => !match || match.has(n.id))
                .map((n) => ({
                    label: n.data.label,
                    kind: n.data.kind,
                    links: g.edges.filter((e) => e.source === n.id || e.target === n.id).length,
                    id: n.id,
                }));
        }
        const label = (id: string): string => g.nodes.find((n) => n.id === id)?.data.label ?? id;
        return g.edges
            .filter((e) => !match || match.has(e.source) || match.has(e.target))
            .map((e) => ({
                source: label(e.source),
                relationship: baseEdgeKind(e.data.kind),
                target: label(e.target),
                rows: (e.data as { count?: number }).count ?? 1,
                id: e.id,
            }));
    });
    readonly nodeOptions = computed(() => {
        // What the canvas draws: over an Investigation that is its Working Set, whose ids the server run maps (serverIds).
        const g = this.canvasData();
        return (g?.nodes ?? [])
            .map((n) => ({ id: n.id, label: n.data.label }))
            .sort((a, b) => a.label.localeCompare(b.label))
            .slice(0, 500);
    });

    // ── canvas emphasis (shared: written by search / canvas clicks / the analysis toolbox child) ──
    readonly emphasis = signal<GraphEmphasis | null>(null);

    /** The last run's id mappings — what the node ids were minted with (`undefined` = unscoped, untyped). */
    private readonly brushEntityTypes = computed<(EntityIdMapping | undefined)[]>(() => {
        const run = this.lastRun();
        if (run?.idMappings?.length) return run.idMappings;
        const q = run?.query;
        return q?.projections?.length ? q.projections : [q?.projection];
    });

    /** Remember a run with the typed mappings its answer resolved (LA-17 D-M6). */
    private runOf(sourceId: GraphSourceId, q: GraphSourceQuery, g: ProjectedGraph) {
        return {
            sourceId,
            query: sourceId === 'entity-projection' ? resolveRunQuery(q, g) : q,
            idMappings: g.idMappings,
        };
    }

    /** LA-22: a Geo Map selection highlights the nodes its KEYS project to; an explicit emphasis wins. */
    readonly geoBrushEmphasis = computed<GraphEmphasis | null>(() => {
        const b = this.brush.brush();
        const g = this.displayed();
        if (b?.origin !== 'geo' || !g) return null;
        const ids = new Set(g.nodes.map((n) => n.id));
        return { nodeIds: nodeIdsForKeys(b.keys, ids, this.brushEntityTypes()), edgeIds: [] };
    });

    // ── saved views ──
    readonly views = signal<LinkAnalysisView[]>([]);
    /** Stated in the saved-views menu, so the analyst reads it before choosing one (D-S1). */
    readonly savedViewNotice = SAVED_VIEW_NOT_EVIDENCE;

    /** The saved Link Analysis views as transfer references — what the export/import menu offers. */
    readonly transferItems = computed(() =>
        this.views().map((v) => ({ kind: 'link-analysis-view' as const, id: v.id })),
    );

    /** The start the graph on screen was drawn from (phase 6); a case saves it so reopening draws it again. Null = none. */
    readonly caseStart = signal<CaseStart | null>(null);
    /** The Entity Lists the graph on screen uses (A-1b): the list it started from, the exclusion lists written from it. */
    readonly caseLists = signal<{ start?: string; exclusions: string[] }>({ exclusions: [] });
    /** The lists a case would save, with their titles, said in the *Save as a case* menu. */
    readonly caseListsLine = computed(() => {
        const { start, exclusions } = this.caseLists();
        const title = (id: string) => this.entityLists().find((l) => l.id === id)?.title ?? id;
        const parts = [
            ...(start ? [`started from ${title(start)}`] : []),
            ...(exclusions.length ? [`leaves out ${exclusions.map(title).join(', ')}`] : []),
        ];
        return parts.length ? `Lists it uses: ${parts.join('; ')}.` : 'It uses no Entity List yet.';
    });

    readonly saveForm = this.fb.nonNullable.group({
        name: ['', [Validators.required, uniqueNameValidator(() => this.views().map((v) => v.name))]],
        description: [''],
    });
    readonly saving = signal(false);

    /** An imported draft this editor holds UNSAVED (Import as draft, D1–D8) — in memory only. */
    readonly importDraft = signal<LaImportDraft | null>(null);
    /** The stored copy the draft replaces (D6 diff baseline); null when the id is new here. */
    readonly draftStored = signal<Record<string, unknown> | null>(null);
    /** The stored copy's hash, sent as `If-Match` on the draft's Save so it cannot clobber a concurrent edit. */
    private draftIfMatch: string | undefined;

    /** An incoming investigation pivot (ui-design-review R8) awaiting a graph to resolve against —
     *  cleared after the first query run, found or not. */
    private pendingPivot?: ElementObjectRef;

    private destroyed = false;

    ngOnDestroy(): void {
        this.destroyed = true;
        // a pending debounced push must not send after the page is gone; its callers settle (resolve) so none hangs
        if (this.pushTimer) clearTimeout(this.pushTimer);
        this.pushTimer = null;
        this.pushWaiters.splice(0).forEach((w) => w());
    }

    ngOnInit(): void {
        // Each list degrades independently — a failing lookup must not blank the pane.
        this.datasetsLoaded = new Promise((resolve) =>
            this.datasetsService.list().subscribe({
                next: (d) => {
                    this.datasets.set(d);
                    resolve(d);
                },
                error: () => resolve([]),
            }),
        );
        this.pipelinesService.list().subscribe({ next: (p) => this.pipelines.set(p), error: () => undefined });
        // A start (`?seed=`, below) waits for the views too: a Dataset's start mapping may come from a saved view.
        this.viewsLoaded = new Promise((resolve) =>
            this.viewsService.list().subscribe({
                next: (v) => {
                    this.views.set(v);
                    resolve();
                },
                error: () => resolve(),
            }),
        );
        this.loadStartSources(); // before the param streams: a deep-linked start awaits what it reads
        // The footer's server ceiling; absent when the server cannot answer. Graph Run is part of the Investigation surface,
        // so Basic (no Investigations) does not ask - its browser algorithms keep their own caps.
        if (this.investigationsEnabled()) this.graphRuns.loadCatalogue();
        if (this.closedBySpaceSwitch) this.toastr.info(closedBySpaceSwitchMessage(this.closedBySpaceSwitch), 'Closed');
        // A stream, not one read: the page is kept alive (KEEP_ALIVE), so a later pivot lands on it with its graph
        // already drawn - resolve that at once rather than after the next load.
        this.route.queryParamMap
            .pipe(
                map((p) => `${p.get('pivotType')}:${p.get('pivotId')}`),
                distinctUntilChanged(),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe(() => {
                this.pendingPivot = this.pivotService.readIncoming(this.route);
                const g = this.graph();
                if (g) this.resolvePendingPivot(g);
            });
        // DR-U9: `?investigation=<id>` (the Alerts page's link from a fired Investigation rule) opens that Investigation.
        // A stream, not the snapshot: a second Alert's link navigates to this same route with a NEW id, which Angular
        // reuses. The id the store already has open (it writes the param itself) is not adopted again.
        this.route.queryParamMap
            .pipe(
                map((p) => p.get('investigation')),
                distinctUntilChanged(),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe((investigationId) => {
                const open = this.investigation.activeId();
                if (!investigationId && open) {
                    // Back on the kept-alive page through a link without the param (the nav rail): the Investigation is
                    // still open, so put its id back in the URL - a reload then reopens it, as it did before leaving.
                    void this.router.navigate([], {
                        relativeTo: this.route,
                        queryParams: { investigation: open },
                        queryParamsHandling: 'merge',
                        replaceUrl: true,
                    });
                    return;
                }
                if (!investigationId || investigationId === open) return;
                if (!this.investigationsEnabled()) return; // Basic: no Investigation to open (LA-EDITIONS-1 ED-4)
                this.openInvestigation();
                void this.investigation.adopt(investigationId);
            });
        // Sprint 1: `?seed=<id>&entityType=msisdn[&dataset=<id>]` investigates that entity. A stream, not the snapshot:
        // the starter card navigates to the same route, which Angular reuses. Each new request is consumed once.
        this.route.queryParamMap
            .pipe(
                map(parseInvestigateParams),
                distinctUntilChanged(sameInvestigateRequest),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe((req) => {
                if (!req) return;
                // LA-ONBOARD-1 OB-9: the deep link, an Entity profile's Investigate and the Start box take ONE path (F-8).
                void this.startFromEntity(req);
                // Consumed: drop the params, so a reload does not start over and close the open Investigation.
                void this.router.navigate([], {
                    relativeTo: this.route,
                    queryParams: { seed: null, entityType: null, dataset: null },
                    queryParamsHandling: 'merge',
                    replaceUrl: true,
                });
            });
        // LA-ONBOARD-1 phase 3: `?view=<id>` opens that saved view, drawing its graph (the first-run sample lands here).
        // The param STAYS (operator 2026-10-24): it is how a browser refresh reopens the view that was open, exactly as
        // `?investigation=` does. The page writes it when a view is opened or saved and Close drops it; a view the page
        // itself opened (it already holds that id) is not loaded a second time.
        this.route.queryParamMap
            .pipe(
                map((p) => p.get('view')),
                distinctUntilChanged(),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe((viewId) => {
                const open = this.openViewId();
                if (!viewId && open) {
                    // Back on the kept-alive page through a link without the param (the nav rail): the view is still
                    // open, so put its id back - a reload then reopens it. Not when the link brings its own thing to
                    // open (an Alert's `?investigation=`, a `?seed=`): that is about to replace the view, and both
                    // must not be in the address.
                    const q = this.route.snapshot.queryParamMap;
                    if (!q.has('investigation') && !q.has('seed')) this.setViewParam(open);
                    return;
                }
                if (!viewId || viewId === open) return;
                this.viewsService.list().subscribe({
                    next: (all) => {
                        const v = all.find((x) => x.id === viewId);
                        if (v) void this.loadView(v);
                        else {
                            this.toastr.info('That saved view is not in this Space.', 'Not found');
                            this.setViewParam(null);
                        }
                    },
                    error: () => undefined,
                });
            });
        // LA-ONBOARD-1 phases 4 + 6: `?dataset=<id>` alone (Add data's "Explore in Link Analysis") preselects that Dataset in
        // the Start box when it can start a graph (a link the Space knows); a Dataset without one is picked in the Query panel,
        // as the "Explore a Dataset" card does. With `seed` it is the entity's Dataset, which the seed stream consumes.
        // Consumed once, like `view`.
        this.route.queryParamMap
            .pipe(
                map((p) => (p.get('seed') ? null : p.get('dataset'))),
                distinctUntilChanged(),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe(async (datasetId) => {
                if (!datasetId) return;
                void this.router.navigate([], {
                    relativeTo: this.route,
                    queryParams: { dataset: null },
                    queryParamsHandling: 'merge',
                    replaceUrl: true,
                });
                const [datasets] = await Promise.all([this.datasetsLoaded, this.startTypesLoaded, this.viewsLoaded]);
                if (this.startCatalogue().datasets.some((d) => d.id === datasetId)) this.startDataset.set(datasetId);
                else if (datasets.some((d) => d.id === datasetId)) this.exploreDataset(datasetId);
                else this.toastr.info('That Dataset is not in this Space.', 'Not found');
            });
    }

    // ── LA-ONBOARD-1 phase 6 (OB-9): Start from one entity, a list, a paste or the whole Dataset ──

    /** The Entity Types in force with their bindings; null = not read (no registry, or it failed): primary types only. */
    readonly startTypes = signal<EntityTypeSummary[] | null>(null);
    private startTypesLoaded: Promise<void> = Promise.resolve();
    private viewsLoaded: Promise<void> = Promise.resolve();
    /** The Space's Entity Lists, for "Start from a list". */
    readonly entityLists = signal<EntityListSummary[]>([]);
    /** The Dataset the Start box preselects (`?dataset=`); null = its first. */
    readonly startDataset = signal<string | null>(null);
    /** What the last start did (drawn, held, not found) - shown under the Start box and the graph's status line. */
    readonly startNotice = signal<StartNotice | null>(null);
    readonly starting = signal(false);
    /** What can be started from: Datasets with a link, and the Entity Types their link ends hold. */
    readonly startCatalogue = computed(() => startCatalogue(this.datasets(), this.views(), this.startTypes()));
    /** The host has an Add data screen (the la-app's `/add-data`); the console has none yet, so the card is not offered there. */
    readonly addDataOffered = hasRoute(this.router.config, 'add-data');
    /** The projection node cap in force - read, never cached (My browser may lower it). */
    readonly nodeCap = (): number => projectionNodeCapValue();

    private loadStartSources(): void {
        this.startTypesLoaded = new Promise<void>((resolve) => {
            try {
                this.injector
                    .get(EntityProfileService)
                    .entityTypes()
                    .pipe(takeUntilDestroyed(this.destroyRef))
                    .subscribe({
                        next: (l) => {
                            this.startTypes.set(l?.items ?? []);
                            resolve();
                        },
                        error: () => resolve(), // degrade: the Datasets' primary types still start a graph
                    });
            } catch {
                resolve(); // no registry in this host
            }
        });
        this.inv
            .listEntityLists()
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({ next: (l) => this.entityLists.set(l?.lists ?? []), error: () => undefined });
    }

    openAddData(): void {
        void this.router.navigateByUrl('/add-data');
    }

    /** The Start box's choice. One entity on the Investigations level seeds an Investigation; every other start draws. */
    async startFrom(req: StartRequest): Promise<void> {
        const ds = this.startCatalogue().datasets.find((d) => d.id === req.datasetId);
        if (!ds || this.starting()) return;
        this.forgetOpenView(); // the Start box, Discover's "Start from these" and a Dataset all draw something new
        this.startNotice.set(null);
        if (req.kind === 'dataset') {
            this.sourceId.set('entity-projection');
            this.queryPanel?.patchFormFromView({ id: '', name: '', sourceId: 'entity-projection', query: { projection: ds.mapping } });
            await this.execute('entity-projection', { projection: ds.mapping });
            return;
        }
        if (req.kind === 'entity') {
            await this.startFromEntity({ seed: req.value, entityType: req.entityType, dataset: ds.id });
            return;
        }
        let values: string[];
        // the Entity Type the start names (LA-ONBOARD-DEFECTS-1): the paste's pick, the list's own type, or - for a result
        // found on the graph - the type the graph was drawn as; never silently the Dataset's primary type
        let entityType: string | undefined =
            req.kind === 'paste' ? req.entityType : req.kind === 'found' ? this.lastRun()?.query?.projection?.entityType : undefined;
        if (req.kind === 'list') {
            try {
                const list = await firstValueFrom(this.inv.getEntityList(req.listId));
                entityType = list.entityType || undefined;
                if (!list.members) {
                    this.startNotice.set({ variant: 'warning', text: `The members of ${list.title} are not shown to you, so it cannot start a graph.` });
                    return;
                }
                if (!list.members.length) {
                    this.startNotice.set({ variant: 'warning', text: `${list.title} has no members yet.` });
                    return;
                }
                values = list.members;
            } catch (err) {
                this.startNotice.set({ variant: 'warning', text: apiErrorMessage(err, 'The Entity List could not be read.') });
                return;
            }
        } else values = req.values;
        const found = await this.drawStart(ds, values, entityType);
        if (found.length) {
            // a case remembers its start (A-1b): reopening draws it again over the data as it is then
            this.caseStart.set({
                datasetId: ds.id,
                values: found,
                ...(entityType ? { entityType } : {}),
                ...(req.kind === 'list' ? { listId: req.listId } : {}),
            });
            if (req.kind === 'list') this.caseLists.set({ start: req.listId, exclusions: [] });
            if (req.kind === 'found')
                this.startNotice.update((n) => (n ? { ...n, text: `Started from ${req.what}. ${n.text}` } : n));
        }
        // Investigations level: the drawn start is also queued as the first seeds of the Investigation (as "top results" are).
        if (found.length && this.investigationsEnabled())
            this.queueSeedsAndOpen(found.map((v) => ({ id: v, label: v, ids: [v] })));
    }

    /**
     * ONE entity (the Start box, the `?seed=` deep link, an Entity profile's Investigate): on the Explorer level draw it and one
     * ring over a Dataset that links its type (F-8: it drew the whole Dataset); on the Investigations level seed an Investigation.
     */
    async startFromEntity(req: InvestigateRequest): Promise<void> {
        this.forgetOpenView(); // what is drawn next is not the saved view
        if (this.investigationsEnabled()) {
            await this.prepareInvestigation(req);
            return;
        }
        this.investigateIssue.set('');
        await Promise.all([this.datasetsLoaded, this.startTypesLoaded, this.viewsLoaded]);
        const ds = datasetForType(this.startCatalogue(), req.entityType, req.dataset);
        if (!ds) {
            this.startNotice.set({
                variant: 'warning',
                text:
                    `No Dataset in this Space links ${req.entityType} entities yet, so ${req.seed} cannot be drawn. ` +
                    'Add data, or explore a Dataset and choose its from and to columns.',
            });
            return;
        }
        const found = await this.drawStart(ds, [req.seed], req.entityType);
        if (found.length) this.caseStart.set({ datasetId: ds.id, values: found, entityType: req.entityType });
    }

    /**
     * The explorer start (OB-9): one neighbours read per value (`POST /inv/projection/neighbors`, at most
     * {@link START_MAX_SEEDS}) merged into a NEW graph - the values plus one ring - with the busy neighbours above the Space's
     * hub threshold held (the Grow preview's own `held`), the values marked grown, and the run remembered over the Dataset's
     * mapping, so Grow continues from the ring. Values that read nothing are reported. Returns the values that were drawn.
     * The mapping is typed by `entityType`, the type the start named (LA-ONBOARD-DEFECTS-1); absent, the Dataset's primary.
     */
    private async drawStart(ds: StartDataset, values: readonly string[], entityType?: string): Promise<string[]> {
        const source = this.graphSources.byId('entity-projection');
        if (!source?.expand) return [];
        const wanted = values.slice(0, START_MAX_SEEDS);
        const projection = entityType ? { ...ds.mapping, entityType } : ds.mapping;
        const q: GraphSourceQuery = { projection };
        const firstDraw = !this.graph();
        const focusBefore = this.focusHolder();
        this.starting.set(true);
        this.loading.set(true);
        this.loadError.set('');
        try {
            const reads: ProjectedGraph[] = [];
            for (let i = 0; i < wanted.length; i += 5)
                reads.push(...(await Promise.all(wanted.slice(i, i + 5).map((v) => source.expand!(v, v, q) as Promise<ProjectedGraph>))));
            const found = wanted.filter((_, i) => reads[i].edges.length > 0);
            const unknown = wanted.filter((_, i) => reads[i].edges.length === 0);
            if (!found.length) {
                this.startNotice.set({
                    variant: 'warning',
                    text: `Not in ${ds.name}: ${someValues(unknown)}. Nothing was drawn - check the spelling, or pick another Dataset.`,
                });
                return [];
            }
            const drawn = reads.filter((r) => r.edges.length > 0);
            const g = { ...mergeGraphs(drawn), idMappings: drawn[0].idMappings } as ProjectedGraph;
            let held = new Map<string, number | null>();
            try {
                const p = await firstValueFrom(
                    this.inv.previewNeighbors(
                        neighboursPreviewBody(projection, found, { direction: 'either', linkKinds: null, threshold: this.growThreshold() }, []),
                    ),
                );
                held = new Map(p.held.map((h) => [h.id, h.contacts]));
            } catch {
                // no preview on this server: nothing is held, Grow still asks before it goes through a busy one
            }
            this.clearResults();
            this.growExclude.reset(); // a new graph: an Undo of the old one's Grow must not bring it back
            this.sourceId.set('entity-projection');
            this.queryPanel?.patchFormFromView({ id: '', name: '', sourceId: 'entity-projection', query: q });
            this.graph.set(g);
            this.truncated.set(drawn.some((r) => r.truncated));
            this.mappingSummary.set([]);
            this.kindFilter.set([]);
            this.hiddenNodeIds.set([]);
            this.lastRun.set(this.runOf('entity-projection', q, g));
            this.expandedNodeIds.set(
                drawn.map((r, i) => seedNodeOf(r, found[i])).filter((id): id is string => !!id),
            );
            this.growExclude.basicHeld.set(held);
            this.queryOpen.set(false);
            const t = this.growTerms();
            this.startNotice.set({
                variant: unknown.length ? 'warning' : 'info',
                text: startOutcome({
                    drawn: found.length,
                    nodes: g.nodes.length,
                    held: held.size,
                    unknown,
                    notDrawn: values.length - wanted.length,
                    dataset: ds.name,
                    terms: t,
                }),
            });
            if (firstDraw) this.focusDrawnGraph(focusBefore, g.nodes.length, g.edges.length);
            return found;
        } catch (err) {
            this.toastr.error(invErrorMessage(err, 'The start could not be drawn.'));
            return [];
        } finally {
            this.loading.set(false);
            this.starting.set(false);
        }
    }

    /** The Datasets of this Space, once listed (empty when the list failed). */
    private datasetsLoaded: Promise<LaDataset[]> = Promise.resolve([]);
    /** A deep-linked "Investigate a number" waiting for its purpose in the Investigation tab. */
    readonly pendingInvestigation = signal<PendingInvestigation | null>(null);
    /** Why a deep-linked number could not be bound to its default Dataset; '' = no problem. */
    readonly investigateIssue = signal('');

    /**
     * Bind a deep-linked number to its profile's default Dataset and ask for the purpose in the Investigation tab
     * (nothing is created before Start). Without that Dataset in the Space: say so, queue the number as a seed and
     * open the Dataset picker — the regular start then seeds it.
     */
    async prepareInvestigation(req: InvestigateRequest): Promise<void> {
        if (!this.investigationsEnabled()) return; // the Explorer level has no Investigation: startFromEntity draws instead
        const badSeed = seedError(req);
        if (badSeed) {
            // The same rule as the starter card: a malformed number is refused, never queued.
            this.pendingInvestigation.set(null);
            this.investigateIssue.set(`${req.seed} cannot be investigated: ${badSeed}`);
            return;
        }
        // The packs arrive with the Space's Domain Profile: a deep link that lands first waits for them.
        if (!profileForEntityType(req.entityType) && !this.domainProfiles.state()) await this.domainProfiles.ready();
        const seed = req.entityType.toLowerCase() === 'msisdn' ? normaliseMsisdn(req.seed) : req.seed;
        // A Domain Profile that investigates the type is the override; else OB-9's generic start: a Dataset whose link holds it.
        const profiled = investigateBinding({ ...req, seed });
        const [datasets] = await Promise.all([this.datasetsLoaded, this.startTypesLoaded, this.viewsLoaded]);
        const startDs = profiled ? null : datasetForType(this.startCatalogue(), req.entityType, req.dataset);
        const binding = profiled ?? (startDs ? genericBinding(seed, req.entityType, startDs, activeDomainProfileId()) : null);
        const wanted = binding?.projection.datasetId ?? req.dataset;
        const ds = wanted ? datasets.find((d) => d.id === wanted) : undefined;
        let reason = !binding
            ? `No Dataset in this Space links entities of type ${req.entityType}.`
            : !ds
              ? `The Dataset ${wanted} is not in this Space, so ${seed} cannot be investigated over it.`
              : '';
        // A Dataset named by the link must carry the profile's mapped columns, or every expand would be refused.
        if (profiled && binding && ds && req.dataset) {
            const missing = missingMappedColumns(
                binding,
                (await this.datasetRows.columns(ds)).map((c) => c.name),
            );
            if (missing.length)
                reason = `The Dataset ${wanted} has no column ${missing.join(', ')}, which the ${binding.profileId} profile binds.`;
        }
        if (binding && ds && !reason) {
            this.investigateIssue.set('');
            this.investigation.close(); // the pending form shows only while no Investigation is open (it stays remembered)
            this.profileControl.setValue(binding.profileId);
            const windowEnd = await this.latestEventTime(ds, binding.timeCol);
            this.pendingInvestigation.set(windowEnd ? { ...binding, windowEnd } : binding);
            this.openInvestigation();
            return;
        }
        this.pendingInvestigation.set(null);
        this.investigateIssue.set(
            `${reason} Choose the Dataset holding ${seed}'s links and run it; ${seed} is queued as the first seed of ` +
                'the Investigation you start from it.',
        );
        this.investigation.queueSeeds([{ id: seed, label: seed, ids: [seed] }]);
    }

    /**
     * The Dataset's latest event time — where the preset window ends (seed data ends before today). Read over
     * `/db/query`, the guarded read-only route Advanced search runs for analysts; null when it cannot be read, and
     * then no window is sent.
     */
    private async latestEventTime(ds: LaDataset, timeCol: string | undefined): Promise<string | null> {
        if (!timeCol || !ds.sourceName) return null;
        const res = await this.datasetRows.sql(ds.sourceName, latestTimeSql(ds.sourceName, timeCol), 1);
        return res.error ? null : latestTimeOf(res.rows[0]?.['latest']);
    }

    /** The current profile's "Expand next degree" presets ({} = the server defaults). */
    readonly degreePresets = computed(() => this.profile().investigate?.expand ?? {});
    readonly maxDegree = computed(() => this.profile().investigate?.maxDegree ?? DEFAULT_MAX_DEGREE);

    /** Try to find the pivoted-in record among the just-loaded graph's nodes; focus it if present,
     *  else toast that this view doesn't have it. Runs once, after the first graph load. */
    private resolvePendingPivot(g: G6GraphData): void {
        const pivot = this.pendingPivot;
        if (!pivot) return;
        this.pendingPivot = undefined;
        const node = g.nodes.find((n) => n.data.objectRef?.id === pivot.id && n.data.objectRef?.type === pivot.type);
        if (node) this.focusNode(node.id);
        else this.toastr.info('That record is not in this graph.', 'Not found');
    }

    /** The ready-made "follow the money" saved view of this Space, or null - the starter card is then hidden. */
    readonly followView = computed(() => pickFollowTheMoney(this.views(), this.datasets()));

    /** Starter card "Follow the money": load the ready-made view, which draws its graph. */
    loadFollowTheMoney(): void {
        const v = this.followView();
        if (v) void this.loadView(v);
    }

    /**
     * Starter card "Explore a Dataset": show the Query panel and open its guided Dataset picker. With `datasetId` (Add
     * data's `?dataset=<id>`) that Dataset is picked instead, and its graph is drawn when its link columns are suggested.
     */
    exploreDataset(datasetId?: string): void {
        this.queryDockOpen.set(true);
        this.queryOpen.set(true);
        this.sourceId.set('entity-projection');
        this.cdr.detectChanges(); // the panel mounts with the dock - the picker must exist before it is opened
        this.queryPanel?.startExplore(datasetId);
    }

    /** Starter card "Open a saved view": the same menu as the rail's saved-views button. */
    openSavedViews(): void {
        this.viewsTrigger?.openMenu();
    }

    /** A ranking row was picked: highlight, select and centre that node on the canvas. */
    onNodePick(id: string): void {
        this.focusNode(id);
        const picked = this.canvasData()?.nodes.find((n) => n.id === id);
        if (picked && this.investigation.active()) this.investigation.selected.set(picked);
        this.graphView?.centerOn([id]);
    }

    /** "Start an Investigation from the top results": queue the ranked nodes and show the Investigation tab. */
    queueSeedsAndOpen(seeds: QueuedSeed[]): void {
        if (!this.investigationsEnabled()) return; // Basic: the "start an Investigation" action is not offered
        if (!seeds.length) {
            this.toastr.info('None of the top results can seed an Investigation.', 'Nothing to queue');
            return;
        }
        this.investigation.queueSeeds(seeds);
        this.openInvestigation();
    }

    /** Re-fetch the saved views (after an import brought some in). */
    reloadViews(): void {
        this.viewsService.list().subscribe({ next: (v) => this.views.set(v), error: () => undefined });
    }

    /** Node-id → label over the full loaded graph. An arrow field so it can be passed to the toolbox
     *  child as an `[labelOf]` input without losing its `this` binding. */
    readonly labelOf = (id: string): string => this.graph()?.nodes.find((n) => n.id === id)?.data.label ?? id;

    async run(): Promise<void> {
        const q = this.queryPanel?.buildQuery() ?? { error: 'The query form is not ready.' };
        if ('error' in q) {
            this.loadError.set(q.error);
            return;
        }
        this.forgetOpenView(); // a query the analyst ran is not the saved view any more
        await this.execute(this.sourceId(), q);
    }

    /**
     * An Investigation canvas replaces whatever the page drew, and an Investigation has its own `?investigation=`:
     * the two must not both be reopened on refresh.
     */
    private readonly forgetViewForInvestigation = effect(() => {
        if (this.investigation.canvas()) untracked(() => this.forgetOpenView());
    });

    /** A fresh graph (or none) clears the canvas emphasis + presentation filters and the analysis toolbox's results. */
    private clearResults(): void {
        this.emphasis.set(null);
        this.toolbox?.reset();
        this.search.set('');
        this.collapsedRoots.set([]);
        this.timeColumn.set('');
        this.timeCutoff.set(null);
        this.localFilter.set(null);
        this.latestSnapshot.set(null);
        this.history.set(emptyHistory()); // a fresh graph invalidates prior undo/redo snapshots
        this.serverPaths.set(null);
        this.expandSource.set(null);
        this.expandedNodeIds.set([]);
        this.serverPattern.set(null);
        this.temporalFindings.set(null);
        this.temporalError.set('');
        this.temporalSealed.set(null);
        this.temporalSealError.set('');
        this.mappingSummary.set([]);
        this.caseStart.set(null);
        this.caseLists.set({ exclusions: [] });
        this.discover?.reset();
    }

    /**
     * What is open, as the Close action names it: the Investigation (or the Draft of it that is the working scope), an
     * imported view not saved yet, else the drawn analysis; '' = nothing open (the empty state).
     */
    readonly openWorkLabel = computed<string>(() => {
        const inv = this.investigation;
        if (inv.active()) {
            const title = `Investigation ${inv.displayTitle()}`;
            return inv.activeDraftId() ? `Draft ${inv.activeDraftId()} of ${title}` : title;
        }
        if (this.importDraft()) return `unsaved view ${this.importDraft()!.id}`;
        return this.graph() ? 'the analysis' : '';
    });

    /**
     * What a Space switch closed (the switch reloads the app, so it was remembered for the tab); said once on init. Read
     * as a field, before {@link rememberOpen} first runs and overwrites the record with this page's own state.
     */
    private readonly actor = inject(SessionService).actor() ?? '';
    private readonly closedBySpaceSwitch = takeClosedBySpaceSwitch(this.spaces.currentSpaceId() ?? '', this.actor);
    /** The Space this page was opened in; everything it holds belongs to it (a Space switch reloads the app). */
    private readonly pageSpace = this.spaces.currentSpaceId() ?? '';
    /**
     * Remember what is open for this tab, so a Space switch (an app reload) can say what it closed. Keyed by
     * {@link pageSpace}, never the live Space: the switch changes that before the reload, and the record must still
     * name the Space the item belongs to.
     */
    private readonly rememberOpen = effect(() => {
        const label = this.openWorkLabel();
        untracked(() => rememberOpenWork(label ? { space: this.pageSpace, actor: this.actor, label } : null));
    });

    /**
     * Close what is open (operator 2026-10-21) and go back to the empty state: the Investigation and its Draft scope (the
     * store drops `?investigation=` from the URL), an imported view, the drawn graph and its results. Nothing is deleted:
     * an Investigation reopens from the Investigation tab, a saved view from Saved views. An imported view that was never
     * saved is the one thing Close would lose, so that is asked first.
     */
    async closeOpenWork(): Promise<void> {
        const draft = this.importDraft();
        if (
            draft &&
            !(await this.confirm.confirmDestructive(`The imported view ${draft.id} is not saved. Close it anyway?`, {
                title: 'Close unsaved view',
                confirmText: 'Close',
            }))
        )
            return;
        this.clearDraft();
        this.openViewId.set(null);
        this.setViewParam(null); // Close drops what a refresh would reopen
        this.investigation.close();
        this.investigation.clearQueuedSeeds();
        this.pendingInvestigation.set(null);
        this.investigateIssue.set('');
        this.startNotice.set(null);
        this.clearResults();
        this.graph.set(null);
        this.cachedAsOf.set(null);
        this.lastRun.set(null);
        this.truncated.set(false);
        this.loadError.set('');
        this.kindFilter.set([]);
        this.hiddenNodeIds.set([]);
        this.queryOpen.set(true);
        this.growExclude.reset();
    }

    /** PERF-P10a-1/3: identical (Space, viewer, source, query) answers are served from the client; see graph-query-cache.ts. */
    private readonly queryCache = new GraphQueryCache<{ g: G6GraphData; masking: MaskingNote | null }>(
        () => `${this.spaces.currentSpaceId() ?? ''}|${this.session.actor() ?? ''}|${this.limits.limits().maskingModeInForce ?? ''}`,
        () => Date.now(),
        // PERF-P10a-2: after expiry, requests that revalidate to 304 fold to the same graph - keep the previous object
        (a, b) =>
            deepEqual(a.g, b.g) && // EVERY field (nodes' data, mappings, idMappings, omittedLinks, source, ...)
            deepEqual(a.masking, b.masking),
    );
    /** "As of" line for a graph served from the cache; null when the server just answered. */
    readonly cachedAsOf = signal<number | null>(null);
    private pushTimer: ReturnType<typeof setTimeout> | null = null;
    private pushWaiters: (() => void)[] = [];

    /**
     * The masking badge is a side effect of each read (InvService.masking), so the note the read was served under
     * is kept with the cached graph and re-applied on a hit: the badge always describes the graph on screen.
     */
    private async cachedQuery(sourceId: GraphSourceId, source: GraphSource, q: GraphSourceQuery, force = false) {
        this.inv.reads.setScope(this.limits.limits().maskingModeInForce ?? ''); // a masking-mode change drops the stored ETags
        const hit = await this.queryCache.get(
            sourceId,
            q,
            async () => {
                if (force) this.inv.reads.clear(); // Refresh is a hard re-read: no If-None-Match, the entry is replaced
                // per read: a read that sets no note is stored as "no note", never the previous read's
                const before = this.inv.masking();
                this.inv.masking.set(null);
                try {
                    return { g: await source.query(q), masking: this.inv.masking() };
                } catch (err) {
                    this.inv.masking.set(before);
                    throw err;
                }
            },
            force,
        );
        if (hit.fromCache) this.inv.masking.set(hit.value.masking);
        return hit;
    }

    /** The Refresh bypass: re-run the last query against the server, ignoring the cache. */
    async refreshFromServer(): Promise<void> {
        const run = this.lastRun();
        if (run) await this.execute(run.sourceId, run.query, true);
    }

    private async execute(sourceId: GraphSourceId, q: GraphSourceQuery, force = false): Promise<void> {
        const source = this.graphSources.byId(sourceId);
        if (!source) return;
        const firstDraw = !this.graph();
        const focusBefore = this.focusHolder();
        this.loading.set(true);
        this.loadError.set('');
        this.startNotice.set(null); // a new graph: the last start's outcome no longer describes it
        this.clearResults();
        this.pushState.set(q.filter && hasConditions(q.filter) ? 'sent with the query' : 'not pushed');
        try {
            const hit = await this.cachedQuery(sourceId, source, q, force);
            const g = hit.value.g;
            this.cachedAsOf.set(hit.fromCache ? hit.fetchedAt : null);
            this.growExclude.reset(); // a new graph: an Undo of the old one's Grow must not bring it back
            this.graph.set(g);
            this.resolvePendingPivot(g);
            this.truncated.set(!!(g as ProjectedGraph).truncated);
            this.mappingSummary.set((g as MultiProjectedGraph).mappings ?? []);
            this.kindFilter.set([]);
            this.lastRun.set(this.runOf(sourceId, q, g as ProjectedGraph));
            this.queryOpen.set(false); // smart form: collapse to the selected-values summary
            if (firstDraw) this.focusDrawnGraph(focusBefore, g.nodes.length, g.edges.length);
        } catch (err) {
            this.graph.set(null);
            this.lastRun.set(null);
            this.queryOpen.set(true); // a failing query needs its form back
            // EDITIONS CP-09: a 503 means the backend module is absent in this bundle, not that the query
            // was bad — say so, instead of a generic failure the user will retry (the assist-panel idiom).
            // The nav entry is hidden on the same flag; this is the belt for a bookmarked/deep-linked URL.
            this.loadError.set(
                (err as { status?: number } | null)?.status === 503
                    ? 'Link analysis is not available in this edition — its backend module is not installed in this bundle.'
                    : err instanceof Error
                      ? err.message
                      : invErrorMessage(err, 'The graph query failed.'),
            );
        } finally {
            this.loading.set(false);
        }
    }

    /** What holds keyboard focus now: a starter card the draw is about to remove is a different holder after it. */
    private focusHolder(): Element | null {
        const a = document.activeElement;
        return a && a !== document.body ? a : null;
    }

    /** Announced in the sr-only live region after the first draw; the text is the only place the count is spoken. */
    readonly drawAnnouncement = signal('');

    /**
     * WCAG 2.4.3: the control that started the first draw (a landing card) is removed with the landing state, so
     * focus would fall to the page body. Once per first draw, move it to the graph region - but only when focus is
     * still where the draw began (the removed trigger, or nowhere); a user who moved on is never pulled back.
     */
    private focusDrawnGraph(before: Element | null, nodes: number, links: number): void {
        this.drawAnnouncement.set(`Graph drawn, ${nodes} nodes, ${links} links`);
        this.cdr.detectChanges(); // the card is removed and the region mounted in this pass
        const now = document.activeElement;
        const stillAtTrigger = !now || now === document.body || (before !== null && now === before && !before.isConnected);
        if (stillAtTrigger) this.graphRegion?.nativeElement.focus({ preventScroll: false });
    }

    // ── the two-stage query loop ──

    /** The condition-group editor mutated the tree in place — new root ref so the computeds re-run. */
    onFilterChanged(): void {
        this.filterWhere.update((w) => ({ ...w }));
    }

    /** Stage 1: apply the tree to the working set in the browser (free — no round-trip). */
    applyFilterLocally(): void {
        const w = this.filterWhere();
        this.localFilter.set(hasConditions(w) ? cloneGroup(w) : null);
    }

    clearFilter(): void {
        this.filterWhere.set(emptyGroup());
        this.localFilter.set(null);
    }

    /**
     * Stage 2: re-run the last query with the tree as `filter`, merging the result over the working set so
     * layout and selection survive and every node the narrower question excluded is MARKED, never dropped.
     * `truncated` on the result is the loop's signal to refine again.
     */
    pushFilter(): Promise<void> {
        // PERF-P10a-4: a burst of edits yields one request after the pause; every caller resolves when it is done.
        return new Promise<void>((resolve) => {
            this.pushWaiters.push(resolve);
            if (this.pushTimer) clearTimeout(this.pushTimer);
            this.pushTimer = setTimeout(() => {
                this.pushTimer = null;
                const waiters = this.pushWaiters.splice(0);
                void this.sendPushFilter().finally(() => waiters.forEach((w) => w()));
            }, PUSH_FILTER_DEBOUNCE_MS);
        });
    }

    private async sendPushFilter(): Promise<void> {
        const run = this.lastRun();
        const source = run && this.graphSources.byId(run.sourceId);
        const prev = this.graph();
        if (!run || !source || !prev) return;
        const w = this.filterWhere();
        const q: GraphSourceQuery = { ...run.query, filter: hasConditions(w) ? cloneGroup(w) : undefined };
        this.pushing.set(true);
        try {
            const hit = await this.cachedQuery(run.sourceId, source, q);
            const next = hit.value.g;
            this.cachedAsOf.set(hit.fromCache ? hit.fetchedAt : null);
            const truncated = !!(next as ProjectedGraph).truncated;
            this.graph.set(markStranded(prev, next));
            this.truncated.set(truncated);
            this.lastRun.set(this.runOf(run.sourceId, q, next as ProjectedGraph));
            this.localFilter.set(null); // the server applied it; the local stage starts clean
            const n = next.edges.length.toLocaleString();
            this.pushState.set(truncated ? `${n} links · truncated — refine and push again` : `${n} links · complete`);
        } catch (err) {
            if (!this.destroyed) this.toastr.error(apiErrorMessage(err, 'Pushing the predicate to the server failed.'));
        } finally {
            this.pushing.set(false);
        }
    }

    // ── evidence: snapshot the answer, attach it to a Case (spec §3.6 / plan S1.3, UI first) ──

    /**
     * A1 (operator 2026-09-30): the snapshot captures what is ON SCREEN — `canvasData()`, i.e. the Working Set
     * while an Investigation draws it, the query graph otherwise. The dialog hashes this same graph, so the
     * SHA-256 manifest covers exactly the drawn nodes/edges.
     */
    private snapshotData(): SnapshotDialogData | null {
        const g = this.canvasData();
        const ws = this.investigation.canvas() !== null;
        const run = this.lastRun();
        const investigationId = this.investigation.activeId() ?? undefined;
        let origin: SnapshotDialogData['origin'];
        let predicate: SnapshotDialogData['predicate'] = null;
        if (ws) {
            origin = {
                sourceId: 'investigation',
                dataset: this.investigation.binding()?.datasetId,
                query: { investigationId },
            };
        } else if (run) {
            origin = { sourceId: run.sourceId, dataset: run.query.projection?.datasetId, query: run.query };
            predicate = run.query.filter ?? this.localFilter() ?? null;
        } else return null;
        if (!g) return null;
        return {
            graph: g,
            predicate,
            origin,
            layout: this.layoutId(),
            suggestedTitle: snapshotTitle(
                ws,
                this.investigation.header()?.title ??
                    this.investigation.refs().find((r) => r.id === investigationId)?.title,
                this.sourceLabel(),
                g.nodes.filter((n) => !n.data.missing).length,
            ),
            caseId: this.deepLinkedCaseId(),
            // The box-selected nodes win: "Attach selected to a Case" records exactly them, else what the emphasis lit.
            selectedNodeIds: this.selectedOnCanvas().length ? this.selectedOnCanvas() : (this.emphasis()?.nodeIds ?? []),
            investigationId,
        };
    }

    /** Freeze the on-screen graph as a snapshot; resolves with it (or undefined when cancelled). */
    openSnapshot(): Promise<GraphSnapshot | undefined> {
        const data = this.snapshotData();
        if (!data) return Promise.resolve(undefined);
        return new Promise((resolve) =>
            this.dialog
                .open<LinkAnalysisSnapshotDialog, SnapshotDialogData, GraphSnapshot | undefined>(
                    LinkAnalysisSnapshotDialog,
                    { data, width: '36rem' },
                )
                .afterClosed()
                .subscribe((snap) => {
                    if (snap) {
                        this.latestSnapshot.set(snap);
                        const attached = snap.attachedTo?.length ? ` Attached to ${snap.attachedTo.join(', ')}.` : '';
                        this.toastr.success(
                            `Snapshot “${snap.title}” saved (${snap.manifestHash.slice(0, 12)}…).${attached}`,
                        );
                    }
                    resolve(snap ?? undefined);
                }),
        );
    }

    /**
     * The open Investigation's binding, while its Working Set is what the canvas draws — so the toolbox runs a pattern
     * pack on the server over that Dataset with the Investigation's own kind / time columns (its links carry neither).
     */
    readonly investigationPatternTarget = computed<InvestigationPatternTarget | null>(() => {
        if (!this.investigation.canvas()) return null;
        const h = this.investigation.header();
        if (!h?.dataset) return null;
        return {
            dataset: h.dataset,
            ...(h.linkKindCol ? { linkKindCol: h.linkKindCol } : {}),
            ...(h.timeCol ? { timeCol: h.timeCol } : {}),
        };
    });

    /**
     * Run a branching pack over the open Investigation's WHOLE Dataset (`POST /inv/pattern/branching`). Matches are
     * highlighted where the Working Set draws them; nothing is added to the Investigation (that stays an op).
     */
    private async runPatternOverInvestigation(req: ServerPatternRequest): Promise<void> {
        const h = this.investigation.header();
        const target = this.investigationPatternTarget();
        const investigationId = this.investigation.activeId();
        if (!h || !target || !investigationId) return;
        this.serverPatternBusy.set(true);
        try {
            const res = await firstValueFrom(
                this.inv.branchingPattern({
                    // the ids come back as this Investigation's pseudonyms, so they line up with the canvas
                    investigationId,
                    dataset: target.dataset,
                    sourceCol: h.sourceCol,
                    targetCol: h.targetCol,
                    linkKindCol: req.linkKindCol ?? target.linkKindCol,
                    timeCol: req.timeCol ?? target.timeCol,
                    stages: req.stages,
                }),
            );
            const base = this.canvasData() ?? { nodes: [], edges: [] };
            const { state } = branchingResultToGraph(res, base);
            this.serverPattern.set(state);
            const drawn = new Set(base.nodes.map((n) => n.id));
            const nodeIds = [...new Set(state.matches.flatMap((m) => m.nodeIds))].filter((id) => drawn.has(id));
            this.emphasis.set(nodeIds.length ? { nodeIds, edgeIds: [] } : null);
        } catch (err) {
            this.serverPattern.set(null);
            this.toastr.error(invErrorMessage(err, 'The server pattern search failed.'));
        } finally {
            this.serverPatternBusy.set(false);
        }
    }

    /** Why "Save this analysis" is unavailable to this user — empty when it is available. Sealing a snapshot needs `canManageIncidents`. */
    readonly snapshotUnavailable = computed(() =>
        this.lens.canManageIncidents() ? '' : 'Saving an analysis needs the Incident management capability.',
    );

    /** Why "Attach to Case" is unavailable to this user — empty when it is available. Attaching needs `canManageIncidents`. */
    readonly attachUnavailable = computed(() =>
        this.lens.canManageIncidents() ? '' : 'Attaching to a Case needs the Incident management capability.',
    );

    /** LA-17 "Merge with...": enabled only inside an Investigation, with the capability, on two unmasked typed entities. */
    readonly mergeUnavailable = computed(() => {
        if (!this.lens.canManageIncidents()) return 'Merging identities needs the Incident management capability.';
        const ws = this.investigation.workingSet();
        const ids = this.selectedOnCanvas().flatMap((id) => {
            const node = (this.canvasData()?.nodes ?? []).find((n) => n.id === id);
            return node ? idsInWorkingSet(node, ws) : [];
        });
        return mergePairOf(ids, ws).reason;
    });

    /** Hands the two selected entities to the Identity section (Investigation tab); nothing is written until it is confirmed there. */
    mergeSelected(): void {
        if (this.mergeUnavailable()) return;
        const ws = this.investigation.workingSet();
        const ids = this.selectedNodes().flatMap((n) => idsInWorkingSet(n, ws));
        const { pair } = mergePairOf(ids, ws);
        if (!pair) return;
        this.investigation.mergeDraft.set(pair);
        // LA-I2-GAPS-RESIDUALS-1 (5): open the tab that holds the prefilled merge, so the next step is on screen.
        this.toolboxTab.set('investigation');
        this.toastr.info('Prefilled in the Identity section below - add a reason and confirm there.');
    }

    /** One step: seal what is on screen and attach it to a Case (optionally linking the open Investigation too). */
    openAttachToCase(): void {
        if (this.attachUnavailable()) return;
        const data = this.snapshotData();
        if (!data) {
            this.toastr.info('There is nothing on the canvas to attach yet — run a query or open an Investigation.');
            return;
        }
        this.dialog
            .open<LinkAnalysisAttachCaseDialog, AttachCaseDialogData, AttachCaseResult | undefined>(
                LinkAnalysisAttachCaseDialog,
                { data, width: '36rem' },
            )
            .afterClosed()
            .subscribe((r) => {
                if (!r?.caseId) return;
                const linked = r.linkedInvestigation ? ` Investigation ${r.linkedInvestigation} is linked to it.` : '';
                if (!r.snapshot) {
                    this.toastr.success(`Attached to ${r.caseId}.${linked}`);
                    return;
                }
                this.latestSnapshot.set(r.snapshot);
                this.toastr.success(
                    `Sealed “${r.snapshot.title}” (${r.snapshot.manifestHash?.slice(0, 12) ?? ''}…) and attached it to ${r.caseId}.${linked}`,
                );
            });
    }

    /**
     * Advanced search (mockup review point 4): SQL over the projected Dataset with a tabular result, beside
     * the predicate builder. The dialog hands back a graph folded from the RESULT rows — SQL text never
     * reaches the projection route — which replaces the working set with the previous nodes marked stranded.
     */
    openAdvancedSearch(): void {
        const p = this.lastRun()?.query.projection;
        const dataset = p && this.datasets().find((d) => d.id === p.datasetId);
        if (!p || !dataset) {
            this.toastr.info('Advanced search needs a single-Dataset projection to be loaded first.');
            return;
        }
        const data: LinkAnalysisAdvancedSearchData = { dataset, projection: p };
        this.dialog
            .open<LinkAnalysisAdvancedSearchDialog, LinkAnalysisAdvancedSearchData, ProjectedGraph | undefined>(
                LinkAnalysisAdvancedSearchDialog,
                { data, width: '64rem', maxWidth: '96vw' },
            )
            .afterClosed()
            .subscribe((g) => {
                if (!g) return;
                const prev = this.graph();
                this.graph.set(prev ? markStranded(prev, g) : g);
                this.truncated.set(!!g.truncated);
                this.localFilter.set(null);
                this.pushState.set(`${g.edges.length.toLocaleString()} links · from advanced search`);
            });
    }

    // ── workspace layout ──

    /** Reopen the full query form (status-bar pencil) in the query dock. */
    editQuery(): void {
        this.queryDockOpen.set(true);
        this.queryOpen.set(true);
    }

    /** Open the graph-algorithms toolbox (the right dock's Analysis tab). */
    openAnalysis(): void {
        this.toolboxDockOpen.set(true);
        this.toolboxTab.set('analysis');
    }

    /** LA-10: open the Investigation tab (start one, or work the open one's op log). */
    openInvestigation(): void {
        this.toolboxDockOpen.set(true);
        this.toolboxTab.set('investigation');
    }

    /** Collapse both docks so the canvas takes the row; a second call restores them. */
    toggleCanvasMaximized(): void {
        const open = this.canvasMaximized();
        this.queryDockOpen.set(open);
        this.toolboxDockOpen.set(open);
    }

    // ── display options ──

    /** Pick (or with `null` clear) the stroke colour for a node kind. */
    setNodeColor(kind: string, color: string | null): void {
        this.recordHistory();
        const { [kind]: _old, ...rest } = this.nodeColors();
        this.nodeColors.set(color ? { ...rest, [kind]: color } : rest);
    }

    /** Pick (or with `null` clear) the stroke colour for a relationship kind. */
    setEdgeColor(kind: string, color: string | null): void {
        this.recordHistory();
        const { [kind]: _old, ...rest } = this.edgeColors();
        this.edgeColors.set(color ? { ...rest, [kind]: color } : rest);
    }

    /** Pick (or with `null` clear) the shape ("icon") for a node kind. */
    setNodeShape(kind: string, shape: string | null): void {
        this.recordHistory();
        const { [kind]: _old, ...rest } = this.nodeShapes();
        this.nodeShapes.set(shape ? { ...rest, [kind]: shape } : rest);
    }

    /** Pick (or with `null` clear) the line pattern for a relationship kind. */
    setEdgePattern(kind: string, pattern: EdgePattern | null): void {
        this.recordHistory();
        const { [kind]: _old, ...rest } = this.edgePatterns();
        this.edgePatterns.set(pattern ? { ...rest, [kind]: pattern } : rest);
    }

    /** Pick (or with `null` clear) the line width for a relationship kind. */
    setEdgeSize(kind: string, size: number | null): void {
        this.recordHistory();
        const { [kind]: _old, ...rest } = this.edgeSizes();
        this.edgeSizes.set(size != null ? { ...rest, [kind]: size } : rest);
    }

    /** Pick the node size in px (undoable). The size is the analyst's alone: nothing scales it. */
    setNodeSize(size: number): void {
        this.recordHistory();
        this.nodeSize.set(size);
    }

    /** Pick how far the layout spreads nodes (undoable); the layout re-runs. */
    setLayoutSpacing(spacing: number): void {
        this.recordHistory();
        this.layoutSpacing.set(spacing);
    }

    /** Replace the conditional-formatting rules (undoable). */
    setNodeRules(rules: NodeRule[]): void {
        this.recordHistory();
        this.nodeRules.set(rules);
    }

    /** Pick a graph layout (tree layouts are gated on {@link isTreeShaped} in the template). */
    setLayout(id: GraphLayoutId): void {
        this.recordHistory();
        this.pickedLayout.set(id);
    }

    // ── undo/redo (Phase G, presentation state only — not the query/graph itself) ──

    private readonly history = signal<HistoryStack<PresentationSnapshot>>(emptyHistory());
    readonly canUndo = computed(() => this.history().past.length > 0);
    readonly canRedo = computed(() => this.history().future.length > 0);

    private snapshotPresentation(): PresentationSnapshot {
        return {
            search: this.search(),
            kindFilter: this.kindFilter(),
            timeColumn: this.timeColumn(),
            timeCutoff: this.timeCutoff(),
            collapsedRoots: this.collapsedRoots(),
            hiddenNodeIds: this.hiddenNodeIds(),
            nodeLabels: this.nodeLabels(),
            edgeLabels: this.edgeLabels(),
            allEdgeLabels: this.allEdgeLabels(),
            nodeColors: this.nodeColors(),
            edgeColors: this.edgeColors(),
            nodeShapes: this.nodeShapes(),
            edgePatterns: this.edgePatterns(),
            edgeSizes: this.edgeSizes(),
            nodeRules: this.nodeRules(),
            nodeSize: this.nodeSize(),
            layoutSpacing: this.layoutSpacing(),
            layoutId: this.pickedLayout() ?? this.layoutId(),
        };
    }

    private restorePresentation(s: PresentationSnapshot): void {
        this.search.set(s.search);
        this.kindFilter.set(s.kindFilter);
        this.timeColumn.set(s.timeColumn);
        this.timeCutoff.set(s.timeCutoff);
        this.collapsedRoots.set(s.collapsedRoots);
        this.hiddenNodeIds.set(s.hiddenNodeIds);
        this.nodeLabels.set(s.nodeLabels);
        this.edgeLabels.set(s.edgeLabels);
        this.allEdgeLabels.set(s.allEdgeLabels);
        this.nodeColors.set(s.nodeColors);
        this.edgeColors.set(s.edgeColors);
        this.nodeShapes.set(s.nodeShapes);
        this.edgePatterns.set(s.edgePatterns);
        this.edgeSizes.set(s.edgeSizes);
        this.nodeRules.set(s.nodeRules);
        this.nodeSize.set(s.nodeSize);
        this.layoutSpacing.set(s.layoutSpacing);
        this.pickedLayout.set(s.layoutId);
    }

    /** Call before a presentation-state mutation to make it undoable. */
    private recordHistory(): void {
        this.history.update((h) => pushHistory(h, this.snapshotPresentation()));
    }

    undoPresentation(): void {
        const result = undoHistory(this.history(), this.snapshotPresentation());
        if (!result) return;
        this.history.set(result.history);
        this.restorePresentation(result.state);
    }

    redoPresentation(): void {
        const result = redoHistory(this.history(), this.snapshotPresentation());
        if (!result) return;
        this.history.set(result.history);
        this.restorePresentation(result.state);
    }

    /** Ctrl/Cmd+Z undo, Ctrl/Cmd+Shift+Z (or Ctrl+Y) redo — ignored while typing in a form field. */
    onHistoryKeydown(e: KeyboardEvent): void {
        // Kept alive while another page shows (KEEP_ALIVE): that page's Ctrl+Z is not ours.
        if (!this.host.nativeElement.isConnected) return;
        const target = e.target as HTMLElement | null;
        if (target && ['INPUT', 'TEXTAREA'].includes(target.tagName)) return;
        if (this.onGrowExcludeKey(e, target)) return;
        if (this.onCanvasModeKey(e, target)) return;
        if (!(e.ctrlKey || e.metaKey)) return;
        if (e.key.toLowerCase() === 'z' && !e.shiftKey) {
            e.preventDefault();
            // LA-GROW-EXCLUDE-1: the last Grow / Exclude offers its own Undo first (the result line says Ctrl+Z).
            if (this.growExclude.undoable()) {
                void this.growExclude.undoLast();
                return;
            }
            this.undoPresentation();
        } else if ((e.key.toLowerCase() === 'z' && e.shiftKey) || e.key.toLowerCase() === 'y') {
            e.preventDefault();
            this.redoPresentation();
        }
    }

    private applyDisplay(display: GraphDisplayOptions | undefined): void {
        this.nodeLabels.set(display?.nodeLabels ?? false);
        this.edgeLabels.set(display?.edgeLabels ?? false);
        this.allEdgeLabels.set(display?.allEdgeLabels ?? false);
        this.nodeColors.set(display?.nodeColors ?? {});
        this.edgeColors.set(display?.edgeColors ?? {});
        this.nodeShapes.set(display?.nodeShapes ?? {});
        this.edgePatterns.set(display?.edgePatterns ?? {});
        this.edgeSizes.set(display?.edgeSizes ?? {});
        this.nodeRules.set(display?.nodeRules ?? []);
        this.nodeSize.set(display?.nodeSize ?? GRAPH_DEFAULT_NODE_SIZE);
        this.layoutSpacing.set(display?.layoutSpacing ?? GRAPH_DEFAULT_LAYOUT_SPACING);
    }

    // ── canvas tools: fit · fullscreen · collapse/expand ──

    fitToScreen(): void {
        this.graphView?.fitView();
    }

    /** Toggle fullscreen for the whole studio (`app`) or just the canvas zone (`graph`). */
    toggleFullscreen(zone: 'app' | 'graph'): void {
        const el = (zone === 'app' ? this.studioRoot : this.canvasZone)?.nativeElement;
        if (!el?.requestFullscreen) return; // unsupported environment (e.g. jsdom)
        if (document.fullscreenElement) void document.exitFullscreen();
        else void el.requestFullscreen();
    }

    onFullscreenChange(): void {
        const fs = document.fullscreenElement;
        this.fullscreen.set(
            fs && fs === this.studioRoot?.nativeElement
                ? 'app'
                : fs && fs === this.canvasZone?.nativeElement
                  ? 'graph'
                  : null,
        );
    }

    /** Hide everything downstream of the node (the detail popup's Collapse branch). */
    collapseBranch(id: string): void {
        if (this.collapsedRoots().includes(id)) return;
        this.recordHistory();
        this.collapsedRoots.set([...this.collapsedRoots(), id]);
    }

    expandBranch(id: string): void {
        this.recordHistory();
        this.collapsedRoots.set(this.collapsedRoots().filter((r) => r !== id));
    }

    expandAll(): void {
        this.collapsedRoots.set([]);
    }

    /** Collapse (`C`, right-click, §5.5): fold each node's pendant neighbours, or unfold it when already folded. */
    toggleCollapse(ids: string[]): void {
        const g = this.canvasData();
        if (!g || !ids.length) return;
        const folded = new Set(this.collapsedRoots());
        const unfold = ids.filter((id) => folded.has(id));
        const fold = ids.filter((id) => !folded.has(id) && foldableOf(g, id).length);
        if (!unfold.length && !fold.length) {
            this.drawAnnouncement.set('Nothing to collapse: no neighbour links only to the picked node.');
            return;
        }
        this.recordHistory();
        this.collapsedRoots.set([...this.collapsedRoots().filter((r) => !unfold.includes(r)), ...fold]);
        this.drawAnnouncement.set(`Collapsed ${fold.length}, unfolded ${unfold.length}.`);
    }

    // ── Clean up (§5.6) ──

    /** The clean-up seeds: the Investigation's seeds (hop 0) on the canvas, else the selection while exploring. */
    readonly cleanupSeeds = computed<string[]>(() => {
        const ws = this.investigation.canvas() ? this.investigation.workingSet() : null;
        if (!ws) return this.selectedOnCanvas();
        const seeds = new Set(ws.entities.filter((e) => e.hop === 0).map((e) => e.id));
        return (this.canvasData()?.nodes ?? []).filter((n) => rawIdsOf(n).some((r) => seeds.has(r))).map((n) => n.id);
    });
    /** Trim spares held nodes (and the seeds, added by the tool). */
    readonly cleanupSpare = computed<string[]>(() =>
        (this.canvasData()?.nodes ?? []).filter((n) => n.data.held).map((n) => n.id),
    );
    readonly cleanupCap = (): number => analysisNodeCapValue();

    async onCleanupApplied(a: CleanupApply): Promise<void> {
        if (this.investigation.canvas() !== null) {
            if (!this.writesToInvestigation()) return;
            const ws = this.investigation.workingSet();
            const nodes = (this.canvasData()?.nodes ?? []).filter((n) => a.ids.includes(n.id));
            const ids = [...new Set(nodes.flatMap((n) => idsInWorkingSet(n, ws)))];
            if (ids.length && !(await this.recordCleanup(ids, a.reason)))
                this.toastr.error(this.investigation.error() || 'The clean-up was refused.');
            return;
        }
        this.recordHistory();
        this.hiddenNodeIds.update((h) => [...new Set([...h, ...a.ids])]);
        this.toastr.info(`Hid ${a.ids.length} entities. Undo restores them.`);
    }

    /**
     * ⚑UIR-A3 (not yet confirmed by the operator) — the ONE place an Investigation records a clean-up: a `hide` step over
     * what the tool takes away, then an `annotate` step carrying the prefilled reason (a `hide` takes no reason). OP-1:
     * the server's `keep` only protects from exclusion, it never hides the rest, so Keep only selected is a `hide` too.
     */
    private async recordCleanup(ids: string[], reason: string): Promise<boolean> {
        if (!(await this.investigation.apply({ op: 'hide', ids }))) return false;
        return this.investigation.apply({ op: 'annotate', ids, note: reason });
    }

    onCleanupHighlight(e: GraphEmphasis | null): void {
        this.emphasis.set(e);
    }

    // ── the canvas pointer model: box select, double click, keyboard view moves (operator 2026-10-15) ──

    /**
     * A drag on the EMPTY canvas draws a selection box instead of panning (the Minimap, the wheel and the arrow keys move
     * the view); a double click toggles expand / fold; the view re-fits after a data change. Opt-in on the shared host.
     */
    readonly canvasInteraction = computed<GraphViewInteraction>(() => canvasInteractionFor(this.canvasMode()));

    /** What the box (or Select all) picked. Read through {@link selectedOnCanvas}: a hide, a fold or a new run drops what is gone. */
    private readonly selectedNodeIds = signal<string[]>([]);
    readonly selectedOnCanvas = computed<string[]>(() => {
        const ids = this.selectedNodeIds();
        if (!ids.length) return [];
        const drawn = new Set((this.canvasData()?.nodes ?? []).map((n) => n.id));
        const kept = ids.filter((id) => drawn.has(id));
        return kept.length === ids.length ? ids : kept;
    });
    /** The selection spoken in an sr-only live region: the count is otherwise only visible in the toolbar. */
    readonly selectionAnnouncement = signal('');
    /** Nodes one 'Expand from the selected nodes' will fetch in exploration (each is its own request). */
    readonly selectionExpandCap = 25;

    private announceSelection(n: number): void {
        this.selectionAnnouncement.set(n ? `${n} ${n === 1 ? 'node' : 'nodes'} selected` : 'Selection cleared');
    }

    onSelectionChange(ids: string[]): void {
        this.selectedNodeIds.set(ids);
        this.announceSelection(ids.length);
    }

    clearSelection(): void {
        if (!this.selectedNodeIds().length) return;
        this.selectedNodeIds.set([]);
        this.announceSelection(0);
    }

    /** Every drawn node (a super-node is a stand-in, not a node an op can name). The keyboard path to a box select. */
    selectAll(): void {
        const ids = (this.canvasData()?.nodes ?? []).filter((n) => n.data.kind !== SUPER_NODE_KIND).map((n) => n.id);
        this.selectedNodeIds.set(ids);
        this.announceSelection(ids.length);
    }

    /** Every drawn node not selected now, and none of those that are. */
    invertSelection(): void {
        const picked = new Set(this.selectedOnCanvas());
        const ids = (this.canvasData()?.nodes ?? [])
            .filter((n) => n.data.kind !== SUPER_NODE_KIND && !picked.has(n.id))
            .map((n) => n.id);
        this.selectedNodeIds.set(ids);
        this.announceSelection(ids.length);
    }

    /** The selection plus every drawn node one link away from it. */
    addNeighboursToSelection(): void {
        const picked = new Set(this.selectedOnCanvas());
        if (!picked.size) return;
        const g = this.canvasData();
        const out = new Set(picked);
        for (const e of g?.edges ?? []) {
            if (picked.has(e.source)) out.add(e.target);
            if (picked.has(e.target)) out.add(e.source);
        }
        const drawn = new Set((g?.nodes ?? []).filter((n) => n.data.kind !== SUPER_NODE_KIND).map((n) => n.id));
        const ids = [...out].filter((id) => drawn.has(id));
        this.selectedNodeIds.set(ids);
        this.announceSelection(ids.length);
    }

    private selectedNodes(): G6GraphData['nodes'] {
        const picked = new Set(this.selectedOnCanvas());
        return (this.canvasData()?.nodes ?? []).filter((n) => picked.has(n.id));
    }

    /** The selection's raw ids that are entities of the open Investigation's Working Set - what an op may name (else 422). */
    private selectedWorkingSetIds(): string[] {
        const ws = this.investigation.workingSet();
        return [...new Set(this.selectedNodes().flatMap((n) => idsInWorkingSet(n, ws)))];
    }

    /** True while the canvas draws an open Investigation's Working Set: ops are LOGGED steps and need the write capability. */
    private writesToInvestigation(): boolean {
        if (this.investigation.canvas() === null) return false;
        if (!this.lens.canManageIncidents()) {
            this.toastr.info('This changes the Investigation log and needs the Incident management capability.');
            return false;
        }
        return true;
    }

    /**
     * An expand over `nodes` as the Investigation's own logged `expand` step - the same request, presets and one-hop
     * bookkeeping as the Investigation tab's Expand, so four-eyes, masking and the budget gates apply unchanged.
     */
    private async expandInInvestigation(nodes: G6GraphData['nodes']): Promise<boolean> {
        const ws = this.investigation.workingSet();
        const ids = [...new Set(nodes.flatMap((n) => idsInWorkingSet(n, ws)))];
        if (!ids.length) {
            this.toastr.info('None of these nodes is in the Working Set.');
            return false;
        }
        const ok = await this.investigation.apply({
            op: 'expand',
            ids,
            ...this.investigation.withPresets(this.degreePresets(), {}),
        });
        if (!ok) {
            this.toastr.error(this.investigation.error() || 'The expand was refused.');
            return false;
        }
        const step = this.investigation.lastStep();
        if (step?.status === 'pending' && step.pending?.id) this.investigation.markOneHop(step.pending.id, ids);
        return true;
    }

    /** The selection bar's "Expand from the N selected nodes". */
    async expandFromSelected(): Promise<void> {
        const nodes = this.selectedNodes();
        if (!nodes.length) return;
        if (this.investigation.canvas() !== null) {
            if (this.writesToInvestigation()) await this.expandInInvestigation(nodes);
            return;
        }
        // Exploration: one hop per node, each its own fetch, so the number is bounded and said.
        const todo = nodes.filter((n) => !this.expandedNodeIds().includes(n.id));
        if (!todo.length) {
            this.toastr.info('Every selected node is already expanded.');
            return;
        }
        const batch = todo.slice(0, this.selectionExpandCap);
        for (const n of batch) await this.expandNode(n.id, n.data.label, n.data.spellings);
        if (todo.length > batch.length)
            this.toastr.info(`Expanded the first ${batch.length} of ${todo.length} selected nodes - run it again for the rest.`);
    }

    /** "Hide selected": a LOGGED `hide` step inside an Investigation, a presentation hide (undoable) in exploration. */
    async hideSelected(): Promise<void> {
        const nodes = this.selectedNodes();
        if (!nodes.length) return;
        if (this.investigation.canvas() !== null) {
            if (!this.writesToInvestigation()) return;
            const ids = this.selectedWorkingSetIds();
            if (!ids.length) return;
            if (await this.investigation.apply({ op: 'hide', ids })) this.clearSelection();
            else this.toastr.error(this.investigation.error() || 'The hide was refused.');
            return;
        }
        this.recordHistory();
        this.hiddenNodeIds.update((h) => [...new Set([...h, ...nodes.map((n) => n.id)])]);
        this.clearSelection();
    }

    /** "Exclude selected" (Investigation only): a logged `exclude` step with a required reason, asked in the shared reason prompt. */
    excludeSelected(): void {
        if (this.investigation.canvas() === null || !this.writesToInvestigation()) return;
        const ids = this.selectedWorkingSetIds();
        if (!ids.length) return;
        this.dialog
            .open<EntityListReasonDialog, EntityListReasonData, string | undefined>(EntityListReasonDialog, {
                width: '28rem',
                data: {
                    title: `Exclude ${ids.length} ${ids.length === 1 ? 'entity' : 'entities'}`,
                    message: 'They leave the Working Set and their links go with them. The step is logged with this reason.',
                    confirmLabel: 'Exclude',
                    destructive: true,
                    maxLength: 200,
                },
            })
            .afterClosed()
            .subscribe(async (reason) => {
                if (!reason) return;
                if (await this.investigation.apply({ op: 'exclude', ids, reason })) this.clearSelection();
                else this.toastr.error(this.investigation.error() || 'The exclusion was refused.');
            });
    }

    /**
     * A double click on a node toggles it. Not yet expanded: fetch its neighbours (the node dialog's Expand neighbours, or the
     * Investigation's logged Expand). Already expanded: fold the branch it brought in; a second double click unfolds it.
     * Folding is a presentation action and exists only while exploring (the Investigation's Working Set is sealed server-side).
     */
    async onNodeDblClick(id: string): Promise<void> {
        const shown = this.displayed()?.nodes.find((n) => n.id === id);
        if (shown?.data.kind === SUPER_NODE_KIND) {
            this.toggleSuperNode(id);
            return;
        }
        const node = this.canvasData()?.nodes.find((n) => n.id === id);
        if (!node) return;
        if (this.investigation.canvas() !== null) {
            if (node.data.unexpanded) {
                if (this.writesToInvestigation()) await this.expandInInvestigation([node]);
                return;
            }
        }
        if (this.collapsedRoots().includes(id)) {
            this.expandBranch(id);
            this.drawAnnouncement.set(`Unfolded the branch of ${node.data.label}.`);
            return;
        }
        const canExpand = !!this.graphSources.byId(this.sourceId())?.expand;
        if (this.investigation.canvas() === null && !this.expandedNodeIds().includes(id) && canExpand) {
            await this.expandNode(id, node.data.label, node.data.spellings);
            this.drawAnnouncement.set(`Expanded ${node.data.label}.`);
            return;
        }
        const g = this.canvasData();
        if (g && foldableOf(g, id).length) {
            this.collapseBranch(id);
            this.drawAnnouncement.set(`Folded the branch of ${node.data.label}.`);
        } else this.drawAnnouncement.set(`${node.data.label} has no branch to fold.`);
    }

    /**
     * The keyboard equivalents of the pointer gestures (WCAG 2.5.7 Dragging Movements) while the graph region has focus:
     * arrows move the view (Shift = a larger step), + and - zoom, Ctrl/Cmd+A selects every node, Escape clears the selection.
     */
    onGraphKeydown(e: KeyboardEvent): void {
        const t = e.target as HTMLElement | null;
        if (t && ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName)) return;
        const step = e.shiftKey ? 240 : 80;
        const move: Record<string, [number, number]> = {
            ArrowLeft: [step, 0],
            ArrowRight: [-step, 0],
            ArrowUp: [0, step],
            ArrowDown: [0, -step],
        };
        if (e.key in move && !e.ctrlKey && !e.metaKey && !e.altKey) {
            e.preventDefault();
            this.graphView?.panBy(...move[e.key]);
        } else if ((e.key === '+' || e.key === '=') && !e.ctrlKey && !e.metaKey) {
            e.preventDefault();
            this.graphView?.zoomBy(1.2);
        } else if ((e.key === '-' || e.key === '_') && !e.ctrlKey && !e.metaKey) {
            e.preventDefault();
            this.graphView?.zoomBy(1 / 1.2);
        } else if (e.key.toLowerCase() === 'a' && (e.ctrlKey || e.metaKey) && !e.shiftKey) {
            e.preventDefault();
            this.selectAll();
        } else if (e.key.toLowerCase() === 'c' && !e.ctrlKey && !e.metaKey && !e.altKey) {
            e.preventDefault();
            this.toggleCollapse(this.selectedNodeIds());
        } else if (e.key === 'Escape' && this.selectedNodeIds().length) {
            e.preventDefault();
            this.clearSelection();
        }
    }

    // ── element details (canvas click → full-detail popup) ──

    /** WAI-ARIA tabs: ArrowLeft/Right (wrapping), Home, End move AND activate; focus follows (roving tabindex). */
    onToolboxTabKey(ev: KeyboardEvent): void {
        const tabs = this.toolboxTabs;
        const at = tabs.findIndex((t) => t.id === this.toolboxTab());
        const to =
            ev.key === 'ArrowRight'
                ? (at + 1) % tabs.length
                : ev.key === 'ArrowLeft'
                  ? (at - 1 + tabs.length) % tabs.length
                  : ev.key === 'Home'
                    ? 0
                    : ev.key === 'End'
                      ? tabs.length - 1
                      : -1;
        if (to < 0) return;
        ev.preventDefault();
        this.toolboxTab.set(tabs[to].id);
        (ev.currentTarget as HTMLElement).parentElement
            ?.querySelector<HTMLElement>(`#la-toolbox-tab-${tabs[to].id}`)
            ?.focus();
    }

    /**
     * A node chosen in the list view (click, Enter or Space). The list is an explicit choice, not a pointer on a
     * moving canvas, so it picks the Investigation entity AND opens the detail at once — the canvas's second-click
     * convention made Enter on a fresh row only select it (live run 5).
     */
    onListNodeSelect(id: string): void {
        if (this.investigation.active()) {
            const picked = this.canvasData()?.nodes.find((n) => n.id === id);
            if (picked && this.investigation.selected()?.id !== id) this.investigation.selected.set(picked);
        }
        this.onNodeClick(id);
    }

    onNodeClick(id: string): void {
        // A super-node exists only in `displayed()`, so the lookup below would miss it and the click
        // would do nothing at all. Clicking a stand-in means "show me what you are standing in for".
        if (this.displayed()?.nodes.find((n) => n.id === id)?.data.kind === SUPER_NODE_KIND) {
            this.toggleSuperNode(id);
            return;
        }
        // LA-10: while an Investigation is open a click picks the entity its ops act on (Investigation tab); a click on
        // the entity ALREADY picked opens its detail (Risk / Enrichment / Actions), the second-click convention.
        if (this.investigation.active()) {
            const picked = this.canvasData()?.nodes.find((n) => n.id === id);
            if (picked && this.investigation.selected()?.id !== id) {
                // No dock open / tab switch here: reflowing the panels moved the canvas under the pointer, so the
                // second click missed the node (third live run). Tabs change only on an explicit action.
                this.investigation.selected.set(picked);
                return;
            }
            if (!picked || !this.investigation.canvas()) return;
        }
        this.openNodeDetail(id);
    }

    /** The node detail dialog: rows, branch/expand actions (exploration), and the Risk / Enrichment / Actions sections. */
    /** The Entity Profile page beside this route (`…/link-analysis` -> `…/entities/<type>/<key>`); null when unknown. */
    private entityPageOf(type: string | undefined, key: string): UrlTree | null {
        const segs = (this.route.snapshot?.pathFromRoot ?? []).flatMap((r) => r.url.map((u) => u.path));
        if (!type || !key || segs[segs.length - 1] !== 'link-analysis') return null;
        return this.router.createUrlTree(['/', ...segs.slice(0, -1), 'entities', type, key]);
    }

    private openNodeDetail(id: string): void {
        const onInvestigation = this.investigation.canvas() !== null;
        const g = onInvestigation ? this.canvasData() : this.baseGraph();
        const node = g?.nodes.find((n) => n.id === id);
        if (!g || !node) return;
        this.brush.fromLink([id], this.brushEntityTypes());
        const outgoing = g.edges.filter((e) => e.source === id);
        const incoming = g.edges.filter((e) => e.target === id);
        const labelIn = (n: string) => g.nodes.find((x) => x.id === n)?.data.label ?? this.labelOf(n);
        const neighbors = [...new Set([...outgoing.map((e) => e.target), ...incoming.map((e) => e.source)])].map(
            labelIn,
        );
        const collapsed = this.collapsedRoots().includes(id);
        const objectRef = node.data.objectRef;
        const source = this.graphSources.byId(this.sourceId());
        const ids = this.serverIds();
        const invId = this.graphRunInvestigationId();
        const ws = this.investigation.workingSet();
        const key = rawIdsOf(node)[0] ?? node.data.label;
        const insight: NodeInsightContext = {
            nodeId: id,
            label: node.data.label,
            key,
            listKey: ws?.exploreAliases?.[key],
            profileId: this.profileId() ?? 'generic',
            entityType: this.profile().investigate?.entityType,
            investigationId: invId,
            graphKey: invId && ws ? `${invId}:${ws.hash}` : null,
            serverId: ids?.serverNode(id) ?? null,
            serverNodeIds: (this.canvasData()?.nodes ?? [])
                .map((n) => ids?.serverNode(n.id))
                .filter((x): x is string => !!x),
            originLabel: (sid) => {
                const cid = ids?.node(sid);
                return cid ? labelIn(cid) : sid;
            },
            canRunGraph: this.canRunGraphOnServer(),
            canManageLists: this.lens.canManageIncidents(),
            onListChanged: () => this.investigation.entityListsChanged.update((n) => n + 1),
            canHide: !this.investigation.active(),
            entityPage: this.entityPageOf(this.profile().investigate?.entityType, rawIdsOf(node)[0] ?? node.data.label),
        };
        this.dialog
            .open(ElementDetailDialog, {
                width: '36rem',
                data: {
                    title: node.data.label,
                    subtitle: node.data.kind,
                    rows: [
                        { label: 'ID', value: id },
                        {
                            label: 'Links',
                            value: `${outgoing.length + incoming.length} (${outgoing.length} out · ${incoming.length} in)`,
                        },
                        {
                            label: 'Neighbors',
                            value:
                                neighbors.slice(0, 8).join(', ') +
                                (neighbors.length > 8 ? ` … +${neighbors.length - 8}` : ''),
                        },
                        // The legend's ringed node, with its sealed contact count when served.
                        ...(node.data.highConnectivity
                            ? [{ label: 'Connectivity', value: this.hubBadgeFor(ids?.serverNode(id) ?? id, node.data.contacts) }]
                            : []),
                        ...(node.data.unexpanded ? [{ label: 'Expansion', value: 'Not expanded yet' }] : []),
                        // LA-08: which Datasets this (possibly merged) entity was projected from.
                        ...(node.data.provenance?.length
                            ? [{ label: 'Datasets', value: node.data.provenance.join(', ') }]
                            : []),
                        // LA-08: a node mapping's `attributes` values (raw), one row per column.
                        ...Object.entries(node.data.attrs ?? {}).map(([k, v]) => ({ label: k, value: v ?? '—' })),
                    ],
                    branch: collapsed ? 'expand' : foldableOf(g, id).length ? 'collapse' : undefined,
                    objectRef,
                    pivotViews: objectRef ? ['map'] : undefined,
                    expandable: !onInvestigation && !!source?.expand,
                    extension: { component: NodeInsightComponent, inputs: { ctx: insight } },
                },
            })
            .afterClosed()
            .subscribe((action: ElementDetailResult) => {
                if (action === 'focus') this.focusNode(id);
                else if (action === 'collapse') this.collapseBranch(id);
                else if (action === 'expand') this.expandBranch(id);
                else if (action === 'expand-neighbors') this.expandNode(id, node.data.label, node.data.spellings);
                else if (action === 'hide') {
                    this.recordHistory();
                    this.hiddenNodeIds.update((h) => (h.includes(id) ? h : [...h, id]));
                }
                else if (action === 'open-record' && objectRef) {
                    this.router.navigate(['/' + (objectRef.type === 'CASE' ? 'cases' : 'incidents'), objectRef.id]);
                }
            });
    }

    /**
     * LA-11: walk paths server-side (`POST /inv/traversal/recursive-paths`) from the picked node, optionally to a
     * second one, over one edge mapping of the loaded query. The server wants the RAW value, so a node sends its
     * first raw spelling (its label for a non-projected node). The answer is merged into the working set —
     * hops beyond it are added — and every returned path is highlighted; its fences are shown by the toolbox.
     */
    async findPaths(req: FindPathsRequest): Promise<void> {
        const target = this.traversalTargets()[req.mapping];
        const g = this.graph();
        if (!target || !g) return;
        const raw = (id: string): string => {
            const n = g.nodes.find((x) => x.id === id);
            return n?.data.spellings?.[0] ?? n?.data.label ?? id;
        };
        this.serverPathsBusy.set(true);
        try {
            const res = await firstValueFrom(
                this.inv.recursivePaths({
                    dataset: target.dataset,
                    sourceCol: target.sourceCol,
                    targetCol: target.targetCol,
                    startNode: raw(req.from),
                    targetNode: req.to ? raw(req.to) : undefined,
                    maxDepth: req.maxDepth,
                    direction: req.direction,
                    filter: target.filter,
                    limit: SERVER_PATH_LIMIT,
                }),
            );
            const { graph, state } = recursivePathsToGraph(res, g, target.idMappings);
            this.graph.set(graph);
            this.serverPaths.set(state);
            this.emphasis.set(
                state.paths.length
                    ? {
                          nodeIds: [...new Set(state.paths.flatMap((p) => p.nodeIds))],
                          edgeIds: [...new Set(state.paths.flatMap((p) => p.edgeIds))],
                      }
                    : null,
            );
        } catch (err) {
            this.serverPaths.set(null);
            this.toastr.error(invErrorMessage(err, 'The server path search failed.'));
        } finally {
            this.serverPathsBusy.set(false);
        }
    }

    /**
     * LA-14b: run the toolbox's branching motif server-side (`POST /inv/pattern/branching`) over the WHOLE Dataset of
     * one edge mapping — offered when the projection was truncated, because a structuring motif's small legs are the
     * first links the projection's cap cuts. The matches are merged into the working set (legs beyond it are added)
     * and highlighted, exactly as LA-11's server paths are.
     */
    async runPatternOnServer(req: ServerPatternRequest): Promise<void> {
        if (req.overInvestigation) return this.runPatternOverInvestigation(req);
        const target = this.traversalTargets()[req.mapping];
        const g = this.graph();
        if (!target || !g) return;
        this.serverPatternBusy.set(true);
        try {
            const res = await firstValueFrom(
                this.inv.branchingPattern({
                    dataset: target.dataset,
                    sourceCol: target.sourceCol,
                    targetCol: target.targetCol,
                    // The toolbox pre-fills a pack's kind / time column from the domain profile when blank.
                    linkKindCol: req.linkKindCol ?? target.linkKindCol,
                    timeCol: req.timeCol ?? (this.timeColumn() || undefined),
                    stages: req.stages,
                    filter: target.filter,
                }),
            );
            const { graph, state } = branchingResultToGraph(res, g, target.idMappings);
            this.graph.set(graph);
            this.serverPattern.set(state);
            this.emphasis.set(
                state.matches.length
                    ? {
                          nodeIds: [...new Set(state.matches.flatMap((m) => m.nodeIds))],
                          edgeIds: [...new Set(state.matches.flatMap((m) => m.edgeIds))],
                      }
                    : null,
            );
        } catch (err) {
            this.serverPattern.set(null);
            this.toastr.error(invErrorMessage(err, 'The server pattern search failed.'));
        } finally {
            this.serverPatternBusy.set(false);
        }
    }

    /**
     * Burst / periodicity (`POST /inv/pattern/temporal`) over the WHOLE Dataset of one edge mapping. A read: the
     * findings are placed on the canvas and highlighted, and the graph itself is not changed.
     */
    async runTemporal(req: TemporalRunRequest): Promise<void> {
        const target = this.traversalTargets()[req.mapping];
        const g = this.graph();
        const timeCol = this.timeColumn();
        if (!target || !g || !timeCol) return;
        this.temporalBusy.set(true);
        this.temporalError.set('');
        this.temporalSealed.set(null);
        this.temporalSealError.set('');
        try {
            const res = await firstValueFrom(
                this.inv.temporalPattern({
                    dataset: target.dataset,
                    sourceCol: target.sourceCol,
                    targetCol: target.targetCol,
                    timeCol,
                    mode: req.mode,
                    series: req.series,
                    filter: target.filter,
                    windowSeconds: req.mode === 'burst' ? req.windowSeconds : undefined,
                    minEvents: req.minEvents,
                    maxCv: req.mode === 'periodicity' ? req.maxCv : undefined,
                }),
            );
            const state = temporalResultToState(res, g, target.idMappings);
            this.lastTemporalRun = req;
            this.temporalFindings.set(state);
            this.emphasis.set(this.focusTemporal(state));
        } catch (err) {
            this.temporalFindings.set(null);
            this.temporalError.set(invErrorMessage(err, 'The burst and periodicity scan failed.'));
        } finally {
            this.temporalBusy.set(false);
        }
    }

    /**
     * "Seal these findings": a `temporal` op on the open Investigation, with the knobs of the scan the panel is showing.
     * The server re-runs the scan over the Investigation's own edge columns, so what is sealed is its answer, not this
     * panel's rows; the Working Set does not move.
     */
    async sealTemporal(): Promise<void> {
        const req = this.lastTemporalRun;
        if (!req || !this.temporalFindings()) return;
        this.temporalSealed.set(null);
        this.temporalSealError.set('');
        const burst = req.mode === 'burst';
        const ok = await this.investigation.apply({
            op: 'temporal',
            mode: req.mode,
            series: req.series,
            minEvents: req.minEvents,
            ...(burst ? { windowSeconds: req.windowSeconds } : { maxCv: req.maxCv }),
        });
        const step = this.investigation.lastStep();
        if (ok && step?.temporalFindings)
            this.temporalSealed.set({
                step: step.step,
                count: step.temporalFindings.count,
                outsideWorkingSet: step.temporalFindings.outsideWorkingSet,
                fingerprint: step.temporalFindings.fingerprint,
            });
        else this.temporalSealError.set(this.investigation.error() || 'The findings could not be sealed.');
    }

    private focusTemporal(state: TemporalFindingsState): GraphEmphasis | null {
        const sel = allTemporalSelection(state);
        return sel.edgeIds.length || sel.nodeIds.length ? sel : null;
    }

    /**
     * Phase E incremental expand: fetch `nodeLabel`'s one-hop neighborhood via the current source's
     * `expand()` and merge it into the loaded graph — filters/analysis state survives (unlike `run()`,
     * which replaces the graph and resets them).
     */
    async expandNode(
        id: string,
        nodeLabel: string,
        spellings?: string[],
        keep?: (e: G6GraphData['edges'][number]) => boolean,
    ): Promise<void> {
        const run = this.lastRun();
        const source = this.graphSources.byId(this.sourceId());
        if (!run || !source?.expand) return;
        try {
            const read = await source.expand(id, nodeLabel, run.query, spellings);
            // LA-GROW-EXCLUDE-1: a Basic Grow keeps only its direction and link kinds.
            const extra = keep ? filterExpansion(read, id, keep) : read;
            this.expandSource.set((extra as ProjectedGraph).source ?? null);
            this.graph.update((g) => (g ? mergeGraphs([g, extra]) : extra));
            this.expandedNodeIds.update((ids) => (ids.includes(id) ? ids : [...ids, id]));
            // LA-02: an expand that hit the server's row limit, or the browser's node cap, leaves the
            // working set INCOMPLETE. `mergeGraphs` returns a bare `G6GraphData` and structurally drops
            // `truncated`, so the flag has to be carried onto the signal here or the overlay keeps
            // saying "complete" over a partial neighbourhood - a false negative presented as a finding.
            // Monotonic on purpose: only a fresh `run()` replaces the graph and so resets it.
            if ((extra as ProjectedGraph).truncated) this.truncated.set(true);
        } catch (err) {
            this.toastr.error(invErrorMessage(err, 'Could not fetch neighbors for this node.'));
        }
    }

    onEdgeClick(id: string): void {
        const g = this.displayed();
        const edge = g?.edges.find((e) => e.id === id);
        if (!g || !edge) return;
        const count = (edge.data as { count?: number }).count;
        this.dialog
            .open(ElementDetailDialog, {
                width: '28rem',
                data: {
                    title: `${this.labelOf(edge.source)} → ${this.labelOf(edge.target)}`,
                    subtitle: baseEdgeKind(edge.data.kind),
                    rows: [
                        { label: 'From', value: this.labelOf(edge.source) },
                        { label: 'To', value: this.labelOf(edge.target) },
                        { label: 'Relationship', value: baseEdgeKind(edge.data.kind) },
                        ...(count ? [{ label: 'Folded rows', value: String(count) }] : []),
                        { label: 'ID', value: id },
                    ],
                },
            })
            .afterClosed()
            .subscribe((action: ElementDetailResult) => {
                if (action === 'focus') this.emphasis.set({ nodeIds: [edge.source, edge.target], edgeIds: [id] });
            });
    }

    /** Bottom-panel row click — focus the element on the canvas (rows carry their graph id). */
    onTableRow(row: Record<string, unknown>): void {
        const id = String(row['id'] ?? '');
        if (!id) return;
        if (this.tableMode() === 'nodes') {
            this.focusNode(id);
            return;
        }
        const edge = this.displayed()?.edges.find((e) => e.id === id);
        if (edge) this.emphasis.set({ nodeIds: [edge.source, edge.target], edgeIds: [id] });
    }

    // ── search & filters ──

    // `search` deliberately isn't recorded per keystroke here (Phase G) — that would flood the undo
    // stack with one entry per character typed. `clearFilters` still records, since it's a single action.
    onSearch(text: string): void {
        this.search.set(text);
        const g = this.displayed();
        if (!g || !text.trim()) {
            this.emphasis.set(null);
            return;
        }
        this.emphasis.set({ nodeIds: searchNodes(g, text), edgeIds: [] });
    }

    toggleKind(kind: string, on: boolean): void {
        this.recordHistory();
        const all = this.nodeKinds();
        const current = this.kindFilter().length ? this.kindFilter() : all;
        const next = on ? [...new Set([...current, kind])] : current.filter((k) => k !== kind);
        this.kindFilter.set(next.length === all.length ? [] : next);
    }

    kindOn(kind: string): boolean {
        return !this.kindFilter().length || this.kindFilter().includes(kind);
    }

    /** Pick the timeline's `attrs` column (or '' to turn it off); resets the cutoff to the column's max. */
    setTimeColumn(col: string): void {
        this.recordHistory();
        this.timeColumn.set(col);
        this.timeCutoff.set(col ? (this.timeExtent()?.[1] ?? null) : null);
        // The playback strip leaves with the column; so must the slice it had highlighted.
        if (!col) this.emphasis.set(null);
    }

    /** Move the timeline cutoff (epoch millis, from the slider). Not recorded per-drag (like search). */
    setTimeCutoff(cutoff: number): void {
        this.timeCutoff.set(cutoff);
    }

    /** Short date-time label for the timeline slider readout. */
    timeLabel(t: number): string {
        return new Date(t).toISOString().slice(0, 16).replace('T', ' ');
    }

    clearFilters(): void {
        this.recordHistory();
        this.kindFilter.set([]);
        this.search.set('');
        this.timeColumn.set('');
        this.timeCutoff.set(null);
        this.emphasis.set(null);
    }

    /** Emphasize a single node on the canvas (pivot resolution, table-row click, node-detail focus). */
    focusNode(id: string): void {
        this.emphasis.set({ nodeIds: [id], edgeIds: [] });
    }

    // ── export ──

    /** Download the displayed graph as `link-analysis.json` (the shared G6GraphData shape). */
    exportJson(): void {
        const g = this.canvasData();
        if (!g) return;
        this.download(
            URL.createObjectURL(new Blob([JSON.stringify(g, null, 2)], { type: 'application/json' })),
            `${this.exportBaseName()}.json`,
        );
    }

    /** Download the rendered canvas as `link-analysis.png`. */
    async exportPng(): Promise<void> {
        const dataUri = await this.graphView?.exportPng();
        if (dataUri) this.download(dataUri, `${this.exportBaseName()}.png`);
        else this.toastr.warning('Nothing rendered to export yet.');
    }

    /** Download the rendered graph as a standalone `link-analysis.svg` (Phase F). */
    exportSvg(): void {
        const svg = this.graphView?.exportSvg();
        if (svg) this.download(URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' })), `${this.exportBaseName()}.svg`);
        else this.toastr.warning('Nothing rendered to export yet.');
    }

    /** Download the displayed graph as generic `link-analysis.graphml` (Phase F, no vendor dialect). */
    exportGraphml(): void {
        const g = this.canvasData();
        if (!g) return;
        this.download(
            URL.createObjectURL(new Blob([toGraphml(g)], { type: 'application/xml' })),
            `${this.exportBaseName()}.graphml`,
        );
    }

    /** `link-analysis`, or the open Investigation's title / id — the file name stem of every export. */
    private exportBaseName(): string {
        const id = this.investigation.activeId();
        if (!this.workingSetCanvas() || !id) return 'link-analysis';
        const title = this.investigation.header()?.title ?? this.investigation.refs().find((r) => r.id === id)?.title;
        const stem = (title ?? id).replace(/[^\w.-]+/g, '-').replace(/^-+|-+$/g, '');
        return `link-analysis-${stem || id}`;
    }

    private download(href: string, filename: string): void {
        const a = document.createElement('a');
        a.href = href;
        a.download = filename;
        a.click();
        if (href.startsWith('blob:')) URL.revokeObjectURL(href);
    }

    // ── saved views ──

    async saveView(): Promise<void> {
        this.saveForm.markAllAsTouched();
        if (this.saveForm.invalid) return;
        const { name, description } = this.saveForm.getRawValue();
        const view = this.viewFromState(
            name
                .trim()
                .toLowerCase()
                .replace(/[^a-z0-9]+/g, '-'),
            name.trim(),
            description.trim() || undefined,
        );
        if (!view) return;
        this.saving.set(true);
        try {
            await firstValueFrom(this.viewsService.save(view, { update: this.views().some((v) => v.id === view.id) }));
            this.views.set([...this.views().filter((v) => v.id !== view.id), view]);
            this.rememberOpenView(view.id); // what is on screen is now this saved view: a refresh reopens it
            this.saveForm.reset({ name: '', description: '' });
            this.saveTrigger?.closeMenu();
            this.toastr.success(`Saved the case “${view.name}”.`);
        } catch (err) {
            this.toastr.error(apiErrorMessage(err, 'Saving the view failed.'));
        } finally {
            this.saving.set(false);
        }
    }

    /** The screen's current state as a saved view under `id` — null (with the reason shown) when the query
     *  form cannot build a query. */
    private viewFromState(id: string, name: string, description: string | undefined): LinkAnalysisView | null {
        const q = this.queryPanel?.buildQuery() ?? { error: 'The query form is not ready.' };
        if ('error' in q) {
            this.loadError.set(q.error);
            return null;
        }
        return {
            id,
            name,
            description,
            sourceId: this.sourceId(),
            query: q,
            display: this.chosenDisplay(), // what the analyst chose travels with the view (the automatic look is derived afresh); reapplied on load
            layout: this.pickedLayout() ?? this.layoutId(),
            profile: this.profileId() === 'generic' ? undefined : this.profileId(),
            view: { plugins: this.viewOptions(), legend: this.legendOpen(), workingSet: this.workingSetOpen() },
            // LA-10: no server list exists — the view is where this screen remembers its Investigation ids.
            investigations: this.investigation.refs().length ? this.investigation.refs() : undefined,
            ...this.caseOfState(),
        };
    }

    /**
     * A case's lists and start (A-1b): the list the graph started from and the exclusion lists written from it, and the
     * start with every entity grown since (each re-drawn with its ring on open, at most {@link START_MAX_SEEDS}).
     */
    private caseOfState(): Pick<LinkAnalysisView, 'lists' | 'start'> {
        const { start: startList, exclusions } = this.caseLists();
        const lists = startList || exclusions.length ? { ...(startList ? { start: startList } : {}), ...(exclusions.length ? { exclusions } : {}) } : undefined;
        const s = this.caseStart();
        if (!s) return lists ? { lists } : {};
        const nodes = this.graph()?.nodes ?? [];
        const grown = this.expandedNodeIds()
            .map((id) => nodes.find((n) => n.id === id))
            .map((n) => (n ? rawIdsOf(n)[0] : undefined))
            .filter((v): v is string => !!v);
        // A list start re-reads the list's members as they are then: only what was grown beyond them is kept as values.
        const drawn = new Set(s.values ?? []);
        const values = s.listId
            ? [...new Set(grown.filter((v) => !drawn.has(v)))].slice(0, START_MAX_SEEDS)
            : [...new Set([...drawn, ...grown])].slice(0, START_MAX_SEEDS);
        return {
            ...(lists ? { lists } : {}),
            start: {
                datasetId: s.datasetId,
                ...(s.entityType ? { entityType: s.entityType } : {}),
                ...(s.listId ? { listId: s.listId } : {}),
                ...(values.length ? { values } : {}),
            },
        };
    }

    // ── Import as draft (operator decisions 2026-09-25; D5's Link Analysis view slice) ──

    /**
     * Adopt the transfer menu's draft as UNSAVED work (D1): the view's state loads exactly as "Load view"
     * does — re-running its read-only projection — and nothing is written until {@link saveDraft}. An
     * existing id (D6) also reads the stored copy: the banner's diff baseline and the Save's `If-Match`.
     */
    async onDraftImported(draft: LaImportDraft): Promise<void> {
        // `/bundle/preview` judges this view's `query.projection.datasetId` (ComponentIntegrity), so the
        // dialog's list is shown as-is; an unreadable preview arrives as null and reads "not checked".
        this.importDraft.set(draft);
        this.draftStored.set(null);
        this.draftIfMatch = undefined;
        if (draft.targetExists) {
            this.components.get('link-analysis-view', draft.id).subscribe({
                next: (def) => {
                    if (this.importDraft()?.id !== draft.id) return; // discarded or replaced meanwhile
                    this.draftStored.set(def?.content ?? null);
                    this.draftIfMatch = def?.contentHash;
                },
                error: (e) => this.toastr.error(apiErrorMessage(e, `Could not load view "${draft.id}"`)),
            });
        }
        await this.loadView(this.viewsService.fromContent(draft.id, draft.content));
    }

    /** Save the draft through the pane's own route (D2): create for a new id, else an update carrying
     *  `If-Match` (D6). A refused Save keeps the draft on the page. */
    async saveDraft(): Promise<void> {
        const draft = this.importDraft();
        if (!draft) return;
        if (draft.targetExists && !this.draftIfMatch) {
            this.toastr.warning(`The stored "${draft.id}" has not loaded, so the draft cannot be saved safely.`);
            return;
        }
        const incoming = this.viewsService.fromContent(draft.id, draft.content);
        const view = this.viewFromState(draft.id, incoming.name, incoming.description);
        if (!view) return;
        this.saving.set(true);
        try {
            // Re-check what is about to be WRITTEN (D3, operator 2026-10-06): advisory — the findings go to the
            // banner (so a refused Save shows them) and the Save goes ahead either way.
            const integrity = await recheckDraft(
                this.transfer,
                'link-analysis-view',
                view.id,
                this.viewsService.toContent(view),
            );
            if (this.importDraft() === draft) this.importDraft.set({ ...draft, integrity });
            await firstValueFrom(
                this.viewsService.save(view, { update: draft.targetExists, ifMatch: this.draftIfMatch }),
            );
            this.views.set([...this.views().filter((v) => v.id !== view.id), view]);
            this.clearDraft();
            this.rememberOpenView(view.id); // the draft is a saved view now
            const warning = draftSavedWarning(view.id, integrity);
            if (warning) this.toastr.warning(warning);
            else this.toastr.success(`Saved “${view.name}”.`);
        } catch (err) {
            this.toastr.error(apiErrorMessage(err, 'Saving the draft failed.'));
        } finally {
            this.saving.set(false);
        }
    }

    /** Drop the draft, writing nothing: an existing id goes back to its stored view; a new one leaves the
     *  canvas as unsaved exploration. */
    async discardDraft(): Promise<void> {
        const draft = this.importDraft();
        const stored = this.draftStored();
        this.clearDraft();
        if (draft?.targetExists && stored) await this.loadView(this.viewsService.fromContent(draft.id, stored));
    }

    private clearDraft(): void {
        this.importDraft.set(null);
        this.draftStored.set(null);
        this.draftIfMatch = undefined;
    }

    /**
     * The id of the SAVED view this page has open, or null: an imported draft, a query run from the Start box and
     * nothing at all are all null. It is mirrored into `?view=` so a refresh reopens the view (an unsaved graph is not
     * restorable: it is only what a query drew, and the query is the view).
     */
    readonly openViewId = signal<string | null>(null);

    /** Put (or with null drop) `?view=` in the address without adding a history entry. */
    private setViewParam(id: string | null): void {
        void this.router.navigate([], {
            relativeTo: this.route,
            queryParams: { view: id },
            queryParamsHandling: 'merge',
            replaceUrl: true,
        });
    }

    /**
     * A saved view is now what this page shows: remember it for a refresh. Not while an Investigation canvas is on screen:
     * that is reopened by its own `?investigation=`, and both must not be reopened on refresh.
     */
    private rememberOpenView(id: string): void {
        if (this.investigation.canvas()) return;
        this.openViewId.set(id);
        this.setViewParam(id);
    }

    /**
     * What is on screen is no longer a saved view (a query was run, an entity was investigated, an Investigation was
     * opened, a draft was imported), so a refresh must not reopen the old one over it. A no-op when no view is open.
     */
    private forgetOpenView(): void {
        if (!this.openViewId()) return;
        this.openViewId.set(null);
        this.setViewParam(null);
    }

    async loadView(view: LinkAnalysisView): Promise<void> {
        this.sourceId.set(view.sourceId);
        this.applyDisplay(view.display);
        this.pickedLayout.set(view.layout ?? null);
        this.profileControl.setValue(view.profile ?? activeDomainProfileId());
        // A saved predicate is part of the question: seed the builder so the analyst sees and can refine it.
        this.filterWhere.set(view.query.filter ? cloneGroup(view.query.filter) : emptyGroup());
        if (view.view?.plugins) this.viewOptions.set(canvasModeOptions(view.view.plugins));
        this.lassoMode.set(false);
        this.overlaysStated = { legend: view.view?.legend, workingSet: view.view?.workingSet };
        this.applyOverlayStart();
        this.queryPanel?.patchFormFromView(view);
        this.investigation.restore(view.investigations ?? []); // closes any open Investigation, synchronously
        // The imported draft (and only it) is not saved yet, so there is nothing for a refresh to reopen. After the
        // restore, because a view cannot be remembered while an Investigation canvas is still on screen.
        if (this.importDraft()?.id === view.id) this.forgetOpenView();
        else this.rememberOpenView(view.id);
        if (view.start && (await this.drawCaseStart(view))) return;
        await this.execute(view.sourceId, view.query);
        if (view.lists) this.caseLists.set({ start: view.lists.start, exclusions: view.lists.exclusions ?? [] });
    }

    /**
     * Open a case drawn from a Start (phase 7): its list's members as they are now plus the values it saved, each with one
     * ring, then its exclusion lists' members hidden again. False = the start cannot be drawn here (its Dataset no longer
     * starts a graph, or nothing was found): the caller reruns the view's query instead.
     */
    private async drawCaseStart(view: LinkAnalysisView): Promise<boolean> {
        const start = view.start!;
        await Promise.all([this.datasetsLoaded, this.startTypesLoaded, this.viewsLoaded]);
        const ds = this.startCatalogue().datasets.find((d) => d.id === start.datasetId);
        if (!ds) return false;
        let values = start.values ?? [];
        let entityType = start.entityType;
        if (start.listId) {
            try {
                const list = await firstValueFrom(this.inv.getEntityList(start.listId));
                values = [...new Set([...(list.members ?? []), ...values])];
                entityType ??= list.entityType || undefined;
            } catch {
                // the list is gone or unreadable: draw what the case saved
            }
        }
        const found = await this.drawStart(ds, values, entityType);
        if (!found.length) return false;
        this.caseStart.set({
            datasetId: ds.id,
            values: found,
            ...(entityType ? { entityType } : {}),
            ...(start.listId ? { listId: start.listId } : {}),
        });
        this.caseLists.set({ start: view.lists?.start, exclusions: view.lists?.exclusions ?? [] });
        await this.hideExcludedByCase(view.lists?.exclusions ?? []);
        return true;
    }

    /** Hide the drawn entities a case's exclusion lists hold now (as Exclude → Add to a list did when it was saved). */
    private async hideExcludedByCase(listIds: readonly string[]): Promise<void> {
        const members = new Set<string>();
        for (const id of listIds) {
            try {
                for (const m of (await firstValueFrom(this.inv.getEntityList(id))).members ?? []) members.add(m.trim().toUpperCase());
            } catch {
                // an unreadable list hides nothing
            }
        }
        if (!members.size) return;
        const hide = (this.graph()?.nodes ?? [])
            .filter((n) => rawIdsOf(n).some((v) => members.has(v.trim().toUpperCase())))
            .map((n) => n.id);
        if (hide.length) this.hideLocally(hide, 'on the case’s exclusion lists, hidden');
    }

    /** Prior saved copies of a view — a Component like any other, so the shared history dialog works
     *  as-is (`link-analysis-view` is a WRITABLE_TYPE, so /components versioning applies). A restore
     *  archives the current copy first (reversible); reload the list so a renamed restore shows through. */
    openHistory(view: LinkAnalysisView): void {
        this.dialog
            .open(ComponentHistoryDialog, { data: { type: 'link-analysis-view', id: view.id, label: view.name } })
            .afterClosed()
            .subscribe((restored) => {
                if (restored) this.reloadViews();
            });
    }

    /** Per-view comments (D10) — a collaboration thread on the saved view, not an edit of its content. */
    openComments(view: LinkAnalysisView): void {
        this.dialog.open(LinkAnalysisCommentsDialog, { data: { id: view.id, label: view.name } });
    }

    /** Per-view tags (D7) — labels the view in place, through the cross-entity assignment edges. Like
     *  Comments, this annotates the saved view rather than editing it, so there is nothing to reload. */
    openTags(view: LinkAnalysisView): void {
        this.tags.open({ targetKind: 'link-analysis-view', targetId: view.id, label: view.name });
    }

    /**
     * Offer a saved view in the Exchange catalog (D9). Only an `entity-projection` view is shareable — its
     * roots are Datasets, whereas a lineage/provenance view's roots are catalog assets/Pipelines the
     * Exchange cannot grant — so the action is hidden for the others rather than 422ing after the fact.
     * The datasets it reads must already be offered; the backend 409s and we surface that as a toast.
     */
    offer(view: LinkAnalysisView): void {
        const owner = this.spaces.currentSpaceId() ?? 'default';
        this.dialog
            .open(OfferShareDialog, { data: { kind: 'link-analysis-view', owner, item: view.id } })
            .afterClosed()
            .subscribe((r: OfferShareResult | undefined) => {
                if (!r) return;
                this.exchange
                    .offer({ kind: 'link-analysis-view', owner, item: view.id, description: r.description })
                    .subscribe({
                        next: () => this.toastr.success(`View "${view.name}" offered for sharing.`),
                        error: (e) => this.toastr.error(apiErrorMessage(e, `Could not offer "${view.name}".`)),
                    });
            });
    }

    /** Whether a given saved view can be offered — see {@link offer} for why the source matters. */
    shareable(view: LinkAnalysisView): boolean {
        return this.canShare() && view.sourceId === 'entity-projection';
    }

    // ── LA-GROW-EXCLUDE-1: Grow, the Held tray and Exclude on the graph (plan la-grow-exclude-plan.md §3–§4) ──

    readonly growExclude = inject(GrowExcludeStore);
    /** The active pack's words for what the canvas draws ("numbers", "calls"). */
    readonly growTerms = computed(() => packTerms(this.profile()));
    /** Recorded Investigation steps on its Working Set canvas, else the explorer graph (not recorded). */
    readonly growMode = computed<'investigation' | 'basic'>(() => (this.investigation.canvas() ? 'investigation' : 'basic'));
    /** The Space's hub threshold in force — the default "Hold … with more than N contacts". */
    readonly growThreshold = computed(() => this.limits.limits().hubThresholdInForce ?? 500);
    /** Why an Investigation action cannot run for this user; empty = it can. */
    readonly growWriteWhyNot = computed(() =>
        this.growMode() === 'investigation' && !this.lens.canManageIncidents()
            ? 'This changes the Investigation log and needs the Incident management capability.'
            : '',
    );
    /** The Entity Type the items on the canvas are (what an exclusion list is typed by); null = unknown. */
    readonly growEntityType = computed<string | null>(
        () =>
            this.investigation.binding()?.entityType ??
            this.profile().investigate?.entityType ??
            this.canvasData()?.nodes.find((n) => n.data.entityType)?.data.entityType ??
            // the explorer graph drawn by a start: the type its mapping was drawn as (the Start box's pick,
            // LA-ONBOARD-DEFECTS-1), else the primary Entity Type of the Dataset it came from (LA-ONBOARD-1 phase 8)
            this.lastRun()?.query?.projection?.entityType ??
            this.discoverDataset()?.primaryEntityType ??
            null,
    );

    /** Canvas nodes → the ids an op names: Working Set ids on an Investigation, node ids on the explorer. */
    private growIdsOf(nodes: G6GraphData['nodes']): string[] {
        if (this.growMode() === 'investigation') {
            const ws = this.investigation.workingSet();
            return [...new Set(nodes.flatMap((n) => idsInWorkingSet(n, ws)))];
        }
        return nodes.map((n) => n.id);
    }

    /** The canvas node a Working Set id is drawn as (or the id itself on the explorer). */
    private canvasNodeOf(id: string): G6GraphData['nodes'][number] | undefined {
        const nodes = this.canvasData()?.nodes ?? [];
        if (this.growMode() !== 'investigation') return nodes.find((n) => n.id === id);
        const ws = this.investigation.workingSet();
        return nodes.find((n) => idsInWorkingSet(n, ws).includes(id));
    }

    /** The Space's four-eyes thresholds, for the Expand dialog's "will need approval" note. */
    readonly fourEyesLimits = computed(() => {
        const l = this.limits.limits();
        return { budgetAbove: l.fourEyesBudgetAbove ?? null, fanOutAbove: l.fourEyesFanOutAbove ?? null };
    });

    /**
     * What the Expand dialog (§5.4) can start from: the selection, the outer ring, the seeds, the whole Working Set
     * (Investigation), or the selection, the nodes not expanded yet, the whole graph (explorer). The first usable one is
     * the default, so a right-click on a node starts from it.
     */
    readonly growContext = computed<GrowContext>(() => {
        const t = this.growTerms();
        const picked = this.selectedNodes().filter((n) => n.data.kind !== SUPER_NODE_KIND);
        const many = (n: number) => `${n.toLocaleString('en')} ${n === 1 ? t.node : t.nodes}`;
        const sources: ExpandSource[] = [];
        const add = (s: ExpandSource, cap: number) => {
            if (!s.blocked && !s.frontier.length && !s.heldFrontier.length)
                s.blocked = `There is nothing to expand from ${s.label.toLowerCase()}.`;
            if (!s.blocked && s.frontier.length + s.heldFrontier.length > cap)
                s.blocked = `One Expand names at most ${cap.toLocaleString('en')} ${t.nodes}; select fewer, or remove busy ${t.nodes} first.`;
            sources.push(s);
        };
        const pick = (mode: GrowContext['mode'], blocked: string | null, cap: number, hasWindow: boolean): GrowContext => {
            const first = sources.find((s) => !s.blocked) ?? sources[0];
            return {
                mode,
                frontier: first?.frontier ?? [],
                heldFrontier: first?.heldFrontier ?? [],
                from: first?.from ?? 'nothing yet',
                // only the reasons that hold for every start: each start's own reason travels on its source
                blocked: blocked ?? (sources.length ? null : 'There is nothing to expand from yet.'),
                sources,
                linkKinds: this.legendLinkKinds(),
                hubThreshold: this.growThreshold(),
                terms: t,
                frontierCap: cap,
                maxDegree: this.maxDegree(),
                hasWindow,
            };
        };
        if (this.growMode() === 'investigation') {
            const ws = this.investigation.workingSet();
            const shown = (ws?.entities ?? []).filter((e) => !e.hidden);
            const flagged = new Set(shown.filter((e) => e.highConnectivity).map((e) => e.id));
            const overridden = this.investigation.overriddenHubs();
            const isHeld = (id: string) => flagged.has(id) && !overridden.has(id);
            const cap = laLimit('investigation.max_frontier');
            const split = (value: ExpandSource['value'], label: string, ids: string[], from: string): ExpandSource => ({
                value,
                label,
                frontier: ids.filter((id) => !isHeld(id)),
                heldFrontier: ids.filter(isHeld),
                from,
                blocked: null,
            });
            if (picked.length) {
                const ids = this.growIdsOf(picked);
                const s = split('selection', 'The selection', ids, `the ${many(ids.length)} you selected`);
                if (!ids.length) s.blocked = 'None of the selected items is in the Working Set.';
                add(s, cap);
            }
            const d = degreeState(ws, this.maxDegree(), this.investigation.degreeOutcomes(), false, this.investigation.overrideSteps());
            add(
                {
                    value: 'outer',
                    label: 'The outer ring (next degree)',
                    frontier: d.frontier,
                    heldFrontier: d.heldHubs,
                    from:
                        d.current >= 0
                            ? `the outer ring (degree ${d.current}): ${many(d.frontier.length + d.heldHubs.length)}`
                            : 'nothing yet',
                    // "every entity at the edge is a hub" is the dialog's to say: unticking Hold can still expand
                    blocked: d.blocked && !(d.frontier.length === 0 && d.heldHubs.length) ? d.blocked : null,
                },
                cap,
            );
            const seeds = shown.filter((e) => e.hop === 0).map((e) => e.id);
            add(split('seeds', 'The seeds', seeds, `the ${many(seeds.length)} seeded`), cap);
            const all = shown.map((e) => e.id);
            add(split('all', 'The whole Working Set', all, `the whole Working Set: ${many(all.length)}`), cap);
            return pick('investigation', this.growWriteWhyNot() || null, cap, !!this.investigation.header()?.timeCol);
        }
        // Basic / exploration: one neighbours read per node, bounded like "Expand from the selected nodes".
        const run = this.lastRun();
        const canExpand = !!this.graphSources.byId(this.sourceId())?.expand && !!run?.query.projection;
        const held = this.growExclude.basicHeld();
        const isHeld = (n: G6GraphData['nodes'][number]) => held.has(n.id) || rawIdsOf(n).some((r) => held.has(r));
        const done = new Set(this.expandedNodeIds());
        const cap = this.selectionExpandCap;
        const basic = (value: ExpandSource['value'], label: string, pool: G6GraphData['nodes'], what: string, why: string | null = null) => {
            const capped = pool.slice(0, cap);
            sources.push({
                value,
                label,
                frontier: capped.filter((n) => !isHeld(n)).map((n) => n.id),
                heldFrontier: capped.filter(isHeld).map((n) => n.id),
                from:
                    pool.length > capped.length
                        ? `the first ${capped.length} of ${many(pool.length)} ${what}`
                        : `the ${many(pool.length)} ${what}`,
                blocked: why ?? (pool.length ? null : `There is nothing to expand from ${label.toLowerCase()}.`),
            });
        };
        const nodes = (this.canvasData()?.nodes ?? []).filter((n) => n.data.kind !== SUPER_NODE_KIND);
        if (picked.length) basic('selection', 'The selection', picked, 'you selected');
        const fresh = nodes.filter((n) => !done.has(n.id));
        basic(
            'outer',
            'The nodes not expanded yet',
            fresh,
            'not expanded yet',
            nodes.length && !fresh.length ? `Every ${t.node} on the graph has been expanded already.` : null,
        );
        basic('all', 'The whole graph', nodes, 'on the graph');
        return pick(
            'basic',
            !run
                ? 'Draw a graph first.'
                : !canExpand
                  ? 'Expand needs a graph drawn from one Entity/Link mapping (its neighbours read).'
                  : null,
            cap,
            false,
        );
    });

    /**
     * The explorer's preview: ONE `neighbors/preview` over the whole frontier (distinct neighbours, so no over-count), with
     * the rest of the graph as `drawn` and the Grow's own read binding (attribute columns, the query's filter), plus the
     * links between nodes already shown that the graph does not draw yet (`withUndrawnLinks`), so it counts what `growBasic`
     * would add. An absent route → no numbers; a refusal (e.g. 403) reaches the panel with the server's
     * reason. No `excludeBy`: the explorer graph has no list in force (its neighbours read cannot apply one), so the
     * preview never counts one.
     */
    readonly growBasicAdapter = (this.growExclude.basicAdapter = {
        preview: async (frontier: string[], req: GrowRequest) => {
            const q = this.lastRun()?.query;
            const p = q?.projection;
            if (!p) return null;
            const nodes = this.canvasData()?.nodes ?? [];
            const ids = frontier.map((id) => nodes.find((n) => n.id === id)?.data.label ?? id);
            // what the Grow compares against: the whole graph, hidden nodes too (growBasic counts this.graph())
            const graph = this.graph() ?? { nodes: [], edges: [] };
            const drawn = drawnValues(graph, frontier, laLimit('input.max_ids'), laLimit('input.id_length'));
            const preview = withUndrawnLinks(
                await firstValueFrom(this.inv.previewNeighbors(neighboursPreviewBody(p, ids, req, drawn, q.filter))),
                graph,
                frontier,
                req,
            );
            // held items only mean something while holding
            return req.hold ? preview : { ...preview, held: [], heldCount: 0 };
        },
    });

    /** The Investigation's held busy items, else the explorer's — what the Held tray lists and the canvas badges. */
    readonly heldItems = computed<HeldItem[]>(() => {
        if (this.growMode() === 'investigation') {
            const overridden = this.investigation.overriddenHubs();
            const sealed = this.investigation.hubContacts();
            return (this.investigation.workingSet()?.entities ?? [])
                .filter((e) => e.highConnectivity && !e.hidden && !overridden.has(e.id))
                .map((e) => ({ id: e.id, label: e.id, contacts: e.contacts ?? sealed.get(e.id) ?? null }));
        }
        const held = this.growExclude.basicHeld();
        if (!held.size) return [];
        const hidden = new Set(this.hiddenNodeIds());
        const out: HeldItem[] = [];
        for (const n of this.canvasSource()?.nodes ?? []) {
            if (hidden.has(n.id)) continue;
            const raw = held.has(n.id) ? n.id : rawIdsOf(n).find((r) => held.has(r));
            if (raw !== undefined) out.push({ id: n.id, label: n.data.label, contacts: held.get(raw) ?? null });
        }
        return out;
    });

    /** The canvas with its held busy nodes marked: the ring a flagged hub already wears, plus the "held" badge. */
    private markHeld(g: G6GraphData | null): G6GraphData | null {
        if (!g) return g;
        const items = this.heldItems();
        if (!items.length) return g;
        const held = new Set(items.map((h) => h.id));
        // DR-D2: the canvas may speak the Space's alias where the Working Set speaks its own (`exploreAliases`)
        const back = new Map(
            Object.entries(this.investigation.workingSet()?.exploreAliases ?? {}).map(([inv, explore]) => [explore, inv]),
        );
        const isHeld =
            this.growMode() === 'investigation'
                ? (n: G6GraphData['nodes'][number]) => rawIdsOf(n).some((id) => held.has(back.get(id) ?? id))
                : (n: G6GraphData['nodes'][number]) => held.has(n.id);
        return {
            ...g,
            nodes: g.nodes.map((n) => (isHeld(n) ? { ...n, data: { ...n.data, held: true, highConnectivity: true } } : n)),
        };
    }

    /** Open the Expand dialog (§5.4): G, the toolbar's Expand…, right-click → Expand…, the Investigation tab. */
    openExpand(): void {
        if (!this.graph() && !this.workingSetCanvas()) return;
        this.growExclude.exclusionsOpen.set(false);
        this.growExclude.growOpen.set(true);
    }

    closeExpand(): void {
        this.growExclude.growOpen.set(false);
        this.growExclude.resetPreview();
    }

    /** The Expand dialog's choice, walked degree by degree: recorded `expand` steps, or the explorer's neighbours reads. */
    async onExpand({ ctx, req }: ExpandRun): Promise<void> {
        this.growExclude.growOpen.set(false);
        if (ctx.mode === 'investigation') {
            // going through hubs is a sealed override: ask first, as Expand anyway does
            if (!req.hold && !(await this.confirmHubOverride(ctx.heldFrontier))) return;
            if (!(await this.growExclude.growInvestigation(ctx, req, this.degreePresets())))
                this.toastr.error(this.investigation.error() || 'The Expand was refused.');
            return;
        }
        await this.growBasic(ctx, req);
    }

    /** "Hold supernodes" unticked in an Investigation: the override is sealed in every degree's step, so confirm it. */
    private confirmHubOverride(held: string[]): Promise<boolean> {
        const purpose = this.investigation.header()?.purpose;
        const what = held.length
            ? `${held.length} held high-connectivity ${held.length === 1 ? 'entity' : 'entities'} and any the walk reaches`
            : 'any high-connectivity entity the walk reaches';
        return this.confirm.confirmDestructive(
            `Expand through ${what}? A hub can pull in many unrelated numbers.\n\n` +
                'The override is sealed in each step of the Investigation log, with your name, and is shown in the log ' +
                'and the Dossier' +
                (purpose ? ` under the stated purpose: “${purpose}”.` : '.'),
            { title: 'Expand through high connectivity?', confirmText: 'Expand anyway', destructive: false },
        );
    }

    /** The explorer frontier minus the busy nodes a preview of it names; those join the Held tray. */
    private async holdBusy(frontier: string[], req: GrowRequest): Promise<string[]> {
        let held: { id: string; contacts: number }[] = [];
        try {
            held = (await this.growExclude.basicAdapter?.preview(frontier, req))?.held ?? [];
        } catch {
            return frontier; // no preview: nothing more is known to hold
        }
        if (!held.length) return frontier;
        const next = new Map(this.growExclude.basicHeld());
        for (const h of held) next.set(h.id, h.contacts);
        this.growExclude.basicHeld.set(next);
        const nodes = this.graph()?.nodes ?? [];
        return frontier.filter((id) => {
            const n = nodes.find((x) => x.id === id);
            return !next.has(id) && !(n && rawIdsOf(n).some((r) => next.has(r)));
        });
    }

    /** Basic Grow (§3.5): neighbours reads merged into the explorer graph — not recorded; Undo restores the graph. */
    private async growBasic(ctx: GrowContext, req: GrowRequest): Promise<void> {
        const before = {
            graph: this.graph(),
            expanded: this.expandedNodeIds(),
            truncated: this.truncated(),
            held: this.growExclude.basicHeld(),
        };
        const t = ctx.terms;
        const preview = this.growExclude.preview().preview;
        if (req.hold && preview?.held.length) {
            const next = new Map(this.growExclude.basicHeld());
            for (const h of preview.held) next.set(h.id, h.contacts);
            this.growExclude.basicHeld.set(next);
        }
        let frontier = [...ctx.frontier, ...(req.hold ? [] : ctx.heldFrontier)];
        const startNodes = this.graph()?.nodes.length ?? 0;
        const startLinks = this.graph()?.edges.length ?? 0;
        let applied = 0;
        let stop = '';
        for (let d = 1; d <= req.degrees && frontier.length; d++) {
            // degree 1 is held by the dialog's preview; each later degree reads its own, so a busy node it reached is
            // held too (no preview on this server = nothing more is known to hold)
            if (d > 1 && req.hold) frontier = await this.holdBusy(frontier, req);
            if (!frontier.length) break;
            applied = d;
            const known = new Set((this.graph()?.nodes ?? []).map((n) => n.id));
            for (const id of frontier.slice(0, this.selectionExpandCap)) {
                const node = this.graph()?.nodes.find((n) => n.id === id);
                if (!node) continue;
                await this.expandNode(id, node.data.label, node.data.spellings, (e) =>
                    growKeepsEdge(id, req, e, baseEdgeKind(e.data.kind)),
                );
            }
            const held = this.growExclude.basicHeld();
            frontier = (this.graph()?.nodes ?? [])
                .filter((n) => !known.has(n.id) && !(req.hold && (held.has(n.id) || rawIdsOf(n).some((r) => held.has(r)))))
                .map((n) => n.id);
            if (d < req.degrees) {
                const cap = frontierCapStop(frontier.length, this.selectionExpandCap, d, t);
                if (cap) {
                    stop = ' ' + cap;
                    break;
                }
                if (!frontier.length) stop = ` Degree ${d} found nothing new to grow from, so the walk stopped there.`;
            }
        }
        const nodes = (this.graph()?.nodes.length ?? 0) - startNodes;
        const links = (this.graph()?.edges.length ?? 0) - startLinks;
        const text =
            `Expanded ${applied === 1 ? 'one degree' : `${applied} degrees`}: ${nodes.toLocaleString('en')} ${nodes === 1 ? t.node : t.nodes} ` +
            `and ${links.toLocaleString('en')} ${links === 1 ? t.link : t.links} added (not recorded).` +
            stop;
        const after = this.graph();
        this.growExclude.lastChange.set({
            text,
            // only while the graph is still the one this Grow drew: a later expand would be thrown away
            valid: () => this.graph() === after,
            undo: () => {
                this.graph.set(before.graph);
                this.expandedNodeIds.set(before.expanded);
                this.truncated.set(before.truncated);
                this.growExclude.basicHeld.set(before.held);
                this.growExclude.lastChange.set({ text: 'Undone.' });
            },
        });
        this.drawAnnouncement.set(text);
    }

    /** The nodes "Exclude…" acts on: the given ones, else the selection, else the picked Investigation entity. */
    private excludeTargets(ids?: string[]): G6GraphData['nodes'] {
        const nodes = this.canvasData()?.nodes ?? [];
        if (ids?.length) return nodes.filter((n) => ids.includes(n.id));
        const sel = this.selectedNodes();
        if (sel.length) return sel;
        const picked = this.investigation.selected();
        return picked ? nodes.filter((n) => n.id === picked.id) : [];
    }

    /** "Exclude…" (right-click, X): the two-choice dialog, then the recorded op / the local hide. */
    openExclude(nodeIds?: string[]): void {
        const targets = this.excludeTargets(nodeIds).filter((n) => n.data.kind !== SUPER_NODE_KIND);
        const t = this.growTerms();
        if (!targets.length) {
            this.drawAnnouncement.set(`Select the ${t.nodes} to exclude first (click one, or drag a box).`);
            this.toastr.info(`Select the ${t.nodes} to exclude first.`);
            return;
        }
        const ids = this.growIdsOf(targets);
        if (!ids.length) {
            this.toastr.info('None of these nodes is in the Working Set.');
            return;
        }
        const mode = this.growMode();
        void this.growExclude.loadLists().then(() => {
            this.dialog
                .open<LinkAnalysisExcludeDialog, ExcludeDialogData, ExcludeDialogResult | undefined>(
                    LinkAnalysisExcludeDialog,
                    {
                        width: '30rem',
                        data: {
                            count: ids.length,
                            labels: targets.map((n) => n.data.label),
                            mode,
                            terms: t,
                            lists: this.growExclude.lists(),
                            entityType: this.growEntityType(),
                            canManageLists: this.lens.canManageIncidents(),
                            canManageCategories: this.lens.canManageExclusionCategories(),
                            hereWhyNot: this.growWriteWhyNot(),
                        },
                    },
                )
                .afterClosed()
                .subscribe((r) => {
                    if (r) void this.applyExclude(r, targets, ids);
                });
        });
    }

    private async applyExclude(r: ExcludeDialogResult, targets: G6GraphData['nodes'], ids: string[]): Promise<void> {
        const t = this.growTerms();
        if (this.growMode() === 'investigation') {
            if (r.kind === 'here') {
                if (await this.growExclude.excludeHere(ids, r.reason, t)) this.clearSelection();
                else this.toastr.error(this.investigation.error() || 'The exclusion was refused.');
                return;
            }
            const res = await this.growExclude.addToList(
                { id: r.listId, newTitle: r.newTitle, entityType: this.growEntityType() },
                ids,
                r.reason,
                t,
                true,
            );
            if (res.ok) {
                this.clearSelection();
                this.toastr.info(res.message);
                this.noteCaseExclusion(res.listId);
            } else this.toastr.error(res.message);
            return;
        }
        // Basic: a local hide (Undo / Ctrl+Z bring them back), and the list write when asked.
        if (r.kind === 'list') {
            const res = await this.growExclude.addToList(
                { id: r.listId, newTitle: r.newTitle, entityType: this.growEntityType() },
                targets.map((n) => rawIdsOf(n)[0] ?? n.data.label),
                r.reason,
                t,
                false,
            );
            if (!res.ok) {
                this.toastr.error(res.message);
                return;
            }
            this.toastr.info(res.message);
            this.noteCaseExclusion(res.listId);
        }
        this.hideLocally(
            targets.map((n) => n.id),
            r.kind === 'list' ? 'added to the list and hidden' : 'hidden',
        );
    }

    /** An exclusion list written from this graph is one the case uses (A-1b). */
    private noteCaseExclusion(listId: string | undefined): void {
        if (!listId) return;
        this.caseLists.update((c) => (c.exclusions.includes(listId) ? c : { ...c, exclusions: [...c.exclusions, listId] }));
        this.refreshEntityLists();
    }

    /** Re-read the Space's Entity Lists (the Start box and the case's list titles read them). */
    private refreshEntityLists(): void {
        this.queryCache.clear(); // a list write may change what the server answers
        this.inv.reads.clear();
        this.inv
            .listEntityLists()
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({ next: (l) => this.entityLists.set(l?.lists ?? []), error: () => undefined });
    }

    // ── LA-ONBOARD-1 phase 7 (UC-12): Find something interesting ──

    /** Why *Start from these* cannot run: it draws over the Dataset the graph came from, which must start a graph. */
    readonly discoverStartWhyNot = computed(() =>
        this.investigation.canvas()
            ? 'Starting again from these would leave the Investigation: close it first.'
            : this.discoverDataset()
              ? ''
              : 'This graph was not drawn from a Dataset with a link, so nothing can start from it.',
    );
    /** Why *Add to a list* cannot run (list writes need the Incident management capability; the server asks the same). */
    readonly discoverListWhyNot = computed(() =>
        this.lens.canManageIncidents() ? '' : 'Adding to a list needs the Incident management capability.',
    );

    /** The Dataset the graph on screen was drawn from, when a start can draw over it. */
    private discoverDataset(): StartDataset | null {
        const id = this.lastRun()?.query?.projection?.datasetId;
        return (id && this.startCatalogue().datasets.find((d) => d.id === id)) || null;
    }

    /** *Show on graph*: emphasise the result and bring its first entity into view. */
    showFound(sel: GraphSelection): void {
        this.emphasis.set(sel);
        if (sel.nodeIds[0]) this.focusNode(sel.nodeIds[0]);
    }

    /** *Start from these*: the Start box's path (phase 6) over the graph's Dataset - "the busiest / a community". */
    async startFromFound(a: FoundAction): Promise<void> {
        const ds = this.discoverDataset();
        if (!ds) return;
        await this.startFrom({ kind: 'found', datasetId: ds.id, values: a.row.values, what: a.what });
    }

    /** *Add to a list* (UC-10): the dialog picks or creates the list and writes; an exclusion list becomes the case's. */
    addFoundToList(a: FoundAction): void {
        const data: AddToListData = {
            labels: a.row.nodeIds.map((id) => this.labelOf(id)),
            values: a.row.values,
            entityType: this.growEntityType(),
            lists: this.entityLists(),
            purpose: a.purpose,
            terms: this.growTerms(),
        };
        this.dialog
            .open<LinkAnalysisAddToListDialog, AddToListData, AddToListResult | undefined>(LinkAnalysisAddToListDialog, {
                width: '30rem',
                data,
            })
            .afterClosed()
            .subscribe((r) => {
                if (!r) return;
                this.toastr.info(r.message);
                if (r.purpose === 'exclusion') {
                    this.noteCaseExclusion(r.listId);
                    // as Exclude → Add to a list does on the explorer graph: out of sight now, not only when the case reopens
                    if (this.growMode() === 'basic') this.hideLocally(a.row.nodeIds, 'added to the list and hidden');
                } else this.refreshEntityLists();
                this.investigation.entityListsChanged.update((n) => n + 1);
            });
    }

    /** Hide explorer nodes (presentation, undoable) and say so with an Undo. */
    private hideLocally(nodeIds: string[], what: string): void {
        const t = this.growTerms();
        const added = nodeIds.filter((id) => !this.hiddenNodeIds().includes(id));
        this.recordHistory();
        this.hiddenNodeIds.update((h) => [...h, ...added]);
        this.clearSelection();
        this.growExclude.lastChange.set({
            text: `${nodeIds.length.toLocaleString('en')} ${nodeIds.length === 1 ? t.node : t.nodes} ${what} (not recorded).`,
            // brings back only what this hide took off, whatever was hidden since
            undo: () => {
                this.hiddenNodeIds.update((h) => h.filter((id) => !added.includes(id)));
                this.growExclude.lastChange.set({ text: 'Undone.' });
            },
        });
    }

    /** The Held tray's "Remove held": one recorded `exclude` with the reason, or (explorer) a local hide. */
    async removeHeld(): Promise<void> {
        const items = this.heldItems();
        if (!items.length) return;
        if (this.growMode() === 'investigation') {
            const ok = await this.growExclude.removeHeld(
                items.map((h) => h.id),
                this.growThreshold(),
                this.growTerms(),
            );
            if (!ok) this.toastr.error(this.investigation.error() || 'Removing the held items was refused.');
            return;
        }
        this.hideLocally(
            items.map((h) => h.id),
            'busy, hidden',
        );
    }

    /** The Held tray's "Expand anyway" on one item: the recorded override, or (explorer) a plain neighbours read. */
    async expandHeldAnyway(id: string): Promise<void> {
        if (this.growMode() === 'investigation') {
            if (!(await this.growExclude.expandAnyway(id, this.degreePresets())))
                this.toastr.error(this.investigation.error() || 'The expand was refused.');
            return;
        }
        const node = this.canvasData()?.nodes.find((n) => n.id === id);
        if (!node) return;
        const next = new Map(this.growExclude.basicHeld());
        for (const r of [...rawIdsOf(node), node.id]) next.delete(r);
        this.growExclude.basicHeld.set(next);
        await this.expandNode(id, node.data.label, node.data.spellings);
    }

    /** Held (n): move focus into the held tray, where each held item can be expanded anyway or removed. */
    focusHeldTray(): void {
        this.host.nativeElement
            .querySelector<HTMLElement>('inspecto-link-analysis-held-tray button, inspecto-link-analysis-held-tray [tabindex]')
            ?.focus();
    }

    focusHeld(id: string): void {
        const node = this.canvasNodeOf(id);
        if (node) this.focusNode(node.id);
    }

    openExclusions(): void {
        this.growExclude.growOpen.set(false);
        this.growExclude.exclusionsOpen.set(!this.growExclude.exclusionsOpen());
        void this.growExclude.loadLists();
    }

    readonly exclusionsLine = computed(() => hiddenByListsLine(this.growExclude.inForce(), this.growTerms()));
    readonly exclusionsRemoved = computed(() =>
        removedRows(this.investigation.workingSet(), this.growExclude.inForce(), this.growExclude.canSeeRestricted()),
    );
    /** The toolbar's count: removed by lists (Investigation) or hidden on the explorer. */
    readonly hiddenCount = computed(() =>
        this.growMode() === 'investigation'
            ? this.growExclude.inForce().reduce((s, l) => s + l.removed, 0)
            : this.hiddenNodeIds().length,
    );
    readonly createListWhyNot = computed(() =>
        !this.lens.canManageIncidents()
            ? 'Creating a list needs the Incident management capability.'
            : !this.growEntityType()
              ? 'This graph has no Entity Type, so a list cannot be made for it.'
              : '',
    );

    async switchListOff(listId: string): Promise<void> {
        if (!(await this.growExclude.switchOff(listId)))
            this.toastr.error(this.investigation.error() || 'The list could not be switched off.');
    }

    async switchListOn(listId: string): Promise<void> {
        if (!(await this.growExclude.switchOn(listId, this.growTerms())))
            this.toastr.error(this.investigation.error() || 'The list could not be switched on.');
    }

    async createExclusionList(title: string): Promise<void> {
        this.toastr.info(await this.growExclude.createList(title, this.growEntityType()));
    }

    showHiddenAgain(): void {
        this.recordHistory();
        this.hiddenNodeIds.set([]);
    }

    // ── right-click a node → Exclude… / Grow from here ──

    @ViewChild('nodeMenuTrigger') private nodeMenuTrigger?: MatMenuTrigger;
    readonly nodeMenuAt = signal({ x: 0, y: 0 });
    readonly nodeMenuTargets = signal<string[]>([]);

    onNodeContextMenu(e: { id: string; x: number; y: number }): void {
        const sel = this.selectedOnCanvas();
        this.nodeMenuTargets.set(sel.includes(e.id) ? sel : [e.id]);
        this.nodeMenuAt.set({ x: e.x, y: e.y });
        this.cdr.markForCheck();
        // the trigger moves first, then the menu opens at it
        setTimeout(() => this.nodeMenuTrigger?.openMenu());
    }

    expandFromMenu(): void {
        this.selectedNodeIds.set(this.nodeMenuTargets());
        this.announceSelection(this.nodeMenuTargets().length);
        this.openExpand();
    }

    /** L = Lasso, F = Fisheye, Esc = back to Pan (the same guards as G / X). True when the key was ours. */
    private onCanvasModeKey(e: KeyboardEvent, target: HTMLElement | null): boolean {
        const mode = canvasModeForKey(e, this.canvasMode());
        if (!mode || !this.canvasKeyTarget(target)) return false;
        e.preventDefault();
        this.setCanvasMode(mode);
        return true;
    }

    /** G = Grow, X = Exclude… (outside a text field, a grid, a menu or a dialog). True when the key was ours. */
    private onGrowExcludeKey(e: KeyboardEvent, target: HTMLElement | null): boolean {
        if (e.ctrlKey || e.metaKey || e.altKey || e.repeat) return false;
        const key = e.key.toLowerCase();
        if (key !== 'g' && key !== 'x') return false;
        if (!this.canvasKeyTarget(target)) return false;
        e.preventDefault();
        if (key === 'g') this.openExpand();
        else this.openExclude();
        return true;
    }

    /** A canvas shortcut applies outside a text field, a grid, a menu or a dialog, with a graph on screen. */
    private canvasKeyTarget(target: HTMLElement | null): boolean {
        if (target?.isContentEditable || target?.tagName === 'SELECT') return false;
        if (
            target?.closest?.(
                '.ag-root-wrapper, [role="menu"], mat-dialog-container, [role="grid"], [role="combobox"], [role="listbox"], mat-select',
            )
        )
            return false;
        if (this.dialog.openDialogs.length) return false;
        return !!(this.graph() || this.workingSetCanvas());
    }
}
