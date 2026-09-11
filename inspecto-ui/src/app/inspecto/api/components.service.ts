import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';
import { Finding } from './models';

/** The reusable component-registry kinds (mirrors backend `ComponentStore.WRITABLE_TYPES`). `rule-template`
 *  backs the data-table Pro Max saved query templates; `dataset`/`widget`/`dashboard` back Studio;
 *  `requirement` backs the Business Requirements-intake queue (C1). Every kind here is backend-persisted —
 *  keep this union in lockstep with `WRITABLE_TYPES`, since a kind the server does not know 400s on every
 *  list/create/remove (that is exactly how `rule-template` stayed broken; the mock served it regardless).
 *  None of the extras are in {@link COMPONENT_TYPES} (not pipeline-node palette components). */
export type ComponentType =
    | 'grammar'
    | 'schema'
    | 'mapping'
    | 'transform'
    | 'sink'
    | 'rule-template'
    | 'dataset'
    | 'query'
    | 'widget'
    | 'dashboard'
    | 'requirement'
    | 'reconciliation'
    | 'link-analysis-view'
    | 'geo-map-view'
    | 'pattern-pack';

/** The component kinds, in palette order, for the list/editor. `schema`/`mapping` open the S5 grid
 *  editors. ⚠ A schema EDIT saves through this service's {@link ComponentsService.update} like every
 *  other kind; only a schema CREATE goes to `/config/write` (SCHEMA-DIALOG-IFMATCH-1 — the two routes
 *  write different files, and an edit belongs on the registry component the list serves). */
export const COMPONENT_TYPES: ComponentType[] = ['grammar', 'schema', 'mapping', 'transform', 'sink'];

/**
 * One registry component (GET /components/{type}[/{id}]) — its kind, in-file identity, `<type>/<id>` ref,
 * and the parsed `.toon` content map. The content shape varies by kind (a grammar's CSV dialect, a schema's
 * typed fields, a transform's operator config, a sink's store/format/partitions).
 */
export interface ComponentDef {
    type: string;
    name: string;
    ref: string;
    content: Record<string, unknown>;
    /**
     * The content hash the server stamps on every component doc — this component's optimistic-concurrency
     * handle, and the value to pass back as {@link ComponentsService.update}'s `ifMatch`. The list route
     * serves it too, so an editor opened from a list already holds one without a second read.
     *
     * ⚠ Optional because it is absent from a locally-constructed `ComponentDef` (a dialog closing with the
     * content it just saved); a caller with no hash writes unconditionally, exactly as before.
     */
    contentHash?: string;
}

/** One archived prior copy of a component (MET-5): `GET /components/{type}/{id}/versions`. */
export interface ComponentVersion {
    type: string;
    id: string;
    version: number;
    /** ISO-8601 time this copy was last the live version (best-effort from the file's saved time). */
    savedAt: string | null;
    contentHash: string;
    content: Record<string, unknown>;
}

/** One produced relation in a preview (transform / schema): the rel key, its row count, and a bounded sample. */
/** One `{name, type}` pair of a derived output schema — the type is DuckDB's own, verbatim. */
export interface DerivedColumn {
    name: string;
    type: string;
}

export interface RelationPreview {
    rel: string;
    rowCount: number;
    rows: Record<string, unknown>[];
    /**
     * The DERIVED output schema — DuckDB's `DESCRIBE` of the produced relation, so the author never
     * restates it. ⚠ Empty offline: the mock has no SQL engine and will not guess a type.
     */
    columnTypes?: DerivedColumn[];
}

/** Transform/schema dry-run result (POST /components/{transform,schema}/{id}/test). */
export interface RelationsPreview {
    inputColumns: string[];
    relations: RelationPreview[];
    /**
     * The statements the config compiled to, in execution order — what actually ran, recorded rather
     * than re-derived. ⚠ Empty offline, for the same reason as `columnTypes`.
     */
    sql?: string[];
}

/** Grammar parse result (POST /components/grammar/{id}/test). */
export interface GrammarPreview {
    columns: string[];
    rowCount: number;
    rows: Record<string, unknown>[];
    rejectedRows: number;
}

/** Sink scratch-validate result (POST /components/sink/{id}/test). */
export interface SinkPreview {
    store: string | null;
    rowCount: number;
    rows: Record<string, unknown>[];
    warnings: string[];
}

