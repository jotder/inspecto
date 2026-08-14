import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DbBrowserService, DbResult } from 'app/inspecto/api/db-browser.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import {
    ColumnMeta,
    ColumnType,
    QueryModel,
    compileSql,
    dbColumnType,
    evaluateRows,
    inferColumns,
} from 'app/inspecto/query';
import { runSql } from 'app/inspecto/data-table/sql/sql-run';
import { SAMPLE_SOURCES } from 'app/inspecto/mock/sample-sources';

/**
 * The **rows seam**: what a Dataset's `sourceName` actually resolves to. Live (the default —
 * `environment.mockStudio` false, the same flag that decides whether datasets themselves come from the
 * real ComponentStore) that is the real store, read over `/db/table` (or `/db/query` when the dataset
 * embeds a Query Core model, compiled by {@link compileSql}); offline it is the store's entry in
 * {@link SAMPLE_SOURCES}, filtered in-browser by {@link evaluateRows}. Same signature either way.
 *
 * This is the layer under {@link DatasetResultService}: that one runs a {@link QuerySpec} (offline over
 * rows, live over `/bi/query`), this one supplies the rows a screen reads directly — drill-throughs,
 * filter-value pickers, SQL previews, column pickers.
 *
 * ⚠ **A result is a PAGE, not the store.** `truncated` says the store held more than `limit`; a consumer
 * that folds, counts or aggregates over `rows` must say so rather than imply completeness — no real
 * endpoint returns unbounded rows, which is exactly why the old synchronous `SAMPLE_SOURCES[name]`
 * lookups could not be repointed in place.
 *
 * ⚠ **Not every sample-row read is a defect this replaces.** Link Analysis, Geo and Reconciliation each
 * already pair a server call with a sample fold as its *offline arm* (`EntityProjectionGraphSource`,
 * `PointProjectionGeoSource`, `ReconExecService`); those folds are correct as written. Converting them to
 * this service would put a second server round-trip behind a path that already has one.
 */
@Injectable({ providedIn: 'root' })
export class DatasetRowsService {
    private db = inject(DbBrowserService);
    private cache = new Map<string, Promise<DatasetRows>>();

    /**
     * Resolve one page of a Dataset's rows. Never throws — a missing store or a server error comes back
     * as `{rows: [], error}` so a pane can say why it is empty instead of silently rendering nothing.
     * An identical request already in flight or resolved is reused.
     */
    rows(ds: RowSourceRef, limit = DEFAULT_ROW_LIMIT): Promise<DatasetRows> {
        const key = `${ds.sourceName}|${limit}|${ds.query ? JSON.stringify(ds.query) : ''}`;
        const cached = this.cache.get(key);
        if (cached) return cached;
        const promise = environment.mockStudio ? Promise.resolve(sampleRows(ds)) : this.remoteRows(ds, limit);
        this.cache.set(key, promise);
        // A failed read shouldn't stick forever — drop it so the next call retries instead of replaying it.
        promise.then((r) => (r.error ? this.cache.delete(key) : undefined));
        return promise;
    }

    /**
     * The columns a screen may offer for `ds` — its declared columns when it has them (authored and
     * role-tagged, so no round-trip), else the 1-row probe of the store behind it.
     */
    async columns(ds: RowSourceRef): Promise<ColumnMeta[]> {
        const declared = declaredColumns(ds);
        if (declared.length) return declared;
        return (await this.rows(ds, 1)).columns;
    }

    /**
     * Run authored SQL against one store: `/db/query` live (guarded server-side — a single read-only
     * statement), the in-browser AlaSQL engine over the store's sample page offline. Not cached: the SQL
     * is being edited, so every run is a new question. Never throws.
     */
    async sql(sourceName: string, sql: string, limit = DEFAULT_ROW_LIMIT): Promise<DatasetRows> {
        if (environment.mockStudio) {
            const page = sampleRows({ sourceName });
            if (page.error) return page;
            const res = await runSql(sql, sourceName, page.rows);
            return res.ok
                ? { rows: res.rows, columns: inferColumns(res.rows), truncated: false }
                : { rows: [], columns: [], truncated: false, error: res.error };
        }
        try {
            return fromDbResult(await firstValueFrom(this.db.query({ table: sourceName, sql, limit })), []);
        } catch (e) {
            return { rows: [], columns: [], truncated: false, error: apiErrorMessage(e, 'The query failed.') };
        }
    }

