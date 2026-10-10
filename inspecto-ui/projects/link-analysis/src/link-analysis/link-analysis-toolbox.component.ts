import { DecimalPipe } from '@angular/common';
import { RiskScorePanelComponent } from '@inspecto/core/components/risk-score-panel.component';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ComponentsService } from '@inspecto/core/api';
import { GraphRunResult, GraphScoreView, GraphSuspicionView } from '@inspecto/link-analysis/api/graph-runs.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import {
    BranchStage,
    BranchingMatch,
    LegThreshold,
    matchBranchingPattern,
    thresholdLabel,
} from '@inspecto/link-analysis/graph/branching-pattern-engine';
import { G6GraphData } from '@inspecto/core/graph';
import {
    GraphSelection,
    NodeScore,
    PatternStep,
    PredictedLink,
    SuspicionScore,
    allPaths,
    analysisNodeCapValue,
    selectionNodeCapValue,
    articulationPoints,
    betweennessCentrality,
    bridges,
    cliques,
    closenessCentrality,
    connectedComponents,
    degreeCentrality,
    detectCommunities,
    eigenvectorCentrality,
    explainNode,
    findCycles,
    hits,
    jaccardSimilarity,
    kCore,
    katzCentrality,
    linkPrediction,
    louvainCommunities,
    matchPattern,
    maxFlow,
    maximumSpanningForest,
    neighborhood,
    pageRank,
    shortestPath,
    suspicionNodeCapValue,
    suspicionScore,
    triangleCount,
    patternNeedsTime,
    weightedShortestPath,
} from '@inspecto/link-analysis/graph/graph-analysis';
import { GraphEmphasis } from '@inspecto/core/graph/graph-view.component';
import { PATTERN_PACKS, PatternPack, patternPackFromContent } from './pattern-packs';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { FormsModule } from '@angular/forms';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { ServerPathsState, ServerPatternState } from './entity-projection';
import { indexSourceNote } from './index-source';
import { IndexMappingTarget, LinkAnalysisIndexBuildComponent } from './link-analysis-index-build.component';
import { LinkAnalysisServerRunComponent } from './link-analysis-server-run.component';
import { LinkAnalysisResultNextComponent } from './link-analysis-result-next.component';
import { QueuedSeed, TOP_SEED_COUNT, topSeeds } from './la-starter';
import {
    ServerIdMap,
    countDropped,
    droppedNotice,
    truncationNotice,
    toCommunityMap,
    toGroups,
    toNodeScores,
    toPredictedLinks,
    toSelection,
    toSuspicionScores,
} from './graph-run-apply';

/**
 * LA-11: what the analyst asked the server to walk. `mapping` is the index into the host's
 * `traversalMappings` (a picker value is a string); the host resolves it to a Dataset + column pair.
 */
export interface FindPathsRequest {
    from: string;
    to?: string;
    mapping: number;
    maxDepth: number;
    direction: 'DIRECTED' | 'UNDIRECTED';
}

/** LA-14b: run the loaded branching motif over the whole Dataset of one edge mapping (the host owns the call). */
export interface ServerPatternRequest {
    mapping: number;
    stages: BranchStage[];
}

type AnalysisTab =
    | 'path'
    | 'explain'
    | 'centrality'
    | 'communities'
    | 'pattern'
    | 'all-paths'
    | 'server-paths'
    | 'components'
    | 'cycles'
    | 'cut-points'
    | 'cohesion'
    | 'similarity'
    | 'flow'
    | 'scoring'
    | 'index-build';

/** The tool groups that can hand an over-cap run to the server (D-4 step 7). */
type ServerTool =
    | 'centrality'
    | 'communities'
    | 'cliques'
    | 'prediction'
    | 'flow'
    | 'scoring'
    | 'path'
    | 'allPaths'
    | 'cycles'
    | 'cutNodes'
    | 'cutEdges'
    | 'forest';

/** One "Run on server" control: which server algorithm, with what parameters, why the browser did not run it. */
export interface ServerRunSpec {
    algorithm: string;
    params: Record<string, unknown>;
    note: string;
    /** Why the run cannot start yet (a missing pick); '' = nothing holds it. */
    hold: string;
}

/** The metrics the Centrality group can rank by — each returns a {@link NodeScore} list. */
type CentralityMetric =
    | 'degree'
    | 'betweenness'
    | 'closeness'
    | 'eigenvector'
    | 'katz'
    | 'pagerank'
    | 'hub'
    | 'authority';

/**
 * **Link Analysis — graph-algorithms toolbox** (the bottom panel's Analysis tab, extracted from the
 * studio god component per plan S2/B4). A self-contained panel that runs the pure `graph-analysis`
 * library over the currently-displayed graph and emits the resulting {@link GraphEmphasis} for the
 * host to paint on the canvas. Owns its own result state so a saved analysis survives tab switches
 * (the host mounts it with `[hidden]`, never `@if`, so this instance is never torn down).
 */
@Component({
    selector: 'inspecto-link-analysis-toolbox',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ChipComponent,
        DecimalPipe,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        LinkAnalysisResultNextComponent,
        FormsModule,
        RiskScorePanelComponent,
        LinkAnalysisServerRunComponent,
        LinkAnalysisIndexBuildComponent,
    ],
    templateUrl: './link-analysis-toolbox.component.html',
})
export class LinkAnalysisToolboxComponent {
    // NOT `components` — that name is already this component's connected-components signal (below), and
    // reusing it is a duplicate-identifier compile error, not a shadow.
    private componentsApi = inject(ComponentsService);

