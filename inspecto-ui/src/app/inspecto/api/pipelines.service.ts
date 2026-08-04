import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';
import type { AttributeSpec } from 'app/inspecto/component-model/attribute-spec';

/** A node-type family (palette grouping + role checks); mirrors the backend `NodeCategory`. */
export type PipelineNodeCategory = 'SOURCE' | 'PARSE' | 'TRANSFORM' | 'SINK' | 'CONTROL' | string;

/** A compact pipeline entry (GET /pipelines) — one per registered pipeline, lifted to a pipeline graph. */
export interface PipelineSummary {
    /** The pipeline's identity (the normalised id) — what every other route keys on. */
    name: string;
    active: boolean;
    nodeCount: number;
    edgeCount: number;
    produces: string[];
    consumes: string[];
    /**
     * A non-runnable authoring template (`template: true`). Absent ⇒ an ordinary pipeline. A template is
     * refused by every run path server-side, so the UI hides run affordances rather than guarding them.
     */
    template?: boolean;
    /** The display name, sent only when it differs from {@link name}; absent ⇒ the id IS the label. */
    displayName?: string;
}

/** One node in a pipeline graph projection (structural only — no raw config; the inspector shows this). */
export interface PipelineNode {
    id: string;
    type: string;
    category: PipelineNodeCategory;
    label: string;
    name?: string;
    description?: string;
    use?: string;
    store?: string;
    sourceStore?: string;
    sinkKind?: string;
    restsOnDisk?: boolean;
}

/** One relationship-typed edge. `kind` styles the line (solid data / dashed control / route). */
export interface PipelineEdge {
    from: string;
    to: string;
    rel: string;
    kind: 'data' | 'control' | 'route';
    routeKey?: string;
}

/** A pipeline graph projection (GET /pipelines/{id}/graph) for the G6 renderer. */
export interface PipelineGraph {
    name: string;
    active: boolean;
    nodes: PipelineNode[];
    edges: PipelineEdge[];
    produces: string[];
    consumes: string[];
}

/** A node-type descriptor for the editor palette (GET /pipelines/node-types). */
export interface PipelineNodeType {
    type: string;
    category: PipelineNodeCategory;
    label: string;
    description: string;
    accepts: string[];
    emits: string[];
    emitsNamedRoutes: boolean;
    /**
     * Whether a save can lower this type back to the flat pipeline config. `false` ⇒ the engine may
     * well run it, but the editor cannot persist it (422 `UNSUPPORTED_NODE`), so the palette disables
     * it. Save-ability, not runnability.
     */
    lowerable: boolean;
    /**
     * The node's config vocabulary, published by the server (§3.1 of
     * `docs/superpower/vocabulary-and-config-contract-plan.md`). This is the SOURCE for the node dialog's
     * form; `node-attributes.ts` is the offline/mock fallback for when the catalog has not loaded.
     *
     * An empty array is meaningful and different from `undefined`: it means the server says this type has
     * **no** schema (free-form editor only), whereas `undefined` means we never heard from the server.
     */
    attributes?: AttributeSpec[];
}

/** A node in the combined topology: a pipeline node (with its owning `flow`) or a synthetic `STORE` join node. */
export interface CombinedNode extends PipelineNode {
    flow?: string;   // the owning flow (absent on synthetic store nodes)
}

/** An edge in the combined topology; `kind:'store'` is a producer→store or store→consumer join edge. */
export interface CombinedEdge {
    from: string;
    to: string;
    rel: string;
    kind: 'data' | 'control' | 'route' | 'store';
    routeKey?: string;
    restsOnDisk?: boolean;   // on store edges: whether the joined store rests on disk
    flow?: string;
}

/** The combined pipeline+job topology (GET /pipelines/combined): flows joined at shared store nodes (T24). */
export interface PipelineCombined {
    flows: { name: string; active: boolean }[];
    nodes: CombinedNode[];
    edges: CombinedEdge[];
    links: { producer: string; store: string; consumer: string }[];
}

// ── Authored pipelines (editor build-side) — the lossless shape that round-trips through the backend ──

/** A node in an authored pipeline (config-bearing — what GET /pipelines/authored/{id}/raw and PUT exchange). */
export interface AuthoredNode {
    id: string;
    type: string;
    name?: string;
    description?: string;
    use?: string;
    config?: Record<string, unknown>;
}

/** An authored edge (`rel` = `data` | `control` | `route:<key>` | …). */
export interface AuthoredEdge {
    from: string;
    rel: string;
    to: string;
}

/** A full authored pipeline definition (GET …/raw, POST/PUT body) — lossless, unlike the read-only projection. */
export interface AuthoredPipeline {
    name: string;
    active: boolean;
    nodes: AuthoredNode[];
    edges: AuthoredEdge[];
}