    /** Drop every cached page — call when the data behind a store may have changed. */
    clear(): void {
        this.cache.clear();
    }

    /** Live: the real store over `/db/query` (a virtual dataset's model) or `/db/table` (everything else). */
    private async remoteRows(ds: RowSourceRef, limit: number): Promise<DatasetRows> {
        const declared = declaredColumns(ds);
        try {
            const res = await firstValueFrom(
                ds.query
                    ? this.db.query({
                          table: ds.sourceName,
                          sql: compileSql(ds.query, { name: ds.sourceName, rows: [], columns: declared }),
                          limit,
                      })
                    : this.db.table({ name: ds.sourceName, limit }),
            );
            return fromDbResult(res, declared);
        } catch (e) {
            return {
                rows: [],
                columns: declared,
                truncated: false,
                error: apiErrorMessage(e, `Could not read the store "${ds.sourceName}".`),
            };
        }
    }
}

/** One page of a Dataset's rows, plus what the consumer needs to be honest about it. */
export interface DatasetRows {
    rows: Record<string, unknown>[];
    columns: ColumnMeta[];
    /** The store held more rows than `limit` — the page is a sample, not the whole relation. */
    truncated: boolean;
    /** Why the page is empty (unknown store, server error). Absent when the read succeeded. */
    error?: string;
}

/**
 * What {@link DatasetRowsService} needs of a Dataset. Declared structurally rather than as the Studio
 * `Dataset` type so shared code does not import a feature (`angular-ui` §3) — a `Dataset` satisfies it.
 */
export interface RowSourceRef {
    sourceName: string;
    /** A virtual/materialized dataset's Query Core model; compiled to SQL live, evaluated offline. */
    query?: QueryModel | null;
    columns?: readonly { name: string; type: ColumnType }[];
}

/**
 * The default page size. Deliberately below the backend's 5000 clamp and above its own 200 default: big
 * enough that the client-side folds over a page stay representative, small enough to stay a page.
 */
export const DEFAULT_ROW_LIMIT = 1000;

function declaredColumns(ds: RowSourceRef): ColumnMeta[] {
    return (ds.columns ?? []).map((c) => ({ name: c.name, type: c.type }));
}

/**
 * Offline: the store's sample rows, through the dataset's own model when it has one. Exported because
 * the *offline arms* of the three server-first folds (Link Analysis, Geo, Reconciliation) need exactly
 * this and nothing else — they already made the server call, so they must not go through the service and
 * make a second one. Before this there were two divergent copies of it; the Reconciliation one dropped
 * the column metadata, which silently compared numbers and dates as strings.
 */
export function sampleDatasetRows(ds: RowSourceRef | null): Record<string, unknown>[] {
    return ds ? sampleRows(ds).rows : [];
}

function sampleRows(ds: RowSourceRef): DatasetRows {
    const raw = SAMPLE_SOURCES[ds.sourceName];
    if (!raw) {
        return {
            rows: [],
            columns: declaredColumns(ds),
            truncated: false,
            error: `No offline sample rows for the store "${ds.sourceName}".`,
        };
    }
    const declared = declaredColumns(ds);
    const columns = declared.length ? declared : inferColumns(raw);
    // The model defines a virtual/materialized dataset, so it applies whenever one is set; a physical
    // dataset carries `query: null` by construction (`buildDataset`).
    const rows = ds.query ? evaluateRows(ds.query, { name: ds.sourceName, rows: raw, columns }) : raw;
    return { rows, columns, truncated: false };
}

/** Map a `/db/*` result to the seam's shape — DuckDB type names become Query Core {@link ColumnType}s. */
function fromDbResult(res: DbResult, declared: ColumnMeta[]): DatasetRows {
    const served = res.columns.map((c) => ({
        name: c.name,
        type: dbColumnType(c.type),
        cardinality: c.cardinality ?? undefined,
    }));
    return {
        rows: res.rows,
        // The server describes what it actually returned; a declared list can be stale or narrower.
        columns: served.length ? served : declared,
        truncated: res.statistics?.truncated ?? false,
    };
}
