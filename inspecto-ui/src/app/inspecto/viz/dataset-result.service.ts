import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { BiFilter, BiQueryBody, BiQueryService } from 'app/inspecto/api/bi-query.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { ColumnMeta, Condition, ConditionGroup, columnType } from 'app/inspecto/query';
import { SqlRunResult } from 'app/inspecto/data-table/sql/sql-run';
import { QuerySpec } from './viz-types';

/**
 * De-dupes/caches identical {@link QuerySpec} runs so N widgets sharing a dataset + cross-filter compute
 * once, not N times (e.g. dashboard tiles, gallery thumbnails). The spec is mapped to validated
 * identifiers and executed server-side via {@link BiQueryService} (`POST /bi/query`). A spec that cannot
 * cross the wire faithfully (a named-measure SQL expression, an OR filter branch) fails honestly instead
 * of silently dropping terms.
 */

@Injectable({ providedIn: 'root' })
export class DatasetResultService {
    private bi = inject(BiQueryService);
    private cache = new Map<string, Promise<SqlRunResult>>();

    /** Run `spec` — an identical spec already in flight or resolved is reused, not re-run. */
    run(spec: QuerySpec, cols: ColumnMeta[] = []): Promise<SqlRunResult> {
        const key = hashSpec(spec);
        const cached = this.cache.get(key);
        if (cached) return cached;
        const promise = this.runRemote(spec, cols);
        this.cache.set(key, promise);
        // A failed run shouldn't stick forever — drop it so the next call retries instead of replaying the error.
        promise.catch(() => this.cache.delete(key));
        return promise;
    }

    /** Drop all cached results — call when the data a spec would read over has changed. */
    clear(): void {
        this.cache.clear();
    }

    /** Execute the spec server-side. Never throws — errors come back as `{ok:false}` results. */
    private async runRemote(spec: QuerySpec, cols: ColumnMeta[]): Promise<SqlRunResult> {
        const body = biQueryBody(spec, cols);
        if (!body) {
            return {
                ok: false,
                rows: [],
                error:
                    'This widget uses features (a named-measure SQL expression, an OR filter, or an ' +
                    'empty projection) that the BI endpoint cannot run.',
            };
        }
        try {
            const r = await firstValueFrom(this.bi.run(body));
            return { ok: true, rows: r.rows };
        } catch (e) {
            return { ok: false, rows: [], error: apiErrorMessage(e, 'BI query failed on the server.') };
        }
    }
}

/**
 * Map a {@link QuerySpec} to the `/bi/query` wire body, or `null` when it cannot cross faithfully.
 * The wire takes validated `{agg, field}` identifier pairs and a flat implicit-AND filter list — never
 * SQL text — so: a named-measure `expression` (no structured `agg` origin) is unmappable; nested AND
 * groups flatten; a real OR branch is unmappable (dropping it would change the numbers, which is worse
 * than an error). Filter values are typed through `cols` (the backend renders literals by JSON type).
 */
export function biQueryBody(spec: QuerySpec, cols: ColumnMeta[] = []): BiQueryBody | null {
    if (!spec.measures.length && !spec.groupBy.length) return null; // the wire needs at least one of them
    const measures: BiQueryBody['measures'] = [];
    for (const m of spec.measures) {
        if (!m.agg) return null; // named-measure SQL expression — not expressible on the wire
        measures.push(m.agg === 'count' ? { agg: 'count' } : { agg: m.agg, field: m.field ?? m.id });
    }
    const filters = flattenFilters(spec.filters ?? null, cols);
    if (filters === null) return null;

    const body: BiQueryBody = { dataset: spec.datasetId };
    if (measures.length) body.measures = measures;
    if (spec.groupBy.length) body.groupBy = spec.groupBy;
    // Only grains on grouped columns cross — the server refuses the rest, and a stale grain on a channel
    // whose field has since changed would otherwise 422 the whole widget.
    const grains = Object.entries(spec.grains ?? {}).filter(([f]) => spec.groupBy.includes(f));
    if (grains.length) body.grains = Object.fromEntries(grains);
    if (filters.length) body.filters = filters;
    if (spec.orderBy?.length) body.orderBy = spec.orderBy;
    if (spec.limit != null) body.limit = spec.limit;
    return body;
}

/** Flatten a nested condition tree to the wire's implicit-AND list; `null` = not faithfully mappable. */
function flattenFilters(group: ConditionGroup | null, cols: ColumnMeta[]): BiFilter[] | null {
    if (!group || !group.items.length) return [];
    // An OR of a single item is that item; a real OR branch cannot be expressed as an AND list.
    if (group.op === 'OR' && group.items.length > 1) return null;
    const out: BiFilter[] = [];
    for (const item of group.items) {
        if (item.kind === 'group') {
            const nested = flattenFilters(item, cols);
            if (nested === null) return null;
            out.push(...nested);
            continue;
        }
        const terms = filterTerms(item, cols);
        if (terms === null) return null;
        out.push(...terms);
    }
    return out;
}

/** One UI condition → its wire term(s); `between` decomposes into `>=` + `<=` under the implicit AND. */
function filterTerms(c: Condition, cols: ColumnMeta[]): BiFilter[] | null {
    const typed = (raw: string | undefined): unknown => typedValue(raw, columnType(cols, c.field));
    switch (c.operator) {
        case '=':
        case '!=':
        case '<':
        case '<=':
        case '>':
        case '>=':
            return [{ field: c.field, op: c.operator, value: typed(c.value) }];
        case 'contains':
            return [{ field: c.field, op: 'like', value: `%${c.value ?? ''}%` }];
        case 'startsWith':
            return [{ field: c.field, op: 'like', value: `${c.value ?? ''}%` }];
        case 'endsWith':
            return [{ field: c.field, op: 'like', value: `%${c.value ?? ''}` }];
        case 'in':
            return [{ field: c.field, op: 'in', value: (c.value ?? '').split(',').map((v) => typed(v.trim())) }];
        case 'between':
            return [
                { field: c.field, op: '>=', value: typed(c.value) },
                { field: c.field, op: '<=', value: typed(c.value2) },
            ];
        case 'isNull':
            return [{ field: c.field, op: 'isNull' }];
        case 'isNotNull':
            return [{ field: c.field, op: 'notNull' }];
        default:
            return null; // an operator this mapper doesn't know — fail honest, never guess
    }
}

/** Type a raw text value by its column so the backend renders the right SQL literal. */
function typedValue(raw: string | undefined, type: string): unknown {
    if (raw == null) return raw;
    if (type === 'number') {
        const n = Number(raw);
        return Number.isNaN(n) ? raw : n;
    }
    if (type === 'boolean') return raw === 'true';
    return raw;
}

/**
 * A stable cache key for a {@link QuerySpec}. Relies on the query builders (`buildXyQuery` /
 * `buildTableQuery` / `buildValueQuery`) always constructing the spec object with the same key order —
 * true today, so a full stable-stringify (sorting keys) isn't needed for this M1 scope.
 */
function hashSpec(spec: QuerySpec): string {
    return JSON.stringify(spec);
}
