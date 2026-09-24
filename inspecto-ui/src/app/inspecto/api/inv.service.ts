import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import type { ConditionGroup } from '../query/query-types';
import type { BranchStage } from '../graph/branching-pattern-engine';

/** One aggregated projection triple: a distinct (source, target[, kind]) pair with its folded row count. */
export interface ProjectionTriple {
    source: string;
    target: string;
    /** The link kind from `linkKindCol`, or null when the projection is untyped. */
    kind: string | null;
    count: number;
    /** Values of `attrCols`, keyed by column name — present only when the request sent `attrCols`. */
    attrs?: Record<string, string | null>;
}

export interface ProjectionResult {
    /** Heaviest first — the server orders by count so a node cap keeps the densest subgraph. */
    rows: ProjectionTriple[];
    /** True when the server row limit cut the projection short. */
    truncated: boolean;
}

/** The wire shape of an entity-projection request (mirrors the studio's `EntityProjection` mapping). */
export interface ProjectionRequest {
    dataset: string;
    sourceCol: string;
    targetCol: string;
    linkKindCol?: string;
    /** Extra columns to carry as per-edge attributes; differing values split a folded pair into separate rows. */
    attrCols?: string[];
    limit?: number;
    /** Plan S1.4 — a structured predicate applied pre-fold. Ignored by the backend until the field lands. */
    filter?: ConditionGroup;
}

/** {@code POST /inv/projection/neighbors} request — a mapping plus the entity value to expand. */
export interface NeighborsRequest extends ProjectionRequest {
    /** The entity's raw projected value — rows where it's either endpoint. */
    value: string;
}

/** LA-08: one node mapping of {@code POST /inv/projection/multi} — distinct `idColumn` values become Entities. */
export interface MultiNodeMapping {
    dataset: string;
    idColumn: string;
    labelColumn?: string;
    /** A constant category stamped on every node this mapping yields. */
    category?: string;
    attributes?: string[];
}

/** LA-08: one edge mapping — the folded `(sourceColumn, targetColumn)` pairs of one Dataset. */
export interface MultiEdgeMapping {
    dataset: string;
    sourceColumn: string;
    targetColumn: string;
    /** A constant link type for every edge this mapping yields (there is no per-row kind column here). */
    type?: string;
    attributes?: string[];
    /** Narrows ONLY this mapping; the request's top-level `filter` applies to every edge mapping. */
    filter?: ConditionGroup;
}

export interface MultiProjectionRequest {
    nodes?: MultiNodeMapping[];
    edges?: MultiEdgeMapping[];
    /** Applies to every edge mapping — each one must have every column it names, or the call is a 422. */
    filter?: ConditionGroup;
    /** Per MAPPING, not per call. */
    limit?: number;
}

/** A node row as the server returns it — values RAW (D-S4: the SPA normalises). */
export interface MultiProjectionNode {
    id: string;
    label: string | null;
    category: string | null;
    attrs?: Record<string, string | null>;
    __provenance_dataset: string;
}

export interface MultiProjectionEdge extends ProjectionTriple {
    __provenance_dataset: string;
}

/** One mapping's share of the union — how many rows it contributed and whether its own `limit` cut it. */
export interface MultiProjectionMappingSummary {
    dataset: string;
    role: 'node' | 'edge';
    rows: number;
    truncated: boolean;
}

export interface MultiProjectionResult {
    nodes: MultiProjectionNode[];
    edges: MultiProjectionEdge[];
    mappings: MultiProjectionMappingSummary[];
    /** True when ANY mapping hit the limit. */
    truncated: boolean;
}

/** LA-11: {@code POST /inv/traversal/recursive-paths} — the simple paths from `startNode`, walked server-side. */
export interface RecursivePathsRequest {
    dataset: string;
    sourceCol: string;
    targetCol: string;
    /** The RAW value as it appears in the Dataset (the server compares `CAST(col AS VARCHAR)` exactly). */
    startNode: string;
    targetNode?: string;
    /** Clamped server-side to 1..10 (default 6). */
    maxDepth?: number;
    maxEdgeYield?: number;
    limit?: number;
    direction?: 'DIRECTED' | 'UNDIRECTED';
    weightCol?: string;
    temporalConstraint?: { timestampCol: string; monotonic?: boolean; maxTotalDurationHours?: number };
    filter?: ConditionGroup;
}

