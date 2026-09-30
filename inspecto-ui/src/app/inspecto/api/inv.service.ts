import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParameterCodec, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import type { ConditionGroup } from '../query/query-types';
import type { BranchStage } from '../graph/branching-pattern-engine';
import type { EntityTypeRef } from '../graph/entity-key';

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
    /** LA-17 D-M6: column → the Entity Type typing it; an untyped column is absent. */
    columnTypes?: Record<string, EntityTypeRef>;
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
    /** LA-17 D-M6: the Entity Type of the mapping's `idColumn`, when typed. */
    entityType?: EntityTypeRef;
}

export interface MultiProjectionEdge extends ProjectionTriple {
    __provenance_dataset: string;
    /** LA-17 D-M6: the Entity Types of the mapping's source / target columns, when typed. */
    sourceType?: EntityTypeRef;
    targetType?: EntityTypeRef;
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

/** The ops the backend evaluates (`InvestigationRoutes.SHIPPED`), plus LA-17's list-bound `excludeBy` / `seedBy`
 *  (entity-model design §4.4.1). `threshold` and `snapshot` are in the closed vocabulary but answer 422 "not
 *  implemented yet" — never offer them. */
export type InvestigationOpName =
    | 'seed'
    | 'expand'
    | 'exclude'
    | 'hide'
    | 'keep'
    | 'window'
    | 'annotate'
    | 'excludeBy'
    | 'seedBy';

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
    /** The stated purpose / legal basis (D-U5) — required; recorded in the sealed header and the Dossier, not enforced. */
    purpose: string;
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
    /** The stated purpose / legal basis (D-U5); absent on an Investigation created before it was required. */
    purpose?: string | null;
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
    | { op: 'annotate'; ids: string[]; note: string }
    /** LA-17 (§4.4.1): resolved at the Entity List's HEAD and sealed in the log — replay never re-reads the list.
     *  Refused at append: unknown list 404 · retired list / its Entity Type not in force 409 · over 5 000 members 422. */
    | { op: 'excludeBy'; listId: string; reason: string }
    | { op: 'seedBy'; listId: string };

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
    /** On `exclude` / `excludeBy`: the ids a prior `keep` protected, so they were NOT excluded. */
    protected?: string[];
    read?: { rowCount: number; fingerprint: string; readAt: string };
    workingSet: WorkingSetSummary;
    /** LA-17 `excludeBy` / `seedBy`: what the step resolved and did (§4.4.1). */
    list?: EntityListStepReport;
}

/**
 * What a list-bound op's step reports (§4.4.1; field names read off the in-flight `InvestigationRoutes.listResult`,
 * which §4.4.1 does not name). Every field is optional — the SPA degrades to the log's own text line.
 */
export interface EntityListStepReport {
    listId: string;
    /** The fact-log position the list was resolved at — replay is pinned to it. */
    atSeq?: number;
    headHash?: string | null;
    entityType?: string;
    purpose?: EntityListPurpose;
    /** How many members were sealed (a COUNT — the members themselves are never echoed for display). */
    members?: number;
    /** `excludeBy`: how many entities the step removed. */
    removed?: number;
    /** `seedBy`: how many raw ids the step seeded. */
    seeded?: number;
    /** Members that matched no entity (`excludeBy`) or no value of the bound columns (`seedBy`) — reported, not an
     *  error. The backend may answer the keys or a count; the SPA only ever COUNTS them (see `unmatchedCount`). */
    unmatched?: number | string[];
}

