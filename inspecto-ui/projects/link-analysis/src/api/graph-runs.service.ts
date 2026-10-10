import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { EMPTY, Observable, concat, defer, exhaustMap, of, switchMap, takeWhile, timer } from 'rxjs';
import { apiErrorMessage, apiUrl, toParams } from '@inspecto/core/api/api-base';

/**
 * LA separation D-4 step 7 — the server side of graph analysis (`GraphRunRoutes`, as built in `docs/okf/frontend/features/link-analysis.md`
 * §Graph Run): start a **Graph Run** over an Investigation's Working Set, poll it, cancel it. The browser keeps running every
 * algorithm locally under its cap; this is what runs above it.
 *
 * The wire shapes below mirror `GraphRunRoutes.view` / `GraphResultJson` field for field.
 */

export type GraphRunStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'BUDGET_EXCEEDED' | 'FAILED';

/** The states a run never leaves. Only `COMPLETED` carries a `result`. */
export function isTerminalGraphRun(status: GraphRunStatus): boolean {
    return status !== 'QUEUED' && status !== 'RUNNING';
}

export type GraphExceededReason = 'NODES' | 'EDGES' | 'TIMEOUT' | 'WORK';

export interface GraphBudgetView {
    maxNodes: number;
    maxEdges: number;
    timeoutMs: number;
}

export type GraphResultKind =
    | 'SCORES'
    | 'HITS'
    | 'SELECTION'
    | 'SELECTIONS'
    | 'GROUPS'
    | 'COMMUNITIES'
    | 'IDS'
    | 'FLAG'
    | 'FLOW'
    | 'LINKS'
    | 'SUSPICION'
    | 'GRAPH'
    | 'PROPAGATED_RISK';

export interface GraphScoreView {
    id: string;
    label: string;
    score: number;
}

export interface GraphSuspicionView extends GraphScoreView {
    factors: { degree: number; betweenness: number; pageRank: number; core: number; triangles: number };
}

/** `propagatedRisk`: `score` is `raw` capped at 100; `factors` are the top contributing origins of `contributors`. */
export interface GraphPropagatedRiskView extends GraphScoreView {
    raw: number;
    own: number;
    contributors: number;
    factors: { origin: string; distance: number; weight: number; contribution: number }[];
}

/** `edgeIds` are D-U9 wire ids (`linkId`), `nodeIds` the (possibly masked) entity ids - NOT the canvas's own ids. */
export interface GraphSelectionView {
    nodeIds: string[];
    edgeIds: string[];
}

export interface GraphPredictedLinkView {
    source: string;
    target: string;
    sourceLabel: string;
    targetLabel: string;
    score: number;
}

/** One list of a result as the server cut it (`GraphResultJson`): `total` is the full count, `returned` what is here. */
export interface GraphListCut {
    total: number;
    returned: number;
    limit: number;
    truncated: boolean;
}

/** `{algorithm, kind, dropped, elapsedMs}` plus the fields of the variant `kind` names. */
export interface GraphRunResult {
    algorithm: string;
    kind: GraphResultKind;
    dropped: number;
    elapsedMs: number;
    /** Never a silent cap: true when ANY list below was cut at `graph_run.max_result_items`; `lists` says which and by how much. */
    truncated?: boolean;
    /** Per list: every top-level one, plus a nested one (`groups[0]`, `selection.nodeIds`) only when it was cut. */
    lists?: Record<string, GraphListCut>;
    scores?: GraphScoreView[] | GraphSuspicionView[] | GraphPropagatedRiskView[];
    hubs?: GraphScoreView[];
    authorities?: GraphScoreView[];
    selection?: GraphSelectionView | null;
    selections?: GraphSelectionView[];
    groups?: string[][];
    /** An ordered list of pairs, not an object: the algorithm's pair order is part of the answer. */
    communities?: { id: string; community: string }[];
    ids?: string[];
    value?: number | boolean;
    minCut?: GraphSelectionView | null;
    links?: GraphPredictedLinkView[];
    nodes?: { id: string; label: string }[];
    edges?: { id: string; source: string; target: string }[];
}