export interface RecursivePath {
    /** Raw values, start first. */
    nodes: string[];
    hops: number;
    /** Summed `weightCol`, or null when the request named none. */
    weight: number | null;
}

export interface RecursivePathsResult {
    paths: RecursivePath[];
    /** The path limit OR the edge-yield fence cut the answer short. */
    truncated: boolean;
    /** A recursion level reached `maxEdgeYield` — deeper paths may exist that were never walked. */
    edgeYieldCapped: boolean;
    /** The fences actually applied, after server-side clamping. */
    fences: { maxDepth: number; maxEdgeYield: number; timeoutMs: number };
}

/**
 * LA-14b: {@code POST /inv/pattern/branching} — a branching motif run over the WHOLE Dataset, for when the projected
 * graph is truncated and the browser matcher could only see part of it. `stages` is the browser's `BranchStage[]`.
 */
export interface BranchingPatternRequest {
    dataset: string;
    sourceCol: string;
    targetCol: string;
    linkKindCol?: string;
    /** The time column — required by a motif with a window or ordering, exactly as in the browser. */
    timeCol?: string;
    stages: BranchStage[];
    filter?: ConditionGroup;
    limit?: number;
}

/** The browser matcher's `BranchingResult` shape, with RAW node values and the matched legs spelled out. */
export interface BranchingPatternResult {
    matches: { nodeIds: string[]; edgeIds: string[]; layers: string[][] }[];
    edges: { id: string; source: string; target: string; kind: string; attrs: Record<string, string | null> }[];
    truncated: boolean;
    /** More than the server's leg fence were eligible — part of the Dataset was not searched. */
    legCapped: boolean;
    /** Why the motif could not be evaluated — the browser matcher's wording. */
    refusal?: string;
    fences: { maxLegs: number; workBudget: number; timeoutMs: number };
}

// ── LA-10: the Investigation object (`InvestigationRoutes`) ──────────────────────────────────────────────

/** The seven ops the backend evaluates (`InvestigationRoutes.SHIPPED`). `seedBy`, `excludeBy`, `threshold` and
 *  `snapshot` are in the closed vocabulary but answer 422 "not implemented yet" — never offer them. */
export type InvestigationOpName = 'seed' | 'expand' | 'exclude' | 'hide' | 'keep' | 'window' | 'annotate';

/** A rung's traversal direction (plan §2.4; `InvestigationRoutes.DIRECTIONS`). */
export type ExpandDirection = 'either' | 'out' | 'in' | 'reciprocal';

/** A day-of-week in a window's mask (`InvestigationTime.DAYS`). */
export type WindowDay = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN';

/**
 * A time window (LA-13, `InvestigationTime.window`): an absolute `[from, to)` range (ISO instants) plus an intraday
 * `slot` `[start, end)` (`HH:mm`, may cross midnight) and a `days` mask. `timezone` is REQUIRED whenever a slot or
 * mask is given — they are wall-clock notions.
 */
export interface InvestigationWindow {
    from?: string | null;
    to?: string | null;
    slot?: { start: string; end: string } | null;
    days?: WindowDay[] | null;
    timezone?: string | null;
}

/**
 * One hop-ladder rung (plan §2.4, `InvestigationRoutes` expand params). `budget` caps the rows read (a breach sets
 * `truncated`) — it was `limit` before LA-13, and `limit` is now REFUSED with 422. `window` defaults to `'inherit'`
 * (the Investigation's current window); `'full'` reads all time.
 */