    constructor() {
        // This Space's authored pattern packs, if it has any — merged over the shipped built-ins. Degrades
        // silently: an error (no write root, intelligence-free edition, offline) leaves the shipped built-ins
        // in place, which is the whole point of seeding the signal with them — there is no error surface to
        // show for a catalog that has a default.
        this.componentsApi
            .list('pattern-pack')
            .pipe(takeUntilDestroyed(inject(DestroyRef)))
            .subscribe({
                next: (defs) => {
                    const authored = defs
                        .map((d) => patternPackFromContent(d.content))
                        .filter((p): p is PatternPack => !!p);
                    // Authored packs ADD to the shipped built-ins rather than replacing them (PACK-1):
                    // authoring one pack should never silently remove the other six. An authored pack that
                    // reuses a built-in id overrides it — `selectPack` resolves by id and would otherwise
                    // never reach the authored one.
                    if (authored.length)
                        this.patternPacks.set([
                            ...PATTERN_PACKS.filter((b) => !authored.some((a) => a.id === b.id)),
                            ...authored,
                        ]);
                },
                error: () => undefined,
            });
    }

    /** The graph the tools operate on — the host's displayed (filtered + collapsed) graph. */
    readonly graph = input<G6GraphData | null>(null);
    readonly nodeOptions = input<{ id: string; label: string }[]>([]);
    /** The graph's nodes as picker options — every From / To / Node / Source / Sink asks through the shared picker (UI-13). */
    readonly pathNodeOptions = computed<PickerOption[]>(() =>
        this.nodeOptions().map((n) => ({ value: n.id, label: n.label })),
    );
    /** Centrality algorithms — "Metric" here is the graph-analysis term, not the BI Measure. */
    readonly centralityOptions: PickerOption[] = [
        { value: 'degree', label: 'Degree' },
        { value: 'betweenness', label: 'Betweenness' },
        { value: 'closeness', label: 'Closeness' },
        { value: 'eigenvector', label: 'Eigenvector' },
        { value: 'katz', label: 'Katz' },
        { value: 'pagerank', label: 'PageRank' },
        { value: 'hub', label: 'HITS — hub' },
        { value: 'authority', label: 'HITS — authority' },
    ];
    readonly cohesionOptions: PickerOption[] = [
        { value: 'k-core', label: 'k-core' },
        { value: 'triangles', label: 'Triangle count' },
        { value: 'cliques', label: 'Cliques' },
    ];
    /** "Custom motif" is a real, blank-valued choice (the skill's idiom), then every loaded pack. */
    readonly packOptions = computed<PickerOption[]>(() => [
        { value: '', label: 'Custom motif' },
        ...this.patternPacks().map((p) => ({ value: p.id, label: p.label })),
    ]);
    readonly nodeKinds = input<string[]>([]);
    readonly edgeKinds = input<string[]>([]);
    /** Tool-group ids the host's domain profile foregrounds — badged "suggested" on the header. */
    readonly suggested = input<string[]>([]);
    /** Node-id → label lookup, supplied by the host (its full-graph labels). */
    readonly labelOf = input<(id: string) => string>((id) => id);
    /**
     * LA-14a: the edge attribute column carrying the event time, as chosen in the pane's time control.
     * A motif with an ordering constraint cannot be evaluated without it — see {@link runPattern}.
     */
    readonly timeAttr = input<string>('');

    /**
     * LA-11: the edge mappings a server traversal can walk (the loaded query's), as picker options whose value
     * is the mapping's index. Empty = no Entity/Link projection is loaded, and the tool says so.
     */
    readonly traversalMappings = input<PickerOption[]>([]);
    /** LA-11: the last server traversal's answer, mapped onto the graph by the host (null = none yet). */
    readonly serverPaths = input<ServerPathsState | null>(null);
    readonly serverPathsBusy = input(false);
    /** DR-U4: where the server path search was answered from, in words. */
    readonly serverPathsSourceNote = computed(() => indexSourceNote(this.serverPaths()?.source));
    /**
     * LA-14b: the projection behind the graph was cut by the server's link cap. A branching motif's legs are small
     * one-off amounts that sort LAST and are cut FIRST, so on a truncated graph the browser matcher may be looking
     * at a graph the ring was removed from — the toolbox offers the server search instead.
     */
    readonly graphTruncated = input(false);
    /** LA-14b: the last server pattern search, mapped onto the graph by the host (null = none yet). */
    readonly serverPattern = input<ServerPatternState | null>(null);
    readonly serverPatternBusy = input(false);

    /**
     * D-4 step 7: the Investigation whose Working Set the canvas draws (null = the canvas shows a query graph, so a
     * server run has nothing to run over), the server-to-canvas id translation for that Working Set, and whether the
     * Subject holds `canRunLinkGraphAnalysis`.
     */
    readonly investigationId = input<string | null>(null);
    /** DR-D3: an Investigation is open (even when the canvas draws the query graph and no id is passed). */
    readonly investigationOpen = input(false);
    /**
     * How many nodes the open Investigation's Working Set has (hidden ones left out), or null when the canvas shows a
     * query graph. A server run reads the Working Set, so when one is open THAT is the size the browser-or-server
     * decision is made on - the displayed query graph is not what the server would analyse.
     */
    readonly workingSetNodes = input<number | null>(null);
    readonly serverIds = input<ServerIdMap | null>(null);
    /**
     * The server's per-algorithm `inlineNodeCeiling` (`GET /inv/graph/algorithms`), by algorithm id; null until it is
     * known. The selection algorithms have no browser cap, so the browser threshold is {@link selectionNodeCapValue}
     * LOWERED to this where the server states a smaller one.
     */
    readonly serverCeilings = input<Record<string, number> | null>(null);
    readonly canRunOnServer = input(true);
    /** Does the Subject hold `canBuildLinkIndex`? */
    readonly canBuildIndex = input(true);
    /** DR-U5: the loaded query's edge mappings, for the Edge index tool's "Index / Flat" line and first-build prefill. */
    readonly indexMappings = input<IndexMappingTarget[]>([]);

    /** A selection to emphasize on the canvas (`null` clears). */
    readonly emphasisChange = output<GraphEmphasis | null>();
    /** A ranking row was picked: the host selects that node and centres the canvas on it. */
    readonly nodePick = output<string>();
    /** "Start an Investigation from the top results": the top-ranked nodes, to queue as seed entities. */
    readonly seedFromResults = output<QueuedSeed[]>();
    /** LA-11: run a server-side multi-hop traversal — the host owns the call (this panel has no HTTP). */
    readonly findPaths = output<FindPathsRequest>();
    /** LA-14b: run the branching motif server-side over the whole Dataset. */
    readonly runPatternOnServer = output<ServerPatternRequest>();