export interface GraphRunSource {
    kind: 'index';
    version: number;
    stale: boolean;
    staleReason?: string;
    fingerprint?: string;
}

export interface GraphRunView {
    runId: string;
    status: GraphRunStatus;
    investigationId: string;
    algorithm: string;
    engine: string;
    budget: GraphBudgetView;
    budgetClamped: boolean;
    consumed: { nodes: number; edges: number; elapsedMs: number; work: number };
    progress: { work: number; fraction: number };
    cancelRequested: boolean;
    cached: boolean;
    /** BUDGET_EXCEEDED only: which limit, and the server's own sentence naming the numbers. */
    exceeded?: GraphExceededReason;
    reason?: string;
    /** FAILED only: the exception CLASS name (never its message). */
    failure?: string;
    createdAt: string;
    finishedAt?: string;
    /** Absent on the list; `at` is the log step the run read. An index run has no `at`, and its counts are ESTIMATES (`estimated`). */
    input?: {
        nodes: number;
        edges: number;
        hiddenEntities: number;
        droppedDangling: number;
        at?: number;
        kind?: 'index';
        version?: number;
        estimated?: boolean;
    };
    /** An index run only (a Working Set run has no such key): which index version answered, and whether it was stale. */
    source?: GraphRunSource;
    /** ONLY on a COMPLETED run - a BUDGET_EXCEEDED / CANCELLED / FAILED run has no such key. */
    result?: GraphRunResult;
    masking?: Record<string, unknown>;
}

export interface GraphRunRequest {
    investigationId: string;
    /** Where the graph is read from: the Investigation's Working Set (default) or a published edge index (D-3 step 7). */
    input?: 'workingSet' | 'index';
    /** `input: 'index'` only: the indexed Dataset and the index's own edge mapping. A 422 on a Working Set run. */
    dataset?: string;
    sourceCol?: string;
    targetCol?: string;
    linkKindCol?: string;
    /** `input: 'index'` + `degreeCentrality` only: the 1..20 node ids to score. */
    seeds?: string[];
    /** Log step to read; absent = the committed head (what the canvas shows). */
    at?: number;
    algorithm: string;
    params?: Record<string, unknown>;
    weights?: 'count' | 'none';
    kinds?: string[];
    budget?: Partial<GraphBudgetView>;
}

/**
 * The catalogue's parameter types. `DOUBLE_LIST` / `ID_LIST` / `SCORE_MAP` are JSON arrays / an id → number object; for
 * them `min`/`max` bound each number and `maxSize` the entry count.
 */
export type GraphParamType = 'INT' | 'DOUBLE' | 'ENUM' | 'DOUBLE_LIST' | 'ID_LIST' | 'SCORE_MAP';

export interface GraphAlgorithmParam {
    name: string;
    type: GraphParamType;
    default: unknown;
    min?: number;
    max?: number;
    allowed?: string[];
    maxSize?: number;
}

export interface GraphAlgorithm {
    id: string;
    label: string;
    cost: 'SYNC' | 'JOB';
    /** Largest Working Set a request WAITS for; above it the answer is a 202 the caller polls. Not a limit. */
    inlineNodeCeiling: number;
    params: GraphAlgorithmParam[];
    needsWeights: boolean;
    needsSource: boolean;
    needsTarget: boolean;
    needsNode: boolean;
    resultKind: GraphResultKind;
    /** The engines that can run it: always `memory`, plus `index` for neighborhood, egoNetwork and a seeds-only degreeCentrality. */
    engines?: string[];
}