export interface ExpandRung {
    budget?: number;
    direction?: ExpandDirection;
    linkKinds?: string[] | null;
    window?: 'inherit' | 'full' | InvestigationWindow;
    minEvents?: number;
    minDistinctDays?: number | null;
    candidateDegreeMin?: number | null;
    candidateDegreeMax?: number | null;
    maxFanOut?: number | null;
}

/** `POST /inv/investigations` — bound to one Dataset + the projection's columns. */
export interface InvestigationCreateRequest {
    id?: string;
    title?: string;
    dataset: string;
    sourceCol: string;
    targetCol: string;
    linkKindCol?: string;
}

/** A fork's parent (D-E4): the Investigation it was re-ordered from, the order, and the parent's log length. */
export interface InvestigationLineage {
    id: string;
    order: number[];
    parentSteps: number;
}

export interface InvestigationHeader {
    id: string;
    title: string | null;
    owner: string | null;
    dataset: string;
    sourceCol: string;
    targetCol: string;
    linkKindCol: string | null;
    createdAt: string;
    /** Always null (D-E3): no version-addressable read exists, so reads are sealed at use, not pinned. */
    datasetVersion: null;
    parent: InvestigationLineage | null;
}

/** One op's body. Ids are RAW Dataset values — the server compares `CAST(col AS VARCHAR)` exactly. */
export type InvestigationOpRequest =
    | { op: 'seed'; ids: string[]; entityType?: string }
    | ({ op: 'expand'; ids?: string[] } & ExpandRung)
    | { op: 'window'; window: InvestigationWindow | 'full' }
    | { op: 'exclude'; ids: string[]; reason: string }
    | { op: 'hide' | 'keep'; ids: string[] }
    /** LA-19: `note` ≤ 2000 chars; every id must be in the Working Set. ⛔ Never send `confidence` — its scale is
     *  undecided (D-U9) and the server refuses it with 422. */
    | { op: 'annotate'; ids: string[]; note: string };

/** What one step changed. Link changes are COUNTS only — the links themselves come from `/replay`. */
export interface WorkingSetDelta {
    admitted: string[];
    removed: string[];
    linksAdded: number;
    linksRemoved: number;
    hidden: string[];
    kept: string[];
    excluded: string[];
}

/** The Working Set as COUNTS (what `/ops`, `/undo` and `/reorder` answer). */
export interface WorkingSetSummary {
    entities: number;
    links: number;
    excluded: number;
    hash: string;
}

export interface InvestigationStepResult {
    step: number;
    op: InvestigationOpName | 'undo';
    undoes?: number;
    delta: WorkingSetDelta;
    /** True when this step's expand hit its row limit — the Working Set is then incomplete. */
    truncated: boolean;
    /** On `exclude`: the ids a prior `keep` protected, so they were NOT excluded. */
    protected?: string[];
    read?: { rowCount: number; fingerprint: string; readAt: string };
    workingSet: WorkingSetSummary;
}

export interface InvestigationForkRequest {
    /** A permutation of the parent's EFFECTIVE op steps (undo entries and undone ops excluded). */
    order: number[];
    id?: string;
    title?: string;
}

export interface InvestigationForkResult {
    id: string;
    parent: InvestigationLineage;
    steps: number;
    workingSet: WorkingSetSummary;
}

export interface WorkingSetEntity {
    /** The RAW value, as the Dataset holds it. */
    id: string;
    type: string | null;
    hop: number;
    seed: string;
    admittedBy: number;
    hidden: boolean;
    kept: boolean;
}

export interface WorkingSetLink {
    source: string;
    target: string;
    kind: string | null;
    count: number;
    admittedBy: number;
}

export interface WorkingSetExclusion {
    id: string;
    step: number;
    reason: string;
}

/** LA-19: one note on one entity, from the `annotate` step that wrote it. A later exclusion keeps it. */
export interface WorkingSetAnnotation {
    id: string;
    step: number;
    note: string;
}

/** The full Working Set — only `/replay` returns it. */
export interface WorkingSet {
    entities: WorkingSetEntity[];
    links: WorkingSetLink[];
    excluded: WorkingSetExclusion[];
    /** LA-19: ABSENT when no entity is annotated (an unannotated state hashes as it did before). */
    annotations?: WorkingSetAnnotation[];
    hash: string;
}

