import { HttpErrorResponse } from '@angular/common/http';
import { BranchingMatch } from '@inspecto/link-analysis/graph/branching-pattern-engine';
import {
    G6Edge,
    G6GraphData,
    G6Node,
    EntityIdMapping,
    EntityProjection,
    EntityTypeRef,
    GraphSource,
    GraphSourceQuery,
    normalizeEntityKey,
    endpointId,
    entityIdCandidates,
    resolveEntityId,
    typedOrEntityId,
} from '@inspecto/core/graph';
import { GraphSelection, mergeGraphs } from '@inspecto/link-analysis/graph/graph-analysis';
import {
    InvService,
    MultiProjectionMappingSummary,
    MultiProjectionResult,
    BranchingPatternResult,
    ProjectionTriple,
    RecursivePathsResult,
} from '@inspecto/link-analysis/api/inv.service';
import { apiErrorMessage } from '@inspecto/core/api';
import { firstValueFrom } from 'rxjs';
import { CHART_CATEGORICAL_NEUTRAL } from '@inspecto/core/theme/chart-tokens';
import type { LaDatasets } from '@inspecto/link-analysis/la-host';

/**
 * The P3 **entity-projection** GraphSource (GLOSSARY §11): fold a Dataset's rows into a business
 * Entity/Link graph. Distinct source/target column values become **Entities**; each row becomes a
 * **Link**, deduplicated per (source, target, kind) with a folded `count`.
 *
 * Since INV-1 (2026-07-08) the projection is <b>backend-first</b>: `POST /inv/projection` does the
 * DuckDB-side aggregation over the real Dataset (scaling far beyond browser row-folding) and
 * {@link projectTriples} maps the aggregated triples into the identical G6 shapes. A backend failure
 * **surfaces** — there is no client-side fallback since the offline mock backend was removed
 * (`f1553136`).
 *
 * {@link projectEntities} is that former fallback, **deliberately retained** (MOCK-DEAD-COMPUTE-1,
 * decided 2026-08-31): no production path calls it, but it is the **reference fold** the live
 * {@link projectTriples} is asserted to agree with shape-for-shape, and the vehicle the C5 example
 * graph sources are pinned through. Delete it and both guards go with it.
 */

/** Above this many entities the projection truncates (and says so) rather than melt the canvas. */
/**
 * DEFAULT ceiling on nodes admitted to one projection.
 *
 * ⚠ A **default, not a truth** — measured on one host and one graph shape (plan §1.7). It is the real
 * governor of what an analyst sees, and it is set by LAYOUT cost, not by the algorithms: the default
 * layered layout renders 500 nodes in ~0.9 s but 750 in ~10.7 s. A different host, layout or data shape
 * moves that cliff. ⇒ deployments override it via {@link configureProjectionLimits}; the server
 * publishes the value at `/bootstrap` (`limits.projectionNodeCap`).
 */
export const PROJECTION_NODE_CAP_DEFAULT = 500;

let projectionNodeCap = PROJECTION_NODE_CAP_DEFAULT;

/** The ceiling currently in force. Read it — never cache it. */
export function projectionNodeCapValue(): number {
    return projectionNodeCap;
}

/**
 * Apply a deployment's projection limit. ⛔ Fails closed on nonsense: a cap that is not a finite number
 * ≥ 1 is refused and the previous value stands, because a cap of 0 would yield an empty graph for every
 * query and read as "no data" rather than "misconfigured".
 */
export function configureProjectionLimits(limits: { projectionNodeCap?: number } | null | undefined): void {
    const v = limits?.projectionNodeCap;
    if (typeof v === 'number' && Number.isFinite(v) && v >= 1) projectionNodeCap = Math.floor(v);
}

/** Restore the measured default — for tests, and for a deployment that clears its override. */
export function resetProjectionLimits(): void {
    projectionNodeCap = PROJECTION_NODE_CAP_DEFAULT;
}

