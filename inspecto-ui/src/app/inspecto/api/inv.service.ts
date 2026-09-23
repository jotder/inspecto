import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';
import type { ConditionGroup } from '../query/query-types';

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
}