export interface InvestigationReplayRequest {
    at?: number;
    /** Re-run every effective expand's recorded query against current data and report drift. */
    reread?: boolean;
}

export interface ReplayDriftRow {
    step: number;
    dataset: string;
    sealedAt: string;
    sealedFingerprint: string;
    currentFingerprint: string;
    sealedRows: number;
    currentRows: number;
    diverged: boolean;
}

export interface InvestigationReplayResult {
    id: string;
    at: number;
    workingSet: WorkingSet;
    /** Full re-evaluation agrees with every hash recorded at append time. */
    equivalent: boolean;
    mismatches: number[];
    reread: boolean;
    drift: ReplayDriftRow[];
    diverged: boolean;
}

export interface InvestigationLogEntry {
    step: number;
    kind: 'op' | 'undo';
    author: string | null;
    at: string;
    op?: InvestigationOpName;
    /** The op's validated params: an expand's rung arrives resolved (defaults filled); a `window` op has no ids. */
    params?: { ids?: string[]; entityType?: string | null; reason?: string; note?: string } & Omit<
        ExpandRung,
        'window'
    > & {
            window?: 'inherit' | 'full' | InvestigationWindow | null;
        };
    undoes?: number;
    undoneBy: number | null;
    read?: { dataset: string; readAt: string; rowCount: number; truncated: boolean; fingerprint: string };
    derivedFrom?: { investigation: string; step: number };
    workingSetHash: string;
    /** The server's plain-language line for this step. */
    text: string;
}

export interface InvestigationLog {
    header: InvestigationHeader;
    entries: InvestigationLogEntry[];
    /** The TRUE number of log entries; `entries` may be capped (`truncated`). */
    total: number;
    truncated: boolean;
}

/** LA-20's three relations of a Working Set. */
export type WorkingSetRelationName = 'entities' | 'links' | 'excluded';

/** `GET /inv/investigations/{id}/working-set` — one relation page, with the head it is the relation OF. */
export interface WorkingSetRelation {
    id: string;
    relation: WorkingSetRelationName;
    columns: string[];
    rows: Record<string, unknown>[];
    /** The TRUE row count; `rows` is a bounded page (`truncated`). */
    total: number;
    offset: number;
    limit: number;
    truncated: boolean;
    /** With `?at`, the pinned step's head — its hash is what a Frozen Widget checks its pin against. */
    head: { step: number; workingSetHash: string };
    key: string;
    cached: boolean;
}

export interface WorkingSetRelationQuery {
    of?: WorkingSetRelationName;
    /** LA-21: the relation at a past step (a Frozen Widget's pin); omitted = the current head. */
    at?: number;
    limit?: number;
    offset?: number;
}

// ── LA-19: the coverage indicator (`InvestigationCoverageRoutes`) ──────────────────────────────────────

/** `GET /inv/investigations/{id}/coverage` — which local days of a bounded window have NO rows in the Dataset. */
export interface InvestigationCoverage {
    id: string;
    dataset: string;
    window: InvestigationWindow;
    /** The zone the days are counted in (the window's `timezone`, else UTC). */
    zone: string;
    expectedDays: number;
    coveredDays: number;
    /** `YYYY-MM-DD` local dates with zero rows — a day the window's day mask excludes is never listed. */
    missingDays: string[];
    complete: boolean;
    perDay: { date: string; rows: number }[];
    readAt: string;
    /** Per-Collector coverage is NOT assessed: a Dataset row carries no Collector attribution. */
    collectors: { assessed: false; note: string };
}

// ── LA-12: the Dossier (`DossierRoutes`) ──────────────────────────────────────────────────────────────

export interface DossierQuery {
    /** The step the dossier covers up to; omitted = the head. */
    at?: number;
    /** Sealed snapshot ids anchored to this Investigation, to include their score vectors. */
    snapshots?: string[];
}