/**
 * An `entity-projection` query with its mappings replaced by the typed ones the answer resolved (D-M6), so every
 * later mint off the remembered run (Working Set, traversal, advanced search) agrees with the drawn ids.
 */
export function resolveRunQuery(q: GraphSourceQuery, g: ProjectedGraph): GraphSourceQuery {
    const typed = g.idMappings as EntityProjection[] | undefined;
    if (!typed?.length) return q;
    if (q.projections?.length === typed.length) return { ...q, projections: typed };
    if (q.projection && !q.projections?.length) return { ...q, projection: typed[0] };
    return q;
}

/** Apply a projection answer's `columnTypes` to the mapping it answered (absent/empty = untyped). */
export function withColumnTypes(
    p: EntityProjection,
    types: Record<string, EntityTypeRef> | undefined,
): EntityProjection {
    const { sourceType: _s, targetType: _t, ...rest } = p;
    const sourceType = types?.[p.sourceCol];
    const targetType = types?.[p.targetCol];
    return { ...rest, ...(sourceType ? { sourceType } : {}), ...(targetType ? { targetType } : {}) };
}

/** Record a raw spelling on a node that already exists (D-S4) — the split-identity notice reads these. */
function addSpelling(node: G6Node, value: string): void {
    const s = node.data.spellings;
    if (s && !s.includes(value)) s.push(value);
}

/**
 * Merge several {@link ProjectedGraph}s (one per {@link EntityProjection} mapping) into one graph:
 * nodes dedup by id (first mapping wins the node's display data), edges concatenate as-is (their ids
 * already carry source/target/kind/attrs, so a genuine duplicate collapses naturally). `truncated` is
 * true if any input mapping truncated.
 */
export function mergeProjectedGraphs(graphs: ProjectedGraph[]): ProjectedGraph {
    return { ...mergeGraphs(graphs), truncated: graphs.some((g) => g.truncated) };
}

/**
 * Investigation pivot (ui-design-review R8): when a projection column is named for an operational
 * object — `caseId`/`incidentId`/`objectId` (case-insensitive) — the entities it produces ARE record
 * references, so their nodes carry an `objectRef` and the detail dialog offers "Open record".
 */
export function objectRefForColumn(column: string | undefined, value: string): G6Node['data']['objectRef'] {
    const col = (column ?? '').toLowerCase();
    if (col === 'caseid') return { id: value, type: 'CASE' };
    if (col === 'incidentid' || col === 'objectid') return { id: value, type: 'INCIDENT' };
    return undefined;
}

/** A projection failure the UI can render inline (bad mapping ≠ a thrown stack). */
export interface ProjectionError {
    error: string;
}

export interface ProjectedGraph extends G6GraphData {
    /** True when the node cap cut the projection short — surfaced as a banner. */
    truncated: boolean;
    /**
     * Links the Working Set canvas left off because it holds more than the render ceiling
     * ({@code WORKING_SET_LINK_RENDER_CEILING}); absent or 0 = every link is drawn. Never a silent cut.
     */
    omittedLinks?: number;
    /**
     * LA-17 D-M6: the mappings the node ids were minted with, column types resolved — what a later mint over the
     * same graph (brush, path hop) must use. Set by the GraphSources; absent on a pure fold.
     */
    idMappings?: EntityIdMapping[];
}

export function isProjectionError(v: ProjectedGraph | ProjectionError): v is ProjectionError {
    return 'error' in v;
}

