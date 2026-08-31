import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { DbBrowserService, DbResult } from 'app/inspecto/api/db-browser.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { ColumnMeta, ColumnType, QueryModel, compileSql, dbColumnType } from 'app/inspecto/query';

/**
 * The **rows seam**: what a Dataset's `sourceName` actually resolves to. It is always the real
 * store, read over `/db/table` (or `/db/query` when the dataset embeds a Query Core model, compiled by
 * {@link compileSql}).
 *
 * This is the layer under {@link DatasetResultService}: that one runs a {@link QuerySpec} over
 * `/bi/query`, this one supplies the rows a screen reads directly — drill-throughs, filter-value
 * pickers, SQL previews, column pickers.
 *
 * ⚠ **A result is a PAGE, not the store.** `truncated` says the store held more than `limit`; a consumer
 * that folds, counts or aggregates over `rows` must say so rather than imply completeness — no real
 * endpoint returns unbounded rows.
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
        // 🔴 A Dataset with no source names no store, so there is nothing to ask for. Saying so beats
        // issuing `GET /db/table?limit=1` with NO `name`, which 400s and leaves a column picker silently
        // empty with no clue why (BACKLOG MOCK-GONE-1(b)). The seam refuses rather than the caller,
        // because every consumer reaches the store through here.
        if (!ds.sourceName?.trim())
            return Promise.resolve({
                rows: [],
                columns: declaredColumns(ds),
                truncated: false,
                error: 'This Dataset names no store, so its rows cannot be read. Set its source.',
            });
        const key = `${ds.sourceName}|${limit}|${ds.query ? JSON.stringify(ds.query) : ''}`;
        const cached = this.cache.get(key);
        if (cached) return cached;
        const promise = this.remoteRows(ds, limit);
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
     * The stores a Dataset may read — the business groups of `/db/catalog`.
     * Operational (`ops:*`) groups are excluded: they are the control plane's own tables, not business
     * data, and `/db/table` needs their group id, which a Dataset's `sourceName` cannot carry.
     */
    async stores(): Promise<StoreList> {
        try {
            const catalog = await firstValueFrom(this.db.catalog());
            const names = (catalog.groups ?? [])
                .filter((g) => !g.id.startsWith('ops:'))
                .flatMap((g) => g.tables.map((t) => t.name));
            return { names: [...new Set(names)].sort() };
        } catch (e) {
            return { names: [], error: apiErrorMessage(e, 'Could not list the stores in this space.') };
        }
    }

    /**
     * Run authored SQL against one store over `/db/query` (guarded server-side — a single read-only
     * statement). Not cached: the SQL
     * is being edited, so every run is a new question. Never throws.
     */
    async sql(sourceName: string, sql: string, limit = DEFAULT_ROW_LIMIT): Promise<DatasetRows> {
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

/** The stores a Dataset may name; `error` set when the catalog could not be read (never both empty-silent). */
export interface StoreList {
    names: string[];
    error?: string;
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