/** The dossier manifest — what a reader keeps, and what `…/dossier/verify` checks against the store. */
export interface DossierManifest {
    algorithm: string;
    investigation: string;
    at: number;
    snapshots: string[];
    artefacts: { path: string; bytes: number; sha256: string }[];
    content: Record<string, string>;
    root: string;
}

export interface DossierLedgerRow {
    step: number;
    at: string;
    author: string | null;
    kind: 'op' | 'undo';
    op: string;
    text: string;
    undoneBy: number | null;
    entitiesAfter: number;
    workingSetHash: string;
    truncated?: boolean;
    readFingerprint?: string;
}

/** `GET …/dossier` (format json) — the whole dossier, built from the sealed log only (`GraphDossierBuilder`). */
export interface Dossier {
    id: string;
    generatedAt: string;
    summary: {
        investigation: string;
        title: string | null;
        owner: string | null;
        dataset: string;
        at: number;
        steps: number;
        entities: number;
        links: number;
        excluded: number;
        hidden: number;
        kept: number;
        snapshots: string[];
    };
    topology: { entities: number; links: number; degreeTotal: number } & Record<string, unknown>;
    scores: { computedBy: string; tables: { metric: string; snapshot: string; total: number }[]; note?: string };
    ledger: DossierLedgerRow[];
    negativeSpace: unknown;
    integrity: { intact: boolean; stepsChecked: number; failures: unknown[] };
    manifest: DossierManifest;
    renderings: { json: unknown; steps: string[]; method: string };
}

/** `POST …/dossier/verify` — the manifest rebuilt from the store NOW, compared with the one submitted. */
export interface DossierVerifyResult {
    id: string;
    verified: boolean;
    /** The submitted manifest's root matches its own body — false means the manifest itself was edited. */
    selfConsistent: boolean;
    /** The store's own recorded hashes still agree. */
    intact: boolean;
    submittedRoot: string;
    currentRoot: string;
    changed: string[];
    missing: string[];
    added: string[];
    contentChanged: string[];
}

// ── LA-23: Investigation Template, Measures, Alert Rules ──────────────────────────────────────────────

export interface InvestigationTemplate {
    id: string;
    title: string | null;
    owner: string | null;
    createdAt: string;
    derivedFrom: { investigation: string; steps: number; workingSetHash: string };
    roles: {
        dataset: string;
        sourceCol: string;
        targetCol: string;
        linkKindCol: string | null;
        timeCol: string | null;
        timeColZone: string | null;
    };
    /** Seed steps → `seed1`, `seed2`, … (ids NOT stored); window steps → `window1`, … with the authored default. */
    parameters: InvestigationTemplateParameter[];
    ops: Record<string, unknown>[];
    /** Exclude/hide/keep steps left out (D-E8) — counts only, never ids or reasons. */
    dropped: { step: number; op: string; count: number }[];
    /** Expands that named a frontier and became an expand of the whole Working Set. */
    generalised: { step: number; namedFrontier: number; exact: boolean }[];
}

/** A template parameter (LA-13): `kind` says which. A window parameter is optional at instantiation. */
export type InvestigationTemplateParameter =
    | { name: string; kind: 'seed'; entityType: string | null; step: number }
    | { name: string; kind: 'window'; default: InvestigationWindow | null; step: number };

export interface InstantiateTemplateRequest {
    id?: string;
    title?: string;
    /** Seed parameter → non-empty id list; window parameter (optional) → a window or `'full'`. */
    params: Record<string, string[] | InvestigationWindow | 'full'>;
    dataset?: string;
    sourceCol?: string;
    targetCol?: string;
    linkKindCol?: string;
    timeCol?: string;
    timeColZone?: string;
}

export interface InstantiateTemplateResult {
    id: string;
    header: InvestigationHeader;
    steps: number;
    workingSet: WorkingSetSummary;
}

export interface InvestigationMeasure {
    name: string;
    relation: WorkingSetRelationName;
    measure: string;
    value: number | null;
}