/** Pure row→graph fold. Rows with a blank source or target value are skipped. */
export function projectEntities(
    rows: Record<string, unknown>[],
    p: EntityProjection,
): ProjectedGraph | ProjectionError {
    if (!p.sourceCol || !p.targetCol) return { error: 'The mapping needs a source and a target column.' };
    if (rows.length && !(p.sourceCol in rows[0])) return { error: `Column '${p.sourceCol}' is not in the dataset.` };
    if (rows.length && !(p.targetCol in rows[0])) return { error: `Column '${p.targetCol}' is not in the dataset.` };

    const nodes = new Map<string, G6Node>();
    const edges = new Map<
        string,
        G6Edge & { data: { kind: string; count: number; attrs?: Record<string, string | null> } }
    >();
    let truncated = false;

    const ensure = (value: string, column: string, end: 'source' | 'target'): string | null => {
        const id = endpointId(p, end, value);
        if (!id) return null;
        if (!nodes.has(id)) {
            if (nodes.size >= projectionNodeCapValue()) {
                truncated = true;
                return null;
            }
            nodes.set(id, {
                id,
                data: {
                    label: value,
                    kind: 'entity',
                    objectRef: objectRefForColumn(column, value),
                    spellings: [value],
                },
            });
        } else addSpelling(nodes.get(id)!, value);
        return id;
    };

    for (const row of rows) {
        const s = String(row[p.sourceCol] ?? '').trim();
        const t = String(row[p.targetCol] ?? '').trim();
        if (!s || !t || !endpointId(p, 'source', s) || !endpointId(p, 'target', t)) continue;
        const sid = ensure(s, p.sourceCol, 'source');
        const tid = ensure(t, p.targetCol, 'target');
        if (!sid || !tid) continue;
        const kind = p.linkKindCol ? String(row[p.linkKindCol] ?? 'link') : 'link';
        const attrs = p.attrCols?.length
            ? Object.fromEntries(p.attrCols.map((c) => [c, row[c] == null ? null : String(row[c])]))
            : undefined;
        // attrs join the fold key — differing values split a folded pair into separate edges,
        // mirroring the backend's GROUP BY semantics (docs/BACKLOG.md INV-1).
        const key = `${sid}->${tid}:${kind}${attrs ? ':' + JSON.stringify(attrs) : ''}`;
        const existing = edges.get(key);
        if (existing) {
            existing.data.count++;
            existing.data.kind = `${kind} · ${existing.data.count}`;
        } else {
            edges.set(key, { id: key, source: sid, target: tid, data: { kind, count: 1, attrs } });
        }
    }
    return { nodes: [...nodes.values()], edges: [...edges.values()], truncated };
}

/**
 * Fold the backend's aggregated triples (heaviest first) into the same G6 shapes as
 * {@link projectEntities}: {@link endpointId} node ids, `sid->tid:kind` edge ids, `kind · count`
 * folded-edge labels, and the {@link projectionNodeCapValue} with a truncation flag.
 */
export function projectTriples(
    triples: ProjectionTriple[],
    serverTruncated: boolean,
    p?: EntityProjection,
    /** The node cap guards the QUERY graph; a caller whose nodes are already bounded elsewhere passes Infinity. */
    nodeCap: number = projectionNodeCapValue(),
): ProjectedGraph {
    const nodes = new Map<string, G6Node>();
    // Keyed by edge id: two server triples whose spellings normalise to one pair fold into one edge (D-S4).
    const edges = new Map<string, G6Edge & { data: { kind: string; count: number } }>();
    let truncated = serverTruncated;

    const ensure = (value: string, column: string | undefined, end: 'source' | 'target'): string | null => {
        const id = endpointId(p, end, value);
        if (!id) return null;
        if (!nodes.has(id)) {
            if (nodes.size >= nodeCap) {
                truncated = true;
                return null;
            }
            nodes.set(id, {
                id,
                data: {
                    label: value,
                    kind: 'entity',
                    objectRef: objectRefForColumn(column, value),
                    spellings: [value],
                },
            });
        } else addSpelling(nodes.get(id)!, value);
        return id;
    };

    for (const t of triples) {
        const s = String(t.source ?? '').trim();
        const tv = String(t.target ?? '').trim();
        if (!s || !tv || !endpointId(p, 'source', s) || !endpointId(p, 'target', tv)) continue;
        const sid = ensure(s, p?.sourceCol, 'source');
        const tid = ensure(tv, p?.targetCol, 'target');
        if (!sid || !tid) continue;
        const kind = t.kind ?? 'link';
        const id = `${sid}->${tid}:${kind}${t.attrs ? ':' + JSON.stringify(t.attrs) : ''}`;
        const existing = edges.get(id);
        const count = (existing?.data.count ?? 0) + t.count;
        const label = count > 1 ? `${kind} · ${count}` : kind;
        if (existing) {
            existing.data.count = count;
            existing.data.kind = label;
        } else {
            edges.set(id, { id, source: sid, target: tid, data: { kind: label, count, attrs: t.attrs } });
        }
    }
    return { nodes: [...nodes.values()], edges: [...edges.values()], truncated };
}