/** One index as `GET /inv/index` lists it (the fields the Run panel reads). */
export interface LinkIndexSummary {
    dataset: string;
    mapping: {
        sourceCol: string;
        targetCol: string;
        kindCol: string | null;
        timeCol?: string | null;
        timeColZone?: string | null;
        weightCol?: string | null;
        attrCols?: string[] | null;
    };
    version: number;
    stale: boolean;
    reason: string | null;
    /** Appended file groups the version carries (an append is refused at 8: compact first). */
    deltas?: number;
    /** Advice only (D-3 step 8): what differs between the version and the Dataset now, and which build closes the gap. */
    plan?: LinkIndexPlan;
}

export type LinkIndexBuildMode = 'full' | 'append' | 'compact';

export interface LinkIndexPlan {
    recommended: 'none' | LinkIndexBuildMode;
    appendable: boolean;
    reasons: string[];
    added: number;
    removed: number;
    changed: number;
    samples?: { added?: string[]; removed?: string[]; changed?: string[] };
}

/** `POST /inv/index/builds` body: the index's own mapping plus the `mode`. */
export interface LinkIndexBuildRequest {
    dataset: string;
    sourceCol: string;
    targetCol: string;
    kindCol?: string;
    timeCol?: string;
    timeColZone?: string;
    weightCol?: string;
    attrCols?: string[];
    mode: LinkIndexBuildMode;
}

export type LinkIndexBuildStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED';

export interface LinkIndexBuildView {
    buildId: string;
    status: LinkIndexBuildStatus;
    dataset?: string;
    progress?: { phase: string; step: number; steps: number };
    cancelRequested?: boolean;
    failure?: string;
    result?: { version: number; rows: number; edges: number; nodes: number; bytes: number; totalMs: number };
}

export interface LinkIndexList {
    /** `index.enabled` of the Space: while false the server refuses every index run (`index_disabled`). */
    enabled: boolean;
    indexes: LinkIndexSummary[];
}

/** The fields of `GET /modules` the Link Analysis modules view reads. `state` is e.g. ACTIVE / INERT / not-installed. */
export interface ModuleView {
    id: string;
    title: string;
    state: string;
    buildId: string | null;
    reasons?: string[];
    enabledInSpace: boolean;
}

export interface ModulesReport {
    hostBuildId: string;
    modules: ModuleView[];
}

export interface GraphAlgorithmCatalogue {
    engine: string;
    algorithms: GraphAlgorithm[];
    /** The server's hard limits: a budget is clamped to them. */
    ceilings: GraphBudgetView;
    /** The budget a run takes when it states none (the Space's `graph_run` settings, clamped). */
    defaults: GraphBudgetView & { clamped: boolean };
    pool: { threads: number; queue: number };
    inlineWaitMs: number;
}

/** The server error code inside a v1 error body (`{ error: { errorCode, message } }`), else ''. */
function errorCodeOf(err: HttpErrorResponse): string {
    const c = (err.error as { error?: { errorCode?: unknown } } | null)?.error?.errorCode;
    return typeof c === 'string' ? c : '';
}

/**
 * The sentence an analyst reads for a refused `input: 'index'` run (a 422): the closed reason codes and the fences the server
 * names, in plain words - and ALWAYS the server's own sentence after them, so no cause is lost. Never a hint that the Working
 * Set was used instead: it was not.
 */
