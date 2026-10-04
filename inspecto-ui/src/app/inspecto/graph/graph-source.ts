import type { GraphDirection } from 'app/inspecto/api';
import type { G6GraphData } from './graph-types';
import type { EntityTypeRef } from './entity-key';
import type { ConditionGroup } from '../query/query-types';

/**
 * The **GraphSource seam** (GLOSSARY §11, design: docs/superpower/link-analysis-and-graphsource.md):
 * one renderer (`GraphViewComponent`) + one query shape + N pluggable sources. Framework-agnostic —
 * concrete sources live with their feature (they wrap the existing pure mappers and, where needed,
 * an API service); this file is types only, so anything can depend on it without importing a feature.
 */

/** The four graph planes a query can target (GLOSSARY §11: P1 / P2 / P2′ / P3). */
export type GraphSourceId =
    | 'component-registry'
    | 'lineage'
    | 'provenance'
    | 'entity-projection'
    | 'entity-projection-multi';

/**
 * The `entity-projection` mapping (P3): fold a Dataset's rows into a business Entity/Link graph —
 * distinct source/target column values become Entities, each row a Link. `linkKindCol` types the
 * Link from a column's value; `attrCols` carry extra row attributes onto the Link.
 */
export interface EntityProjection {
    datasetId: string;
    sourceCol: string;
    targetCol: string;
    linkKindCol?: string;
    attrCols?: string[];
    /**
     * Multi-mapping merges (P3, `projections`) are **type-scoped**: an `entityType` distinguishes a
     * `person` entity named "Bob" from an `account` entity named "Bob" so they don't silently merge
     * into one node just because their projected value happens to match. Ignored (and the id scheme
     * stays plain `entity:<value>`, unchanged since 2026-07-08) when only one mapping runs.
     */
    entityType?: string;
    /**
     * LA-17 D-M6: the Entity Type of `sourceCol` / `targetCol`, as the server resolved it from the Dataset's
     * column classifications (never authored). A typed endpoint mints `<type>:<key>` and wins over `entityType`.
     */
    sourceType?: EntityTypeRef;
    targetType?: EntityTypeRef;
}

/** The part of a mapping that decides entity node ids — what every mint site takes. */
export type EntityIdMapping = Pick<EntityProjection, 'entityType' | 'sourceType' | 'targetType'>;

/**
 * The unified graph query — the generalization of the lineage plane's `GraphQuery`
 * (`GET /catalog/graph`) plus the per-plane extras. A source reads only the fields it understands.
 */
export interface GraphSourceQuery {
    /** Root node; absent = the whole graph. */
    from?: string;
    /**
     * Multi-root seeds (lineage/provenance only — GLOSSARY §11 P2/P2′): query each root and merge the
     * results into one graph. Takes precedence over `from` when present. Not meaningful for
     * entity-projection (P3), which has no root concept — use `projections` there instead.
     */
    roots?: string[];
    /** BFS radius from `from`. */
    depth?: number;
    direction?: GraphDirection;
    /** Node-kind filter. */
    kinds?: string[];
    /** Edge-kind filter. */
    edgeKinds?: string[];
    /** P2: attach the operational overlay. */
    overlay?: boolean;
    /** P2′: weight edges by provenance row counts. */
    counts?: boolean;
    /** P3: the Dataset column→Entity/Link mapping (entity-projection only). */
    projection?: EntityProjection;
    /**
     * P3 multi-entity/multi-dataset mapping: run several {@link EntityProjection}s and merge the
     * results into one graph (entity-projection only). When present, takes precedence over `projection`.
     */
    projections?: EntityProjection[];
    /**
     * LA-08 (entity-projection-multi only): node mappings + edge mappings over several Datasets, answered by
     * ONE `POST /inv/projection/multi` call. Entities from different Datasets whose values normalise to one
     * key (D-S4) merge into one node, which keeps the Datasets it came from in `data.provenance`.
     */
    multi?: { nodes: MultiNodeMapping[]; edges: MultiEdgeMapping[] };
    /**
     * Stage 2 of the two-stage query loop (spec §3.7): the shared condition tree, applied to the Dataset's
     * rows BEFORE the fold so edge counts stay right. Sent verbatim as `filter` on `POST /inv/projection`.
     * ⚠ Until the backend gains the field (plan S1.4) the server ignores it and returns the unfiltered
     * projection — the UI says so instead of pretending.
     */
    filter?: ConditionGroup;
}

/** One pluggable origin of graph data. `query()` may hit the backend or derive client-side. */
export interface GraphSource {
    readonly id: GraphSourceId;
    readonly label: string;
    query(q: GraphSourceQuery): Promise<G6GraphData>;
    /**
     * Phase E incremental expand: the one-hop neighborhood of `nodeLabel` (the node's raw projected
     * value/id, not its graph node id) under the same query context `q` — merged into the existing
     * canvas by the caller rather than replacing it. Optional: sources with no natural "neighbors of
     * X" notion (component-registry — a fully-loaded static graph) omit it, and the UI hides the action.
     * `spellings` are the node's RAW values (`data.spellings`) — a multi-mapping source needs them, because its
     * node id is normalised and its label may come from a label column.
     */
    expand?(nodeId: string, nodeLabel: string, q: GraphSourceQuery, spellings?: string[]): Promise<G6GraphData>;
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