/**
 * The pluggable source: backend projection first ({@code POST /inv/projection} → {@link projectTriples});
 * on any failure (offline demo, pre-INV-1 backend) the original client fold over sample rows.
 */
export class EntityProjectionGraphSource implements GraphSource {
    readonly id = 'entity-projection' as const;
    readonly label = 'Entity/Link (from a Dataset)';
    constructor(
        private datasets: LaDatasets,
        private inv: InvService,
    ) {}

    async query(q: GraphSourceQuery): Promise<ProjectedGraph> {
        if (q.projections?.length) {
            const graphs = await Promise.all(q.projections.map((p) => this.queryOne(p, q.filter)));
            return { ...mergeProjectedGraphs(graphs), idMappings: graphs.flatMap((g) => g.idMappings ?? []) };
        }
        if (!q.projection) throw new Error('The entity-projection source needs a Dataset mapping.');
        return this.queryOne(q.projection, q.filter);
    }

    /** One mapping's projection: backend-first, falling back to the client sample fold on failure. */
    private async queryOne(p: EntityProjection, filter?: GraphSourceQuery['filter']): Promise<ProjectedGraph> {
        if (!p.datasetId) throw new Error('The entity-projection source needs a Dataset mapping.');
        if (!p.sourceCol || !p.targetCol) throw new Error('The mapping needs a source and a target column.');
        const res = await firstValueFrom(
            this.inv.project({
                dataset: p.datasetId,
                sourceCol: p.sourceCol,
                targetCol: p.targetCol,
                linkKindCol: p.linkKindCol || undefined,
                attrCols: p.attrCols?.length ? p.attrCols : undefined,
                filter,
            }),
        );
        const typed = withColumnTypes(p, res.columnTypes);
        return { ...projectTriples(res.rows, res.truncated, typed), idMappings: [typed] };
    }

    /**
     * Phase E incremental expand: the one-hop neighborhood of `nodeLabel` (the entity's raw projected
     * value) via `POST /inv/projection/neighbors`. Only supported for a single-mapping query — a
     * multi-mapping graph (`q.projections`) doesn't record which mapping produced which node, so
     * expand there needs a follow-up scope decision, not a guess; it throws a clear message instead.
     */
    async expand(_nodeId: string, nodeLabel: string, q: GraphSourceQuery): Promise<ProjectedGraph> {
        const p = q.projection;
        if (!p) throw new Error('Incremental expand needs a single-mapping query.');
        const res = await firstValueFrom(
            this.inv.neighbors({
                dataset: p.datasetId,
                sourceCol: p.sourceCol,
                targetCol: p.targetCol,
                linkKindCol: p.linkKindCol || undefined,
                attrCols: p.attrCols?.length ? p.attrCols : undefined,
                filter: q.filter,
                value: nodeLabel,
            }),
        );
        const typed = withColumnTypes(p, res.columnTypes);
        return { ...projectTriples(res.rows, res.truncated, typed), idMappings: [typed] };
    }
}