export function indexRefusalMessage(serverMessage: string): string {
    const m = serverMessage;
    const rules: [RegExp, string][] = [
        [/index_disabled/, 'The edge index is switched off for this Space (index.enabled is false).'],
        [/no_index/, 'No edge index has been built for this Dataset yet.'],
        [
            /index_stale_refused/,
            'The index is stale and rows it still holds may have been removed from the Dataset, so it was refused. Rebuild the index.',
        ],
        [
            /mapping_not_indexed|column_not_indexed/,
            'The index was built over different columns than this run asked for.',
        ],
        [
            /depth_over_index_cap|hops \d+ is over/,
            'The index answers at most 2 hops. Run it on the Working Set for a deeper walk.',
        ],
        [
            /frontier_over_index_cap|index cap is 20|lists \d+ nodes/,
            'The index looks up at most 20 nodes per step. Pick fewer nodes, or run it on the Working Set.',
        ],
        [
            /'at' cannot be combined/,
            'The index holds the Dataset as built, so a past step of the Investigation cannot be read from it.',
        ],
        [
            /hides entities/,
            'This Investigation hides entities, which the index cannot honour. Run it on the Working Set.',
        ],
        [/cannot run from the index/, 'This algorithm cannot run from the index. Run it on the Working Set.'],
    ];
    const hit = rules.find(([re]) => re.test(m));
    return hit ? `${hit[1]} (Server: ${m})` : `The index run was refused: ${m}`;
}

/**
 * The sentence an analyst reads for a failed graph-run call. Each status means something specific on these routes:
 * 403 the capability, 404 an Investigation/run the caller cannot see (on cancel too: somebody else's run, or one whose Investigation they lost, answers like an unknown run), 409 a run
 * that already finished, 422 a request the server refused (its own words are the useful part), 503 either the
 * waiting line is full (`STORE_BUSY`) or server-side runs are not available here at all.
 */
export function graphRunErrorMessage(err: unknown, fallback: string): string {
    if (!(err instanceof HttpErrorResponse)) return fallback;
    const server = apiErrorMessage(err, '');
    switch (err.status) {
        case 403:
            return 'You are not allowed to run graph analysis on the server (it needs the Run link graph analysis capability).';
        case 404:
            return 'The Investigation or run was not found - it may be closed, or it is not yours to read.';
        case 409:
            return 'That run has already finished.';
        case 422:
            return server ? `The server refused the run: ${server}` : 'The server refused the run.';
        case 503:
            return errorCodeOf(err) === 'STORE_BUSY'
                ? 'The server is busy: its waiting line for graph runs is full. Try again in a moment.'
                : 'Server-side graph runs are not available here (the server has no writable Space, is shutting down, or lacks the Link Analysis module).';
        default:
            return server || fallback;
    }
}

@Injectable({ providedIn: 'root' })
export class GraphRunsService {
    private readonly http = inject(HttpClient);

    private loadingCatalogue = false;

    /** How often a started run is re-read, in ms. */
    pollMs = 1000;

    /** The catalogue once `loadCatalogue` has answered; null until then (and when the server cannot answer). */
    readonly catalogue = signal<GraphAlgorithmCatalogue | null>(null);

    /** The Space's indexes, once {@link loadIndexes} has answered; null until then (and when the server cannot answer). */
    readonly indexes = signal<LinkIndexList | null>(null);
    private loadingIndexes = false;

    /** `GET /inv/index`: whether the Space serves indexes and which exist. A failure leaves it null - no index is offered. */
    loadIndexes(): void {
        if (this.indexes() || this.loadingIndexes) return;
        this.loadingIndexes = true;
        this.http.get<LinkIndexList>(apiUrl('/inv/index')).subscribe({
            next: (l) => this.indexes.set(l),
            error: () => (this.loadingIndexes = false),
            complete: () => (this.loadingIndexes = false),
        });
    }

    /** `POST /inv/index/builds`: always 202; poll {@link watchBuild}. */
    startBuild(req: LinkIndexBuildRequest): Observable<LinkIndexBuildView> {
        return this.http.post<LinkIndexBuildView>(apiUrl('/inv/index/builds'), req);
    }

    /**
     * `POST /inv/index/builds/{id}/cancel` → 202 `{buildId, status, cancelRequested}`: the build's starter or an
     * administrator (anyone else, and a Dataset no longer viewable, is the same 404 as an unknown build); a build that
     * already finished is a 409. The build ends `CANCELLED` on the next {@link watchBuild} read, never `FAILED`.
     */
    cancelBuild(
        buildId: string,
    ): Observable<{ buildId: string; status: LinkIndexBuildStatus; cancelRequested: boolean }> {
        return this.http.post<{ buildId: string; status: LinkIndexBuildStatus; cancelRequested: boolean }>(
            apiUrl(`/inv/index/builds/${encodeURIComponent(buildId)}/cancel`),
            {},
        );
    }