    /** The analysis tool groups (the accordion = the graph-algorithms toolbox). */
    readonly tools: { id: AnalysisTab; label: string; icon: string }[] = [
        { id: 'path', label: 'Shortest path', icon: 'heroicons_outline:arrows-right-left' },
        { id: 'all-paths', label: 'All paths', icon: 'heroicons_outline:share' },
        { id: 'server-paths', label: 'Find paths (server)', icon: 'heroicons_outline:server-stack' },
        { id: 'explain', label: 'Explain node', icon: 'heroicons_outline:light-bulb' },
        { id: 'centrality', label: 'Centrality', icon: 'heroicons_outline:star' },
        { id: 'communities', label: 'Communities', icon: 'heroicons_outline:user-group' },
        { id: 'components', label: 'Connected components', icon: 'heroicons_outline:squares-2x2' },
        { id: 'cycles', label: 'Cycles', icon: 'heroicons_outline:arrow-path' },
        { id: 'cut-points', label: 'Cut points', icon: 'heroicons_outline:scissors' },
        { id: 'cohesion', label: 'Cohesive groups', icon: 'heroicons_outline:cube' },
        { id: 'similarity', label: 'Similarity & prediction', icon: 'heroicons_outline:sparkles' },
        { id: 'flow', label: 'Flow & backbone', icon: 'heroicons_outline:beaker' },
        { id: 'scoring', label: 'Suspicion score', icon: 'heroicons_outline:shield-exclamation' },
        { id: 'pattern', label: 'Pattern match', icon: 'heroicons_outline:magnifying-glass-circle' },
        { id: 'index-build', label: 'Edge index', icon: 'heroicons_outline:server-stack' },
    ];

    /** The open tool group (accordion: one open at a time; `null` = all collapsed). */
    readonly tab = signal<AnalysisTab | null>('path');
    readonly pathFrom = signal('');
    readonly pathTo = signal('');
    /** Shortest path by fewest hops, or by strongest ties (weighted). */
    readonly pathMetric = signal<'hops' | 'weighted'>('hops');
    /** LA-11 knobs. Depth is clamped server-side to 1..10; the answer states the fence it applied. */
    readonly serverMapping = signal('0');
    readonly serverDepth = signal(6);
    readonly serverDirection = signal<'DIRECTED' | 'UNDIRECTED'>('DIRECTED');
    /** "To" is optional for a server walk — a blank-valued "any node" is the real no-target choice. */
    readonly optionalPathNodeOptions = computed<PickerOption[]>(() => [
        { value: '', label: 'Any node' },
        ...this.pathNodeOptions(),
    ]);
    readonly explainFor = signal('');
    readonly explainHops = signal(1);
    /** The server's id for the Explain node (the index run walks from it), or null when the graph carries no id map. */
    private readonly explainServerNode = computed(() => this.serverIds()?.serverNode(this.explainFor()) ?? null);
    /** `neighborhood` params for a run on the index; hops are sent as picked - the server's 2-hop fence is stated, not clamped. */
    readonly explainIndexParams = computed<Record<string, unknown>>(() => {
        const node = this.explainServerNode();
        return node ? { node, hops: this.explainHops() } : {};
    });
    readonly explainIndexHold = computed(() => (this.explainServerNode() ? '' : 'Pick a node first.'));
    /** `egoNetwork` params for a run on the index: the same Node as Explain; no hops (an ego network is one hop). */
    readonly egoIndexParams = computed<Record<string, unknown>>(() => {
        const node = this.explainServerNode();
        return node ? { node } : {};
    });
    /** Canvas ids picked to score from the index (`degreeCentrality` scores only the nodes named, 1..20). */
    readonly scoreSeeds = signal<string[]>([]);
    readonly scoreSeedPick = signal('');
    /** The picked seeds as the server's node ids; a node the id map does not know is left out and stated by the hold. */
    readonly scoreSeedIds = computed(() => {
        const map = this.serverIds();
        return this.scoreSeeds()
            .map((id) => map?.serverNode(id))
            .filter((id): id is string => !!id);
    });

    addScoreSeed(): void {
        const id = this.scoreSeedPick();
        if (id && !this.scoreSeeds().includes(id)) this.scoreSeeds.update((s) => [...s, id]);
        this.scoreSeedPick.set('');
    }

    removeScoreSeed(id: string): void {
        this.scoreSeeds.update((s) => s.filter((x) => x !== id));
    }
    readonly centralityMetric = signal<CentralityMetric>('degree');
    readonly analysisError = signal('');
    readonly pathResult = signal<{ hops: string[] } | null>(null);
    readonly allPathsResult = signal<GraphSelection[]>([]);
    readonly explainText = signal('');
    readonly ranking = signal<NodeScore[]>([]);
    readonly communityMethod = signal<'label-prop' | 'louvain'>('label-prop');
    readonly communities = signal<{ id: string; members: string[] }[]>([]);
    readonly components = signal<string[][]>([]);
    /** The pattern-match motif — step 0 = the start node; each later step traverses one edge. */
    readonly patternSteps = signal<PatternStep[]>([{}, { direction: 'out' }]);
    readonly patternMatches = signal<GraphSelection[]>([]);
    /**
     * The pattern packs (parameterized starter motifs) + the one loaded, for its hint. Seeded with the shipped
     * built-ins so the catalog is never empty, then REPLACED by this Space's authored `pattern-pack` components
     * when the fetch returns any (V2 (c)). A signal because this component is OnPush — reassigning a plain
     * field from the HTTP callback would never re-render. The built-ins stay the fallback on error / empty /
     * no write root, so the catalog is populated synchronously and never blank.
     */
    readonly patternPacks = signal<PatternPack[]>(PATTERN_PACKS);
    readonly loadedPack = signal<PatternPack | null>(null);
    /**
     * LA-14b — the loaded BRANCHING motif (a copy, editable), or null when the builder holds a linear one.
     * Its thresholds are rendered as editable fields so the band a match depends on is never hidden.
     */
    readonly branchStages = signal<BranchStage[] | null>(null);
    readonly thresholdLabel = thresholdLabel;
    // ── V2 result state (each group keeps its own so results survive tab switches) ──
    readonly cycles = signal<GraphSelection[]>([]);
    readonly cutNodes = signal<string[]>([]);
    readonly cutEdges = signal<string[]>([]);
    /** The id map (= Working Set) each server cut-points half was computed for; null = none, or a local run. */
    private cutNodesMap: ServerIdMap | null = null;
    private cutEdgesMap: ServerIdMap | null = null;
    /** The sentence for a server result the canvas could not fully show ('' = it showed all of it, or no server result). */
    readonly serverDropped = signal('');
    /** The sentence for a COMPLETED server result the server cut at its result cap ('' = not cut, or no server result). */
    readonly serverTruncated = signal('');