/**
 * A readable message for an `/inv/*` failure. A 404 there is deliberately ambiguous — the Dataset is unknown
 * OR the caller may not view it (R3: shared-away reads as absence) — and on `/inv/projection/multi` it refuses
 * the WHOLE call, so the message says no partial graph was drawn. A 422 carries the server's own reason.
 */
export function invErrorMessage(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse && err.status === 404) {
        return (
            'A Dataset in this query is not available to you (unknown, or not shared with you) — ' +
            'the whole query was refused, so no partial graph is shown. Server: ' +
            apiErrorMessage(err, 'not found')
        );
    }
    return apiErrorMessage(err, fallback);
}

/** Add `v` to a first-seen-order list held on a node/edge, creating it on first use. */
function addTo(list: string[] | undefined, v: string): string[] {
    const out = list ?? [];
    if (!out.includes(v)) out.push(v);
    return out;
}

/** An LA-08 answer as a graph, plus the per-mapping row summary the pane shows beside it. */
export interface MultiProjectedGraph extends ProjectedGraph {
    mappings: MultiProjectionMappingSummary[];
}

/**
 * Map a `POST /inv/projection/multi` answer (LA-08) into the studio graph. The server returns values RAW, one
 * entry per provenance (D-S4); a node id is minted UNSCOPED by {@link entityId}, or `<type>:<key>` when the row
 * says its column is typed (LA-17 D-M6), so an entity that appears in two Datasets under two spellings is ONE node carrying both Datasets in `data.provenance` and both
 * spellings in `data.spellings`. Node mappings are read first, so their label column and category win the
 * display; an edge endpoint no node mapping named still becomes an entity. Edges fold on
 * `sid->tid:kind[:attrs]` exactly as {@link projectTriples} does, summing counts across Datasets.
 */
export function projectMultiResult(res: MultiProjectionResult): MultiProjectedGraph {
    const nodes = new Map<string, G6Node>();
    const edges = new Map<string, G6Edge & { data: { kind: string; count: number } }>();
    let truncated = res.truncated;
    // A mapping's category is a free-form entity kind (SIM, IMEI, Cell…), not a catalog NodeKind, so the
    // catalog accent map would draw every one the fallback grey: give each category, in first-seen order, the
    // next categorical palette colour. Nodes no mapping named stay the plain `entity` kind.
    const categoryColors = new Map<string, string>();
    const categoryColor = (c: string) => {
        if (!categoryColors.has(c))
            categoryColors.set(c, CHART_CATEGORICAL_NEUTRAL[categoryColors.size % CHART_CATEGORICAL_NEUTRAL.length]);
        return categoryColors.get(c)!;
    };

    const types = new Map<string, EntityTypeRef>();
    const ensure = (
        value: string,
        provenance: string,
        type: EntityTypeRef | undefined,
        label?: string | null,
        category?: string | null,
    ) => {
        if (type) types.set(type.id, type);
        const id = typedOrEntityId(type, undefined, value);
        if (!id) return null;
        const node = nodes.get(id);
        if (node) {
            addSpelling(node, value);
            node.data.provenance = addTo(node.data.provenance, provenance);
            return id;
        }
        if (nodes.size >= projectionNodeCapValue()) {
            truncated = true;
            return null;
        }
        const data: G6Node['data'] = {
            label: label || value,
            kind: category || 'entity',
            spellings: [value],
            provenance: [provenance],
        };
        if (category) data.color = categoryColor(category);
        nodes.set(id, { id, data });
        return id;
    };

    for (const n of res.nodes) {
        const v = String(n.id ?? '').trim();
        if (v) ensure(v, n.__provenance_dataset, n.entityType, n.label, n.category);
    }
    for (const t of res.edges) {
        const s = String(t.source ?? '').trim();
        const tv = String(t.target ?? '').trim();
        if (!s || !tv || !typedOrEntityId(t.sourceType, undefined, s) || !typedOrEntityId(t.targetType, undefined, tv))
            continue;
        const sid = ensure(s, t.__provenance_dataset, t.sourceType);
        const tid = ensure(tv, t.__provenance_dataset, t.targetType);
        if (!sid || !tid) continue;
        const kind = t.kind ?? 'link';
        const id = `${sid}->${tid}:${kind}${t.attrs ? ':' + JSON.stringify(t.attrs) : ''}`;
        const existing = edges.get(id);
        const count = (existing?.data.count ?? 0) + t.count;
        const label = count > 1 ? `${kind} · ${count}` : kind;
        if (existing) {
            existing.data.count = count;
            existing.data.kind = label;
            existing.data.provenance = addTo(existing.data.provenance, t.__provenance_dataset);
        } else {
            edges.set(id, {
                id,
                source: sid,
                target: tid,
                data: { kind: label, count, attrs: t.attrs, provenance: [t.__provenance_dataset] },
            });
        }
    }
    // A raw value arriving without its column (a Geo key, a path hop) may be any seen type, or untyped.
    const idMappings: EntityIdMapping[] = [{}, ...[...types.values()].map((t) => ({ sourceType: t, targetType: t }))];
    return { nodes: [...nodes.values()], edges: [...edges.values()], truncated, mappings: res.mappings, idMappings };
}