/** Mapping-rule validation result (POST /components/mapping/validate, S6b) — no write, ever. */
export interface MappingValidation {
    type: 'mapping';
    /** Anchored to `rules[N].targetColumn` / `.sourceExpression` / `.transformType`, or blank for the set. */
    findings: Finding[];
    clean: boolean;
}

/** One node in a hierarchical parse preview (ASN.1 / JSON / XML) — a labelled value with optional children. */
export interface ParserTreeNode {
    label: string;
    /** The decoded value at a leaf (absent for container nodes). */
    value?: string;
    /** A type tag shown as a chip (e.g. `SEQUENCE`, `string`, element name). */
    type?: string;
    children?: ParserTreeNode[];
}

/** Flat parse preview (DSV / TXT / Parquet / XLSX / HTML / Other) — rows for the rich data grid. */
export interface ParserTablePreview {
    kind: 'table';
    columns: string[];
    rows: Record<string, unknown>[];
    rowCount: number;
    rejectedRows: number;
    /** B2, additive: per-column INFERRED types from the server's auto_detect sniff — advisory
     *  (ingest stays all-VARCHAR); absent for formats without a sniff and from old servers. */
    columnTypes?: { name: string; type: string }[];
}

/** Hierarchical parse preview — a forest of records for the collapsible tree view. */
export interface ParserTreePreview {
    kind: 'tree';
    recordCount: number;
    nodes: ParserTreeNode[];
}

/** A parse-preview result — flat for tabular formats, tree for hierarchical ones (discriminated on `kind`). */
export type ParserPreview = ParserTablePreview | ParserTreePreview;

/**
 * Component registry CRUD + per-component dry-run/test (T18/T19, §7.1–7.2). Generalises the connection
 * write pattern to the non-secret kinds; writes are write-root gated (503 when disabled). The `/test`
 * endpoints run the component over a sample through the production logic on a throwaway DuckDB (no write).
 */
@Injectable({ providedIn: 'root' })
export class ComponentsService {
    private http = inject(HttpClient);

    /** Components of one kind (empty when no write root is configured). */
    list(type: ComponentType): Observable<ComponentDef[]> {
        return this.http.get<ComponentDef[]>(apiUrl(`/components/${type}`));
    }

    /** One component by kind/id. */
    get(type: ComponentType, id: string): Observable<ComponentDef> {
        return this.http.get<ComponentDef>(apiUrl(`/components/${type}/${encodeURIComponent(id)}`));
    }

    /** Create a component (write-root gated). `content` must include the `id`. 503/409/422 on failure. */
    create(type: ComponentType, content: Record<string, unknown>): Observable<ComponentDef> {
        return this.http.post<ComponentDef>(apiUrl(`/components/${type}`), content);
    }

    /**
     * Replace a component's content (write-root gated). 503/404/422 on failure, and 409
     * `CONFLICT_STALE_VERSION` when `ifMatch` no longer matches the stored content.
     *
     * `ifMatch` is the {@link ComponentDef.contentHash} the read served — pass it and a save that would
     * clobber a concurrent edit is refused instead of silently winning. ⚠ The response carries the
     * POST-save hash; a caller that keeps sending the original has its SECOND save refused as stale.
     *
     * 🔴 Pass the BARE hash, not an ETag: the server's tag is `"sha256:<hash>"` (quotes included) and it
     * compares the header for exact equality, so a raw `contentHash` would match nothing and refuse EVERY
     * save. The tag is built here, in one place, rather than at each call site — the list route serves
     * only the bare hash, so there is no ETag header for a list-opened editor to echo back.
     *
     * `compatibility: 'none'` is the schema BACKWARD-gate escape hatch. ⚠ It is a QUERY parameter here,
     * not a body key, and deliberately so: a component body *is* the content, so a `compatibility` key
     * placed in it would be persisted into the schema itself.
     */
    update(
        type: ComponentType,
        id: string,
        content: Record<string, unknown>,
        opts?: { ifMatch?: string; compatibility?: 'none' },
    ): Observable<ComponentDef> {
        return this.http.put<ComponentDef>(apiUrl(`/components/${type}/${encodeURIComponent(id)}`), content, {
            ...(opts?.compatibility ? { params: { compatibility: opts.compatibility } } : {}),
            ...(opts?.ifMatch ? { headers: { 'If-Match': `"sha256:${opts.ifMatch}"` } } : {}),
        });
    }

    /** Delete a component (write-root gated). 503/404/409 (in use) on failure. */
    remove(type: ComponentType, id: string): Observable<unknown> {
        return this.http.delete(apiUrl(`/components/${type}/${encodeURIComponent(id)}`));
    }

