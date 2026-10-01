import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { EMPTY, Observable, concat, defer, exhaustMap, of, switchMap, takeWhile, timer } from 'rxjs';
import { apiErrorMessage, apiUrl, toParams } from './api-base';

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
    | 'GRAPH';

export interface GraphScoreView {
    id: string;
    label: string;
    score: number;
}

export interface GraphSuspicionView extends GraphScoreView {
    factors: { degree: number; betweenness: number; pageRank: number; core: number; triangles: number };
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
    scores?: GraphScoreView[] | GraphSuspicionView[];
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
    /** Absent on the list; `at` is the log step the run read. */
    input?: { nodes: number; edges: number; hiddenEntities: number; droppedDangling: number; at: number };
    /** ONLY on a COMPLETED run - a BUDGET_EXCEEDED / CANCELLED / FAILED run has no such key. */
    result?: GraphRunResult;
    masking?: Record<string, unknown>;
}

export interface GraphRunRequest {
    investigationId: string;
    /** Log step to read; absent = the committed head (what the canvas shows). */
    at?: number;
    algorithm: string;
    params?: Record<string, unknown>;
    weights?: 'count' | 'none';
    kinds?: string[];
    budget?: Partial<GraphBudgetView>;
}

export interface GraphAlgorithmParam {
    name: string;
    type: string;
    default: unknown;
    min?: number;
    max?: number;
    allowed?: string[];
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
 * The sentence an analyst reads for a failed graph-run call. Each status means something specific on these routes:
 * 403 the capability (or, on cancel, somebody else's run), 404 an Investigation/run the caller cannot see, 409 a run
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