/** The LA-08 GraphSource: one `POST /inv/projection/multi` call per query. No incremental expand (yet). */
export class MultiProjectionGraphSource implements GraphSource {
    readonly id = 'entity-projection-multi' as const;
    readonly label = 'Entity/Link (several Datasets)';
    constructor(private inv: InvService) {}

    async query(q: GraphSourceQuery): Promise<MultiProjectedGraph> {
        const m = q.multi;
        if (!m || (!m.nodes.length && !m.edges.length)) throw new Error('Add at least one node or edge mapping.');
        try {
            const res = await firstValueFrom(
                this.inv.projectMulti({ nodes: m.nodes, edges: m.edges, filter: q.filter }),
            );
            return projectMultiResult(res);
        } catch (err) {
            throw new Error(invErrorMessage(err, 'The multi-Dataset projection failed.'), { cause: err });
        }
    }
}

/** What an LA-11 traversal found, in graph ids, with the server's fences stated rather than implied. */
export interface ServerPathsState {
    paths: GraphSelection[];
    /** The path limit or the edge-yield fence cut the answer short. */
    truncated: boolean;
    /** A recursion level hit `maxEdgeYield`: longer paths may exist that were never walked. */
    edgeYieldCapped: boolean;
    /** The depth fence the server applied (after clamping) — no path longer than this was looked for. */
    depthLimit: number;
    /** The longest path returned, in hops (0 when none). */
    deepest: number;
}

/**
 * Map a `POST /inv/traversal/recursive-paths` answer (LA-11) onto the working set. Path values are raw and carry
 * no column, so each is minted by {@link resolveEntityId} over the run's `mappings` — the candidate the projection
 * already drew, else the start as a source and every later hop as a target (D-M6). A step between two nodes the working set already links reuses that link (either direction); a step it
 * does not have — the walk ran over the whole Dataset, not the loaded slice — is added as a `path` link, and
 * so is any node it reached beyond the working set, so every returned path can be drawn and highlighted.
 */