    private clearServerNotices(): void {
        this.serverDropped.set('');
        this.serverTruncated.set('');
    }
    readonly cohesionMetric = signal<'k-core' | 'triangles' | 'cliques'>('k-core');
    readonly cohesionRanking = signal<NodeScore[]>([]);
    readonly cliquesResult = signal<string[][]>([]);
    readonly similarityFor = signal('');
    readonly similarityResult = signal<NodeScore[]>([]);
    readonly predictions = signal<PredictedLink[]>([]);
    readonly flowFrom = signal('');
    readonly flowTo = signal('');
    readonly flowResult = signal<{ value: number; minCut: GraphSelection } | null>(null);
    readonly spanningForest = signal<GraphSelection | null>(null);
    readonly suspicion = signal<SuspicionScore[]>([]);
    /** ASSURE-RISK-SCORE-1: a saved risk-score model to read an entity's server-side Risk Score from. */
    readonly riskModel = signal('');
    /** The node whose Risk Score factor breakdown is shown (its id is the entity key). */
    readonly riskEntity = signal<string | null>(null);

    /**
     * Where an algorithm the browser would REFUSE (graph above its cap) goes instead: the server. Each entry is
     * non-null only when the displayed graph is over that algorithm's cap - at or under it the local run is
     * untouched. A computed, so the child control gets a stable object until something it depends on changes.
     */
    readonly serverSpecs = computed<Record<ServerTool, ServerRunSpec | null>>(() => {
        const n = this.workingSetNodes() ?? this.graph()?.nodes.length ?? 0;
        const subject = this.workingSetNodes() !== null ? 'The Working Set' : 'This graph';
        const spec = (
            algorithm: string,
            cap: number,
            params: Record<string, unknown> = {},
            hold = '',
        ): ServerRunSpec | null =>
            n > cap
                ? {
                      algorithm,
                      params,
                      hold,
                      note: `${subject} has ${n} nodes - above the ${cap}-node limit for running this in the browser.`,
                  }
                : null;
        const analysis = analysisNodeCapValue();
        // Selection algorithms: no browser cap of their own - one shared threshold, lowered by the server's ceiling.
        const selCap = (algorithm: string) =>
            Math.min(selectionNodeCapValue(), this.serverCeilings()?.[algorithm] ?? Number.POSITIVE_INFINITY);
        const pick = (a: string, b: string) => {
            const from = this.serverIds()?.serverNode(a);
            const to = this.serverIds()?.serverNode(b);
            return from && to ? { params: { from, to }, hold: '' } : { params: {}, hold: 'Pick the two nodes first.' };
        };
        const named = (prefix: string, s: ServerRunSpec | null): ServerRunSpec | null =>
            s && { ...s, note: prefix + s.note };
        const pathAlgorithm = this.pathMetric() === 'weighted' ? 'weightedShortestPath' : 'shortestPath';
        const pathPick = pick(this.pathFrom(), this.pathTo());
        const metric = this.centralityMetric();
        const centrality =
            metric === 'betweenness'
                ? spec('betweennessCentrality', suspicionNodeCapValue())
                : metric === 'closeness'
                  ? spec('closenessCentrality', analysis)
                  : metric === 'eigenvector'
                    ? spec('eigenvectorCentrality', analysis)
                    : metric === 'katz'
                      ? spec('katzCentrality', analysis)
                      : metric === 'hub' || metric === 'authority'
                        ? spec('hits', analysis)
                        : null; // degree and PageRank have no browser cap
        const from = this.serverIds()?.serverNode(this.flowFrom());
        const to = this.serverIds()?.serverNode(this.flowTo());
        return {
            centrality,
            communities: spec(
                this.communityMethod() === 'louvain' ? 'louvainCommunities' : 'detectCommunities',
                analysis,
            ),
            cliques: this.cohesionMetric() === 'cliques' ? spec('cliques', analysis) : null,
            prediction: spec('linkPrediction', analysis),
            flow: spec(
                'maxFlow',
                analysis,
                from && to ? { from, to } : {},
                from && to ? '' : 'Pick a source and a sink first.',
            ),
            scoring: spec('suspicionScore', suspicionNodeCapValue()),
            path: spec(pathAlgorithm, selCap(pathAlgorithm), pathPick.params, pathPick.hold),
            allPaths: spec('allPaths', selCap('allPaths'), pathPick.params, pathPick.hold),
            cycles: spec('findCycles', selCap('findCycles')),
            // Two algorithms behind one button: each has its own control, so each note names which one it is.
            cutNodes: named('Articulation nodes: ', spec('articulationPoints', selCap('articulationPoints'))),
            cutEdges: named('Bridges: ', spec('bridges', selCap('bridges'))),
            forest: spec('maximumSpanningForest', selCap('maximumSpanningForest')),
        };
    });

