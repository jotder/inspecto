import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpResponse } from '@angular/common/http';
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
     * `docs/okf/frontend/features/pipelines.md`). This is the SOURCE for the node dialog's
     * form; `node-attributes.ts` is the offline/mock fallback for when the catalog has not loaded.
     *
     * An empty array is meaningful and different from `undefined`: it means the server says this type has
     * **no** schema (free-form editor only), whereas `undefined` means we never heard from the server.
     */
    attributes?: AttributeSpec[];
}

/**
 * One recipe-verb palette entry (GET /pipelines/step-types, ELT amendment Phase 5): the seven verbs +
 * `route` in pipeline order, each carrying the node type it authors as + that type's served specs;
 * plugin-contributed types follow, keyed by their own type. The served version of the client verb map
 * (`RECIPE_VERBS`), which stays only as the old-server fallback.
 */
export interface RecipeStepType {
    verb: string;
    type: string;
    category: PipelineNodeCategory;
    label: string;
    description: string;
    lowerable: boolean;
    attributes: AttributeSpec[];
}

/** A node in the combined topology: a pipeline node (with its owning `flow`) or a synthetic `STORE` join node. */
export interface CombinedNode extends PipelineNode {
    flow?: string; // the owning flow (absent on synthetic store nodes)
}

/** An edge in the combined topology; `kind:'store'` is a producer→store or store→consumer join edge. */
export interface CombinedEdge {
    from: string;
    to: string;
    rel: string;
    kind: 'data' | 'control' | 'route' | 'store';
    routeKey?: string;
    restsOnDisk?: boolean; // on store edges: whether the joined store rests on disk
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

/** The result of the full identity migration (POST /pipelines/{name}/rename) — every artifact keyed
 *  by the old id moved to the new one; counts are informational, not a completeness guarantee. */
export interface PipelineRenameResult {
    written: boolean;
    oldId: string;
    id: string;
    name: string;
    path: string;
    ledgerRowsMoved: number;
    auditFilesRenamed: number;
    dependentsRewritten: number;
}

/** A pipeline's `reference:` block (D8) — set only when `produces` is `'reference'`. */
export interface PipelineReferenceSettings {
    load: 'replace' | 'upsert' | 'scd2';
    key?: string[];
    refresh_seconds?: number;
}

/**
 * The pipeline-level `produces`/`reference` block (D8, pipeline-graph backlog). Opaque passthrough to
 * the graph editor — {@link PipelineGraph} never carries it — so it has its own dedicated read/write
 * pair instead of riding through `PUT .../graph`.
 */
export interface PipelineSettings {
    produces: 'stream' | 'reference';
    reference: PipelineReferenceSettings | null;
}

export interface PipelineSettingsResult {
    written: boolean;
    path: string;
    produces: string;
    reference: PipelineReferenceSettings | null;
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

    /** The recipe-verb palette (Phase 5). 404 on an old server — callers fall back to the client verb map. */
    stepTypes(): Observable<RecipeStepType[]> {
        return this.http.get<RecipeStepType[]>(apiUrl('/pipelines/step-types'));
    }

    /**
     * The Pipeline Document (ELT amendment §5.1) — a Markdown projection of the pipeline's config for
     * business verification and sign-off, regenerated on demand and never stored.
     *
     * Returns the whole {@link HttpResponse} rather than the body alone because the sign-off
     * fingerprint rides the `X-Config-Fingerprint` header — a Markdown blob has nowhere else to carry
     * it. This is the only call in the app that needs `observe: 'response'`; read `res.body` for the
     * document and {@link documentFingerprint} for the hash.
     */
    document(name: string): Observable<HttpResponse<Blob>> {
        return this.http.get(apiUrl(`/pipelines/${encodeURIComponent(name)}/document`), {
            observe: 'response',
            responseType: 'blob',
        });
    }