export function recursivePathsToGraph(
    res: RecursivePathsResult,
    base: G6GraphData,
    mappings: readonly (EntityIdMapping | undefined)[] = [],
): { graph: G6GraphData; state: ServerPathsState } {
    const nodes = new Map(base.nodes.map((n) => [n.id, n]));
    const edges = new Map(base.edges.map((e) => [e.id, e]));
    const linkBetween = (a: string, b: string): string | undefined =>
        [...edges.values()].find((e) => (e.source === a && e.target === b) || (e.source === b && e.target === a))?.id;

    // A hop with no mintable id (an empty typed key -- the server refuses those) drops its whole path.
    const mintable = res.paths.filter((p) =>
        p.nodes.every((raw) => entityIdCandidates(mappings, String(raw ?? '').trim()).length),
    );
    const paths: GraphSelection[] = mintable.map((p) => {
        const nodeIds = p.nodes.map((raw, i) => {
            const value = String(raw ?? '').trim();
            const id = resolveEntityId(mappings, value, (x) => nodes.has(x), i === 0 ? 'source' : 'target');
            if (!nodes.has(id)) nodes.set(id, { id, data: { label: value, kind: 'entity', spellings: [value] } });
            return id;
        });
        const edgeIds: string[] = [];
        for (let i = 1; i < nodeIds.length; i++) {
            const [a, b] = [nodeIds[i - 1], nodeIds[i]];
            let id = linkBetween(a, b);
            if (!id) {
                id = `path:${a}->${b}`;
                edges.set(id, { id, source: a, target: b, data: { kind: 'path' } });
            }
            edgeIds.push(id);
        }
        return { nodeIds, edgeIds };
    });
    return {
        graph: { nodes: [...nodes.values()], edges: [...edges.values()] },
        state: {
            paths,
            truncated: res.truncated,
            edgeYieldCapped: res.edgeYieldCapped,
            depthLimit: res.fences.maxDepth,
            deepest: res.paths.reduce((m, p) => Math.max(m, p.hops), 0),
        },
    };
}

/** What an LA-14b server pattern search found, in graph ids — the browser matcher's result shape. */
export interface ServerPatternState {
    matches: BranchingMatch[];
    /** The match limit, the work budget or the leg fence cut the answer short. */
    truncated: boolean;
    /** The leg fence was hit: part of the Dataset was not searched. */
    legCapped: boolean;
    refusal?: string;
}

/**
 * Map a `POST /inv/pattern/branching` answer (LA-14b) onto the working set, as {@link recursivePathsToGraph} does
 * for LA-11: node values are raw; a leg endpoint mints by {@link resolveEntityId} preferring its own end (D-M6), a match's bare values by the id
 * their leg minted (else {@link resolveEntityId}); a leg the working set already draws is reused
 * (the same edge id {@link projectTriples} mints — when the projection's `attrCols` are the time column then the
 * threshold columns, the order the server spells `attrs` in), and one it lacks —
 * the search ran over the whole Dataset, typically because the projection cut exactly these legs — is added.
 */
export function branchingResultToGraph(
    res: BranchingPatternResult,
    base: G6GraphData,
    mappings: readonly (EntityIdMapping | undefined)[] = [],
): { graph: G6GraphData; state: ServerPatternState } {
    const nodes = new Map(base.nodes.map((n) => [n.id, n]));
    const edges = new Map(base.edges.map((e) => [e.id, e]));
    const minted = new Map<string, string>(); // raw value -> the id a leg endpoint minted it as
    const node = (raw: string, end?: 'source' | 'target'): string | null => {
        const value = String(raw ?? '').trim();
        const id = end
            ? resolveEntityId(mappings, value, (x) => nodes.has(x), end)
            : (minted.get(value) ?? resolveEntityId(mappings, value, (x) => nodes.has(x), 'target'));
        if (!id) return null; // an empty typed key: no node, like a blank value
        if (end && !minted.has(value)) minted.set(value, id);
        if (!nodes.has(id)) nodes.set(id, { id, data: { label: value, kind: 'entity', spellings: [value] } });
        else addSpelling(nodes.get(id)!, value);
        return id;
    };
    const legId = new Map<string, string>();
    for (const leg of res.edges) {
        const sid = node(leg.source, 'source');
        const tid = node(leg.target, 'target');
        if (!sid || !tid) continue;
        const id = `${sid}->${tid}:${leg.kind}:${JSON.stringify(leg.attrs)}`;
        if (!edges.has(id))
            edges.set(id, {
                id,
                source: sid,
                target: tid,
                data: { kind: leg.kind, attrs: leg.attrs as Record<string, string> },
            });
        legId.set(leg.id, id);
    }
    const matches: BranchingMatch[] = res.matches.map((m) => ({
        nodeIds: m.nodeIds.map((v) => node(v)).filter((id) => id !== null),
        edgeIds: m.edgeIds.map((id) => legId.get(id) ?? id),
        layers: m.layers.map((layer) => layer.map((v) => node(v)).filter((id) => id !== null)),
    }));
    return {
        graph: { nodes: [...nodes.values()], edges: [...edges.values()] },
        state: { matches, truncated: res.truncated, legCapped: res.legCapped, refusal: res.refusal },
    };
}