    /**
     * A selection algorithm hands over to the server (its local button is replaced) only when a server run can really
     * start: the Working Set is over the threshold AND an Investigation is open AND the Subject may run one. Otherwise
     * the local button stays, so a big query graph is never left with no way to run these (they never refuse locally).
     */
    serverFirst(tool: ServerTool): boolean {
        return !!this.serverSpecs()[tool] && !!this.investigationId() && this.canRunOnServer();
    }

    /**
     * A COMPLETED server run, applied through the SAME code as the matching local run (the `apply*` methods below), so a
     * server answer and a browser answer leave identical state. The ids were translated by the host's {@link serverIds}.
     */
    applyServerResult(r: GraphRunResult): void {
        const map = this.serverIds();
        if (!map) return;
        this.analysisError.set('');
        this.clearServerNotices();
        this.applyServerAnswer(r, map);
        // The analyst must be told when the canvas could not show all of a result (it drops ids it does not draw).
        this.serverDropped.set(droppedNotice(countDropped(r, map)));
        this.serverTruncated.set(truncationNotice(r));
    }

    private applyServerAnswer(r: GraphRunResult, map: ServerIdMap): void {
        switch (r.algorithm) {
            case 'betweennessCentrality':
            case 'closenessCentrality':
            case 'eigenvectorCentrality':
            case 'katzCentrality':
                return this.applyRanking(toNodeScores((r.scores ?? []) as GraphScoreView[], map));
            case 'hits':
                return this.applyRanking(
                    toNodeScores((this.centralityMetric() === 'authority' ? r.authorities : r.hubs) ?? [], map),
                );
            case 'detectCommunities':
            case 'louvainCommunities':
                return this.applyCommunities(toCommunityMap(r.communities ?? [], map));
            case 'cliques':
                return this.applyCliques(toGroups(r.groups ?? [], map));
            case 'linkPrediction':
                return this.applyPredictions(toPredictedLinks(r.links ?? [], map));
            case 'maxFlow':
                return this.applyFlow({
                    value: Number(r.value ?? 0),
                    minCut: toSelection(r.minCut ?? { nodeIds: [], edgeIds: [] }, map),
                });
            case 'suspicionScore':
                return this.applySuspicion(toSuspicionScores((r.scores ?? []) as GraphSuspicionView[], map));
            case 'shortestPath':
            case 'weightedShortestPath':
                return this.applyPath(r.selection ? toSelection(r.selection, map) : null);
            case 'allPaths':
                return this.applyAllPaths((r.selections ?? []).map((s) => toSelection(s, map)));
            case 'findCycles':
                return this.applyCycles((r.selections ?? []).map((s) => toSelection(s, map)));
            case 'articulationPoints': {
                // The other half only counts if the SAME id map (= the same Working Set) produced it; else it is stale.
                const nodes = (r.ids ?? []).map((id) => map.node(id)).filter((id): id is string => !!id);
                const edgesKnown = this.cutEdgesMap === map;
                this.applyCutPoints(nodes, edgesKnown ? this.cutEdges() : [], edgesKnown ? 'both' : 'nodes');
                this.cutNodesMap = map;
                this.cutEdgesMap = edgesKnown ? map : null;
                return;
            }
            case 'bridges': {
                const edges = (r.ids ?? []).map((id) => map.edge(id)).filter((id): id is string => !!id);
                const nodesKnown = this.cutNodesMap === map;
                this.applyCutPoints(nodesKnown ? this.cutNodes() : [], edges, nodesKnown ? 'both' : 'edges');
                this.cutEdgesMap = map;
                this.cutNodesMap = nodesKnown ? map : null;
                return;
            }
            case 'maximumSpanningForest':
                return this.applySpanningForest(toSelection(r.selection ?? { nodeIds: [], edgeIds: [] }, map));
        }
    }

    /** Node label via the host-supplied lookup. */
    label(id: string): string {
        return this.labelOf()(id);
    }

    /** Clear all result state (called by the host when a fresh graph is loaded). Presentation-only
     *  choices (open tab, hop count, metric, motif) deliberately persist, matching the prior behavior. */
    reset(): void {
        this.pathResult.set(null);
        this.allPathsResult.set([]);
        this.explainText.set('');
        this.ranking.set([]);
        this.communities.set([]);
        this.components.set([]);
        this.patternMatches.set([]);
        this.cycles.set([]);
        this.cutNodes.set([]);
        this.cutEdges.set([]);
        this.cutNodesMap = null;
        this.cutEdgesMap = null;
        this.cohesionRanking.set([]);
        this.cliquesResult.set([]);
        this.similarityResult.set([]);
        this.predictions.set([]);
        this.flowResult.set(null);
        this.spanningForest.set(null);
        this.suspicion.set([]);
        this.analysisError.set('');
        this.clearServerNotices();
        this.pathFrom.set('');
        this.pathTo.set('');
        this.explainFor.set('');
        this.similarityFor.set('');
        this.flowFrom.set('');
        this.flowTo.set('');
    }

    /** Accordion header click — open this group, or collapse it if already open. */
    toggleTool(tool: AnalysisTab): void {
        this.tab.set(this.tab() === tool ? null : tool);
    }