/** One named reason a graph cannot lower to the flat config (W5; `nodeId` absent for graph-level). */
export interface PipelineRefusal {
    code: string;
    nodeId?: string;
    message: string;
}

/** The receipt of PUT /pipelines/{name}/graph — the canonical config was validated and written. */
export interface PipelineGraphWriteResult {
    written: boolean;
    path: string;
    name: string;
    findings: { severity: string; fieldPath?: string; message: string }[];
}

/** One produced relation at a node in a dry-run (exact count + a bounded row sample). */
export interface DryRunRelation {
    rel: string;
    rowCount: number;
    rows: Record<string, unknown>[];
}

/** A non-sink node's dry-run outputs. */
export interface DryRunNode {
    node: string;
    type: string;
    relations: DryRunRelation[];
}

/** A sink branch's dry-run outcome. */
export interface DryRunSink {
    node: string;
    store: string;
    rowCount: number;
    rows: Record<string, unknown>[];
}

/** The dry-run result (POST /pipelines/authored/{id}/dry-run): seed + per-node + per-sink counts. */
export interface PipelineDryRunResult {
    seedNode: string;
    nodes: DryRunNode[];
    sinks: DryRunSink[];
}

/** Result of testing a single processor node over a bounded sample (POST /components/{type}/{id}/test). */
export interface ComponentTestResult {
    type: string;
    id: string;
    ok: boolean;
    detail: string;
    rowCount: number;
    rows: Record<string, unknown>[];
}

// ── Run-to-here (the in-editor build-and-test loop) ──

/** One relationship a node produced during a run-to-here (success/unmatched/kept/dropped/route:<key>). */
export interface PipelineRunRelation {
    node: string;
    rel: string;
    rowCount: number;
    rows: Record<string, unknown>[];
}

/** The materialized output a run-to-here landed (scratch only — never a production write). */
export interface PipelineRunOutput {
    store: string;
    format: string;
    path: string;
    rowCount: number;
}

/**
 * Result of running an authored pipeline up to a node over picked inbox files
 * (POST /pipelines/authored/{id}/run?to={nodeId}). Per-relation counts + a bounded sample, plus the scratch
 * Parquet the run landed — the editor's incremental "build a little, test a little" feedback.
 */
export interface PipelineRunResult {
    seedNode: string;
    toNode: string;
    files: string[];
    relations: PipelineRunRelation[];
    output: PipelineRunOutput | null;
    warnings: string[];
}

// ── Data-plane provenance (T22) — per-edge record counts of a past flow run ──

/** One run of a flow that recorded provenance (GET /provenance/batches), newest first. */
export interface ProvenanceBatch {
    batchId: string;
    runTs: string;
    totalRows: number;
}

/** The records a node emitted on one relationship during a run (GET /provenance) — a Sankey edge weight. */
export interface ProvenanceCount {
    nodeId: string;
    rel: string;
    rowCount: number;
}

/** The result of copying a pipeline into a template (POST /pipelines/{name}/save-as-template). */
export interface PipelineTemplateResult {
    written: boolean;
    path: string;
    id: string;
    source: string;
    template: boolean;
    /** Human-readable remarks about the copy (e.g. whether the schema file could be copied). */
    notes: string[];
}

/** The result of relabelling a pipeline (POST /pipelines/{name}/label). */
export interface PipelineLabelResult {
    written: boolean;
    path: string;
    /** The pipeline's identity — unchanged by a relabel, and still what every route keys on. */
    id: string;
    name: string;
    /** True when this relabel pinned the previously-derived identity as an explicit `id:`. */
    stampedId: boolean;
}