/**
 * An identity whose raw spellings differ only by case, spacing or trailing punctuation (decision D-S4).
 * Since 2026-09-23 {@link entityId} NORMALISES ids, so a projection folds those spellings into ONE node
 * (`data.spellings`); this group still names them, because two spellings genuinely CAN be two entities
 * and the analyst must be told a fold happened. A graph whose nodes carry raw ids (a saved view from
 * before D-S4, or another GraphSource) is reported the same way, as two or more `ids` sharing a key.
 */
export interface SplitIdentityGroup {
    /** The entity-type scope these ids share; empty for the unscoped single-mapping path. */
    scope: string;
    /** The shared normalised value, for a stable `track` and for tests. */
    key: string;
    /** The node ids on `key` -- just one when the projection already folded the spellings. */
    ids: string[];
    /** The distinct raw spellings on `key`, first-seen order -- always 2 or more. */
    spellings: string[];
    /** One member's label, to name the group without implying which spelling is canonical. */
    sample: string;
}

/**
 * Detect identities whose raw spellings the normalised id folded together (or, on a raw-id graph, left
 * split), so the analyst knows degree, communities and rankings count them as one.
 *
 * The comparison key is the SAME {@link normalizeEntityKey} the ids are minted with -- a private copy
 * would let the notice and the ids disagree. The count is distinct raw spellings vs distinct keys.
 * This function itself never mutates the graph.
 *
 * Type-scoped ids are compared only within their own scope, so `entity:person:bob` and
 * `entity:account:bob` (or typed `msisdn:+44…` vs `imsi:…`, D-M6) are two entities by construction, not a split
 * identity. Super-node stand-ins
 * are skipped -- their label is a count, not a name.
 */
export function splitIdentityGroups(graph: G6GraphData | null | undefined): SplitIdentityGroup[] {
    if (!graph) return [];
    const byScope = new Map<string, Map<string, { ids: string[]; spellings: string[]; sample: string }>>();
    for (const n of graph.nodes) {
        if (n.data.superMembers) continue;
        const raw = n.id.startsWith('entity:') ? n.id.slice('entity:'.length) : n.id;
        // A type-scoped id is `<entityType>:<value>`; keep the scope apart so types never merge.
        const cut = raw.indexOf(':');
        const scope = cut >= 0 ? raw.slice(0, cut) : '';
        const value = cut >= 0 ? raw.slice(cut + 1) : raw;
        const key = normalizeEntityKey(value);
        let bucket = byScope.get(scope);
        if (!bucket) byScope.set(scope, (bucket = new Map()));
        let hit = bucket.get(key);
        if (!hit) bucket.set(key, (hit = { ids: [], spellings: [], sample: n.data.label }));
        hit.ids.push(n.id);
        for (const s of n.data.spellings ?? [value]) if (!hit.spellings.includes(s)) hit.spellings.push(s);
    }
    const groups: SplitIdentityGroup[] = [];
    for (const [scope, bucket] of byScope) {
        for (const [key, v] of bucket) {
            if (v.spellings.length > 1) groups.push({ scope, key, ...v });
        }
    }
    return groups;
}