    /** The config fingerprint a {@link document} response was stamped with, if the server sent one. */
    documentFingerprint(res: HttpResponse<Blob>): string | null {
        return res.headers.get('X-Config-Fingerprint');
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
            apiUrl(`/pipelines/${encodeURIComponent(name)}/graph`),
            pipeline,
        );
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
            displayName ? { id, name: displayName } : { id },
        );
    }

    /**
     * Change a pipeline's DISPLAY name only. The server pins the current identity as an explicit `id:`
     * first, so the config file, audit trail, ledger keys and Catalog Stream all stay where they are —
     * this is a relabel, not a migration, and {@link PipelineSummary.name} is unchanged afterwards.
     */
    label(name: string, displayName: string): Observable<PipelineLabelResult> {
        return this.http.post<PipelineLabelResult>(apiUrl(`/pipelines/${encodeURIComponent(name)}/label`), {
            name: displayName,
        });
    }

    /**
     * Change a pipeline's IDENTITY — the full migration (T3): the config filename, commit log, audit
     * CSVs, DuckDB status mirror and acquisition ledger all move to `newId`. `dirs.*` are left where
     * they are (the server 422s a `relocateDirs: true` request — not this route's job); dependent
     * configs (enrichment/job triggers, expectation/decision-rule targets, dataset references) are
     * best-effort rewritten unless `rewriteDependents` is explicitly `false`. 409 while the pipeline is
     * active or currently running; 409 if `newId` is already taken.
     */
    rename(name: string, newId: string, opts: { newName?: string; rewriteDependents?: boolean } = {}) {
        return this.http.post<PipelineRenameResult>(apiUrl(`/pipelines/${encodeURIComponent(name)}/rename`), {
            newId,
            ...(opts.newName ? { newName: opts.newName } : {}),
            ...(opts.rewriteDependents === false ? { rewriteDependents: false } : {}),
        });
    }

    /** The pipeline-level `produces`/`reference` block, read straight off the config file (D8). */
    settings(name: string): Observable<PipelineSettings> {
        return this.http.get<PipelineSettings>(apiUrl(`/pipelines/${encodeURIComponent(name)}/settings`));
    }

    /** Write the `produces`/`reference` block. `reference: null` clears a previously-saved one. */
    saveSettings(name: string, settings: PipelineSettings): Observable<PipelineSettingsResult> {
        return this.http.post<PipelineSettingsResult>(
            apiUrl(`/pipelines/${encodeURIComponent(name)}/settings`),
            settings,
        );
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

    /**
     * Dry-run a bounded sample through an authored pipeline (per-node + per-sink counts; no production write).
     *
     * <p>Pass `candidate` to preview a DRAFT graph instead of the stored one: it is parsed and validated
     * through the same gate the save route uses (an invalid draft 422s identically) and never written
     * anywhere. When present the stored flow is not consulted at all, so a draft for an id with no stored
     * pipeline previews too — which is what lets a caller with no pipeline of its own synthesize a
     * throwaway graph purely to see what some rules would produce.
     */
    dryRunAuthored(
        id: string,
        sampleRows: Record<string, unknown>[],
        candidate?: AuthoredPipeline,
    ): Observable<PipelineDryRunResult> {
        return this.http.post<PipelineDryRunResult>(
            apiUrl(`/pipelines/authored/${encodeURIComponent(id)}/dry-run`),
            candidate ? { sampleRows, pipeline: candidate } : { sampleRows },
        );
    }

    /** Test a single processor node over a bounded sample (no production write) — the per-processor test. */
    testNode(type: string, id: string): Observable<ComponentTestResult> {
        return this.http.post<ComponentTestResult>(
            apiUrl(`/components/${encodeURIComponent(type)}/${encodeURIComponent(id)}/test`),
            {},
        );
    }

    /**
     * Run the authored pipeline up to {nodeId} over the chosen inbox `files`, materializing to a scratch store
     * (no production write). Drives the editor's run-to-here loop — per-relation counts + the Parquet landed.
     */
    runToNode(id: string, nodeId: string, files: string[]): Observable<PipelineRunResult> {
        return this.http.post<PipelineRunResult>(
            apiUrl(`/pipelines/authored/${encodeURIComponent(id)}/run`),
            { files },
            { params: { to: nodeId } },
        );
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