export interface InvestigationMeasures {
    id: string;
    head: { step: number; workingSetHash: string };
    measures: InvestigationMeasure[];
    byKind: { kind: string | null; links: number; events: number }[];
    key: string;
    cached: boolean;
}

export type AlertComparator = 'gt' | 'gte' | 'lt' | 'lte';
export type AlertSeverity = 'INFO' | 'WARNING' | 'CRITICAL';

export interface InvestigationAlertRuleRequest {
    name: string;
    relation: WorkingSetRelationName;
    measure: string;
    comparator: AlertComparator;
    threshold: number;
    severity: AlertSeverity;
}

export interface InvestigationAlertRuleResult {
    rule: Record<string, unknown>;
    current: number | null;
    wouldFire: boolean;
    /** What a fired Alert discloses, and to whom — the backend's words, shown verbatim. */
    disclosure: string;
}

/**
 * Investigation-studio backend (INV-1): the real DuckDB-side Entity Projection over a Dataset —
 * the server half of the Link Analysis studio's `entity-projection` GraphSource. Offline/mock mode
 * answers 501 (see `mock/handlers/inv.handler.ts`) and the studio falls back to its client-side
 * sample fold, so the demo path is unchanged.
 */
@Injectable({ providedIn: 'root' })
export class InvService {
    private http = inject(HttpClient);

    project(req: ProjectionRequest): Observable<ProjectionResult> {
        return this.http.post<ProjectionResult>(apiUrl('/inv/projection'), req);
    }

    /** Phase E incremental expand: the one-hop neighborhood of `req.value` within the mapping. */
    neighbors(req: NeighborsRequest): Observable<ProjectionResult> {
        return this.http.post<ProjectionResult>(apiUrl('/inv/projection/neighbors'), req);
    }

    /** LA-08: node + edge mappings over several Datasets in one call. One unviewable Dataset ⇒ the whole call 404s. */
    projectMulti(req: MultiProjectionRequest): Observable<MultiProjectionResult> {
        return this.http.post<MultiProjectionResult>(apiUrl('/inv/projection/multi'), req);
    }

    /** LA-11: multi-hop paths from a start value, fenced server-side (depth, edge yield, timeout). */
    recursivePaths(req: RecursivePathsRequest): Observable<RecursivePathsResult> {
        return this.http.post<RecursivePathsResult>(apiUrl('/inv/traversal/recursive-paths'), req);
    }

    /** LA-14b: a branching motif over the whole Dataset — bounded server-side (legs, work, timeout, match limit). */
    branchingPattern(req: BranchingPatternRequest): Observable<BranchingPatternResult> {
        return this.http.post<BranchingPatternResult>(apiUrl('/inv/pattern/branching'), req);
    }

    // ── LA-10 Investigation. There is NO list or get-one route: the caller remembers the ids it created. ──

    createInvestigation(req: InvestigationCreateRequest): Observable<InvestigationHeader> {
        return this.http.post<InvestigationHeader>(apiUrl('/inv/investigations'), req);
    }

    appendInvestigationOp(id: string, op: InvestigationOpRequest): Observable<InvestigationStepResult> {
        return this.http.post<InvestigationStepResult>(invPath(id, 'ops'), op);
    }

    /** Reverts the latest effective op (a recorded log edit). 409 when there is nothing to undo. */
    undoInvestigation(id: string): Observable<InvestigationStepResult> {
        return this.http.post<InvestigationStepResult>(invPath(id, 'undo'), {});
    }

    /** D-E4: re-ordering FORKS — the answer is a NEW Investigation; the original is untouched. */
    reorderInvestigation(id: string, req: InvestigationForkRequest): Observable<InvestigationForkResult> {
        return this.http.post<InvestigationForkResult>(invPath(id, 'reorder'), req);
    }

    /** The only route that answers the FULL Working Set (entities, links, exclusions). Persists nothing. */
    replayInvestigation(id: string, req: InvestigationReplayRequest = {}): Observable<InvestigationReplayResult> {
        return this.http.post<InvestigationReplayResult>(invPath(id, 'replay'), req);
    }