    /** Re-read a build every {@link pollMs} until it is terminal (the terminal view last), then refresh {@link indexes}. */
    watchBuild(buildId: string): Observable<LinkIndexBuildView> {
        return timer(this.pollMs, this.pollMs).pipe(
            exhaustMap(() =>
                this.http.get<LinkIndexBuildView>(apiUrl(`/inv/index/builds/${encodeURIComponent(buildId)}`)),
            ),
            takeWhile((v) => v.status === 'QUEUED' || v.status === 'RUNNING', true),
        );
    }

    /** Re-ask `GET /inv/index` (after a build the version, the stale flag and the plan all changed). */
    reloadIndexes(): void {
        this.indexes.set(null);
        this.loadIndexes();
    }

    /** `GET /modules`: the installed-module topology (read-only; any authenticated Subject). */
    modules(): Observable<ModulesReport> {
        return this.http.get<ModulesReport>(apiUrl('/modules'));
    }

    algorithms(): Observable<GraphAlgorithmCatalogue> {
        return this.http.get<GraphAlgorithmCatalogue>(apiUrl('/inv/graph/algorithms'));
    }

    /** Fetch the catalogue once into {@link catalogue}; a failure leaves it null (the footer and the Run button degrade). */
    loadCatalogue(): void {
        if (this.catalogue() || this.loadingCatalogue) return; // the footer and a Run control both ask, at once
        this.loadingCatalogue = true;
        this.algorithms().subscribe({
            next: (c) => this.catalogue.set(c),
            error: () => (this.loadingCatalogue = false),
            complete: () => (this.loadingCatalogue = false),
        });
    }

    /** `200` when the run is already terminal (over budget at submit, a cache hit, a fast run), `202` otherwise. */
    start(req: GraphRunRequest): Observable<GraphRunView> {
        return this.http.post<GraphRunView>(apiUrl('/inv/graph/runs'), req);
    }

    get(runId: string): Observable<GraphRunView> {
        return this.http.get<GraphRunView>(apiUrl(`/inv/graph/runs/${encodeURIComponent(runId)}`));
    }

    list(investigationId?: string): Observable<{ runs: GraphRunView[]; total: number }> {
        return this.http.get<{ runs: GraphRunView[]; total: number }>(apiUrl('/inv/graph/runs'), {
            params: toParams({ investigationId }),
        });
    }

    cancel(runId: string): Observable<{ runId: string; status: GraphRunStatus; cancelRequested: boolean }> {
        return this.http.post<{ runId: string; status: GraphRunStatus; cancelRequested: boolean }>(
            apiUrl(`/inv/graph/runs/${encodeURIComponent(runId)}/cancel`),
            {},
        );
    }

    /**
     * Re-read a run every {@link pollMs} until it is terminal, emitting each view (the terminal one last, then complete).
     * Unsubscribing stops the polling; there is no timer left behind.
     */
    watch(runId: string): Observable<GraphRunView> {
        return timer(this.pollMs, this.pollMs).pipe(
            exhaustMap(() => this.get(runId)), // a slow read is not abandoned by the next tick
            takeWhile((v) => !isTerminalGraphRun(v.status), true),
        );
    }

    /** Start a run and follow it: the start's answer first, then (if not terminal) every poll up to the terminal view. */
    run(req: GraphRunRequest): Observable<GraphRunView> {
        return this.start(req).pipe(
            switchMap((first) =>
                concat(of(first), isTerminalGraphRun(first.status) ? EMPTY : defer(() => this.watch(first.runId))),
            ),
        );
    }
}
