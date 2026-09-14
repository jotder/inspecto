import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { ComponentsService, apiUrl } from 'app/inspecto/api';
import { ParameterDef, QueryModel } from 'app/inspecto/query';
import { Query, QueryConfig, QueryType } from './query-types';

/**
 * The Result Set contract `POST /queries/{id}/run` shares with `POST /bi/query`.
 *
 * <p>⚠ Deliberately NOT reusing `BiQueryResult`: that one carries a `sql` field this route does not
 * return, and the columns here carry `cardinality`. Declaring the shape a route actually sends beats
 * borrowing a near-neighbour and reading a field that is always undefined.
 */
export interface QueryRunResult {
    resultSet: { columns: { name: string; type: string; role?: string; cardinality?: number }[]; rowCount: number };
    rows: Record<string, unknown>[];
    statistics: { rowCount: number; elapsedMs: number; truncated: boolean };
    renderings?: unknown;
    exportOptions?: string[];
}

/**
 * Query store — persists {@link Query}s through the component registry as the `query` component type
 * (mock-served by the unified mock store; real persistence once the backend storage enum is widened).
 * Mirrors `DatasetsService`: a query is "just a component" with a {@link QueryConfig} body.
 */
@Injectable({ providedIn: 'root' })
export class QueriesService {
    private components = inject(ComponentsService);
    private http = inject(HttpClient);

    /**
     * Run a SAVED query server-side — the engine resolves its declared `$params`, safety-gates the SQL
     * and executes it against the dataset's trusted relation.
     *
     * <p>⚠ This is a different thing from the editor's Run, and the difference is not cosmetic. The
     * editor previews an **unsaved draft** through `DatasetRowsService`, resolving parameters in the
     * browser — it has to, because a draft has no id to address. This runs **what is stored**, so it is
     * the only path that proves what the query will do when a job or a dashboard runs it.
     *
     * <p>⚠ Refusals are 422 and meaningful: a non-`sql` query, a query with no `text`, a failed SQL
     * safety check (carrying `findings`), or an unresolvable parameter. Surface the message; do not
     * retry.
     */
    run(
        id: string,
        body?: { limit?: number; offset?: number; parameters?: Record<string, unknown> },
    ): Observable<QueryRunResult> {
        return this.http.post<QueryRunResult>(apiUrl(`/queries/${encodeURIComponent(id)}/run`), body ?? {});
    }

    list(): Observable<Query[]> {
        return this.components.list('query').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    get(id: string): Observable<Query> {
        return this.components.get('query', id).pipe(map((d) => fromContent(d.name, d.content)));
    }

    /** Create by default; pass `{update: true}` when editing an existing query — the backend 409s a
     *  create on an existing id (id is immutable in the editor, so update never renames). */
    save(q: Query, opts?: { update?: boolean }): Observable<Query> {
        const req$ = opts?.update
            ? this.components.update('query', q.id, toContent(q))
            : this.components.create('query', { id: q.id, ...toContent(q) });
        return req$.pipe(map(() => q));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('query', id);
    }
}

function toContent(q: Query): Record<string, unknown> {
    const config: QueryConfig = {
        type: q.type,
        datasetId: q.datasetId ?? null,
        sourceName: q.sourceName,
        text: q.text ?? null,
        model: q.model ?? null,
        parameters: q.parameters,
    };
    return { name: q.name, description: q.description, ...config } as Record<string, unknown>;
}

function fromContent(name: string, content: Record<string, unknown>): Query {
    return {
        id: name,
        name: (content['name'] as string) ?? name,
        description: content['description'] as string | undefined,
        type: (content['type'] as QueryType) ?? 'sql',
        datasetId: (content['datasetId'] as string | null) ?? null,
        sourceName: content['sourceName'] as string | undefined,
        text: (content['text'] as string | null) ?? null,
        model: (content['model'] as QueryModel | null) ?? null,
        parameters: (content['parameters'] as ParameterDef[]) ?? [],
    };
}