    /** Prior saved copies of a component, newest first (MET-5). Empty when none / writes disabled. */
    versions(type: ComponentType, id: string): Observable<ComponentVersion[]> {
        return this.http.get<ComponentVersion[]>(apiUrl(`/components/${type}/${encodeURIComponent(id)}/versions`));
    }

    /** Restore an archived version as the current component (write-root gated). Returns the restored component. */
    restore(type: ComponentType, id: string, version: number): Observable<ComponentDef> {
        return this.http.post<ComponentDef>(
            apiUrl(`/components/${type}/${encodeURIComponent(id)}/versions/${version}/restore`),
            {},
        );
    }

    /** Parse raw `sampleText` with a grammar's dialect (scratch-only). */
    testGrammar(id: string, sampleText: string): Observable<GrammarPreview> {
        return this.http.post<GrammarPreview>(apiUrl(`/components/grammar/${encodeURIComponent(id)}/test`), {
            sampleText,
        });
    }

    /** Run a transform over sample rows through the production RowShaper (scratch-only). */
    testTransform(id: string, sampleRows: Record<string, unknown>[]): Observable<RelationsPreview> {
        return this.http.post<RelationsPreview>(apiUrl(`/components/transform/${encodeURIComponent(id)}/test`), {
            sampleRows,
        });
    }

    /** Scratch-validate a sink against sample rows (store/format/partition checks; no write). */
    testSink(id: string, sampleRows: Record<string, unknown>[]): Observable<SinkPreview> {
        return this.http.post<SinkPreview>(apiUrl(`/components/sink/${encodeURIComponent(id)}/test`), { sampleRows });
    }

    /**
     * Which inline-preview family, if any, will accept a node of this type — the **route's own
     * acceptance rule**, not a category lookup, which is why it lives beside the two calls below.
     *
     * ⛔ Do not "improve" this into a `PipelineNodeCategory` test. `enrichment` is `category: 'TRANSFORM'`
     * in the served catalog but its type carries no `transform.` prefix, and `previewInlineTransform`
     * **422s** a config whose `type` is not `transform.*` — so a category-keyed predicate would offer a
     * test that can only ever fail. If enrichment should become testable, the server accepts it first.
     */
    static previewFamilyFor(type: string): 'transform' | 'sink' | null {
        if (type.startsWith('transform.')) return 'transform';
        if (type.startsWith('sink.')) return 'sink';
        return null;
    }

    /**
     * Run an **INLINE** transform config over sample rows — `POST /components/transform/preview`.
     * The pipeline editor authors node configs inline, so before this route only a *registered*
     * component could be tried: exactly the config an operator is in the middle of writing was the one
     * they could not test. ⚠ The body's `config` must carry the node's own `type`, or the route 422s.
     */
    previewTransform(
        config: Record<string, unknown>,
        sampleRows: Record<string, unknown>[],
    ): Observable<RelationsPreview> {
        return this.http.post<RelationsPreview>(apiUrl('/components/transform/preview'), { config, sampleRows });
    }

    /** Scratch-validate an **INLINE** sink config against sample rows (no write). */
    previewSink(config: Record<string, unknown>, sampleRows: Record<string, unknown>[]): Observable<SinkPreview> {
        return this.http.post<SinkPreview>(apiUrl('/components/sink/preview'), { config, sampleRows });
    }

    /**
     * Validate draft mapping rules WITHOUT writing (S6b) — the import loop's gate. Findings are
     * anchored to `rules[N].<key>`, which the grid editor maps onto cells. Server-side on purpose:
     * every rule is a `TransformCompiler` precondition, and a browser-side copy would drift into
     * accepting a mapping the engine then rejects at run time.
     */
    validateMapping(rules: Record<string, unknown>[]): Observable<MappingValidation> {
        return this.http.post<MappingValidation>(apiUrl('/components/mapping/validate'), { rules });
    }

    /**
     * Derive output column schema and types for a SQL transform step via DuckDB's zero-row DESCRIBE
     * — `POST /components/transform/describe`. Catches syntax and binder errors live.
     */
    describeTransform(
        inputColumns: { name: string; type?: string }[],
        sql: string,
    ): Observable<{ columns: DerivedColumn[] }> {
        return this.http.post<{ columns: DerivedColumn[] }>(apiUrl('/components/transform/describe'), {
            inputColumns,
            sql,
        });
    }
}