/** How many members a list op left unmatched, whichever shape the server answered. */
export function unmatchedCount(r: EntityListStepReport | null | undefined): number {
    const u = r?.unmatched;
    return Array.isArray(u) ? u.length : (u ?? 0);
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
    /** The Admiralty grade (D-U9): source reliability A–F then information credibility 1–6, e.g. `B2`. Absent when ungraded. */
    confidence?: string;
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
    params?: { ids?: string[]; entityType?: string | null; reason?: string; note?: string; listId?: string } & Omit<
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

// ── LA-24: the optional Case link (`InvestigationCaseRoutes`) ─────────────────────────────────────────

/** `GET|PUT|DELETE /inv/investigations/{id}/case` — the link, and what it grants the caller. */
export interface InvestigationCaseLink {
    investigationId: string;
    caseRef: string | null;
    linkedBy: string | null;
    linkedAt: string | null;
    /** `case-member` = reading someone else's Investigation as a member of its linked Case. */
    access: 'owner' | 'case-member';
    readOnly: boolean;
    /** Whether the link grants the Case's members anything right now (false without Case management). */
    sharing: boolean;
    reason: string;
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

/** `GET /inv/snapshots` — sealed snapshot ids, newest first; `total` is the TRUE count, so a bounded page says so. */
export interface SealedSnapshotIds {
    ids: string[];
    total: number;
    truncated: boolean;
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
    /** Required as on create (D-U5): instantiating mints an Investigation. */
    purpose: string;
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

// ── LA-18: value Measures over the WHOLE Dataset (`ValueMeasureRoutes`, `ValueMeasures`) ─────────────────

/** The six a value-measure Alert Rule may watch; `valueWeightedLinks` is read-only (the GET answers it, a bind refuses it). */
export type AlertableValueMeasureName =
    | 'passThrough'
    | 'velocity'
    | 'timeToCashOut'
    | 'cashOutConcentration'
    | 'structuring'
    | 'benefitTransfer';
export type ValueMeasureName = AlertableValueMeasureName | 'valueWeightedLinks';

/**
 * The Measure block: its name, the value/time columns, the `[from, to)` window, and any thresholds / kind list
 * (`cashOutKinds` | `benefitKinds`). An omitted threshold is the server's default. ⛔ There is no `filter` — the
 * Measure reads the Dataset, never an analyst's view (the §2.6 ≥ 5 000 trap); the server refuses one.
 */
export interface ValueMeasureBlock {
    name: ValueMeasureName;
    valueCol: string;
    timeCol: string;
    from?: string;
    to?: string;
    /** Parallel backend lane (not yet shipped): a rolling window `<N>h` | `<N>d` in place of `from`/`to`. */
    last?: string;
    /** Parallel backend lane (not yet shipped): restrict the Measure to the members of an Entity List. */
    agentList?: string;
    [threshold: string]: string | number | string[] | undefined;
}

/** `GET /inv/value-measures` — the block plus the Dataset and its link roles. */
export interface ValueMeasureQuery extends ValueMeasureBlock {
    dataset: string;
    sourceCol: string;
    targetCol: string;
    linkKindCol?: string;
}

export interface ValueMeasureResult {
    /** The block with every default filled in — exactly what an Alert Rule bound with it would store. */
    measure: ValueMeasureBlock;
    /** The thresholds in force, in words. */
    threshold: string;
    entities: Record<string, unknown>[];
    count: number;
    /** The answer was capped at `fences.maxEntities` — more entities breach than are listed. */
    truncated: boolean;
    rowsInWindow: number;
    /** Rows in the window whose value did not parse as a number — skipped, and counted. */
    unvalued: number;
    fences?: { maxEntities: number; timeoutSeconds: number; maxWindowDays: number };
}

/** `POST …/alert-rules` for a value Measure: fires when ≥ 1 entity breaches (comparator/threshold are the server's). */
export interface ValueMeasureAlertRuleRequest {
    name: string;
    valueMeasure: ValueMeasureBlock;
    severity: AlertSeverity;
}

export type ValueMeasureAlertRuleResult = InvestigationAlertRuleResult & Partial<ValueMeasureResult>;

// ── LA-17: Entity Lists (`EntityListRoutes`, wire contract: entity-model design §4.3.1) ─────────────────

export type EntityListPurpose = 'allow' | 'block' | 'watch' | 'exclusion';

/** One list as `GET /inv/entity-lists` lists it. A Space object — reads need only Space access. */
export interface EntityListSummary {
    id: string;
    title: string;
    purpose: EntityListPurpose;
    /** An Entity Type id from `entityTypesInForce` (the Link Analysis settings). */
    entityType: string;
    size: number;
    /** Retired lists are still listed, flagged. */
    retired: boolean;
    createdAt: string;
    createdBy: string | null;
    /** The fact-log sequence of this list's latest change. */
    lastSeq: number;
}

export interface EntityListIndex {
    lists: EntityListSummary[];
    headSeq: number;
    headHash: string | null;
}

/** `GET /inv/entity-lists/{id}` — the list as of `atSeq`. `members` are normalised keys, sorted, and MASKED per the
 *  Space's `maskingMode` (reveal is not in this slice) — never treat them as raw values to send back. */
export interface EntityListDetail extends EntityListSummary {
    members: string[];
    atSeq: number;
    headHash: string | null;
}

/** `POST /inv/entity-lists` → 201. `id` is minted when absent, else `^[a-z0-9][a-z0-9_-]{0,63}$`; an id ever used
 *  (retired too) is a 409. `reason` must be non-blank. */
export interface EntityListCreateRequest {
    id?: string;
    title: string;
    purpose: EntityListPurpose;
    entityType: string;
    reason: string;
}

/** `POST /inv/entity-lists/{id}/members` — RAW values (the server normalises with the list's Entity Type);
 *  ≤ 5 000 per call; a value in both `add` and `remove` is a 422. */
export interface EntityListMembersRequest {
    add?: string[];
    remove?: string[];
    reason: string;
}

/** The list after a members call. `changed: 0` = nothing was effective and no fact was written. */
export interface EntityListMembersResult extends EntityListDetail {
    changed: number;
}

// ── LA-17 slice 2: analyst identity resolution (`EntityIdentityRoutes`, entity-model design §8.2) ───────

/** One `identity.asserted` fact. `a`/`b` are typed keys `<type>:<value>` as the server rendered them — MASKED
 *  per the Space's `maskingMode` (a `masked:<hex>` token); never unmask or send them back as keys. */
export interface IdentityAssertion {
    seq: number;
    a: string;
    b: string;
    /** `analyst` in this cut (`dataset:<id>@<fingerprint>` is reserved for the import cut). */
    via: string;
    actor: string | null;
    at: string;
    reason: string;
}

/** A resolved group: `id` = the smallest member key; every joining assertion is listed. Keys masked as above. */
export interface IdentityGroup {
    id: string;
    members: string[];
    assertions: IdentityAssertion[];
}

export interface IdentityGroupIndex {
    groups: IdentityGroup[];
    atSeq: number;
    headSeq: number;
    headHash: string;
}

export interface IdentityGroupRead {
    group: IdentityGroup;
    atSeq: number;
    headHash: string;
}

/** `POST /inv/entity-identities` — typed keys; the server normalises each with its type's rule and seals it. */
export interface IdentityAssertRequest {
    a: string;
    b: string;
    reason: string;
}

export interface IdentityAssertResult {
    assertion: IdentityAssertion;
    group: IdentityGroup;
    atSeq: number;
    headHash: string;
}

/** `groups` = the groups of the retracted assertion's two keys afterwards (two when it split). */
export interface IdentityRetractResult {
    retracted: number;
    groups: IdentityGroup[];
    atSeq: number;
    headHash: string;
}

/**
 * Encodes a query value with `encodeURIComponent`. ⚠ Angular's default `HttpUrlEncodingCodec` leaves `+` raw, and
 * the server decodes the raw query ONCE with `URLDecoder`, where a raw `+` is a space — so `msisdn:+4477…` would be
 * looked up as `msisdn: 4477…` and resolve to nothing.
 */
export const STRICT_QUERY_CODEC: HttpParameterCodec = {
    encodeKey: (k) => encodeURIComponent(k),
    encodeValue: (v) => encodeURIComponent(v),
    decodeKey: (k) => decodeURIComponent(k),
    decodeValue: (v) => decodeURIComponent(v),
};

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

    /** LA-24: the Investigation's Case link. The owner, or a member of the linked Case (read-only). */
    investigationCase(id: string): Observable<InvestigationCaseLink> {
        return this.http.get<InvestigationCaseLink>(invPath(id, 'case'));
    }

    /** LA-24: link to a Case the caller can see — owner-only (`canManageIncidents`). */
    linkInvestigationCase(id: string, caseRef: string): Observable<InvestigationCaseLink> {
        return this.http.put<InvestigationCaseLink>(invPath(id, 'case'), { caseRef });
    }

    /** LA-24: remove the Case link — owner-only. */
    unlinkInvestigationCase(id: string): Observable<InvestigationCaseLink> {
        return this.http.delete<InvestigationCaseLink>(invPath(id, 'case'));
    }

    /** LA-12: the dossier as JSON (all three renderings included). Owner-only; audited server-side. */
    dossier(id: string, q: DossierQuery = {}): Observable<Dossier> {
        return this.http.get<Dossier>(invPath(id, 'dossier'), { params: dossierParams(q, 'json') });
    }

    /** LA-12: one rendering as a file — a Blob through HttpClient, so the bearer travels (never a bare href). */
    dossierRendering(id: string, format: 'steps' | 'method', q: DossierQuery = {}): Observable<Blob> {
        return this.http.get(invPath(id, 'dossier'), { params: dossierParams(q, format), responseType: 'blob' });
    }

    /** LA-03: the sealed snapshot ids (not filtered by Investigation — the dossier refuses one not anchored to it). */
    sealedSnapshotIds(limit?: number): Observable<SealedSnapshotIds> {
        return this.http.get<SealedSnapshotIds>(apiUrl('/inv/snapshots'), { params: toParams({ limit }) });
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

    /** LA-18: bind an Alert Rule to a value Measure over the Investigation's whole Dataset (one Alert per rule). */
    bindValueMeasureAlertRule(id: string, req: ValueMeasureAlertRuleRequest): Observable<ValueMeasureAlertRuleResult> {
        return this.http.post<ValueMeasureAlertRuleResult>(invPath(id, 'alert-rules'), req);
    }

    /** LA-18: one value Measure over a whole Dataset. Read-only; 503 no write root · 422 · 404 unknown Dataset. */
    valueMeasures(q: ValueMeasureQuery): Observable<ValueMeasureResult> {
        return this.http.get<ValueMeasureResult>(apiUrl('/inv/value-measures'), { params: toParams({ ...q }) });
    }

    // ── LA-17 Entity Lists. Writes need `canManageIncidents`; no write root → 503; unknown list → 404. ──

    listEntityLists(): Observable<EntityListIndex> {
        return this.http.get<EntityListIndex>(apiUrl('/inv/entity-lists'));
    }

    /** `at` pins the read to a fact-log position: beyond the head → 422; the list did not exist yet → 404. */
    getEntityList(id: string, at?: number): Observable<EntityListDetail> {
        return this.http.get<EntityListDetail>(entityListPath(id, ''), { params: toParams({ at }) });
    }

    createEntityList(req: EntityListCreateRequest): Observable<EntityListDetail> {
        return this.http.post<EntityListDetail>(apiUrl('/inv/entity-lists'), req);
    }

    /** A retired list → 409. */
    changeEntityListMembers(id: string, req: EntityListMembersRequest): Observable<EntityListMembersResult> {
        return this.http.post<EntityListMembersResult>(entityListPath(id, '/members'), req);
    }

    /** Already retired → 409. */
    retireEntityList(id: string, reason: string): Observable<EntityListDetail> {
        return this.http.post<EntityListDetail>(entityListPath(id, '/retire'), { reason });
    }

    // ── LA-17 slice 2 identity resolution. Reads AND writes need `canManageIncidents`; no write root → 503. ──

    /** Every resolved group as of `at` (default: the head). */
    listIdentityGroups(at?: number): Observable<IdentityGroupIndex> {
        return this.http.get<IdentityGroupIndex>(apiUrl('/inv/entity-identities'), { params: toParams({ at }) });
    }

    /** The group holding `key` — EXACT match, so pass the normalised typed key (`typedEntityKey`). A key in no
     *  live assertion resolves to itself. */
    identityGroup(key: string, at?: number): Observable<IdentityGroupRead> {
        let params = new HttpParams({ encoder: STRICT_QUERY_CODEC }).set('key', key);
        if (at !== undefined) params = params.set('at', String(at));
        return this.http.get<IdentityGroupRead>(apiUrl('/inv/entity-identities/group'), { params });
    }

    /** → 201. Self-assertion / untyped key / type not in force → 422. */
    assertIdentity(req: IdentityAssertRequest): Observable<IdentityAssertResult> {
        return this.http.post<IdentityAssertResult>(apiUrl('/inv/entity-identities'), req);
    }

    /** Unknown or already-retracted assertion → 409. */
    retractIdentity(seq: number, reason: string): Observable<IdentityRetractResult> {
        return this.http.post<IdentityRetractResult>(apiUrl(`/inv/entity-identities/${seq}/retract`), { reason });
    }
}

function entityListPath(id: string, suffix: string): string {
    return apiUrl(`/inv/entity-lists/${encodeURIComponent(id)}${suffix}`);
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