    /** The result chip on a tool-group header (empty until that analysis has run). */
    toolBadge(tool: AnalysisTab): string {
        switch (tool) {
            case 'path': {
                const p = this.pathResult();
                return p ? `${p.hops.length} hops` : '';
            }
            case 'explain':
                return this.explainText() ? this.label(this.explainFor()) : '';
            case 'index-build':
                return '';
            case 'centrality':
                return this.ranking().length ? `top ${this.ranking().length}` : '';
            case 'communities':
                return this.communities().length ? `${this.communities().length} found` : '';
            case 'pattern':
                return this.patternMatches().length ? `${this.patternMatches().length} matches` : '';
            case 'all-paths':
                return this.allPathsResult().length ? `${this.allPathsResult().length} paths` : '';
            case 'server-paths': {
                const sp = this.serverPaths();
                return sp ? `${sp.paths.length} paths${sp.truncated ? ' · truncated' : ''}` : '';
            }
            case 'components':
                return this.components().length ? `${this.components().length} found` : '';
            case 'cycles':
                return this.cycles().length ? `${this.cycles().length} found` : '';
            case 'cut-points':
                return this.cutNodes().length || this.cutEdges().length
                    ? `${this.cutNodes().length} nodes · ${this.cutEdges().length} bridges`
                    : '';
            case 'cohesion':
                return this.cohesionMetric() === 'cliques'
                    ? this.cliquesResult().length
                        ? `${this.cliquesResult().length} cliques`
                        : ''
                    : this.cohesionRanking().length
                      ? `top ${this.cohesionRanking().length}`
                      : '';
            case 'similarity':
                return this.similarityResult().length || this.predictions().length
                    ? `${this.similarityResult().length} similar · ${this.predictions().length} predicted`
                    : '';
            case 'flow': {
                const f = this.flowResult();
                return f
                    ? `flow ${f.value}`
                    : this.spanningForest()
                      ? `${this.spanningForest()!.edgeIds.length} edges`
                      : '';
            }
            case 'scoring':
                return this.suspicion().length ? `top ${this.suspicion().length}` : '';
        }
    }

    // ── analysis ──

    runPath(): void {
        const g = this.graph();
        if (!g || !this.pathFrom() || !this.pathTo()) return;
        this.analysisError.set('');
        this.clearServerNotices();
        this.applyPath(
            this.pathMetric() === 'weighted'
                ? weightedShortestPath(g, this.pathFrom(), this.pathTo())
                : shortestPath(g, this.pathFrom(), this.pathTo()),
        );
    }

    private applyPath(p: GraphSelection | null): void {
        if (!p) {
            this.pathResult.set(null);
            this.emphasisChange.emit(null);
            this.analysisError.set('No path connects the two nodes.');
            return;
        }
        this.pathResult.set({ hops: p.nodeIds });
        this.emphasisChange.emit({ nodeIds: p.nodeIds, edgeIds: p.edgeIds });
    }

    runExplain(): void {
        const g = this.graph();
        const id = this.explainFor();
        if (!g || !id) return;
        const nb = neighborhood(g, id, this.explainHops());
        this.explainText.set(explainNode(g, id));
        this.emphasisChange.emit({ nodeIds: nb.nodes.map((n) => n.id), edgeIds: nb.edges.map((e) => e.id) });
    }

    runCentrality(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        this.clearServerNotices();
        try {
            this.applyRanking(this.centralityScores(g));
        } catch (err) {
            this.ranking.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
        }
    }

    private applyRanking(scores: NodeScore[]): void {
        this.ranking.set(scores.slice(0, 20));
        this.emphasisChange.emit(null);
    }

    private centralityScores(g: G6GraphData): NodeScore[] {
        switch (this.centralityMetric()) {
            case 'betweenness':
                return betweennessCentrality(g);
            case 'closeness':
                return closenessCentrality(g);
            case 'eigenvector':
                return eigenvectorCentrality(g);
            case 'katz':
                return katzCentrality(g);
            case 'pagerank':
                return pageRank(g);
            case 'hub':
                return hits(g).hubs;
            case 'authority':
                return hits(g).authorities;
            default:
                return degreeCentrality(g);
        }
    }

    focusNode(id: string): void {
        this.emphasisChange.emit({ nodeIds: [id], edgeIds: [] });
    }

    /** A ranking row: highlight the node and ask the host to select and centre it. */
    pickNode(id: string): void {
        this.focusNode(id);
        this.nodePick.emit(id);
    }

    /** How many top results {@link startInvestigationFrom} carries over. */
    readonly seedCount = TOP_SEED_COUNT;

    startInvestigationFrom(ranked: readonly { id: string; label: string }[]): void {
        this.seedFromResults.emit(topSeeds(ranked, this.graph()));
    }

    runCommunities(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        this.clearServerNotices();
        let byNode: Map<string, string>;
        try {
            byNode = this.communityMethod() === 'louvain' ? louvainCommunities(g) : detectCommunities(g);
        } catch (err) {
            this.communities.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
            return;
        }
        this.applyCommunities(byNode);
    }

    private applyCommunities(byNode: Map<string, string>): void {
        const grouped = new Map<string, string[]>();
        for (const [node, community] of byNode) {
            const arr = grouped.get(community) ?? [];
            arr.push(node);
            grouped.set(community, arr);
        }
        const list = [...grouped.entries()]
            .map(([id, members]) => ({ id, members }))
            .sort((a, b) => b.members.length - a.members.length);
        this.communities.set(list);
        this.emphasisChange.emit({ nodeIds: [], groups: byNode });
    }

    focusCommunity(members: string[]): void {
        this.emphasisChange.emit({ nodeIds: members, edgeIds: [] });
    }

    runAllPaths(): void {
        const g = this.graph();
        if (!g || !this.pathFrom() || !this.pathTo()) return;
        this.analysisError.set('');
        this.clearServerNotices();
        this.applyAllPaths(allPaths(g, this.pathFrom(), this.pathTo()));
    }

    private applyAllPaths(paths: GraphSelection[]): void {
        this.allPathsResult.set(paths);
        if (!paths.length) {
            this.emphasisChange.emit(null);
            this.analysisError.set('No path connects the two nodes.');
            return;
        }
        this.emphasisChange.emit({
            nodeIds: [...new Set(paths.flatMap((p) => p.nodeIds))],
            edgeIds: [...new Set(paths.flatMap((p) => p.edgeIds))],
        });
    }

    /** LA-11: ask the host to walk paths from `pathFrom` (to `pathTo`, when set) over the chosen edge mapping. */
    runServerPaths(): void {
        if (!this.pathFrom() || !this.traversalMappings().length) return;
        const idx = Number(this.serverMapping());
        this.findPaths.emit({
            from: this.pathFrom(),
            to: this.pathTo() && this.pathTo() !== this.pathFrom() ? this.pathTo() : undefined,
            mapping: idx >= 0 && idx < this.traversalMappings().length ? idx : 0,
            maxDepth: Math.max(1, Math.min(10, Math.floor(this.serverDepth() || 6))),
            direction: this.serverDirection(),
        });
    }