/** Read-only pipeline-graph projection + authored-pipeline CRUD/dry-run for the editor (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class PipelinesService {
    private http = inject(HttpClient);

    /** Every registered pipeline, lifted to a pipeline-graph summary. */
    list(): Observable<PipelineSummary[]> {
        return this.http.get<PipelineSummary[]>(apiUrl('/pipelines'));
    }

    /** The full graph projection for one pipeline, by its (normalised) name. */
    graph(name: string): Observable<PipelineGraph> {
        return this.http.get<PipelineGraph>(apiUrl(`/pipelines/${encodeURIComponent(name)}/graph`));
    }

    /** The node-type catalog for the palette. */
    nodeTypes(): Observable<PipelineNodeType[]> {
        return this.http.get<PipelineNodeType[]>(apiUrl('/pipelines/node-types'));
    }

    /** The combined pipeline+job topology — every pipeline joined at the shared store nodes (T24). */
    combined(): Observable<PipelineCombined> {
        return this.http.get<PipelineCombined>(apiUrl('/pipelines/combined'));
    }

    // ── the canonical round-trip (W5, U-A): the editor edits *_pipeline.toon itself ──

    /**
     * The LOSSLESS editable graph of a registered pipeline: lifted for topology, node configs
     * verbatim in the config-file vocabulary, plus a synthesized node per registered enrichment
     * companion. Round-trips through {@link savePipelineGraph}.
     */
    pipelineGraphRaw(name: string): Observable<AuthoredPipeline> {
        return this.http.get<AuthoredPipeline>(apiUrl(`/pipelines/${encodeURIComponent(name)}/graph/raw`));
    }

    /**
     * Lower the graph to the canonical `<name>_pipeline.toon` (URL name authoritative). An active
     * graph must be complete; an inactive draft may be partial. 422 carries named `refusals[]`
     * (UNSUPPORTED_NODE / MULTI_SINK / NO_* completeness codes) when the topology cannot be
     * represented as a flat config, or `findings[]` when the lowered config fails the write gate.
     */
    savePipelineGraph(name: string, pipeline: AuthoredPipeline): Observable<PipelineGraphWriteResult> {
        return this.http.put<PipelineGraphWriteResult>(
            apiUrl(`/pipelines/${encodeURIComponent(name)}/graph`), pipeline);
    }

    /**
     * Copy a pipeline into a non-runnable authoring template. The server repoints every environment
     * binding (dirs, stream, collector id, DuckLake path) into a `templates/<id>/` sandbox and copies the
     * schema, so the copy cannot read or write anything the source owns — and `template: true` keeps it
     * unrunnable until an operator deliberately clears it. 409 when `id` is already taken.
     */
    saveAsTemplate(name: string, id: string, displayName?: string): Observable<PipelineTemplateResult> {
        return this.http.post<PipelineTemplateResult>(
            apiUrl(`/pipelines/${encodeURIComponent(name)}/save-as-template`),
            displayName ? { id, name: displayName } : { id });
    }

    /**
     * Change a pipeline's DISPLAY name only. The server pins the current identity as an explicit `id:`
     * first, so the config file, audit trail, ledger keys and Catalog Stream all stay where they are —
     * this is a relabel, not a migration, and {@link PipelineSummary.name} is unchanged afterwards.
     */
    label(name: string, displayName: string): Observable<PipelineLabelResult> {
        return this.http.post<PipelineLabelResult>(
            apiUrl(`/pipelines/${encodeURIComponent(name)}/label`), { name: displayName });
    }

    // ── authored-pipeline CRUD + dry-run (editor; all writes 503 without -Dassist.write.root) ──

    /** Summaries of every authored pipeline (empty list when no write root). */
    authoredList(): Observable<PipelineSummary[]> {
        return this.http.get<PipelineSummary[]>(apiUrl('/pipelines/authored'));
    }

    /** The lossless authored definition (nodes with config) for editing — round-trips through PUT. */
    authoredRaw(id: string): Observable<AuthoredPipeline> {
        return this.http.get<AuthoredPipeline>(apiUrl(`/pipelines/authored/${encodeURIComponent(id)}/raw`));
    }

    /** Delete an authored pipeline. */
    deleteAuthored(id: string): Observable<unknown> {
        return this.http.delete(apiUrl(`/pipelines/authored/${encodeURIComponent(id)}`));
    }

    /** Dry-run a bounded sample through an authored pipeline (per-node + per-sink counts; no production write). */
    dryRunAuthored(id: string, sampleRows: Record<string, unknown>[]): Observable<PipelineDryRunResult> {
        return this.http.post<PipelineDryRunResult>(
            apiUrl(`/pipelines/authored/${encodeURIComponent(id)}/dry-run`), { sampleRows });
    }

    /** Test a single processor node over a bounded sample (no production write) — the per-processor test. */
    testNode(type: string, id: string): Observable<ComponentTestResult> {
        return this.http.post<ComponentTestResult>(
            apiUrl(`/components/${encodeURIComponent(type)}/${encodeURIComponent(id)}/test`), {});
    }

    /**
     * Run the authored pipeline up to {nodeId} over the chosen inbox `files`, materializing to a scratch store
     * (no production write). Drives the editor's run-to-here loop — per-relation counts + the Parquet landed.
     */
    runToNode(id: string, nodeId: string, files: string[]): Observable<PipelineRunResult> {
        return this.http.post<PipelineRunResult>(
            apiUrl(`/pipelines/authored/${encodeURIComponent(id)}/run`),
            { files },
            { params: { to: nodeId } });
    }

    // ── data-plane provenance (T22; 404 unless -Dprovenance.backend=duckdb) ──

    /** Recent runs of a flow that recorded provenance (newest first). */
    provenanceBatches(flow: string): Observable<ProvenanceBatch[]> {
        return this.http.get<ProvenanceBatch[]>(apiUrl('/provenance/batches'), { params: { flow } });
    }

    /** The per-(node, relationship) record counts of one run — painted onto the flow's edges as weights. */
    provenance(flow: string, batch: string): Observable<ProvenanceCount[]> {
        return this.http.get<ProvenanceCount[]>(apiUrl('/provenance'), { params: { flow, batch } });
    }
}
