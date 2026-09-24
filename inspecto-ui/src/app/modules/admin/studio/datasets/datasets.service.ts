import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService, apiUrl } from 'app/inspecto/api';
import { CalculatedColumn, Dataset, DatasetColumn, DatasetConfig, DatasetKind, NamedMeasure } from './dataset-types';
import { isSharedRef } from 'app/inspecto/api/shared-ref';

/**
 * Dataset store — persists {@link Dataset}s through the component registry as the `dataset` component type
 * (mock-served by the unified mock store today; real persistence once the backend storage enum is widened).
 * Mirrors `inspecto/rule/rules.service.ts` — the component model means a dataset is "just a component" with a
 * {@link DatasetConfig} body.
 */
@Injectable({ providedIn: 'root' })
export class DatasetsService {
    private components = inject(ComponentsService);
    private http = inject(HttpClient);

    list(): Observable<Dataset[]> {
        return this.components.list('dataset').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    get(id: string): Observable<Dataset> {
        return this.components.get('dataset', id).pipe(map((d) => fromContent(d.name, d.content)));
    }

    /** A {@link Dataset} from raw stored/bundle content — the read path's own mapping, for an imported draft. */
    fromContent(id: string, content: Record<string, unknown>): Dataset {
        return fromContent(id, content);
    }

    /** The content {@link save} writes for a {@link Dataset} — what an imported draft's pre-Save re-check judges. */
    toContent(ds: Dataset): Record<string, unknown> {
        return toContent(ds);
    }

    /** Create by default; pass `{update: true}` when editing an existing dataset — the backend 409s a
     *  create on an existing id (id is immutable in the editors, so update never renames). */
    save(ds: Dataset, opts?: { update?: boolean; ifMatch?: string }): Observable<Dataset> {
        const req$ = opts?.update
            ? opts.ifMatch
                ? this.components.update('dataset', ds.id, toContent(ds), { ifMatch: opts.ifMatch })
                : this.components.update('dataset', ds.id, toContent(ds))
            : this.components.create('dataset', { id: ds.id, ...toContent(ds) });
        return req$.pipe(map(() => ds));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('dataset', id);
    }

    /**
     * Materialize this dataset into `target` — a Parquet snapshot that registers as a Dataset of its own
     * (`POST /datasets/{id}/materialize`, STUDIO-HALVES-1).
     *
     * ⚠ **Asynchronous.** A 202 means the run was ADMITTED, not that anything was written — the default
     * snapshot is a million rows. The `runId` is the handle to the run; the server also sends a
     * `Location` header pointing at it, which this deliberately does not read (no caller in this SPA
     * observes response headers, and the body already carries the id).
     *
     * ⚠ The interesting failures are not 500s: **409** means a materialize of the same target is already
     * in flight, and **422** an unsafe target or one equal to the source. Both are states to show the
     * operator, not noise to swallow. ⛔ The 409 is NOT `CONFLICT_STALE_VERSION`, so it must not go
     * through `isStaleVersionError` and its "reload to get their version" copy — nothing here is stale.
     */
    materialize(id: string, target: string): Observable<MaterializeAccepted> {
        return this.http.post<MaterializeAccepted>(apiUrl(`/datasets/${encodeURIComponent(id)}/materialize`), {
            target,
        });
    }
}

/** The 202 body of `POST /datasets/{id}/materialize`. */
export interface MaterializeAccepted {
    runId: string;
    dataset: string;
    target: string;
    status: string;
}

function toContent(d: Dataset): Record<string, unknown> {
    const config: DatasetConfig = {
        kind: d.kind,
        sourceName: d.sourceName,
        query: d.query ?? null,
        physicalRef: d.physicalRef ?? null,
        columns: d.columns,
        measures: d.measures,
        calculated: d.calculated,
        viz: d.viz ?? null,
    };
    return { name: d.name, ...config } as Record<string, unknown>;
}

function fromContent(name: string, content: Record<string, unknown>): Dataset {
    return {
        id: name,
        name: (content['name'] as string) ?? name,
        kind: (content['kind'] as DatasetKind) ?? 'virtual',
        // ⛔ Never default this to an INVENTED source name. The old `?? 'data'` named a key that did
        // not exist, so a dataset written without a sourceName read `[]` rows in every consumer and was
        // indistinguishable from an empty store.
        //
        // 🔴 `physicalRef` is different, and IS the honest answer: go-live registration writes the
        // landed store's name there (`DatasetRegistrationService` — `physicalRef: store`) and writes no
        // `sourceName` at all. Every such dataset therefore had a blank source, and the rows seam built
        // `GET /db/table?limit=1` with NO `name` → 400, leaving the column pickers silently empty
        // (BACKLOG MOCK-GONE-1(b), found by driving the real app 2026-08-31).
        // ⚠ A `shared/<owner>/<item>` ref is NOT a local store name — it spans spaces and is resolved
        // server-side, so it is excluded here rather than passed to `/db/table` as a table name.
        sourceName:
            (content['sourceName'] as string) ||
            (isSharedRef(content['physicalRef'] as string) ? '' : ((content['physicalRef'] as string) ?? '')) ||
            '',
        query: (content['query'] as Dataset['query']) ?? null,
        physicalRef: (content['physicalRef'] as string | null) ?? null,
        columns: (content['columns'] as DatasetColumn[]) ?? [],
        measures: (content['measures'] as NamedMeasure[]) ?? [],
        calculated: (content['calculated'] as CalculatedColumn[]) ?? [],
        viz: (content['viz'] as Dataset['viz']) ?? null,
    };
}