    investigationLog(id: string, limit?: number): Observable<InvestigationLog> {
        return this.http.get<InvestigationLog>(invPath(id, 'log'), { params: toParams({ limit }) });
    }

    /** LA-20/21: the Working Set as a relation. Owner-only (a 404 for anyone else — indistinguishable from absence). */
    workingSetRelation(id: string, q: WorkingSetRelationQuery = {}): Observable<WorkingSetRelation> {
        return this.http.get<WorkingSetRelation>(invPath(id, 'working-set'), {
            params: toParams({ of: q.of, at: q.at, limit: q.limit, offset: q.offset }),
        });
    }

    /** LA-19: coverage over `from`/`to` (+ `timezone`), or — with none — over the Investigation's own window. */
    investigationCoverage(
        id: string,
        w: { from?: string; to?: string; timezone?: string } = {},
    ): Observable<InvestigationCoverage> {
        return this.http.get<InvestigationCoverage>(invPath(id, 'coverage'), {
            params: toParams({ from: w.from, to: w.to, timezone: w.timezone }),
        });
    }

    /** LA-12: the dossier as JSON (all three renderings included). Owner-only; audited server-side. */
    dossier(id: string, q: DossierQuery = {}): Observable<Dossier> {
        return this.http.get<Dossier>(invPath(id, 'dossier'), { params: dossierParams(q, 'json') });
    }

    /** LA-12: one rendering as a file — a Blob through HttpClient, so the bearer travels (never a bare href). */
    dossierRendering(id: string, format: 'steps' | 'method', q: DossierQuery = {}): Observable<Blob> {
        return this.http.get(invPath(id, 'dossier'), { params: dossierParams(q, format), responseType: 'blob' });
    }

    /** LA-12: check a held manifest against the store as it is NOW. Persists nothing. */
    verifyDossier(id: string, manifest: unknown): Observable<DossierVerifyResult> {
        return this.http.post<DossierVerifyResult>(invPath(id, 'dossier/verify'), { manifest });
    }

    /** LA-23: save the effective log as a write-once template. The answer lists what was dropped/generalised. */
    saveInvestigationTemplate(
        id: string,
        body: { id?: string; title?: string } = {},
    ): Observable<InvestigationTemplate> {
        return this.http.post<InvestigationTemplate>(invPath(id, 'template'), body);
    }

    investigationTemplate(templateId: string): Observable<InvestigationTemplate> {
        return this.http.get<InvestigationTemplate>(templatePath(templateId, ''));
    }

    /** LA-23: a NEW Investigation from a template; every expand reads (and seals) the Dataset now. */
    instantiateTemplate(templateId: string, req: InstantiateTemplateRequest): Observable<InstantiateTemplateResult> {
        return this.http.post<InstantiateTemplateResult>(templatePath(templateId, '/instantiate'), req);
    }

    /** LA-23: the declared Measures over the Working Set — what an Alert Rule bound to one would compute. */
    investigationMeasures(id: string): Observable<InvestigationMeasures> {
        return this.http.get<InvestigationMeasures>(invPath(id, 'measures'));
    }

    /** LA-23: bind an Alert Rule to a Measure. 503 when the alert engine is absent. */
    bindInvestigationAlertRule(
        id: string,
        req: InvestigationAlertRuleRequest,
    ): Observable<InvestigationAlertRuleResult> {
        return this.http.post<InvestigationAlertRuleResult>(invPath(id, 'alert-rules'), req);
    }
}

function dossierParams(q: DossierQuery, format: string) {
    return toParams({ at: q.at, snapshots: q.snapshots?.length ? q.snapshots.join(',') : undefined, format });
}

function templatePath(id: string, suffix: string): string {
    return apiUrl(`/inv/investigation-templates/${encodeURIComponent(id)}${suffix}`);
}

function invPath(id: string, action: string): string {
    return apiUrl(`/inv/investigations/${encodeURIComponent(id)}/${action}`);
}