    /** LA-14b — the loaded branching motif, over the whole Dataset of the picked edge mapping. */
    runBranchingOnServer(): void {
        const stages = this.branchStages();
        if (!stages || !this.traversalMappings().length) return;
        const idx = Number(this.serverMapping());
        this.runPatternOnServer.emit({ mapping: idx >= 0 && idx < this.traversalMappings().length ? idx : 0, stages });
    }

    focusAllPath(p: GraphSelection): void {
        this.emphasisChange.emit({ nodeIds: p.nodeIds, edgeIds: p.edgeIds });
    }

    runConnectedComponents(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        this.clearServerNotices();
        this.components.set(connectedComponents(g));
    }

    focusComponent(members: string[]): void {
        this.emphasisChange.emit({ nodeIds: members, edgeIds: [] });
    }

    // ── pattern matching (motif builder) ──

    /** Load a pattern pack's motif into the builder (a fresh copy so edits don't mutate the catalog). */
    loadPatternPack(id: string): void {
        const pack = this.patternPacks().find((p) => p.id === id) ?? null;
        this.loadedPack.set(pack);
        this.branchStages.set(
            pack?.stages
                ? pack.stages.map((s) => ({ ...s, ...(s.threshold ? { threshold: { ...s.threshold } } : {}) }))
                : null,
        );
        if (!pack) return;
        this.patternSteps.set(pack.steps.map((s) => ({ ...s })));
        this.patternMatches.set([]);
        this.analysisError.set('');
        this.clearServerNotices();
    }

    addPatternStep(): void {
        this.patternSteps.update((s) => [...s, { direction: 'out' }]);
    }

    removePatternStep(i: number): void {
        this.patternSteps.update((s) => (s.length > 1 ? s.filter((_, idx) => idx !== i) : s));
    }

    updatePatternStep(i: number, patch: Partial<PatternStep>): void {
        this.patternSteps.update((s) => s.map((step, idx) => (idx === i ? { ...step, ...patch } : step)));
    }

    /** Edit one branching stage (a fresh array, so the OnPush template re-renders; the catalog is untouched). */
    updateBranchStage(i: number, patch: Partial<BranchStage>): void {
        this.branchStages.update((st) => st?.map((s, idx) => (idx === i ? { ...s, ...patch } : s)) ?? null);
    }

    /** Edit one stage's threshold band. A blank bound is "no bound", never 0. */
    updateBranchThreshold(i: number, patch: Partial<LegThreshold>): void {
        this.branchStages.update(
            (st) =>
                st?.map((s, idx) =>
                    idx === i && s.threshold ? { ...s, threshold: { ...s.threshold, ...patch } } : s,
                ) ?? null,
        );
    }

    /** A number input's value as a bound: blank / unparseable ⇒ undefined (no bound). */
    boundOf(raw: string): number | undefined {
        const n = raw.trim() === '' ? Number.NaN : Number(raw);
        return Number.isFinite(n) ? n : undefined;
    }

    runPattern(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        this.clearServerNotices();
        const stages = this.branchStages();
        if (stages) {
            this.runBranching(g, stages);
            return;
        }
        const steps = this.patternSteps();
        const timeAttr = this.timeAttr();
        // LA-14a: say WHY nothing can be found. `matchPattern` fails closed on a temporal motif with no
        // time column, and "No matches" is the same sentence a genuinely empty result produces - which
        // would tell the analyst the chain is absent when in fact it was never looked for.
        if (patternNeedsTime(steps) && !timeAttr) {
            this.patternMatches.set([]);
            this.emphasisChange.emit(null);
            this.analysisError.set(
                'This pattern requires the hops to be in time order — choose a time column in the Query panel first.',
            );
            return;
        }
        const matches = matchPattern(g, steps, { timeAttr });
        this.patternMatches.set(matches);
        if (!matches.length) {
            this.emphasisChange.emit(null);
            this.analysisError.set('No matches for this pattern.');
            return;
        }
        this.emphasisChange.emit({
            nodeIds: [...new Set(matches.flatMap((m) => m.nodeIds))],
            edgeIds: [...new Set(matches.flatMap((m) => m.edgeIds))],
        });
    }

    /**
     * LA-14b — run a branching motif. Every refusal (no time column, a threshold nothing passes because the
     * view filtered the legs away, the node cap) is shown as its own sentence, never as "No matches".
     */
    private runBranching(g: G6GraphData, stages: BranchStage[]): void {
        const res = matchBranchingPattern(g, stages, { timeAttr: this.timeAttr() });
        this.patternMatches.set(res.matches);
        if (res.refusal || !res.matches.length) {
            this.emphasisChange.emit(null);
            this.analysisError.set(res.refusal ?? 'No matches for this pattern.');
            return;
        }
        if (res.truncated) {
            this.analysisError.set(`Showing the first ${res.matches.length} matches — there may be more.`);
        }
        this.emphasisChange.emit({
            nodeIds: [...new Set(res.matches.flatMap((m) => m.nodeIds))],
            edgeIds: [...new Set(res.matches.flatMap((m) => m.edgeIds))],
        });
    }

    focusMatch(m: GraphSelection): void {
        this.emphasisChange.emit({ nodeIds: m.nodeIds, edgeIds: m.edgeIds });
    }

    /**
     * The node labels of a match joined into a readable chain (`Acme → Bob → Store`). A branching match
     * (LA-14b) reads by LAYER instead — `SMURF-01, SMURF-02 +10 ⇒ MULE-HUB-01 ⇒ RELAY-01, RELAY-02 ⇒ OFFSHORE-77`.
     */
    patternMatchLabel(m: GraphSelection): string {
        const layers = (m as Partial<BranchingMatch>).layers;
        if (layers) {
            return layers
                .map((layer) => {
                    const shown = layer.slice(0, 2).map((id) => this.label(id));
                    return layer.length > 2 ? `${shown.join(', ')} +${layer.length - 2}` : shown.join(', ');
                })
                .join(' ⇒ ');
        }
        return m.nodeIds.map((id) => this.label(id)).join(' → ');
    }

    // ── V2: advanced traversal ──

    runCycles(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        this.clearServerNotices();
        this.applyCycles(findCycles(g));
    }

    private applyCycles(found: GraphSelection[]): void {
        this.cycles.set(found);
        if (!found.length) {
            this.emphasisChange.emit(null);
            this.analysisError.set('No cycles in this graph.');
            return;
        }
        this.emphasisChange.emit({
            nodeIds: [...new Set(found.flatMap((c) => c.nodeIds))],
            edgeIds: [...new Set(found.flatMap((c) => c.edgeIds))],
        });
    }

    focusCycle(c: GraphSelection): void {
        this.emphasisChange.emit({ nodeIds: c.nodeIds, edgeIds: c.edgeIds });
    }

    /** A cycle rendered as a closed chain (`A → B → C → A`). */
    cycleLabel(c: GraphSelection): string {
        return [...c.nodeIds, c.nodeIds[0]].map((id) => this.label(id)).join(' → ');
    }

    runCutPoints(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        this.clearServerNotices();
        this.cutNodesMap = null;
        this.cutEdgesMap = null;
        this.applyCutPoints(articulationPoints(g), bridges(g), 'both');
    }

    /** `ran` = which halves this answer actually computed; "No cut points" is only claimed when both were. */
    private applyCutPoints(nodes: string[], edges: string[], ran: 'both' | 'nodes' | 'edges'): void {
        this.cutNodes.set(nodes);
        this.cutEdges.set(edges);
        if (!nodes.length && !edges.length) {
            this.emphasisChange.emit(null);
            this.analysisError.set(
                ran === 'both'
                    ? 'No cut points — the graph has no single points of failure.'
                    : ran === 'nodes'
                      ? 'No articulation nodes found — links (bridges) have not been checked for this Working Set yet.'
                      : 'No bridges found — nodes (articulation) have not been checked for this Working Set yet.',
            );
            return;
        }
        this.emphasisChange.emit({ nodeIds: nodes, edgeIds: edges });
    }

    // ── V2: cohesive groups ──

    runCohesion(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        this.clearServerNotices();
        try {
            if (this.cohesionMetric() === 'cliques') {
                this.applyCliques(cliques(g));
            } else {
                const scores = this.cohesionMetric() === 'triangles' ? triangleCount(g) : kCore(g);
                this.cohesionRanking.set(scores.slice(0, 20));
                this.cliquesResult.set([]);
                this.emphasisChange.emit(null);
            }
        } catch (err) {
            this.cohesionRanking.set([]);
            this.cliquesResult.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
        }
    }

    private applyCliques(found: string[][]): void {
        this.cliquesResult.set(found);
        this.cohesionRanking.set([]);
        this.emphasisChange.emit(found.length ? { nodeIds: [...new Set(found.flat())], edgeIds: [] } : null);
        if (!found.length) this.analysisError.set('No cliques of size 3 or more.');
    }

    focusClique(members: string[]): void {
        this.emphasisChange.emit({ nodeIds: members, edgeIds: [] });
    }

    cliqueLabel(members: string[]): string {
        return members.map((id) => this.label(id)).join(', ');
    }

    // ── V2: similarity & link prediction ──

    runSimilarity(): void {
        const g = this.graph();
        if (!g || !this.similarityFor()) return;
        this.analysisError.set('');
        this.clearServerNotices();
        this.similarityResult.set(
            jaccardSimilarity(g, this.similarityFor())
                .filter((s) => s.score > 0)
                .slice(0, 20),
        );
        this.emphasisChange.emit({ nodeIds: [this.similarityFor()], edgeIds: [] });
    }

    runPrediction(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        this.clearServerNotices();
        try {
            this.applyPredictions(linkPrediction(g));
        } catch (err) {
            this.predictions.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
        }
    }

    private applyPredictions(found: PredictedLink[]): void {
        this.predictions.set(found);
        if (!found.length) this.analysisError.set('No likely missing links found.');
    }

    focusPrediction(p: PredictedLink): void {
        this.emphasisChange.emit({ nodeIds: [p.source, p.target], edgeIds: [] });
    }

    // ── V2: flow & backbone ──

    runFlow(): void {
        const g = this.graph();
        if (!g || !this.flowFrom() || !this.flowTo()) return;
        this.analysisError.set('');
        this.clearServerNotices();
        try {
            this.applyFlow(maxFlow(g, this.flowFrom(), this.flowTo()));
        } catch (err) {
            this.flowResult.set(null);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
        }
    }

    private applyFlow(result: { value: number; minCut: GraphSelection }): void {
        this.flowResult.set(result);
        this.spanningForest.set(null);
        this.emphasisChange.emit(result.minCut.edgeIds.length ? result.minCut : null);
        if (!result.value) this.analysisError.set('No flow between the two nodes.');
    }

    runSpanningForest(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        this.clearServerNotices();
        this.applySpanningForest(maximumSpanningForest(g));
    }

    private applySpanningForest(msf: GraphSelection): void {
        this.spanningForest.set(msf);
        this.flowResult.set(null);
        this.emphasisChange.emit(msf.edgeIds.length ? msf : null);
    }

    // ── V2: suspicious-node scoring ──

    runScoring(): void {
        const g = this.graph();
        if (!g) return;
        this.analysisError.set('');
        this.clearServerNotices();
        try {
            this.applySuspicion(suspicionScore(g));
        } catch (err) {
            this.suspicion.set([]);
            this.analysisError.set(err instanceof Error ? err.message : 'The analysis failed.');
        }
    }

    private applySuspicion(scores: SuspicionScore[]): void {
        this.suspicion.set(scores.slice(0, 20));
        // Highlight the top decile (at least the top node) so the riskiest nodes stand out.
        const topN = Math.max(1, Math.round(scores.length * 0.1));
        this.emphasisChange.emit({ nodeIds: scores.slice(0, topN).map((s) => s.id), edgeIds: [] });
    }
}
